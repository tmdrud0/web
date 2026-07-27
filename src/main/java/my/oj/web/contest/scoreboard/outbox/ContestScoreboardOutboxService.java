package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdatePublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ContestScoreboardOutboxService implements ContestScoreboardUpdatePublisher {

    private final ContestScoreboardOutboxRepository repository;

    @Override
    @Transactional
    public boolean publishIfAbsent(ContestScoreboardUpdate update) {
        return repository.insertPendingIfAbsent(
                update.contestSubmissionId(),
                update.contestId(),
                update.problemId(),
                update.userId(),
                update.contestStart(),
                update.submittedTime(),
                update.judgedAt(),
                update.result().name(),
                LocalDateTime.now()
        ) == 1;
    }

    @Transactional
    public ContestScoreboardOutbox lockById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Contest scoreboard outbox not found: " + id));
    }

    @Transactional
    public void deleteByContestId(Long contestId) {
        repository.deleteByContestId(contestId);
    }
}
