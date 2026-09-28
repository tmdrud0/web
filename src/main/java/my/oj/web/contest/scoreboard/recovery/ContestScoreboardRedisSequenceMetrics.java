package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What a sequence check saw and what it did about it.
 *
 * <p>Detection without a counter is unobservable: a reused sequence that is found and repaired looks
 * exactly like a sequence that was never reused, which is the same "healthy and broken are
 * indistinguishable" problem the stream metrics exist to solve.</p>
 */
public class ContestScoreboardRedisSequenceMetrics {

    private final Counter duplicateSequencesFound;
    private final Counter resultsReplayed;
    private final Counter rounds;
    private final Counter failedRounds;
    private final Counter saturatedWindows;
    private final Counter unresolvedChecks;
    private final AtomicLong mappedSubmissions = new AtomicLong();

    public ContestScoreboardRedisSequenceMetrics(MeterRegistry registry) {
        this.duplicateSequencesFound = Counter.builder("contest.scoreboard.redis.sequence.duplicates")
                .description("Scoreboard sequences found recorded against more than one stored result")
                .register(registry);
        this.resultsReplayed = Counter.builder("contest.scoreboard.redis.sequence.replayed")
                .description("Stored results re-applied because their sequence was reused, lost, or not marked")
                .register(registry);
        this.rounds = Counter.builder("contest.scoreboard.redis.sequence.rounds")
                .description("Sequence check rounds completed")
                .register(registry);
        this.failedRounds = Counter.builder("contest.scoreboard.redis.sequence.failed")
                .description("Sequence check rounds that ended in a failure")
                .register(registry);
        this.saturatedWindows = Counter.builder("contest.scoreboard.redis.sequence.windows.saturated")
                .description("Rounds that exhausted their window budget, so a deeper loss may be unseen")
                .register(registry);
        this.unresolvedChecks = Counter.builder("contest.scoreboard.redis.sequence.unresolved")
                .description("Check passes that spent every round and still found results to replay")
                .register(registry);
        Gauge.builder("contest.scoreboard.redis.sequence.mapping.size", mappedSubmissions, AtomicLong::get)
                .description("Submissions the scoreboard holds a sequence for")
                .register(registry);
    }

    public void recordDuplicates(long found) {
        if (found > 0) {
            duplicateSequencesFound.increment(found);
        }
    }

    public void recordReplayed(long replayed) {
        if (replayed > 0) {
            resultsReplayed.increment(replayed);
        }
    }

    public void recordRound() {
        rounds.increment();
    }

    public void recordFailedRound() {
        failedRounds.increment();
    }

    public void recordSaturatedWindows() {
        saturatedWindows.increment();
    }

    public void recordUnresolved() {
        unresolvedChecks.increment();
    }

    public void recordMappedSubmissions(long size) {
        mappedSubmissions.set(size);
    }
}
