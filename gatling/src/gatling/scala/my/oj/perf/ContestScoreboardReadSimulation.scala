package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class ContestScoreboardReadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double = java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl      = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val contestId    = propLong("perf.contestId", 1L)
  private val minStartRank = propLong("perf.startRank.min", 1L)
  private val maxStartRank = propLong("perf.startRank.max", 100000L)
  private val pageSize     = propInt("perf.pageSize", 100)
  private val rampSeconds  = propInt("perf.rampSeconds", 30)
  private val holdSeconds  = propInt("perf.holdSeconds", 120)
  private val targetRps    = propDouble("perf.targetRps", 2000d)

  require(maxStartRank >= minStartRank, "perf.startRank.max must be greater than or equal to perf.startRank.min")
  require(pageSize > 0, "perf.pageSize must be greater than 0")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .userAgentHeader("Gatling")

  private val feeder = Iterator.continually {
    Map("startRank" -> Random.between(minStartRank, maxStartRank + 1))
  }

  private val readRequest = http("contest-scoreboard-read")
    .get("/perf/contest/scoreboard")
    .queryParam("contestId", contestId)
    .queryParam("startRank", "${startRank}")
    .queryParam("size", pageSize)
    .check(status.is(200))
    .check(jsonPath("$.contestId").is(contestId.toString))

  private val readScenario = scenario("Contest scoreboard reads")
    .feed(feeder)
    .exec(readRequest)

  setUp(
    readScenario.inject(
      rampUsersPerSec(1).to(targetRps).during(rampSeconds.seconds),
      constantUsersPerSec(targetRps).during(holdSeconds.seconds)
    )
  ).protocols(httpProtocol)
}
