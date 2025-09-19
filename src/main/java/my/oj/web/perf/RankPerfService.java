package my.oj.web.perf;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import lombok.RequiredArgsConstructor;
import my.oj.web.perf.dto.BucketRebuildResult;
import my.oj.web.perf.dto.SeedRequest;
import my.oj.web.perf.dto.SeedResult;
import my.oj.web.perf.dto.SnapshotResult;
import my.oj.web.perf.dto.SolvedBenchResult;
import my.oj.web.perf.dto.StreakBenchResult;
import my.oj.web.user.rank.NaiveSolvedRepository;
import my.oj.web.user.rank.RankRepository;
import my.oj.web.user.rank.RankService;
import my.oj.web.user.rank.solvedbucket.SolvedBucketRepository;
import my.oj.web.user.rank.streaksnapshot.NaiveStreakRepository;
import my.oj.web.user.rank.streaksnapshot.StreakRankService;
import my.oj.web.user.rank.streaksnapshot.StreakSnapshotService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RankPerfService {

    private final JdbcTemplate jdbc;
    private final StreakSnapshotService snapshotService;
    private final StreakRankService streakRankService;
    private final NaiveStreakRepository naiveStreakRepository;
    private final NaiveSolvedRepository naiveSolvedRepository;
    private final RankRepository rankRepository;
    private final SolvedBucketRepository solvedBucketRepository;
    private final RankService rankService;

    @Transactional
    public SeedResult seedUsers(SeedRequest request) {
        String sql = "INSERT INTO `user`(name, pass, solved_count, streak_last_solved_date, "
                + "streak_current_streak, streak_longest_streak) VALUES (?,?,?,?,?,?)";
        var now = LocalDateTime.now();
        var random = new Random(42);
        int inserted = 0;
        List<Object[]> buffer = new java.util.ArrayList<>(request.batchSize());

        for (int i = 1; i <= request.totalUsers(); i++) {
            int daysAgo = random.nextInt(5);
            LocalDateTime lastSolved = now.minusDays(daysAgo).minusMinutes(random.nextInt(1440));
            int currentStreak = random.nextInt(30);
            int longestStreak = Math.max(currentStreak, random.nextInt(50));
            buffer.add(new Object[]{
                    "u" + i,
                    "p",
                    random.nextLong(1000),
                    lastSolved,
                    currentStreak,
                    longestStreak
            });

            if (buffer.size() == request.batchSize()) {
                jdbc.batchUpdate(sql, buffer);
                inserted += buffer.size();
                buffer.clear();
            }
        }

        if (!buffer.isEmpty()) {
            jdbc.batchUpdate(sql, buffer);
            inserted += buffer.size();
        }

        return new SeedResult(inserted);
    }

    public SnapshotResult rebuildSnapshot(int pageSize) {
        long t0 = System.nanoTime();
        snapshotService.rebuild(LocalDate.now(), pageSize);
        long t1 = System.nanoTime();
        return new SnapshotResult(nanosToMillis(t0, t1));
    }

    public BucketRebuildResult rebuildSolvedBuckets() {
        long t0 = System.nanoTime();
        rankService.rebuildSolvedBuckets();
        long t1 = System.nanoTime();
        return new BucketRebuildResult(nanosToMillis(t0, t1));
    }

    public StreakBenchResult benchmarkStreak(int page, int size) {
        int offset = Math.max(0, page) * size;
        long n0 = System.nanoTime();
        var naiveRows = naiveStreakRepository.fetchNaivePage(offset, size);
        long n1 = System.nanoTime();

        long s0 = System.nanoTime();
        var snapshot = streakRankService.getPage(page, size);
        long s1 = System.nanoTime();

        return new StreakBenchResult(
                offset,
                naiveRows.size(),
                nanosToMillis(n0, n1),
                snapshot.items().size(),
                nanosToMillis(s0, s1)
        );
    }

    public SolvedBenchResult benchmarkSolved(int page, int size) {
        int offset = Math.max(0, page) * size;
        long n0 = System.nanoTime();
        var naiveRows = naiveSolvedRepository.fetchNaivePage(offset, size);
        long n1 = System.nanoTime();

        long pageStartRank = (long) page * size + 1;
        var bucket = solvedBucketRepository.findBucketForRank(pageStartRank);
        if (bucket == null) {
            throw new IllegalStateException("Buckets not initialized. Run /perf/buckets/rebuild first.");
        }

        long bucketSolvedCount = bucket.getN();
        long bucketCumHigher = bucket.getCumHigherCount();
        int offsetInGroup = (int) (pageStartRank - (bucketCumHigher + 1));
        if (offsetInGroup < 0) {
            offsetInGroup = 0;
        }

        var cursor = rankRepository.findKthInGroup(bucketSolvedCount, offsetInGroup);
        if (cursor == null) {
            cursor = rankRepository.findKthInGroup(bucketSolvedCount, 0);
            if (cursor == null) {
                throw new IllegalStateException("Empty bucket for solvedCount=" + bucketSolvedCount);
            }
        }

        long s0 = System.nanoTime();
        var optimizedRows = rankRepository.fetchPageFromCursor(
                cursor.getSolvedCount(),
                cursor.getLastSolvedDate(),
                cursor.getId(),
                size
        );
        long s1 = System.nanoTime();

        return new SolvedBenchResult(
                offset,
                naiveRows.size(),
                nanosToMillis(n0, n1),
                optimizedRows.size(),
                nanosToMillis(s0, s1),
                pageStartRank,
                bucketSolvedCount,
                bucketCumHigher
        );
    }

    private double nanosToMillis(long start, long end) {
        return (end - start) / 1_000_000.0;
    }
}
