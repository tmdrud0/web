package my.oj.web.contest.scoreboard.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContestScoreboardOutboxRepository extends JpaRepository<ContestScoreboardOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContestScoreboardOutbox> findById(Long id);

    List<ContestScoreboardOutbox> findTop50ByStatusInOrderByCreatedAtAsc(Collection<ContestScoreboardOutboxStatus> statuses);

    void deleteByContestId(Long contestId);
}
