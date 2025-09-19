package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ContestScoreboardOutboxScheduler {

    private final ContestScoreboardOutboxRepository outboxRepository;
    private final ContestScoreboardOutboxProcessor processor;

    @Scheduled(fixedDelayString = "${contest.outbox.poll-interval-ms:5000}")
    public void pollAndProcess() {
        List<ContestScoreboardOutbox> candidates = outboxRepository
                .findTop50ByStatusInOrderByCreatedAtAsc(List.of(
                        ContestScoreboardOutboxStatus.PENDING,
                        ContestScoreboardOutboxStatus.FAILED
                ));

        for (ContestScoreboardOutbox candidate : candidates) {
            processor.processById(candidate.getId());
        }
    }
}
