package my.oj.web.user.rank;

import my.oj.web.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RankRepository extends JpaRepository<User, Long> {

    // Projections for native queries (column aliases must match getters)
    interface UserKeyProjection {
        Long getId();
        Long getSolvedCount();
        java.time.LocalDateTime getLastSolvedDate();
    }

    interface UserPageRowProjection {
        Long getId();
        String getName();
        Long getSolvedCount();
        java.time.LocalDateTime getLastSolvedDate();
    }

    interface SolvedDistProjection {
        Long getN();
        Long getCnt();
    }

    // Base solved-count ranking: DESC by solved count, ASC by last solved date then user id
    @Query("SELECT u FROM User u ORDER BY u.solvedCount DESC, u.streak.lastSolvedDate ASC, u.id ASC")
    Page<User> findRankingsBySolvedCount(Pageable pageable);


    @Query(value = """
      SELECT u.id AS id, u.solved_count AS solvedCount, u.streak_last_solved_date AS lastSolvedDate
      FROM `user` u
      WHERE u.id = :uid
      """, nativeQuery = true)
    UserKeyProjection findKeyByUserId(@Param("uid") long uid);

    // Count how many users strictly outrank the current user within the same solved-count group
    @Query(value = """
      SELECT COUNT(*)
      FROM `user` u
      WHERE u.solved_count = :sc
        AND (u.streak_last_solved_date < :t
             OR (u.streak_last_solved_date = :t AND u.id < :uid))
      """, nativeQuery = true)
    long countTieBefore(@Param("sc") long sc,
                        @Param("t") LocalDateTime t,
                        @Param("uid") long uid);

    // Locate the k-th (0-based) user inside a solved-count bucket to build the page cursor
    @Query(value = """
      SELECT u.id AS id, u.solved_count AS solvedCount, u.streak_last_solved_date AS lastSolvedDate
      FROM `user` u
      WHERE u.solved_count = :sc
      ORDER BY u.streak_last_solved_date ASC, u.id ASC
      LIMIT :offset, 1
      """, nativeQuery = true)
    UserKeyProjection findKthInGroup(@Param("sc") long sc, @Param("offset") int offset);

    // Fetch a page starting from the cursor (inclusive) using keyset pagination
    @Query(value = """
      SELECT u.id AS id, u.name AS name, u.solved_count AS solvedCount, u.streak_last_solved_date AS lastSolvedDate
      FROM `user` u
      WHERE (u.solved_count < :sc)
         OR (u.solved_count = :sc AND u.streak_last_solved_date > :t)
         OR (u.solved_count = :sc AND u.streak_last_solved_date = :t AND u.id >= :uid)
      ORDER BY u.solved_count DESC, u.streak_last_solved_date ASC, u.id ASC
      LIMIT :limit
      """, nativeQuery = true)
    List<UserPageRowProjection> fetchPageFromCursor(@Param("sc") long sc,
                                                    @Param("t") LocalDateTime t,
                                                    @Param("uid") long uid,
                                                    @Param("limit") int limit);

    // Distribution of solved counts (used to rebuild bucket table)
    @Query(value = """
      SELECT u.solved_count AS n, COUNT(*) AS cnt
      FROM `user` u
      GROUP BY u.solved_count
      ORDER BY n DESC
      """, nativeQuery = true)
    List<SolvedDistProjection> solvedCountDistribution();
}
