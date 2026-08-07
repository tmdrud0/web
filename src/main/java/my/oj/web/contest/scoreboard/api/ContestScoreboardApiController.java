package my.oj.web.contest.scoreboard.api;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scoreboard as its own resource.
 *
 * <p>Reading it through {@code GET /contests/{id}?tab=scoreboard} costs three MySQL round trips
 * before Redis is touched: a contest projection, the problem list, and the contest entity again
 * for one boolean. None of them describe the scoreboard. They are there because the scoreboard was
 * a tab on a page that also had to render a header and a problem list, so the handler loaded
 * everything any tab might want. Measured at 300 reads per second that is 900 round trips a
 * second, 300 of them a duplicate of another 300.
 *
 * <p>Separating the resource removes them rather than optimising them: this reads Redis and
 * nothing else. There is deliberately no existence check on the contest - that would put back a
 * query to learn what an empty slice already says, and an unknown contest is not an error worth a
 * round trip on the hot read path.
 */
@RestController
class ContestScoreboardApiController {

    /**
     * Wide enough for the hundred-row page the load model uses, with room to ask for more, but
     * bounded: size is caller-supplied and each row is a Redis hash read, so an unbounded value
     * would let one request issue tens of thousands of them.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final ContestScoreboardService contestScoreboardService;

    ContestScoreboardApiController(ContestScoreboardService contestScoreboardService) {
        this.contestScoreboardService = contestScoreboardService;
    }

    @GetMapping("/api/contests/{contestId}/scoreboard")
    ContestScoreboardSliceResponse scoreboard(@PathVariable long contestId,
                                              @RequestParam(defaultValue = "1") long startRank,
                                              @RequestParam(defaultValue = "100") int size) {
        long effectiveStartRank = Math.max(1L, startRank);
        int effectiveSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return ContestScoreboardSliceResponse.from(
                contestScoreboardService.slice(contestId, effectiveStartRank, effectiveSize)
        );
    }
}
