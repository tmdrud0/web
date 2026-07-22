package my.oj.web.user.rank.streak.longest;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.rank.RankPageAssembler;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LongestStreakRankService {

    private final LongestStreakSnapshotRepository snapshotRepository;
    private final LongestStreakSnapshotService snapshotService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RankPageDto getPage(Long cursor, int size) {
        int pageSize = Math.max(size, 1);
        long total = snapshotRepository.count();
        if (total == 0) {
            snapshotService.rebuildSnapshot();
            total = snapshotRepository.count();
            if (total == 0) {
                return new RankPageDto(-1, 1, pageSize, 0, null, null, List.of());
            }
        }

        long desiredStart = cursor == null ? 1L : cursor;
        long startRank = Math.max(1, Math.min(desiredStart, ((total - 1) / pageSize) * pageSize + 1));

        List<LongestStreakSnapshot> rows = snapshotRepository.findPage(startRank, pageSize);
        List<RankItemDto> items = RankPageAssembler.toRankItems(
                startRank,
                rows,
                LongestStreakSnapshot::getUserId,
                this::findUsername,
                LongestStreakSnapshot::getLongestStreak,
                LongestStreakSnapshot::getLastSolvedTime
        );

        Long prev = startRank > 1 ? Math.max(1, startRank - pageSize) : null;
        Long next = startRank + pageSize <= total ? startRank + pageSize : null;

        return new RankPageDto(-1, startRank, pageSize, total, prev, next, items);
    }

    @Transactional(readOnly = true)
    public RankPageDto getPageAroundUser(long userId, int size) {
        int pageSize = Math.max(size, 1);
        LongestStreakSnapshot snapshot = snapshotRepository.findByUserId(userId);
        if (snapshot == null) {
            return fallbackForZeroLongest(userId, pageSize);
        }

        long myRank = snapshot.getRank();
        long pageStartRank = ((myRank - 1) / pageSize) * pageSize + 1;
        return buildPageFromRank(pageStartRank, pageSize, myRank);
    }

    @Transactional(readOnly = true)
    public RankItemDto getUserAtRank(long rank) {
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }

        LongestStreakSnapshot snapshot = snapshotRepository.findByRank(rank);
        if (snapshot == null) {
            snapshotService.rebuildSnapshot();
            snapshot = snapshotRepository.findByRank(rank);
            if (snapshot == null) {
                throw new IllegalArgumentException("rank out of range: " + rank);
            }
        }

        User user = userRepository.findById(snapshot.getUserId())
                .orElseThrow(() -> new IllegalStateException("user missing for snapshot"));

        return new RankItemDto(
                snapshot.getRank(),
                user.getId(),
                user.getName(),
                snapshot.getLongestStreak(),
                snapshot.getLastSolvedTime()
        );
    }

    public void rebuildSnapshot() {
        snapshotService.rebuildSnapshot();
    }

    private RankPageDto buildPageFromRank(long startRank, int pageSize, long myRank) {
        List<LongestStreakSnapshot> rows = snapshotRepository.findPage(startRank, pageSize);
        List<RankItemDto> items = RankPageAssembler.toRankItems(
                startRank,
                rows,
                LongestStreakSnapshot::getUserId,
                this::findUsername,
                LongestStreakSnapshot::getLongestStreak,
                LongestStreakSnapshot::getLastSolvedTime
        );

        long total = snapshotRepository.count();
        Long prev = startRank > 1 ? Math.max(1, startRank - pageSize) : null;
        Long next = startRank + pageSize <= total ? startRank + pageSize : null;

        return new RankPageDto(myRank, startRank, pageSize, total, prev, next, items);
    }

    private RankPageDto fallbackForZeroLongest(long userId, int pageSize) {
        int normalizedSize = Math.max(pageSize, 1);
        long totalPositive = snapshotRepository.count();
        if (totalPositive == 0) {
            snapshotService.rebuildSnapshot();
            totalPositive = snapshotRepository.count();
        }

        int fetchSize = (int) Math.max(0, Math.min(normalizedSize - 1, totalPositive));
        long startRank = fetchSize == 0 ? 1 : Math.max(1, totalPositive - fetchSize + 1);

        List<LongestStreakSnapshot> rows = new ArrayList<>(snapshotRepository.fetchTail(fetchSize));
        rows.sort((a, b) -> Long.compare(a.getRank(), b.getRank()));

        List<RankItemDto> items = RankPageAssembler.toRankItems(
                startRank,
                rows,
                LongestStreakSnapshot::getUserId,
                this::findUsername,
                LongestStreakSnapshot::getLongestStreak,
                LongestStreakSnapshot::getLastSolvedTime
        );

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        LocalDateTime lastSolved = user.getStreak() != null ? user.getStreak().getLastSolvedDate() : null;

        long zeroRank = totalPositive + 1;
        items.add(new RankItemDto(
                zeroRank,
                user.getId(),
                user.getName(),
                user.getStreak() != null ? user.getStreak().getLongestStreak() : 0,
                lastSolved
        ));

        Long previousCursor = (fetchSize > 0 && startRank > 1) ? Math.max(1, startRank - normalizedSize) : null;

        return new RankPageDto(
                zeroRank,
                startRank,
                normalizedSize,
                totalPositive,
                previousCursor,
                null,
                items
        );
    }

    private String findUsername(LongestStreakSnapshot snapshot) {
        return userRepository.findById(snapshot.getUserId()).map(User::getName).orElse("");
    }


}
