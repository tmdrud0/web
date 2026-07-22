package my.oj.web.user.rank.streak.longest;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collections;
import java.util.List;

public interface LongestStreakSnapshotRepository extends JpaRepository<LongestStreakSnapshot, Long> {

    @Query(value = """
      SELECT s
      FROM LongestStreakSnapshot s
      WHERE s.rank BETWEEN :start AND :end
      ORDER BY s.rank
      """)
    List<LongestStreakSnapshot> findAllByIdRange(@Param("start") long start,
                                                 @Param("end") long end);

    @Query("SELECT s FROM LongestStreakSnapshot s WHERE s.userId = :userId")
    LongestStreakSnapshot findByUserId(@Param("userId") long userId);

    @Query("SELECT s FROM LongestStreakSnapshot s WHERE s.rank = :rank")
    LongestStreakSnapshot findByRank(@Param("rank") long rank);

    default List<LongestStreakSnapshot> findPage(long startRank, int size) {
        if (size <= 0) {
            return Collections.emptyList();
        }
        long endRank = startRank + size - 1;
        return findAllByIdRange(startRank, endRank);
    }

    default List<LongestStreakSnapshot> fetchTail(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "rank"))).getContent();
    }

}
