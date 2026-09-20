package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one thing every recovery trigger shares.
 *
 * <p>What is being pinned is that a second pass does not run while a first one does, that the gate is
 * given back whatever the pass does, and that a skipped pass is never reported as a completed one -
 * the last being the only silent mistake available here, because every caller treats an empty result
 * as "not done, try again on my own cadence".</p>
 *
 * <p>It is a JVM-local gate and the tests are single-JVM, so nothing below says anything about two
 * instances. That limitation is the reason {@code contest.scoreboard.recovery.owner.enabled} exists;
 * see {@code ARCHITECTURE.md} §3.5.</p>
 */
class ContestScoreboardRecoveryPassGateTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ContestScoreboardRecoveryPassGate gate = new ContestScoreboardRecoveryPassGate(registry);

    @Test
    void runsThePassAndReturnsItsValue() {
        Optional<Integer> result = gate.tryRun(PassKind.MYSQL_REPLAY, () -> 7);

        assertThat(result).contains(7);
        assertThat(gate.held()).isFalse();
    }

    /**
     * A pass that returns nothing is not a completed pass. Reporting empty for it is what keeps a
     * caller from reading its own {@code null} as a pass that ran.
     */
    @Test
    void reportsAPassThatReturnedNothingAsEmpty() {
        assertThat(gate.tryRun(PassKind.SEQUENCE_CHECK, () -> null)).isEmpty();
    }

    @Test
    void skipsTheSecondPassWhileTheFirstIsRunning() {
        AtomicInteger runs = new AtomicInteger();

        gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            // A pass that is running is what a trigger arriving mid-pass sees.
            Optional<Boolean> second = gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
                runs.incrementAndGet();
                return Boolean.TRUE;
            });
            assertThat(second).isEmpty();
            runs.incrementAndGet();
            return Boolean.TRUE;
        });

        assertThat(runs).hasValue(1);
        // Counted per kind, so an operator can tell which trigger was turned away.
        assertThat(skipped(PassKind.SEQUENCE_CHECK)).isEqualTo(1.0);
        assertThat(skipped(PassKind.MYSQL_REPLAY)).isZero();
    }

    @Test
    void releasesTheGateWhenThePassFails() {
        assertThatThrownBy(() -> gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            throw new IllegalStateException("MySQL unavailable");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(gate.held()).isFalse();
        assertThat(gate.tryRun(PassKind.MYSQL_REPLAY, () -> 1)).contains(1);
    }

    /**
     * The gate is one flag rather than one per kind on purpose: the passes share the state they read,
     * so a sequence check and a replay must not run over each other either.
     */
    @Test
    void anyPassHoldsTheGateAgainstEveryOtherKind() {
        gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
            assertThat(gate.tryRun(PassKind.MYSQL_REPLAY, () -> 1)).isEmpty();
            return Boolean.TRUE;
        });

        assertThat(skipped(PassKind.MYSQL_REPLAY)).isEqualTo(1.0);
    }

    /** A concurrent trigger is turned away rather than queued: every caller has its own next tick. */
    @Test
    void aConcurrentTriggerIsTurnedAway() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondRuns = new AtomicInteger();

        Thread first = new Thread(() -> gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Boolean.TRUE;
        }));
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            secondRuns.incrementAndGet();
            return Boolean.TRUE;
        });
        release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(secondRuns).hasValue(0);
        assertThat(gate.held()).isFalse();
    }

    private double skipped(PassKind kind) {
        return registry.get("contest.scoreboard.recovery.pass.skipped")
                .tag("pass", kind.label())
                .counter()
                .count();
    }
}
