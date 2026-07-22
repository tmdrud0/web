package my.oj.web.user.rank.streak;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserStreakRankSnapshotRepository extends JpaRepository<UserStreakRankSnapshot, Long> {

    interface SnapshotRowProjection {
        Long getRank();
        Long getUserId();
        String getName();
        Integer getCurrentStreak();
        java.time.LocalDateTime getLastSolvedTime();
    }

    @Query(value = """
            SELECT s.snapshot_rank AS `rank`,
                   s.user_id AS userId,
                   u.name AS name,
                   s.current_streak AS currentStreak,
                   s.last_solved_time AS lastSolvedTime
            FROM user_streak_rank_snapshot s
            JOIN `user` u ON u.id = s.user_id
            WHERE s.snapshot_rank BETWEEN :startRank AND :endRank
            ORDER BY s.snapshot_rank
            """, nativeQuery = true)
    List<SnapshotRowProjection> fetchPage(@Param("startRank") long startRank,
                                          @Param("endRank") long endRank);

    @Query(value = """
            SELECT *
            FROM (
                SELECT s.snapshot_rank AS `rank`,
                       s.user_id AS userId,
                       u.name AS name,
                       s.current_streak AS currentStreak,
                       s.last_solved_time AS lastSolvedTime
                FROM user_streak_rank_snapshot s
                JOIN `user` u ON u.id = s.user_id
                ORDER BY s.snapshot_rank DESC
                LIMIT :limit
            ) AS tail
            ORDER BY tail.rank
            """, nativeQuery = true)
    List<SnapshotRowProjection> fetchTail(@Param("limit") int limit);

    @Query(value = """
            SELECT s.snapshot_rank AS `rank`,
                   s.user_id AS userId,
                   u.name AS name,
                   s.current_streak AS currentStreak,
                   s.last_solved_time AS lastSolvedTime
            FROM user_streak_rank_snapshot s
            JOIN `user` u ON u.id = s.user_id
            WHERE s.user_id = :userId
            """, nativeQuery = true)
    SnapshotRowProjection findByUserId(@Param("userId") long userId);

    @Query(value = """
            SELECT s.snapshot_rank AS `rank`,
                   s.user_id AS userId,
                   u.name AS name,
                   s.current_streak AS currentStreak,
                   s.last_solved_time AS lastSolvedTime
            FROM user_streak_rank_snapshot s
            JOIN `user` u ON u.id = s.user_id
            WHERE s.snapshot_rank = :rank
            """, nativeQuery = true)
    SnapshotRowProjection findByRank(@Param("rank") long rank);

}
