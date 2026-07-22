package my.oj.web.submission.event.guard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_guard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGuard {

    @Id
    @Column(name = "user_id")
    private Long userId;

    public UserGuard(Long userId) {
        this.userId = userId;
    }
}

