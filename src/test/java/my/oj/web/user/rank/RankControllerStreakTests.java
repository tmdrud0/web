package my.oj.web.user.rank;

import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.activity.DailyActiveUserRepository;
import my.oj.web.user.dto.UserDto;
import my.oj.web.user.rank.streaksnapshot.StreakSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RankControllerStreakTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    StreakSnapshotService snapshotService;

    @Autowired
    DailyActiveUserRepository dailyActiveUserRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    User loggedIn;

    @BeforeEach
    void setUp() throws Exception {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            userRepository.deleteAll();
            String loginName = "login-" + UUID.randomUUID();
            String peerName = "peer-" + UUID.randomUUID();
            loggedIn = userRepository.save(User.withState(null, loginName, "p", 0L, new my.oj.web.user.Streak()));
            User u2 = userRepository.save(User.withState(null, peerName, "p", 0L, new my.oj.web.user.Streak()));

            LocalDateTime now = LocalDateTime.now();
            try {
                setStreak(loggedIn, now.minusHours(4), 1, 2);
                setStreak(u2, now.minusHours(1), 1, 3);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            LocalDate today = now.toLocalDate();
            dailyActiveUserRepository.upsert(today.minusDays(1), loggedIn.getId(), now.minusHours(4));
            dailyActiveUserRepository.upsert(today.minusDays(1), u2.getId(), now.minusHours(1));
            userRepository.flush();
            return today;
        });

        snapshotService.rebuild(LocalDate.now(), 10);
    }

    @Test
    void get_streak_rank_page_renders() throws Exception {
        var dto = UserDto.from(loggedIn);
        mockMvc.perform(get("/rank").param("sortBy", "streak").sessionAttr("user", dto))
                .andExpect(status().isOk())
                .andExpect(view().name("rank"))
                .andExpect(model().attributeExists("streakPageResult"))
                .andExpect(model().attribute("sortBy", "streak"));
    }

    @Test
    void get_streak_around_me_renders() throws Exception {
        var dto = UserDto.from(loggedIn);
        mockMvc.perform(get("/rank").param("sortBy", "streak").param("aroundMe", "true").sessionAttr("user", dto))
                .andExpect(status().isOk())
                .andExpect(view().name("rank"))
                .andExpect(model().attributeExists("streakPageResult"))
                .andExpect(model().attribute("aroundMe", true));
    }

    private static void setStreak(User user, LocalDateTime last, int current, int longest) throws Exception {
        var s = user.getStreak();
        setField(s, "lastSolvedDate", last);
        setField(s, "currentStreak", current);
        setField(s, "longestStreak", longest);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
