package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.http.Predef._

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.Random

class ContestSubmissionStepLoadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl         = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val userIdStart     = propLong("perf.userId.start", 1L)
  private val userIdEnd       = propLong("perf.userId.end", 10000L)
  private val problemIdStart  = propLong("perf.problemId.start", 1L)
  private val problemIdEnd    = propLong("perf.problemId.end", 5L)
  private val startRps        = propDouble("perf.startRps", 200d)
  private val stepRps         = propDouble("perf.stepRps", 200d)
  private val maxRps          = propDouble("perf.maxRps", 1000d)
  private val rampSeconds     = propInt("perf.rampSeconds", 5)
  private val stepHoldSeconds = propInt("perf.stepHoldSeconds", 10)

  require(userIdEnd >= userIdStart, "perf.userId.end must be greater than or equal to perf.userId.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(startRps > 0d, "perf.startRps must be greater than 0")
  require(stepRps > 0d, "perf.stepRps must be greater than 0")
  require(maxRps >= startRps, "perf.maxRps must be greater than or equal to perf.startRps")

  private val httpProtocol = http
    .baseUrl(baseUrl)
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

  private val submitScenario = scenario("Contest submissions (step load)")
    .feed(feeder)
    .exec(submitRequest)

  setUp(
    submitScenario.inject(buildInjectionProfile())
  ).protocols(httpProtocol)
    .assertions(LoadTestAssertions.globalAssertions: _*)

  private def buildInjectionProfile(): List[OpenInjectionStep] = {
    val targets = staircaseTargets()
    val steps = ListBuffer.empty[OpenInjectionStep]

    steps += rampUsersPerSec(1).to(targets.head).during(rampSeconds.seconds)
    steps += constantUsersPerSec(targets.head).during(stepHoldSeconds.seconds)

    targets.sliding(2).foreach {
      case Seq(previous, current) =>
        steps += rampUsersPerSec(previous).to(current).during(rampSeconds.seconds)
        steps += constantUsersPerSec(current).during(stepHoldSeconds.seconds)
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
