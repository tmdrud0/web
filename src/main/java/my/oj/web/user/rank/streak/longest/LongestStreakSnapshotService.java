package my.oj.web.user.rank.streak.longest;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LongestStreakSnapshotService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void rebuildSnapshot() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS longest_streak_rank_snapshot_tmp");
        jdbcTemplate.execute("CREATE TABLE longest_streak_rank_snapshot_tmp LIKE longest_streak_rank_snapshot");
        jdbcTemplate.execute("""
                INSERT INTO longest_streak_rank_snapshot_tmp (snapshot_rank, user_id, longest_streak, last_solved_time, updated_at)
                SELECT ROW_NUMBER() OVER (ORDER BY u.streak_longest_streak DESC, u.streak_last_solved_date ASC, u.id ASC) AS snapshot_rank,
                       u.id,
                       u.streak_longest_streak,
                       u.streak_last_solved_date,
                       NOW(6)
                FROM `user` u
                WHERE u.streak_longest_streak > 0
                """);
        jdbcTemplate.execute("RENAME TABLE longest_streak_rank_snapshot TO longest_streak_rank_snapshot_old, " +
                "longest_streak_rank_snapshot_tmp TO longest_streak_rank_snapshot");
        jdbcTemplate.execute("DROP TABLE IF EXISTS longest_streak_rank_snapshot_old");
    }
}
