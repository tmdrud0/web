package my.oj.web.user;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StreakLogicTests {

    @Test
    void resetCurrentIfStale_setsZeroWhenOlderThanYesterday() throws Exception {
        Streak s = new Streak();
        // simulate old lastSolvedDate
        var f = Streak.class.getDeclaredField("lastSolvedDate");
        f.setAccessible(true);
        f.set(s, LocalDateTime.now().minusDays(3));
        // give a non-zero currentStreak
        var fc = Streak.class.getDeclaredField("currentStreak");
        fc.setAccessible(true);
        fc.setInt(s, 7);

        s.resetCurrentIfStale(LocalDate.now());

        assertThat(s.getCurrentStreak()).isZero();
    }
}

