package my.oj.perf

import io.gatling.core.session.Session

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, LongAdder}

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * The pieces the open-arrival burst needs, and nothing the closed model already has.
 *
 * The closed model measures a population: N users pace themselves at an interval and the offered
 * rate falls out as N/interval, so when the server slows its users are still waiting on their last
 * response and the offered rate falls with it. An open model measures an arrival process: arrivals
 * are scheduled on a clock and pushed in whether or not the previous one has been answered. Those
 * are different claims about the system, and the earlier runs' offered rate - 382.3/s against the
 * 653.8/s the database recorded - is what a closed model reports when the stack is the bottleneck
 * rather than the schedule.
 *
 * So this file is the arrival side only: the plan, the prepared authentication contexts, and the
 * record of what was started. It is deliberately separate from `ApiLoad`, which is the shared
 * workload, and from the simulation, which decides how the arrivals are injected.
 */
object OpenBurstLoad {

  private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def isoUtc(epochMillis: Long): String = isoMillis.format(Instant.ofEpochMilli(epochMillis))

  /**
   * One authenticated session, prepared before the measured window and used by exactly one arrival.
   *
   * A cookie pair rather than a token because the API authenticates from the session: `POST
   * /api/login` sets the session attribute the submission endpoint reads, Spring Session keeps that
   * session in Redis, and the web nodes behind nginx share it - so a session established on either
   * node is valid on both and a cookie captured during preparation is replayable from the load
   * generator without any test-only endpoint or authentication bypass.
   */
  final case class AuthContext(id: Int, userName: String, cookieName: String, cookieValue: String) {
    def cookiePair: String = s"$cookieName=$cookieValue"
  }

  /**
   * The preparation phase's output and the burst's input.
   *
   * Tab separated rather than comma separated because a session cookie value is base64 and a
   * `userName` embeds the run's prefix: neither can contain a tab, and a format that cannot be
   * broken by its own payload is the only one worth handing between two JVMs. The file is a
   * temporary experimental artifact - it holds nothing the database does not already hold, and it
   * exists only so the measurement's own JVM does not have to spend its arrival window logging in.
   */
  object AuthContextFile {

    val columns: Vector[String] = Vector("authContextId", "userName", "cookieName", "cookieValue")

    def write(path: String, contexts: Seq[AuthContext]): Unit = {
      val lines = ListBuffer.empty[String]
      lines += columns.mkString("\t")
      contexts.foreach { context =>
        lines += Vector(context.id.toString, context.userName, context.cookieName, context.cookieValue).mkString("\t")
      }
      Files.write(
        Paths.get(path),
        (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    /**
     * Every malformed line is refused rather than skipped. A context that lost its cookie would be
     * an arrival the server answers with 401 while the run still claimed it was authenticated, which
     * is the one failure this file exists to make impossible.
     */
    def read(path: String): Vector[AuthContext] = {
      val all = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8).asScala.toVector
      // The header is checked rather than skipped. Skipping it means a file whose header was lost
      // has its first context read as the header and dropped without a word, and a missing context
      // is an arrival that has to share a session with another - the one condition the preparation
      // phase exists to make impossible. The count that would have caught it is downstream.
      if (all.isEmpty || all.head.trim != columns.mkString("\t")) {
        throw new IllegalArgumentException(s"$path does not start with the header ${columns.mkString("\t")}")
      }
      val contexts = ListBuffer.empty[AuthContext]
      all.zipWithIndex.foreach {
        case (line, index) if index == 0 || line.trim.isEmpty => // header and trailing newline
        case (line, index) =>
          val parts = line.split("\t", -1)
          if (parts.length != columns.length) {
            throw new IllegalArgumentException(s"$path line ${index + 1} has ${parts.length} fields, not ${columns.length}")
          }
          if (parts.exists(_.isEmpty)) {
            throw new IllegalArgumentException(s"$path line ${index + 1} has an empty field")
          }
          contexts += AuthContext(parts(0).toInt, parts(1), parts(2), parts(3))
      }
      contexts.toVector
    }
  }

  /**
   * The prepared sessions, served to arrivals in a fixed rotation.
   *
   * Served by rotation rather than consumed, because an arrival that could not be given a session
   * would either have to be sent unauthenticated - measuring 401s and calling it a load - or
   * skipped, which would silently lower the offered rate the run is supposed to be measuring. A
   * rotation always has a session to give, and how far it wrapped is exactly how short the
   * preparation was: with more contexts than planned arrivals nothing is reused at all, and
   * `reuseCount` is what says so.
   */
  final class AuthContextPool(contexts: Vector[AuthContext]) {

    require(contexts.nonEmpty, "the burst has no prepared auth context to submit with; prepare sessions before the measurement")

    private val served = new AtomicLong(0L)

    val size: Int = contexts.size

    def next(): AuthContext = contexts((served.getAndIncrement() % size.toLong).toInt)

    def servedCount: Long = served.get()

    /** Arrivals that had to share a session with another arrival: 0 when the pool outnumbers them. */
    def reuseCount: Long = math.max(0L, served.get() - size.toLong)
  }

  /**
   * The arrival schedule, derived from parameters alone so a dry run can be checked against it.
   *
   * `steadyStarts` is the number section 6's verdict is judged on and it is exact: a constant rate
   * for a whole number of seconds is `rate * seconds` arrivals. The ramp is an estimate - the
   * injector's ramp is an interpolation the client does not promise the exact shape of, and the
   * measured ramp count comes from the recorder (`arrivalsBeforeWindow`) rather than from this
   * arithmetic. Nothing downstream depends on the estimate being right: the ramp is outside every
   * measured window by construction.
   */
  final case class Plan(
      targetRps: Double,
      rampFromRps: Double,
      rampSeconds: Int,
      steadySeconds: Int,
      completionTimeoutSeconds: Int) {

    require(targetRps > 0d, s"targetRps must be greater than 0 (got $targetRps)")
    require(rampFromRps > 0d, s"rampFromRps must be greater than 0 (got $rampFromRps): a ramp has to start somewhere")
    require(rampFromRps <= targetRps, s"rampFromRps ($rampFromRps) must not exceed targetRps ($targetRps)")
    require(rampSeconds >= 1, s"rampSeconds must be at least 1 (got $rampSeconds)")
    require(steadySeconds >= 1, s"steadySeconds must be at least 1 (got $steadySeconds)")
    require(completionTimeoutSeconds >= 1, s"completionTimeoutSeconds must be at least 1 (got $completionTimeoutSeconds)")

    val rampMillis: Long = rampSeconds.toLong * 1000L
    val steadyMillis: Long = steadySeconds.toLong * 1000L
    val injectionMillis: Long = rampMillis + steadyMillis

    /**
     * Gatling stops the whole run at `maxDuration`, so it is the explicit completion timeout: the
     * injection is over at `injectionMillis` whatever this says, and everything above it is time
     * left for requests already in flight to be answered. Requests still unanswered when it lands
     * are recorded as incomplete and the run is marked as forcibly terminated rather than clean.
     */
    val maxDurationSeconds: Int = rampSeconds + steadySeconds + completionTimeoutSeconds

    val plannedRampArrivals: Long = math.round((rampFromRps + targetRps) / 2d * rampSeconds.toDouble)
    val plannedSteadyArrivals: Long = math.round(targetRps * steadySeconds.toDouble)
    val plannedStarts: Long = plannedRampArrivals + plannedSteadyArrivals

    def steadyStartMillis(anchorMillis: Long): Long = anchorMillis + rampMillis
    def steadyEndMillis(anchorMillis: Long): Long = anchorMillis + injectionMillis
    def injectionEndMillis(anchorMillis: Long): Long = anchorMillis + injectionMillis
  }

  /**
   * The steady window cut into whole seconds from its own start, and the only rule in this file that
   * has to be exactly right.
   *
   * Epoch-aligned seconds would each straddle two different rates: the window starts where the ramp
   * ended, which is not a second boundary, so a bucket from `12:00:00.000` would hold part of the
   * ramp's last second and part of the hold's first, and the deviation it reported would be the
   * boundary's rather than the injector's. The window is therefore cut from `steadyStartMillis` -
   * the trace's own hold start - so the harness's measured window and these buckets are the same ten
   * intervals by construction.
   *
   * An arrival belongs to bucket `i` when it is at or after that bucket's start and strictly before
   * its end. The window's final instant therefore belongs to no bucket, and an arrival landing
   * exactly on a boundary is counted once, in the later bucket. Both follow from the same
   * half-open rule rather than from two special cases.
   *
   * Pure and separate from the flush because this is what a test can check without a load
   * generator: the counting is a function of the start instants alone, and the completions are
   * deliberately not an input. A request that was never answered is a start that still happened.
   */
  def bucketCounts(startedAtMillis: Seq[Long], steadyStartMillis: Long, steadySeconds: Int): Vector[Long] =
    (0 until steadySeconds).toVector.map { index =>
      val from = steadyStartMillis + index.toLong * 1000L
      val to = from + 1000L
      startedAtMillis.count(started => started >= from && started < to).toLong
    }

  def deviationPercent(started: Long, targetRps: Double): Double =
    if (targetRps <= 0d) Double.NaN else (started.toDouble - targetRps) / targetRps * 100d

  /**
   * One submission start, and whatever became of it.
   *
   * Mutable on purpose: the arrival is recorded before the request is sent and completed by the
   * continuation that runs after it, so the record exists even when the response never arrives -
   * which is the whole point of counting starts rather than completions.
   */
  final class Arrival(val attemptId: Long, val startedAtMillis: Long, val authContextId: Int) {

    @volatile var completedAtMillis: Long = -1L
    @volatile var status: String = "incomplete"
    @volatile var responseCode: String = "unavailable"
    @volatile var submissionId: String = "unavailable"

    def complete(session: Session): Unit = {
      completedAtMillis = System.currentTimeMillis()
      // The request is the only thing this user did, so its accumulated status is the response's
      // outcome. Why it failed - refused below the application, answered 503, timed out - is read
      // from the client log by the harness, which is where the client writes it.
      status = if (session.isFailed) "ko" else "ok"
      responseCode = RequestStartRecorder.attribute(session, "responseCode")
      submissionId = RequestStartRecorder.attribute(session, "submissionId")
    }
  }

  /**
   * The record of what was started, kept independently of what was answered.
   *
   * The client's own report is a completion log: a request that never came back is not in it, and
   * the earlier runs' 382.3/s offered against 653.8/s accepted is that gap read as a fact about the
   * server when part of it was a fact about the log. This counts arrivals at the instant the
   * submission is dispatched from, before any response exists, so the offered rate does not depend
   * on the server answering at all.
   *
   * In memory until the end, and written once. A synchronous write per arrival would put file IO
   * inside the measured window and on the arrival path, and it would be the load generator's own
   * latency being recorded rather than the server's.
   */
  object RequestStartRecorder {

    /** Above this the per-arrival records are dropped (the counts are not); the plan needs 10,500. */
    private val MaxRecords = 60000

    private val arrivals = new ConcurrentLinkedQueue[Arrival]
    private val arrivalCount = new LongAdder
    private val droppedRecords = new LongAdder
    private val attemptIds = new AtomicLong(0L)
    private val engineErrors = new LongAdder
    private val flusher = new AtomicBoolean(false)
    private val armed = new AtomicBoolean(false)

    @volatile private var plan: Plan = null
    @volatile private var anchorMillis: Long = -1L
    @volatile private var anchorSource: String = "unset"
    @volatile private var firstArrivalMillis: Long = -1L
    @volatile private var tracePath: Option[String] = None
    @volatile private var artifactDir: String = "."
    @volatile private var authContextsLoaded: Int = 0
    @volatile private var authContextsServed: Long = 0L
    @volatile private var authContextReuse: Long = 0L

    /**
     * Reads a session attribute without assuming its type. The captured status code is boxed by the
     * check framework as whatever it is, and a cast that guessed wrong would abort the completion
     * record of exactly the responses worth recording.
     */
    def attribute(session: Session, key: String): String =
      session.attributes.get(key).map(_.toString).getOrElse("unavailable")

    def recordEngineError(): Unit = engineErrors.increment()

    def isArmed: Boolean = armed.get()

    /**
     * Called by the marker user, which the injector starts at the instant the arrival schedule
     * begins. That instant is the anchor every boundary is derived from: the trace file places the
     * ramp and the hold relative to it, and the per-second buckets are computed from the same value,
     * so the window the harness reads and the window the counters were bucketed into are the same
     * window by construction rather than by two clocks that were close.
     *
     * The marker runs at injector time zero, alongside the ramp's first arrivals, so an arrival can
     * race ahead of it. That is why bucketing happens at flush and not here: an arrival recorded
     * before the anchor was known still carries its own timestamp, and the anchor only has to exist
     * by the time the run ends - which it does, because the schedule cannot advance without it.
     */
    def arm(p: Plan, markerStartMillis: Long, traceFile: Option[String], dir: String, contextsLoaded: Int): Unit = {
      plan = p
      anchorMillis = markerStartMillis
      anchorSource = "marker-user-start"
      tracePath = traceFile
      artifactDir = dir
      authContextsLoaded = contextsLoaded
      armed.set(true)
      traceFile.foreach(writeTrace(p, markerStartMillis, _))
    }

    def nextAttemptId(): Long = attemptIds.incrementAndGet()

    /** Returns the record so the continuation that runs after the request can complete it. */
    def recordArrival(authContextId: Int): Arrival = {
      val now = System.currentTimeMillis()
      if (firstArrivalMillis < 0L) { firstArrivalMillis = now }
      val arrival = new Arrival(nextAttemptId(), now, authContextId)
      arrivalCount.increment()
      if (arrivals.size() < MaxRecords) { arrivals.add(arrival) } else { droppedRecords.increment() }
      arrival
    }

    def recordPool(pool: AuthContextPool): Unit = {
      authContextsServed = pool.servedCount
      authContextReuse = pool.reuseCount
    }

    /**
     * The schedule, written before the first arrival is answered so the harness can read the
     * boundaries while the load is still in flight. `hold` is the steady window and is the only
     * segment the harness turns into a stage; the ramp is a segment so that a sample landing in it
     * is labelled as the ramp rather than as the steady window it has not reached yet.
     */
    private def writeTrace(p: Plan, anchor: Long, path: String): Unit = {
      val lines = ListBuffer.empty[String]
      def segment(index: Int, kind: String, stageIndex: Int, population: Long, rps: Double, fromMillis: Long, toMillis: Long): Unit = {
        lines += s"${anchor + fromMillis},segmentStart,$index,$kind,$stageIndex,0,$population,$rps"
        lines += s"${anchor + toMillis},segmentEnd,$index,$kind,$stageIndex,0,$population,$rps"
      }
      lines += s"$anchor,anchor,-1,none,-1,0,0,0"
      segment(0, "ramp", -1, p.plannedRampArrivals, p.rampFromRps, 0L, p.rampMillis)
      segment(1, "hold", 0, p.plannedSteadyArrivals, p.targetRps, p.rampMillis, p.injectionMillis)
      lines += s"${anchor + p.injectionMillis},planDone,-1,none,-1,0,0,0"
      Files.write(
        Paths.get(path),
        (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    private def jsonEscape(text: String): String =
      text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /**
     * One write at the end of the run, from a shutdown hook, because nothing in gatling-core 3.10.5
     * calls a `Simulation` hook after the injector finishes. The hook is what makes the flush
     * unconditional: it runs whether Gatling ended cleanly or was stopped at `maxDuration`.
     */
    def installShutdownHook(): Unit = {
      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        override def run(): Unit = flush()
      }, "open-burst-flush"))
    }

    def flush(): Unit = {
      if (!flusher.compareAndSet(false, true)) { return }
      try {
        val p = plan
        if (p == null) { return }
        val records = arrivals.asScala.toVector.sortBy(_.attemptId)
        // The marker's reading if it ran, and otherwise the first arrival: a run whose marker never
        // started has no trace file either, and the harness refuses such a run - but the bucket view
        // is still computable, and a computable number is better than an empty file.
        val anchor = if (anchorMillis > 0L) anchorMillis else firstArrivalMillis
        if (anchorMillis <= 0L) { anchorSource = "first-arrival" }
        val steadyStart = p.steadyStartMillis(anchor)
        val steadyEnd = p.steadyEndMillis(anchor)

        val inWindow = records.filter(a => a.startedAtMillis >= steadyStart && a.startedAtMillis < steadyEnd)
        // Counted from the recorded arrivals rather than from the unbounded total, so the three
        // numbers add up to what the per-attempt file holds; a dropped record is reported on its own
        // rather than folded into "before the window", where it would look like ramp traffic.
        val beforeWindow = records.count(_.startedAtMillis < steadyStart)
        val afterWindow = records.count(_.startedAtMillis >= steadyEnd)

        // Buckets are the steady window cut into whole seconds, not the wall clock's seconds: the
        // window starts where the ramp ended, which is not a second boundary, so epoch-aligned
        // buckets would each straddle two different rates and the deviation they reported would be
        // the boundary's, not the injector's. The boundary is the trace's own hold start, so the
        // buckets and the harness's measured window are the same ten intervals.
        val counted = bucketCounts(records.map(_.startedAtMillis), steadyStart, p.steadySeconds)
        val target = math.round(p.targetRps).toLong
        val bucketLines = ListBuffer.empty[String]
        bucketLines += "secondStartUtc,started,target,deviationPercent"
        val bucketCountsWithStart = counted.zipWithIndex.map {
          case (started, index) => (steadyStart + index.toLong * 1000L, started)
        }
        bucketCountsWithStart.foreach {
          case (bucketStart, started) =>
            val deviation = deviationPercent(started, p.targetRps.toDouble)
            val deviationText = if (deviation.isNaN) "" else f"$deviation%.3f"
            bucketLines += s"${isoUtc(bucketStart)},$started,$target,$deviationText"
        }
        write(Paths.get(artifactDir, "request-starts-1s.csv"), bucketLines)

        val attemptLines = ListBuffer.empty[String]
        attemptLines += "attemptId,startedAt,completedAt,status,responseCode,submissionId,authContextId"
        records.foreach { arrival =>
          val completedAt = if (arrival.completedAtMillis > 0L) isoUtc(arrival.completedAtMillis) else "unavailable"
          attemptLines += Vector(
            arrival.attemptId.toString,
            isoUtc(arrival.startedAtMillis),
            completedAt,
            arrival.status,
            arrival.responseCode,
            arrival.submissionId,
            arrival.authContextId.toString).mkString(",")
        }
        write(Paths.get(artifactDir, "submission-attempts.csv"), attemptLines)

        val completed = records.count(_.completedAtMillis > 0L).toLong
        val incomplete = records.count(_.completedAtMillis <= 0L).toLong
        val ok = records.count(a => a.completedAtMillis > 0L && a.status == "ok").toLong
        val lastCompletion = if (records.isEmpty) -1L else records.map(_.completedAtMillis).max
        val worstDeviation = bucketCountsWithStart.map {
          case (_, started) =>
            val deviation = deviationPercent(started, p.targetRps.toDouble)
            if (deviation.isNaN) 0d else math.abs(deviation)
        }.foldLeft(0d)((left, right) => math.max(left, right))

        val json = ListBuffer.empty[String]
        json += "{"
        json += s"""  "model": "open-arrival","""
        json += s"""  "targetRps": ${p.targetRps},"""
        json += s"""  "rampFromRps": ${p.rampFromRps},"""
        json += s"""  "rampSeconds": ${p.rampSeconds},"""
        json += s"""  "steadySeconds": ${p.steadySeconds},"""
        json += s"""  "completionTimeoutSeconds": ${p.completionTimeoutSeconds},"""
        json += s"""  "maxDurationSeconds": ${p.maxDurationSeconds},"""
        json += s"""  "plannedRampArrivals": ${p.plannedRampArrivals},"""
        json += s"""  "plannedSteadyArrivals": ${p.plannedSteadyArrivals},"""
        json += s"""  "plannedStarts": ${p.plannedStarts},"""
        json += s"""  "anchorUtc": "${isoUtc(anchor)}","""
        json += s"""  "anchorSource": "${jsonEscape(anchorSource)}","""
        json += s"""  "firstArrivalUtc": "${if (firstArrivalMillis > 0L) isoUtc(firstArrivalMillis) else "unavailable"}","""
        json += s"""  "steadyStartUtc": "${isoUtc(steadyStart)}","""
        json += s"""  "steadyEndUtc": "${isoUtc(steadyEnd)}","""
        json += s"""  "injectionEndUtc": "${isoUtc(p.injectionEndMillis(anchor))}","""
        json += s"""  "bucketAlignment": "the steady window cut into whole seconds from its own start, not epoch-aligned seconds","""
        json += s"""  "arrivalCount": ${arrivalCount.sum()},"""
        json += s"""  "recordedArrivals": ${records.size},"""
        json += s"""  "droppedRecords": ${droppedRecords.sum()},"""
        json += s"""  "arrivalsBeforeWindow": $beforeWindow,"""
        json += s"""  "arrivalsInWindow": ${inWindow.size},"""
        json += s"""  "arrivalsAfterWindow": $afterWindow,"""
        json += s"""  "observedRateInWindow": ${if (p.steadySeconds > 0) f"${inWindow.size.toDouble / p.steadySeconds.toDouble}%.3f" else "0"},"""
        json += s"""  "worstBucketDeviationPercent": ${f"$worstDeviation%.3f"},"""
        json += s"""  "completedAttempts": $completed,"""
        json += s"""  "incompleteAttempts": $incomplete,"""
        json += s"""  "okAttempts": $ok,"""
        json += s"""  "koAttempts": ${completed - ok},"""
        json += s"""  "lastCompletionUtc": "${if (lastCompletion > 0L) isoUtc(lastCompletion) else "unavailable"}","""
        json += s"""  "waitingForCompletionSeconds": ${if (lastCompletion > 0L) f"${(lastCompletion - p.injectionEndMillis(anchor)).toDouble / 1000d}%.3f" else "null"},"""
        json += s"""  "forcedTermination": ${incomplete > 0L},"""
        json += s"""  "engineErrors": ${engineErrors.sum()},"""
        json += s"""  "authContextsLoaded": $authContextsLoaded,"""
        json += s"""  "authContextsServed": $authContextsServed,"""
        json += s"""  "authContextReuse": $authContextReuse,"""
        json += s"""  "buckets": ["""
        json += bucketCountsWithStart.zipWithIndex.map {
          case ((bucketStart, started), i) =>
            val deviation = deviationPercent(started, p.targetRps.toDouble)
            val deviationText = if (deviation.isNaN) "null" else f"$deviation%.3f"
            s"""{"index": $i, "secondStartUtc": "${isoUtc(bucketStart)}", "started": $started, "target": $target, "deviationPercent": $deviationText}"""
        }.mkString(",\n    ")
        json += "  ]"
        json += "}"
        write(Paths.get(artifactDir, "open-burst-recorder.json"), json)
      } catch {
        case error: Throwable =>
          // A failed flush must not take the JVM down from inside a shutdown hook, but it must not
          // be silent either: the file it did not write is the evidence the run is judged on.
          System.err.println(s"open-burst recorder flush failed: $error")
      }
    }

    private def write(path: java.nio.file.Path, lines: ListBuffer[String]): Unit = {
      Files.write(
        path,
        (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }
  }
}
