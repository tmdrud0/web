package my.oj.web.user.rank.streak;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_streak_rank_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStreakRankSnapshot {

    @Id
    @Column(name = "snapshot_rank")
    private Long rank;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "current_streak", nullable = false)
    private Integer currentStreak;

    @Column(name = "last_solved_time")
    private LocalDateTime lastSolvedTime;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private UserStreakRankSnapshot(Long rank,
                                   Long userId,
                                   Integer currentStreak,
                                   LocalDateTime lastSolvedTime,
                                   LocalDateTime updatedAt) {
        this.rank = rank;
        this.userId = userId;
        this.currentStreak = currentStreak;
        this.lastSolvedTime = lastSolvedTime;
        this.updatedAt = updatedAt;
    }

    public static UserStreakRankSnapshot of(long rank,
                                            long userId,
                                            int currentStreak,
                                            LocalDateTime lastSolvedTime,
                                            LocalDateTime updatedAt) {
        return new UserStreakRankSnapshot(rank, userId, currentStreak, lastSolvedTime, updatedAt);
    }
}
