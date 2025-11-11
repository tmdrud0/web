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
        outbox.redisSequence = redisSequence;
        return outbox;
    }

    public boolean isProcessable() {
        return status == ContestScoreboardOutboxStatus.PENDING || status == ContestScoreboardOutboxStatus.FAILED;
    }

    public void markSuccess(LocalDateTime processedAt) {
        this.status = ContestScoreboardOutboxStatus.COMPLETED;
        this.processedAt = processedAt;
        this.lastErrorMessage = null;
    }

    public void markFailed(String message) {
        this.status = ContestScoreboardOutboxStatus.FAILED;
        this.lastErrorMessage = message;
    }
}
