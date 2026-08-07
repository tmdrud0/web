package my.oj.perf

import io.gatling.commons.validation._
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
 *
 * That population is also the contest. Reads page through the scoreboard the submissions build,
 * so `ceil(peakRps * interval)` is both the number of sessions and the number of participants,
 * and `perf.startRank.max` is derived from it rather than given - the first version of this
 * simulation set the two independently, drew ranks from 1..100,000 against 620 participants, and
 * spent the run reading past the end of an almost empty scoreboard.
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
  private val pageSize       = propInt("perf.pageSize", 100)

  private val rampSeconds     = propInt("perf.rampSeconds", 30)
  private val avgHoldSeconds  = propInt("perf.avgHoldSeconds", 120)
  private val peakRampSeconds = propInt("perf.peakRampSeconds", 30)
  private val peakHoldSeconds = propInt("perf.peakHoldSeconds", 60)

  private val submitAvgRps  = propDouble("perf.submitAvgRps", 139d)
  private val submitPeakRps = propDouble("perf.submitPeakRps", 200d)
  private val readRps       = propDouble("perf.readRps", 300d)

  // How long a user waits between submissions. With the closed model this is what converts a
  // target rate into a population - ceil(rps * interval) users, not rps arrivals a second - so it
  // decides two things at once: the pace and the size of the contest. The rate is what the caller
  // asked for either way; the interval is what says whether that rate comes from a few users
  // submitting constantly or from a field of them submitting occasionally, and the scoreboard
  // these reads page through is the second number. Choosing it is choosing the contest.
  private val submitIntervalMs = propLong("perf.submitIntervalMillis", 3100L)
  // One interval, and derived rather than given so it stays one. Spreading a user's first
  // submission uniformly across a whole interval makes the expected number of submissions from a
  // session exactly its lifetime divided by the interval - which is the rate schedule the
  // scenario's expected counts are computed from. Any shorter and the population fires an extra
  // burst as it arrives; any longer and it starts behind the schedule and never catches up.
  private val initialJitterMs  = propLong("perf.initialJitterMillis", submitIntervalMs)

  private val availableUsers = userIndexEnd - userIndexStart + 1
  private val avgConcurrentUsers = math.max(1, math.ceil(submitAvgRps * submitIntervalMs / 1000d).toInt)
  private val peakConcurrentUsers = math.max(1, math.ceil(submitPeakRps * submitIntervalMs / 1000d).toInt)
  private val readHoldSeconds = avgHoldSeconds + peakRampSeconds + peakHoldSeconds
  private val totalDuration = (rampSeconds + readHoldSeconds).seconds

  // The contest this reads is the contest these submissions build, so the population the closed
  // model creates is exactly the range of ranks that has rows in it. Deriving the range from that
  // population rather than accepting it as a number keeps the two from drifting apart, which is
  // how the previous run measured nothing: 620 participants against startRank drawn from
  // 1..100,000 put 99.4% of reads past the end of the scoreboard, where the reader answers with a
  // single ZCARD and returns early. A page that has rows costs 102 Redis commands. Web CPU of
  // 33-41% was therefore a ZCARD benchmark, roughly a hundredth of the request it claimed to be.
  private val minStartRank = propLong("perf.startRank.min", 1L)
  private val maxStartRank = propLong("perf.startRank.max", peakConcurrentUsers.toLong)

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
    .check(jsonPath("$.id").exists)

  private val randomSubmissionData = exec { session =>
    val problemId = Random.between(problemIdStart, problemIdEnd + 1)
    val code = s"// oj-${session("userName").as[String]}-${java.util.UUID.randomUUID()}%0Aint main(){return 0;}"
    session.set("problemId", problemId).set("code", code)
  }

  // Sessions start together after the ramp, so without this they would submit in lockstep and
  // deliver the target rate as one spike per interval instead of a steady stream.
  private val initialJitter = pause(_ =>
    (if (initialJitterMs > 0L) Random.between(0L, initialJitterMs) else 0L).millis
  )

  // 202 is the accept; 503 is the admission limiter refusing work it cannot queue, which is
  // backpressure rather than a fault and is counted separately by the harness.
  private val submit = http("api-contest-submit")
    .post("/api/problems/#{problemId}/submissions")
    .body(StringBody("""{"code":"#{code}"}"""))
    .asJson
    .check(status.is(202))
    .check(jsonPath("$.submissionId").exists)

  // How many rows this page must carry, computed from the two numbers the server itself used:
  // the startRank we asked for and the participant count it reports. The reader reads ZCARD and
  // clamps its range to it, so this is the exact row count rather than a lower bound, and it is
  // right while the scoreboard is still filling - early in the ramp the expectation is zero and
  // an empty page passes.
  private def expectedRows(startRank: Long, totalParticipants: Long): Int =
    math.max(0L, math.min(pageSize.toLong, totalParticipants - startRank + 1L)).toInt

  // Counting the rows is the check. The previous one asked for entries[0] and marked it optional,
  // so it passed on any 200 including the empty pages this run was almost entirely making - a
  // check that cannot fail, guarding against the one thing the endpoint it replaced got wrong.
  private val readRequest = http("api-contest-scoreboard-read")
    .get(s"/api/contests/$contestId/scoreboard")
    .queryParam("startRank", "#{startRank}")
    .queryParam("size", pageSize)
    .check(status.is(200))
    .check(jsonPath("$.totalParticipants").ofType[Long].saveAs("totalParticipants"))
    .check(jsonPath("$.entries[*].userId").count.is { session =>
      for {
        total <- session("totalParticipants").validate[Long]
        start <- session("startRank").validate[Long]
      } yield expectedRows(start, total)
    })

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
