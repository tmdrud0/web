package my.oj.web.contest.scoreboard.outbox;

import jakarta.persistence.EntityManager;
import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({
        ContestScoreboardOutboxRecoveryService.class,
        TestQuerydslConfig.class,
        ContestScoreboardRecoveryIntegrationTests.TestConfig.class
})
class ContestScoreboardRecoveryIntegrationTests {

    @Autowired
    private ContestScoreboardOutboxRepository outboxRepository;

    @Autowired
    private ContestScoreboardOutboxRecoveryService recoveryService;

    @Autowired
    private MutableSequenceApplier sequenceApplier;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        sequenceApplier.setCurrentSequence(0L);
    }

    @Test
    void duplicateSequencesAreRequeuedWithoutTouchingUnrelatedRows() {
        ContestScoreboardOutbox duplicateA = saveCompletedOutbox(1001L, 41L);
        ContestScoreboardOutbox duplicateB = saveCompletedOutbox(1002L, 41L);
        ContestScoreboardOutbox unaffected = saveCompletedOutbox(1003L, 42L);

        int requeued = recoveryService.requeueDuplicateSequences(10);
        entityManager.clear();

        assertThat(requeued).isEqualTo(2);
        assertRequeued(duplicateA.getId());
        assertRequeued(duplicateB.getId());

        ContestScoreboardOutbox remaining = outboxRepository.findById(unaffected.getId()).orElseThrow();
        assertThat(remaining.getStatus()).isEqualTo(ContestScoreboardOutboxStatus.COMPLETED);
        assertThat(remaining.getRedisSequence()).isEqualTo(42L);
        assertThat(remaining.getProcessedAt()).isNotNull();
    }

    @Test
    void rowsBeyondRedisAllocatorAreRequeuedInBatches() {
        ContestScoreboardOutbox retained = saveCompletedOutbox(2001L, 70L);
        ContestScoreboardOutbox lostA = saveCompletedOutbox(2002L, 71L);
        ContestScoreboardOutbox lostB = saveCompletedOutbox(2003L, 72L);
        sequenceApplier.setCurrentSequence(70L);

        int firstBatch = recoveryService.requeueLostTail(1);
        int secondBatch = recoveryService.requeueLostTail(10);
        entityManager.clear();

        assertThat(firstBatch).isOne();
        assertThat(secondBatch).isOne();
        ContestScoreboardOutbox remaining = outboxRepository.findById(retained.getId()).orElseThrow();
        assertThat(remaining.getRedisSequence()).isEqualTo(70L);
        assertThat(remaining.getStatus()).isEqualTo(ContestScoreboardOutboxStatus.COMPLETED);
        assertRequeued(lostA.getId());
        assertRequeued(lostB.getId());
    }

    private ContestScoreboardOutbox saveCompletedOutbox(Long submissionId, Long redisSequence) {
        ContestScoreboardOutbox outbox = ContestScoreboardOutbox.pending(
                submissionId,
                9001L,
                submissionId,
                submissionId,
                LocalDateTime.of(2026, 3, 10, 10, 0),
                LocalDateTime.of(2026, 3, 10, 10, 5),
                SubmissionResult.ACCEPTED,
                LocalDateTime.of(2026, 3, 10, 10, 6),
                redisSequence
        );
        outbox.markSuccess(LocalDateTime.of(2026, 3, 10, 10, 7));
        return outboxRepository.saveAndFlush(outbox);
    }

    private void assertRequeued(Long id) {
        ContestScoreboardOutbox outbox = outboxRepository.findById(id).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(ContestScoreboardOutboxStatus.PENDING);
        assertThat(outbox.getRedisSequence()).isNull();
        assertThat(outbox.getProcessedAt()).isNull();
        assertThat(outbox.getLastErrorMessage()).isNull();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        MutableSequenceApplier mutableSequenceApplier() {
            return new MutableSequenceApplier();
        }
    }

    static class MutableSequenceApplier implements ContestScoreboardOutboxApplier {

        private long currentSequence;

        @Override
        public Long apply(Long eventId, ContestScoreboardOutboxPayload payload) {
            throw new UnsupportedOperationException("Not used by recovery tests");
        }

        @Override
        public long currentSequence() {
            return currentSequence;
        }

        void setCurrentSequence(long currentSequence) {
            this.currentSequence = currentSequence;
        }
    }
}
