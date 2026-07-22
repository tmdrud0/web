package my.oj.web.contest.submission.support;

import jakarta.annotation.PostConstruct;
import my.oj.web.contest.submission.core.ContestSubmissionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "contest.submission.id", name = "strategy", havingValue = "simple")
public class SimpleContestSubmissionIdGenerator implements ContestSubmissionIdGenerator {

    private final ContestSubmissionRepository submissionRepository;
    private final AtomicLong counter = new AtomicLong(0L);

    public SimpleContestSubmissionIdGenerator(ContestSubmissionRepository submissionRepository) {
        this.submissionRepository = submissionRepository;
    }

    @PostConstruct
    public void initialize() {
        Long lastId = submissionRepository.findMaxId();
        counter.set(lastId != null ? lastId : 0L);
    }

    @Override
    public long nextId() {
        return counter.incrementAndGet();
    }
}
