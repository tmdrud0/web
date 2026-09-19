package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
        "'${contest.submission.judge.dispatch-mode:rabbit}' == 'rabbit' && "
                + "'${contest.submission.judge.rabbit.listener.enabled:false}' == 'true'")
class ContestJudgeRabbitListener {

    private final ContestSubmissionJudgeProcessor judgeProcessor;

    ContestJudgeRabbitListener(ContestSubmissionJudgeProcessor judgeProcessor) {
        this.judgeProcessor = judgeProcessor;
    }

    @RabbitListener(
            queues = ContestJudgeRabbitTopology.LIVE_QUEUE,
            containerFactory = "contestJudgeRabbitListenerContainerFactory"
    )
    void judge(ContestJudgeMessage message) {
        judgeProcessor.judge(message.submissionId());
    }
}
