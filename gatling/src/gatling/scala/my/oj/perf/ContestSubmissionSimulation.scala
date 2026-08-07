package my.oj.perf

import io.gatling.core.Predef._

import scala.concurrent.duration._

/**
 * Contest submissions at one rate, through the API a user actually posts to.
 *
 * This drove `/perf/contest/submit`, which took a user id in the body and authenticated nobody, so
 * every throughput figure it produced described a system with no session lookup on the write path.
 * Pointing it at `POST /api/problems/{id}/submissions` makes the rate meaningful and the model
 * wrong at the same time: an open model would create a virtual user per submission and each would
 * have to log in first, so the run would measure logins.
 *
 * So the population is closed. `ceil(rps * interval)` users authenticate once and then pace, which
 * delivers the same rate from a contest that has that many participants - see `ApiLoad`. The
 * interval is supplied rather than assumed, because it is what converts the rate the caller asked
 * for into the size of the field, and that is a decision the harness makes against the pool it
 * seeded.
 */
class ContestSubmissionSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val userPrefix     = System.getProperty("perf.userPrefix", "loadtest")
  private val userIndexStart = propInt("perf.userIndex.start", 1)
  private val userIndexEnd   = propInt("perf.userIndex.end", 10000)
  private val problemIdStart = propLong("perf.problemId.start", 1L)
  private val problemIdEnd   = propLong("perf.problemId.end", 5L)
  private val rampSeconds    = propInt("perf.rampSeconds", 10)
  private val holdSeconds    = propInt("perf.holdSeconds", 30)
  private val targetRps      = propDouble("perf.targetRps", 139d)
  private val intervalMs     = propLong("perf.submitIntervalMillis", 25_000L)

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val concurrentUsers = ApiLoad.concurrentUsers(targetRps, intervalMs)

  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(targetRps > 0d, "perf.targetRps must be greater than 0")
  require(intervalMs > 0L, "perf.submitIntervalMillis must be greater than 0")
  require(concurrentUsers <= availableUsers,
    s"this rate needs $concurrentUsers seeded users at a ${intervalMs}ms pace but only $availableUsers are " +
      "available - raise -UserCount, lower perf.targetRps, or shorten perf.submitIntervalMillis")

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  private val submitScenario = scenario("Contest submissions (API)")
    .feed(ApiLoad.loginFeeder(userPrefix, userIndexStart, userIndexEnd))
    .exec(ApiLoad.login)
    .exitHereIfFailed
    .exec(ApiLoad.initialJitter(intervalMs))
    .forever {
      pace(intervalMs.millis)
        .exec(ApiLoad.randomSubmissionData(problemIdStart, problemIdEnd, "oj"))
        .exec(ApiLoad.submit)
    }

  setUp(
    submitScenario.inject(
      rampConcurrentUsers(1).to(concurrentUsers).during(rampSeconds.seconds),
      constantConcurrentUsers(concurrentUsers).during(holdSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .maxDuration((rampSeconds + holdSeconds).seconds)
    .assertions(LoadTestAssertions.globalAssertions: _*)
}
