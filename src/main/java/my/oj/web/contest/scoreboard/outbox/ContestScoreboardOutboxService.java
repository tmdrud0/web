package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContestScoreboardOutboxService {

    private final ContestScoreboardOutboxRepository repository;

    @Transactional
    public void deleteByContestId(Long contestId) {
        repository.deleteByContestId(contestId);
    }
}
