package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
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

    public ContestScoreboardRecoveryPassGate(MeterRegistry meterRegistry) {
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
     * @return the pass's value, or empty when another pass held the gate. A {@code null} value is
     *         reported as empty for the same reason: neither is a completed pass
     */
    public <T> Optional<T> tryRun(PassKind kind, Supplier<T> pass) {
        if (!held.compareAndSet(false, true)) {
            skipped.get(kind).increment();
            log.warn("A recovery pass already holds the gate; this {} pass is skipped", kind.label());
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(pass.get());
        } finally {
            held.set(false);
        }
    }

    /** Whether a pass is running right now. */
    public boolean held() {
        return held.get();
    }
}
