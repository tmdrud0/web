package my.oj.web.submission.judge;

import java.util.Random;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("test")
@Qualifier("partialJudge")
public class MockPartialJudgement implements Judgement {

    @Override
    public Submission judgeSubmission(Submission submission) {
        Random rand = new Random();
        if(rand.nextBoolean()) submission.setResult(SubmissionResult.PARTIAL_ACCEPTED);
        else                   submission.setResult(SubmissionResult.WRONG_ANSWER);

        return submission;
    }
}
