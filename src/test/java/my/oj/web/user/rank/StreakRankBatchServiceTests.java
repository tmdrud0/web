package my.oj.web.user.rank;

import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.activity.DailyActiveUser;
import my.oj.web.user.activity.DailyActiveUserRepository;
import my.oj.web.user.rank.streak.StreakRankBatchService;
import my.oj.web.user.rank.streak.UserStreakRankSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StreakRankBatchServiceTests {

    @Autowired
    private StreakRankBatchService batchService;

    @Autowired
    private DailyActiveUserRepository dailyActiveUserRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserStreakRankSnapshotRepository snapshotRepository;


    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
        dailyActiveUserRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void rebuildFor_updatesStreakAndSnapshot() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate targetDay = today.minusDays(1);
        LocalDate previousDay = targetDay.minusDays(1);
        User user = createUserWithStreak("alice", 1, 1, previousDay.atTime(21, 0));

        dailyActiveUserRepository.save(new DailyActiveUser(targetDay, user.getId(), targetDay.atTime(22, 0)));
        dailyActiveUserRepository.save(new DailyActiveUser(previousDay, user.getId(), previousDay.atTime(21, 0)));

        user.getStreak().recordSolveAt(targetDay.atTime(22, 0));
        userRepository.saveAndFlush(user);

        batchService.rebuildFor(targetDay);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStreak().getCurrentStreak()).isEqualTo(2);
        assertThat(reloaded.getStreak().getLongestStreak()).isEqualTo(2);
        assertThat(reloaded.getStreak().getLastSolvedDate()).isEqualTo(targetDay.atTime(22, 0));

        var snapshotRows = snapshotRepository.fetchPage(1, 10);
        assertThat(snapshotRows).hasSize(1);
        assertThat(snapshotRows.get(0).getUserId()).isEqualTo(user.getId());
        assertThat(snapshotRows.get(0).getCurrentStreak()).isEqualTo(2);

    }

    @Test
    void rebuildFor_resetsWhenInactive() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate targetDay = today.minusDays(1);
        LocalDate previousDay = targetDay.minusDays(1);
        User user = createUserWithStreak("bob", 3, 5, previousDay.minusDays(1).atTime(20, 0));

        // user was only active on the previous day
        dailyActiveUserRepository.save(new DailyActiveUser(previousDay, user.getId(), previousDay.atTime(21, 0)));

        batchService.rebuildFor(targetDay);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStreak().getCurrentStreak()).isZero();
        assertThat(reloaded.getStreak().getLongestStreak()).isEqualTo(5);
        assertThat(snapshotRepository.count()).isZero();
    }

    private User createUserWithStreak(String name, int currentStreak, int longestStreak, LocalDateTime lastSolved) throws Exception {
        User user = User.withState(null, name, "p", 0L, new my.oj.web.user.Streak());
        setField(user.getStreak(), "currentStreak", currentStreak);
        setField(user.getStreak(), "longestStreak", longestStreak);
        setField(user.getStreak(), "lastSolvedDate", lastSolved);
        return userRepository.saveAndFlush(user);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
