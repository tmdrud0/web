package my.oj.web.user.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(my.oj.web.submission.SubmissionConfig.class)
class DailyActiveUserRepositoryTests {

    @Autowired
    DailyActiveUserRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    @Test
    void upsert_updatesLastActiveTime_andKeepsSingleRow() {
        LocalDate day = LocalDate.now();
        Long userId = 1L;

        repo.upsert(day, userId, LocalDateTime.now().minusHours(2));
        LocalDateTime latest = LocalDateTime.now();
        repo.upsert(day, userId, latest);

        var entity = repo.findById(new DailyActiveUser.Pk(day, userId));
        assertThat(entity).isPresent();
        assertThat(entity.get().getLastActiveTime()).isCloseTo(latest, withinSeconds(5));
    }

    private static org.assertj.core.data.TemporalUnitWithinOffset withinSeconds(long seconds) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(seconds, java.time.temporal.ChronoUnit.SECONDS);
    }
}
