package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.core.controller.inject.closed.ClosedInjectionStep

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * A staircase, to find where the stack stops keeping up.
 *
 * The steps are now populations rather than arrival rates, and that changes what the run can tell
 * you. An open staircase pushes arrivals in regardless of whether the server is answering, so it
 * overloads by construction and the reading is the rate at which errors start. A closed one cannot
 * do that: when the server slows, its users are still waiting on their last response, so the
 * offered rate drops with the server instead of piling on top of it.
 *
 * That is not a worse test, it is the same question asked correctly of a system whose clients are
 * people. Each step is `ceil(rps * interval)` users pacing at the interval, so a healthy step
 * delivers its target rate; saturation shows up as measured throughput falling short of the target
 * while latency climbs, which is the point the staircase exists to locate. The steps are still
 * expressed in requests per second so the harness parameters and the earlier runs' vocabulary
 * carry over.
 *
 * Session authentication is the reason it had to move: this drove `/perf/contest/submit`, which
 * took a user id in the body. Against `POST /api/problems/{id}/submissions` an open model would
 * log in once per submission and the staircase would measure logins.
 *
 * `perf.stageRps` replaces the arithmetic walk with an explicit list, which the walk cannot
 * express: a measured capacity sweep needs a warm-up stage that repeats the first plateau's rate,
 * so the ladder is not an arithmetic progression. Everything else - the ramp-then-hold shape, the
 * closed populations, the seeding - is the same, and with the property absent the arithmetic path
 * below is untouched.
 *
 * `perf.stageTraceFile` writes the whole schedule up front, at the instant the injection profile
 * is about to start. The harness otherwise has to infer stage boundaries from the traffic itself,
 * and the only available anchor - the first persisted submission - is spread over one full
 * `submitIntervalMillis` by `initialJitter`, which is a third of a 30 second stage. Predicting the
 * boundaries from a single anchor costs whatever delay sits between `before()` and the injector
 * start (a constant, recorded alongside the run), and it removes the sampling error.
 *
 * `perf.authPrepSeconds` separates session preparation from the measured submission load. Left
 * absent, the model is exactly what every earlier run was measured with: users arrive on the ramp,
 * each logs in as it starts, and each begins submitting immediately, so the logins are spread only
 * as widely as the ramp is long. Set equal to `perf.rampSeconds`, the same ramp becomes the window
 * over which the population establishes its sessions and nothing is submitted until it closes.
 *
 * That separation is not cosmetic. A 1000 RPS step with a 3100ms per-user pace needs 3,100 sessions,
 * and creating them on a one second ramp is 3,100 simultaneous connections through the published
 * port - which the run on 2026-09-20 measured as 2,289 `Connection refused` answers. Every refused
 * login then trips `exitHereIfFailed`, the closed model replaces the session, the replacement takes
 * the next record from the non-circular login feeder, and the feeder emptied before the engine had
 * persisted a single submission. Spreading the same 3,100 connections over a 30 second preparation
 * window is about 103 a second, well inside the 310 a second the warm-up phase had already been
 * measured doing without a single refusal.
 */
class ContestSubmissionStepLoadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl         = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val userPrefix      = System.getProperty("perf.userPrefix", "loadtest")
  private val userIndexStart  = propInt("perf.userIndex.start", 1)
  private val userIndexEnd    = propInt("perf.userIndex.end", 10000)
  private val problemIdStart  = propLong("perf.problemId.start", 1L)
  private val problemIdEnd    = propLong("perf.problemId.end", 5L)
  private val startRps        = propDouble("perf.startRps", 200d)
  private val stepRps         = propDouble("perf.stepRps", 200d)
  private val maxRps          = propDouble("perf.maxRps", 1000d)
  private val rampSeconds     = propInt("perf.rampSeconds", 5)
  private val stepHoldSeconds = propInt("perf.stepHoldSeconds", 10)
  private val intervalMs      = propLong("perf.submitIntervalMillis", 5_000L)

  /**
   * Explicit staircase, e.g. `50,50,100,150,200,230`. Absent means the arithmetic walk, so no
   * existing invocation changes behaviour.
   */
  private val explicitStageRps = Option(System.getProperty("perf.stageRps"))
    .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).map(_.toDouble).toVector)
  private val warmupStageCount = propInt("perf.warmupStageCount", 0)
  private val stageTraceFile   = Option(System.getProperty("perf.stageTraceFile")).filter(_.nonEmpty)

  /**
   * The length of the session-preparation window, or 0 for the model that submits as it logs in.
   * See the class comment: this is the injector's ramp, and it is validated against it below.
   */
  private val authPrepSeconds = propInt("perf.authPrepSeconds", 0)

  /**
   * One clock for the whole schedule, taken before anything else in this class is built.
   *
   * The gate below and the trace's `anchor` line are the same instant rather than two calls to
   * `System.currentTimeMillis()` a few milliseconds apart. Submissions are released at
   * `anchor + authPrepSeconds`, which is exactly where the trace puts the end of the ramp and the
   * start of the hold, so the measured window the harness derives from the trace and the instant
   * the offered load actually begins are the same boundary.
   */
  private val planAnchorMillis = System.currentTimeMillis()
  private val authGateMillis   = planAnchorMillis + authPrepSeconds.toLong * 1000L
  private val deferSubmissions = authPrepSeconds > 0

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val arithmeticTargets = staircaseTargets()
  private val targets = explicitStageRps.getOrElse(arithmeticTargets)
  private val peakConcurrentUsers =
    if (targets.isEmpty) 0 else targets.map(ApiLoad.concurrentUsers(_, intervalMs)).max

  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(startRps > 0d, "perf.startRps must be greater than 0")
  require(stepRps > 0d, "perf.stepRps must be greater than 0")
  require(maxRps >= startRps, "perf.maxRps must be greater than or equal to perf.startRps")
  require(intervalMs > 0L, "perf.submitIntervalMillis must be greater than 0")
  require(peakConcurrentUsers <= availableUsers,
    s"the top step needs $peakConcurrentUsers seeded users at a ${intervalMs}ms pace but only $availableUsers are " +
      "available - raise -UserCount, lower perf.maxRps, or shorten perf.submitIntervalMillis")
  require(explicitStageRps.forall(_.nonEmpty), "perf.stageRps must list at least one target rate")
  require(explicitStageRps.forall(_.forall(_ > 0d)), "every perf.stageRps entry must be greater than 0")
  require(warmupStageCount >= 0 && warmupStageCount < targets.size,
    s"perf.warmupStageCount must leave at least one measured stage (got $warmupStageCount of ${targets.size})")
  require(stageTraceFile.isEmpty || explicitStageRps.isDefined,
    "perf.stageTraceFile describes the explicit stage list, so it requires perf.stageRps")
  require(authPrepSeconds == 0 || (explicitStageRps.isDefined && authPrepSeconds == rampSeconds),
    s"perf.authPrepSeconds ($authPrepSeconds) is the window that prepares the population's sessions, and it is the " +
      s"ramp that precedes the first hold: set it equal to perf.rampSeconds ($rampSeconds) on an explicit single-stage " +
      "plan, or leave it absent for the model that submits as it logs in")

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  /**
   * Login, then wait for the preparation window to close, then submit.
   *
   * With `perf.authPrepSeconds` absent the two chains are identical, which is what keeps this an
   * added property rather than a changed model for the runs that already exist.
   */
  private val submitScenario = {
    val afterLogin = scenario("Contest submissions (API step load)")
      .feed(ApiLoad.loginFeeder(userPrefix, userIndexStart, userIndexEnd))
      .exec(ApiLoad.login)
      .exitHereIfFailed
    val prepared =
      if (deferSubmissions) afterLogin.exec(ApiLoad.waitUntil(authGateMillis))
      else afterLogin
    prepared
      .exec(ApiLoad.initialJitter(intervalMs))
      .forever {
        pace(intervalMs.millis)
          .exec(ApiLoad.randomSubmissionData(problemIdStart, problemIdEnd, "oj-step"))
          .exec(ApiLoad.submit)
      }
  }

  private val totalDuration =
    if (explicitStageRps.isDefined) explicitPlan().map(_.seconds).sum.seconds
    else (arithmeticTargets.size * (rampSeconds + stepHoldSeconds)).seconds

  private val injectionProfile =
    if (explicitStageRps.isDefined) buildExplicitInjectionProfile() else buildInjectionProfile()

  setUp(
    submitScenario.inject(injectionProfile)
  ).protocols(httpProtocol)
    .maxDuration(totalDuration)
    .assertions(LoadTestAssertions.globalAssertions: _*)

  /**
   * Written at construction, because the runner instantiates the simulation immediately before it
   * starts the injector and no `Simulation` hook fires reliably at that point - Gatling's
   * `before`/`after` are `ScenarioBuilder`/`PopulationBuilder` statements, and nothing in
   * gatling-core 3.10.5 calls the `Simulation` ones. The harness reads the boundaries while the
   * run is in flight, so the file is complete before the first request is sent rather than
   * appended to as the schedule advances.
   */
  stageTraceFile.foreach(writeStageTrace)

  private case class PlannedStep(
      kind: String,
      population: Int,
      fromPopulation: Int,
      stageIndex: Int,
      targetRps: Double,
      seconds: Int)

  private def explicitPlan(): List[PlannedStep] = {
    val rates = explicitStageRps.get
    val populations = rates.map(ApiLoad.concurrentUsers(_, intervalMs))
    val steps = ListBuffer.empty[PlannedStep]

    steps += PlannedStep("initialRamp", populations.head, 1, -1, rates.head, rampSeconds)
    populations.zip(rates).zipWithIndex.foreach {
      case ((population, target), index) =>
        if (index > 0) {
          steps += PlannedStep("transition", population, populations(index - 1), index, target, rampSeconds)
        }
        steps += PlannedStep("hold", population, population, index, target, stepHoldSeconds)
    }
    steps.toList
  }

  /**
   * `rampConcurrentUsers(x).to(x)` is not a step this file has ever exercised, and a transition
   * that does not change the population is a plateau, not a ramp. Two consecutive constants say
   * the same thing using only steps the arithmetic path already relies on.
   */
  private def buildExplicitInjectionProfile(): List[ClosedInjectionStep] = {
    val steps = ListBuffer.empty[ClosedInjectionStep]
    explicitPlan().foreach { step =>
      step.kind match {
        case "initialRamp" =>
          steps += rampConcurrentUsers(1).to(step.population).during(step.seconds.seconds)
        case "transition" if step.fromPopulation == step.population =>
          steps += constantConcurrentUsers(step.population).during(step.seconds.seconds)
        case "transition" =>
          steps += rampConcurrentUsers(step.fromPopulation).to(step.population).during(step.seconds.seconds)
        case _ =>
          steps += constantConcurrentUsers(step.population).during(step.seconds.seconds)
      }
    }
    steps.toList
  }

  private def writeStageTrace(path: String): Unit = {
    val steps = explicitPlan()
    // The class's own clock, not a second reading: `perf.authPrepSeconds` releases the submission
    // load at this instant plus the ramp, so the trace must describe that same instant rather than
    // one a few milliseconds later.
    val anchorMillis = planAnchorMillis
    val lines = ListBuffer.empty[String]

    def boundary(event: String, index: Int, step: PlannedStep, offsetMillis: Long): String = {
      val stageIndex = if (step.kind == "hold") step.stageIndex else -1
      val isWarmup = if (step.kind == "hold" && step.stageIndex < warmupStageCount) 1 else 0
      s"${anchorMillis + offsetMillis},$event,$index,${step.kind},$stageIndex,$isWarmup," +
        s"${step.population},${step.targetRps}"
    }

    lines += s"$anchorMillis,anchor,-1,none,-1,0,0,0"
    var offsetMillis = 0L
    steps.zipWithIndex.foreach {
      case (step, index) =>
        lines += boundary("segmentStart", index, step, offsetMillis)
        offsetMillis += step.seconds * 1000L
        lines += boundary("segmentEnd", index, step, offsetMillis)
    }
    lines += s"${anchorMillis + offsetMillis},planDone,-1,none,-1,0,0,0"

    Files.write(
      Paths.get(path),
      (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
  }

  private def buildInjectionProfile(): List[ClosedInjectionStep] = {
    val populations = arithmeticTargets.map(ApiLoad.concurrentUsers(_, intervalMs))
    val steps = ListBuffer.empty[ClosedInjectionStep]

    steps += rampConcurrentUsers(1).to(populations.head).during(rampSeconds.seconds)
    steps += constantConcurrentUsers(populations.head).during(stepHoldSeconds.seconds)

    populations.sliding(2).foreach {
      case Seq(previous, current) =>
        steps += rampConcurrentUsers(previous).to(current).during(rampSeconds.seconds)
        steps += constantConcurrentUsers(current).during(stepHoldSeconds.seconds)
      case _ =>
    }

    steps.toList
  }

  private def staircaseTargets(): Vector[Double] = {
    val targets = ListBuffer.empty[Double]
    var current = startRps
    while (current <= maxRps) {
      targets += current
      current += stepRps
    }
    if (targets.isEmpty || targets.last < maxRps) {
      targets += maxRps
    }
    targets.toVector
  }
}
