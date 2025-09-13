package my.oj.web.user.rank.solvedbucket;

import my.oj.web.user.rank.solvedbucket.SolvedCountBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolvedBucketRepository extends JpaRepository<SolvedCountBucket, Long> {

    interface BucketAtRankProjection {
        Long getN();
        Long getCumHigherCount();
    }

    // pageStartRank가 속한 solved_count (가장 큰 n 중 cum_higher_count < rank)
    @Query(value = """
      SELECT b.n AS n, b.cum_higher_count AS cumHigherCount
      FROM solved_count_bucket b
      WHERE b.cum_higher_count < :rank
        AND :rank <= b.cum_higher_count + b.user_count
      ORDER BY b.n DESC
      LIMIT 1
      """, nativeQuery = true)
    BucketAtRankProjection findBucketForRank(@Param("rank") long rank);

    // 특정 n의 cum_higher_count
    @Query(value = """
      SELECT b.cum_higher_count
      FROM solved_count_bucket b
      WHERE b.n = :n
      """, nativeQuery = true)
    Long findCumHigher(@Param("n") long n);
}
