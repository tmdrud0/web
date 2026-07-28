package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardOutboxProcessorTests {

    @Mock
    private ContestScoreboardApplier outboxApplier;

    @Mock
    private JdbcContestScoreboardOutboxQueue outboxQueue;

    private ContestScoreboardOutboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ContestScoreboardOutboxProcessor(outboxApplier, outboxQueue);
    }

    @Test
    void processBatchAppliesClaimedEventsAndCompletesSuccessesAndFailuresTogether() {
        Duration lease = Duration.ofSeconds(30);
        ContestScoreboardUpdate accepted = payload(1001L, SubmissionResult.ACCEPTED);
        ContestScoreboardUpdate failed = payload(1002L, SubmissionResult.WRONG_ANSWER);
        JdbcContestScoreboardOutboxQueue.ClaimedEvent first =
                new JdbcContestScoreboardOutboxQueue.ClaimedEvent(11L, accepted, "claim-token");
        JdbcContestScoreboardOutboxQueue.ClaimedEvent second =
                new JdbcContestScoreboardOutboxQueue.ClaimedEvent(12L, failed, "claim-token");
        given(outboxQueue.claim(50, lease)).willReturn(List.of(first, second));
        given(outboxApplier.applyAll(org.mockito.ArgumentMatchers.anyList())).willReturn(List.of(
                ContestScoreboardApplier.ApplyResult.success(11L, 101L),
                ContestScoreboardApplier.ApplyResult.failure(12L, "wrong Redis key type")
        ));
        given(outboxQueue.completeAll(org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyList()))
                .willReturn(new JdbcContestScoreboardOutboxQueue.BatchCompletionResult(1, 1, 1, 1));

        ContestScoreboardOutboxProcessor.BatchProcessResult result = processor.processBatch(50, lease);

        assertThat(result).isEqualTo(new ContestScoreboardOutboxProcessor.BatchProcessResult(2, 1, 1, 0));
        verify(outboxQueue).completeAll(
                org.mockito.ArgumentMatchers.argThat(completed ->
                        completed.size() == 1
                                && completed.get(0).event().eventId() == 11L
                                && completed.get(0).redisSequence().equals(101L)),
                org.mockito.ArgumentMatchers.argThat(failures ->
                        failures.size() == 1
                                && failures.get(0).event().eventId() == 12L
                                && failures.get(0).error().contains("wrong Redis key type"))
        );
    }

    private ContestScoreboardUpdate payload(Long submissionId, SubmissionResult result) {
        return new ContestScoreboardUpdate(
                submissionId,
                10L,
                20L,
                30L,
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                result,
                LocalDateTime.of(2026, 3, 10, 12, 2)
        );
    }
}
