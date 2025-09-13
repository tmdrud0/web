package my.oj.web.user.rank;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.solvedbucket.SolvedBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RankService {
    private final RankRepository rankRepository;
    private final SolvedBucketRepository solvedBucketRepository;
    private final SolvedBucketMaintenanceService solvedBucketMaintenanceService;

    /**
     * Returns a solved-count ranking page anchored around the given user.
     * - Order: solved count DESC, last solved time ASC, user id ASC
     * - Page start is located using the solved-count bucket table
     */
    @Transactional
    public RankPageDto getSolvedCountPageForUser(long userId, int pageSize) {
        RankContext context = loadUserContext(userId, pageSize);

        SolvedBucketRepository.BucketAtRankProjection bucket = solvedBucketRepository.findBucketForRank(context.pageStartRank);
        if (bucket == null) {
            throw new IllegalStateException("no bucket for rank=" + context.pageStartRank);
        }

        RankRepository.UserKeyProjection cursor = locateCursor(bucket, context.pageStartRank);

        List<RankRepository.UserPageRowProjection> rows = rankRepository.fetchPageFromCursor(
                cursor.getSolvedCount(),
                cursor.getLastSolvedDate(),
                cursor.getId(),
                pageSize
        );

        List<RankItemDto> items = RankPageAssembler.toRankItems(
                context.pageStartRank,
                rows,
                RankRepository.UserPageRowProjection::getId,
                RankRepository.UserPageRowProjection::getName,
                RankRepository.UserPageRowProjection::getSolvedCount,
                RankRepository.UserPageRowProjection::getLastSolvedDate
        );

        return new RankPageDto(context.myRank, context.pageStartRank, pageSize, items);
    }

    /**
     * Rebuilds solved-count buckets from the current user distribution.
     */
    public void rebuildSolvedBuckets() {
        solvedBucketMaintenanceService.rebuildSolvedBuckets();
    }

    private RankContext loadUserContext(long userId, int pageSize) {
        RankRepository.UserKeyProjection userKey = rankRepository.findKeyByUserId(userId);
        if (userKey == null) {
            throw new IllegalArgumentException("user not found: " + userId);
        }

        long solvedCount = userKey.getSolvedCount();
        LocalDateTime lastSolvedAt = userKey.getLastSolvedDate();

        Long cumHigher = solvedBucketRepository.findCumHigher(solvedCount);
        if (cumHigher == null) {
            solvedBucketMaintenanceService.rebuildSolvedBuckets();
            cumHigher = solvedBucketRepository.findCumHigher(solvedCount);
            if (cumHigher == null) {
                throw new IllegalStateException("bucket missing for n=" + solvedCount);
            }
        }

        long tieBefore = rankRepository.countTieBefore(solvedCount, lastSolvedAt, userKey.getId());
        long myRank = cumHigher + tieBefore + 1;

        long pageStartRank = ((myRank - 1) / pageSize) * pageSize + 1;
        return new RankContext(myRank, pageStartRank);
    }

    private RankRepository.UserKeyProjection locateCursor(SolvedBucketRepository.BucketAtRankProjection bucket, long pageStartRank) {
        long bucketSolvedCount = bucket.getN();
        long bucketCumHigher = bucket.getCumHigherCount();

        int offsetInGroup = (int) (pageStartRank - (bucketCumHigher + 1));
        if (offsetInGroup < 0) {
            offsetInGroup = 0;
        }

        RankRepository.UserKeyProjection cursor = rankRepository.findKthInGroup(bucketSolvedCount, offsetInGroup);
        if (cursor == null) {
            cursor = rankRepository.findKthInGroup(bucketSolvedCount, 0);
            if (cursor == null) {
                throw new IllegalStateException("empty group for n=" + bucketSolvedCount);
            }
        }
        return cursor;
    }

    private record RankContext(long myRank, long pageStartRank) { }
}
