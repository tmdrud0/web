package my.oj.web.user.rank;

import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.solved.SolvedBucketMaintenanceService;
import my.oj.web.user.rank.solved.SolvedRankService;
import my.oj.web.user.rank.solved.solvedbucket.SolvedBucketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({TestQuerydslConfig.class, SolvedRankService.class, SolvedBucketMaintenanceService.class})
class SolvedRankServiceTests {

    @Autowired
    private SolvedRankService rankService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SolvedBucketRepository solvedBucketRepository;

    @BeforeEach
    void clearBuckets() {
        solvedBucketRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getSolvedCountPageForUser_buildsBucketsAndReturnsConsistentSlice() throws Exception {
        persistUser("top", 250, LocalDateTime.parse("2024-01-10T09:00:00"));
        persistUser("second", 200, LocalDateTime.parse("2024-01-10T10:00:00"));
        persistUser("third", 200, LocalDateTime.parse("2024-01-10T11:00:00"));
        persistUser("fourth", 150, LocalDateTime.parse("2024-01-10T08:00:00"));
        User target = persistUser("target", 150, LocalDateTime.parse("2024-01-10T09:30:00"));
        User tail = persistUser("tail", 150, LocalDateTime.parse("2024-01-10T12:00:00"));

        userRepository.flush();

        assertThat(solvedBucketRepository.count()).isZero();

        RankPageDto page = rankService.getSolvedCountPageForUser(target.getId(), 2);

        assertThat(page.myRank()).isEqualTo(5);
        assertThat(page.pageStartRank()).isEqualTo(5);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.totalItems()).isEqualTo(solvedBucketRepository.totalUsers());
        assertThat(page.nextCursor()).isNull();
        assertThat(page.previousCursor()).isEqualTo(3L);
        assertThat(page.items()).extracting(RankItemDto::userId)
                .containsExactly(target.getId(), tail.getId());
        assertThat(page.items()).extracting(RankItemDto::rank)
                .containsExactly(5L, 6L);

        assertThat(solvedBucketRepository.count()).isEqualTo(3);

        long bucketCount = solvedBucketRepository.count();
        RankPageDto tailPage = rankService.getSolvedCountPageForUser(tail.getId(), 2);
        assertThat(solvedBucketRepository.count()).isEqualTo(bucketCount);
        assertThat(tailPage.pageStartRank()).isEqualTo(5);
        assertThat(tailPage.totalItems()).isEqualTo(solvedBucketRepository.totalUsers());
        assertThat(tailPage.items()).extracting(RankItemDto::userId)
                .containsExactly(target.getId(), tail.getId());
    }

    @Test
    void getUserAtRankReturnsCorrectUser() throws Exception {
        persistUser("top", 250, LocalDateTime.parse("2024-01-10T08:00:00"));
        persistUser("early", 200, LocalDateTime.parse("2024-01-10T09:00:00"));
        User target = persistUser("target", 200, LocalDateTime.parse("2024-01-10T11:00:00"));
        persistUser("tail", 150, LocalDateTime.parse("2024-01-10T12:00:00"));

        userRepository.flush();

        assertThat(solvedBucketRepository.count()).isZero();

        RankItemDto third = rankService.getUserAtRank(3);

        assertThat(third.rank()).isEqualTo(3);
        assertThat(third.userId()).isEqualTo(target.getId());
        assertThat(third.solvedCount()).isEqualTo(200);
        assertThat(solvedBucketRepository.count()).isGreaterThan(0);
    }

    @Test
    void zeroSolvedUserReceivesFallbackPage() throws Exception {
        User top = persistUser("top", 300, LocalDateTime.parse("2024-01-10T08:00:00"));
        User mid = persistUser("mid", 200, LocalDateTime.parse("2024-01-10T09:00:00"));
        User low = persistUser("low", 50, LocalDateTime.parse("2024-01-10T11:30:00"));
        User newbie = persistUser("newbie", 0, null);

        userRepository.flush();

        RankPageDto page = rankService.getSolvedCountPageForUser(newbie.getId(), 3);

        assertThat(page.myRank()).isEqualTo(4);
        assertThat(page.pageStartRank()).isEqualTo(2);
        assertThat(page.previousCursor()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.totalItems()).isEqualTo(solvedBucketRepository.totalUsers());
        assertThat(page.items()).extracting(RankItemDto::userId)
                .containsExactly(mid.getId(), low.getId(), newbie.getId());
        assertThat(page.items()).extracting(RankItemDto::rank)
                .containsExactly(2L, 3L, 4L);
    }

    private User persistUser(String name, long solvedCount, LocalDateTime lastSolved) throws Exception {
        Streak streak = new Streak();
        setField(streak, "lastSolvedDate", lastSolved);
        setField(streak, "currentStreak", 1);
        setField(streak, "longestStreak", 5);
        User user = User.withState(null, name, "p", solvedCount, streak);
        return userRepository.save(user);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
