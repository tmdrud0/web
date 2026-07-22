package my.oj.web.user.rank.solved;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.User;
import my.oj.web.user.rank.RankPageAssembler;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.solved.solvedbucket.SolvedBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SolvedRankService {
    private final SolvedRankRepository rankRepository;
    private final SolvedBucketRepository solvedBucketRepository;
    private final SolvedBucketMaintenanceService solvedBucketMaintenanceService;

    @Transactional
    public RankPageDto getSolvedCountPageForUser(long userId, int pageSize) {
        int normalizedSize = Math.max(pageSize, 1);
        User user = rankRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

        if (user.getSolvedCount() == 0) {
            return fallbackForZeroSolved(user, normalizedSize);
        }

        RankContext context = loadUserContext(user, normalizedSize);
        return buildPage(context.pageStartRank, normalizedSize, context.myRank);
    }

    @Transactional(readOnly = true)
    public RankPageDto getSolvedCountPage(Long cursor, int pageSize) {
        int normalizedSize = Math.max(pageSize, 1);
        long desiredStart = cursor == null ? 1L : cursor;
        return buildPage(desiredStart, normalizedSize, -1);
    }

    @Transactional(readOnly = true)
    public RankItemDto getUserAtRank(long rank) {
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }

        long totalPositiveUsers = solvedBucketRepository.totalUsers();
        if (totalPositiveUsers == 0) {
            long actualUsers = rankRepository.count();
            if (actualUsers == 0) {
                throw new IllegalArgumentException("rank out of range: " + rank + " (total=0)");
            }
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            totalPositiveUsers = solvedBucketRepository.totalUsers();
        }
        if (rank > totalPositiveUsers) {
            throw new IllegalArgumentException("rank out of range: " + rank + " (total=" + totalPositiveUsers + ")");
        }

        SolvedBucketRepository.BucketAtRankProjection bucket = solvedBucketRepository.findBucketForRank(rank);
        if (bucket == null) {
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            bucket = solvedBucketRepository.findBucketForRank(rank);
            if (bucket == null) {
                throw new IllegalStateException("bucket missing for rank=" + rank);
            }
        }

        long bucketSolvedCount = bucket.getN();
        long bucketCumHigher = bucket.getCumHigherCount();
        int offset = (int) Math.max(0, rank - (bucketCumHigher + 1));

        List<SolvedRankRepository.UserPageRowProjection> rows = rankRepository.fetchPageFromBucket(
                bucketSolvedCount,
                offset,
                1
        );

        if (rows.isEmpty()) {
            rows = rankRepository.fetchPageFromBucket(bucketSolvedCount, 0, 1);
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException("failed to load user at rank=" + rank);
        }

        SolvedRankRepository.UserPageRowProjection row = rows.get(0);
        return new RankItemDto(rank, row.getId(), row.getName(), row.getSolvedCount(), row.getLastSolvedDate());
    }

    public void rebuildSolvedBuckets() {
        solvedBucketMaintenanceService.rebuildSolvedBuckets();
    }

    private RankContext loadUserContext(User user, int pageSize) {
        long solvedCount = user.getSolvedCount();
        LocalDateTime lastSolvedAt = user.getStreak() != null ? user.getStreak().getLastSolvedDate() : null;
        LocalDateTime effectiveLastSolved = lastSolvedAt != null ? lastSolvedAt : LocalDateTime.MIN;

        Long cumHigher = solvedBucketRepository.findCumHigher(solvedCount);
        if (cumHigher == null) {
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            cumHigher = solvedBucketRepository.findCumHigher(solvedCount);
            if (cumHigher == null) {
                throw new IllegalStateException("bucket missing for n=" + solvedCount);
            }
        }

        long tieBefore = rankRepository.countTieBefore(solvedCount, effectiveLastSolved, user.getId());
        long myRank = cumHigher + tieBefore + 1;

        long pageStartRank = ((myRank - 1) / pageSize) * pageSize + 1;
        return new RankContext(myRank, pageStartRank);
    }

    private RankPageDto fallbackForZeroSolved(User user, int pageSize) {
        int normalizedSize = Math.max(pageSize, 1);

        long totalPositive = solvedBucketRepository.totalUsers();
        if (totalPositive == 0) {
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            totalPositive = solvedBucketRepository.totalUsers();
        }

        int fetchSize = Math.max(0, Math.min(normalizedSize - 1, (int) totalPositive));
        long startRank = fetchSize == 0 ? 1 : Math.max(1, totalPositive - fetchSize + 1);

        List<RankItemDto> items = new ArrayList<>(normalizedSize);
        if (fetchSize > 0) {
            List<SolvedRankRepository.UserPageRowProjection> rows = fetchRows(startRank, fetchSize);
            items.addAll(RankPageAssembler.toRankItems(
                    startRank,
                    rows,
                    SolvedRankRepository.UserPageRowProjection::getId,
                    SolvedRankRepository.UserPageRowProjection::getName,
                    SolvedRankRepository.UserPageRowProjection::getSolvedCount,
                    SolvedRankRepository.UserPageRowProjection::getLastSolvedDate
            ));
        }

        LocalDateTime lastSolved = user.getStreak() != null ? user.getStreak().getLastSolvedDate() : null;
        long zeroRank = totalPositive + 1;

        items.add(new RankItemDto(
                zeroRank,
                user.getId(),
                user.getName(),
                user.getSolvedCount(),
                lastSolved
        ));

        return new RankPageDto(
                zeroRank,
                startRank,
                normalizedSize,
                totalPositive,
                (fetchSize > 0 && startRank > 1) ? Math.max(1, startRank - normalizedSize) : null,
                null,
                items
        );
    }

    private RankPageDto buildPage(long desiredStartRank, int pageSize, long myRank) {
        long totalPositiveUsers = solvedBucketRepository.totalUsers();
        if (totalPositiveUsers == 0) {
            long actualUsers = rankRepository.count();
            if (actualUsers == 0) {
                return new RankPageDto(myRank, 1, pageSize, 0, null, null, List.of());
            }
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            totalPositiveUsers = solvedBucketRepository.totalUsers();
        }

        long adjustedStart = Math.max(1, desiredStartRank);
        if (adjustedStart > totalPositiveUsers) {
            adjustedStart = ((totalPositiveUsers - 1) / pageSize) * pageSize + 1;
        }

        List<SolvedRankRepository.UserPageRowProjection> rows = fetchRows(adjustedStart, pageSize);

        List<RankItemDto> items = RankPageAssembler.toRankItems(
                adjustedStart,
                rows,
                SolvedRankRepository.UserPageRowProjection::getId,
                SolvedRankRepository.UserPageRowProjection::getName,
                SolvedRankRepository.UserPageRowProjection::getSolvedCount,
                SolvedRankRepository.UserPageRowProjection::getLastSolvedDate
        );

        Long previousCursor = adjustedStart > 1 ? Math.max(1, adjustedStart - pageSize) : null;
        Long nextCursor = adjustedStart + pageSize <= totalPositiveUsers ? adjustedStart + pageSize : null;

        return new RankPageDto(myRank, adjustedStart, pageSize, totalPositiveUsers, previousCursor, nextCursor, items);
    }

    private List<SolvedRankRepository.UserPageRowProjection> fetchRows(long startRank, int pageSize) {
        SolvedBucketRepository.BucketAtRankProjection bucket = solvedBucketRepository.findBucketForRank(startRank);
        if (bucket == null) {
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            bucket = solvedBucketRepository.findBucketForRank(startRank);
            if (bucket == null) {
                throw new IllegalStateException("bucket missing for rank=" + startRank);
            }
        }

        long bucketSolvedCount = bucket.getN();
        long bucketCumHigher = bucket.getCumHigherCount();
        int offsetInGroup = (int) Math.max(0, startRank - (bucketCumHigher + 1));

        return rankRepository.fetchPageFromBucket(bucketSolvedCount, offsetInGroup, pageSize);
    }

    private record RankContext(long myRank, long pageStartRank) { }
}
