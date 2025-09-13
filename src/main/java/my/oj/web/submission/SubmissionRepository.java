package my.oj.web.submission;

import my.oj.web.problem.Problem;
import my.oj.web.submission.dto.MinimalSubmissionDto;
import my.oj.web.submission.dto.SubmissionSummaryDto;
import my.oj.web.submission.dto.SubmissionViewProjection;
import my.oj.web.user.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long>, SubmissionRepositoryCustom {

    Boolean existsByUserIdAndProblemIdAndCodeHash(Long userId, Long problemId, String codeHash);

    Optional<Submission> findFirstByUserIdAndProblemIdAndCodeHash(Long userId, Long problemId, String codeHash);

    @Query("SELECT new my.oj.web.submission.dto.MinimalSubmissionDto(s.id, s.result, s.submittedTime) " +
            "FROM Submission s " +
            "WHERE s.user.id = :userId AND s.problem.id = :problemId")
    List<MinimalSubmissionDto> findSubmissionDtosByUserIdAndProblemId(@Param("userId") Long userId,
                                                                      @Param("problemId") Long problemId);

    @Modifying
    @Query("update Submission s set s.result = :r where s.id = :id")
    int updateResult(@Param("id") Long id, @Param("r") SubmissionResult r);


    @Query("""
        select
            s.id as id,
            p.id as problemId,
            p.name as problemName,
            u.id as userId,
            u.name as username,
            s.result as result,
            s.code as code,
            s.submittedTime as submittedTime
        from Submission s
        join s.problem p
        join s.user u
        where s.id = :id
    """)
    Optional<SubmissionViewProjection> findViewById(@Param("id") Long id);
}


