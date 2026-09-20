package my.oj.web.submission.judge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContestJudgeLatencyPropertiesTests {

    @Test
    void seedAndSubmissionIdProduceStableButIdSpecificDraws() {
        ContestJudgeLatencyProperties properties =
                new ContestJudgeLatencyProperties(true, 0.1, 2_000L, 10L, 42L, "submission-id");

        double first = properties.deterministicDraw(101L);

        assertThat(properties.deterministicDraw(101L)).isEqualTo(first);
        assertThat(properties.deterministicDraw(102L)).isNotEqualTo(first);
        assertThat(first).isBetween(0.0, Math.nextDown(1.0));
    }

    @Test
    void missingSeedKeepsDeterministicPathDisabled() {
        ContestJudgeLatencyProperties properties =
                new ContestJudgeLatencyProperties(true, null, null, null, null, null);

        assertThatThrownBy(() -> properties.deterministicDraw(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deterministicAssignmentsFollowConfiguredRatioAndChangeWithSeed() {
        ContestJudgeLatencyProperties first =
                new ContestJudgeLatencyProperties(true, 0.05, 2_000L, 50L, 123L, "submission-id");
        ContestJudgeLatencyProperties second =
                new ContestJudgeLatencyProperties(true, 0.05, 2_000L, 50L, 456L, "submission-id");
        int sampleSize = 20_000;
        int firstSlow = 0;
        int changedAssignments = 0;
        for (long submissionId = 0; submissionId < sampleSize; submissionId++) {
            boolean firstAssignment = first.isSlow(first.deterministicDraw(submissionId));
            boolean secondAssignment = second.isSlow(second.deterministicDraw(submissionId));
            if (firstAssignment) {
                firstSlow++;
            }
            if (firstAssignment != secondAssignment) {
                changedAssignments++;
            }
        }

        assertThat((double) firstSlow / sampleSize).isBetween(0.04, 0.06);
        assertThat(changedAssignments).isGreaterThan(0);
    }

    @Test
    void codeKeyKeepsTheSameWorkloadItemStableAcrossGeneratedSubmissionIds() {
        ContestJudgeLatencyProperties properties =
                new ContestJudgeLatencyProperties(true, 0.05, 2_000L, 50L, 123L, "code");

        assertThat(properties.deterministicDraw(1L, "stable-work-item"))
                .isEqualTo(properties.deterministicDraw(999L, "stable-work-item"));
        assertThat(properties.deterministicDraw(1L, "other-work-item"))
                .isNotEqualTo(properties.deterministicDraw(1L, "stable-work-item"));
    }

    @Test
    void codeKeyMatchesThePowerShellAnalyzerFixture() {
        ContestJudgeLatencyProperties properties =
                new ContestJudgeLatencyProperties(true, 0.05, 2_000L, 50L, 20260920L, "code");

        assertThat(properties.isSlow(properties.deterministicDraw(1L, "stable-work-item")))
                .isFalse();
        assertThat(properties.isSlow(properties.deterministicDraw(1L, "fixture-15")))
                .isTrue();
    }
}
