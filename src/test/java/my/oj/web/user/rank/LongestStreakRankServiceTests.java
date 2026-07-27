package my.oj.web.user.rank;

import jakarta.transaction.Transactional;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.streak.longest.LongestStreakRankService;
import my.oj.web.user.rank.streak.longest.LongestStreakSnapshotRepository;
import my.oj.web.user.rank.streak.longest.LongestStreakSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class LongestStreakRankServiceTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LongestStreakSnapshotService longestStreakSnapshotService;

    @Autowired
    private LongestStreakSnapshotRepository longestStreakSnapshotRepository;

    @Autowired
    private LongestStreakRankService longestStreakRankService;

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2025, 9, 19, 9, 0);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbcTemplate.execute("TRUNCATE TABLE longest_streak_rank_snapshot");
        jdbcTemplate.execute("TRUNCATE TABLE user");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    void rebuildBucketsAndFindNthUser() {
        Long aliceId = insertUser("alice", 10, BASE_TIME.minusHours(1));
        Long bobId = insertUser("bob", 10, BASE_TIME.plusHours(3));
        Long caraId = insertUser("cara", 8, BASE_TIME.plusHours(6));

        longestStreakSnapshotService.rebuildSnapshot();

        RankItemDto first = longestStreakRankService.getUserAtRank(1);
        assertThat(first.userId()).isEqualTo(aliceId);
        assertThat(first.solvedCount()).isEqualTo(10);

        RankItemDto second = longestStreakRankService.getUserAtRank(2);
        assertThat(second.userId()).isEqualTo(bobId);

        RankItemDto third = longestStreakRankService.getUserAtRank(3);
        assertThat(third.userId()).isEqualTo(caraId);
    }

    @Test
    void aroundMePageRanksInDescendingOrder() {
        Long aliceId = insertUser("alice", 12, BASE_TIME.minusHours(2));
        Long bobId = insertUser("bob", 12, BASE_TIME.plusHours(2));
        Long caraId = insertUser("cara", 10, BASE_TIME.plusHours(5));
        Long danId = insertUser("dan", 9, BASE_TIME.plusHours(8));

        longestStreakSnapshotService.rebuildSnapshot();

        RankPageDto page = longestStreakRankService.getPageAroundUser(bobId, 3);

        assertThat(page.myRank()).isEqualTo(2);
        assertThat(page.pageStartRank()).isEqualTo(1);
        assertThat(page.previousCursor()).isNull();
        assertThat(page.totalItems()).isEqualTo(longestStreakSnapshotRepository.count());
        assertThat(page.items()).extracting(RankItemDto::userId).containsExactly(aliceId, bobId, caraId);
    }

    @Test
    void getUserAtRankRebuildsBucketsOnDemand() {
        Long topId = insertUser("top", 20, BASE_TIME.minusHours(1));
        Long midId = insertUser("mid", 15, BASE_TIME.plusHours(2));

        assertThat(longestStreakSnapshotRepository.count()).isZero();

        RankItemDto rankOne = longestStreakRankService.getUserAtRank(1);

        assertThat(rankOne.userId()).isEqualTo(topId);
        assertThat(longestStreakSnapshotRepository.count()).isEqualTo(2);
    }

    @Test
    void firstPageUsesRankingOrder() {
        Long topId = insertUser("top", 15, BASE_TIME.minusHours(3));
        Long secondId = insertUser("second", 15, BASE_TIME.plusHours(1));
        Long thirdId = insertUser("third", 12, BASE_TIME.plusHours(4));
        insertUser("fourth", 8, BASE_TIME.plusHours(6));

        longestStreakSnapshotService.rebuildSnapshot();

        RankPageDto page = longestStreakRankService.getPage(null, 3);

        assertThat(page.pageStartRank()).isEqualTo(1);
        assertThat(page.previousCursor()).isNull();
        assertThat(page.nextCursor()).isEqualTo(4L);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().stream().map(RankItemDto::userId).toList()).containsExactly(topId, secondId, thirdId);

        RankItemDto third = longestStreakRankService.getUserAtRank(3);
        assertThat(third.userId()).isEqualTo(thirdId);
    }

    @Test
    void aroundMeFallbackForZeroLongestShowsBottomPage() {
        Long topId = insertUser("top", 9, BASE_TIME.minusHours(5));
        Long midId = insertUser("mid", 5, BASE_TIME.minusHours(3));
        Long tailId = insertUser("tail", 2, BASE_TIME.minusHours(1));
        Long zeroId = insertUser("zero", 0, BASE_TIME.plusHours(2));

        longestStreakSnapshotService.rebuildSnapshot();

        RankPageDto page = longestStreakRankService.getPageAroundUser(zeroId, 3);

        assertThat(page.myRank()).isEqualTo(4);
        assertThat(page.pageStartRank()).isEqualTo(2);
        assertThat(page.totalItems()).isEqualTo(longestStreakSnapshotRepository.count());
        assertThat(page.previousCursor()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).extracting(RankItemDto::userId)
                .containsExactly(midId, tailId, zeroId);
        assertThat(page.items()).extracting(RankItemDto::rank)
                .containsExactly(2L, 3L, 4L);
    }

    private Long insertUser(String name, int longestStreak, LocalDateTime lastSolved) {
        jdbcTemplate.update("INSERT INTO user(name, pass, solved_count, streak_last_solved_date, streak_current_streak, streak_longest_streak) VALUES (?,?,?,?,?,?)",
                name,
                "pass",
                0,
                Timestamp.valueOf(lastSolved),
                longestStreak,
                longestStreak);
        return jdbcTemplate.queryForObject("SELECT id FROM user WHERE name = ?", Long.class, name);
    }
}
