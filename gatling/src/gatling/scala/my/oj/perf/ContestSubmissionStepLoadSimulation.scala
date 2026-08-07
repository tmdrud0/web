package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.core.controller.inject.closed.ClosedInjectionStep

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

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val peakConcurrentUsers = ApiLoad.concurrentUsers(maxRps, intervalMs)

  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(startRps > 0d, "perf.startRps must be greater than 0")
  require(stepRps > 0d, "perf.stepRps must be greater than 0")
  require(maxRps >= startRps, "perf.maxRps must be greater than or equal to perf.startRps")
  require(intervalMs > 0L, "perf.submitIntervalMillis must be greater than 0")
  require(peakConcurrentUsers <= availableUsers,
    s"the top step needs $peakConcurrentUsers seeded users at a ${intervalMs}ms pace but only $availableUsers are " +
      "available - raise -UserCount, lower perf.maxRps, or shorten perf.submitIntervalMillis")

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  private val submitScenario = scenario("Contest submissions (API step load)")
    .feed(ApiLoad.loginFeeder(userPrefix, userIndexStart, userIndexEnd))
    .exec(ApiLoad.login)
    .exitHereIfFailed
    .exec(ApiLoad.initialJitter(intervalMs))
    .forever {
      pace(intervalMs.millis)
        .exec(ApiLoad.randomSubmissionData(problemIdStart, problemIdEnd, "oj-step"))
        .exec(ApiLoad.submit)
    }

  private val targets = staircaseTargets()
  private val totalDuration = (targets.size * (rampSeconds + stepHoldSeconds)).seconds

  setUp(
    submitScenario.inject(buildInjectionProfile())
  ).protocols(httpProtocol)
    .maxDuration(totalDuration)
    .assertions(LoadTestAssertions.globalAssertions: _*)

  private def buildInjectionProfile(): List[ClosedInjectionStep] = {
    val populations = targets.map(ApiLoad.concurrentUsers(_, intervalMs))
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
