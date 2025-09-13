package my.oj.web.user.rank;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.User;
import my.oj.web.user.dto.UserDto;
import my.oj.web.auth.CurrentUser;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.streaksnapshot.StreakMaintenanceService;
import my.oj.web.user.rank.streaksnapshot.StreakRankService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class RankController {

    private final RankRepository rankRepository;
    private final RankService rankService;
    private final StreakRankService streakRankService;
    private final StreakMaintenanceService streakMaintenanceService;

    @GetMapping("/rank")
    public String listRank(
            @CurrentUser UserDto currentUser,
            Model model,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "solvedCount") String sortBy,
            @RequestParam(defaultValue = "false") boolean aroundMe) {

        Long userId = currentUser.id();

        if (isStreakSort(sortBy)) {
            return aroundMe
                    ? renderStreakAroundMe(model, size, userId)
                    : renderStreakPage(model, page, size);
        }

        return aroundMe
                ? renderSolvedAroundMe(model, size, userId)
                : renderSolvedPage(model, page, size);
    }

    private boolean isStreakSort(String sortBy) {
        return "streak".equalsIgnoreCase(sortBy);
    }

    private String renderStreakAroundMe(Model model, int size, Long userId) {
        streakMaintenanceService.ensureFreshnessForUser(userId);
        var pageResult = streakRankService.getPageAroundUser(userId, size);
        model.addAttribute("streakPageResult", pageResult);
        model.addAttribute("sortBy", "streak");
        model.addAttribute("aroundMe", true);
        return "rank";
    }

    private String renderStreakPage(Model model, int page, int size) {
        var pageResult = streakRankService.getPage(page, size);
        long total = streakRankService.totalToday();
        model.addAttribute("streakTotal", total);
        model.addAttribute("streakPageResult", pageResult);
        model.addAttribute("sortBy", "streak");
        model.addAttribute("aroundMe", false);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        return "rank";
    }

    private String renderSolvedAroundMe(Model model, int size, Long userId) {
        RankPageDto pageResult = rankService.getSolvedCountPageForUser(userId, size);
        model.addAttribute("pageResult", pageResult);
        model.addAttribute("sortBy", "solvedCount");
        model.addAttribute("aroundMe", true);
        return "rank";
    }

    private String renderSolvedPage(Model model, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> rankingPage = rankRepository.findRankingsBySolvedCount(pageable);
        model.addAttribute("rankingPage", rankingPage);
        model.addAttribute("sortBy", "solvedCount");
        model.addAttribute("aroundMe", false);
        return "rank";
    }
}


