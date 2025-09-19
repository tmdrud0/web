package my.oj.web.submission.event.contest;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.event.SubmissionResultEvent;
import my.oj.web.submission.event.SubmissionSubmittedEvent;
import my.oj.web.submission.judge.Judgement;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ContestSubmissionSubmittedListener {

    private final ContestSubmissionService contestSubmissionService;
    private final ApplicationEventPublisher publisher;
    @Qualifier("contestJudgement")
    private final Judgement contestJudgement;

    @Async
    @EventListener(condition = "#evt.origin == T(my.oj.web.submission.SubmissionOrigin).CONTEST")
    public void onContestSubmission(SubmissionSubmittedEvent evt) {
        Long contestSubmissionId = evt.submissionId();
        if (contestSubmissionId == null) {
            return;
        }
        ContestSubmission contestSubmission = contestSubmissionService.getById(contestSubmissionId);

        Submission submission = Submission.create(
                contestSubmission.getUser(),
                contestSubmission.getProblem(),
                contestSubmission.getCode(),
                contestSubmission.getSubmittedTime()
        );

        Submission judged = contestJudgement.judgeSubmission(submission);
        publisher.publishEvent(new SubmissionResultEvent(
                contestSubmissionId,
                SubmissionOrigin.CONTEST,
                judged.getResult(),
                LocalDateTime.now()
        ));
    }
}
