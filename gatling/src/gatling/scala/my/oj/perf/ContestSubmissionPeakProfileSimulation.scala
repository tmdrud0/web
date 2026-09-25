package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.http.Predef._

import scala.concurrent.duration._

import OpenBurstLoad.{Arrival, AuthContext, AuthContextFile, AuthContextPool}
import PeakProfileLoad._

/**
 * The contest peak profile as open arrivals: the baseline rate B with a peak on top of it, each
 * segment `constantUsersPerSec(rate).during(seconds)` and, by default, `.randomized` so the arrivals
 * are a Poisson process.
 *
 * The sessions are prepared beforehand (`AuthPrepSimulation`), one per arrival, exactly as the open
 * burst does, so the arrival window holds submissions and nothing else, and no arrival shares an
 * account with another (the submission limiter is keyed on (contest, user)).
 *
 * A marker user injected at time zero reads the clock, writes the segment trace the harness labels
 * its samples and its analysis with, and arms the recorder with the same instant.
 */
class ContestSubmissionPeakProfileSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)

  private val baseUrl         = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val problemIdStart  = propLong("perf.problemId.start", 1L)
  private val problemIdEnd    = propLong("perf.problemId.end", 5L)
  private val authContextFile = System.getProperty("perf.authContextFile", "")
  private val artifactDir     = System.getProperty("perf.artifactDir", ".")
  private val traceFile       = System.getProperty("perf.stageTraceFile", "")
  private val baseRps         = java.lang.Double.parseDouble(System.getProperty("perf.peakBaseRps", "5"))
  private val segmentsText    = System.getProperty("perf.peakSegments", "90:1,60:5,30:10,60:5,120:1")
  private val randomized      = java.lang.Boolean.parseBoolean(System.getProperty("perf.peakRandomized", "true"))
  private val completionTimeoutSeconds = propInt("perf.peakCompletionTimeoutSeconds", 120)

  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(authContextFile.nonEmpty, "perf.authContextFile names the prepared sessions; each arrival replays one")
  require(traceFile.nonEmpty, "perf.stageTraceFile names where the segment schedule is written for the harness to read")

  private val profile = PeakProfileLoad.parse(baseRps, segmentsText, completionTimeoutSeconds)
  private val contexts = AuthContextFile.read(authContextFile)
  private val pool = new AuthContextPool(contexts)

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  private val markerScenario = scenario("Peak profile schedule marker")
    .exec { session =>
      Recorder.arm(profile, System.currentTimeMillis(), artifactDir, pool.size, randomized, traceFile)
      session
    }

  private val arrivalScenario = scenario("Contest submissions (peak profile)")
    .exec { session =>
      val context = pool.next()
      session
        .set("userName", context.userName)
        .set("authCookieName", context.cookieName)
        .set("authCookieValue", context.cookieValue)
        .set("authContext", context)
    }
    .exec(ApiLoad.randomSubmissionData(problemIdStart, problemIdEnd, "oj-peak"))
    .exec { session =>
      val context = session.attributes.get("authContext").collect { case c: AuthContext => c }
        .getOrElse(throw new IllegalStateException("the arrival was not given a prepared session"))
      val arrival = Recorder.recordArrival(context.id)
      session.set("arrival", arrival)
    }
    .exec(addCookie(Cookie("#{authCookieName}", "#{authCookieValue}")))
    .exec(ApiLoad.submitCapturingOutcome)
    .exec { session =>
      session.attributes.get("arrival").collect { case a: Arrival => a }.foreach(_.complete(session))
      Recorder.recordServed(pool.servedCount)
      session
    }

  private val injection: List[OpenInjectionStep] = profile.segments.toList.map { s =>
    val step = constantUsersPerSec(s.rps).during(s.seconds.seconds)
    if (randomized) step.randomized else step
  }

  setUp(
    markerScenario.inject(atOnceUsers(1)),
    arrivalScenario.inject(injection)
  ).protocols(httpProtocol)
    .maxDuration(profile.maxDurationSeconds.seconds)

  Recorder.installShutdownHook()
}
