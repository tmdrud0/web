package my.oj.web.contest.submission.messaging;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
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
    void judge(ContestJudgeMessage message,
               @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        if (Boolean.TRUE.equals(redelivered)) {
            // The broker keeps no durable per-delivery record, so this line is the only place a
            // redelivery (for example after a judge node died holding the message unacknowledged) can
            // be tied to the submission it carried.
            log.info("Redelivered contest judge message for submission {}", message.submissionId());
        }
        judge(message);
    }

    void judge(ContestJudgeMessage message) {
        judgeProcessor.judge(message.submissionId());
    }
}
