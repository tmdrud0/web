package my.oj.web.user.rank.solved;

import my.oj.web.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SolvedRankRepository extends JpaRepository<User, Long> {

    interface UserPageRowProjection {
        Long getId();
        String getName();
        Long getSolvedCount();
        LocalDateTime getLastSolvedDate();
    }

    interface SolvedDistProjection {
        Long getN();
        Long getCnt();
    }

    @Query(value = """
      SELECT COUNT(*)
      FROM `user` u
      WHERE u.solved_count = :sc
        AND (u.streak_last_solved_date < :t
             OR (u.streak_last_solved_date = :t AND u.id < :uid))
      """, nativeQuery = true)
    long countTieBefore(@Param("sc") long sc,
                        @Param("t") LocalDateTime t,
                        @Param("uid") long uid);

    @Query(value = """
      SELECT u.id AS id, u.name AS name, u.solved_count AS solvedCount, u.streak_last_solved_date AS lastSolvedDate
      FROM `user` u FORCE INDEX (idx_user_ranking)
      WHERE u.solved_count <= :sc
      ORDER BY u.solved_count DESC, u.streak_last_solved_date ASC, u.id ASC
      LIMIT :limit OFFSET :offset
      """, nativeQuery = true)
    List<UserPageRowProjection> fetchPageFromBucket(@Param("sc") long sc,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    @Query(value = """
      SELECT u.solved_count AS n, COUNT(*) AS cnt
      FROM `user` u
      WHERE u.solved_count > 0
      GROUP BY u.solved_count
      ORDER BY n DESC
      """, nativeQuery = true)
    List<SolvedDistProjection> solvedCountDistribution();

}
