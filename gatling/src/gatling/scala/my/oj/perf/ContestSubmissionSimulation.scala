package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class ContestSubmissionSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val baseUrls       = Option(System.getProperty("perf.baseUrls"))
    .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSeq)
    .filter(_.nonEmpty)
    .getOrElse(Seq(baseUrl))
  private val userIdStart    = propLong("perf.userId.start", 1L)
  private val userIdEnd      = propLong("perf.userId.end", 10000L)
  private val problemIdStart = propLong("perf.problemId.start", 1L)
  private val problemIdEnd   = propLong("perf.problemId.end", 5L)
  private val rampSeconds    = propInt("perf.rampSeconds", 10)
  private val holdSeconds    = propInt("perf.holdSeconds", 30)
  private val targetRps      = propDouble("perf.targetRps", 139d)

  require(userIdEnd >= userIdStart, "perf.userId.end must be greater than or equal to perf.userId.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(targetRps > 0d, "perf.targetRps must be greater than 0")

  private val httpProtocol = http
    .baseUrls(baseUrls: _*)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling")
    .shareConnections

  private val feeder = Iterator.continually {
    val userId = Random.between(userIdStart, userIdEnd + 1)
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = java.util.UUID.randomUUID().toString
    Map(
      "userId" -> userId,
      "problemId" -> problemId,
      "code" -> code
    )
  }

  private val submitRequest = http("contest-submit")
    .post("/perf/contest/submit")
    .body(StringBody(
      """{"userId":#{userId},"problemId":#{problemId},"code":"#{code}"}"""
    ))
    .asJson
    .check(status.in(200, 201))
    .check(jsonPath("$.submissionId").exists)

  private val submitScenario = scenario("Contest submissions")
    .feed(feeder)
    .exec(submitRequest)

  setUp(
    submitScenario.inject(
      rampUsersPerSec(1).to(targetRps).during(rampSeconds.seconds),
      constantUsersPerSec(targetRps).during(holdSeconds.seconds)
    )
  ).protocols(httpProtocol)
}
