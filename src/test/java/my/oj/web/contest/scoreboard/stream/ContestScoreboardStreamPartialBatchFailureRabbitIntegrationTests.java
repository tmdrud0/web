package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.submission.messaging.ContestJudgeRabbitTopology;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.testsupport.ContestScoreboardTestData;
import my.oj.web.testsupport.ContestScoreboardTestData.Attempt;
import my.oj.web.testsupport.ContestScoreboardTestData.SeededContest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A batch that fails in the middle of real Redis, against the real broker.
 *
 * <p>{@code ContestScoreboardStreamBatchFailureRabbitIntegrationTests} fails at decode: its poison
 * carries a schema version the listener rejects, so {@code decode} throws before a single Redis write
 * is attempted and the batch is refused whole. That is a real case, but it is not the one the
 * checkpoint's ordering rests on. What has to hold is a batch that is <em>partly</em> in Redis when it
 * fails - earlier deliveries written, later ones not - because Redis has no transaction spanning them
 * and the only thing keeping the standings and the checkpoint consistent is where the write stopped.</p>
 *
 * <h2>How the failure is produced</h2>
 *
 * <p>The second contest's ranking key is created as a string before anything is published. The script's
 * first act is {@code assertKeyType(KEYS[3], 'zset')}, so the real Lua running on the real Redis refuses
 * the delivery with an error reply before it reads or writes anything - not a mock, not an injected
 * exception, and specifically not a write the script then has to undo. Nothing is faked about the
 * failure: it is the script's own guard doing its job on a key that is genuinely the wrong type.</p>
 *
 * <h2>The five deliveries</h2>
 *
 * <p>Two are published while the consumer is running and three while it is stopped, which is what makes
 * the three into one batch: they are all waiting in the stream before the consumer is handed its start,
 * so the failure lands in the middle of a batch with an applied delivery in front of it and an unapplied
 * one behind. The last is published after the failure, above the checkpoint, and is the delivery that
 * asks what a consumer which has moved past unapplied results is allowed to claim next.</p>
 *
 * <table>
 *   <caption>Delivery order, which is offset order</caption>
 *   <tr><th>Offset</th><th>Delivery</th><th>What it must leave behind</th></tr>
 *   <tr><td>0</td><td>healthy</td><td>applied; the checkpoint moves here</td></tr>
 *   <tr><td>1</td><td>healthy</td><td>applied inside the batch that then fails</td></tr>
 *   <tr><td>2</td><td>the poison</td><td>refused by the script; the checkpoint stops below it</td></tr>
 *   <tr><td>3</td><td>healthy</td><td>never attempted - the batch stops at the poison</td></tr>
 *   <tr><td>4</td><td>healthy, published after the failure</td><td>refused; not applied over the poison</td></tr>
 * </table>
 *
 * <h2>What the checkpoint may and may not do</h2>
 *
 * <p>The checkpoint must stop at the last applied offset rather than at the last delivered one - a
 * checkpoint that had stepped over the poison would leave that result reachable from nowhere, which is
 * the loss the whole design exists to prevent. Offset 1 is what makes this a partial batch and not a
 * refused one; offset 3 is what shows the batch stopped; offset 4 is what shows the consumer's position
 * having moved past unapplied results does not by itself license the next forward step.</p>
 *
 * <p>The resubscribe is what brings the batch back, and it has to ask for the stored checkpoint itself
 * rather than its successor: the successor is exactly the assumption that stream offsets are
 * consecutive, and asking for it would register a retention gap and refuse to move instead. That is why
 * {@code retention-gap-fallback=none} is set here - it turns the resubscribe's argument into something
 * the outcome can prove. The poison is removed and the same deliveries have to converge, which they can
 * only do if the resubscribe resumed at the checkpoint inclusively.</p>
 *
 * <p>The supervisor pass is on an hour in this class. Not because it is unimportant, but because it is
 * periodic: a restart landing between the failure and the delivery above it would replace the
 * consumer's own position with a fresh subscribe and the gap question would never be asked.
 * {@code ContestScoreboardStreamBatchFailureRabbitIntegrationTests} asserts the supervisor's
 * resubscribe at the stored checkpoint against the same broker; here it is performed by hand at a point
 * the test chooses, and what is asserted is what the consumer's position may claim.</p>
 *
 * <h2>Why each method gets its own context</h2>
 *
 * <p>Two things a method leaves behind are JVM-scoped and neither is reset by seeding. The first is
 * {@link ContestScoreboardStreamPosition}'s outstanding range: a method that fails a batch on purpose
 * records the range the checkpoint may not pass, and that range outlives the Redis flush the next
 * method performs - so the next method's first delivery would be judged against a failure it did not
 * cause. The second is the meters, which count up across the whole context, so a method asserting
 * {@code failures == 1} would be reading its predecessor's failure too. A fresh context per method is
 * what makes each method's assertions about its own deliveries rather than about the class's history.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // These tests read scoreboard_applied_at, which stream-offset no longer writes by default.
        "contest.scoreboard.stream-offset.applied-at-tracking=true",
        "contest.scoreboard.store=redis",
        "contest.scoreboard.stream.consumer.enabled=true",
        // This context consumes the stream, so it is the recovery owner by definition.
        "contest.scoreboard.recovery.owner.enabled=true",
        // The stream-offset mode's own answer to a jump the stream cannot explain. Refusing is what
        // makes the resubscribe's argument observable: had it asked for the successor, the range below
        // the delivery would be judged a retention gap and the scoreboard would never converge.
        "contest.scoreboard.recovery.stream-offset.retention-gap-fallback=none",
        "contest.scoreboard.stream.consumer.batch-size=20",
        "contest.scoreboard.stream.consumer.prefetch=20",
        "contest.scoreboard.stream.consumer.receive-timeout=20ms",
        "contest.scoreboard.stream.consumer.retry-backoff=10ms",
        // Effectively off, so nothing resubscribes behind the test's back. See the class javadoc.
        "contest.scoreboard.stream.consumer.offset-check-interval=1h",
        "contest.scoreboard.stream.consumer.tail-probe-interval=1h",
        "contest.submission.judge.result-stream.publisher.enabled=true",
        "contest.submission.judge.rabbit.publisher.enabled=false",
        "contest.submission.judge.rabbit.listener.enabled=false",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest"
})
@EnabledIfSystemProperty(named = "rabbitIntegration", matches = "true")
class ContestScoreboardStreamPartialBatchFailureRabbitIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 8, 9, 12, 0);

    private static final long FIRST_SUBMISSION_ID = 930_000_000_000_000_201L;
    private static final long SECOND_SUBMISSION_ID = 930_000_000_000_000_202L;
    private static final long POISONED_SUBMISSION_ID = 930_000_000_000_000_203L;
    private static final long BEHIND_SUBMISSION_ID = 930_000_000_000_000_204L;
    private static final long AFTER_SUBMISSION_ID = 930_000_000_000_000_205L;

    /**
     * The offsets a freshly declared stream gives the deliveries, in publication order: 0, 1, 2, 3, 4.
     * Only the three the assertions name are written down; the poison is the one at 2 and the delivery
     * behind it the one at 3.
     */
    private static final long FIRST_OFFSET = 0L;
    private static final long SECOND_OFFSET = 1L;
    private static final long AFTER_OFFSET = 4L;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.getInteger("redisPort", 16379));
        // Same reason as the neighbouring stream tests: the queue is durable and the tail monitor
        // probes once at startup, before this class can empty anything.
        emptyResultStream();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ContestScoreboardApplier applier;
    @Autowired
    private ContestScoreboardStreamLifecycle lifecycle;
    @Autowired
    private ContestScoreboardStreamPosition position;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    @Qualifier("contestJudgeResultStreamRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    /** The contest whose ranking key is deliberately the wrong Redis type. */
    private SeededContest poisonedContest;
    private SeededContest healthyContest;

    /**
     * One delivery: which submission it carries and when it was submitted and judged. Kept as fields
     * rather than rebuilt per publish so the times the broker is told and the ones MySQL holds cannot
     * drift apart.
     */
    private static final class Delivery {
        private final long submissionId;
        private final int submittedMinute;
        private final int judgedMinute;

        private Delivery(long submissionId, int submittedMinute, int judgedMinute) {
            this.submissionId = submissionId;
            this.submittedMinute = submittedMinute;
            this.judgedMinute = judgedMinute;
        }
    }

    private static final Delivery FIRST_DELIVERY = new Delivery(FIRST_SUBMISSION_ID, 2, 3);
    private static final Delivery SECOND_DELIVERY = new Delivery(SECOND_SUBMISSION_ID, 4, 5);
    private static final Delivery POISONED_DELIVERY = new Delivery(POISONED_SUBMISSION_ID, 6, 7);
    private static final Delivery BEHIND_DELIVERY = new Delivery(BEHIND_SUBMISSION_ID, 8, 9);
    private static final Delivery AFTER_DELIVERY = new Delivery(AFTER_SUBMISSION_ID, 10, 11);

    @BeforeEach
    void seed() {
        lifecycle.stop();
        // Recreated rather than merely emptied, so the offsets named above are the ones the broker
        // issues. The assertion confirms the redeclaration took, which turns a broker that refuses it
        // into a failure here rather than a timeout later.
        amqpAdmin.deleteQueue(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
        amqpAdmin.initialize();
        assertThat(amqpAdmin.getQueueProperties(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE))
                .as("the result stream queue was redeclared at offset zero")
                .isNotNull();
        ContestScoreboardTestData.flushRedis(redisTemplate);
        healthyContest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "stream-partial-batch-healthy", CONTEST_START, 1, 1);
        poisonedContest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "stream-partial-batch-poisoned", CONTEST_START, 1, 1);
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate,
                healthyContest.contestId(),
                CONTEST_START,
                List.of(
                        attempt(FIRST_DELIVERY, healthyContest),
                        attempt(SECOND_DELIVERY, healthyContest),
                        attempt(BEHIND_DELIVERY, healthyContest),
                        attempt(AFTER_DELIVERY, healthyContest)
                ),
                true
        );
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate,
                poisonedContest.contestId(),
                CONTEST_START,
                List.of(attempt(POISONED_DELIVERY, poisonedContest)),
                true
        );
        // The whole failure injection: one key of the right name holding the wrong type. The script's
        // assertKeyType refuses the delivery before it reads the checkpoint, so this contest's events
        // cannot be applied and cannot move the offset while it is there.
        redisTemplate.opsForValue().set(rankingKey(poisonedContest), "not-a-zset");
        lifecycle.start();
    }

    @AfterEach
    void cleanUp() {
        lifecycle.stop();
        ContestScoreboardTestData.deleteContest(jdbcTemplate, healthyContest.contestId());
        ContestScoreboardTestData.deleteContest(jdbcTemplate, poisonedContest.contestId());
        ContestScoreboardTestData.flushRedis(redisTemplate);
        // The stream is durable and shared with the classes that run after this one, so the deliveries
        // this test published must not be waiting for them.
        emptyResultStream();
    }

    @Test
    void aBatchThatFailsHalfwayStopsTheCheckpointAndIsNeverSteppedOver() {
        publish(FIRST_DELIVERY, healthyContest);
        await("the first delivery to be applied",
                () -> checkpoint() == FIRST_OFFSET && appliedCount() == 1.0);

        // Stopping first is what makes the next three one batch: they are all in the stream before the
        // consumer is handed its start, so it reads them together and the failure lands mid-batch.
        lifecycle.stop();
        publish(SECOND_DELIVERY, healthyContest);
        publish(POISONED_DELIVERY, poisonedContest);
        publish(BEHIND_DELIVERY, healthyContest);
        lifecycle.start();

        await("the batch to fail at the poisoned delivery",
                () -> counter("contest.scoreboard.stream.failures") >= 1.0);

        assertThat(checkpoint())
                .as("the checkpoint stopped at the last offset applied rather than the last delivered")
                .isEqualTo(SECOND_OFFSET);
        assertThat(position.highestAppliedOffset())
                .as("the process has applied nothing past the delivery in front of the failure")
                .isEqualTo(FIRST_OFFSET);
        assertThat(processed(healthyContest))
                .as("the delivery in front of the poison was applied inside the batch that failed, "
                        + "which is what makes this a partial batch - and the one behind it was not")
                .containsExactlyInAnyOrder(ids(FIRST_SUBMISSION_ID, SECOND_SUBMISSION_ID));
        assertThat(processed(poisonedContest))
                .as("the refused delivery was applied nowhere")
                .isEmpty();
        assertThat(appliedCount())
                .as("the batch never completed, so the delivery behind the poison was never attempted")
                .isEqualTo(1.0);
        // The completion marker is written per completed batch and is deliberately behind the
        // standings; repairPending catches it up on the next start. What it must never do is lead.
        assertThat(appliedRows(healthyContest))
                .as("only the completed batch is marked, and the marker never runs ahead of the standings")
                .isEqualTo(1L);
        assertThat(appliedRows(poisonedContest))
                .as("the refused delivery is recorded in MySQL as applied nowhere")
                .isZero();

        // Above the checkpoint, while the consumer's position has already moved past the poison. A step
        // taken on the strength of that position would carry the checkpoint over a result nobody
        // applied, and this delivery is the only thing that can ask for one.
        publish(AFTER_DELIVERY, healthyContest);
        // The second failure is the refusal: the poison failed the batch, and this delivery fails for
        // starting above the range that batch left unapplied. Deliberately not the gap counter - every
        // offset here is still in the broker, so counting one as a retention gap would report history
        // the broker kept as history it lost.
        await("the delivery above the failed offset to be refused rather than stepped over",
                () -> counter("contest.scoreboard.stream.failures") >= 2.0);

        assertThat(counter("contest.scoreboard.stream.offset.gaps"))
                .as("nothing was outside the broker's retention, so nothing is a retention gap")
                .isZero();

        assertThat(checkpoint())
                .as("a delivery above a failed batch does not license moving over it")
                .isEqualTo(SECOND_OFFSET);
        assertThat(processed(healthyContest))
                .as("the delivery above the failed batch was applied nowhere")
                .containsExactlyInAnyOrder(ids(FIRST_SUBMISSION_ID, SECOND_SUBMISSION_ID));
        assertThat(appliedCount())
                .as("no batch completed between the failure and the refusal")
                .isEqualTo(1.0);

        // The recovery: the poison is gone and the consumer resumes at the stored checkpoint itself -
        // the offset whose delivery is already applied, which the script absorbs. Resuming at its
        // successor would be the contiguity assumption, and with no fallback configured the scoreboard
        // would refuse to move at all, so converging is what proves the argument.
        redisTemplate.delete(rankingKey(poisonedContest));
        lifecycle.stop();
        lifecycle.start();

        await("the failed batch and everything behind it to be applied",
                () -> checkpoint() == AFTER_OFFSET
                        && processed(healthyContest).size() == 4
                        && processed(poisonedContest).size() == 1);

        assertThat(processed(healthyContest))
                .as("all four healthy deliveries are in the standings, the one behind the poison included")
                .containsExactlyInAnyOrder(
                        ids(FIRST_SUBMISSION_ID, SECOND_SUBMISSION_ID, BEHIND_SUBMISSION_ID, AFTER_SUBMISSION_ID));
        assertThat(processed(poisonedContest))
                .as("the delivery that failed is applied from the stream rather than lost")
                .containsExactly(ids(POISONED_SUBMISSION_ID));
        assertThat(appliedRows(healthyContest))
                .as("every applied delivery is marked complete")
                .isEqualTo(4L);
        assertThat(appliedRows(poisonedContest)).isEqualTo(1L);
        assertThat(checkpoint())
                .as("the checkpoint ends at the last offset actually applied")
                .isEqualTo(AFTER_OFFSET);
    }

    /**
     * The same refusal where the scoreboard has no checkpoint to hand a mode.
     *
     * <p>An unset checkpoint reads like licence to adopt whatever arrives first, and for the first
     * delivery of a stream nothing has been applied from it is exactly that. It is not licence to adopt
     * the delivery that arrives <em>after</em> a failed batch: the consumer has already moved past the
     * offset that failed and the broker will not hand that one back, so adopting the higher offset makes
     * the standings claim a range they never had with the failure as the only record that anything was
     * skipped - and there is no checkpoint left for a later pass to notice it from.</p>
     *
     * <p>This class keeps the supervisor's pass out of the way on purpose, so that the refusal is the
     * only outcome the arrangement admits. With the pass running it could resubscribe first and re-read
     * the failed delivery and this one as a single batch, which nothing would apply for a reason that
     * has nothing to do with the refusal.</p>
     */
    @Test
    void aDeliveryAboveAFailedRangeIsRefusedEvenWithNoCheckpointToHandAMode() {
        publishUndecodable(FIRST_DELIVERY, healthyContest);
        await("the undecodable delivery to fail its batch",
                () -> counter("contest.scoreboard.stream.failures") >= 1.0);
        // Published only now, so the consumer's position is already past the offset that failed and this
        // delivery cannot be handed over in the same batch as it.
        publish(AFTER_DELIVERY, healthyContest);

        await("the delivery above the failed offset to be refused",
                () -> counter("contest.scoreboard.stream.unapplied.refusals") >= 1.0);

        assertThat(checkpoint())
                .as("the checkpoint is not adopted from a delivery above a range nothing applied")
                .isEqualTo(-1L);
        assertThat(processed(healthyContest)).isEmpty();
        assertThat(appliedRows(healthyContest)).isZero();
        assertThat(appliedCount()).isZero();
    }

    private long checkpoint() {
        return applier.currentStreamOffset();
    }

    private static Attempt attempt(Delivery delivery, SeededContest contest) {
        // ACCEPTED, which is what publish sends: the row MySQL holds and the delivery the broker carries
        // have to say the same thing or the test would be measuring its own disagreement.
        return new Attempt(delivery.submissionId, contest.problemIds().get(0), contest.userIds().get(0),
                delivery.submittedMinute, delivery.judgedMinute, SubmissionResult.ACCEPTED);
    }

    private void publish(Delivery delivery, SeededContest contest) {
        publish(message(delivery, contest, ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION));
    }

    /** A delivery the consumer cannot decode, so the batch it lands in is discarded whole. */
    private void publishUndecodable(Delivery delivery, SeededContest contest) {
        publish(message(delivery, contest, ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION + 1));
    }

    private static ContestJudgeResultStreamMessage message(Delivery delivery,
                                                          SeededContest contest,
                                                          int schemaVersion) {
        return new ContestJudgeResultStreamMessage(
                schemaVersion,
                delivery.submissionId,
                contest.contestId(),
                contest.problemIds().get(0),
                contest.userIds().get(0),
                CONTEST_START,
                CONTEST_START.plusMinutes(delivery.submittedMinute),
                CONTEST_START.plusMinutes(delivery.judgedMinute),
                SubmissionResult.ACCEPTED
        );
    }

    /**
     * Publishes one delivery and waits for the broker to confirm it before returning.
     *
     * <p>The wait is what makes the offset order below the publication order, and this test reads the
     * offsets as an arrangement rather than as a set. Without it the guarantee does not hold: publishing
     * five in a row without waiting was measured on this broker handing the fifth publication offset 1
     * and the second, third and fourth offsets 2, 3 and 4. See
     * {@code StreamPublishConfirmOrderRabbitIntegrationTests}, which asserts the guarantee this relies
     * on against the same broker.</p>
     */
    private void publish(ContestJudgeResultStreamMessage message) {
        CorrelationData correlationData = new CorrelationData();
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY,
                message,
                correlationData
        );
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(10L, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker nacked scoreboard delivery "
                        + message.submissionId() + ": " + confirm.getReason());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + message.submissionId(),
                    interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Failed to publish " + message.submissionId(), failure);
        }
    }

    private String rankingKey(SeededContest contest) {
        return "contest:scoreboard:" + contest.contestId() + ":ranking";
    }

    /**
     * The deliveries the standings hold. The script writes this set in the same call as the checkpoint,
     * so what it holds is what the checkpoint's offset is a claim about.
     */
    private Set<String> processed(SeededContest contest) {
        Set<String> ids = redisTemplate.opsForSet().members(
                ContestScoreboardTestData.processedKey(contest.contestId())
        );
        return ids == null ? Set.of() : ids;
    }

    private static String[] ids(long... submissionIds) {
        String[] values = new String[submissionIds.length];
        for (int index = 0; index < submissionIds.length; index++) {
            values[index] = String.valueOf(submissionIds[index]);
        }
        return values;
    }

    private long appliedRows(SeededContest contest) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = ? AND scoreboard_applied_at IS NOT NULL",
                Long.class,
                contest.contestId()
        );
        return count == null ? 0L : count;
    }

    private double appliedCount() {
        return counter("contest.scoreboard.applied");
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    /**
     * Deletes the result stream and declares it empty again, so the offsets this class reasons about are
     * the ones the broker issues from zero - and so a delivery does not survive into the next class. The
     * arguments match the topology bean's.
     */
    private static void emptyResultStream() {
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        try (com.rabbitmq.client.Connection connection = factory.newConnection();
             com.rabbitmq.client.Channel channel = connection.createChannel()) {
            channel.queueDelete(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
            channel.queueDeclare(
                    ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE,
                    true,
                    false,
                    false,
                    Map.of(
                            "x-queue-type", "stream",
                            "x-max-age", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_AGE,
                            "x-max-length-bytes", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_LENGTH_BYTES
                    )
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Could not empty the result stream queue", failure);
        }
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, interrupted);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }
}
