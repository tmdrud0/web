package my.oj.perf

import io.gatling.core.session.Session

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

import OpenBurstLoad.{Arrival, isoUtc}

/**
 * The contest peak profile: a baseline arrival rate B with a short peak on top of it.
 *
 * The profile is a list of segments, each a length in seconds and a multiple of B, offered back to
 * back as open arrivals (`constantUsersPerSec(rate).during(seconds)`), optionally `.randomized` so the
 * arrivals are a Poisson process - which is what the queue model the runs are compared against
 * assumes. The default is the experiment's shape: 90s at 1B, 60s at 5B, 30s at 10B, 60s at 5B and
 * 120s at 1B.
 *
 * Kept apart from `OpenBurstLoad`, whose plan is a ramp and one steady hold judged second by second
 * against a tolerance. A Poisson schedule has no per-second tolerance to be judged by, and the
 * segments are what the analyzer labels its windows with.
 */
object PeakProfileLoad {

  final case class Segment(index: Int, seconds: Int, multiplier: Double, rps: Double, startMillis: Long, endMillis: Long) {
    def expectedArrivals: Double = rps * seconds.toDouble
  }

  final case class Profile(baseRps: Double, segments: Vector[Segment], completionTimeoutSeconds: Int) {
    require(baseRps > 0d, s"baseRps must be greater than 0 (got $baseRps)")
    require(segments.nonEmpty, "a peak profile needs at least one segment")
    require(completionTimeoutSeconds >= 1, s"completionTimeoutSeconds must be at least 1 (got $completionTimeoutSeconds)")

    val injectionMillis: Long = segments.last.endMillis
    val injectionSeconds: Int = segments.map(_.seconds).sum
    val maxDurationSeconds: Int = injectionSeconds + completionTimeoutSeconds
    val expectedArrivals: Double = segments.map(_.expectedArrivals).sum

    /** The segment an instant (millis from the anchor) belongs to; half-open, like the analyzer. */
    def segmentAt(offsetMillis: Long): Option[Segment] =
      segments.find(s => offsetMillis >= s.startMillis && offsetMillis < s.endMillis)
  }

  /** "90:1,60:5,30:10,60:5,120:1" at base B -> segments. Whole seconds only: the trace is in millis. */
  def parse(baseRps: Double, text: String, completionTimeoutSeconds: Int): Profile = {
    val parts = text.split(",").map(_.trim).filter(_.nonEmpty).toVector
    require(parts.nonEmpty, s"perf.peakSegments is empty: '$text'")
    var start = 0L
    val segments = parts.zipWithIndex.map { case (part, index) =>
      val fields = part.split(":")
      require(fields.length == 2, s"segment '$part' is not seconds:multiplier")
      val seconds = fields(0).trim.toInt
      val multiplier = fields(1).trim.toDouble
      require(seconds >= 1, s"segment '$part' must last at least one second")
      require(multiplier > 0d, s"segment '$part' must have a positive multiplier")
      val segment = Segment(index, seconds, multiplier, baseRps * multiplier, start, start + seconds * 1000L)
      start = segment.endMillis
      segment
    }
    Profile(baseRps, segments, completionTimeoutSeconds)
  }

  /**
   * The trace the harness reads, in the staircase's format so its parser and sampler label every
   * tick with the segment it fell in. Every segment is a "hold" with its own stage index: a peak
   * profile has no ramps, the rate steps from one segment to the next.
   */
  def traceLines(profile: Profile, anchorMillis: Long): Vector[String] = {
    val lines = ListBuffer.empty[String]
    lines += s"$anchorMillis,anchor,-1,none,-1,0,0,0"
    profile.segments.foreach { s =>
      val expected = math.round(s.expectedArrivals)
      lines += s"${anchorMillis + s.startMillis},segmentStart,${s.index},hold,${s.index},0,$expected,${s.rps}"
      lines += s"${anchorMillis + s.endMillis},segmentEnd,${s.index},hold,${s.index},0,$expected,${s.rps}"
    }
    lines += s"${anchorMillis + profile.injectionMillis},planDone,-1,none,-1,0,0,0"
    lines.toVector
  }

  /** Arrivals per segment, from start instants alone. An instant before the anchor or after the end is in no segment. */
  def countBySegment(profile: Profile, anchorMillis: Long, startedAtMillis: Seq[Long]): Vector[Long] =
    profile.segments.map { s =>
      startedAtMillis.count(t => t >= anchorMillis + s.startMillis && t < anchorMillis + s.endMillis).toLong
    }

  /**
   * One row per submission attempt, kept in memory and written once from a shutdown hook, the same
   * way the open-burst recorder is: a write per arrival would put file IO on the arrival path.
   */
  object Recorder {
    private val MaxRecords = 400000
    private val arrivals = new ConcurrentLinkedQueue[Arrival]
    private val attemptIds = new AtomicLong(0L)
    private val dropped = new AtomicLong(0L)
    private val flushed = new AtomicBoolean(false)
    @volatile private var profile: Profile = null
    @volatile private var anchorMillis: Long = -1L
    @volatile private var artifactDir: String = "."
    @volatile private var contextsLoaded: Int = 0
    @volatile private var contextsServed: Long = 0L
    @volatile private var randomized: Boolean = true

    def arm(p: Profile, anchor: Long, dir: String, loaded: Int, isRandomized: Boolean, tracePath: String): Unit = {
      profile = p
      anchorMillis = anchor
      artifactDir = dir
      contextsLoaded = loaded
      randomized = isRandomized
      Files.write(Paths.get(tracePath), (traceLines(p, anchor).mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    def recordArrival(authContextId: Int): Arrival = {
      val arrival = new Arrival(attemptIds.incrementAndGet(), System.currentTimeMillis(), authContextId)
      if (arrivals.size() < MaxRecords) arrivals.add(arrival) else dropped.incrementAndGet()
      arrival
    }

    def recordServed(served: Long): Unit = { contextsServed = served }

    def installShutdownHook(): Unit =
      Runtime.getRuntime.addShutdownHook(new Thread(() => flush(), "peak-profile-flush"))

    def flush(): Unit = {
      if (!flushed.compareAndSet(false, true)) return
      try {
        val p = profile
        if (p == null) return
        val records = arrivals.asScala.toVector.sortBy(_.attemptId)
        val lines = ListBuffer.empty[String]
        lines += "attemptId,startedAt,completedAt,status,responseCode,submissionId,authContextId,segmentIndex"
        records.foreach { a =>
          val completedAt = if (a.completedAtMillis > 0L) isoUtc(a.completedAtMillis) else "unavailable"
          val segment = p.segmentAt(a.startedAtMillis - anchorMillis).map(_.index.toString).getOrElse("")
          lines += Vector(a.attemptId.toString, isoUtc(a.startedAtMillis), completedAt, a.status, a.responseCode,
            a.submissionId, a.authContextId.toString, segment).mkString(",")
        }
        write("submission-attempts.csv", lines)

        val counts = countBySegment(p, anchorMillis, records.map(_.startedAtMillis))
        val ok = records.count(a => a.completedAtMillis > 0L && a.status == "ok")
        val incomplete = records.count(_.completedAtMillis <= 0L)
        val json = ListBuffer.empty[String]
        json += "{"
        json += s"""  "model": "peak-profile-open-arrival","""
        json += s"""  "randomized": $randomized,"""
        json += s"""  "baseRps": ${p.baseRps},"""
        json += s"""  "anchorUtc": "${isoUtc(anchorMillis)}","""
        json += s"""  "injectionEndUtc": "${isoUtc(anchorMillis + p.injectionMillis)}","""
        json += s"""  "completionTimeoutSeconds": ${p.completionTimeoutSeconds},"""
        json += s"""  "expectedArrivals": ${p.expectedArrivals},"""
        json += s"""  "recordedArrivals": ${records.size},"""
        json += s"""  "droppedRecords": ${dropped.get()},"""
        json += s"""  "okAttempts": $ok,"""
        json += s"""  "koAttempts": ${records.size - ok - incomplete},"""
        json += s"""  "incompleteAttempts": $incomplete,"""
        json += s"""  "authContextsLoaded": $contextsLoaded,"""
        json += s"""  "authContextsServed": $contextsServed,"""
        json += s"""  "authContextReuse": ${math.max(0L, contextsServed - contextsLoaded)},"""
        json += s"""  "segments": ["""
        json += p.segments.zip(counts).map { case (s, c) =>
          s"""    {"index": ${s.index}, "seconds": ${s.seconds}, "multiplier": ${s.multiplier}, "rps": ${s.rps}, "startUtc": "${isoUtc(anchorMillis + s.startMillis)}", "endUtc": "${isoUtc(anchorMillis + s.endMillis)}", "expected": ${s.expectedArrivals}, "started": $c}"""
        }.mkString(",\n")
        json += "  ]"
        json += "}"
        write("peak-recorder.json", json)
      } catch {
        case error: Throwable => System.err.println(s"peak-profile recorder flush failed: $error")
      }
    }

    private def write(name: String, lines: ListBuffer[String]): Unit =
      Files.write(Paths.get(artifactDir, name), (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)

    def attribute(session: Session, key: String): String = OpenBurstLoad.RequestStartRecorder.attribute(session, key)
  }
}
