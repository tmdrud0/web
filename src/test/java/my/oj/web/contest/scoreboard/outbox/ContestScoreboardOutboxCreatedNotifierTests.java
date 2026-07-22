package my.oj.web.contest.scoreboard.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestScoreboardOutboxCreatedNotifierTests {

    @Test
    void immediateNotifierReloadsOutboxAndPublishesItsId() {
        ContestScoreboardOutboxService outboxService = mock(ContestScoreboardOutboxService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ContestScoreboardOutbox outbox = mock(ContestScoreboardOutbox.class);
        when(outbox.getId()).thenReturn(77L);
        when(outboxService.findByContestSubmissionId(91L)).thenReturn(Optional.of(outbox));
        ImmediateContestScoreboardOutboxCreatedNotifier notifier =
                new ImmediateContestScoreboardOutboxCreatedNotifier(outboxService, publisher);

        notifier.notifyCreated(91L);

        var event = forClass(ContestScoreboardOutboxCreatedEvent.class);
        verify(publisher).publishEvent(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().outboxId()).isEqualTo(77L);
    }

    @Test
    void noopNotifierDoesNothingInJudgeRole() {
        new NoopContestScoreboardOutboxCreatedNotifier().notifyCreated(91L);
    }
}
