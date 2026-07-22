package my.oj.web.contest;

import lombok.RequiredArgsConstructor;
import my.oj.web.auth.CurrentUser;
import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestSummaryView;
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

@Controller
@RequiredArgsConstructor
public class ContestController {

    private final ContestService contestService;
    private final ContestRepository contestRepository;
    private final ContestScoreboardPageAssembler scoreboardPageAssembler;

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

        ContestScoreboardPageView pageView = scoreboardPageAssembler.assemble(
                contest.id(), finalized, currentUser, aroundMe, cursor
        );

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

}
