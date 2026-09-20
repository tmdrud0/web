package my.oj.web.contest.submission.core;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "contest_submission_result")
public class ContestSubmissionResult {

    @Id
    @Column(name = "submission_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "submission_id")
    private ContestSubmission submission;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisional_result", nullable = false)
    private SubmissionResult provisionalResult;

    @Column(name = "provisional_judged_at")
    private LocalDateTime provisionalJudgedAt;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_result")
    private SubmissionResult finalResult;

    @Column(name = "final_judged_at")
    private LocalDateTime finalJudgedAt;

    /**
     * The sequence the scoreboard held for this result at the moment it was applied, or null when
     * the scoreboard does not issue sequences.
     *
     * <p>Deliberately not named {@code redis_seq}: that name already belongs to
     * {@code contest_submission_outbox.redis_seq}, a table whose rows are purged when a contest is
     * finalized, so a checkpoint kept there would disappear exactly when a recovery needs it.</p>
     */
    @Column(name = "scoreboard_applied_seq")
    private Long scoreboardAppliedSeq;

    public static ContestSubmissionResult pending(ContestSubmission submission) {
        ContestSubmissionResult result = new ContestSubmissionResult();
        result.submission = submission;
        result.provisionalResult = SubmissionResult.PENDING;
        result.contestId = submission.getContest().getId();
        return result;
    }

    public void recordProvisional(SubmissionResult result, LocalDateTime judgedAt) {
        this.provisionalResult = result;
        this.provisionalJudgedAt = judgedAt;
    }

    public void recordFinal(SubmissionResult result, LocalDateTime judgedAt) {
        this.finalResult = result;
        this.finalJudgedAt = judgedAt;
    }
}
