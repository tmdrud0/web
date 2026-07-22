package my.oj.web.submission.event.normal;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.event.SubmissionResultEvent;
import my.oj.web.submission.event.SubmissionSubmittedEvent;
import my.oj.web.submission.judge.Judgement;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class NormalSubmissionSubmittedListener {

    private final SubmissionRepository submissionRepository;
    private final ApplicationEventPublisher publisher;
    private final Judgement judgement;

    @Async
    @EventListener(
            condition = "#evt.origin == T(my.oj.web.submission.SubmissionOrigin).NORMAL"
    )
    public void onSubmitted(SubmissionSubmittedEvent evt) {
        Long submissionId = evt.submissionId();
        if (submissionId == null) {
            return;
        }
        Submission submission = submissionRepository.getReferenceById(submissionId);
        Submission judged = judgement.judgeSubmission(submission);

        publisher.publishEvent(new SubmissionResultEvent(
                judged.getId(),
                SubmissionOrigin.NORMAL,
                judged.getResult(),
                LocalDateTime.now()
        ));
    }
}
