package my.oj.web.contest;

import lombok.RequiredArgsConstructor;
import my.oj.web.auth.CurrentUser;
import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestScoreboardRow;
import my.oj.web.contest.dto.ContestSummaryView;
import my.oj.web.contest.finalization.ContestFinalScore;
import my.oj.web.contest.finalization.ContestFinalScoreService;
import my.oj.web.contest.finalization.ContestFinalScoreStatus;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.dto.UserDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ContestController {

    private static final int SCOREBOARD_PAGE_SIZE = 100;
    private static final int AROUND_WINDOW_SIZE = 11;

    private final ContestService contestService;
    private final ContestRepository contestRepository;
    private final ContestScoreboardService contestScoreboardService;
    private final ContestFinalScoreService contestFinalScoreService;
    private final UserRepository userRepository;

    @GetMapping("/contests/{id}")
    public String contestDetail(@PathVariable Long id,
                                @RequestParam(value = "tab", defaultValue = "problems") String activeTab,
                                @RequestParam(value = "cursor", required = false) Long cursor,
                                @RequestParam(value = "aroundMe", defaultValue = "false") boolean aroundMe,
                                @CurrentUser(required = false) UserDto currentUser,
                                Model model) {
        ContestDetailDto contest = contestService.findDetailById(id)
                .orElseThrow(() -> new IllegalArgumentException("Contest not found"));

        boolean finalized = contestRepository.findById(id)
                .map(Contest::isFinalized)
                .orElse(false);

        LocalDateTime now = LocalDateTime.now();
        ContestStatus status = ContestStatus.from(contest.startTime(), contest.endTime(), now);
        String timeMessage = buildTimeMessage(status, contest.startTime(), contest.endTime(), now);

        ScoreboardPageView pageView = buildScoreboardPage(contest.id(), finalized, currentUser, aroundMe, cursor);

        model.addAttribute("contest", contest);
        model.addAttribute("status", status);
        model.addAttribute("statusLabel", status.getLabel());
        model.addAttribute("timeMessage", timeMessage);
        model.addAttribute("activeTab", activeTab);
        model.addAttribute("scoreboardRows", pageView.rows());
        model.addAttribute("hasScoreboard", !pageView.rows().isEmpty());
        model.addAttribute("scoreboardTotalParticipants", pageView.totalParticipants());
        model.addAttribute("scoreboardStartRank", pageView.startRank());
        model.addAttribute("scoreboardPageSize", pageView.pageSize());
        model.addAttribute("scoreboardPrevCursor", pageView.previousCursor().orElse(null));
        model.addAttribute("scoreboardNextCursor", pageView.nextCursor().orElse(null));
        model.addAttribute("scoreboardAroundMe", pageView.aroundMe());
        model.addAttribute("scoreboardCursor", pageView.cursor().orElse(null));
        model.addAttribute("scoreboardFinalized", finalized);
        model.addAttribute("currentUserId", currentUser != null ? currentUser.id() : null);

        return "contest";
    }

    @GetMapping("/contests")
    public String listContests(Model model, @PageableDefault(size = 10) Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        Page<ContestSummaryView> contestPage = contestRepository.findAll(pageable)
                .map(contest -> toSummaryView(contest, now));

        model.addAttribute("contestPage", contestPage);
        return "contests";
    }

    private ContestSummaryView toSummaryView(Contest contest, LocalDateTime now) {
        ContestStatus status = ContestStatus.from(contest.getStartTime(), contest.getEndTime(), now);
        String timeMessage = buildTimeMessage(status, contest.getStartTime(), contest.getEndTime(), now);
        return new ContestSummaryView(
                contest.getId(),
                contest.getName(),
                contest.getStartTime(),
                contest.getEndTime(),
                status,
                status.getLabel(),
                timeMessage
        );
    }

    private String buildTimeMessage(ContestStatus status, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return switch (status) {
            case UPCOMING -> {
                if (start == null) {
                    yield "Start time not set";
                }
                Duration untilStart = Duration.between(now, start);
                yield "Starts in " + formatDuration(untilStart);
            }
            case RUNNING -> {
                if (end == null) {
                    yield "In progress";
                }
                Duration untilEnd = Duration.between(now, end);
                yield "Time left " + formatDuration(untilEnd);
            }
            case ENDED -> end == null
                    ? "Finished"
                    : "Finished (" + end.toString().replace("T", " ") + ")";
        };
    }

    private String formatDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "00:00:00";
        }
        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        builder.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        return builder.toString();
    }

    private ScoreboardPageView buildScoreboardPage(Long contestId,
                                                   boolean finalized,
                                                   UserDto currentUser,
                                                   boolean aroundMe,
                                                   Long cursor) {
        return finalized
                ? buildFinalScoreboardPage(contestId, currentUser, aroundMe, cursor)
                : buildLiveScoreboardPage(contestId, currentUser, aroundMe, cursor);
    }

    private ScoreboardPageView buildLiveScoreboardPage(Long contestId,
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
                slice = contestScoreboardService.slice(contestId, requestedStart, SCOREBOARD_PAGE_SIZE);
            }
        } else {
            slice = contestScoreboardService.slice(contestId, requestedStart, SCOREBOARD_PAGE_SIZE);
        }

        if (!viewingAround && slice.entries().isEmpty() && slice.totalParticipants() > 0
                && slice.startRank() > slice.totalParticipants()) {
            long total = slice.totalParticipants();
            long lastStart = ((total - 1) / SCOREBOARD_PAGE_SIZE) * SCOREBOARD_PAGE_SIZE + 1;
            slice = contestScoreboardService.slice(contestId, lastStart, SCOREBOARD_PAGE_SIZE);
        }

        Set<Long> userIds = slice.entries().stream()
                .map(ContestScoreboardEntry::userId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, String> userNames = resolveUserNames(userIds);

        List<ContestScoreboardRow> rows = toRows(slice, userNames);
        Long previousCursor = null;
        Long nextCursor = null;
        if (!viewingAround && slice.totalParticipants() > 0) {
            if (slice.startRank() > 1) {
                previousCursor = Math.max(1L, slice.startRank() - SCOREBOARD_PAGE_SIZE);
            }
            long candidateNext = slice.startRank() + SCOREBOARD_PAGE_SIZE;
            if (candidateNext <= slice.totalParticipants()) {
                nextCursor = candidateNext;
            }
        }
        Long effectiveCursor = viewingAround ? null : slice.startRank();
        return new ScoreboardPageView(
                rows,
                slice.startRank(),
                slice.totalParticipants(),
                SCOREBOARD_PAGE_SIZE,
                viewingAround,
                Optional.ofNullable(previousCursor),
                Optional.ofNullable(nextCursor),
                Optional.ofNullable(effectiveCursor)
        );
    }

    private ScoreboardPageView buildFinalScoreboardPage(Long contestId,
                                                        UserDto currentUser,
                                                        boolean aroundMe,
                                                        Long cursor) {
        List<ContestFinalScore> scores = contestFinalScoreService.getScores(contestId, ContestFinalScoreStatus.FINAL);
        if (scores.isEmpty()) {
            return ScoreboardPageView.empty();
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
                    startRank = Math.max(1L, ((total - 1) / SCOREBOARD_PAGE_SIZE) * SCOREBOARD_PAGE_SIZE + 1);
                    window = selectPage(scores, startRank, total);
                }
            }
        } else {
            window = selectPage(scores, startRank, total);
            if (startRank > total) {
                startRank = Math.max(1L, ((total - 1) / SCOREBOARD_PAGE_SIZE) * SCOREBOARD_PAGE_SIZE + 1);
                window = selectPage(scores, startRank, total);
            }
        }

        List<ContestScoreboardEntry> entries = window.stream()
                .map(score -> new ContestScoreboardEntry(score.getUserId(), score.getSolvedCount(), score.getPenalty()))
                .toList();
        ContestScoreboardSlice slice = new ContestScoreboardSlice(contestId, startRank, entries, total);

        Set<Long> userIds = entries.stream()
                .map(ContestScoreboardEntry::userId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, String> userNames = resolveUserNames(userIds);

        List<ContestScoreboardRow> rows = toRows(slice, userNames);
        Long previousCursor = null;
        Long nextCursor = null;
        if (!viewingAround && total > 0) {
            if (startRank > 1) {
                previousCursor = Math.max(1L, startRank - SCOREBOARD_PAGE_SIZE);
            }
            long candidateNext = startRank + SCOREBOARD_PAGE_SIZE;
            if (candidateNext <= total) {
                nextCursor = candidateNext;
            }
        }
        Long effectiveCursor = viewingAround ? null : startRank;
        return new ScoreboardPageView(
                rows,
                startRank,
                total,
                SCOREBOARD_PAGE_SIZE,
                viewingAround,
                Optional.ofNullable(previousCursor),
                Optional.ofNullable(nextCursor),
                Optional.ofNullable(effectiveCursor)
        );
    }

    private List<ContestFinalScore> selectPage(List<ContestFinalScore> scores, long startRank, long total) {
        if (total == 0) {
            return List.of();
        }
        long effectiveStart = Math.max(1, Math.min(startRank, total));
        int startIndex = (int) (effectiveStart - 1);
        int endIndex = Math.min(scores.size(), startIndex + SCOREBOARD_PAGE_SIZE);
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

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds)
                .stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private List<ContestScoreboardRow> toRows(ContestScoreboardSlice slice, Map<Long, String> userNames) {
        List<ContestScoreboardEntry> entries = slice.entries();
        if (entries.isEmpty()) {
            return List.of();
        }

        List<ContestScoreboardRow> rows = new ArrayList<>(entries.size());
        long startRank = slice.startRank();
        long displayRank = startRank - 1;
        int previousSolved = Integer.MIN_VALUE;
        long previousPenalty = Long.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            ContestScoreboardEntry entry = entries.get(i);
            long absoluteRank = startRank + i;
            if (entry.solvedCount() != previousSolved || entry.penalty() != previousPenalty) {
                displayRank = absoluteRank;
                previousSolved = entry.solvedCount();
                previousPenalty = entry.penalty();
            }
            String userName = userNames.getOrDefault(entry.userId(), "User #" + entry.userId());
            rows.add(new ContestScoreboardRow(
                    Math.toIntExact(displayRank),
                    entry.userId(),
                    userName,
                    entry.solvedCount(),
                    entry.penalty()
            ));
        }
        return rows;
    }

    private record ScoreboardPageView(List<ContestScoreboardRow> rows,
                                      long startRank,
                                      long totalParticipants,
                                      int pageSize,
                                      boolean aroundMe,
                                      Optional<Long> previousCursor,
                                      Optional<Long> nextCursor,
                                      Optional<Long> cursor) {
        static ScoreboardPageView empty() {
            return new ScoreboardPageView(
                    List.of(),
                    1,
                    0,
                    ContestController.SCOREBOARD_PAGE_SIZE,
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
            );
        }
    }
}
