package my.oj.web.contest.submission.core;

import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.ContestSubmissionIdGenerator;
import my.oj.web.problem.Problem;
import my.oj.web.submission.CodeHashGenerator;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ContestSubmissionServiceTests {

    @Mock
    private ContestSubmissionRepository submissionRepository;

    @Mock
    private ContestSubmissionResultRepository resultRepository;

    @Mock
    private ContestSubmissionDuplicateRegistry duplicateRegistry;

    @Mock
    private ContestSubmissionWriter submissionWriter;

    @Mock
    private ContestSubmissionIdGenerator idGenerator;

    private ContestSubmissionService contestSubmissionService;

    private User user;
    private Problem problem;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        user = User.withState(10L, "alice", "pass", 0L, new Streak());
        Contest contest = new Contest("Test Contest");
        ReflectionTestUtils.setField(contest, "id", 100L);
        problem = Problem.create("P1", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 200L);
        now = LocalDateTime.now();
        contestSubmissionService = new ContestSubmissionService(
                submissionRepository,
                resultRepository,
                duplicateRegistry,
                idGenerator,
                submissionWriter
        );
    }

    @Test
    void create_savesNewSubmission_WhenUnique() {
        given(duplicateRegistry.findDuplicateSubmissionId(anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(Optional.empty());
        given(idGenerator.nextId()).willReturn(500L);
        ContestSubmission saved = ContestSubmission.create(user, problem, "print(1)", CodeHashGenerator.generate("print(1)"), now);
        ReflectionTestUtils.setField(saved, "id", 500L);
        given(submissionWriter.save(any())).willReturn(new ContestSubmissionService.ContestSubmissionCreateResult(saved, false));

        ContestSubmissionService.ContestSubmissionCreateResult result =
                contestSubmissionService.create(user, problem, "print(1)", now);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.submission().getId()).isEqualTo(500L);
        ArgumentCaptor<ContestSubmissionWriteRequest> captor = ArgumentCaptor.forClass(ContestSubmissionWriteRequest.class);
        verify(submissionWriter).save(captor.capture());
        assertThat(captor.getValue().problemId()).isEqualTo(200L);
        assertThat(captor.getValue().reservedSubmissionId()).isEqualTo(500L);
        verify(duplicateRegistry).registerSubmission(eq(100L), eq(200L), eq(10L), eq(CodeHashGenerator.generate("print(1)")), eq(500L));
    }

    @Test
    void create_returnsDuplicate_WhenWriterDetectsDuplicate() {
        given(duplicateRegistry.findDuplicateSubmissionId(anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(Optional.empty());
        given(idGenerator.nextId()).willReturn(777L);
        ContestSubmission existing = ContestSubmission.create(user, problem, "print(1)", CodeHashGenerator.generate("print(1)"), now.minusMinutes(1));
        ReflectionTestUtils.setField(existing, "id", 777L);
        given(submissionWriter.save(any())).willReturn(new ContestSubmissionService.ContestSubmissionCreateResult(existing, true));

        ContestSubmissionService.ContestSubmissionCreateResult result =
                contestSubmissionService.create(user, problem, "print(1)", now);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.submission().getId()).isEqualTo(777L);
        verify(submissionWriter).save(any());
    }

    @Test
    void createAsync_completesOnlyAfterWriterCommit() {
        given(duplicateRegistry.findDuplicateSubmissionId(anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(Optional.empty());
        given(idGenerator.nextId()).willReturn(501L);

        CompletableFuture<ContestSubmissionService.ContestSubmissionCreateResult> writerFuture =
                new CompletableFuture<>();
        given(submissionWriter.saveAsync(any())).willReturn(writerFuture);

        CompletionStage<ContestSubmissionService.ContestSubmissionCreateResult> stage =
                contestSubmissionService.createAsync(user, problem, "print(2)", now);

        assertThat(stage.toCompletableFuture()).isNotDone();
        verify(duplicateRegistry, never()).registerSubmission(anyLong(), anyLong(), anyLong(), anyString(), anyLong());

        ContestSubmission saved = ContestSubmission.create(
                user, problem, "print(2)", CodeHashGenerator.generate("print(2)"), now
        );
        ReflectionTestUtils.setField(saved, "id", 501L);
        writerFuture.complete(new ContestSubmissionService.ContestSubmissionCreateResult(saved, false));

        assertThat(stage.toCompletableFuture().join().submission().getId()).isEqualTo(501L);
        verify(duplicateRegistry).registerSubmission(
                eq(100L), eq(200L), eq(10L), eq(CodeHashGenerator.generate("print(2)")), eq(501L)
        );
    }

    @Test
    void create_throwsWhenContestFinalized() {
        Contest contest = problem.getContest();
        contest.markFinalized(now.minusMinutes(1));

        assertThatThrownBy(() -> contestSubmissionService.create(user, problem, "print(1)", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contest submissions are closed");
    }

    @Test
    void create_returnsRedisCachedDuplicateWithoutSaving() {
        ContestSubmission existing = ContestSubmission.create(user, problem, "print(1)", "hash", now.minusMinutes(1));
        ReflectionTestUtils.setField(existing, "id", 888L);

        given(duplicateRegistry.findDuplicateSubmissionId(eq(100L), eq(200L), eq(10L), anyString()))
                .willReturn(Optional.of(888L));
        given(submissionRepository.findById(888L)).willReturn(Optional.of(existing));

        ContestSubmissionService.ContestSubmissionCreateResult result =
                contestSubmissionService.create(user, problem, "print(1)", now);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.submission().getId()).isEqualTo(888L);
        verify(submissionRepository).findById(888L);
        verifyNoInteractions(submissionWriter);
        verify(duplicateRegistry).registerSubmission(eq(100L), eq(200L), eq(10L), eq(CodeHashGenerator.generate("print(1)")), eq(888L));
    }

    @Test
    void purgeContest_deletesRedisEntries() {
        contestSubmissionService.purgeContest(100L);

        verify(resultRepository).deleteByContestId(100L);
        verify(submissionRepository).deleteByContestId(100L);
        verify(duplicateRegistry).purgeContest(100L);
    }

}
