package my.oj.web.contest.scoreboard.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContestScoreboardOutboxRepository extends JpaRepository<ContestScoreboardOutbox, Long> {

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
                o.nextAttemptAt = null,
                o.dueAt = current_timestamp
            where o.redisSequence in :sequences
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            """)
    int requeueByRedisSequenceIn(@Param("sequences") Collection<Long> sequences);

    /**
     * The highest sequences on record, which is where a lost tail would sit. The caller
     * compares them against the Redis allocator rather than filtering here, so the allocator
     * can be read after this snapshot is taken - see
     * {@code ContestScoreboardOutboxRecoveryService#requeueLostTail}.
     */
    @Query("""
            select new my.oj.web.contest.scoreboard.outbox.SequencedOutboxRow(o.id, o.redisSequence)
            from ContestScoreboardOutbox o
            where o.redisSequence is not null
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            order by o.redisSequence desc, o.id desc
            """)
    List<SequencedOutboxRow> findHighestRedisSequences(Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ContestScoreboardOutbox o
            set o.status = my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PENDING,
                o.redisSequence = null,
                o.processedAt = null,
                o.lastErrorMessage = null,
                o.claimToken = null,
                o.claimedAt = null,
                o.nextAttemptAt = null,
                o.dueAt = current_timestamp
            where o.id in :ids
              and o.redisSequence is not null
              and o.status <> my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxStatus.PROCESSING
            """)
    int requeueByIdIn(@Param("ids") Collection<Long> ids);

    void deleteByContestId(Long contestId);
}
