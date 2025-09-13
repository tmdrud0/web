package my.oj.web.submission.event.normal;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.event.SubmissionResultEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NormalSubmissionResultListener {
    private final NormalSubmissionResultService submissionResultService;

    @Async
    @EventListener(condition = "#evt.origin == T(my.oj.web.submission.SubmissionOrigin).NORMAL")
    public void onResult(SubmissionResultEvent evt) {
        if (evt.submissionId() == null) {
            return;
        }
        submissionResultService.handleSubmissionResult(evt.submissionId(), evt.result(), evt.judgedAt());
    }
}
