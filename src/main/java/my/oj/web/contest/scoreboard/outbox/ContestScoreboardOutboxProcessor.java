package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ContestScoreboardOutboxProcessor {

    private final ContestScoreboardOutboxService outboxService;
    private final ContestScoreboardService scoreboardService;

    @Async
    @EventListener
    public void onOutboxCreated(ContestScoreboardOutboxCreatedEvent evt) {
        processById(evt.outboxId());
    }

    @Transactional
    public void processById(Long outboxId) {
        ContestScoreboardOutbox outbox = outboxService.lockById(outboxId);
        if (!outbox.isProcessable()) {
            return;
        }
        try {
            scoreboardService.recordJudgement(
                    outbox.getId(),
                    outbox.getContestId(),
                    outbox.getProblemId(),
                    outbox.getUserId(),
                    outbox.getContestStart(),
                    outbox.getSubmittedTime(),
                    outbox.getResult()
            );
            outbox.markSuccess(LocalDateTime.now());
        } catch (Exception ex) {
            outbox.markFailed(ex.getMessage());
            throw ex;
        }
    }
}
