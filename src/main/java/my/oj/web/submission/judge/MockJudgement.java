package my.oj.web.submission.judge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

@Slf4j
@Component
@Profile("test")
@Qualifier("fullJudge")
public class MockJudgement implements Judgement {

    @Override
    public Submission judgeSubmission(Submission submission) {
        Random rand = new Random();
        if(rand.nextBoolean()) submission.setResult(SubmissionResult.ACCEPTED);
        else                   submission.setResult(SubmissionResult.WRONG_ANSWER);

        return submission;
    }
}