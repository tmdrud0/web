package my.oj.web.user.rank.streaksnapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class StreakSnapshotScheduler {
    private final StreakSnapshotService snapshotService;

    // Runs daily at 00:05
    @Scheduled(cron = "0 5 0 * * *")
    public void runDaily() {
        snapshotService.rebuild(LocalDate.now(), 10);
    }
}

