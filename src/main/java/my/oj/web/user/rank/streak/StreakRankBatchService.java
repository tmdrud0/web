package my.oj.web.user.rank.streak;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.activity.DailyActiveUser;
import my.oj.web.user.activity.DailyActiveUserRepository;
import my.oj.web.user.rank.streak.longest.LongestStreakBucketUpdater;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
public class StreakRankBatchService {

    private static final int BATCH_SIZE = 500;

    private final DailyActiveUserRepository dailyActiveUserRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final LongestStreakBucketUpdater longestStreakBucketUpdater;
    private final TransactionTemplate transactionTemplate;

    public StreakRankBatchService(DailyActiveUserRepository dailyActiveUserRepository,
                                  UserRepository userRepository,
                                  JdbcTemplate jdbcTemplate,
                                  LongestStreakBucketUpdater longestStreakBucketUpdater,
                                  TransactionTemplate transactionTemplate) {
        this.dailyActiveUserRepository = dailyActiveUserRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.longestStreakBucketUpdater = longestStreakBucketUpdater;
        this.transactionTemplate = transactionTemplate;
    }

    public void rebuildFor(LocalDate targetDay) {
        applyDailyActivity(targetDay);
        refreshSnapshotTable();
    }

    public void rebuildForYesterday() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        rebuildFor(today.minusDays(1));
    }

    BatchStats applyDailyActivity(LocalDate targetDay) {
        LocalDate previousDay = targetDay.minusDays(1);

        List<DailyActiveUser> targetEntries = dailyActiveUserRepository.findAllByDay(targetDay);
        List<DailyActiveUser> previousEntries = dailyActiveUserRepository.findAllByDay(previousDay);

        Map<Long, DailyActiveUser> todayMap = toMap(targetEntries);
        Map<Long, DailyActiveUser> previousMap = toMap(previousEntries);

        Set<Long> candidateIds = new HashSet<>();
        candidateIds.addAll(todayMap.keySet());
        candidateIds.addAll(previousMap.keySet());

        if (candidateIds.isEmpty()) {
            transactionTemplate.executeWithoutResult(status -> dailyActiveUserRepository.deleteOlderThan(previousDay));
            return BatchStats.empty();
        }

        List<Long> orderedIds = new ArrayList<>(candidateIds);
        orderedIds.sort(Long::compareTo);

        int updatedTotal = 0;
        int resetTotal = 0;

        for (int start = 0; start < orderedIds.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, orderedIds.size());
            List<Long> batchIds = orderedIds.subList(start, end);

            BatchStats batchStats = Objects.requireNonNull(transactionTemplate.execute(status ->
                    processBatch(batchIds, todayMap, previousMap)));

            updatedTotal += batchStats.updatedCount();
            resetTotal += batchStats.resetCount();
        }

        transactionTemplate.executeWithoutResult(status -> dailyActiveUserRepository.deleteOlderThan(previousDay));

        return new BatchStats(candidateIds.size(), updatedTotal, resetTotal);
    }

    private BatchStats processBatch(List<Long> batchIds,
                                    Map<Long, DailyActiveUser> todayMap,
                                    Map<Long, DailyActiveUser> previousMap) {

        List<User> batchUsers = userRepository.findAllById(batchIds);
        Map<Long, User> userMap = new HashMap<>(batchUsers.size());
        for (User user : batchUsers) {
            userMap.put(user.getId(), user);
        }

        int updated = 0;
        int reset = 0;

        for (Long userId : batchIds) {
            User user = userMap.get(userId);
            if (user == null) {
                continue;
            }

            var streak = user.getStreak();
            int oldLongest = streak.getLongestStreak();

            DailyActiveUser today = todayMap.get(userId);
            DailyActiveUser previous = previousMap.get(userId);

            if (today != null) {
                int newCurrent = previous != null ? streak.getCurrentStreak() + 1 : 1;
                streak.applyBatchResult(newCurrent);
                int newLongest = streak.getLongestStreak();
                if (newLongest > oldLongest) {
                    longestStreakBucketUpdater.handleIncrease(oldLongest, newLongest);
                }
                updated++;
            } else if (streak.getCurrentStreak() != 0) {
                streak.resetByBatch();
                reset++;
            }
        }

        return new BatchStats(batchIds.size(), updated, reset);
    }

    private Map<Long, DailyActiveUser> toMap(List<DailyActiveUser> entries) {
        Map<Long, DailyActiveUser> map = new HashMap<>(entries.size());
        for (DailyActiveUser entry : entries) {
            map.put(entry.getUserId(), entry);
        }
        return map;
    }

    private void refreshSnapshotTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS user_streak_rank_snapshot_tmp");
        jdbcTemplate.execute("CREATE TABLE user_streak_rank_snapshot_tmp LIKE user_streak_rank_snapshot");
        jdbcTemplate.execute("""
                INSERT INTO user_streak_rank_snapshot_tmp (snapshot_rank, user_id, current_streak, last_solved_time, updated_at)
                SELECT ROW_NUMBER() OVER (ORDER BY u.streak_current_streak DESC, u.streak_last_solved_date ASC, u.id ASC) AS snapshot_rank,
                       u.id,
                       u.streak_current_streak,
                       u.streak_last_solved_date,
                       NOW(6)
                FROM `user` u
                WHERE u.streak_current_streak > 0
                """);
        jdbcTemplate.execute("RENAME TABLE user_streak_rank_snapshot TO user_streak_rank_snapshot_old, " +
                "user_streak_rank_snapshot_tmp TO user_streak_rank_snapshot");
        jdbcTemplate.execute("DROP TABLE IF EXISTS user_streak_rank_snapshot_old");
    }

    private record BatchStats(int totalCandidates, int updatedCount, int resetCount) {
        private static BatchStats empty() {
            return new BatchStats(0, 0, 0);
        }
    }
}
