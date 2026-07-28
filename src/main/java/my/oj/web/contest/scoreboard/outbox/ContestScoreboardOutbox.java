package my.oj.web.contest.scoreboard.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "contest_submission_outbox")
public class ContestScoreboardOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_submission_id", nullable = false)
    private Long contestSubmissionId;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "contest_start")
    private LocalDateTime contestStart;

    @Column(name = "submitted_time", nullable = false)
    private LocalDateTime submittedTime;

    @Column(name = "judged_at")
    private LocalDateTime judgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private SubmissionResult result;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContestScoreboardOutboxStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    @Column(length = 500)
    private String lastErrorMessage;

    @Column(name = "redis_seq")
    private Long redisSequence;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /**
     * When this row may next be claimed; {@code null} once it is completed. The worker selects
     * solely on this column, so every write that makes a row claimable again has to set it.
     *
     * <p>It is compared against the database clock. Production writes go through SQL and stamp it
     * with {@code CURRENT_TIMESTAMP(6)}; the JVM timestamp used here is only accurate when the two
     * clocks share a time zone.
     */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    public static ContestScoreboardOutbox pending(Long contestSubmissionId,
                                                  Long contestId,
                                                  Long problemId,
                                                  Long userId,
                                                  LocalDateTime contestStart,
                                                  LocalDateTime submittedTime,
                                                  SubmissionResult result,
                                                  LocalDateTime judgedAt,
                                                  Long redisSequence) {
        ContestScoreboardOutbox outbox = new ContestScoreboardOutbox();
        outbox.contestSubmissionId = contestSubmissionId;
        outbox.contestId = contestId;
        outbox.problemId = problemId;
        outbox.userId = userId;
        outbox.contestStart = contestStart;
        outbox.submittedTime = submittedTime;
        outbox.result = result;
        outbox.judgedAt = judgedAt;
        outbox.status = ContestScoreboardOutboxStatus.PENDING;
        outbox.createdAt = LocalDateTime.now();
        outbox.dueAt = outbox.createdAt;
        outbox.redisSequence = redisSequence;
        return outbox;
    }

    public void markSuccess(LocalDateTime processedAt) {
        this.status = ContestScoreboardOutboxStatus.COMPLETED;
        this.processedAt = processedAt;
        this.lastErrorMessage = null;
        this.dueAt = null;
    }
}
