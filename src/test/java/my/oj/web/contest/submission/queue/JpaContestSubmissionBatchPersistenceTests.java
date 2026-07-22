package my.oj.web.contest.submission.queue;

import jakarta.persistence.EntityManager;
import my.oj.web.contest.submission.core.ContestSubmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class JpaContestSubmissionBatchPersistenceTests {

    @Mock
    private EntityManager entityManager;

    @Test
    void insertAllPersistsEverySubmissionBeforeFlush() {
        JpaContestSubmissionBatchPersistence persistence = new JpaContestSubmissionBatchPersistence();
        ReflectionTestUtils.setField(persistence, "entityManager", entityManager);
        ContestSubmission first = ContestSubmission.placeholder(1L);
        ContestSubmission second = ContestSubmission.placeholder(2L);

        persistence.insertAll(List.of(first, second));

        var ordered = inOrder(entityManager);
        ordered.verify(entityManager).persist(first);
        ordered.verify(entityManager).persist(second);
        ordered.verify(entityManager).flush();
    }
}
