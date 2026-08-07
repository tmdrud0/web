package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.dto.ContestScoreboardRow;
import my.oj.web.contest.finalization.ContestFinalScore;
import my.oj.web.contest.finalization.ContestFinalScoreService;
import my.oj.web.contest.finalization.ContestFinalScoreStatus;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.dto.UserDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The scoreboard views that are not the hot one: a finished contest's final standings, and the
 * window around a given user.
 *
 * <p>This was the scoreboard tab's page assembler. It survives the tab because it is the only
 * thing that knows two questions {@code /api/contests/{id}/scoreboard} deliberately does not
 * answer - final scores live in MySQL rather than Redis, and finding a user's own neighbourhood
 * needs a rank lookup before a range read. Both are cold: a client asks for them when a contest
 * ends or when a user opens their own row, not hundreds of times a second, so both resolve user
 * names from MySQL where the hot path refuses to.
 */
@Component
@RequiredArgsConstructor
public class ContestScoreboardViewAssembler {

    private static final int PAGE_SIZE = 100;
    private static final int AROUND_WINDOW_SIZE = 11;

    private final ContestScoreboardService contestScoreboardService;
    private final ContestFinalScoreService contestFinalScoreService;
    private final UserRepository userRepository;

    public ContestScoreboardView assemble(Long contestId,
                                          boolean finalized,
                                          UserDto currentUser,
                                          boolean aroundMe,
                                          Long cursor) {
        return finalized
                ? buildFinalScoreboard(contestId, currentUser, aroundMe, cursor)
                : buildLiveScoreboard(contestId, currentUser, aroundMe, cursor);
    }

    private ContestScoreboardView buildLiveScoreboard(Long contestId,
                                                      UserDto currentUser,
                                                      boolean aroundMe,
                                                      Long cursor) {
        boolean viewingAround = false;
        ContestScoreboardSlice slice;
        long requestedStart = cursor != null && cursor > 0 ? cursor : 1L;
        if (aroundMe && currentUser != null) {
            Optional<ContestScoreboardSlice> around = contestScoreboardService.rankingAroundUser(
                    contestId,
                    currentUser.id(),
                    AROUND_WINDOW_SIZE
            );
            if (around.isPresent()) {
                slice = around.get();
                viewingAround = true;
            } else {
                slice = contestScoreboardService.slice(contestId, requestedStart, PAGE_SIZE);
            }
        } else {
            slice = contestScoreboardService.slice(contestId, requestedStart, PAGE_SIZE);
        }

        if (!viewingAround && slice.entries().isEmpty() && slice.totalParticipants() > 0
                && slice.startRank() > slice.totalParticipants()) {
            long total = slice.totalParticipants();
            long lastStart = ((total - 1) / PAGE_SIZE) * PAGE_SIZE + 1;
            slice = contestScoreboardService.slice(contestId, lastStart, PAGE_SIZE);
        }

        List<ContestScoreboardRow> rows = toRows(slice, resolveUserNames(userIds(slice.entries())));
        return view(rows, slice.startRank(), slice.totalParticipants(), viewingAround);
    }

    private ContestScoreboardView buildFinalScoreboard(Long contestId,
                                                       UserDto currentUser,
                                                       boolean aroundMe,
                                                       Long cursor) {
        List<ContestFinalScore> scores = contestFinalScoreService.getScores(contestId, ContestFinalScoreStatus.FINAL);
        if (scores.isEmpty()) {
            return ContestScoreboardView.empty(PAGE_SIZE);
        }

        boolean viewingAround = false;
        long total = scores.size();
        long startRank = cursor != null && cursor > 0 ? cursor : 1L;
        List<ContestFinalScore> window;
        if (aroundMe && currentUser != null) {
            int index = findUserIndex(scores, currentUser.id());
            if (index >= 0) {
                viewingAround = true;
                int effectiveWindow = Math.min(AROUND_WINDOW_SIZE, scores.size());
                int startIndex = Math.max(0, index - effectiveWindow / 2);
                if (startIndex + effectiveWindow > scores.size()) {
                    startIndex = scores.size() - effectiveWindow;
                }
                window = scores.subList(startIndex, startIndex + effectiveWindow);
                startRank = startIndex + 1L;
            } else {
                window = selectPage(scores, startRank, total);
                if (startRank > total) {
                    startRank = lastPageStart(total);
                    window = selectPage(scores, startRank, total);
                }
            }
        } else {
            window = selectPage(scores, startRank, total);
            if (startRank > total) {
                startRank = lastPageStart(total);
                window = selectPage(scores, startRank, total);
            }
        }

        List<ContestScoreboardEntry> entries = window.stream()
                .map(score -> new ContestScoreboardEntry(score.getUserId(), score.getSolvedCount(), score.getPenalty()))
                .toList();
        ContestScoreboardSlice slice = new ContestScoreboardSlice(contestId, startRank, entries, total);
        List<ContestScoreboardRow> rows = toRows(slice, resolveUserNames(userIds(entries)));
        return view(rows, startRank, total, viewingAround);
    }

    private ContestScoreboardView view(List<ContestScoreboardRow> rows,
                                       long startRank,
                                       long totalParticipants,
                                       boolean viewingAround) {
        Long previousCursor = null;
        Long nextCursor = null;
        if (!viewingAround && totalParticipants > 0) {
            if (startRank > 1) {
                previousCursor = Math.max(1L, startRank - PAGE_SIZE);
            }
            long candidateNext = startRank + PAGE_SIZE;
            if (candidateNext <= totalParticipants) {
                nextCursor = candidateNext;
            }
        }
        return new ContestScoreboardView(
                rows,
                startRank,
                totalParticipants,
                PAGE_SIZE,
                viewingAround,
                previousCursor,
                nextCursor,
                viewingAround ? null : startRank
        );
    }

    private long lastPageStart(long total) {
        return Math.max(1L, ((total - 1) / PAGE_SIZE) * PAGE_SIZE + 1);
    }

    private List<ContestFinalScore> selectPage(List<ContestFinalScore> scores, long startRank, long total) {
        long effectiveStart = Math.max(1, Math.min(startRank, total));
        int startIndex = (int) (effectiveStart - 1);
        int endIndex = Math.min(scores.size(), startIndex + PAGE_SIZE);
        return scores.subList(startIndex, endIndex);
    }

    private int findUserIndex(List<ContestFinalScore> scores, Long userId) {
        if (userId == null) {
            return -1;
        }
        for (int i = 0; i < scores.size(); i++) {
            if (userId.equals(scores.get(i).getUserId())) {
                return i;
            }
        }
        return -1;
    }

    private Set<Long> userIds(List<ContestScoreboardEntry> entries) {
        return entries.stream()
                .map(ContestScoreboardEntry::userId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds)
                .stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private List<ContestScoreboardRow> toRows(ContestScoreboardSlice slice, Map<Long, String> userNames) {
        if (slice.entries().isEmpty()) {
            return List.of();
        }

        long[] ranks = ContestScoreboardRanking.competitionRanks(slice);
        List<ContestScoreboardRow> rows = new ArrayList<>(slice.entries().size());
        for (int i = 0; i < slice.entries().size(); i++) {
            ContestScoreboardEntry entry = slice.entries().get(i);
            rows.add(new ContestScoreboardRow(
                    Math.toIntExact(ranks[i]),
                    entry.userId(),
                    userNames.getOrDefault(entry.userId(), "User #" + entry.userId()),
                    entry.solvedCount(),
                    entry.penalty()
            ));
        }
        return rows;
    }
}
