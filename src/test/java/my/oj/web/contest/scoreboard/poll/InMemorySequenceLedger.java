package my.oj.web.contest.scoreboard.poll;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The ledger's SQL in memory. {@link #recordApplied} is all-or-nothing, as its transaction is; the
 * transaction itself is pinned against MySQL by {@code JdbcContestScoreboardSequenceLedgerMySqlIntegrationTests}.
 */
class InMemorySequenceLedger implements ContestScoreboardSequenceLedger {

    static final LocalDateTime START = LocalDateTime.of(2026, 9, 28, 9, 0);

    final TreeMap<Long, Row> rows = new TreeMap<>();
    long watermark;
    final List<StoredRange> ranges = new ArrayList<>();
    long nextGeneration = 1;
    boolean failNextRecord;
    boolean failOpenRange;
    final List<Long> throughIdsQueried = new ArrayList<>();
    Consumer<InMemorySequenceLedger> afterUnsequencedQuery = ledger -> { };

    InMemorySequenceLedger judged(long submissionId, SubmissionResult result) {
        rows.put(submissionId, new Row(result, null));
        return this;
    }

    InMemorySequenceLedger sequenced(long submissionId, SubmissionResult result, long sequence) {
        rows.put(submissionId, new Row(result, sequence));
        watermark = Math.max(watermark, sequence);
        return this;
    }

    Long sequenceOf(long submissionId) {
        return rows.get(submissionId).sequence;
    }

    @Override
    public long highestDurableSequence() {
        return watermark;
    }

    @Override
    public Long highestUnsequencedJudgedSubmissionId() {
        return rows.descendingMap().entrySet().stream()
                .filter(entry -> entry.getValue().sequence == null && entry.getValue().judged())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<ContestScoreboardSequencedResult> unsequencedJudgedResults(Long afterId, long throughId, int limit) {
        throughIdsQueried.add(throughId);
        List<ContestScoreboardSequencedResult> page = rows.entrySet().stream()
                .filter(entry -> entry.getValue().sequence == null && entry.getValue().judged())
                .filter(entry -> afterId == null || entry.getKey() > afterId)
                .filter(entry -> entry.getKey() <= throughId)
                .limit(limit)
                .map(entry -> result(entry.getKey(), entry.getValue()))
                .toList();
        afterUnsequencedQuery.accept(this);
        return page;
    }

    @Override
    public void recordApplied(Map<Long, Long> sequencesBySubmissionId) {
        if (sequencesBySubmissionId.isEmpty()) {
            return;
        }
        if (failNextRecord) {
            failNextRecord = false;
            throw new IllegalStateException("MySQL is away");
        }
        if (!rows.keySet().containsAll(sequencesBySubmissionId.keySet())) {
            throw new IllegalStateException("A marker matched no row");
        }
        sequencesBySubmissionId.forEach((id, sequence) -> rows.get(id).sequence = sequence);
        watermark = Math.max(watermark,
                sequencesBySubmissionId.values().stream().max(Comparator.naturalOrder()).orElseThrow());
    }

    @Override
    public List<ContestScoreboardRecoveryRange> pendingRanges() {
        return ranges.stream().filter(range -> !range.completed).map(StoredRange::range).toList();
    }

    @Override
    public ContestScoreboardRecoveryRange openRange(long fromExclusive, long throughInclusive) {
        if (failOpenRange) {
            throw new IllegalStateException("MySQL is away");
        }
        for (StoredRange stored : ranges) {
            if (!stored.completed && stored.range.fromExclusive() == fromExclusive
                    && stored.range.throughInclusive() == throughInclusive) {
                return stored.range;
            }
        }
        StoredRange stored = new StoredRange(
                new ContestScoreboardRecoveryRange(nextGeneration++, fromExclusive, throughInclusive));
        ranges.add(stored);
        return stored.range;
    }

    @Override
    public List<ContestScoreboardSequencedResult> judgedResultsInRange(long fromExclusive,
                                                                      long throughInclusive,
                                                                      int limit) {
        return rows.entrySet().stream()
                .filter(entry -> entry.getValue().sequence != null
                        && entry.getValue().sequence > fromExclusive
                        && entry.getValue().sequence <= throughInclusive
                        && entry.getValue().judged())
                .sorted(Comparator.<Map.Entry<Long, Row>>comparingLong(entry -> entry.getValue().sequence)
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> result(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public boolean completeRange(long generation) {
        for (StoredRange stored : ranges) {
            if (stored.range.generation() == generation && !stored.completed) {
                stored.completed = true;
                return true;
            }
        }
        return false;
    }

    boolean completed(long generation) {
        return ranges.stream().anyMatch(range -> range.range.generation() == generation && range.completed);
    }

    private static ContestScoreboardSequencedResult result(long submissionId, Row row) {
        return new ContestScoreboardSequencedResult(submissionId, row.sequence, 1L, 10L, 100L + submissionId % 7,
                START, START.plusMinutes(submissionId % 60), row.result);
    }

    static final class Row {
        SubmissionResult result;
        Long sequence;

        Row(SubmissionResult result, Long sequence) {
            this.result = result;
            this.sequence = sequence;
        }

        boolean judged() {
            return result != SubmissionResult.PENDING;
        }
    }

    static final class StoredRange {
        final ContestScoreboardRecoveryRange range;
        boolean completed;

        StoredRange(ContestScoreboardRecoveryRange range) {
            this.range = range;
        }

        ContestScoreboardRecoveryRange range() {
            return range;
        }
    }
}
