package my.oj.web.contest.submission.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContestSubmissionRepository extends JpaRepository<ContestSubmission, Long> {

    @Query("""
            select cs.id as submissionId,
                   c.id as contestId,
                   cs.problem.id as problemId,
                   cs.user.id as userId,
                   c.startTime as contestStart,
                   cs.submittedTime as submittedTime,
                   cs.code as code
            from ContestSubmission cs
            join cs.contest c
            where cs.id = :submissionId
            """)
    Optional<ContestSubmissionJudgeProjection> findJudgeProjectionById(@Param("submissionId") Long submissionId);

    @Query("select cs from ContestSubmission cs where cs.id in :ids order by cs.id")
    List<ContestSubmission> findAllByIdInOrderById(@Param("ids") Collection<Long> ids);

    void deleteByContestId(Long contestId);

    @Query("select coalesce(max(cs.id), 0) from ContestSubmission cs")
    Long findMaxId();
}
