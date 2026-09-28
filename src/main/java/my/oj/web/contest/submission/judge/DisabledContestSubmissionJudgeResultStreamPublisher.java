package my.oj.web.contest.submission.judge;

import my.oj.web.contest.scoreboard.delivery.ContestScoreboardDelivery;
import my.oj.web.contest.scoreboard.delivery.ResultStreamPublisherDisabledCondition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registered whenever the RabbitMQ publisher is not: the publisher flag is off, or the scoreboard is fed
 * by the MySQL poller, which needs nothing published because the stored result is the delivery.
 */
@Component
@Conditional(ResultStreamPublisherDisabledCondition.class)
class DisabledContestSubmissionJudgeResultStreamPublisher
        implements ContestSubmissionJudgeResultStreamPublisher {

    private final boolean mySqlPollDelivery;

    @Autowired
    DisabledContestSubmissionJudgeResultStreamPublisher(Environment environment) {
        this(ContestScoreboardDelivery.isMySqlPoll(environment));
    }

    DisabledContestSubmissionJudgeResultStreamPublisher(boolean mySqlPollDelivery) {
        this.mySqlPollDelivery = mySqlPollDelivery;
    }

    /**
     * Under the MySQL poller this is the delivery: the row the batch writer just committed is what the
     * poller reads, so there is nothing to publish. Under the Stream delivery a result that is not
     * published never reaches the scoreboard, so the role is misconfigured and the batch fails loudly.
     */
    @Override
    public void publishAll(List<ContestSubmissionJudgeResultCommand> commands) {
        if (!mySqlPollDelivery && commands != null && !commands.isEmpty()) {
            throw new IllegalStateException("Judge result stream publisher is disabled");
        }
    }
}
