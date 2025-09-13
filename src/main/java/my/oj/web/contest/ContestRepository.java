package my.oj.web.contest;

import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    @Query("SELECT new my.oj.web.contest.dto.ContestDto(c.id, c.name, c.startTime, c.endTime) " +
            "FROM Contest c ORDER BY c.id DESC")
    List<ContestDto> findAllList();

    @Query("""
    SELECT new my.oj.web.contest.dto.ContestDetailDto(
    c.id, c.name, c.startTime, c.endTime)
    FROM Contest c WHERE c.id = :id
    """)
    Optional<ContestDetailDto> findDetailById(@Param("id") Long id);

}
