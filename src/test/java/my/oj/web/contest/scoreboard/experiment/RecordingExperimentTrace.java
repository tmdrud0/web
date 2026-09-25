package my.oj.web.contest.scoreboard.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps every record in memory, so a test can read what an observed component reported. */
public class RecordingExperimentTrace implements ContestScoreboardExperimentTrace {

    public record LiveBatch(long appliedAtEpochMillis, List<LiveEvent> events) {
    }

    private final List<LiveBatch> liveBatches = Collections.synchronizedList(new ArrayList<>());
    private final List<RecoveryRecord> recoveryRecords = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void liveBatchApplied(long appliedAtEpochMillis, List<LiveEvent> events) {
        liveBatches.add(new LiveBatch(appliedAtEpochMillis, List.copyOf(events)));
    }

    @Override
    public void recovery(RecoveryRecord record) {
        recoveryRecords.add(record);
    }

    public List<LiveBatch> liveBatches() {
        return List.copyOf(liveBatches);
    }

    public List<RecoveryRecord> recoveryRecords() {
        return List.copyOf(recoveryRecords);
    }

    public List<RecoveryEvent> recoveryEvents() {
        return recoveryRecords().stream().map(RecoveryRecord::event).toList();
    }
}
