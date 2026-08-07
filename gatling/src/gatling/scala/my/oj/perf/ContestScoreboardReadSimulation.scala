package my.oj.perf

import io.gatling.core.Predef._

import scala.concurrent.duration._

/**
 * Scoreboard reads at a fixed rate, against `GET /api/contests/{id}/scoreboard`.
 *
 * Stays open and anonymous, because that is what the endpoint is: no session, no per-caller state,
 * and a reader who gives up does not stop the next one arriving. Only the submissions had to close.
 *
 * `perf.startRank.max` has no default on purpose. It was hardcoded to 100,000 while the contest the
 * harness seeded finished with roughly 9,720 participants, so about ninety per cent of reads paged
 * past the end of the scoreboard - where the reader answers with a single ZCARD and returns early,
 * against 102 Redis commands for a page that has rows. The run reported a rate it never served.
 * The harness now measures the participant count after the population phase drains and passes it
 * in; refusing to start without it is what stops the constant coming back.
 */
class ContestScoreboardReadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double = java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl      = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val contestId    = propLong("perf.contestId", 1L)
  private val minStartRank = propLong("perf.startRank.min", 1L)
  private val maxStartRank = propLong("perf.startRank.max", 0L)
  private val pageSize     = propInt("perf.pageSize", 100)
  private val rampSeconds  = propInt("perf.rampSeconds", 30)
  private val holdSeconds  = propInt("perf.holdSeconds", 120)
  private val targetRps    = propDouble("perf.targetRps", 2000d)

  require(maxStartRank > 0L,
    "perf.startRank.max must be set to the contest's participant count - reads past the end of the " +
      "scoreboard cost one Redis command instead of 102 and measure nothing")
  require(maxStartRank >= minStartRank, "perf.startRank.max must be greater than or equal to perf.startRank.min")
  require(pageSize > 0, "perf.pageSize must be greater than 0")
  require(targetRps > 0d, "perf.targetRps must be greater than 0")

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  private val readScenario = scenario("Contest scoreboard reads (API)")
    .feed(ApiLoad.startRankFeeder(minStartRank, maxStartRank))
    .exec(ApiLoad.scoreboardRead("api-contest-scoreboard-read", contestId, pageSize))

  setUp(
    readScenario.inject(
      rampUsersPerSec(1).to(targetRps).during(rampSeconds.seconds),
      constantUsersPerSec(targetRps).during(holdSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(LoadTestAssertions.globalAssertions: _*)
}
