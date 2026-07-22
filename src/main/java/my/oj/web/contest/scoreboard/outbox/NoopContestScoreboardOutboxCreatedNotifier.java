package my.oj.web.contest.scoreboard.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "contest.outbox.immediate", name = "enabled", havingValue = "false")
class NoopContestScoreboardOutboxCreatedNotifier implements ContestScoreboardOutboxCreatedNotifier {

    @Override
    public void notifyCreated(Long contestSubmissionId) {
    }
}
