package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContestScoreboardOutboxService {

    private final ContestScoreboardOutboxRepository repository;

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
