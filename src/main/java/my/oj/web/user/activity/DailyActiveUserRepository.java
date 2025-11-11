package my.oj.web.user.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyActiveUserRepository extends JpaRepository<DailyActiveUser, DailyActiveUser.Pk> {

    @Modifying
    @Query(value = """
        INSERT INTO daily_active_users(day, user_id, last_active_time)
        VALUES (:day, :userId, :lastActiveTime)
        ON DUPLICATE KEY UPDATE last_active_time = VALUES(last_active_time)
    """, nativeQuery = true)
    void upsert(@Param("day") LocalDate day,
                @Param("userId") Long userId,
                @Param("lastActiveTime") java.time.LocalDateTime lastActiveTime);

    @Query("select dau from DailyActiveUser dau where dau.day = :day")
    List<DailyActiveUser> findAllByDay(@Param("day") LocalDate day);

    @Modifying
    @Query("delete from DailyActiveUser dau where dau.day < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDate cutoff);
}
