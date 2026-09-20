package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A judge that always answers the same way to the same submission.
 *
 * <p>{@link ContestProvisionalJudgement} accepts nothing the scoreboard counts, so a load run under
 * it leaves every user on zero and gives a recovery measurement no work to lose;
 * {@link LatencyProfileContestJudgement} produces solves but draws its verdicts from
 * {@link java.util.concurrent.ThreadLocalRandom}, so two runs of the same scenario judge the same
 * submission differently and end on different scoreboards. This stub is the third answer: real
 * solves, decided by the submitted source alone.</p>
 *
 * <p>The source is the only input, and the load generator derives that source from
 * {@code (user, submission index)} in its deterministic mode, so the pair
 * {@code (user, nth submission)} names the same problem and the same verdict in every run. What a run
 * is then free to vary - and the only thing this experiment wants it to vary - is when those
 * submissions arrived relative to the rollback.</p>
 *
 * <p>Hashing rather than, say, accepting every third submission is what keeps the two outcomes
 * uncorrelated with anything the load model controls. A source that merely counted would put every
 * accepted attempt at a predictable position in each user's sequence, and a rollback that removes a
 * contiguous tail would then remove a systematically different mix of solves and wrong answers than a
 * rollback elsewhere - the lost tail is the experiment's independent variable, and it must not also
 * be moving the composition of what was lost.</p>
 *
 * <p>Off unless {@code contest.submission.judge.deterministic.enabled} is true, and backed off by the
 * two other stubs so exactly one implementation exists in any context. The back-off with
 * {@link LatencyProfileContestJudgement} is worth stating: this stub ignores how long a judgement
 * takes, so enabling both would silently drop the latency shape the other one exists to reproduce.
 * With both stubs backing off, the pair leaves no implementation at all, and the injection point
 * fails at startup - which is the loud answer to a configuration that asked for two judges.</p>
 */
@Component
@ConditionalOnExpression("'${contest.submission.judge.deterministic.enabled:false}' == 'true' && "
        + "'${contest.submission.judge.latency.enabled:false}' == 'false'")
@EnableConfigurationProperties(ContestJudgeDeterministicProperties.class)
public class DeterministicContestJudgement implements ContestSubmissionJudgement {

    private final ContestJudgeDeterministicProperties properties;

    public DeterministicContestJudgement(ContestJudgeDeterministicProperties properties) {
        this.properties = properties;
    }

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        return properties.isAccepted(draw(submission.getCode()))
                ? SubmissionResult.ACCEPTED
                : SubmissionResult.WRONG_ANSWER;
    }

    /**
     * A per-mille draw that depends on nothing but the submitted source.
     *
     * <p>SHA-256 rather than {@link String#hashCode()}: the latter is specified and stable, so it
     * would work, but it is also a polynomial over 16-bit characters that a load generator's source
     * templates can drive into narrow buckets by accident. Thirty-two bits of the digest are spread
     * over the whole range whatever the sources look like, and the remaining bias of reducing a
     * uniform 64-bit draw modulo {@value ContestJudgeDeterministicProperties#PERMILLE} is on the
     * order of one part in 2^54, which no run of this experiment can resolve.</p>
     *
     * <p>A null source is judged rather than refused. {@code code} is nullable on the projection and
     * a judge that threw here would fail the submission instead of scoring it, which would show up in
     * the run as an ingress failure that has nothing to do with recovery.</p>
     */
    static int draw(String code) {
        byte[] hashed = sha256().digest((code == null ? "" : code).getBytes(StandardCharsets.UTF_8));
        return Math.floorMod(ByteBuffer.wrap(hashed).getLong(), ContestJudgeDeterministicProperties.PERMILLE);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
