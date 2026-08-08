package my.oj.web.contest.submission.core;

import my.oj.web.submission.SubmissionResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContestSubmissionResultRepository extends JpaRepository<ContestSubmissionResult, Long> {

    @Query("select distinct csr.contestId from ContestSubmissionResult csr order by csr.contestId")
    List<Long> findDistinctContestIds();

    @Query("""
            select csr.id as submissionId,
                   csr.contestId as contestId,
                   s.problem.id as problemId,
                   s.user.id as userId,
                   s.contest.startTime as contestStart,
                   s.submittedTime as submittedTime,
                   csr.provisionalResult as result,
                   csr.provisionalJudgedAt as judgedAt
            from ContestSubmissionResult csr
            join csr.submission s
            where csr.id = :submissionId
            """)
    Optional<ContestSubmissionStoredJudgeResultProjection> findStoredJudgeResultBySubmissionId(
            @Param("submissionId") Long submissionId
    );

    @Query("""
            select csr.submission.id
            from ContestSubmissionResult csr
            where csr.contestId = :contestId
              and (:afterId is null or csr.submission.id > :afterId)
            order by csr.submission.id
            """)
    List<Long> findSubmissionIdsByContestId(@Param("contestId") Long contestId,
                                            @Param("afterId") Long afterId,
                                            Pageable pageable);

    @Query("""
            select csr.submission.id
            from ContestSubmissionResult csr
            where csr.contestId = :contestId
              and csr.provisionalResult = :result
              and (:afterId is null or csr.submission.id > :afterId)
            order by csr.submission.id
            """)
    List<Long> findSubmissionIdsByContestIdAndProvisionalResult(@Param("contestId") Long contestId,
                                                                @Param("result") SubmissionResult result,
                                                                @Param("afterId") Long afterId,
                                                                Pageable pageable);

    @Query("select csr from ContestSubmissionResult csr join fetch csr.submission s join fetch s.user join fetch s.problem join fetch s.contest where csr.contestId = :contestId order by s.submittedTime asc, csr.id asc")
    List<ContestSubmissionResult> findAllByContestIdWithSubmission(@Param("contestId") Long contestId);

    @Modifying
    @Query("update ContestSubmissionResult csr set csr.finalResult = csr.provisionalResult, csr.finalJudgedAt = csr.provisionalJudgedAt where csr.contestId = :contestId")
    void copyProvisionalToFinal(@Param("contestId") Long contestId);

    @Modifying
    @Query("delete from ContestSubmissionResult csr where csr.contestId = :contestId")
    void deleteByContestId(@Param("contestId") Long contestId);
}
