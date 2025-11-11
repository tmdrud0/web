package my.oj.web.user.rank.solved.solvedbucket;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "solved_count_bucket")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SolvedCountBucket {

    @Id
    private Long n;

    @Column(name = "user_count", nullable = false)
    private Long userCount;

    @Column(name = "cum_higher_count", nullable = false)
    private Long cumHigherCount;

    public SolvedCountBucket(Long n, Long userCount, Long cumHigherCount) {
        this.n = n;
        this.userCount = userCount;
        this.cumHigherCount = cumHigherCount;
    }
}
