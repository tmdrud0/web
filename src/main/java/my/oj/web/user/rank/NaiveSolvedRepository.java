package my.oj.web.user.rank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NaiveSolvedRepository extends JpaRepository<my.oj.web.user.User, Long> {

    interface NaiveRowProjection {
        Long getId();
        String getName();
        Long getSolvedCount();
        java.time.LocalDateTime getLastSolvedDate();
    }

    @Query(value = """
        SELECT u.id AS id,
               u.name AS name,
               u.solved_count AS solvedCount,
               u.streak_last_solved_date AS lastSolvedDate
        FROM `user` u
        ORDER BY u.solved_count DESC, u.streak_last_solved_date ASC, u.id ASC
        LIMIT :offset, :limit
    """, nativeQuery = true)
    List<NaiveRowProjection> fetchNaivePage(@Param("offset") int offset, @Param("limit") int limit);
}

