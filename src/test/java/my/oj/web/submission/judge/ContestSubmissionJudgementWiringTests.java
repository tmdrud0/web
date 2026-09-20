package my.oj.web.submission.judge;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three judges are alternatives of one interface, and which one exists is decided by two
 * independent settings. This pins the four combinations down, because the failure mode is silent
 * rather than loud: two beans of the same interface do not stop a context from starting, they only
 * make the injection point ambiguous at the moment something first asks for one.
 */
class ContestSubmissionJudgementWiringTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ContestProvisionalJudgement.class,
                    LatencyProfileContestJudgement.class,
                    DeterministicContestJudgement.class);

    @Test
    void selectsTheProvisionalStubWhenNothingIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ContestSubmissionJudgement.class);
            assertThat(context.getBean(ContestSubmissionJudgement.class))
                    .isInstanceOf(ContestProvisionalJudgement.class);
        });
    }

    @Test
    void selectsTheDeterministicStubWhenOnlyItIsEnabled() {
        contextRunner
                .withPropertyValues("contest.submission.judge.deterministic.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestSubmissionJudgement.class);
                    assertThat(context.getBean(ContestSubmissionJudgement.class))
                            .isInstanceOf(DeterministicContestJudgement.class);
                    assertThat(context.getBean(ContestJudgeDeterministicProperties.class).enabled())
                            .isTrue();
                });
    }

    @Test
    void selectsTheLatencyStubWhenOnlyItIsEnabled() {
        contextRunner
                .withPropertyValues("contest.submission.judge.latency.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestSubmissionJudgement.class);
                    assertThat(context.getBean(ContestSubmissionJudgement.class))
                            .isInstanceOf(LatencyProfileContestJudgement.class);
                });
    }

    /**
     * Both stubs back off, so the interface has no implementation. In this runner that is only an
     * absent bean; in the application it is a failure to construct
     * {@code ContestSubmissionJudgeProcessor}, and therefore a context that does not start. Asking
     * for two judges has no answer, and the setting pair is left to say so loudly rather than having
     * one stub quietly win.
     */
    @Test
    void selectsNothingWhenBothSubstitutesAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "contest.submission.judge.latency.enabled=true",
                        "contest.submission.judge.deterministic.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ContestSubmissionJudgement.class));
    }
}
