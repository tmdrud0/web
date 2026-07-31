package my.oj.web.contest.submission.support;

import my.oj.web.contest.Contest;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestSubmissionProblemCacheTests {

    @Mock
    private ProblemRepository problemRepository;

    @Test
    void repeatedReadsUseCachedProblemAndContest() {
        Problem problem = problem(10L, 20L, null);
        given(problemRepository.findWithContestById(20L)).willReturn(Optional.of(problem));
        ContestSubmissionProblemCache cache =
                new ContestSubmissionProblemCache(problemRepository, 60L, 100, Clock.systemUTC());

        assertThat(cache.findById(20L)).containsSame(problem);
        assertThat(cache.findById(20L)).containsSame(problem);

        verify(problemRepository).findWithContestById(20L);
    }

    @Test
    void contestEvictionReloadsFinalizedState() {
        Problem openProblem = problem(10L, 20L, null);
        Problem finalizedProblem = problem(10L, 20L, null);
        finalizedProblem.getContest().markFinalized(LocalDateTime.now());
        given(problemRepository.findWithContestById(20L))
                .willReturn(Optional.of(openProblem), Optional.of(finalizedProblem));
        ContestSubmissionProblemCache cache =
                new ContestSubmissionProblemCache(problemRepository, 60L, 100, Clock.systemUTC());

        assertThat(cache.findById(20L).orElseThrow().getContest().isFinalized()).isFalse();
        cache.evictContest(10L);

        assertThat(cache.findById(20L).orElseThrow().getContest().isFinalized()).isTrue();
        verify(problemRepository, times(2)).findWithContestById(20L);
    }

    @Test
    void contestEndForcesReloadEvenBeforeTtlExpires() {
        ZoneId zone = ZoneOffset.UTC;
        MutableClock clock = new MutableClock(Instant.parse("2026-07-31T00:00:00Z"), zone);
        Problem problem = problem(10L, 20L, LocalDateTime.of(2026, 7, 31, 0, 1));
        given(problemRepository.findWithContestById(20L)).willReturn(Optional.of(problem));
        ContestSubmissionProblemCache cache =
                new ContestSubmissionProblemCache(problemRepository, 3600L, 100, clock);

        cache.findById(20L);
        clock.setInstant(Instant.parse("2026-07-31T00:01:01Z"));
        cache.findById(20L);

        verify(problemRepository, times(2)).findWithContestById(20L);
    }

    private static Problem problem(long contestId, long problemId, LocalDateTime contestEnd) {
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", contestId);
        ReflectionTestUtils.setField(contest, "endTime", contestEnd);
        Problem problem = Problem.create("A", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", problemId);
        return problem;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
