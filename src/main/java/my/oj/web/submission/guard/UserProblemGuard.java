package my.oj.web.submission.guard;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_problem_guard")
public class UserProblemGuard {
    @EmbeddedId
    private UserProblemGuardId id;

    protected UserProblemGuard() {}

    public UserProblemGuard(Long userId, Long problemId) {
        this.id = new UserProblemGuardId(userId, problemId);
    }
}