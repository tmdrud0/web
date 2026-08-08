package my.oj.web.contest.submission.judge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "contest.submission.judge.result-stream.publisher",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
class DisabledContestSubmissionJudgeResultStreamPublisher
        implements ContestSubmissionJudgeResultStreamPublisher {

    @Override
    public void publishAll(List<ContestSubmissionJudgeResultCommand> commands) {
        if (commands != null && !commands.isEmpty()) {
            throw new IllegalStateException("Judge result stream publisher is disabled");
        }
    }
}
