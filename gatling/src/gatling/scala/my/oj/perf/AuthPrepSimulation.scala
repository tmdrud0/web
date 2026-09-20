package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.core.session.Session
import io.gatling.http.Predef._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.atomic.{AtomicBoolean, LongAdder}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

import OpenBurstLoad.{AuthContext, AuthContextFile}

/**
 * The sessions the burst submits with, established before it starts and kept in a file.
 *
 * The earlier burst runs logged in as they submitted: a 1000 starts/s arrival stream that also has to
 * open 1000 sessions a second spends its measured window on logins, and the run cannot tell a slow
 * login from a slow submission because they are the same request name at the same instant. That is
 * what the 2026-09-20 evidence shows - every refused login replaced its session, the replacement took
 * the next account from a non-circular feeder, and the feeder emptied before the engine persisted a
 * single submission.
 *
 * So the prep is a separate phase with its own arrival rate on purpose, not a slow start to the
 * measured one:
 *
 *   - nothing is measured here, so there is no window to keep clean and no reason to hurry. The
 *     accounts are spread over the whole window at a rate the previous runs were measured carrying
 *     (about 200/s against the 310/s the warm-up phase had already done with no refusals at all),
 *     which is what keeps a session storm from arriving as a burst of its own.
 *   - the measurement then starts with every session already established, so section 6's "0 logins
 *     during the measurement window" is a property of the model rather than a hope about timing.
 *   - one session per arrival, because the API's submission rate limiter is keyed on
 *     (contest, user): a pool smaller than the arrival count would put two concurrent submissions
 *     on one user, and the second would be refused by the limiter rather than by the system under
 *     test.
 *
 * What it writes is a temporary experimental artifact - account name and session cookie per context,
 * both of which the database already holds. No endpoint was added and no authentication is bypassed:
 * the sessions are made by the product's own `POST /api/login` and replayed as the cookie the
 * response set.
 */
class AuthPrepSimulation extends Simulation {

  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val userPrefix     = System.getProperty("perf.userPrefix", "loadtest")
  private val userIndexStart = propInt("perf.userIndex.start", 1)
  private val userIndexEnd   = propInt("perf.userIndex.end", 10000)
  private val prepRps        = propDouble("perf.authPrepRps", 200d)
  private val prepSeconds    = propInt("perf.authPrepSeconds", 60)
  private val contextFile    = System.getProperty("perf.authContextFile", "")
  private val artifactDir    = System.getProperty("perf.artifactDir", ".")
  private val completionTimeoutSeconds = propInt("perf.authPrepCompletionTimeoutSeconds", 60)

  /**
   * The whole `name=value` pair rather than the value alone, because the pair is what an arrival has
   * to replay: `addCookie` needs the name back, and a generator that kept only the value would be
   * guessing at the name it belongs to.
   *
   * What is captured is whatever the response set - the check does not filter by name - and the name
   * is then compared with `perf.authCookieName`. A response that set only some other cookie would be
   * counted as a preparation failure instead of being replayed as a session, so a renamed session
   * cookie stops the run with a number rather than putting 401s inside the measured window. `;` and
   * `,` end the pair, so a response that also set another cookie still yields the first one.
   */
  private val expectedCookieName = System.getProperty("perf.authCookieName", "SESSION")
  private val sessionCookie = headerRegex("Set-Cookie", "([A-Za-z0-9_.-]+=[^;,\\s]+)").find.saveAs("authCookiePair")

  private val plannedArrivals = math.round(prepRps * prepSeconds.toDouble)
  private val availableUsers = userIndexEnd - userIndexStart + 1

  require(prepRps > 0d, s"perf.authPrepRps must be greater than 0 (got $prepRps)")
  require(prepSeconds >= 1, s"perf.authPrepSeconds must be at least 1 (got $prepSeconds)")
  require(contextFile.nonEmpty, "perf.authContextFile names where the prepared sessions are written")
  require(expectedCookieName.trim.nonEmpty, "perf.authCookieName names the session cookie the burst replays")
  require(userIndexEnd >= userIndexStart, "perf.userIndex.end must be greater than or equal to perf.userIndex.start")
  // The login feeder is a non-circular list of one account per session by design - a recycled
  // account would be a second live session sharing its rate-limit and dedup state - so an arrival
  // count above the pool is an exhausted feeder, not a slower login.
  require(plannedArrivals <= availableUsers,
    s"the preparation schedules $plannedArrivals logins but only $availableUsers accounts are seeded")

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  private val loginFailures  = new LongAdder
  private val missingCookies = new LongAdder
  private val unexpectedCookieNames = new LongAdder
  private val contexts = ListBuffer.empty[AuthContext]

  private val prepScenario = scenario("Open burst auth preparation")
    .feed(ApiLoad.loginFeeder(userPrefix, userIndexStart, userIndexEnd))
    .exec(ApiLoad.login.check(sessionCookie))
    .exec { session =>
      // Collected under a lock rather than in a concurrent collection because the identifier has to
      // be the position in the file: two contexts claiming the same id would make the burst's
      // per-attempt record unable to say which session an arrival used.
      if (session.isFailed) {
        loginFailures.increment()
      } else {
        val pair = session.attributes.get("authCookiePair").map(_.toString).getOrElse("")
        val separator = pair.indexOf('=')
        if (separator <= 0 || separator == pair.length - 1) {
          missingCookies.increment()
        } else if (pair.substring(0, separator) != expectedCookieName) {
          unexpectedCookieNames.increment()
        } else {
          val userName = session.attributes.get("userName").map(_.toString).getOrElse("")
          contexts.synchronized {
            contexts += AuthContext(contexts.size, userName, pair.substring(0, separator), pair.substring(separator + 1))
          }
        }
      }
      session
    }

  setUp(
    prepScenario.inject(constantUsersPerSec(prepRps).during(prepSeconds.seconds))
  ).protocols(httpProtocol)
    // Injection stops at prepSeconds; this is only the room left for logins already in flight to be
    // answered, and it is far above the sub-second a login takes so that reaching it would mean the
    // ingress, not the schedule, was the problem.
    .maxDuration((prepSeconds + completionTimeoutSeconds).seconds)

  installFlush()

  private def installFlush(): Unit = {
    val written = new AtomicBoolean(false)
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = {
        if (!written.compareAndSet(false, true)) { return }
        try {
          val snapshot = contexts.synchronized(contexts.toVector)
          AuthContextFile.write(contextFile, snapshot)
          val summary = ListBuffer.empty[String]
          summary += "{"
          summary += s"""  "phase": "auth-preparation","""
          summary += s"""  "prepRps": $prepRps,"""
          summary += s"""  "prepSeconds": $prepSeconds,"""
          summary += s"""  "plannedLogins": $plannedArrivals,"""
          summary += s"""  "availableAccounts": $availableUsers,"""
          summary += s"""  "contextsPrepared": ${snapshot.size},"""
          summary += s"""  "authCookieName": "${expectedCookieName.replace("\\", "\\\\")}","""
          summary += s"""  "loginFailures": ${loginFailures.sum()},"""
          summary += s"""  "responsesWithoutASessionCookie": ${missingCookies.sum()},"""
          summary += s"""  "responsesWithAnUnexpectedCookieName": ${unexpectedCookieNames.sum()},"""
          summary += s"""  "authContextFile": "${contextFile.replace("\\", "\\\\")}""""
          summary += "}"
          Files.write(
            Paths.get(artifactDir, "auth-prep.json"),
            (summary.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        } catch {
          case error: Throwable => System.err.println(s"auth preparation flush failed: $error")
        }
      }
    }, "auth-prep-flush"))
  }
}
