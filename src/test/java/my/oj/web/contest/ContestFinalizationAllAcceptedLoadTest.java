package my.oj.web.contest;

import org.junit.jupiter.api.Disabled;
import org.springframework.test.context.TestPropertySource;

@Disabled("Default load scenario uses fiftyFifty; enable explicitly if all-accepted case is required")
@TestPropertySource(properties = "loadtest.judge.mode=allAccepted")
class ContestFinalizationAllAcceptedLoadTest extends AbstractContestFinalizationLoadTest {
}
