package my.oj.web.contest.scoreboard.outbox.worker;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import my.oj.web.contest.scoreboard.outbox.SequencedOutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ContestScoreboardOutboxRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ContestScoreboardOutboxRecoveryService.class);

    private final ContestScoreboardOutboxRepository outboxRepository;
    private final ContestScoreboardApplier scoreboardApplier;

    @Transactional
    public int requeueDuplicateSequences(int duplicateSequenceBatchSize) {
        List<Long> duplicateSequences = outboxRepository.findDuplicateRedisSequences(
                PageRequest.of(0, Math.max(1, duplicateSequenceBatchSize))
        );
        if (duplicateSequences.isEmpty()) {
            return 0;
        }
        int requeued = outboxRepository.requeueByRedisSequenceIn(duplicateSequences);
        if (requeued > 0) {
            log.warn("Requeued {} scoreboard outbox rows with duplicate Redis sequences {}",
                    requeued, duplicateSequences);
        }
        return requeued;
    }

    /**
     * Replays rows whose sequence the store no longer knows about - a store that lost data
     * without anything colliding yet, which duplicate detection cannot see because a collision
     * only shows up once new traffic reuses the sequences.
     *
     * <p>The candidates are read first and the allocator second, and that order is the whole
     * correctness argument. A sequence reaches the outbox only after the allocator handed it
     * out, so any row visible in the query was already covered by the allocator at that moment.
     * Reading the allocator afterwards can only raise the bar - concurrent workers push it up,
     * never down. Reading it first would leave a stale bar, and every row the workers completed
     * in between would look like a lost tail and be requeued for nothing.
     */
    @Transactional
    public int requeueLostTail(int batchSize) {
        List<SequencedOutboxRow> candidates = outboxRepository.findHighestRedisSequences(
                PageRequest.of(0, Math.max(1, batchSize))
        );
        if (candidates.isEmpty()) {
            return 0;
        }

        long currentSequence = scoreboardApplier.currentSequence();
        List<Long> ids = candidates.stream()
                .filter(row -> row.redisSequence() > currentSequence)
                .map(SequencedOutboxRow::id)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }

        int requeued = outboxRepository.requeueByIdIn(ids);
        if (requeued > 0) {
            log.warn("Requeued {} scoreboard outbox rows beyond Redis sequence {}",
                    requeued, currentSequence);
        }
        return requeued;
    }
}
