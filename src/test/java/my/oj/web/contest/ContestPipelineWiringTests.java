package my.oj.web.contest;

import my.oj.web.contest.scoreboard.ContestScoreboardStore;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardStore;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxApplier;
import my.oj.web.contest.scoreboard.outbox.DirectContestScoreboardOutboxApplier;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.ContestSubmissionIdGenerator;
import my.oj.web.contest.submission.support.ContestSubmissionRateLimiter;
import my.oj.web.contest.submission.support.InMemoryContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.InMemoryContestSubmissionRateLimiter;
import my.oj.web.contest.submission.support.SimpleContestSubmissionIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which implementation each @ConditionalOnProperty axis resolves to.
 *
 * <p>Every axis below has two or more implementations selected by a property. Nothing
 * else in the suite notices when one is swapped for another, so a misplaced property
 * file — or a deleted branch that silently falls back to a different bean — passes
 * unnoticed. These assertions make that loud.
 *
 * <p>When an axis is intentionally collapsed to a single implementation, delete its
 * assertion here in the same commit.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContestPipelineWiringTests {

    @Autowired
    ContestScoreboardStore scoreboardStore;
    @Autowired
    ContestScoreboardOutboxApplier outboxApplier;
    @Autowired
    ContestSubmissionDuplicateRegistry duplicateRegistry;
    @Autowired
    ContestSubmissionRateLimiter rateLimiter;
    @Autowired
    ContestSubmissionIdGenerator idGenerator;

    @Test
    void shared_state_still_runs_in_memory_until_a_container_is_available() {
        assertThat(scoreboardStore).isInstanceOf(InMemoryContestScoreboardStore.class);
        assertThat(duplicateRegistry).isInstanceOf(InMemoryContestSubmissionDuplicateRegistry.class);
        assertThat(rateLimiter).isInstanceOf(InMemoryContestSubmissionRateLimiter.class);
    }

    @Test
    void scoreboard_outbox_uses_direct_applier() {
        assertThat(outboxApplier).isInstanceOf(DirectContestScoreboardOutboxApplier.class);
    }

    @Test
    void id_generation_follows_the_main_configuration() {
        assertThat(idGenerator).isInstanceOf(SimpleContestSubmissionIdGenerator.class);
    }
}
