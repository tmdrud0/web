package my.oj.web.submission;

import my.oj.web.api.ResourceNotFoundException;

public class SubmissionNotFoundException extends ResourceNotFoundException {

    public SubmissionNotFoundException(long submissionId) {
        super("Submission not found: " + submissionId);
    }
}
