package my.oj.web.user;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Embeddable
@Getter
public class Streak {
    private LocalDateTime lastSolvedDate;
    private int currentStreak;
    private int longestStreak;

    public Streak() {
        lastSolvedDate = LocalDateTime.now().minusDays(1);
        currentStreak = 0;
        longestStreak = 0;
    }

    public void updateUserStreak() {
        LocalDateTime today = LocalDateTime.now();

        if(today.toLocalDate().isEqual(lastSolvedDate.toLocalDate())) return;

        if(lastSolvedDate.toLocalDate().isEqual(today.minusDays(1).toLocalDate())) {
            currentStreak++;
        } else {
            currentStreak = 1;
        }

        lastSolvedDate = today;
        longestStreak = Math.max(currentStreak, longestStreak);
    }

    public void resetCurrentIfStale(LocalDate today) {
        if (lastSolvedDate == null) {
            currentStreak = 0;
            return;
        }
        LocalDate last = lastSolvedDate.toLocalDate();
        if (last.isBefore(today.minusDays(1))) {
            currentStreak = 0;
        }
    }
}
