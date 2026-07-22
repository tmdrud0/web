package my.oj.web.user;

import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Embeddable
@Getter
public class Streak implements Serializable {
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

        if (lastSolvedDate != null && today.toLocalDate().isEqual(lastSolvedDate.toLocalDate())) {
            return;
        }

        if (lastSolvedDate != null && lastSolvedDate.toLocalDate().isEqual(today.minusDays(1).toLocalDate())) {
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

    public void applyBatchResult(int newCurrent) {
        if (newCurrent < 0) {
            newCurrent = 0;
        }
        this.currentStreak = newCurrent;
        if (newCurrent > this.longestStreak) {
            this.longestStreak = newCurrent;
        }
    }

    public void resetByBatch() {
        this.currentStreak = 0;
    }

    public void recordSolveAt(LocalDateTime solvedAt) {
        if (solvedAt == null) {
            return;
        }
        if (this.lastSolvedDate == null || solvedAt.isAfter(this.lastSolvedDate)) {
            this.lastSolvedDate = solvedAt;
        }
    }
}
