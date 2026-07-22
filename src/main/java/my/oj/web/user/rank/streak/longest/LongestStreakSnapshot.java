package my.oj.web.user.rank.streak.longest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "longest_streak_rank_snapshot")
public class LongestStreakSnapshot {

    @Id
    @Column(name = "snapshot_rank")
    private long rank;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "longest_streak", nullable = false)
    private int longestStreak;

    @Column(name = "last_solved_time")
    private LocalDateTime lastSolvedTime;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LongestStreakSnapshot() {
    }

    public LongestStreakSnapshot(long rank, long userId, int longestStreak, LocalDateTime lastSolvedTime, LocalDateTime updatedAt) {
        this.rank = rank;
        this.userId = userId;
        this.longestStreak = longestStreak;
        this.lastSolvedTime = lastSolvedTime;
        this.updatedAt = updatedAt;
    }

    public long getRank() {
        return rank;
    }

    public long getUserId() {
        return userId;
    }

    public int getLongestStreak() {
        return longestStreak;
    }

    public LocalDateTime getLastSolvedTime() {
        return lastSolvedTime;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
