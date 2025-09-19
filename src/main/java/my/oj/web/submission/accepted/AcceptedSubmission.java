package my.oj.web.submission.accepted;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.oj.web.problem.Problem;
import my.oj.web.user.User;

import java.time.LocalDateTime;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "problem_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AcceptedSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    private LocalDateTime submittedTime;


    private AcceptedSubmission(Long id, User user, Problem problem, LocalDateTime submittedTime) {
        this.id = id;
        this.user = user;
        this.problem = problem;
        this.submittedTime = submittedTime;
    }

    public static AcceptedSubmission create(User user, Problem problem, LocalDateTime submittedTime) {
        return new AcceptedSubmission(null, user, problem, submittedTime);
    }

}
