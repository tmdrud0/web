package my.oj.web.user.rank.streak.longest.longestbucket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "longest_streak_bucket")
public class LongestStreakBucket {

    @Id
    @Column(name = "n")
    private Integer n;

    @Column(name = "user_count", nullable = false)
    private Long userCount;

    @Column(name = "cum_higher_count", nullable = false)
    private Long cumHigherCount;

    protected LongestStreakBucket() {
    }

    public LongestStreakBucket(Integer n, Long userCount, Long cumHigherCount) {
        this.n = n;
        this.userCount = userCount;
        this.cumHigherCount = cumHigherCount;
    }

    public Integer getN() {
        return n;
    }

    public Long getUserCount() {
        return userCount;
    }

    public Long getCumHigherCount() {
        return cumHigherCount;
    }
}
