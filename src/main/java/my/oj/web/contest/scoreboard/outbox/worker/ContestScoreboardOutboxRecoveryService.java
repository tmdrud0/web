package my.oj.web.contest.scoreboard.outbox.worker;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
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

    @Transactional
    public int requeueLostTail(int batchSize) {
        long currentSequence = scoreboardApplier.currentSequence();
        List<Long> ids = outboxRepository.findIdsAboveRedisSequence(
                currentSequence,
                PageRequest.of(0, Math.max(1, batchSize))
        );
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
