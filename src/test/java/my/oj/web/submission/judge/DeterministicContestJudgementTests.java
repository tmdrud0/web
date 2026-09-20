package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicContestJudgementTests {

    private static final int PERMILLE = ContestJudgeDeterministicProperties.PERMILLE;

    @Test
    void drawsTheSameValueForTheSameSourceEveryTime() {
        String code = "// perf-user_1-7%0Aint main(){return 0;}";

        int first = DeterministicContestJudgement.draw(code);

        assertThat(DeterministicContestJudgement.draw(code)).isEqualTo(first);
        assertThat(DeterministicContestJudgement.draw(new String(code.toCharArray()))).isEqualTo(first);
    }

    @Test
    void drawsWithinThePermilleRangeForEverySource() {
        for (String code : sources(5000)) {
            assertThat(DeterministicContestJudgement.draw(code)).isBetween(0, PERMILLE - 1);
        }
    }

    @Test
    void judgesANullSourceRatherThanThrowing() {
        DeterministicContestJudgement judgement =
                new DeterministicContestJudgement(new ContestJudgeDeterministicProperties(true, 400));

        assertThat(judgement.judgeSubmission(submissionWithCode(null))).isNotNull();
    }

    @Test
    void acceptsAtTheConfiguredRateAcrossARealisticSourceSet() {
        ContestJudgeDeterministicProperties properties =
                new ContestJudgeDeterministicProperties(true, 400);
        List<String> codes = sources(20000);

        long accepted = codes.stream()
                .filter(code -> properties.isAccepted(DeterministicContestJudgement.draw(code)))
                .count();

        // The point is not precision but that the run produces solves at roughly the rate asked for.
        // A hash that clustered would leave the scoreboard either empty or fully solved, and either
        // would make a lost tail of results invisible to the consistency check.
        assertThat(accepted / (double) codes.size()).isBetween(0.37d, 0.43d);
    }

    @Test
    void spreadsEverySourceAcrossTheWholeRange() {
        Set<Integer> distinct = new HashSet<>();
        Map<Integer, Integer> bucketCounts = new HashMap<>();

        for (String code : sources(10000)) {
            int draw = DeterministicContestJudgement.draw(code);
            distinct.add(draw);
            bucketCounts.merge(draw / 100, 1, Integer::sum);
        }

        assertThat(distinct).hasSizeGreaterThan(900);
        assertThat(bucketCounts.keySet()).hasSize(10);
        // No decile may carry more than a fifth of the range when a tenth is expected; a hash that
        // folded the source template's structure into the draw would show up here.
        assertThat(bucketCounts.values()).allSatisfy(count -> assertThat(count).isLessThan(2000));
    }

    @Test
    void mapsTheDrawOntoAcceptedAndWrongAnswer() {
        DeterministicContestJudgement judgement =
                new DeterministicContestJudgement(new ContestJudgeDeterministicProperties(true, 500));
        Map<SubmissionResult, Integer> seen = new HashMap<>();

        for (String code : sources(2000)) {
            seen.merge(judgement.judgeSubmission(submissionWithCode(code)), 1, Integer::sum);
        }

        assertThat(seen.keySet())
                .containsExactlyInAnyOrder(SubmissionResult.ACCEPTED, SubmissionResult.WRONG_ANSWER);
        assertThat(seen.get(SubmissionResult.ACCEPTED)).isGreaterThan(0);
        assertThat(seen.get(SubmissionResult.WRONG_ANSWER)).isGreaterThan(0);
    }

    @Test
    void neverAcceptsAtARateOfZeroAndAlwaysBelowAThousand() {
        ContestJudgeDeterministicProperties never = new ContestJudgeDeterministicProperties(true, 0);
        ContestJudgeDeterministicProperties always = new ContestJudgeDeterministicProperties(true, 1000);

        assertThat(never.isAccepted(0)).isFalse();
        assertThat(always.isAccepted(PERMILLE - 1)).isTrue();
    }

    @Test
    void treatsAnOmittedRateAsTheDefaultAndClampsOneThatCannotBeMeant() {
        assertThat(new ContestJudgeDeterministicProperties(true, null).effectiveAcceptPermille())
                .isEqualTo(400);
        assertThat(new ContestJudgeDeterministicProperties(true, -1).effectiveAcceptPermille())
                .isZero();
        assertThat(new ContestJudgeDeterministicProperties(true, 5000).effectiveAcceptPermille())
                .isEqualTo(PERMILLE);
    }

    private static ContestSubmissionJudgeProjection submissionWithCode(String code) {
        ContestSubmissionJudgeProjection submission = mock(ContestSubmissionJudgeProjection.class);
        when(submission.getCode()).thenReturn(code);
        return submission;
    }

    /**
     * Sources shaped like the ones the load generator's deterministic mode produces: a comment
     * carrying the user and the submission index, then a fixed body. Reproducing the shape matters,
     * because a hash is only as uniform as the strings it is given.
     */
    private static List<String> sources(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            codes.add("// perf-user_" + (index % 200 + 1) + "-" + (index / 200)
                    + "%0Aint main(){return 0;}");
        }
        return codes;
    }
}
