package my.oj.web.contest.submission.judge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContestSubmissionJudgeProcessor {

    private final ContestSubmissionService contestSubmissionService;
    private final ContestSubmissionJudgement contestJudgement;
    private final ContestSubmissionJudgeResultWriter resultWriter;

    public void judge(Long contestSubmissionId) {
        if (contestSubmissionId == null) {
            return;
        }

        var storedResult = contestSubmissionService.findStoredJudgeResultById(contestSubmissionId);
        if (storedResult.isPresent()) {
            log.info(
                    "Republishing stored contest judge result without rejudging submission {}",
                    contestSubmissionId
            );
            resultWriter.republish(ContestSubmissionJudgeResultCommand.from(storedResult.get()));
            return;
        }

        ContestSubmissionJudgeProjection submission =
                contestSubmissionService.getJudgeProjectionById(contestSubmissionId);
        SubmissionResult result = contestJudgement.judgeSubmission(submission);
        resultWriter.persist(
                submission,
                result,
                LocalDateTime.now()
        );
    }
}
