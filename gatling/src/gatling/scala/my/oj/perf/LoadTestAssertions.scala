package my.oj.perf

import io.gatling.core.Predef._

object LoadTestAssertions {

  private def propDouble(name: String, default: Double): Double =
    java.lang.Double.parseDouble(System.getProperty(name, default.toString))

  private def propInt(name: String, default: Int): Int =
    java.lang.Integer.getInteger(name, default)

  private def propLong(name: String, default: Long): Long =
    java.lang.Long.getLong(name, default)

  val minSuccessPercent: Double = propDouble("perf.assert.minSuccessPercent", 99d)
  val maxP95Millis: Int = propInt("perf.assert.p95Millis", 10_000)
  val minRequestCount: Long = propLong("perf.assert.minRequests", 1L)

  require(minSuccessPercent > 0d && minSuccessPercent <= 100d,
    "perf.assert.minSuccessPercent must be in (0, 100]")
  require(maxP95Millis > 0, "perf.assert.p95Millis must be greater than 0")
  require(minRequestCount > 0, "perf.assert.minRequests must be greater than 0")

  // Every simulation's request checks accept only its successful HTTP statuses,
  // so an HTTP 500 is counted as a failed request and violates this assertion.
  val globalAssertions = Seq(
    global.allRequests.count.gte(minRequestCount),
    global.successfulRequests.percent.gte(minSuccessPercent),
    global.responseTime.percentile3.lte(maxP95Millis)
  )
}
