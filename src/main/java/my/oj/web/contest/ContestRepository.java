package my.oj.web.contest;

import my.oj.web.contest.dto.ContestDetailDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    @Query("""
    SELECT new my.oj.web.contest.dto.ContestDetailDto(
    c.id, c.name, c.startTime, c.endTime)
    FROM Contest c WHERE c.id = :id
    """)
    Optional<ContestDetailDto> findDetailById(@Param("id") Long id);

}
