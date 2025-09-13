package my.oj.web.submission.dto;

import lombok.Getter;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

@Getter
public class SubmissionSummaryDto {
    private final Long id;
    private final Long problemId;
    private final String problemName;
    private final Long userId;
    private final String username;
    private final SubmissionResult result;
    private final LocalDateTime submittedTime;


    public SubmissionSummaryDto(Long id, Long problemId, String problemName,
                                Long userId, String username, SubmissionResult result,
                                LocalDateTime submittedTime) {
        this.id = id;
        this.problemId = problemId;
        this.problemName = problemName;
        this.userId = userId;
        this.username = username;
        this.result = result;
        this.submittedTime = submittedTime;
    }


}
