package my.oj.web.submission.guard;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
@Getter
public class UserProblemGuardId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    protected UserProblemGuardId() {}

    public UserProblemGuardId(Long userId, Long problemId) {
        this.userId = userId;
        this.problemId = problemId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserProblemGuardId)) return false;
        UserProblemGuardId that = (UserProblemGuardId) o;
        return Objects.equals(userId, that.userId)
                && Objects.equals(problemId, that.problemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, problemId);
    }

    @Override
    public String toString() {
        return "UserProblemGuardId{" +
                "userId=" + userId +
                ", problemId=" + problemId +
                '}';
    }
}
