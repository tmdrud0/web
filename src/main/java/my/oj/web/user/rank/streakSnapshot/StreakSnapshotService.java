package my.oj.web.user.rank.streaksnapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StreakSnapshotService {
    private final StreakSnapshotRepository repo;

    @Transactional
    public void rebuild(LocalDate date, int pageSize) {
        repo.deleteSnapshotForDate(date);
        repo.buildSnapshotUsers(date);
    }
}



