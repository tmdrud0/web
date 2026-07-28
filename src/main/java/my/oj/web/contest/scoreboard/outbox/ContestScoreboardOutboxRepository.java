package my.oj.web.contest.scoreboard.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContestScoreboardOutboxRepository extends JpaRepository<ContestScoreboardOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContestScoreboardOutbox> findById(Long id);

    @Query("""
            select o.redisSequence
            from ContestScoreboardOutbox o
            where o.redisSequence is not null
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            group by o.redisSequence
            having count(o.id) > 1
            order by o.redisSequence asc
            """)
    List<Long> findDuplicateRedisSequences(Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ContestScoreboardOutbox o
            set o.status = my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PENDING,
                o.redisSequence = null,
                o.processedAt = null,
                o.lastErrorMessage = null,
                o.claimToken = null,
                o.claimedAt = null,
                o.nextAttemptAt = null
            where o.redisSequence in :sequences
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            """)
    int requeueByRedisSequenceIn(@Param("sequences") Collection<Long> sequences);

    @Query("""
            select o.id
            from ContestScoreboardOutbox o
            where o.redisSequence > :redisSequence
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            order by o.redisSequence asc, o.id asc
            """)
    List<Long> findIdsAboveRedisSequence(@Param("redisSequence") Long redisSequence, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ContestScoreboardOutbox o
            set o.status = my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PENDING,
                o.redisSequence = null,
                o.processedAt = null,
                o.lastErrorMessage = null,
                o.claimToken = null,
                o.claimedAt = null,
                o.nextAttemptAt = null
            where o.id in :ids
              and o.redisSequence is not null
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            """)
    int requeueByIdIn(@Param("ids") Collection<Long> ids);

    void deleteByContestId(Long contestId);
}
