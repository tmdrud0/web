package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryRecord;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One history rebuild at a time, inside this JVM.
 *
 * <h2>What it fixes</h2>
 *
 * <p>A recovery pass reads a snapshot of MySQL and writes it to the scoreboard. Two overlapping
 * passes are not merely wasteful: {@code redis-seq} judges every stored row against the allocator it
 * read at one moment, so a second pass reading the allocator while the first is still replaying sees
 * the first pass's in-flight results as lost and replays them again. The triggers are independent -
 * a startup runner, a fixed-delay scheduler, the supervisor, and the live path itself - so nothing
 * but a shared gate keeps them apart.</p>
 *
 * <h2>What it is not</h2>
 *
 * <p>This is a JVM-local gate, like {@code ContestScoreboardApplyLock}. It does not coordinate two
 * instances. Nothing in this application does: the recovery roles are expected to run on one
 * instance, and {@code contest.scoreboard.recovery.owner.enabled} is what makes that expectation
 * explicit rather than accidental. See {@code ARCHITECTURE.md} §3.5.</p>
 *
 * <p>The gate covers the long reconstructions only. A single live event applied through the Lua
 * script never takes it, so a running replay cannot stop the live path from applying results.</p>
 */
@Component
@Slf4j
public class ContestScoreboardRecoveryPassGate {

    private final AtomicBoolean held = new AtomicBoolean();
    private final Map<PassKind, Counter> skipped = new EnumMap<>(PassKind.class);
    private final ContestScoreboardExperimentTrace trace;

    public ContestScoreboardRecoveryPassGate(MeterRegistry meterRegistry) {
        this(meterRegistry, ContestScoreboardExperimentTrace.NOOP);
    }

    @Autowired
    public ContestScoreboardRecoveryPassGate(MeterRegistry meterRegistry,
                                             ObjectProvider<ContestScoreboardExperimentTrace> trace) {
        this(meterRegistry, trace.getIfAvailable(() -> ContestScoreboardExperimentTrace.NOOP));
    }

    public ContestScoreboardRecoveryPassGate(MeterRegistry meterRegistry, ContestScoreboardExperimentTrace trace) {
        this.trace = trace;
        for (PassKind kind : PassKind.values()) {
            skipped.put(kind, Counter.builder("contest.scoreboard.recovery.pass.skipped")
                    .tag("pass", kind.label())
                    .description("Recovery passes skipped because another pass already held the gate")
                    .register(meterRegistry));
        }
    }

    /**
     * Runs one pass, or nothing at all when a pass already holds the gate.
     *
     * <p>Skipping is not an error and is not retried here. Every caller has its own cadence - a
     * schedule, a supervisor interval, a batch that will be re-read - so the next tick is the retry,
     * and a caller that reported success for a pass it did not run would be the one real mistake.</p>
     *
     * <p>An empty result therefore means one thing only: {@link #held} was already taken, so this call
     * ran nothing. A pass that completes without a value is refused rather than reported as empty,
     * because empty is what the callers read as "another pass is running" - and the callers of this
     * gate act on that: the recovery strategies turn it into a retry-later outcome, which is a
     * statement about another pass rather than about this one. A {@code null}-returning pass reported
     * that way would say another pass was running when none was, and the range it did not cover would
     * be retried on that false premise for as long as the bug survived.</p>
     *
     * @return the pass's value, or empty when another pass held the gate. Never a value that is absent
     *         for any other reason
     * @throws IllegalStateException when the pass completed without returning a value, which cannot be
     *         told apart from a pass that never ran
     */
    public <T> Optional<T> tryRun(PassKind kind, Supplier<T> pass) {
        if (!held.compareAndSet(false, true)) {
            skipped.get(kind).increment();
            log.warn("A recovery pass already holds the gate; this {} pass is skipped", kind.label());
            if (trace.enabled()) {
                long now = System.currentTimeMillis();
                trace.recovery(record(RecoveryEvent.PASS_SKIPPED, kind, now, now, "skipped"));
            }
            return Optional.empty();
        }
        long startedAt = trace.enabled() ? System.currentTimeMillis() : 0L;
        if (trace.enabled()) {
            trace.recovery(record(RecoveryEvent.PASS_START, kind, startedAt, -1L, "started"));
        }
        String outcome = "failed";
        try {
            T value = pass.get();
            if (value == null) {
                throw new IllegalStateException("A " + kind.label() + " pass completed without returning a"
                        + " value; an empty result means another pass held the gate, so this pass could"
                        + " not be told apart from one that never ran");
            }
            outcome = abbreviate(String.valueOf(value));
            return Optional.of(value);
        } finally {
            held.set(false);
            if (trace.enabled()) {
                trace.recovery(record(RecoveryEvent.PASS_END, kind, startedAt, System.currentTimeMillis(), outcome));
            }
        }
    }

    private static RecoveryRecord record(RecoveryEvent event, PassKind kind, long start, long end, String outcome) {
        return new RecoveryRecord(event, Thread.currentThread().getName(), start, -1L, end, -1, kind.label(), outcome);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** Whether a pass is running right now. */
    public boolean held() {
        return held.get();
    }
}
