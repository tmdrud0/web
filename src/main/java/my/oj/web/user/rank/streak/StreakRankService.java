package my.oj.web.user.rank.streak;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.rank.RankPageAssembler;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StreakRankService {

    private final UserStreakRankSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RankPageDto getPage(Long cursor, int size) {
        int pageSize = Math.max(size, 1);
        long total = snapshotRepository.count();
        if (total == 0) {
            return new RankPageDto(-1, 1, pageSize, 0, null, null, List.of());
        }

        long desiredStart = cursor == null ? 1L : cursor;
        return buildPage(desiredStart, pageSize, -1, total);
    }

    @Transactional(readOnly = true)
    public RankPageDto getPageAroundUser(long userId, int size) {
        int pageSize = Math.max(size, 1);
        UserStreakRankSnapshotRepository.SnapshotRowProjection me = snapshotRepository.findByUserId(userId);
        if (me == null) {
            return fallbackForZeroStreak(userId, pageSize);
        }

        long total = snapshotRepository.count();
        long myRank = me.getRank();
        long pageStartRank = ((myRank - 1) / pageSize) * pageSize + 1;
        return buildPage(pageStartRank, pageSize, myRank, total);
    }

    @Transactional(readOnly = true)
    public RankItemDto getUserAtRank(long rank) {
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }

        UserStreakRankSnapshotRepository.SnapshotRowProjection row = snapshotRepository.findByRank(rank);
        if (row == null) {
            throw new IllegalArgumentException("rank out of range: " + rank);
        }

        return new RankItemDto(
                row.getRank(),
                row.getUserId(),
                row.getName(),
                row.getCurrentStreak(),
                row.getLastSolvedTime()
        );
    }

    private RankPageDto buildPage(long desiredStartRank, int pageSize, long myRank, long total) {
        long start = Math.max(1, desiredStartRank);
        if (start > total) {
            start = ((total - 1) / pageSize) * pageSize + 1;
        }

        long end = start + pageSize - 1;
        List<UserStreakRankSnapshotRepository.SnapshotRowProjection> rows = snapshotRepository.fetchPage(start, end);

        List<RankItemDto> items = RankPageAssembler.toRankItems(
                start,
                rows,
                UserStreakRankSnapshotRepository.SnapshotRowProjection::getUserId,
                UserStreakRankSnapshotRepository.SnapshotRowProjection::getName,
                row -> row.getCurrentStreak().longValue(),
                UserStreakRankSnapshotRepository.SnapshotRowProjection::getLastSolvedTime
        );

        Long prev = start > 1 ? Math.max(1, start - pageSize) : null;
        Long next = start + pageSize <= total ? start + pageSize : null;

        return new RankPageDto(myRank, start, pageSize, total, prev, next, items);
    }

    private RankPageDto fallbackForZeroStreak(long userId, int size) {
        int pageSize = Math.max(size, 1);
        long total = snapshotRepository.count();
        int fetchSize = Math.max(0, Math.min(pageSize - 1, (int) total));
        long fetchedStartRank = fetchSize == 0 ? 1 : Math.max(1, total - fetchSize + 1);

        List<RankItemDto> items = new ArrayList<>(pageSize);
        if (fetchSize > 0) {
            List<UserStreakRankSnapshotRepository.SnapshotRowProjection> rows = snapshotRepository.fetchTail(fetchSize);
            items.addAll(RankPageAssembler.toRankItems(
                    fetchedStartRank,
                    rows,
                    UserStreakRankSnapshotRepository.SnapshotRowProjection::getUserId,
                    UserStreakRankSnapshotRepository.SnapshotRowProjection::getName,
                    row -> row.getCurrentStreak().longValue(),
                    UserStreakRankSnapshotRepository.SnapshotRowProjection::getLastSolvedTime
            ));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

        long zeroRank = total + 1;
        items.add(new RankItemDto(
                zeroRank,
                user.getId(),
                user.getName(),
                user.getStreak().getCurrentStreak(),
                user.getStreak().getLastSolvedDate()
        ));

        long pageStartRank = fetchSize > 0 ? fetchedStartRank : zeroRank;
        Long previousCursor = (fetchSize > 0 && pageStartRank > 1)
                ? Math.max(1, pageStartRank - pageSize)
                : null;
        Long nextCursor = (fetchSize > 0 && pageStartRank + pageSize <= total)
                ? pageStartRank + pageSize
                : null;

        return new RankPageDto(
                zeroRank,
                pageStartRank,
                pageSize,
                total + 1,
                previousCursor,
                nextCursor,
                items
        );
    }
}
