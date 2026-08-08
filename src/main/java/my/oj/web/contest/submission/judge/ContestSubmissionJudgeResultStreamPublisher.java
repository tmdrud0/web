package my.oj.web.contest.submission.judge;

import java.util.List;

public interface ContestSubmissionJudgeResultStreamPublisher {

    void publishAll(List<ContestSubmissionJudgeResultCommand> commands);
}
