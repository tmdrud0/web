package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.submission.core.ContestScoreboardReplayRow;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardFullReplayServiceTests {

    private static final long CONTEST_ID = 77L;

    @Mock
    private ContestScoreboardApplier scoreboardApplier;
    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestScoreboardAppliedMarker appliedMarker;

    private ContestScoreboardFullReplayService replayService;

    @BeforeEach
    void setUp() {
        replayService = new ContestScoreboardFullReplayService(
                scoreboardApplier,
                resultRepository,
                new ContestSubmissionBatchExecutor(new NoOpTransactionManager()),
                appliedMarker,
                new ContestScoreboardApplyLock(),
                properties(2, 2)
        );
    }

    /**
     * The mode's defining property: it re-sends stored results to whatever the RDB snapshot
     * restored. Calling reset would delete that state, which is what the rebuild service does and
     * what makes it unusable as a full replay.
     */
    @Test
    void replayContest_replaysStoredResultsWithoutResettingTheScoreboard() {
        stubPage(null, List.of(row(3L, SubmissionResult.WRONG_ANSWER), row(5L, SubmissionResult.ACCEPTED)));

        assertThat(replayService.replayContest(CONTEST_ID)).isEqualTo(2);

        verify(scoreboardApplier, never()).reset(anyLong());
        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requests = requestsCaptor();
        verify(scoreboardApplier).applyAll(requests.capture());
        assertThat(requests.getValue())
                .extracting(ContestScoreboardApplier.ApplyRequest::update)
                .extracting(
                        ContestScoreboardUpdate::contestSubmissionId,
                        ContestScoreboardUpdate::contestId,
                        ContestScoreboardUpdate::problemId,
                        ContestScoreboardUpdate::userId,
                        ContestScoreboardUpdate::result
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                3L, CONTEST_ID, 201L, 100L, SubmissionResult.WRONG_ANSWER),
                        org.assertj.core.groups.Tuple.tuple(
                                5L, CONTEST_ID, 201L, 100L, SubmissionResult.ACCEPTED)
                );
        // A rebuild request carries no offset, so a replay cannot move the stream checkpoint.
        assertThat(requests.getValue()).allMatch(request -> request.streamOffset() == null);
        verify(appliedMarker).markApplied(List.of(3L, 5L));
    }

    /** Unjudged rows must never reach the scoreboard, or the real judgement is skipped for good. */
    @Test
    void replayContest_asksTheDatabaseToExcludeUnjudgedRows() {
        when(resultRepository.findReplayRowsByContestId(
                eq(CONTEST_ID), isNull(), eq(SubmissionResult.PENDING), any()))
                .thenReturn(List.of());

        replayService.replayContest(CONTEST_ID);

        verify(resultRepository).findReplayRowsByContestId(
                eq(CONTEST_ID), isNull(), eq(SubmissionResult.PENDING), any());
    }

    @Test
    void replayContest_pagesPastTheLastSubmissionIdOfEachBatch() {
        stubPage(null, List.of(row(3L, SubmissionResult.ACCEPTED), row(5L, SubmissionResult.ACCEPTED)));
        stubPage(5L, List.of(row(8L, SubmissionResult.ACCEPTED)));
        stubPage(8L, List.of());

        assertThat(replayService.replayContest(CONTEST_ID)).isEqualTo(3);

        InOrder order = inOrder(resultRepository);
        order.verify(resultRepository).findReplayRowsByContestId(eq(CONTEST_ID), isNull(), any(), any());
        order.verify(resultRepository).findReplayRowsByContestId(eq(CONTEST_ID), eq(5L), any(), any());
        order.verify(resultRepository).findReplayRowsByContestId(eq(CONTEST_ID), eq(8L), any(), any());
    }

    /** The database batch bounds the read; the replay batch bounds how much one EVAL carries. */
    @Test
    void replayContest_sendsOneReplayBatchPerChunkOfTheDatabaseBatch() {
        replayService = new ContestScoreboardFullReplayService(
                scoreboardApplier,
                resultRepository,
                new ContestSubmissionBatchExecutor(new NoOpTransactionManager()),
                appliedMarker,
                new ContestScoreboardApplyLock(),
                properties(4, 2)
        );
        stubPage(null, List.of(
                row(1L, SubmissionResult.ACCEPTED),
                row(2L, SubmissionResult.ACCEPTED),
                row(3L, SubmissionResult.ACCEPTED),
                row(4L, SubmissionResult.ACCEPTED)
        ));

        assertThat(replayService.replayContest(CONTEST_ID)).isEqualTo(4);

        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requests = requestsCaptor();
        verify(scoreboardApplier, times(2)).applyAll(requests.capture());
        assertThat(requests.getAllValues())
                .extracting(chunk -> chunk.stream()
                        .map(request -> request.update().contestSubmissionId())
                        .toList())
                .containsExactly(List.of(1L, 2L), List.of(3L, 4L));
        verify(appliedMarker).markApplied(List.of(1L, 2L));
        verify(appliedMarker).markApplied(List.of(3L, 4L));
    }

    @Test
    void replayContest_failsLoudlyWhenAnEventCannotBeApplied() {
        stubPage(null, List.of(row(3L, SubmissionResult.ACCEPTED)));
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.failure(
                            request.correlationId(), "wrong Redis key type"))
                    .toList();
        });

        assertThatThrownBy(() -> replayService.replayContest(CONTEST_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong Redis key type");
        verify(appliedMarker, never()).markApplied(anyList());
    }

    @Test
    void replayContest_doesNotTouchTheScoreboardWhenThereIsNothingToReplay() {
        stubPage(null, List.of());

        assertThat(replayService.replayContest(CONTEST_ID)).isZero();

        verifyNoInteractions(scoreboardApplier, appliedMarker);
    }

    @Test
    void replayAllContests_replaysEveryContestThatHasStoredResults() {
        when(resultRepository.findDistinctContestIds()).thenReturn(List.of(1L, 2L));
        when(resultRepository.findReplayRowsByContestId(eq(1L), isNull(), any(), any()))
                .thenReturn(List.of(row(3L, SubmissionResult.ACCEPTED)));
        when(resultRepository.findReplayRowsByContestId(eq(2L), isNull(), any(), any()))
                .thenReturn(List.of(row(4L, SubmissionResult.ACCEPTED), row(5L, SubmissionResult.ACCEPTED)));
        // The keyset loop ends on the empty page after each contest's last row.
        when(resultRepository.findReplayRowsByContestId(eq(1L), eq(3L), any(), any()))
                .thenReturn(List.of());
        when(resultRepository.findReplayRowsByContestId(eq(2L), eq(5L), any(), any()))
                .thenReturn(List.of());
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> succeed(invocation.getArgument(0)));

        assertThat(replayService.replayAllContests()).isEqualTo(3);
    }

    private void stubPage(Long afterId, List<ContestScoreboardReplayRow> rows) {
        if (afterId == null) {
            when(resultRepository.findReplayRowsByContestId(eq(CONTEST_ID), isNull(), any(), any()))
                    .thenReturn(rows);
        } else {
            when(resultRepository.findReplayRowsByContestId(eq(CONTEST_ID), eq(afterId), any(), any()))
                    .thenReturn(rows);
        }
        if (!rows.isEmpty()) {
            when(scoreboardApplier.applyAll(anyList()))
                    .thenAnswer(invocation -> succeed(invocation.getArgument(0)));
        }
    }

    private static ContestScoreboardRecoveryProperties properties(int dbBatchSize, int replayBatchSize) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.FULL_REPLAY,
                new ContestScoreboardRecoveryProperties.FullReplay(dbBatchSize, replayBatchSize, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 1000, 500,
                        3, Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED)
        );
    }

    private static ContestScoreboardReplayRow row(long submissionId, SubmissionResult result) {
        return new ReplayRow(submissionId, result);
    }

    private static List<ContestScoreboardApplier.ApplyResult> succeed(
            List<ContestScoreboardApplier.ApplyRequest> requests) {
        return requests.stream()
                .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), null))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requestsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    /** Only the submission id and result take part in the assertions. */
    private static final class ReplayRow implements ContestScoreboardReplayRow {

        private final long submissionId;
        private final SubmissionResult result;

        ReplayRow(long submissionId, SubmissionResult result) {
            this.submissionId = submissionId;
            this.result = result;
        }

        @Override
        public Long getSubmissionId() {
            return submissionId;
        }

        @Override
        public Long getContestId() {
            return CONTEST_ID;
        }

        @Override
        public Long getProblemId() {
            return 201L;
        }

        @Override
        public Long getUserId() {
            return 100L;
        }

        @Override
        public LocalDateTime getContestStart() {
            return LocalDateTime.of(2024, 1, 1, 9, 0);
        }

        @Override
        public LocalDateTime getSubmittedTime() {
            return LocalDateTime.of(2024, 1, 1, 10, 0);
        }

        @Override
        public SubmissionResult getResult() {
            return result;
        }
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // no-op
        }
    }
}
