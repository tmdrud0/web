package my.oj.perf

import io.gatling.core.session.Session
import io.netty.channel.nio.NioEventLoopGroup
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import OpenBurstLoad._

/**
 * The open-arrival model's arithmetic, its boundary rule, and its record of what was started.
 *
 * These are the parts of the burst that can be checked without a stack, and they are the parts a
 * mistake in would be invisible in the run: a bucket boundary off by one millisecond, a plan whose
 * window is a second longer than the rate was offered for, or a start count that quietly depends on
 * the answer arriving. Everything else about the burst is a measurement, and a measurement is
 * judged by its own evidence rather than by a unit test.
 *
 * This class sits in the simulations' source set rather than in a `test` source set because the
 * gatling plugin loads the test source set into the simulations' classpath: a test source set that
 * depended back on the compiled simulations would close the loop
 * `compileGatlingJava -> compileTestJava -> compileGatlingJava`. The build wires it to the
 * `loadModelTest` task (see `build.gradle`), which runs it over the output of the same compilation
 * the load generator runs.
 */
class OpenBurstLoadTest {

  @Test
  def planRejectsAScheduleThatCannotBeOffered(): Unit = {
    assertThrows(classOf[IllegalArgumentException], () => Plan(0d, 1d, 1, 10, 120))
    assertThrows(classOf[IllegalArgumentException], () => Plan(1000d, 0d, 1, 10, 120))
    assertThrows(classOf[IllegalArgumentException], () => Plan(1000d, 2000d, 1, 10, 120))
    assertThrows(classOf[IllegalArgumentException], () => Plan(1000d, 1d, 0, 10, 120))
    assertThrows(classOf[IllegalArgumentException], () => Plan(1000d, 1d, 1, 0, 120))
    assertThrows(classOf[IllegalArgumentException], () => Plan(1000d, 1d, 1, 10, 0))
  }

  @Test
  def planPlacesTheWindowAndTheArrivalsFromTheParametersAlone(): Unit = {
    val plan = Plan(1000d, 1d, 1, 10, 120)

    // The hold is a constant rate for a whole number of seconds, so its arrival count is exact:
    // 10 seconds at 1000/s is 10,000, which is the number the supply verdict is judged against.
    assertEquals(10000L, plan.plannedSteadyArrivals)
    // The ramp is an interpolation between 1/s and 1000/s over one second. Its count is the plan's
    // estimate and never the measured one: the ramp is outside every measured window.
    assertEquals(501L, plan.plannedRampArrivals)
    assertEquals(10501L, plan.plannedStarts)
    assertEquals(131, plan.maxDurationSeconds)

    val anchor = 1_700_000_000_000L
    assertEquals(1000L, plan.steadyStartMillis(anchor) - anchor)
    assertEquals(11000L, plan.steadyEndMillis(anchor) - anchor)
    assertEquals(11000L, plan.injectionEndMillis(anchor) - anchor)
    // The measured window is the hold and nothing else: one second of ramp before it, and no part of
    // the completion timeout inside it.
    assertEquals(plan.steadySeconds * 1000L, plan.steadyEndMillis(anchor) - plan.steadyStartMillis(anchor))
    assertEquals(plan.steadyMillis, plan.steadyEndMillis(anchor) - plan.steadyStartMillis(anchor))
  }

  @Test
  def bucketsAreCutFromTheWindowStartAndCountEachBoundaryOnce(): Unit = {
    // Deliberately not a second boundary: the window opens where the ramp ended, and epoch-aligned
    // buckets would straddle two different rates and report the boundary's deviation as the
    // injector's.
    val steadyStart = 1_700_000_000_500L
    val starts = Seq(
      steadyStart - 1L, // the last millisecond of the ramp: not in any bucket
      steadyStart, // the first millisecond of the window
      steadyStart + 999L, // the last millisecond of bucket 0
      steadyStart + 1000L, // bucket 1's start, not bucket 0's end
      steadyStart + 2500L,
      steadyStart + 2999L,
      steadyStart + 3000L // the window's own end instant: not in any bucket
    )

    val counts = bucketCounts(starts, steadyStart, 3)

    // 2, 1, 2: bucket 1 holds the one arrival at +1000ms and nothing else, because the arrival at
    // +2500ms is two seconds in. The sum is the six arrivals before the window's closing instant.
    assertEquals(Vector(2L, 1L, 2L), counts)
    assertEquals(3, counts.size)
    assertEquals(5L, counts.sum)
    // A window with no arrivals still has a bucket per second: an empty bucket is a reading, and a
    // missing one would look like a window that never opened.
    assertEquals(Vector(0L, 0L, 0L), bucketCounts(Seq.empty, steadyStart, 3))
  }

  @Test
  def bucketsCountStartsThatWereNeverAnswered(): Unit = {
    val steadyStart = 1_700_000_000_000L
    val completed = new Arrival(1L, steadyStart + 10L, 0)
    completed.completedAtMillis = steadyStart + 120L
    completed.status = "ok"
    val neverAnswered = Seq(new Arrival(2L, steadyStart + 20L, 1), new Arrival(3L, steadyStart + 30L, 2))

    val all = Seq(completed) ++ neverAnswered
    assertEquals(Vector(3L, 0L, 0L), bucketCounts(all.map(_.startedAtMillis), steadyStart, 3))
    // The same two unanswered starts on their own, and the point of the recorder: the offered rate is
    // what was started, so a response that never came back cannot lower it. Reading the client's
    // completion log as the offer is what made an earlier run report 382.3/s against the 653.8/s the
    // database recorded for the same load.
    assertEquals(Vector(2L, 0L, 0L), bucketCounts(neverAnswered.map(_.startedAtMillis), steadyStart, 3))
    assertEquals(0L, neverAnswered.count(_.completedAtMillis > 0L))
  }

  @Test
  def anAttemptIsCompletedFromTheResponseItGot(): Unit = {
    val group = new NioEventLoopGroup(1)
    try {
      val eventLoop = group.next()
      def session(userId: Long): Session = Session("open-burst", userId, eventLoop)

      val accepted = new Arrival(1L, 1000L, 0)
      val okSession = session(1L).set("responseCode", 202).set("submissionId", 4321L)
      accepted.complete(okSession)
      assertEquals("ok", accepted.status)
      assertEquals("202", accepted.responseCode)
      assertEquals("4321", accepted.submissionId)
      assertTrue(accepted.completedAtMillis > 0L)

      val refused = new Arrival(2L, 1001L, 1)
      refused.complete(session(2L).markAsFailed)
      assertEquals("ko", refused.status)
      // Nothing captured is reported as unavailable rather than as zero: a check that did not run
      // saved nothing, and a zero would read as a measured status code.
      assertEquals("unavailable", refused.responseCode)
      assertEquals("unavailable", refused.submissionId)
    } finally {
      group.shutdownGracefully()
    }
  }

  @Test
  def theRecorderGivesEveryStartItsOwnIdentity(): Unit = {
    val first = RequestStartRecorder.recordArrival(3)
    val second = RequestStartRecorder.recordArrival(4)

    assertEquals(first.attemptId + 1L, second.attemptId)
    assertEquals("incomplete", first.status)
    assertEquals(-1L, first.completedAtMillis)
    assertEquals(3, first.authContextId)
    assertTrue(first.startedAtMillis > 0L)
  }

  @Test
  def thePoolServesOneContextPerArrivalAndReportsReuse(): Unit = {
    val contexts = Vector(
      AuthContext(0, "u1", "SESSION", "v1"),
      AuthContext(1, "u2", "SESSION", "v2")
    )
    val pool = new AuthContextPool(contexts)

    assertEquals("SESSION=v1", pool.next().cookiePair)
    assertEquals("SESSION=v2", pool.next().cookiePair)
    assertEquals(0L, pool.reuseCount)
    // A third arrival has to share: the pool is served by rotation so that no arrival is ever sent
    // unauthenticated, and how far it wrapped is exactly how short the preparation was.
    assertEquals("SESSION=v1", pool.next().cookiePair)
    assertEquals(1L, pool.reuseCount)
    assertEquals(3L, pool.servedCount)
    assertThrows(classOf[IllegalArgumentException], () => new AuthContextPool(Vector.empty))
  }

  @Test
  def theContextFileRoundTripsAndRefusesAMalformedLine(): Unit = {
    val directory = Files.createTempDirectory("open-burst-contexts")
    val path = directory.resolve("auth-contexts.tsv").toString
    val contexts = Vector(AuthContext(0, "burst_user_1", "SESSION", "abc/def+gh=="), AuthContext(1, "burst_user_2", "SESSION", "xyz"))

    AuthContextFile.write(path, contexts)
    assertEquals(contexts, AuthContextFile.read(path))

    // A line that lost a field would be an arrival whose session is a guess, so the file is refused
    // rather than read with the missing field defaulted.
    Files.write(Paths.get(path), "authContextId\tuserName\tcookieName\tcookieValue\n0\tburst_user_1\tSESSION\n".getBytes(StandardCharsets.UTF_8))
    assertThrows(classOf[IllegalArgumentException], () => AuthContextFile.read(path))

    // A file that lost its header is refused too, rather than having its first context read as the
    // header and dropped: a silently missing context is an arrival that shares a session with
    // another, which is the one thing the preparation is sized to avoid.
    Files.write(Paths.get(path), "0\tburst_user_1\tSESSION\tabc/def+gh==\n".getBytes(StandardCharsets.UTF_8))
    val headerFailure = assertThrows(classOf[IllegalArgumentException], () => AuthContextFile.read(path))
    assertTrue(headerFailure.getMessage.contains("header"))
  }
}
