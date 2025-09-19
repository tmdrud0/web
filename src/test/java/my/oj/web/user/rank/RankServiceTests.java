package my.oj.web.user.rank;

import my.oj.web.submission.SubmissionConfig;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.SolvedBucketMaintenanceService;
import my.oj.web.user.rank.solvedbucket.SolvedBucketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({RankService.class, SolvedBucketMaintenanceService.class, SubmissionConfig.class})
class RankServiceTests {

    @Autowired
    private RankService rankService;

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
        assertThat(page.items()).extracting(RankItemDto::userId)
                .containsExactly(target.getId(), tail.getId());
        assertThat(page.items()).extracting(RankItemDto::rank)
                .containsExactly(5L, 6L);

        assertThat(solvedBucketRepository.count()).isEqualTo(3);

        long bucketCount = solvedBucketRepository.count();
        RankPageDto tailPage = rankService.getSolvedCountPageForUser(tail.getId(), 2);
        assertThat(solvedBucketRepository.count()).isEqualTo(bucketCount);
        assertThat(tailPage.pageStartRank()).isEqualTo(5);
        assertThat(tailPage.items()).extracting(RankItemDto::userId)
                .containsExactly(target.getId(), tail.getId());
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


