package my.oj.web.user.rank.streaksnapshot;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "streak_snapshot_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreakSnapshotUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    @Column(name = "last_solved_date", nullable = false)
    private LocalDateTime lastSolvedDate;

    @Column(name = "`rank`", nullable = false)
    private int rank;

    public StreakSnapshotUser(LocalDate snapshotDate, Long userId, int currentStreak,
                              LocalDateTime lastSolvedDate, int rank) {
        this.snapshotDate = snapshotDate;
        this.userId = userId;
        this.currentStreak = currentStreak;
        this.lastSolvedDate = lastSolvedDate;
        this.rank = rank;
    }
}

