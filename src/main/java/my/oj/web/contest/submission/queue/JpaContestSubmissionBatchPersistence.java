package my.oj.web.contest.submission.queue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import my.oj.web.contest.submission.core.ContestSubmission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "contest.submission.bulk",
        name = "persistence-mode",
        havingValue = "jpa",
        matchIfMissing = true
)
public class JpaContestSubmissionBatchPersistence implements ContestSubmissionBatchPersistence {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertAll(List<ContestSubmission> submissions) {
        submissions.forEach(entityManager::persist);
        entityManager.flush();
    }
}
