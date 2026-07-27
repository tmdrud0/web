package my.oj.web.user.rank;

import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.streak.StreakRankService;
import my.oj.web.user.rank.streak.UserStreakRankSnapshot;
import my.oj.web.user.rank.streak.UserStreakRankSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StreakRankServiceTests {

    @Autowired
    private StreakRankService streakRankService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserStreakRankSnapshotRepository snapshotRepository;

    @BeforeEach
    void clean() {
        snapshotRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getPageAroundUser_returnsConsistentSlice() throws Exception {
        User top = persistUser("top", 3, LocalDateTime.now().minusHours(5));
        User mid = persistUser("mid", 2, LocalDateTime.now().minusHours(2));
        User target = persistUser("target", 1, LocalDateTime.now().minusHours(1));

        snapshotRepository.save(UserStreakRankSnapshot.of(1, top.getId(), 3, top.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(2, mid.getId(), 2, mid.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(3, target.getId(), 1, target.getStreak().getLastSolvedDate(), LocalDateTime.now()));

        RankPageDto page = streakRankService.getPageAroundUser(target.getId(), 3);

        assertThat(page.myRank()).isEqualTo(3);
        assertThat(page.pageStartRank()).isEqualTo(1);
        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(page.previousCursor()).isNull();
        assertThat(page.nextCursor()).isNull();
        List<Long> ids = page.items().stream().map(RankItemDto::userId).toList();
        assertThat(ids).containsExactly(top.getId(), mid.getId(), target.getId());
    }

    @Test
    void getPageAroundUser_fallsBackForZeroStreakShowsBottomPage() throws Exception {
        User leader = persistUser("leader", 5, LocalDateTime.now().minusHours(5));
        User middle = persistUser("middle", 3, LocalDateTime.now().minusHours(3));
        User tail = persistUser("tail", 1, LocalDateTime.now().minusHours(1));
        User idle = persistUser("idle", 0, LocalDateTime.now().minusDays(3));

        snapshotRepository.save(UserStreakRankSnapshot.of(1, leader.getId(), 5, leader.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(2, middle.getId(), 3, middle.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(3, tail.getId(), 1, tail.getStreak().getLastSolvedDate(), LocalDateTime.now()));

        RankPageDto page = streakRankService.getPageAroundUser(idle.getId(), 3);

        assertThat(page.myRank()).isEqualTo(4);
        assertThat(page.pageStartRank()).isEqualTo(2);
        assertThat(page.totalItems()).isEqualTo(4);
        assertThat(page.previousCursor()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).extracting(RankItemDto::userId)
                .containsExactly(middle.getId(), tail.getId(), idle.getId());
        assertThat(page.items()).extracting(RankItemDto::rank)
                .containsExactly(2L, 3L, 4L);
    }

    @Test
    void getPage_returnsOrderedSlice() throws Exception {
        User u1 = persistUser("u1", 5, LocalDateTime.now().minusHours(4));
        User u2 = persistUser("u2", 3, LocalDateTime.now().minusHours(3));
        User u3 = persistUser("u3", 2, LocalDateTime.now().minusHours(2));
        User u4 = persistUser("u4", 1, LocalDateTime.now().minusHours(1));

        snapshotRepository.save(UserStreakRankSnapshot.of(1, u1.getId(), 5, u1.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(2, u2.getId(), 3, u2.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(3, u3.getId(), 2, u3.getStreak().getLastSolvedDate(), LocalDateTime.now()));
        snapshotRepository.save(UserStreakRankSnapshot.of(4, u4.getId(), 1, u4.getStreak().getLastSolvedDate(), LocalDateTime.now()));

        RankPageDto page = streakRankService.getPage(null, 3);
        assertThat(page.pageStartRank()).isEqualTo(1);
        assertThat(page.totalItems()).isEqualTo(4);
        assertThat(page.previousCursor()).isNull();
        assertThat(page.nextCursor()).isEqualTo(4L);
        assertThat(page.items()).extracting(RankItemDto::userId)
                .containsExactly(u1.getId(), u2.getId(), u3.getId());

        RankPageDto secondPage = streakRankService.getPage(4L, 3);
        assertThat(secondPage.pageStartRank()).isEqualTo(4);
        assertThat(secondPage.previousCursor()).isEqualTo(1L);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(secondPage.items()).extracting(RankItemDto::userId)
                .containsExactly(u4.getId());
    }

    private User persistUser(String name, int currentStreak, LocalDateTime lastSolved) throws Exception {
        User user = User.withState(null, name, "p", 0L, new my.oj.web.user.Streak());
        setField(user.getStreak(), "currentStreak", currentStreak);
        setField(user.getStreak(), "longestStreak", Math.max(currentStreak, 1));
        setField(user.getStreak(), "lastSolvedDate", lastSolved);
        return userRepository.saveAndFlush(user);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
