package my.oj.web.user.rank.streaksnapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StreakSnapshotRepository extends JpaRepository<StreakSnapshotUser, Long> {

    interface PageRowProjection {
        Long getUserId();
        String getName();
        Integer getRank();
        Integer getCurrentStreak();
        java.time.LocalDateTime getLastSolvedDate();
    }

    interface UserRankProjection {
        Integer getRank();
    }

    @Modifying
    @Query(value = "DELETE FROM streak_snapshot_user WHERE snapshot_date = :d", nativeQuery = true)
    void deleteSnapshotForDate(@Param("d") LocalDate date);

    @Modifying
    @Query(value = """
        INSERT INTO streak_snapshot_user(
            snapshot_date, user_id, current_streak, last_solved_date, `rank`
        )
        SELECT :d AS snapshot_date,
               u.id AS user_id,
               u.streak_current_streak AS current_streak,
               u.streak_last_solved_date AS last_solved_date,
               ROW_NUMBER() OVER (
                   ORDER BY
                     u.streak_current_streak DESC,
                     u.streak_last_solved_date ASC,
                     u.id ASC
               ) AS `rank`
        FROM `user` u
        JOIN (
            SELECT user_id
            FROM daily_active_users
            WHERE day = DATE_SUB(:d, INTERVAL 1 DAY)
            GROUP BY user_id
        ) dau ON dau.user_id = u.id
    """, nativeQuery = true)
    void buildSnapshotUsers(@Param("d") LocalDate date);

    @Query(value = """
        SELECT s.`rank` AS `rank`
        FROM streak_snapshot_user s
        WHERE s.snapshot_date = :d AND s.user_id = :uid
        LIMIT 1
    """, nativeQuery = true)
    UserRankProjection findUserPosition(@Param("d") LocalDate date, @Param("uid") long userId);

    @Query(value = """
        SELECT s.user_id AS userId, u.name AS name, s.`rank` AS `rank`, s.current_streak AS currentStreak, s.last_solved_date AS lastSolvedDate
        FROM streak_snapshot_user s
        JOIN `user` u ON u.id = s.user_id
        WHERE s.snapshot_date = :d AND s.`rank` BETWEEN :from AND :to
        ORDER BY s.`rank` ASC
    """, nativeQuery = true)
    List<PageRowProjection> fetchPageByRankRange(@Param("d") LocalDate date,
                                                 @Param("from") int fromRank,
                                                 @Param("to") int toRank);

    @Modifying
    @Query(value = "DELETE FROM streak_snapshot_user WHERE snapshot_date = :d AND user_id = :uid", nativeQuery = true)
    void deleteUserFromSnapshot(@Param("d") LocalDate date, @Param("uid") long userId);

    @Query(value = "SELECT COUNT(*) FROM streak_snapshot_user WHERE snapshot_date = :d", nativeQuery = true)
    long countBySnapshotDate(@Param("d") LocalDate date);
}
