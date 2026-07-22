
package my.oj.web.problem;

import my.oj.web.problem.dto.ContestProblemDto;
import my.oj.web.problem.dto.ProblemDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProblemRepository extends JpaRepository<Problem, Long>, ProblemRepositoryCustom {
    @Query("SELECT new my.oj.web.problem.dto.ProblemDto(p.id, p.name, c.id, c.name, p.contestNum) " +
            "FROM Problem p " +
            "JOIN p.contest c " +
            "WHERE p.id = :problemId")
    public ProblemDto findDtoById(@Param("problemId") Long problemId);

    @Query("SELECT new my.oj.web.problem.dto.ContestProblemDto(p.id, p.name, p.contestNum) " +
            "FROM Problem p " +
            "WHERE p.contest.id = :contestId")
    public List<ContestProblemDto> findDtoByContestId(@Param("contestId") Long contestId);

    @Query("""
            SELECT p
            FROM Problem p
            LEFT JOIN FETCH p.contest
            WHERE p.id = :problemId
            """)
    Optional<Problem> findWithContestById(@Param("problemId") Long problemId);
}
