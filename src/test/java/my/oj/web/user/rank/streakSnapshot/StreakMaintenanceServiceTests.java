package my.oj.web.user.rank.streaksnapshot;

import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.activity.DailyActiveUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StreakMaintenanceServiceTests {

    @Autowired
    UserRepository userRepository;

    @Autowired
    StreakSnapshotService snapshotService;

    @Autowired
    StreakSnapshotRepository snapshotRepo;

    @Autowired
    DailyActiveUserRepository dailyActiveUserRepository;

    @Autowired
    StreakMaintenanceService maintenanceService;

    @Test
    @Transactional
    void ensureFreshness_resetsStaleUser_andRemovesFromSnapshot() throws Exception {
        User u = userRepository.save(User.withState(null, "dave", "p", 0L, new my.oj.web.user.Streak()));
        // active today, streak 1 -> included in snapshot
        LocalDateTime now = LocalDateTime.now();
        setField(u.getStreak(), "lastSolvedDate", now.minusHours(2));
        setField(u.getStreak(), "currentStreak", 1);
        setField(u.getStreak(), "longestStreak", 2);

        LocalDate today = now.toLocalDate();
        dailyActiveUserRepository.upsert(today.minusDays(1), u.getId(), now.minusHours(2));

        snapshotService.rebuild(today, 10);
        var before = snapshotRepo.findUserPosition(today, u.getId());
        assertThat(before).isNotNull();

        // make him stale (3 days ago)
        setField(u.getStreak(), "lastSolvedDate", now.minusDays(3));
        setField(u.getStreak(), "currentStreak", 5);

        maintenanceService.ensureFreshnessForUser(u.getId());

        // streak reset to 0 and snapshot row removed
        assertThat(u.getStreak().getCurrentStreak()).isZero();
        var after = snapshotRepo.findUserPosition(today, u.getId());
        assertThat(after).isNull();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
