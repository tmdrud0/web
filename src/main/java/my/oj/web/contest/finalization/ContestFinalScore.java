package my.oj.web.contest.finalization;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "contest_final_score", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"contest_id", "user_id", "status"})
})
public class ContestFinalScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "solved_count", nullable = false)
    private int solvedCount;

    @Column(name = "penalty", nullable = false)
    private long penalty;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ContestFinalScoreStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private ContestFinalScore(Long contestId,
                               Long userId,
                               int solvedCount,
                               long penalty,
                               int rank,
                               ContestFinalScoreStatus status,
                               LocalDateTime createdAt) {
        this.contestId = contestId;
        this.userId = userId;
        this.solvedCount = solvedCount;
        this.penalty = penalty;
        this.rank = rank;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static ContestFinalScore of(Long contestId,
                                       Long userId,
                                       int solvedCount,
                                       long penalty,
                                       int rank,
                                       ContestFinalScoreStatus status) {
        return new ContestFinalScore(contestId, userId, solvedCount, penalty, rank, status, LocalDateTime.now());
    }
}
