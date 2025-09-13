package my.oj.web.submission;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import my.oj.web.problem.Problem;
import my.oj.web.user.User;

import java.time.LocalDateTime;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "problem_id", "code_hash"})
})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private LocalDateTime submittedTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String code;

    @Setter(AccessLevel.NONE)
    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    private SubmissionResult result;

    public static Submission create(User user, Problem problem, String code, LocalDateTime submittedTime) {
        Submission submission = new Submission();
        submission.setUser(user);
        submission.setProblem(problem);
        submission.setCode(code);
        submission.setResult(SubmissionResult.PENDING);
        submission.setSubmittedTime(submittedTime);
        return submission;
    }

    public void setCode(String code) {
        this.code = code;
        this.codeHash = CodeHashGenerator.generate(code);
    }
}
