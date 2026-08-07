package my.oj.web.contest;

import my.oj.web.contest.dto.ContestDetailDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    /**
     * The contest itself, finalisation included.
     *
     * <p>{@code finalizedAt} is projected because the caller always needs to know whether the
     * standings are final, and the page used to answer that by loading the whole entity a second
     * time - a duplicate round trip for one boolean, on a request that had already read this row.
     */
    @Query("""
    SELECT new my.oj.web.contest.dto.ContestDetailDto(
    c.id, c.name, c.startTime, c.endTime, c.finalizedAt)
    FROM Contest c WHERE c.id = :id
    """)
    Optional<ContestDetailDto> findDetailById(@Param("id") Long id);

}
