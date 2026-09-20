package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.submission.core.ContestScoreboardDuplicateSequence;
import my.oj.web.contest.submission.core.ContestScoreboardSequencedRow;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code redis-seq} check pass, with the scoreboard and the database stubbed.
 *
 * <p>The integration tests prove the queries and the apply path; this one proves the decisions in
 * between - which rows become candidates, in which order the sources are read, and how many rounds a
 * pass spends. Those are the parts a wrong answer does not fail loudly on: the check would report a
 * clean pass while never having compared the two values that matter.</p>
 *
 * <p>Where a replay happens, the stub gives the loss for the first round and nothing afterwards -
 * which is what the replayed rows are supposed to look like once the marker has persisted their new
 * sequence. A pass that reports a second round of candidates is the non-converging case, and only
 * the test about it asks for that.</p>
 */
class ContestScoreboardRedisSequenceRecoveryServiceTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 5, 4, 12, 0);
    private static final long SUBMISSION_101 = 101L;
    private static final long SUBMISSION_102 = 102L;
    private static final long SUBMISSION_103 = 103L;

    private ContestSubmissionResultRepository resultRepository;
    private ContestScoreboardApplier scoreboardApplier;
    private ContestScoreboardSequenceSource sequenceSource;
    private ContestScoreboardApplyLock applyLock;
    private ContestScoreboardAppliedMarker appliedMarker;
    private ContestSubmissionBatchExecutor batchExecutor;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        resultRepository = mock(ContestSubmissionResultRepository.class);
        scoreboardApplier = mock(ContestScoreboardApplier.class);
        sequenceSource = mock(ContestScoreboardSequenceSource.class);
        applyLock = mock(ContestScoreboardApplyLock.class);
        appliedMarker = mock(ContestScoreboardAppliedMarker.class);
        batchExecutor = mock(ContestSubmissionBatchExecutor.class);
        registry = new SimpleMeterRegistry();

        when(resultRepository.findDuplicateAppliedSequences(any(), any())).thenReturn(List.of());
        when(resultRepository.findSequencedRowsDescending(any(), any())).thenReturn(List.of());
        when(resultRepository.findRowsByAppliedSequences(anyList())).thenReturn(List.of());
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), null))
                    .toList();
        });
        // The collaborators are stubs, so the retry wrapper and the lock have to actually run their
        // work: a check that silently did not replay would pass a test that only counted calls.
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(batchExecutor).executeWithRetry(any(), anyInt(), any());
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(applyLock).withLock(any(Runnable.class));
    }

    @Test
    void aConsistentScoreboardFindsNothingToReplay() {
        givenTailFirstRoundOnly(row(SUBMISSION_101, 3L), row(SUBMISSION_102, 2L));
        when(sequenceSource.allocatorSequence()).thenReturn(3L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = service(config()).check();

        assertThat(report.rounds()).isEqualTo(1);
        assertThat(report.replayed()).isZero();
        assertThat(report.duplicateGroups()).isZero();
        assertThat(report.unresolved()).isFalse();
        verifyNoInteractions(scoreboardApplier);
        verifyNoInteractions(appliedMarker);
    }

    /**
     * The lost tail: a result stored under a sequence the scoreboard no longer knows about, because
     * the snapshot it was restored from predates it.
     */
    @Test
    void aSequenceAboveTheAllocatorIsReplayed() {
        givenTailFirstRoundOnly(row(SUBMISSION_101, 5L), row(SUBMISSION_102, 4L), row(SUBMISSION_103, 3L));
        when(sequenceSource.allocatorSequence()).thenReturn(3L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = service(config()).check();

        assertThat(report.rounds()).isEqualTo(2);
        assertThat(report.replayed()).isEqualTo(2);
        assertThat(report.unresolved()).isFalse();
        assertThat(registry.get("contest.scoreboard.redis.sequence.replayed").counter().count())
                .isEqualTo(2.0);

        List<ContestScoreboardApplier.ApplyRequest> requests = replayedRequests();
        assertThat(requests).extracting(request -> request.update().contestSubmissionId())
                .containsExactly(SUBMISSION_102, SUBMISSION_101);
        assertThat(requests).allSatisfy(request -> {
            // A replay must not claim stream work: the checkpoint belongs to the stream path.
            assertThat(request.streamOffset()).isNull();
            assertThat(request.update().result()).isEqualTo(SubmissionResult.ACCEPTED);
        });
        verify(appliedMarker).markApplied(List.of(SUBMISSION_102, SUBMISSION_101));
    }

    /**
     * Every row of a group, not one representative: re-application is what gives each of them a
     * sequence of its own, so leaving one out leaves the reuse in place.
     */
    @Test
    void everyResultOfAReusedSequenceIsReplayed() {
        givenDuplicateGroupFirstRoundOnly(List.of(duplicate(4L, 2L)),
                List.of(row(SUBMISSION_101, 4L), row(SUBMISSION_102, 4L)));
        when(sequenceSource.allocatorSequence()).thenReturn(9L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = service(config()).check();

        assertThat(report.duplicateGroups()).isEqualTo(1);
        assertThat(report.replayed()).isEqualTo(2);
        assertThat(report.rounds()).isEqualTo(2);
        assertThat(report.unresolved()).isFalse();
        assertThat(registry.get("contest.scoreboard.redis.sequence.duplicates").counter().count())
                .isEqualTo(1.0);
    }

    /**
     * The ordering rule, and the reason it is a test of its own: a sequence is stored after the
     * allocator issued it, so reading the allocator first would flag every in-flight result as lost.
     * Only the database-first order makes the comparison mean "the allocator moved backwards".
     *
     * <p>The whole sequence is spelled out, both rounds included, and closed with
     * {@code verifyNoMoreInteractions}. Relative order alone is not enough to pin this: an allocator
     * read hoisted above the database reads would still find a later allocator read to match, and
     * the test would pass while the rule it names was broken. Closing the sequence is what makes a
     * call in the wrong place - or one call too many - fail here.</p>
     */
    @Test
    void everyDatabaseReadHappensBeforeTheAllocatorIsRead() {
        givenDuplicateGroupFirstRoundOnly(List.of(duplicate(4L, 2L)), List.of(row(SUBMISSION_101, 4L)));
        givenTailFirstRoundOnly(row(SUBMISSION_102, 2L));
        when(sequenceSource.allocatorSequence()).thenReturn(9L);

        service(config()).check();

        InOrder order = inOrder(resultRepository, sequenceSource);
        // Round one: both database reads, and only then the allocator.
        order.verify(resultRepository).findDuplicateAppliedSequences(isNull(), any());
        order.verify(resultRepository).findRowsByAppliedSequences(List.of(4L));
        order.verify(resultRepository).findSequencedRowsDescending(isNull(), any());
        order.verify(sequenceSource).allocatorSequence();
        // Round two, after the replay: the group is gone, so the pass ends.
        order.verify(resultRepository).findDuplicateAppliedSequences(isNull(), any());
        order.verify(resultRepository).findSequencedRowsDescending(isNull(), any());
        order.verify(sequenceSource).allocatorSequence();
        order.verify(sequenceSource).mappedSubmissionCount();
        order.verifyNoMoreInteractions();
    }

    /**
     * A pass that keeps finding candidates is a pass that cannot repair what it found - replaying a
     * result the scoreboard already applied does not reissue its sequence. It must spend its rounds
     * and report that, instead of looping while pretending to converge.
     */
    @Test
    void aPassThatKeepsFindingCandidatesSpendsItsRoundsAndReportsIt() {
        when(resultRepository.findDuplicateAppliedSequences(isNull(), any()))
                .thenReturn(List.of(duplicate(4L, 2L)));
        when(resultRepository.findRowsByAppliedSequences(List.of(4L)))
                .thenReturn(List.of(row(SUBMISSION_101, 4L)));
        when(sequenceSource.allocatorSequence()).thenReturn(9L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = service(config()).check();

        assertThat(report.rounds()).isEqualTo(2);
        assertThat(report.replayed()).isEqualTo(2);
        assertThat(report.unresolved()).isTrue();
        assertThat(registry.get("contest.scoreboard.redis.sequence.unresolved").counter().count())
                .isEqualTo(1.0);
    }

    /**
     * The allocator is read once per round and never per window, so a walk that needed several
     * windows is still judged against a single value.
     */
    @Test
    void aMultiWindowWalkIsJudgedAgainstOneAllocatorValue() {
        when(resultRepository.findSequencedRowsDescending(isNull(), any()))
                .thenReturn(List.of(row(SUBMISSION_101, 5L), row(SUBMISSION_102, 4L)))
                .thenReturn(List.of());
        when(resultRepository.findSequencedRowsDescending(eq(4L), any()))
                .thenReturn(List.of(row(SUBMISSION_103, 3L)));
        when(sequenceSource.allocatorSequence()).thenReturn(2L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report =
                service(config(2, 3)).check();

        assertThat(report.replayed()).isEqualTo(3);
        assertThat(report.saturated()).isFalse();
        // Three windows were read across the two rounds; the allocator was read once per round.
        verify(sequenceSource, times(2)).allocatorSequence();
    }

    /**
     * A window budget that runs out means the walk stopped looking, which is a different thing from
     * a tail that was fully read - and the metric has to say so, because the rows beyond the budget
     * are indistinguishable from rows that were never lost.
     */
    @Test
    void aWalkThatRunsOutOfWindowsIsReportedAsSaturated() {
        when(resultRepository.findSequencedRowsDescending(isNull(), any()))
                .thenReturn(List.of(row(SUBMISSION_101, 5L)))
                .thenReturn(List.of());
        when(resultRepository.findSequencedRowsDescending(eq(5L), any()))
                .thenReturn(List.of(row(SUBMISSION_102, 3L)));
        when(sequenceSource.allocatorSequence()).thenReturn(1L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report =
                service(config(1, 2)).check();

        assertThat(report.replayed()).isEqualTo(2);
        assertThat(report.saturated()).isTrue();
        assertThat(registry.get("contest.scoreboard.redis.sequence.windows.saturated").counter().count())
                .isEqualTo(1.0);
    }

    /** The replay bounds are the operator's, not constants. */
    @Test
    void theReplayUsesTheConfiguredChunkSizeAndRetryBounds() {
        givenTailFirstRoundOnly(row(SUBMISSION_101, 5L), row(SUBMISSION_102, 4L));
        when(sequenceSource.allocatorSequence()).thenReturn(3L);

        ContestScoreboardRecoveryProperties.RedisSequence config =
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 100, 3, 5, 1, 4,
                        Duration.ofMillis(20), true);
        service(config).check();

        verify(batchExecutor, times(2))
                .executeWithRetry(any(), eq(4), eq(Duration.ofMillis(20)));
        verify(applyLock, times(2)).withLock(any(Runnable.class));
    }

    /**
     * The failure is not swallowed here. The scheduler owns the guard that records it and keeps the
     * schedule alive, so a pass that hid its own failure would leave that guard nothing to record.
     */
    @Test
    void aReplayFailurePropagatesToTheCaller() {
        givenTailFirstRoundOnly(row(SUBMISSION_101, 5L));
        when(sequenceSource.allocatorSequence()).thenReturn(1L);
        doAnswer(invocation -> {
            throw new IllegalStateException("scoreboard unavailable");
        }).when(batchExecutor).executeWithRetry(any(), anyInt(), any());

        assertThatThrownBy(() -> service(config()).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scoreboard unavailable");
        verify(appliedMarker, never()).markApplied(anyList());
    }

    /**
     * The marker is what makes a replayed result stop being a candidate: it reads the sequence the
     * scoreboard now holds back out and writes it beside the timestamp.
     */
    @Test
    void theReplayedSequenceIsPersistedThroughTheMarker() {
        givenTailFirstRoundOnly(row(SUBMISSION_101, 5L));
        when(sequenceSource.allocatorSequence()).thenReturn(1L);

        service(config()).check();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(appliedMarker).markApplied(ids.capture());
        assertThat(ids.getValue()).containsExactly(SUBMISSION_101);
    }

    /** A loss the first round finds and the second does not, because the replay repaired it. */
    private void givenTailFirstRoundOnly(ContestScoreboardSequencedRow... rows) {
        when(resultRepository.findSequencedRowsDescending(isNull(), any()))
                .thenReturn(List.of(rows))
                .thenReturn(List.of());
    }

    private void givenDuplicateGroupFirstRoundOnly(List<ContestScoreboardDuplicateSequence> page,
                                                  List<ContestScoreboardSequencedRow> rows) {
        when(resultRepository.findDuplicateAppliedSequences(isNull(), any()))
                .thenReturn(page)
                .thenReturn(List.of());
        when(resultRepository.findRowsByAppliedSequences(page.stream()
                .map(ContestScoreboardDuplicateSequence::getAppliedSequence)
                .toList())).thenReturn(rows);
    }

    private ContestScoreboardRedisSequenceRecoveryService service(
            ContestScoreboardRecoveryProperties.RedisSequence redisSeq) {
        return new ContestScoreboardRedisSequenceRecoveryService(
                resultRepository,
                scoreboardApplier,
                sequenceSource,
                applyLock,
                appliedMarker,
                batchExecutor,
                properties(redisSeq),
                new ContestScoreboardRedisSequenceMetrics(registry)
        );
    }

    private static ContestScoreboardRecoveryProperties properties(
            ContestScoreboardRecoveryProperties.RedisSequence redisSeq) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.REDIS_SEQ,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                redisSeq,
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED
                )
        , new ContestScoreboardRecoveryProperties.RecoveryOwner(true));
    }

    /** A pass with the documented defaults except for the two sizes each test is about. */
    private static ContestScoreboardRecoveryProperties.RedisSequence config() {
        return config(100, 3);
    }

    private static ContestScoreboardRecoveryProperties.RedisSequence config(int windowSize, int maxWindows) {
        return new ContestScoreboardRecoveryProperties.RedisSequence(
                Duration.ofSeconds(30), Duration.ofSeconds(30), windowSize, maxWindows, 2, 500, 3,
                Duration.ofMillis(50), true);
    }

    private List<ContestScoreboardApplier.ApplyRequest> replayedRequests() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requests =
                ArgumentCaptor.forClass(List.class);
        verify(scoreboardApplier).applyAll(requests.capture());
        return requests.getValue();
    }

    private static ContestScoreboardSequencedRow row(long submissionId, Long appliedSequence) {
        return new SequencedRow(submissionId, appliedSequence);
    }

    private static ContestScoreboardDuplicateSequence duplicate(long sequence, long count) {
        return new DuplicateSequence(sequence, count);
    }

    private record SequencedRow(long submissionId, Long appliedSequence)
            implements ContestScoreboardSequencedRow {

        @Override
        public Long getSubmissionId() {
            return submissionId;
        }

        @Override
        public Long getAppliedSequence() {
            return appliedSequence;
        }

        @Override
        public Long getContestId() {
            return 9001L;
        }

        @Override
        public Long getProblemId() {
            return 21L;
        }

        @Override
        public Long getUserId() {
            return 201L;
        }

        @Override
        public LocalDateTime getContestStart() {
            return CONTEST_START;
        }

        @Override
        public LocalDateTime getSubmittedTime() {
            return CONTEST_START.plusMinutes(1);
        }

        @Override
        public SubmissionResult getResult() {
            return SubmissionResult.ACCEPTED;
        }
    }

    private record DuplicateSequence(long sequence, long count) implements ContestScoreboardDuplicateSequence {

        @Override
        public Long getAppliedSequence() {
            return sequence;
        }

        @Override
        public long getResultCount() {
            return count;
        }
    }
}
