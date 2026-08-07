package my.oj.web.contest;

import my.oj.web.api.ResourceNotFoundException;

public class ContestNotFoundException extends ResourceNotFoundException {

    public ContestNotFoundException(long contestId) {
        super("Contest not found: " + contestId);
    }
}
