package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ContestScoreboardOutboxService {

    private final ContestScoreboardOutboxRepository repository;
    private final ContestScoreboardOutboxSequenceStore sequenceStore;

    @Transactional
    public ContestScoreboardOutbox enqueue(Long contestSubmissionId,
                                           Long contestId,
                                           Long problemId,
                                           Long userId,
                                           LocalDateTime contestStart,
                                           LocalDateTime submittedTime,
                                           SubmissionResult result,
                                           LocalDateTime judgedAt) {
        ContestScoreboardOutboxPayload payload = new ContestScoreboardOutboxPayload(
                contestSubmissionId,
                contestId,
                problemId,
                userId,
                contestStart,
                submittedTime,
                result,
                judgedAt
        );
        Long redisSequence = sequenceStore.reserveSequence(payload);
        ContestScoreboardOutbox outbox = ContestScoreboardOutbox.pending(
                contestSubmissionId,
                contestId,
                problemId,
                userId,
                contestStart,
                submittedTime,
                result,
                judgedAt,
                redisSequence
        );
        return repository.save(outbox);
    }

    @Transactional
    public ContestScoreboardOutbox lockById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Contest scoreboard outbox not found: " + id));
    }
}
