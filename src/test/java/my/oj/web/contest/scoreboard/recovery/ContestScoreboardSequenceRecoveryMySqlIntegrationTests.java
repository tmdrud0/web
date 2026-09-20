package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboard;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardApplier;
import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.contest.scoreboard.stream.JdbcContestScoreboardAppliedAtWriter;
import my.oj.web.contest.submission.core.ContestScoreboardSequencedRow;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.testsupport.ContestScoreboardTestData;
import my.oj.web.testsupport.ContestScoreboardTestData.Attempt;
import my.oj.web.testsupport.ContestScoreboardTestData.SeededContest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sequence check's state machine against real MySQL.
 *
 * <p>What is stubbed here is the scoreboard and only the scoreboard: the detection queries, the
 * ordering walk, the replay's database write and the convergence of a second pass all run for real.
 * That split is deliberate - the queries are where a mistake is silent (a wrong keyset direction or
 * a per-contest grouping drops rows without failing), while the scoreboard's own write path is
 * already pinned by the Redis integration tests.</p>
 *
 * <p>The scoreboard is in process, which is the seam {@link ContestScoreboardSequenceSource} exists
 * for: a sequence state machine can be driven against the real schema without a Redis in the loop.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestQuerydslConfig.class)
class ContestScoreboardSequenceRecoveryMySqlIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 5, 4, 12, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ContestSubmissionResultRepository resultRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private InMemoryContestScoreboard scoreboard;
    private InMemoryContestScoreboardApplier applier;
    private ContestScoreboardRedisSequenceRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        scoreboard = new InMemoryContestScoreboard();
        applier = new InMemoryContestScoreboardApplier(scoreboard, () -> true);
        JdbcContestScoreboardAppliedAtWriter writer = new JdbcContestScoreboardAppliedAtWriter(jdbcTemplate);
        ContestScoreboardAppliedMarker marker =
                new ContestScoreboardAppliedMarker(writer, applier, () -> true);
        recoveryService = new ContestScoreboardRedisSequenceRecoveryService(
                resultRepository,
                applier,
                applier,
                new ContestScoreboardApplyLock(),
                marker,
                new ContestSubmissionBatchExecutor(transactionManager),
                properties(1000, 4),
                new ContestScoreboardRedisSequenceMetrics(new SimpleMeterRegistry())
        );
    }

    /**
     * The canonical loss: the database holds results applied after the snapshot, so their sequences
     * sit above an allocator that was rewound with the standings.
     *
     * <p>The replayed rows are asserted through what the replay left behind - the standings, the
     * timestamp the marker writes, and the invariant that makes a second pass empty - rather than
     * through the numbers themselves, which are the scoreboard's to choose.</p>
     */
    @Test
    void aLostTailIsReplayedUntilNothingSitsAboveTheAllocator() {
        SeededContest contest = seedContest("seq-tail", 1, 5);
        List<Judged> judged = attempts(contest, 5);

        // Restored from a snapshot taken after three of the five judgements: the scoreboard knows
        // three of them and has issued three sequences.
        for (int index = 0; index < 3; index++) {
            apply(judged.get(index));
        }
        // The database kept all five: it recorded the sequence each was applied under.
        writeSequences(judged, 1L);

        assertThat(applier.allocatorSequence()).isEqualTo(3L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = recoveryService.check();

        assertThat(report.replayed()).isEqualTo(2);
        assertThat(report.duplicateGroups()).isZero();
        assertThat(report.unresolved()).isFalse();
        assertThat(applier.allocatorSequence()).isEqualTo(5L);
        assertThat(storedSequences(judged))
                .allSatisfy(sequence -> assertThat(sequence).isLessThanOrEqualTo(applier.allocatorSequence()));
        // The two the replay reached are now marked applied; the three it left alone are untouched,
        // which is what makes the repair proportionate to the loss.
        assertThat(appliedTimestamps(judged.subList(3, 5))).doesNotContainNull();
        assertThat(appliedTimestamps(judged.subList(0, 3))).containsOnlyNulls();
        assertThat(rankingUserIds(contest)).containsExactlyInAnyOrderElementsOf(contest.userIds());
        // A replay is not stream work: the checkpoint the snapshot restored is left alone.
        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);

        // The point of the invariant: the repair is self-limiting, so the next pass is silent.
        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport second = recoveryService.check();
        assertThat(second.replayed()).isZero();
        assertThat(second.rounds()).isEqualTo(1);
    }

    /**
     * One sequence on three results in three different contests - and a sequence the walking path
     * cannot see.
     *
     * <p>The reused sequence is one the allocator has already passed, which is the only way the group
     * scan is load-bearing: the descending walk judges a row against the allocator and keeps only what
     * sits above it, so a reuse at or below the allocator is invisible to that walk and would be missed
     * entirely if the check relied on it. Nothing here sits above the allocator, so the group scan is
     * the only thing that can find these rows.</p>
     *
     * <p>The three rows belong to different contests, which is the other half: the allocator is global,
     * so a per-contest grouping would miss the group. The reused sequence is also the one the last
     * known result holds - the shape a rewound allocator leaves, where it re-issues a sequence it had
     * already handed out.</p>
     *
     * <p>Only the two unknown rows come away with a new sequence. The known one is already in the
     * scoreboard's processed set, so replaying it is absorbed and it keeps the sequence it has - which
     * is what makes the group resolve to a single row and the next pass silent.</p>
     */
    @Test
    void everyResultSharingASequenceAcrossContestsIsReplayedWithoutOmission() {
        SeededContest first = seedContest("seq-dup-a", 1, 1);
        SeededContest second = seedContest("seq-dup-b", 1, 1);
        Judged shared = attempts(first, 1).get(0);
        Judged other = attempts(second, 1).get(0);

        // Five results the restored scoreboard does know about: applying them is what lifts the
        // allocator to five, and the database records the same 1..5 they were issued.
        SeededContest background = seedContest("seq-dup-background", 1, 5);
        List<Judged> known = attempts(background, 5);
        for (Judged judged : known) {
            apply(judged);
        }
        writeSequences(known, 1L);

        // The rollback re-issued five to two results that were never applied here.
        writeSequence(shared, 5L);
        writeSequence(other, 5L);

        assertThat(applier.allocatorSequence()).isEqualTo(5L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = recoveryService.check();

        assertThat(report.duplicateGroups()).isEqualTo(1);
        assertThat(report.replayed()).isEqualTo(3);
        assertThat(report.unresolved()).isFalse();
        assertThat(storedSequences(List.of(shared, other)))
                .doesNotContainNull()
                .doesNotHaveDuplicates()
                .allSatisfy(sequence -> assertThat(sequence).isGreaterThan(5L));
        assertThat(storedSequences(List.of(shared, other)))
                .allSatisfy(sequence -> assertThat(sequence).isLessThanOrEqualTo(applier.allocatorSequence()));
        // The group had three members and the third was left where it was: a replay only moves the
        // results the scoreboard had lost.
        assertThat(storedSequences(known)).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(appliedTimestamps(List.of(shared, other))).doesNotContainNull();

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport afterRepair = recoveryService.check();
        assertThat(afterRepair.duplicateGroups()).isZero();
        assertThat(afterRepair.replayed()).isZero();
    }

    /**
     * The keyset the walk pages with, against the real query.
     *
     * <p>A window that comes back full has to be continued, not taken for the whole answer - and the
     * direction of the comparison is what decides that. Walking a two-row window at a time over four
     * sequenced rows must read all four exactly once, in descending order, and must not read the
     * unsequenced one at all.</p>
     */
    @Test
    void theWindowWalkKeysetsPastTheWindowItJustRead() {
        SeededContest contest = seedContest("seq-window", 1, 5);
        List<Judged> judged = attempts(contest, 5);
        writeSequences(judged, 1L);
        jdbcTemplate.update(
                "UPDATE contest_submission_result SET scoreboard_applied_seq = NULL WHERE submission_id = ?",
                judged.get(1).attempt().submissionId());

        List<ContestScoreboardSequencedRow> firstWindow = resultRepository.findSequencedRowsDescending(
                null, PageRequest.of(0, 2));
        Long afterFirstWindow = firstWindow.get(firstWindow.size() - 1).getAppliedSequence();
        List<ContestScoreboardSequencedRow> secondWindow = resultRepository.findSequencedRowsDescending(
                afterFirstWindow, PageRequest.of(0, 2));

        assertThat(firstWindow).extracting(ContestScoreboardSequencedRow::getAppliedSequence)
                .containsExactly(5L, 4L);
        assertThat(firstWindow).extracting(ContestScoreboardSequencedRow::getSubmissionId)
                .containsExactly(
                        judged.get(4).attempt().submissionId(),
                        judged.get(3).attempt().submissionId());
        assertThat(secondWindow).extracting(ContestScoreboardSequencedRow::getAppliedSequence)
                .containsExactly(3L, 1L);
        // The unsequenced result is not in either window: it was never applied under a sequence, so
        // it carries nothing the allocator could have rewound past.
        assertThat(concat(firstWindow, secondWindow))
                .extracting(ContestScoreboardSequencedRow::getSubmissionId)
                .doesNotContain(judged.get(1).attempt().submissionId());
    }

    /**
     * An unjudged result is not a candidate, in either of the shapes the checks look for.
     *
     * <p>Each unjudged row below carries exactly the marks that make a row a candidate: one sits above
     * the allocator, and two share a sequence with each other. Replaying one is not a wasted write.
     * The script records a submission in its processed set <em>outside</em> the branch that skips
     * {@code PENDING}, and the in-memory scoreboard mirrors that, so the submission would be recorded
     * as applied while it was unjudged, and its real judgement would then be absorbed as a duplicate
     * for good.</p>
     *
     * <p>The shape is seeded rather than produced, and deliberately so. A sequence is only ever
     * written for a result the scoreboard applied, and the judge path inserts a result row with
     * {@code INSERT IGNORE} rather than rewriting one - so an unjudged row holding a sequence has to
     * come from outside that path, such as a rejudge or an operator's repair. It is the value being
     * unjudged that decides what this mode owes it, not how it got there, and this is where that
     * decision is pinned.</p>
     *
     * <p>The last step is the assertion that matters: not that a mapping is absent from a map, but
     * that the judgement arriving afterwards still lands. It is the only observable that separates
     * "the scoreboard was never shown this row" from "it was shown and absorbed it".</p>
     */
    @Test
    void anUnjudgedResultIsNeverOfferedToTheScoreboard() {
        SeededContest contest = seedContest("seq-pending", 1, 5);
        List<Judged> judged = attempts(contest, 2);
        Judged aboveAllocator = unjudged(contest, 922_000_000_000_000_000L, contest.userIds().get(2));
        Judged sharingASequence = unjudged(contest, 923_000_000_000_000_000L, contest.userIds().get(3));
        Judged sharingTheSameSequence = unjudged(contest, 924_000_000_000_000_000L, contest.userIds().get(4));
        List<Judged> unjudged = List.of(aboveAllocator, sharingASequence, sharingTheSameSequence);

        for (Judged one : judged) {
            apply(one);
        }
        // The database kept a sequence for the judged results - the allocator is at two - and the
        // unjudged rows hold the two shapes the checks key on. The pair's seven is above the
        // allocator as well, so both scans reach it and only the pair's own reuse distinguishes them.
        writeSequences(judged, 1L);
        writeSequence(aboveAllocator, 3L);
        writeSequence(sharingASequence, 7L);
        writeSequence(sharingTheSameSequence, 7L);
        assertThat(applier.allocatorSequence()).isEqualTo(2L);

        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = recoveryService.check();

        // The reuse is found - it is a real reuse, and reporting it is the check's job - and neither
        // of its members is offered, nor is the row above the allocator. A round with no candidates
        // ends the pass, so this is the whole of what the mode did.
        assertThat(report.duplicateGroups()).isEqualTo(1);
        assertThat(report.replayed()).isZero();
        assertThat(report.rounds()).isEqualTo(1);
        assertThat(report.unresolved()).isFalse();

        List<Long> unjudgedIds = unjudged.stream().map(one -> one.attempt().submissionId()).toList();
        // The scoreboard was never shown them: no sequence was issued, and the marker wrote nothing.
        assertThat(applier.appliedSequences(unjudgedIds)).isEmpty();
        assertThat(appliedTimestamps(unjudged)).containsOnlyNulls();
        // And they were left exactly where they were: a row this mode declines to touch keeps both
        // its stored sequence and its missing timestamp.
        assertThat(storedSequences(unjudged)).containsExactly(3L, 7L, 7L);
        assertThat(rankingUserIds(contest)).containsExactlyInAnyOrderElementsOf(
                judged.stream().map(one -> one.attempt().userId()).toList());

        // The judgement arrives, as it would on the stream. It lands - which is what the exclusion
        // protects, because had the unjudged row been replayed the submission would already be in the
        // processed set and this update would be absorbed with the user never appearing.
        judge(aboveAllocator);
        apply(aboveAllocator, SubmissionResult.ACCEPTED);
        assertThat(rankingUserIds(contest)).contains(aboveAllocator.attempt().userId());
    }

    private SeededContest seedContest(String namePrefix, int problemCount, int userCount) {
        return ContestScoreboardTestData.seedContest(jdbcTemplate, namePrefix, CONTEST_START, problemCount, userCount);
    }

    /** One accepted attempt per user, on the contest's only problem, written as submission and result rows. */
    private List<Judged> attempts(SeededContest contest, int count) {
        List<Attempt> attempts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            attempts.add(new Attempt(
                    921_000_000_000_000_000L + contest.contestId() * 100 + index,
                    contest.problemIds().get(0),
                    contest.userIds().get(index % contest.userIds().size()),
                    index + 1,
                    index + 2,
                    SubmissionResult.ACCEPTED
            ));
        }
        ContestScoreboardTestData.insertAttempts(jdbcTemplate, contest.contestId(), CONTEST_START, attempts, true);
        return attempts.stream().map(attempt -> new Judged(contest, attempt)).toList();
    }

    private void apply(Judged judged) {
        apply(judged, judged.attempt().result());
    }

    /**
     * Applies an attempt as if the stream had delivered it just now, optionally under a result other
     * than the one it was seeded with - which is how a later judgement is delivered.
     */
    private void apply(Judged judged, SubmissionResult result) {
        Attempt attempt = judged.attempt();
        applier.apply(ContestScoreboardApplier.ApplyRequest.rebuild(
                attempt.submissionId(),
                new ContestScoreboardUpdate(
                        attempt.submissionId(),
                        judged.contest().contestId(),
                        attempt.problemId(),
                        attempt.userId(),
                        CONTEST_START,
                        CONTEST_START.plusMinutes(attempt.submittedMinute()),
                        result,
                        null
                )
        ));
    }

    /** A stored result that has not been judged yet, in the shape the result table holds it in. */
    private Judged unjudged(SeededContest contest, long submissionId, long userId) {
        Attempt attempt = new Attempt(
                submissionId,
                contest.problemIds().get(0),
                userId,
                3,
                4,
                SubmissionResult.PENDING
        );
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, List.of(attempt), true);
        return new Judged(contest, attempt);
    }

    /** Settles a result the way a later judgement would, both columns included. */
    private void judge(Judged judged) {
        jdbcTemplate.update("""
                UPDATE contest_submission_result
                SET provisional_result = ?, final_result = ?
                WHERE submission_id = ?
                """,
                SubmissionResult.ACCEPTED.name(),
                SubmissionResult.ACCEPTED.name(),
                judged.attempt().submissionId());
    }

    /** Writes each attempt's pre-rollback sequence, as an application before the snapshot recorded it. */
    private void writeSequences(List<Judged> judged, long firstSequence) {
        List<Object[]> updates = new ArrayList<>(judged.size());
        for (int index = 0; index < judged.size(); index++) {
            updates.add(new Object[]{firstSequence + index, judged.get(index).attempt().submissionId()});
        }
        jdbcTemplate.batchUpdate(
                "UPDATE contest_submission_result SET scoreboard_applied_seq = ? WHERE submission_id = ?",
                updates);
    }

    private void writeSequence(Judged judged, long sequence) {
        jdbcTemplate.update(
                "UPDATE contest_submission_result SET scoreboard_applied_seq = ? WHERE submission_id = ?",
                sequence, judged.attempt().submissionId());
    }

    private List<Long> storedSequences(List<Judged> judged) {
        List<Long> sequences = new ArrayList<>(judged.size());
        for (Judged one : judged) {
            sequences.add(jdbcTemplate.queryForObject(
                    "SELECT scoreboard_applied_seq FROM contest_submission_result WHERE submission_id = ?",
                    Long.class,
                    one.attempt().submissionId()));
        }
        return sequences;
    }

    private List<Object> appliedTimestamps(List<Judged> judged) {
        List<Object> timestamps = new ArrayList<>(judged.size());
        for (Judged one : judged) {
            timestamps.add(jdbcTemplate.queryForObject(
                    "SELECT scoreboard_applied_at FROM contest_submission_result WHERE submission_id = ?",
                    Object.class,
                    one.attempt().submissionId()));
        }
        return timestamps;
    }

    private List<Long> rankingUserIds(SeededContest contest) {
        return scoreboard.currentRanking(contest.contestId()).stream()
                .map(ContestScoreboardEntry::userId)
                .toList();
    }

    private static List<ContestScoreboardSequencedRow> concat(List<ContestScoreboardSequencedRow> first,
                                                              List<ContestScoreboardSequencedRow> second) {
        List<ContestScoreboardSequencedRow> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** A seeded attempt together with the contest it belongs to. */
    private record Judged(SeededContest contest, Attempt attempt) {
    }

    private static ContestScoreboardRecoveryProperties properties(int windowSize, int maxWindows) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.REDIS_SEQ,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), windowSize, maxWindows, 3, 500,
                        3, Duration.ofMillis(10), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED
                )
        , new ContestScoreboardRecoveryProperties.RecoveryOwner(true));
    }
}
