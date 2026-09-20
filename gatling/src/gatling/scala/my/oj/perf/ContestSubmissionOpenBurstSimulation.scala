package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.http.Predef._

import scala.concurrent.duration._

import OpenBurstLoad._

/**
 * 1000 submissions a second, offered as arrivals rather than as a population.
 *
 * The closed model answers "what does this population sustain"; this answers "what happens when the
 * arrivals keep coming". A closed model at 1000 RPS is a population of `ceil(1000 * pace)` users
 * pacing themselves, so when the stack slows its users are still waiting on their last response and
 * the offered rate falls away with the server - which is why the earlier runs' offered 382.3/s
 * against 653.8/s accepted was read, wrongly, as a fact about capacity. Here the schedule is a clock:
 * `rampUsersPerSec(1).to(1000)` then `constantUsersPerSec(1000)`, and an arrival that is still waiting
 * does not hold back the next one.
 *
 * Three things make the measurement mean something:
 *
 *   - the sessions already exist. Preparation is its own phase (`AuthPrepSimulation`), so this JVM's
 *     arrival window contains submissions and nothing else. Section 6's "0 logins inside the measured
 *     window" is then a property of the model rather than a hope about timing, and the client log can
 *     be counted to prove it.
 *   - the ramp is excluded. A ramp is not a rate: it starts at one arrival a second and reaches 1000
 *     one second later, and averaging that into a steady-state number would understate the offer by
 *     roughly the factor the ramp is short. The measured window is the hold, and it is the hold only.
 *   - the starts are counted where they are started, before any response exists. See
 *     `RequestStartRecorder`: the client's own report is a completion log, and a request that never
 *     came back is missing from it, which is exactly the case a saturated stack produces.
 *
 * What this run does not do is decide whether the offer succeeded. Ten seconds of 1000 starts/s is
 * either delivered or it is not, and the verdict is computed from the recorder's own file - 9,500 to
 * 10,500 starts, no second outside a tenth of the target, no login, no refusal, no generator error.
 * A shortfall of the *generator* and a shortfall of the *stack* are different findings, and the
 * parameters are validated here so that the first cannot be mistaken for the second.
 */
class ContestSubmissionOpenBurstSimulation extends Simulation {

  private def propLong(name: String, default: Long): Long = java.lang.Long.getLong(name, default)
  private def propInt(name: String, default: Int): Int = java.lang.Integer.getInteger(name, default)
  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private val baseUrl        = System.getProperty("perf.baseUrl", "http://localhost:8080")
  private val problemIdStart = propLong("perf.problemId.start", 1L)
  private val problemIdEnd   = propLong("perf.problemId.end", 5L)
  private val authContextFile = System.getProperty("perf.authContextFile", "")
  private val artifactDir    = System.getProperty("perf.artifactDir", ".")
  private val traceFile      = Option(System.getProperty("perf.stageTraceFile")).map(_.trim).filter(_.nonEmpty)

  private val targetRps                = propDouble("perf.burstTargetRps", 1000d)
  private val rampFromRps              = propDouble("perf.burstRampFromRps", 1d)
  private val rampSeconds              = propInt("perf.burstRampSeconds", 1)
  private val steadySeconds            = propInt("perf.burstSteadySeconds", 10)
  private val completionTimeoutSeconds = propInt("perf.burstCompletionTimeoutSeconds", 120)

  require(problemIdEnd >= problemIdStart, "perf.problemId.end must be greater than or equal to perf.problemId.start")
  require(authContextFile.nonEmpty,
    "perf.authContextFile names the prepared sessions; the burst replays a session per arrival rather than logging in")
  // The harness derives the measured window from this file, so a burst without it would be a load
  // with no window to judge - and the recorder would compute its buckets against an anchor nothing
  // else agreed with.
  require(traceFile.isDefined, "perf.stageTraceFile names where the arrival schedule is written for the harness to read")

  private val plan = Plan(targetRps, rampFromRps, rampSeconds, steadySeconds, completionTimeoutSeconds)

  /**
   * Read once, at construction, so a file that cannot be parsed stops the run before the injector
   * starts rather than on the arrival path: an arrival that failed to find its session would be a
   * submission answered 401 inside the measured window, which is the one thing this design exists to
   * keep out of it.
   */
  private val contexts = AuthContextFile.read(authContextFile)
  private val pool = new AuthContextPool(contexts)

  private val httpProtocol = ApiLoad.jsonProtocol(baseUrl)

  /**
   * The marker, and the reason the schedule needs one.
   *
   * The buckets are the steady window cut into whole seconds, and the window starts where the ramp
   * ended - an instant that is not on a second boundary and that only the injector knows. Deriving it
   * from the harness's wall clock would put a few milliseconds of startup error into the bucket
   * boundaries; taking it from an arrival would put the first arrival's own latency into them. So one
   * user is injected at injector time zero with the single job of reading the clock, writing the
   * trace the harness reads, and arming the recorder with the same instant. A ±10ms error in that
   * reading moves the last bucket by about a tenth of a percent, which is two orders of magnitude
   * inside the ±10% the run is judged by.
   */
  private val markerScenario = scenario("Open burst arrival-schedule marker")
    .exec { session =>
      RequestStartRecorder.arm(plan, System.currentTimeMillis(), traceFile, artifactDir, pool.size)
      session
    }

  /**
   * One arrival, one submission, one prepared session - and the arrival recorded before the request
   * is dispatched, not after it is answered.
   *
   * The session is resolved first because it is where `userName` comes from, and the workload data
   * cannot be drawn without it: the deterministic payload is derived from the account that submits
   * it (`perf.workloadSeed` mixes in the user name), so drawing the data before the session is known
   * leaves `problemId` unset and every request fails to build - 10,500 arrivals injected, none sent,
   * and a load generator that looks like a stack refusal. The recorded instant is still the last
   * thing before the dispatch, with nothing between it and the request but the cookie being attached.
   */
  private val burstScenario = scenario("Contest submissions (open arrival burst)")
    .exec { session =>
      val context = pool.next()
      session
        .set("userName", context.userName)
        .set("authCookieName", context.cookieName)
        .set("authCookieValue", context.cookieValue)
        .set("authContext", context)
    }
    .exec(ApiLoad.randomSubmissionData(problemIdStart, problemIdEnd, "oj-burst"))
    .exec { session =>
      // The context is looked up by type for the same reason the arrival is: it decides which
      // prepared session the submission is sent with, and a cast that guessed wrong would send an
      // arrival as another account - or as nobody, which the server answers 401.
      val context = session.attributes.get("authContext").collect { case context: AuthContext => context }
        .getOrElse(throw new IllegalStateException("the arrival was not given a prepared session"))
      val arrival = RequestStartRecorder.recordArrival(context.id)
      session.set("attemptId", arrival.attemptId).set("arrival", arrival)
    }
    .exec(addCookie(Cookie("#{authCookieName}", "#{authCookieValue}")))
    .exec(ApiLoad.submitCapturingOutcome)
    .exec { session =>
      // Looked up by type rather than read with `asOption`, because the completion is the only thing
      // that has to survive the round trip: a session that could not give the record back would leave
      // the attempt recorded as never answered, which reads as a hung server.
      session.attributes.get("arrival").collect { case arrival: Arrival => arrival }.foreach(_.complete(session))
      session
    }

  /**
   * The two profiles are one schedule with a boundary: the injector runs them in order, from the same
   * instant, so the first hold arrival is the first arrival after `rampMillis`.
   *
   * `maxDuration` is the completion timeout and not a length of load - the injection is already over at
   * `rampMillis + steadyMillis`. What it bounds is how long the run waits for the arrivals already in
   * flight to be answered, and an arrival still unanswered when it lands is recorded as incomplete and
   * the run is marked forced rather than clean, because a submission that never came back is not a
   * submission that was refused.
   */
  private val injectionProfile: List[OpenInjectionStep] = List(
    rampUsersPerSec(plan.rampFromRps).to(plan.targetRps).during(plan.rampSeconds.seconds),
    constantUsersPerSec(plan.targetRps).during(plan.steadySeconds.seconds)
  )

  setUp(
    markerScenario.inject(atOnceUsers(1)),
    burstScenario.inject(injectionProfile)
  ).protocols(httpProtocol)
    .maxDuration(plan.maxDurationSeconds.seconds)

  RequestStartRecorder.installShutdownHook()
}
