package my.oj.web.contest.scoreboard.poll;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** The poll components wired over the in-memory scoreboard and ledger, as the configuration wires them. */
final class PollFixture {

    final InMemorySequencedScoreboard scoreboard;
    final InMemorySequenceLedger ledger;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ContestScoreboardApplyLock applyLock = new ContestScoreboardApplyLock();
    final ContestScoreboardMySqlPollMetrics metrics = new ContestScoreboardMySqlPollMetrics(registry);
    final ContestScoreboardSequencedApplication application;
    final ContestScoreboardRollbackDetector detector;
    final ContestScoreboardMySqlPoller poller;
    final ContestScoreboardRangeRecovery recovery;

    PollFixture(InMemorySequencedScoreboard scoreboard, InMemorySequenceLedger ledger, int batchSize,
                int chunkSize, int maxIterations) {
        this(scoreboard, ledger, batchSize, chunkSize, maxIterations, ContestScoreboardExperimentTrace.NOOP);
    }

    PollFixture(InMemorySequencedScoreboard scoreboard, InMemorySequenceLedger ledger, int batchSize,
                int chunkSize, int maxIterations, ContestScoreboardExperimentTrace trace) {
        this.scoreboard = scoreboard;
        this.ledger = ledger;
        this.application = new ContestScoreboardSequencedApplication(scoreboard, ledger, trace);
        this.detector = new ContestScoreboardRollbackDetector(scoreboard, ledger, applyLock, metrics, trace);
        this.poller = new ContestScoreboardMySqlPoller(ledger, application, detector, applyLock, metrics, batchSize);
        this.recovery = new ContestScoreboardRangeRecovery(ledger, application, detector, applyLock, metrics,
                chunkSize, maxIterations, trace);
    }

    PollFixture(int batchSize) {
        this(new InMemorySequencedScoreboard(), new InMemorySequenceLedger(), batchSize, batchSize, 100);
    }

    ContestScoreboardMySqlPollLifecycle lifecycle(ContestScoreboardPollOwnership ownership) {
        return new ContestScoreboardMySqlPollLifecycle(poller, detector, recovery, ownership, metrics,
                new ContestScoreboardMySqlPollProperties(ledgerBatch(), Duration.ofHours(1), Duration.ofHours(1),
                        Duration.ofHours(1), 500, 100, "test-lock"));
    }

    private static int ledgerBatch() {
        return 500;
    }

    /** Every recorded sequence is held by one result only. */
    boolean noSequenceHeldTwice() {
        Set<Long> seen = new HashSet<>();
        return ledger.rows.values().stream()
                .filter(row -> row.sequence != null)
                .allMatch(row -> seen.add(row.sequence));
    }

    double counter(String name) {
        return registry.find(name).counter() == null ? 0 : registry.find(name).counter().count();
    }
}
