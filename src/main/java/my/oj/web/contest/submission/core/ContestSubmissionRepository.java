package my.oj.web.contest.submission.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContestSubmissionRepository extends JpaRepository<ContestSubmission, Long> {

    @Query("select cs from ContestSubmission cs where cs.id in :ids order by cs.id")
    List<ContestSubmission> findAllByIdInOrderById(@Param("ids") Collection<Long> ids);

    Optional<ContestSubmission> findByContestIdAndProblemIdAndUserIdAndCodeHash(Long contestId, Long problemId, Long userId, String codeHash);

    void deleteByContestId(Long contestId);

    @Query("select coalesce(max(cs.id), 0) from ContestSubmission cs")
    Long findMaxId();
}
