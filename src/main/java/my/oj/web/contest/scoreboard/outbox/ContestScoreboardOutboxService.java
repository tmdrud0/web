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

    @Transactional
    public ContestScoreboardOutbox enqueue(Long contestSubmissionId,
                                           Long contestId,
                                           Long problemId,
                                           Long userId,
                                           LocalDateTime contestStart,
                                           LocalDateTime submittedTime,
                                           SubmissionResult result,
                                           LocalDateTime judgedAt) {
        ContestScoreboardOutbox outbox = ContestScoreboardOutbox.pending(
                contestSubmissionId,
                contestId,
                problemId,
                userId,
                contestStart,
                submittedTime,
                result,
                judgedAt
        );
        return repository.save(outbox);
    }

    @Transactional
    public ContestScoreboardOutbox lockById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Contest scoreboard outbox not found: " + id));
    }
}