package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "contest.submission.judge.rabbit.listener", name = "enabled", havingValue = "true")
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
