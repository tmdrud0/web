package my.oj.web.user.rank.streak;

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
               u.streak_current_streak AS currentStreak,
               u.streak_last_solved_date AS lastSolvedDate
        FROM user u
        WHERE u.streak_current_streak > 0
        ORDER BY u.streak_current_streak DESC, u.streak_last_solved_date ASC, u.id ASC
        LIMIT :offset, :limit
    """, nativeQuery = true)
    List<NaiveRowProjection> fetchNaivePage(@Param("offset") int offset, @Param("limit") int limit);
}
