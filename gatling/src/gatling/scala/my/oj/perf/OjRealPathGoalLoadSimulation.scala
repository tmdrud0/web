package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class OjRealPathGoalLoadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val contestId      = propLong("perf.contestId", 1L)
  private val userPrefix     = System.getProperty("perf.userPrefix", "realgatling")
  private val userIndexStart = propInt("perf.userIndex.start", 1)
  private val userIndexEnd   = propInt("perf.userIndex.end", 10000)
  private val problemIdStart = propLong("perf.problemId.start", 1L)
  private val problemIdEnd   = propLong("perf.problemId.end", 5L)

  private val rampSeconds     = propInt("perf.rampSeconds", 30)
  private val avgHoldSeconds  = propInt("perf.avgHoldSeconds", 120)
  private val peakRampSeconds = propInt("perf.peakRampSeconds", 30)
  private val peakHoldSeconds = propInt("perf.peakHoldSeconds", 60)

  private val submitAvgRps    = propDouble("perf.submitAvgRps", 139d)
  private val submitPeakRps   = propDouble("perf.submitPeakRps", 1000d)
  private val readRps         = propDouble("perf.readRps", 2000d)
  private val submitIntervalMs = propLong("perf.submitIntervalMillis", 3100L)
  private val initialJitterMs  = propLong("perf.initialJitterMillis", 3000L)

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val avgConcurrentUsers = math.max(1, math.ceil(submitAvgRps * submitIntervalMs / 1000d).toInt)
  private val peakConcurrentUsers = math.max(1, math.ceil(submitPeakRps * submitIntervalMs / 1000d).toInt)
  private val readHoldSeconds = avgHoldSeconds + peakRampSeconds + peakHoldSeconds
  private val totalDuration = (rampSeconds + readHoldSeconds).seconds

  require(userIndexEnd >= userIndexStart,
    "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart,
    "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(rampSeconds > 0, "perf.rampSeconds must be greater than 0")
  require(avgHoldSeconds > 0, "perf.avgHoldSeconds must be greater than 0")
  require(peakRampSeconds > 0, "perf.peakRampSeconds must be greater than 0")
  require(peakHoldSeconds > 0, "perf.peakHoldSeconds must be greater than 0")
  require(submitAvgRps > 0d, "perf.submitAvgRps must be greater than 0")
  require(submitPeakRps >= submitAvgRps,
    "perf.submitPeakRps must be greater than or equal to perf.submitAvgRps")
  require(readRps > 0d, "perf.readRps must be greater than 0")
  require(submitIntervalMs > 0L, "perf.submitIntervalMillis must be greater than 0")
  require(initialJitterMs >= 0L, "perf.initialJitterMillis must be greater than or equal to 0")
  require(peakConcurrentUsers <= availableUsers,
    s"derived peak concurrent users ($peakConcurrentUsers) must be <= available users ($availableUsers)")

  // Sharing the HTTP connection pool is required at this concurrency. Without it, each virtual
  // user opens connections until Windows exhausts its ephemeral ports and reports BindException,
  // which is a client-side limit rather than a server measurement.
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

  private val loginPage = http("real-login-page-once")
    .get("/login")
    .check(status.is(200))

  private val login = http("real-login-once")
    .post("/login")
    .disableFollowRedirect
    .formParam("userName", "#{userName}")
    .formParam("pass", "#{password}")
    .check(status.in(302, 303))

  private val submissionForm = http("real-submission-form-once")
    .get("/problems/#{problemId}/submission")
    .check(status.is(200))

  private val submit = http("real-contest-submit")
    .post("/problems/#{problemId}/submission")
    .disableFollowRedirect
    .formParam("code", "#{code}")
    .check(status.in(302, 303))
    .check(headerRegex("Location", "(.*)").saveAs("submitRedirect"))

  private val submitRedirect = http("real-contest-submit-redirect")
    .get("#{submitRedirect}")
    .check(status.is(200))
    .check(regex("Contest submission has been queued").exists)

  private val randomSubmissionData = exec { session =>
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = s"// mixed-real-${session("userName").as[String]}-${java.util.UUID.randomUUID()}%0Aint main(){return 0;}"
    session
      .set("problemId", problemId)
      .set("code", code)
  }

  private val initialJitter = pause(_ =>
    Random.between(0L, initialJitterMs + 1L).millis
  )

  private val submitScenario = scenario("Real contest submissions (OJ target)")
    .feed(loginFeeder)
    .exec(loginPage)
    .exec(login)
    .exitHereIfFailed
    .exec(randomSubmissionData)
    .exec(submissionForm)
    .exitHereIfFailed
    .exec(initialJitter)
    .forever {
      pace(submitIntervalMs.millis)
        .exec(session => session.remove("submitRedirect"))
        .exec(randomSubmissionData)
        .exec(submit)
        .doIf(session => session.contains("submitRedirect")) {
          exec(submitRedirect)
        }
    }

  // Verified against the perf-profile application on 2026-08-06 with a new cookie-free client:
  // GET /contests/{id}?tab=scoreboard returned 200 at the same URI, set no cookie, and rendered the
  // live scoreboard rather than the login page. Reads therefore stay anonymous; aroundMe is
  // omitted so its controller default remains false and login work does not contaminate read load.
  // Requiring both the scoreboard heading and either its empty state or table makes a blank or
  // substituted 200 response fail the check.
  private val scoreboardRendered =
    regex("""(?s)<h3[^>]*>\s*Live Scoreboard\s*</h3>.*?(?:Scoreboard data will appear once submissions are judged\.|<table\b)""").exists

  private val readRequest = http("real-contest-scoreboard-read")
    .get(s"/contests/$contestId")
    .queryParam("tab", "scoreboard")
    .check(status.is(200))
    .check(scoreboardRendered)

  private val readScenario = scenario("Real contest scoreboard reads (OJ target)")
    .exec(readRequest)

  setUp(
    submitScenario.inject(
      rampConcurrentUsers(1).to(avgConcurrentUsers).during(rampSeconds.seconds),
      constantConcurrentUsers(avgConcurrentUsers).during(avgHoldSeconds.seconds),
      rampConcurrentUsers(avgConcurrentUsers).to(peakConcurrentUsers).during(peakRampSeconds.seconds),
      constantConcurrentUsers(peakConcurrentUsers).during(peakHoldSeconds.seconds)
    ),
    readScenario.inject(
      rampUsersPerSec(1).to(readRps).during(rampSeconds.seconds),
      constantUsersPerSec(readRps).during(readHoldSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .maxDuration(totalDuration)
    .assertions(LoadTestAssertions.globalAssertions: _*)
}
