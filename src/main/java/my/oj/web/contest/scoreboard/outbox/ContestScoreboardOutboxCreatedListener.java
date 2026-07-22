package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "contest.outbox.immediate", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContestScoreboardOutboxCreatedListener {

    private final ContestScoreboardOutboxProcessor processor;

    @Async("contestSubmissionExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxCreated(ContestScoreboardOutboxCreatedEvent evt) {
        processor.processById(evt.outboxId());
    }
}
