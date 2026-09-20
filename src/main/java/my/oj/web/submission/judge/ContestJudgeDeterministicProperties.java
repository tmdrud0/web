package my.oj.web.submission.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shape of the reproducible judge stub.
 *
 * <p>{@link ContestProvisionalJudgement} answers {@code PARTIAL_ACCEPTED} to everything, and the
 * scoreboard script counts only {@code ACCEPTED} as a solve. A load run under that stub therefore
 * produces a scoreboard on which nobody has solved anything, which is not a state any recovery
 * measurement can say anything about: a lost tail of all-wrong results leaves every user's summary at
 * zero, so a rollback that took away work and one that took away nothing look the same.</p>
 *
 * <p>What this stub trades away is realism of the verdict; what it buys is a load run whose
 * <em>data</em> is reproducible. The verdict is a pure function of the submitted source, so the same
 * submission is judged the same way in every run, and two runs that differ only in how requests
 * interleaved still end with the same scores, penalties and ranking in MySQL. Without that, no two
 * runs of this experiment would be measuring the same contest and their recovery times could not be
 * compared at all.</p>
 *
 * <p>Off unless {@code contest.submission.judge.deterministic.enabled} is true, and
 * {@link ContestProvisionalJudgement} backs off when it is, so exactly one implementation exists in
 * any context.</p>
 *
 * @param enabled        whether {@link DeterministicContestJudgement} replaces the immediate stub
 * @param acceptPermille submissions accepted per thousand; the rest are wrong answers
 */
@ConfigurationProperties(prefix = "contest.submission.judge.deterministic")
public record ContestJudgeDeterministicProperties(boolean enabled, Integer acceptPermille) {

    /**
     * The denominator of {@code acceptPermille}, and therefore the range
     * {@link DeterministicContestJudgement#draw(String)} produces.
     */
    static final int PERMILLE = 1000;

    private static final int DEFAULT_ACCEPT_PERMILLE = 400;

    /**
     * Clamped rather than refused, unlike the recovery settings. A rate outside {@code [0, 1000]} is
     * not a mistake an operator could mean - it is a rate that saturates at "nothing solves" or
     * "everything solves", and both are useful configurations of this stub, so the clamp is the
     * honest reading and not a silent downgrade of what was asked for.
     */
    public int effectiveAcceptPermille() {
        if (acceptPermille == null) {
            return DEFAULT_ACCEPT_PERMILLE;
        }
        return Math.max(0, Math.min(PERMILLE, acceptPermille));
    }

    /**
     * Compares against a caller-supplied draw so the decision is testable without hashing a source.
     * A rate of zero must never accept, which {@code <} gives and {@code <=} would not.
     */
    public boolean isAccepted(int draw) {
        return draw < effectiveAcceptPermille();
    }
}
