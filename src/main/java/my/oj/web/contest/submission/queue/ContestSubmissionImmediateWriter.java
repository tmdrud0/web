package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "contest.submission.writer", name = "mode", havingValue = "immediate")
public class ContestSubmissionImmediateWriter implements ContestSubmissionQueuedWriter {

    private final ContestSubmissionBulkProcessor processor;

    public ContestSubmissionImmediateWriter(ContestSubmissionBulkProcessor processor) {
        this.processor = processor;
    }

    @Override
    public ContestSubmissionService.ContestSubmissionCreateResult save(ContestSubmissionQueueRequest request) {
        return processor.processSingle(request);
    }
}
