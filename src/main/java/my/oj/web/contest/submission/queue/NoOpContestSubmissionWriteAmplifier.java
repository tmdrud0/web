package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmission;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!perf-triple-writes")
public class NoOpContestSubmissionWriteAmplifier implements ContestSubmissionWriteAmplifier {

    @Override
    public void amplify(List<ContestSubmission> submissions) {
        // Default path keeps submission writes unchanged.
    }
}
