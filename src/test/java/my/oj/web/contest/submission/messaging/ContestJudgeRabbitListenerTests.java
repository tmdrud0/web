package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContestJudgeRabbitListenerTests {

    private final ContestSubmissionJudgeProcessor processor = mock(ContestSubmissionJudgeProcessor.class);
    private final ContestJudgeRabbitListener listener = new ContestJudgeRabbitListener(processor);

    @Test
    void delegatesSubmissionIdToExistingProcessor() {
        listener.judge(new ContestJudgeMessage(5L, 91L, 1));

        verify(processor).judge(91L);
    }

    @Test
    void propagatesProcessorFailureForContainerRetry() {
        RuntimeException failure = new RuntimeException("judge failed");
        doThrow(failure).when(processor).judge(91L);

        assertThatThrownBy(() -> listener.judge(new ContestJudgeMessage(5L, 91L, 1)))
                .isSameAs(failure);
    }
}
