package my.oj.web.contest.submission.core;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.oj.web.contest.Contest;
import my.oj.web.problem.Problem;
import my.oj.web.user.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "contest_submission", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"contest_id", "problem_id", "user_id", "code_hash"})
})
public class ContestSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime submittedTime;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String code;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    public static ContestSubmission create(User user, Problem problem, String code, String codeHash, LocalDateTime submittedTime) {
        Contest contest = problem.getContest();
        if (contest == null) {
            throw new IllegalArgumentException("Contest submission requires an associated contest");
        }

        ContestSubmission contestSubmission = new ContestSubmission();
        contestSubmission.user = user;
        contestSubmission.problem = problem;
        contestSubmission.contest = contest;
        contestSubmission.code = code;
        contestSubmission.codeHash = codeHash;
        contestSubmission.submittedTime = submittedTime;
        return contestSubmission;
    }
}