package my.oj.web.user.rank.api;

import my.oj.web.auth.CurrentUser;
import my.oj.web.user.dto.UserDto;
import my.oj.web.user.rank.dto.RankPageDto;
import my.oj.web.user.rank.solved.SolvedRankService;
import my.oj.web.user.rank.streak.StreakRankService;
import my.oj.web.user.rank.streak.longest.LongestStreakRankService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The leaderboard, on whichever of the three orderings the caller asks for.
 *
 * <p>One resource with a {@code sortBy} rather than three, because the page shape and the cursor
 * mean the same thing in all three and only the backing service differs. The page put each result
 * under a differently named model attribute so a template could pick one; a caller reads
 * {@code page} and {@code sortBy} tells it which ordering produced it.
 */
@RestController
class RankApiController {

    private final SolvedRankService solvedRankService;
    private final StreakRankService streakRankService;
    private final LongestStreakRankService longestStreakRankService;

    RankApiController(SolvedRankService solvedRankService,
                      StreakRankService streakRankService,
                      LongestStreakRankService longestStreakRankService) {
        this.solvedRankService = solvedRankService;
        this.streakRankService = streakRankService;
        this.longestStreakRankService = longestStreakRankService;
    }

    @GetMapping("/api/rank")
    RankResponse rank(@CurrentUser UserDto currentUser,
                      @RequestParam(required = false) Long cursor,
                      @RequestParam(defaultValue = "30") int size,
                      @RequestParam(defaultValue = "solvedCount") String sortBy,
                      @RequestParam(defaultValue = "false") boolean aroundMe) {
        int pageSize = Math.max(size, 1);
        Long userId = currentUser.id();

        if ("streak".equalsIgnoreCase(sortBy)) {
            RankPageDto page = aroundMe
                    ? streakRankService.getPageAroundUser(userId, pageSize)
                    : streakRankService.getPage(cursor, pageSize);
            return new RankResponse("streak", aroundMe, pageSize, cursor, page);
        }

        if ("longest".equalsIgnoreCase(sortBy)) {
            RankPageDto page = aroundMe
                    ? longestStreakRankService.getPageAroundUser(userId, pageSize)
                    : longestStreakRankService.getPage(cursor, pageSize);
            return new RankResponse("longest", aroundMe, pageSize, cursor, page);
        }

        RankPageDto page = aroundMe
                ? solvedRankService.getSolvedCountPageForUser(userId, pageSize)
                : solvedRankService.getSolvedCountPage(cursor, pageSize);
        return new RankResponse("solvedCount", aroundMe, pageSize, cursor, page);
    }

    record RankResponse(String sortBy, boolean aroundMe, int pageSize, Long cursor, RankPageDto page) {
    }
}
