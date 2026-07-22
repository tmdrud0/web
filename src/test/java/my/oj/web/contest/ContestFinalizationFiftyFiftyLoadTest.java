package my.oj.web.contest;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "loadtest.judge.mode=fiftyFifty")
class ContestFinalizationFiftyFiftyLoadTest extends AbstractContestFinalizationLoadTest {
}
