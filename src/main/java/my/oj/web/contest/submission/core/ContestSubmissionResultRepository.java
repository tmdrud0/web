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

    /**
     * Walks one contest's judged results as scoreboard replay rows, keyset-ordered by submission id.
     *
     * <p>{@code unjudged} filters rows whose effective result is {@code PENDING}. The scoreboard
     * script records a submission in its processed set outside the branch that skips PENDING, so
     * replaying an unjudged row would make the real judgement be skipped for good. The comparison
     * also drops rows whose effective result is null, which the {@code not null} constraint on the
     * provisional column already prevents - but a filter that fails closed is the right direction
     * for a value that decides whether a submission is swallowed.</p>
     *
     * <p>The effective result is {@code COALESCE(final, provisional)} so a replay after contest
     * finalization applies the settled result rather than the superseded provisional one.</p>
     */
    @Query("""
            select csr.submission.id as submissionId,
                   csr.contestId as contestId,
                   s.problem.id as problemId,
                   s.user.id as userId,
                   s.contest.startTime as contestStart,
                   s.submittedTime as submittedTime,
                   coalesce(csr.finalResult, csr.provisionalResult) as result
            from ContestSubmissionResult csr
            join csr.submission s
            where csr.contestId = :contestId
              and (:afterId is null or csr.submission.id > :afterId)
              and coalesce(csr.finalResult, csr.provisionalResult) <> :unjudged
            order by csr.submission.id
            """)
    List<ContestScoreboardReplayRow> findReplayRowsByContestId(@Param("contestId") Long contestId,
                                                               @Param("afterId") Long afterId,
                                                               @Param("unjudged") SubmissionResult unjudged,
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
