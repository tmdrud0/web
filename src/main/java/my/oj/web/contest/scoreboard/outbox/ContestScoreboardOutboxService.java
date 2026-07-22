package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContestScoreboardOutboxService {

    private final ContestScoreboardOutboxRepository repository;

    @Transactional
    public boolean insertPendingIfAbsent(Long contestSubmissionId,
                                         Long contestId,
                                         Long problemId,
                                         Long userId,
                                         LocalDateTime contestStart,
                                         LocalDateTime submittedTime,
                                         SubmissionResult result,
                                         LocalDateTime judgedAt) {
        return repository.insertPendingIfAbsent(
                contestSubmissionId,
                contestId,
                problemId,
                userId,
                contestStart,
                submittedTime,
                judgedAt,
                result.name(),
                LocalDateTime.now()
        ) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ContestScoreboardOutbox> findByContestSubmissionId(Long contestSubmissionId) {
        return repository.findByContestSubmissionId(contestSubmissionId);
    }

    @Transactional
    public ContestScoreboardOutbox lockById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Contest scoreboard outbox not found: " + id));
    }
}
