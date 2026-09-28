package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardSequencedApplier;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * {@link ContestScoreboardSequencedApplier} over {@link ContestScoreboardRedisPollScript}.
 *
 * <p>Uses the same keys as the Stream applier - standings, processed set, allocator and mapping - so a
 * scoreboard built by one delivery reads the same through the shared reader. It never touches the Stream
 * offset or the Stream's DB-pending set.</p>
 */
public class RedisContestScoreboardSequencedApplier implements ContestScoreboardSequencedApplier {

    private final StringRedisTemplate redisTemplate;
    private final RedisContestScoreboardSequenceSource sequenceSource;

    public RedisContestScoreboardSequencedApplier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.sequenceSource = new RedisContestScoreboardSequenceSource(redisTemplate);
    }

    @Override
    public long apply(ContestScoreboardUpdate update, long expectedWatermark, long resequenceFloor) {
        if (update == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            throw new IllegalArgumentException("Scoreboard update fields are required");
        }
        Long sequence = redisTemplate.execute(
                ContestScoreboardRedisPollScript.APPLY,
                List.of(
                        ContestScoreboardRedisKeys.ranking(update.contestId()),
                        ContestScoreboardRedisKeys.summary(update.contestId(), update.userId()),
                        ContestScoreboardRedisKeys.problem(update.contestId(), update.userId(), update.problemId()),
                        ContestScoreboardRedisKeys.processed(update.contestId()),
                        ContestScoreboardRedisKeys.SEQUENCE,
                        ContestScoreboardRedisKeys.SUBMISSION_SEQUENCE
                ),
                Long.toString(update.contestSubmissionId()),
                update.result().name(),
                Long.toString(ContestScoreboardPolicy.computeContestMinutes(
                        update.contestStart(), update.submittedTime())),
                Long.toString(ContestScoreboardPolicy.PENALTY_PER_WRONG_MINUTES),
                Long.toString(ContestScoreboardPolicy.SCORE_SOLVED_WEIGHT),
                Long.toString(ContestScoreboardPolicy.SCORE_PENALTY_WEIGHT),
                Long.toString(update.userId()),
                Long.toString(expectedWatermark),
                Long.toString(resequenceFloor)
        );
        if (sequence == null) {
            throw new IllegalStateException("Redis poll scoreboard script returned no sequence");
        }
        return sequence == ContestScoreboardRedisPollScript.ROLLBACK ? ROLLBACK : sequence;
    }

    @Override
    public long allocatorSequence() {
        return sequenceSource.allocatorSequence();
    }

    @Override
    public long fenceAllocator(long atLeast) {
        Long allocator = redisTemplate.execute(
                ContestScoreboardRedisPollScript.FENCE,
                List.of(ContestScoreboardRedisKeys.SEQUENCE),
                Long.toString(atLeast)
        );
        if (allocator == null) {
            throw new IllegalStateException("Redis allocator fence returned no value");
        }
        return allocator;
    }
}
