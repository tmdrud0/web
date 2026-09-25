package my.oj.perf

import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

import PeakProfileLoad._

/** The peak profile's arithmetic: segment boundaries, the trace the harness parses, and per-segment counts. */
class PeakProfileLoadTest {

  @Test
  def parsesTheExperimentShapeIntoContiguousSegments(): Unit = {
    val p = parse(5d, "90:1,60:5,30:10,60:5,120:1", 120)
    assertEquals(5, p.segments.size)
    assertEquals(Vector(0L, 90000L, 150000L, 180000L, 240000L), p.segments.map(_.startMillis))
    assertEquals(360000L, p.injectionMillis)
    assertEquals(Vector(5d, 25d, 50d, 25d, 5d), p.segments.map(_.rps))
    assertEquals(5550d, p.expectedArrivals, 1e-9)
    assertEquals(480, p.maxDurationSeconds)
  }

  @Test
  def refusesAMalformedProfile(): Unit = {
    assertThrows(classOf[IllegalArgumentException], () => parse(5d, "", 120))
    assertThrows(classOf[IllegalArgumentException], () => parse(5d, "90", 120))
    assertThrows(classOf[IllegalArgumentException], () => parse(5d, "0:1", 120))
    assertThrows(classOf[IllegalArgumentException], () => parse(5d, "10:0", 120))
    assertThrows(classOf[IllegalArgumentException], () => parse(0d, "10:1", 120))
  }

  @Test
  def writesOneHoldPerSegmentInTheStaircaseTraceFormat(): Unit = {
    val p = parse(70d, "90:1,60:5", 60)
    val lines = traceLines(p, 1000L)
    assertEquals("1000,anchor,-1,none,-1,0,0,0", lines.head)
    assertEquals("1000,segmentStart,0,hold,0,0,6300,70.0", lines(1))
    assertEquals("91000,segmentEnd,0,hold,0,0,6300,70.0", lines(2))
    assertEquals("91000,segmentStart,1,hold,1,0,21000,350.0", lines(3))
    assertEquals("151000,planDone,-1,none,-1,0,0,0", lines.last)
  }

  @Test
  def countsArrivalsIntoHalfOpenSegments(): Unit = {
    val p = parse(1d, "10:1,10:2", 10)
    val counts = countBySegment(p, 0L, Seq(-1L, 0L, 9999L, 10000L, 19999L, 20000L))
    assertEquals(Vector(2L, 2L), counts)
    assertEquals(Some(1), p.segmentAt(10000L).map(_.index))
    assertEquals(None, p.segmentAt(20000L))
  }
}
