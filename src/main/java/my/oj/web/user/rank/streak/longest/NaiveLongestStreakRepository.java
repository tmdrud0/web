package my.oj.web.user.rank.streak.longest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NaiveLongestStreakRepository extends JpaRepository<my.oj.web.user.User, Long> {

    interface NaiveRowProjection {
        Long getId();
        String getName();
        Integer getLongestStreak();
        java.time.LocalDateTime getLastSolvedDate();
    }

    @Query(value = """
        SELECT u.id AS id,
               u.name AS name,
               u.streak_longest_streak AS longestStreak,
               u.streak_last_solved_date AS lastSolvedDate
        FROM user u
        ORDER BY u.streak_longest_streak DESC, u.streak_last_solved_date ASC, u.id ASC
        LIMIT :offset, :limit
    """, nativeQuery = true)
    List<NaiveRowProjection> fetchNaivePage(@Param("offset") int offset, @Param("limit") int limit);
}

