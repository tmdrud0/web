package my.oj.web.user.rank.streaksnapshot;

import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.activity.DailyActiveUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(my.oj.web.submission.SubmissionConfig.class)
class StreakSnapshotRepositoryTests {

    @Autowired
    UserRepository userRepository;

    @Autowired
    StreakSnapshotRepository snapshotRepo;

    @Autowired
    DailyActiveUserRepository dailyActiveUserRepository;

    private User u1;
    private User u2;
    private User u3;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        snapshotRepo.deleteSnapshotForDate(LocalDate.now());
        dailyActiveUserRepository.deleteAll();

        u1 = userRepository.save(User.withState(null, "alice", "p", 0L, new my.oj.web.user.Streak()));
        u2 = userRepository.save(User.withState(null, "bob", "p", 0L, new my.oj.web.user.Streak()));
        u3 = userRepository.save(User.withState(null, "charlie", "p", 0L, new my.oj.web.user.Streak()));

        LocalDateTime now = LocalDateTime.now();
        setStreak(u1, now.minusHours(3), 1, 5);
        setStreak(u2, now.minusHours(1), 1, 3);
        setStreak(u3, now.minusDays(3), 4, 6);

        LocalDate today = now.toLocalDate();
        dailyActiveUserRepository.upsert(today.minusDays(1), u1.getId(), now.minusHours(3));
        dailyActiveUserRepository.upsert(today.minusDays(1), u2.getId(), now.minusHours(1));
        dailyActiveUserRepository.upsert(now.toLocalDate().minusDays(3), u3.getId(), now.minusDays(3));

        userRepository.flush();
    }

    @Test
    @Transactional
    void buildSnapshotUsers_andFetchPageAndPosition() {
        LocalDate today = LocalDate.now();

        snapshotRepo.deleteSnapshotForDate(today);
        snapshotRepo.buildSnapshotUsers(today);

        List<StreakSnapshotRepository.PageRowProjection> page = snapshotRepo.fetchPageByRankRange(today, 1, 2);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getUserId()).isEqualTo(u1.getId());
        assertThat(page.get(0).getCurrentStreak()).isEqualTo(1);
        assertThat(page.get(1).getUserId()).isEqualTo(u2.getId());

        var pos1 = snapshotRepo.findUserPosition(today, u1.getId());
        var pos2 = snapshotRepo.findUserPosition(today, u2.getId());
        var pos3 = snapshotRepo.findUserPosition(today, u3.getId());

        assertThat(pos1.getRank()).isEqualTo(1);
        assertThat(pos2.getRank()).isEqualTo(2);
        assertThat(pos3).isNull();
    }

    private void setStreak(User user, LocalDateTime lastSolved, int current, int longest) throws Exception {
        var s = user.getStreak();
        setField(s, "lastSolvedDate", lastSolved);
        setField(s, "currentStreak", current);
        setField(s, "longestStreak", longest);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
