package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "contest.outbox.immediate", name = "enabled", havingValue = "true", matchIfMissing = true)
class ImmediateContestScoreboardOutboxCreatedNotifier implements ContestScoreboardOutboxCreatedNotifier {

    private final ContestScoreboardOutboxService outboxService;
    private final ApplicationEventPublisher publisher;

    @Override
    public void notifyCreated(Long contestSubmissionId) {
        outboxService.findByContestSubmissionId(contestSubmissionId)
                .ifPresent(outbox -> publisher.publishEvent(new ContestScoreboardOutboxCreatedEvent(outbox.getId())));
    }
}
