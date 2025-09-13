package my.oj.web.user.rank.streaksnapshot;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.rank.RankPageAssembler;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.dto.RankPageDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StreakRankService {
    private final StreakSnapshotRepository repo;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RankPageDto getPage(int page, int size) {
        LocalDate today = LocalDate.now();
        int pageStartRank = page * size + 1;
        int toRank = pageStartRank + size - 1;
        List<StreakSnapshotRepository.PageRowProjection> rows =
                repo.fetchPageByRankRange(today, pageStartRank, toRank);

        List<RankItemDto> items = RankPageAssembler.toRankItems(
                pageStartRank,
                rows,
                StreakSnapshotRepository.PageRowProjection::getUserId,
                StreakSnapshotRepository.PageRowProjection::getName,
                row -> row.getCurrentStreak(),
                StreakSnapshotRepository.PageRowProjection::getLastSolvedDate
        );
        // myRank unknown in generic page request; set to -1
        return new RankPageDto(-1, pageStartRank, size, items);
    }

    @Transactional(readOnly = true)
    public RankPageDto getPageAroundUser(long userId, int size) {
        LocalDate today = LocalDate.now();
        var pos = repo.findUserPosition(today, userId);
        if (pos == null) {
            return fallbackAroundMe(today, size, userId);
        }

        int pageStart = computePageStart(pos.getRank(), size);
        int toRank = pageStart + size - 1;
        List<StreakSnapshotRepository.PageRowProjection> rows =
                repo.fetchPageByRankRange(today, pageStart, toRank);

        List<RankItemDto> items = RankPageAssembler.toRankItems(
                pageStart,
                rows,
                StreakSnapshotRepository.PageRowProjection::getUserId,
                StreakSnapshotRepository.PageRowProjection::getName,
                row -> row.getCurrentStreak(),
                StreakSnapshotRepository.PageRowProjection::getLastSolvedDate
        );
        return new RankPageDto(pos.getRank(), pageStart, size, items);
    }

    @Transactional(readOnly = true)
    public long totalToday() {
        return repo.countBySnapshotDate(LocalDate.now());
    }

    private int computePageStart(int rank, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("page size must be positive");
        }
        return ((Math.max(rank, 1) - 1) / size) * size + 1;
    }

    private RankPageDto fallbackAroundMe(LocalDate today, int size, long userId) {
        int requestedSize = Math.max(size, 1);
        int limit = Math.max(requestedSize - 1, 0);
        List<RankItemDto> items;
        if (limit > 0) {
            List<StreakSnapshotRepository.PageRowProjection> rows =
                    repo.fetchPageByRankRange(today, 1, limit);
            items = RankPageAssembler.toRankItems(
                    1,
                    rows,
                    StreakSnapshotRepository.PageRowProjection::getUserId,
                    StreakSnapshotRepository.PageRowProjection::getName,
                    row -> row.getCurrentStreak(),
                    StreakSnapshotRepository.PageRowProjection::getLastSolvedDate
            );
        } else {
            items = new java.util.ArrayList<>();
        }
        items.add(createFallbackItem(userId));
        return new RankPageDto(-1, 1, requestedSize, items);
    }

    private RankItemDto createFallbackItem(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        var streak = user.getStreak();
        return new RankItemDto(
                -1,
                user.getId(),
                user.getName(),
                streak.getCurrentStreak(),
                streak.getLastSolvedDate()
        );
    }
}


