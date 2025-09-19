package my.oj.web.contest.finalization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContestFinalScoreRepository extends JpaRepository<ContestFinalScore, Long> {

    void deleteByContestIdAndStatus(Long contestId, ContestFinalScoreStatus status);

    List<ContestFinalScore> findByContestIdAndStatusOrderByRankAsc(Long contestId, ContestFinalScoreStatus status);
}
