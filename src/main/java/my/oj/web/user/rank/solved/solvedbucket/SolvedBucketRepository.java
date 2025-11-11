package my.oj.web.user.rank.solved.solvedbucket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolvedBucketRepository extends JpaRepository<SolvedCountBucket, Long> {

    interface BucketAtRankProjection {
        Long getN();
        Long getCumHigherCount();
    }

    @Modifying
    @Query(value = "UPDATE solved_count_bucket " +
            "SET user_count = user_count - 1, cum_higher_count = cum_higher_count + :delta " +
            "WHERE n = :n", nativeQuery = true)
    int decrementUserAndIncreaseHigher(@Param("n") long solvedCount,
                                       @Param("delta") long delta);

    @Modifying
    @Query(value = "INSERT INTO solved_count_bucket (n, user_count, cum_higher_count) VALUES (:n, 1, 0) " +
            "ON DUPLICATE KEY UPDATE user_count = user_count + 1", nativeQuery = true)
    void incrementUserCount(@Param("n") long solvedCount);

    @Query(value = """
      SELECT b.n AS n, b.cum_higher_count AS cumHigherCount
      FROM solved_count_bucket b
      WHERE b.cum_higher_count < :rank
        AND :rank <= b.cum_higher_count + b.user_count
      ORDER BY b.n DESC
      LIMIT 1
      """, nativeQuery = true)
    BucketAtRankProjection findBucketForRank(@Param("rank") long rank);

    @Query(value = """
      SELECT b.cum_higher_count
      FROM solved_count_bucket b
      WHERE b.n = :n
      """, nativeQuery = true)
    Long findCumHigher(@Param("n") long n);

    @Query("SELECT COALESCE(SUM(b.userCount), 0) FROM SolvedCountBucket b")
    long totalUsers();
}
