package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.stream.JdbcContestScoreboardAppliedAtWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The mode decides which columns a batch records, so the decision is asserted here rather than
 * inferred from three call sites that could each drift.
 */
class ContestScoreboardAppliedMarkerTests {

    private final JdbcContestScoreboardAppliedAtWriter writer =
            mock(JdbcContestScoreboardAppliedAtWriter.class);
    private final ContestScoreboardSequenceSource sequenceSource =
            mock(ContestScoreboardSequenceSource.class);

    @Test
    void withoutSequenceTrackingItWritesTheTimestampOnly() {
        new ContestScoreboardAppliedMarker(writer, sequenceSource, () -> false)
                .markApplied(List.of(7L, 9L));

        verify(writer).markApplied(List.of(7L, 9L));
        verifyNoInteractions(sequenceSource);
        verify(writer, never()).markApplied(any(), any());
    }

    @Test
    void withSequenceTrackingItPersistsTheSequencesTheScoreboardHolds() {
        when(sequenceSource.appliedSequences(List.of(7L, 9L))).thenReturn(Map.of(7L, 31L, 9L, 32L));

        new ContestScoreboardAppliedMarker(writer, sequenceSource, () -> true)
                .markApplied(List.of(7L, 9L));

        verify(writer).markApplied(List.of(7L, 9L), Map.of(7L, 31L, 9L, 32L));
    }

    /**
     * A result the scoreboard never sequenced must still get its timestamp, so an empty lookup is
     * passed through rather than skipping the write.
     */
    @Test
    void withSequenceTrackingAnUnknownSequenceStillRecordsTheTimestamp() {
        when(sequenceSource.appliedSequences(List.of(7L))).thenReturn(Map.of());

        new ContestScoreboardAppliedMarker(writer, sequenceSource, () -> true).markApplied(List.of(7L));

        verify(writer).markApplied(List.of(7L), Map.of());
    }

    @Test
    void anEmptyBatchTouchesNothing() {
        ContestScoreboardAppliedMarker marker =
                new ContestScoreboardAppliedMarker(writer, sequenceSource, () -> true);

        marker.markApplied(List.of());
        marker.markApplied(null);

        verifyNoInteractions(writer);
        verifyNoInteractions(sequenceSource);
    }
}
