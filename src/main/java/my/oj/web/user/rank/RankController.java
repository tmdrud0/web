package my.oj.web.user.rank;

import lombok.RequiredArgsConstructor;
import my.oj.web.auth.CurrentUser;
import my.oj.web.user.dto.UserDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.streak.longest.LongestStreakRankService;
import my.oj.web.user.rank.solved.SolvedRankService;
import my.oj.web.user.rank.streak.StreakRankService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class RankController {

    private final SolvedRankService solvedRankService;
    private final StreakRankService streakRankService;
    private final LongestStreakRankService longestStreakRankService;

    @GetMapping("/rank")
    public String listRank(@CurrentUser UserDto currentUser,
                           Model model,
                           @RequestParam(required = false) Long cursor,
                           @RequestParam(defaultValue = "30") int size,
                           @RequestParam(defaultValue = "solvedCount") String sortBy,
                           @RequestParam(defaultValue = "false") boolean aroundMe) {
        int pageSize = Math.max(size, 1);
        Long userId = currentUser.id();

        if (isStreakSort(sortBy)) {
            RankPageDto page = aroundMe
                    ? streakRankService.getPageAroundUser(userId, pageSize)
                    : streakRankService.getPage(cursor, pageSize);
            populateModel(model, sortBy, aroundMe, pageSize, cursor, page, "streakPageResult");
            return "rank";
        }

        if (isLongestSort(sortBy)) {
            RankPageDto page = aroundMe
                    ? longestStreakRankService.getPageAroundUser(userId, pageSize)
                    : longestStreakRankService.getPage(cursor, pageSize);
            populateModel(model, sortBy, aroundMe, pageSize, cursor, page, "longestPageResult");
            return "rank";
        }

        RankPageDto page = aroundMe
                ? solvedRankService.getSolvedCountPageForUser(userId, pageSize)
                : solvedRankService.getSolvedCountPage(cursor, pageSize);

        populateModel(model, "solvedCount", aroundMe, pageSize, cursor, page, "pageResult");
        return "rank";
    }

    private void populateModel(Model model,
                               String sortBy,
                               boolean aroundMe,
                               int pageSize,
                               Long cursor,
                               RankPageDto page,
                               String attributeName) {
        model.addAttribute(attributeName, page);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("aroundMe", aroundMe);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("cursor", cursor);
    }

    private boolean isStreakSort(String sortBy) {
        return "streak".equalsIgnoreCase(sortBy);
    }

    private boolean isLongestSort(String sortBy) {
        return "longest".equalsIgnoreCase(sortBy);
    }
}
