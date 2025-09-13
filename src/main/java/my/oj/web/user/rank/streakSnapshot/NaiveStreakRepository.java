package my.oj.web.user.rank.streaksnapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NaiveStreakRepository extends JpaRepository<my.oj.web.user.User, Long> {

    interface NaiveRowProjection {
        Long getUserId();
        String getName();
        Integer getCurrentStreak();
        java.time.LocalDateTime getLastSolvedDate();
    }

    @Query(value = """
        SELECT u.id AS userId,
               u.name AS name,
               CASE
                 WHEN DATEDIFF(CURDATE(), DATE(u.streak_last_solved_date)) <= 1 THEN u.streak_current_streak
                 ELSE 0
               END AS currentStreak,
               u.streak_last_solved_date AS lastSolvedDate
        FROM `user` u
        WHERE CASE
                 WHEN DATEDIFF(CURDATE(), DATE(u.streak_last_solved_date)) <= 1 THEN u.streak_current_streak
                 ELSE 0
              END > 0
        ORDER BY currentStreak DESC, u.streak_last_solved_date ASC, u.id ASC
        LIMIT :offset, :limit
    """, nativeQuery = true)
    List<NaiveRowProjection> fetchNaivePage(@Param("offset") int offset, @Param("limit") int limit);
}

