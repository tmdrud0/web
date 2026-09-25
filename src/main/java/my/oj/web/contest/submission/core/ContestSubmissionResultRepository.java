package my.oj.web.contest.submission.core;

import my.oj.web.submission.SubmissionResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * The same rows as {@link #findReplayRowsByContestId}, newest first, keyset below {@code beforeId}.
     *
     * <p>For a rollback replay: what a rollback takes away is the newest results, so walking down from the
     * top puts them back in the first page instead of the last. The scoreboard's rules are commutative
     * over arrival order, so the order changes when the standings are right, not what they end up as.</p>
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
              and (:beforeId is null or csr.submission.id < :beforeId)
              and coalesce(csr.finalResult, csr.provisionalResult) <> :unjudged
            order by csr.submission.id desc
            """)
    List<ContestScoreboardReplayRow> findReplayRowsByContestIdNewestFirst(@Param("contestId") Long contestId,
                                                                          @Param("beforeId") Long beforeId,
                                                                          @Param("unjudged") SubmissionResult unjudged,
                                                                          Pageable pageable);

    /**
     * Sequences the scoreboard handed out more than once, oldest first.
     *
     * <p>Deliberately global rather than per contest: the allocator and the mapping the scoreboard
     * issues from are global, so a sequence reused across two contests is just as much a reuse as
     * one inside a single contest, and only a global grouping sees it.</p>
     *
     * <p>{@code afterSequence} keysets the walk past the first page. A group is a rare event, so a
     * healthy check reads one empty page and stops; a page that came back full is continued rather
     * than taken for the whole answer.</p>
     */
    @Query("""
            select csr.scoreboardAppliedSeq as appliedSequence,
                   count(csr) as resultCount
            from ContestSubmissionResult csr
            where csr.scoreboardAppliedSeq is not null
              and (:afterSequence is null or csr.scoreboardAppliedSeq > :afterSequence)
            group by csr.scoreboardAppliedSeq
            having count(csr) > 1
            order by csr.scoreboardAppliedSeq
            """)
    List<ContestScoreboardDuplicateSequence> findDuplicateAppliedSequences(
            @Param("afterSequence") Long afterSequence,
            Pageable pageable);

    /**
     * Every stored result holding one of these sequences.
     *
     * <p>Not narrowed to one row per group: the reuse is only resolved by re-applying all of them,
     * which is what gives each row a sequence of its own.</p>
     */
    @Query("""
            select csr.submission.id as submissionId,
                   csr.scoreboardAppliedSeq as appliedSequence,
                   csr.contestId as contestId,
                   s.problem.id as problemId,
                   s.user.id as userId,
                   s.contest.startTime as contestStart,
                   s.submittedTime as submittedTime,
                   coalesce(csr.finalResult, csr.provisionalResult) as result
            from ContestSubmissionResult csr
            join csr.submission s
            where csr.scoreboardAppliedSeq in :sequences
            order by csr.scoreboardAppliedSeq, csr.submission.id
            """)
    List<ContestScoreboardSequencedRow> findRowsByAppliedSequences(
            @Param("sequences") Collection<Long> sequences);

    /**
     * One descending window of sequenced results, keyset-continued on the sequence itself.
     *
     * <p>The read is not filtered by the allocator, and that is the point: the ordering rule is that
     * every database read of a sequence happens before the allocator value it will be judged
     * against. A {@code seq > allocator} comparison is only sound in that direction, because a
     * sequence reaches MySQL after the allocator issued it - so a row read first and still ahead of
     * an allocator read afterwards means the allocator moved backwards.</p>
     *
     * <p>The walk descends past the first window because a window alone leaves a hole: when the
     * allocator has fallen further than {@code checkWindowSize} results, the deepest missing rows
     * are outside the first page and never become candidates.</p>
     */
    @Query("""
            select csr.submission.id as submissionId,
                   csr.scoreboardAppliedSeq as appliedSequence,
                   csr.contestId as contestId,
                   s.problem.id as problemId,
                   s.user.id as userId,
                   s.contest.startTime as contestStart,
                   s.submittedTime as submittedTime,
                   coalesce(csr.finalResult, csr.provisionalResult) as result
            from ContestSubmissionResult csr
            join csr.submission s
            where csr.scoreboardAppliedSeq is not null
              and (:afterSequence is null or csr.scoreboardAppliedSeq < :afterSequence)
            order by csr.scoreboardAppliedSeq desc
            """)
    List<ContestScoreboardSequencedRow> findSequencedRowsDescending(
            @Param("afterSequence") Long afterSequence,
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
