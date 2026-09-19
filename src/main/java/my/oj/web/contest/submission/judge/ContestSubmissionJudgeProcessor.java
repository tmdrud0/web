package my.oj.web.contest.submission.judge;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.SubmissionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class ContestSubmissionJudgeProcessor {

    private final ContestSubmissionService contestSubmissionService;
    private final ContestSubmissionJudgement contestJudgement;
    private final ContestSubmissionJudgeResultWriter resultWriter;
    private final ContestJudgeExecutionMetrics metrics;

    @Autowired
    public ContestSubmissionJudgeProcessor(ContestSubmissionService contestSubmissionService,
                                           ContestSubmissionJudgement contestJudgement,
                                           ContestSubmissionJudgeResultWriter resultWriter,
                                           ContestJudgeExecutionMetrics metrics) {
        this.contestSubmissionService = contestSubmissionService;
        this.contestJudgement = contestJudgement;
        this.resultWriter = resultWriter;
        this.metrics = metrics;
    }

    ContestSubmissionJudgeProcessor(ContestSubmissionService contestSubmissionService,
                                    ContestSubmissionJudgement contestJudgement,
                                    ContestSubmissionJudgeResultWriter resultWriter) {
        this(contestSubmissionService, contestJudgement, resultWriter,
                new ContestJudgeExecutionMetrics("rabbit"));
    }

    public void judge(Long contestSubmissionId) {
        if (contestSubmissionId == null) {
            return;
        }

        var storedResult = contestSubmissionService.findStoredJudgeResultById(contestSubmissionId);
        if (storedResult.isPresent()) {
            metrics.recordStoredResultRepublish();
            log.info(
                    "Republishing stored contest judge result without rejudging submission {}",
                    contestSubmissionId
            );
            resultWriter.republish(ContestSubmissionJudgeResultCommand.from(storedResult.get()));
            return;
        }

        ContestSubmissionJudgeProjection submission =
                contestSubmissionService.getJudgeProjectionById(contestSubmissionId);
        long started = System.nanoTime();
        SubmissionResult result;
        try {
            result = contestJudgement.judgeSubmission(submission);
        } finally {
            metrics.recordJudgement(System.nanoTime() - started);
        }
        resultWriter.persist(
                submission,
                result,
                LocalDateTime.now()
        );
    }
}
