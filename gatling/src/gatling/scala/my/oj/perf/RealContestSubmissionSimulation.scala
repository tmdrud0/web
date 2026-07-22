package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class RealContestSubmissionSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl         = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val userPrefix      = System.getProperty("perf.userPrefix", "realgatling")
  private val userIndexStart  = propInt("perf.userIndex.start", 1)
  private val userIndexEnd    = propInt("perf.userIndex.end", 1000)
  private val problemIdStart  = propLong("perf.problemId.start", 1L)
  private val problemIdEnd    = propLong("perf.problemId.end", 5L)
  private val rampSeconds     = propInt("perf.rampSeconds", 10)
  private val holdSeconds     = propInt("perf.holdSeconds", 30)
  private val targetRps       = propDouble("perf.targetRps", 20d)

  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(targetRps > 0d, "perf.targetRps must be greater than 0")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .contentTypeHeader("application/x-www-form-urlencoded")
    .userAgentHeader("Gatling")
    .shareConnections

  private val feeder = Iterator.continually {
    val userIndex = Random.between(userIndexStart, userIndexEnd + 1)
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = s"// gatling-$userIndex-${java.util.UUID.randomUUID()}%0Aint main(){return 0;}"
    Map(
      "userName"  -> s"${userPrefix}_user_$userIndex",
      "password"  -> "pass",
      "problemId" -> problemId,
      "code"      -> code
    )
  }

  private val loginPage = http("login-page")
    .get("/login")
    .check(status.is(200))

  private val login = http("login")
    .post("/login")
    .disableFollowRedirect
    .formParam("userName", "#{userName}")
    .formParam("pass", "#{password}")
    .check(status.in(302, 303))
    .check(headerRegex("Location", """.*/problems.*|/problems"""))

  private val submissionForm = http("submission-form")
    .get("/problems/#{problemId}/submission")
    .check(status.is(200))

  private val submit = http("contest-submit-real")
    .post("/problems/#{problemId}/submission")
    .disableFollowRedirect
    .formParam("code", "#{code}")
    .check(status.in(302, 303))

  private val submitScenario = scenario("Real contest submissions")
    .feed(feeder)
    .exec(loginPage)
    .exec(login)
    .exec(submissionForm)
    .exec(submit)

  setUp(
    submitScenario.inject(
      rampUsersPerSec(1).to(targetRps).during(rampSeconds.seconds),
      constantUsersPerSec(targetRps).during(holdSeconds.seconds)
    )
  ).protocols(httpProtocol)
}
