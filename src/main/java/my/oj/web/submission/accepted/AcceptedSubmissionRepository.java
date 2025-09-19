package my.oj.web.submission.accepted;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AcceptedSubmissionRepository extends JpaRepository<AcceptedSubmission, Long> {
    @Query("SELECT a.problem.id FROM AcceptedSubmission a " +
            "WHERE a.user.id = :userId AND a.problem.id IN :problemIds")
    List<Long> findSolvedProblemIdsInList(@Param("userId") Long userId,
                                          @Param("problemIds") List<Long> problemIds);

    @Query("SELECT a.problem.id FROM AcceptedSubmission a WHERE a.user.id = :userId")
    List<Long> findSolvedProblemIdsByUserId(@Param("userId") Long userId);
}
