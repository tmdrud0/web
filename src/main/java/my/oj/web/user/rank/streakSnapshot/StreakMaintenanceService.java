package my.oj.web.user.rank.streaksnapshot;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StreakMaintenanceService {

    private final UserRepository userRepository;
    private final StreakSnapshotRepository snapshotRepository;

    @Transactional
    public void ensureFreshnessForUser(long userId) {
        User u = userRepository.findById(userId).orElse(null);
        if (u == null) return;

        LocalDate last = u.getStreak().getLastSolvedDate().toLocalDate();
        LocalDate today = LocalDate.now();
        // If last solved day is strictly earlier than yesterday, streak should be zero
        if (last.isBefore(today.minusDays(1))) {
            u.getStreak().resetCurrentIfStale(today);
            snapshotRepository.deleteUserFromSnapshot(today, userId);
        }
    }
}
