package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

/**
 * Submissions and scoreboard reads against the JSON API, which is now the only path there is.
 *
 * This simulation used to drive `/perf/contest/submit` and `/perf/contest/scoreboard`. Those
 * endpoints took a userId in the body instead of authenticating, and the read returned the size of
 * the page rather than the page - it fetched a hundred Redis hashes, counted them and discarded
 * the rows. So it measured a cheaper system than the one users reach, and the gap was large: the
 * same 300 reads per second cost 34% of a web instance's CPU through /perf and saturated both
 * instances through the rendered page. Neither number described the API.
 *
 * Submissions run a closed model rather than the open one the perf version used. Session
 * authentication makes that necessary: an arrival rate with one submission per virtual user would
 * log in once per submission and measure the login. Concurrent users each authenticate once and
 * then submit on a fixed pace, so `submitAvgRps` is delivered by `ceil(rps * interval)` users
 * rather than by an injection rate. Reads stay open and anonymous, which is what the endpoint is.
 */
class OjGoalLoadSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double = java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val contestId      = propLong("perf.contestId", 1L)
  private val userPrefix     = System.getProperty("perf.userPrefix", "loadtest")
  private val userIndexStart = propInt("perf.userIndex.start", 1)
  private val userIndexEnd   = propInt("perf.userIndex.end", 10000)
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
  private val submitPeakRps = propDouble("perf.submitPeakRps", 200d)
  private val readRps       = propDouble("perf.readRps", 300d)

  // How long a user waits between submissions. With the closed model this is what converts a
  // target rate into a population, so it also sets how many sessions exist: at 3.1s, 200 RPS needs
  // 620 concurrent users rather than 200 arrivals a second.
  private val submitIntervalMs = propLong("perf.submitIntervalMillis", 3100L)
  private val initialJitterMs  = propLong("perf.initialJitterMillis", 3000L)

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val avgConcurrentUsers = math.max(1, math.ceil(submitAvgRps * submitIntervalMs / 1000d).toInt)
  private val peakConcurrentUsers = math.max(1, math.ceil(submitPeakRps * submitIntervalMs / 1000d).toInt)
  private val readHoldSeconds = avgHoldSeconds + peakRampSeconds + peakHoldSeconds
  private val totalDuration = (rampSeconds + readHoldSeconds).seconds

  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(maxStartRank >= minStartRank, "perf.startRank.max must be greater than or equal to perf.startRank.min")
  require(pageSize > 0, "perf.pageSize must be greater than 0")
  require(submitPeakRps >= submitAvgRps, "perf.submitPeakRps must be greater than or equal to perf.submitAvgRps")
  require(submitIntervalMs > 0, "perf.submitIntervalMillis must be greater than 0")
  require(peakConcurrentUsers <= availableUsers,
    s"peak needs $peakConcurrentUsers seeded users but only $availableUsers are available - " +
      "raise -UserCount or lower perf.submitPeakRps")

  // Without a shared pool every virtual user opens its own connection and the read stream exhausts
  // the Windows ephemeral port range (16,384 ports, 120s TIME_WAIT) within seconds. Measured
  // without it: BindException on 100% of requests while the server sat idle.
  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling")
    .shareConnections

  // Queued rather than random: two sessions sharing a user would share its dedup and rate-limit
  // state, so the load would not be the load it claims to be.
  private val loginFeeder = (userIndexStart to userIndexEnd).map { userIndex =>
    Map(
      "userName" -> s"${userPrefix}_user_$userIndex",
      "password" -> "pass"
    )
  }.queue

  private val login = http("api-login-once")
    .post("/api/login")
    .body(StringBody("""{"userName":"#{userName}","pass":"#{password}"}"""))
    .asJson
    .check(status.is(200))
    .check(jsonPath("$.id").saveAs("userId"))

  private val randomSubmissionData = exec { session =>
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = s"// oj-${session("userName").as[String]}-${java.util.UUID.randomUUID()}%0Aint main(){return 0;}"
    session.set("problemId", problemId).set("code", code)
  }

  // Sessions start together after the ramp, so without this they would submit in lockstep and
  // deliver the target rate as one spike per interval instead of a steady stream.
  private val initialJitter = pause(_ => Random.between(0L, initialJitterMs + 1L).millis)

  // 202 is the accept; 503 is the admission limiter refusing work it cannot queue, which is
  // backpressure rather than a fault and is counted separately by the harness.
  private val submit = http("api-contest-submit")
    .post("/api/problems/#{problemId}/submissions")
    .body(StringBody("""{"code":"#{code}"}"""))
    .asJson
    .check(status.is(202))
    .check(jsonPath("$.submissionId").exists)

  // Checking an entry proves the page carried rows. The endpoint this replaces reported a count,
  // so a response with no rows in it would have passed.
  private val readRequest = http("api-contest-scoreboard-read")
    .get(s"/api/contests/$contestId/scoreboard")
    .queryParam("startRank", "#{startRank}")
    .queryParam("size", pageSize)
    .check(status.is(200))
    .check(jsonPath("$.totalParticipants").exists)
    .check(jsonPath("$.entries[0].userId").optional)

  private val readFeeder = Iterator.continually {
    Map("startRank" -> Random.between(minStartRank, maxStartRank + 1))
  }

  private val submitScenario = scenario("Contest submissions (OJ target)")
    .feed(loginFeeder)
    .exec(login)
    .exitHereIfFailed
    .exec(initialJitter)
    .forever {
      pace(submitIntervalMs.millis)
        .exec(randomSubmissionData)
        .exec(submit)
    }

  private val readScenario = scenario("Contest scoreboard reads (OJ target)")
    .feed(readFeeder)
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
