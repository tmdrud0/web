package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class RealContestSubmissionSessionReuseSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl            = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val userPrefix         = System.getProperty("perf.userPrefix", "realgatling")
  private val userIndexStart     = propInt("perf.userIndex.start", 1)
  private val userIndexEnd       = propInt("perf.userIndex.end", 1000)
  private val problemIdStart     = propLong("perf.problemId.start", 1L)
  private val problemIdEnd       = propLong("perf.problemId.end", 5L)
  private val rampSeconds        = propInt("perf.rampSeconds", 10)
  private val holdSeconds        = propInt("perf.holdSeconds", 30)
  private val targetSubmitRps    = propDouble("perf.targetSubmitRps", 100d)
  private val submitIntervalMs   = propLong("perf.submitIntervalMillis", 3100L)
  private val initialJitterMs    = propLong("perf.initialJitterMillis", 3000L)
  private val concurrentUsersArg = propInt("perf.concurrentUsers", -1)

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val derivedConcurrentUsers = math.ceil(targetSubmitRps * submitIntervalMs / 1000.0d).toInt
  private val concurrentUsers =
    if (concurrentUsersArg > 0) concurrentUsersArg
    else math.max(1, derivedConcurrentUsers)
  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(targetSubmitRps > 0d, "perf.targetSubmitRps must be greater than 0")
  require(submitIntervalMs > 0L, "perf.submitIntervalMillis must be greater than 0")
  require(initialJitterMs >= 0L, "perf.initialJitterMillis must be greater than or equal to 0")
  require(concurrentUsers <= availableUsers,
    s"perf.concurrentUsers ($concurrentUsers) must be <= available users ($availableUsers)")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .contentTypeHeader("application/x-www-form-urlencoded")
    .userAgentHeader("Gatling")
    .shareConnections

  private val loginFeeder = (userIndexStart to userIndexEnd).map { userIndex =>
    Map(
      "userName" -> s"${userPrefix}_user_$userIndex",
      "password" -> "pass"
    )
  }.queue

  private val loginPage = http("login-page-once")
    .get("/login")
    .check(status.is(200))

  private val login = http("login-once")
    .post("/login")
    .disableFollowRedirect
    .formParam("userName", "#{userName}")
    .formParam("pass", "#{password}")
    .check(status.in(302, 303))

  private val submissionForm = http("submission-form-once")
    .get("/problems/#{problemId}/submission")
    .check(status.is(200))

  private val submit = http("contest-submit-session-reuse")
    .post("/problems/#{problemId}/submission")
    .disableFollowRedirect
    .formParam("code", "#{code}")
    .check(status.in(302, 303))
    .check(headerRegex("Location", "(.*)").saveAs("submitRedirect"))

  private val submitRedirect = http("contest-submit-session-reuse-redirect")
    .get("#{submitRedirect}")
    .check(status.is(200))
    .check(regex("Contest submission has been queued").exists)

  private val randomSubmissionData = exec { session =>
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = s"// session-reuse-${session("userName").as[String]}-${java.util.UUID.randomUUID()}%0Aint main(){return 0;}"
    session
      .set("problemId", problemId)
      .set("code", code)
  }

  private val initialJitter = pause(session =>
    Random.between(0L, initialJitterMs + 1L).millis
  )

  private val submitScenario = scenario("Real contest submissions with session reuse")
    .feed(loginFeeder)
    .exec(loginPage)
    .exec(login)
    .exitHereIfFailed
    .exec(randomSubmissionData)
    .exec(submissionForm)
    .exitHereIfFailed
    .rendezVous(concurrentUsers)
    .exec(initialJitter)
    .during(holdSeconds.seconds) {
      pace(submitIntervalMs.millis)
        .exec(session => session.remove("submitRedirect"))
        .exec(randomSubmissionData)
        .exec(submit)
        .doIf(session => session.contains("submitRedirect")) {
          exec(submitRedirect)
        }
    }

  setUp(
    submitScenario.inject(
      rampUsers(concurrentUsers).during(rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(LoadTestAssertions.globalAssertions: _*)
}
