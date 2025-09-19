package my.oj.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom

class RankSimulation extends Simulation {

  private def propInt(name: String, default: Int): Int =
    Integer.getInteger(name, default).intValue()

  // System props with sensible defaults
  private val baseUrl       = System.getProperty("baseUrl", "http://localhost:8080")
  private val users         = propInt("users", 1000)
  private val pageSize      = propInt("pageSize", 10)
  private val pageMax       = propInt("pageMax", 100000)
  private val vus           = propInt("vus", 50)
  private val rampSeconds   = propInt("rampSeconds", 30)
  private val holdSeconds   = propInt("holdSeconds", 60)
  private val rampDownSecs  = propInt("rampDownSeconds", 30)

  private val totalDuration = (rampSeconds + holdSeconds + rampDownSecs).seconds

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .inferHtmlResources()
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Gatling")
    .disableWarmUp

  // Random user + page feeder
  private val feeder = Iterator.continually {
    val uid  = ThreadLocalRandom.current().nextInt(users) + 1
    val page = ThreadLocalRandom.current().nextInt(Math.max(1, pageMax))
    Map(
      "userName" -> s"u$uid",
      "pass"     -> "p",
      "page"     -> page.toString
    )
  }

  private val login = exec(
    http("login")
      .post("/login")
      .formParam("userName", "${userName}")
      .formParam("pass", "${pass}")
      .check(status.in(200, 302))
  )

  private def setRandomPage = exec { session =>
    val p = ThreadLocalRandom.current().nextInt(Math.max(1, pageMax))
    session.set("page", p.toString)
  }

  private def rank(sort: String) = exec(
    http(s"rank-$sort")
      .get(s"/rank?sortBy=$sort&page=${'$'}{page}&size=$pageSize")
      .check(status.is(200))
  )

  private def aroundMe(sort: String) = exec(
    http(s"rank-$sort-aroundMe")
      .get(s"/rank?sortBy=$sort&aroundMe=true&size=$pageSize")
      .check(status.is(200))
  )

  // Scenarios
  private val solvedScenario = scenario("SolvedRank")
    .feed(feeder)
    .exec(login)
    .forever {
      setRandomPage
        .exec(rank("solvedCount"))
        .pause(1.second)
    }

  private val streakScenario = scenario("StreakRank")
    .feed(feeder)
    .exec(login)
    .forever {
      setRandomPage
        .exec(rank("streak"))
        .pause(1.second)
    }

  private val aroundMeScenario = scenario("AroundMe")
    .feed(feeder)
    .exec(login)
    .forever {
      exec(aroundMe("solvedCount"))
        .pause(1.second)
        .exec(aroundMe("streak"))
        .pause(1.second)
    }

  setUp(
    solvedScenario.inject(
      rampConcurrentUsers(0) to vus during (rampSeconds.seconds),
      constantConcurrentUsers(vus) during (holdSeconds.seconds),
      rampConcurrentUsers(vus) to 0 during (rampDownSecs.seconds)
    ),
    streakScenario.inject(
      rampConcurrentUsers(0) to vus during (rampSeconds.seconds),
      constantConcurrentUsers(vus) during (holdSeconds.seconds),
      rampConcurrentUsers(vus) to 0 during (rampDownSecs.seconds)
    ),
    aroundMeScenario.inject(
      rampConcurrentUsers(0) to vus during (rampSeconds.seconds),
      constantConcurrentUsers(vus) during (holdSeconds.seconds),
      rampConcurrentUsers(vus) to 0 during (rampDownSecs.seconds)
    )
  )
    .protocols(httpProtocol)
    .maxDuration(totalDuration)
}



