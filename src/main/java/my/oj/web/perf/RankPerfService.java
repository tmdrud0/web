package my.oj.web.perf;

import java.util.List;

import lombok.RequiredArgsConstructor;
import my.oj.web.perf.dto.AroundBenchResult;
import my.oj.web.perf.dto.BucketRebuildResult;
import my.oj.web.perf.dto.LongestBenchResult;
import my.oj.web.perf.dto.SolvedBenchResult;
import my.oj.web.perf.dto.StreakBenchResult;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.streak.longest.LongestStreakSnapshotRepository;
import my.oj.web.user.rank.streak.longest.LongestStreakRankService;
import my.oj.web.user.rank.streak.longest.NaiveLongestStreakRepository;
import my.oj.web.user.rank.solved.NaiveSolvedRepository;
import my.oj.web.user.rank.solved.SolvedRankRepository;
import my.oj.web.user.rank.solved.SolvedRankService;
import my.oj.web.user.rank.solved.solvedbucket.SolvedBucketRepository;
import my.oj.web.user.rank.streak.NaiveStreakRepository;
import my.oj.web.user.rank.streak.StreakRankService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RankPerfService {

    private final StreakRankService streakRankService;
    private final NaiveStreakRepository naiveStreakRepository;
    private final NaiveSolvedRepository naiveSolvedRepository;
    private final SolvedRankRepository rankRepository;
    private final SolvedBucketRepository solvedBucketRepository;
    private final SolvedRankService rankService;
    private final LongestStreakRankService longestStreakRankService;
    private final LongestStreakSnapshotRepository longestStreakSnapshotRepository;
    private final NaiveLongestStreakRepository naiveLongestStreakRepository;

    @Transactional
    public BucketRebuildResult rebuildSolvedBuckets() {
        long t0 = System.nanoTime();
        rankService.rebuildSolvedBuckets();
        long t1 = System.nanoTime();
        return new BucketRebuildResult(nanosToMillis(t0, t1));
    }

    public StreakBenchResult benchmarkStreak(int page, int size) {
        int offset = Math.max(0, page) * size;
        long naiveStart = System.nanoTime();
        var naiveRows = naiveStreakRepository.fetchNaivePage(offset, size);
        long naiveEnd = System.nanoTime();

        long optimizedStart = System.nanoTime();
        var optimized = streakRankService.getPage((long) page * size + 1, size);
        long optimizedEnd = System.nanoTime();

        return new StreakBenchResult(
                offset,
                naiveRows.size(),
                nanosToMillis(naiveStart, naiveEnd),
                optimized.items().size(),
                nanosToMillis(optimizedStart, optimizedEnd)
        );
    }

    public SolvedBenchResult benchmarkSolved(int page, int size) {
        int offset = Math.max(0, page) * size;
        long naiveStart = System.nanoTime();
        var naiveRows = naiveSolvedRepository.fetchNaivePage(offset, size);
        long naiveEnd = System.nanoTime();

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

        long optimizedStart = System.nanoTime();
        var optimizedRows = rankRepository.fetchPageFromBucket(
                bucketSolvedCount,
                offsetInGroup,
                size
        );
        long optimizedEnd = System.nanoTime();

        return new SolvedBenchResult(
                offset,
                naiveRows.size(),
                nanosToMillis(naiveStart, naiveEnd),
                optimizedRows.size(),
                nanosToMillis(optimizedStart, optimizedEnd),
                pageStartRank,
                bucketSolvedCount,
                bucketCumHigher
        );
    }

    public LongestBenchResult benchmarkLongest(int page, int size) {
        int offset = Math.max(0, page) * size;
        long naiveStart = System.nanoTime();
        var naiveRows = naiveLongestStreakRepository.fetchNaivePage(offset, size);
        long naiveEnd = System.nanoTime();

        long pageStartRank = (long) page * size + 1;
        long totalPositive = longestStreakSnapshotRepository.count();
        if (totalPositive == 0) {
            throw new IllegalStateException("Buckets not initialized. Run /perf/buckets/longest/rebuild first.");
        }
        if (pageStartRank > totalPositive) {
            pageStartRank = ((totalPositive - 1) / size) * size + 1;
        }

        long optimizedStart = System.nanoTime();
        var optimizedRows = longestStreakSnapshotRepository.findPage(pageStartRank, size);
        long optimizedEnd = System.nanoTime();

        return new LongestBenchResult(
                offset,
                naiveRows.size(),
                nanosToMillis(naiveStart, naiveEnd),
                optimizedRows.size(),
                nanosToMillis(optimizedStart, optimizedEnd),
                pageStartRank,
                0,
                0
        );
    }

    public RankItemDto getSolvedRank(long rank) {
        return rankService.getUserAtRank(rank);
    }

    public RankItemDto getStreakRank(long rank) {
        return streakRankService.getUserAtRank(rank);
    }

    public RankItemDto getLongestRank(long rank) {
        return longestStreakRankService.getUserAtRank(rank);
    }

    public AroundBenchResult benchmarkSolvedAroundRank(long rank, int size) {
        RankItemDto target = rankService.getUserAtRank(rank);
        long userId = target.userId();

        long optimizedStart = System.nanoTime();
        RankPageDto optimized = rankService.getSolvedCountPageForUser(userId, size);
        long optimizedEnd = System.nanoTime();

        long pageStart = optimized.pageStartRank();
        int offset = (int) Math.max(0, pageStart - 1);

        long naiveStart = System.nanoTime();
        List<NaiveSolvedRepository.NaiveRowProjection> naiveRows = naiveSolvedRepository.fetchNaivePage(offset, size);
        long naiveEnd = System.nanoTime();

        return new AroundBenchResult(
                rank,
                userId,
                target,
                optimized.myRank(),
                optimized.pageStartRank(),
                nanosToMillis(optimizedStart, optimizedEnd),
                nanosToMillis(naiveStart, naiveEnd)
        );
    }

    public AroundBenchResult benchmarkStreakAroundRank(long rank, int size) {
        RankItemDto target = streakRankService.getUserAtRank(rank);
        long userId = target.userId();

        long optimizedStart = System.nanoTime();
        RankPageDto optimized = streakRankService.getPageAroundUser(userId, size);
        long optimizedEnd = System.nanoTime();

        long pageStart = optimized.pageStartRank();
        int offset = (int) Math.max(0, pageStart - 1);

        long naiveStart = System.nanoTime();
        List<NaiveStreakRepository.NaiveRowProjection> naiveRows = naiveStreakRepository.fetchNaivePage(offset, size);
        long naiveEnd = System.nanoTime();

        return new AroundBenchResult(
                rank,
                userId,
                target,
                optimized.myRank(),
                optimized.pageStartRank(),
                nanosToMillis(optimizedStart, optimizedEnd),
                nanosToMillis(naiveStart, naiveEnd)
        );
    }

    public AroundBenchResult benchmarkLongestAroundRank(long rank, int size) {
        RankItemDto target = longestStreakRankService.getUserAtRank(rank);
        long userId = target.userId();

        long optimizedStart = System.nanoTime();
        RankPageDto optimized = longestStreakRankService.getPageAroundUser(userId, size);
        long optimizedEnd = System.nanoTime();

        long pageStart = optimized.pageStartRank();
        int offset = (int) Math.max(0, pageStart - 1);

        long naiveStart = System.nanoTime();
        var naiveRows = naiveLongestStreakRepository.fetchNaivePage(offset, size);
        long naiveEnd = System.nanoTime();

        return new AroundBenchResult(
                rank,
                userId,
                target,
                optimized.myRank(),
                optimized.pageStartRank(),
                nanosToMillis(optimizedStart, optimizedEnd),
                nanosToMillis(naiveStart, naiveEnd)
        );
    }

    private double nanosToMillis(long start, long end) {
        return (end - start) / 1_000_000.0;
    }
}


