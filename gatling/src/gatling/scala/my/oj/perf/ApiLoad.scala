package my.oj.perf

import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import io.gatling.http.request.builder.HttpRequestBuilder

import scala.concurrent.duration._
import scala.util.Random

/**
 * The pieces every simulation needs now that the load goes through the product's API.
 *
 * Shared rather than copied because the copies disagreed. Each simulation used to own its own
 * scoreboard read, and one of them checked the response with a check that could not fail while
 * drawing ranks from a range a hundred times larger than the contest - the run passed and measured
 * nothing. Submitting has the same problem waiting: the API authenticates, so every simulation
 * that submits has to solve the same "log in once, then pace" problem, and a simulation that
 * solves it differently measures a different system.
 */
object ApiLoad {

  private val workloadSeed = Option(System.getProperty("perf.workloadSeed")).map(_.toLong)

  private def mix(seed: Long, text: String, sequence: Long): Long = {
    var hash = 0xcbf29ce484222325L ^ seed
    text.foreach { character =>
      hash ^= character.toLong
      hash *= 0x100000001b3L
    }
    var value = hash ^ (sequence * 0x9e3779b97f4a7c15L)
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)
  }

  /**
   * How many users a closed model needs to deliver a rate.
   *
   * A closed model has no arrival rate: it has a population and a pace, and the rate falls out as
   * `users / interval`. So this is also the number of participants the contest ends up with, and
   * the number of logins the run pays - one per session, since sessions never rotate.
   */
  def concurrentUsers(rps: Double, intervalMillis: Long): Int =
    math.max(1, math.ceil(rps * intervalMillis / 1000d).toInt)

  /**
   * Queued rather than random: two sessions sharing an account would share its dedup and
   * rate-limit state, so the load would not be the load it claims to be.
   */
  def loginFeeder(userPrefix: String, userIndexStart: Int, userIndexEnd: Int) =
    (userIndexStart to userIndexEnd).map { userIndex =>
      Map(
        "userName" -> s"${userPrefix}_user_$userIndex",
        "password" -> "pass"
      )
    }.queue

  val login: HttpRequestBuilder = http("api-login-once")
    .post("/api/login")
    .body(StringBody("""{"userName":"#{userName}","pass":"#{password}"}"""))
    .asJson
    .check(status.is(200))
    .check(jsonPath("$.id").exists)

  /**
   * One interval, so a session's expected number of submissions is exactly its lifetime divided by
   * the interval - which is the rate schedule the scenarios' expected counts are computed from.
   * Any shorter and the population fires an extra burst as it arrives; any longer and it starts
   * behind the schedule and never catches up.
   */
  def initialJitter(intervalMillis: Long): ChainBuilder =
    exec(pause(session => {
      val delay = workloadSeed match {
        case Some(seed) if intervalMillis > 0L =>
          Math.floorMod(mix(seed, session("userName").as[String], 0L), intervalMillis)
        case _ if intervalMillis > 0L => Random.between(0L, intervalMillis)
        case _ => 0L
      }
      delay.millis
    }))

  /**
   * Holds a session until an absolute instant, then releases it.
   *
   * Measured from the wall clock rather than from the schedule, because the session-preparation
   * window is the injector's ramp: a fixed pause after each user's own start would smear the
   * submission load across the whole ramp, and the point of separating login from submission is that
   * every session is ready before the load begins. A user whose login finished after the gate waits
   * zero and joins late rather than being held back, so the gate can never deadlock a run; the
   * count of sessions that started before it is readable from the run's own log afterwards.
   *
   * The wait is a Gatling pause, so the user is rescheduled rather than occupying a thread - the
   * population this is used at (thousands) would otherwise block the whole actor pool.
   */
  def waitUntil(gateMillis: Long): ChainBuilder =
    exec(pause(session => {
      val remaining = gateMillis - System.currentTimeMillis()
      if (remaining > 0L) remaining.millis else 0.millis
    }))

  def randomSubmissionData(problemIdStart: Long, problemIdEnd: Long, tag: String): ChainBuilder =
    exec { session =>
      workloadSeed match {
        case Some(seed) =>
          val sequence = session("workloadSequence").asOption[Long].getOrElse(0L) + 1L
          val userName = session("userName").as[String]
          val key = mix(seed, s"$tag:$userName", sequence)
          val problemCount = problemIdEnd - problemIdStart + 1L
          val problemId = problemIdStart + Math.floorMod(key, problemCount)
          val code = s"// $tag-seed$seed-$userName-$sequence%0Aint main(){return 0;}"
          session.set("workloadSequence", sequence).set("problemId", problemId).set("code", code)
        case None =>
          val problemId = Random.between(problemIdStart, problemIdEnd + 1)
          val code = s"// $tag-${session("userName").as[String]}-${java.util.UUID.randomUUID()}%0Aint main(){return 0;}"
          session.set("problemId", problemId).set("code", code)
      }
    }

  /**
   * 202 is the accept; 503 is the admission limiter refusing work it cannot queue, which is
   * backpressure rather than a fault and is counted separately by the harness.
   *
   * The request is built once and the checks are added per caller, so the open-arrival model's
   * variant cannot drift from the closed model's submission: same method, same path, same body.
   */
  private val submitRequest: HttpRequestBuilder = http("api-contest-submit")
    .post("/api/problems/#{problemId}/submissions")
    .body(StringBody("""{"code":"#{code}"}"""))
    .asJson

  val submit: HttpRequestBuilder = submitRequest
    .check(status.is(202))
    .check(jsonPath("$.submissionId").exists)

  /**
   * The same submission, additionally writing what the response carried into the session.
   *
   * The open-arrival recorder keeps one row per attempted submission whether or not the response
   * ever arrived, and a row for an accepted attempt that does not say which submission was accepted
   * cannot be joined against the server's own row for it. `saveAs` runs only for a check that
   * passed, so the accept status is what makes the identifier recorded: an attempt refused with 503
   * is left `unavailable` and is read from the client log, which is where the refused request's
   * status code is written.
   */
  val submitCapturingOutcome: HttpRequestBuilder = submitRequest
    .check(status.is(202).saveAs("responseCode"))
    .check(jsonPath("$.submissionId").ofType[Long].saveAs("submissionId"))

  /**
   * How many rows a scoreboard page must carry, from the two numbers the server itself used: the
   * startRank we asked for and the participant count it reports. The reader reads ZCARD once and
   * clamps its range to it, so this is the exact row count rather than a bound, and it is right
   * while the scoreboard is still filling - early in a run the expectation is zero and an empty
   * page passes.
   */
  def expectedRows(startRank: Long, totalParticipants: Long, pageSize: Int): Int =
    math.max(0L, math.min(pageSize.toLong, totalParticipants - startRank + 1L)).toInt

  /**
   * Counting the rows is the check. Asking for `entries[0]` and marking it optional passed on any
   * 200, including the empty pages a mis-ranged run is almost entirely making - a check that
   * cannot fail, guarding the one thing the endpoint it replaced got wrong.
   */
  def scoreboardRead(requestName: String, contestId: Long, pageSize: Int): HttpRequestBuilder =
    http(requestName)
      .get(s"/api/contests/$contestId/scoreboard")
      .queryParam("startRank", "#{startRank}")
      .queryParam("size", pageSize)
      .check(status.is(200))
      .check(jsonPath("$.totalParticipants").ofType[Long].saveAs("totalParticipants"))
      .check(jsonPath("$.entries[*].userId").count.is { session =>
        for {
          total <- session("totalParticipants").validate[Long]
          start <- session("startRank").validate[Long]
        } yield expectedRows(start, total, pageSize)
      })

  def startRankFeeder(minStartRank: Long, maxStartRank: Long): Iterator[Map[String, Long]] =
    Iterator.continually(Map("startRank" -> Random.between(minStartRank, maxStartRank + 1)))

  /**
   * Without a shared pool every virtual user opens its own connection and the load exhausts the
   * Windows ephemeral port range (16,384 ports, 120s TIME_WAIT) within seconds. Measured without
   * it: BindException on 100% of requests while the server sat idle. nginx also keeps 256 upstream
   * connections alive for 10,000 requests each, so sharing is what the real path looks like.
   */
  def jsonProtocol(baseUrl: String) = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling")
    .shareConnections
}
