package my.oj.web.submission.event.contest;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.event.SubmissionResultEvent;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContestSubmissionResultListener {

    private final ContestSubmissionService contestSubmissionService;

    @Async
    @EventListener(condition = "#evt.origin == T(my.oj.web.submission.SubmissionOrigin).CONTEST")
    public void onResult(SubmissionResultEvent evt) {
        Long contestSubmissionId = evt.submissionId();
        if (contestSubmissionId == null) {
            return;
        }
        contestSubmissionService.applyProvisionalResult(
                contestSubmissionId,
                evt.result(),
                evt.judgedAt()
        );
    }
}
