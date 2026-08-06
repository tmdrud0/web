package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class OjGoalLoadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double = java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val contestId      = propLong("perf.contestId", 1L)
  private val userIdStart    = propLong("perf.userId.start", 1L)
  private val userIdEnd      = propLong("perf.userId.end", 10000L)
  private val problemIdStart = propLong("perf.problemId.start", 1L)
  private val problemIdEnd   = propLong("perf.problemId.end", 5L)
  private val minStartRank   = propLong("perf.startRank.min", 1L)
  private val maxStartRank   = propLong("perf.startRank.max", 100000L)
  private val pageSize       = propInt("perf.pageSize", 100)

  private val rampSeconds     = propInt("perf.rampSeconds", 30)
  private val avgHoldSeconds  = propInt("perf.avgHoldSeconds", 120)
  private val peakRampSeconds = propInt("perf.peakRampSeconds", 30)
  private val peakHoldSeconds = propInt("perf.peakHoldSeconds", 60)

  private val submitAvgRps  = propDouble("perf.submitAvgRps", 139d)
  private val submitPeakRps = propDouble("perf.submitPeakRps", 1000d)
  private val readRps       = propDouble("perf.readRps", 2000d)

  require(userIdEnd >= userIdStart, "perf.userId.end must be greater than or equal to perf.userId.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(maxStartRank >= minStartRank, "perf.startRank.max must be greater than or equal to perf.startRank.min")
  require(pageSize > 0, "perf.pageSize must be greater than 0")
  require(submitPeakRps >= submitAvgRps, "perf.submitPeakRps must be greater than or equal to perf.submitAvgRps")

  private val readHoldSeconds = avgHoldSeconds + peakRampSeconds + peakHoldSeconds

  // Without a shared pool every virtual user opens its own connection, so the 2000 RPS read
  // stream exhausts the Windows ephemeral port range (16,384 ports, 120s TIME_WAIT) within
  // seconds. Measured without it: BindException on 100% of requests, climbing past 55,000 while
  // the server sat idle - a client-side limit reported as a server result. This was the last
  // simulation still missing the setting that 1625d55 applied to the others.
  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling")
    .shareConnections

  private val submitFeeder = Iterator.continually {
    val userId = Random.between(userIdStart, userIdEnd + 1)
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = java.util.UUID.randomUUID().toString
    Map(
      "userId" -> userId,
      "problemId" -> problemId,
      "code" -> code
    )
  }

  private val readFeeder = Iterator.continually {
    Map("startRank" -> Random.between(minStartRank, maxStartRank + 1))
  }

  private val submitRequest = http("contest-submit")
    .post("/perf/contest/submit")
    .body(StringBody(
      """{"userId":${userId},"problemId":${problemId},"code":"${code}"}"""
    ))
    .asJson
    .check(status.in(200, 201))
    .check(jsonPath("$.submissionId").exists)

  private val readRequest = http("contest-scoreboard-read")
    .get("/perf/contest/scoreboard")
    .queryParam("contestId", contestId)
    .queryParam("startRank", "${startRank}")
    .queryParam("size", pageSize)
    .check(status.is(200))
    .check(jsonPath("$.contestId").is(contestId.toString))

  private val submitScenario = scenario("Contest submissions (OJ target)")
    .feed(submitFeeder)
    .exec(submitRequest)

  private val readScenario = scenario("Contest scoreboard reads (OJ target)")
    .feed(readFeeder)
    .exec(readRequest)

  setUp(
    submitScenario.inject(
      rampUsersPerSec(1).to(submitAvgRps).during(rampSeconds.seconds),
      constantUsersPerSec(submitAvgRps).during(avgHoldSeconds.seconds),
      rampUsersPerSec(submitAvgRps).to(submitPeakRps).during(peakRampSeconds.seconds),
      constantUsersPerSec(submitPeakRps).during(peakHoldSeconds.seconds)
    ),
    readScenario.inject(
      rampUsersPerSec(1).to(readRps).during(rampSeconds.seconds),
      constantUsersPerSec(readRps).during(readHoldSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(LoadTestAssertions.globalAssertions: _*)
}
