package my.oj.web.contest.scoreboard.api;

import my.oj.web.auth.CurrentUser;
import my.oj.web.contest.ContestService;
import my.oj.web.contest.scoreboard.ContestScoreboardView;
import my.oj.web.contest.scoreboard.ContestScoreboardViewAssembler;
import my.oj.web.user.dto.UserDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two scoreboard reads that are not the hot one.
 *
 * <p>Separate from {@link ContestScoreboardApiController} on purpose. That endpoint reads Redis
 * and nothing else because it is asked hundreds of times a second; these two are asked when a
 * contest ends and when a user looks for their own row, so they can afford what it cannot - final
 * standings come from MySQL, and both resolve user names.
 *
 * <p>Keeping them here rather than adding modes to the hot endpoint means a change to either
 * cannot slow the read that the capacity of this system is measured by.
 */
@RestController
class ContestScoreboardViewApiController {

    private final ContestScoreboardViewAssembler assembler;
    private final ContestService contestService;

    ContestScoreboardViewApiController(ContestScoreboardViewAssembler assembler, ContestService contestService) {
        this.assembler = assembler;
        this.contestService = contestService;
    }

    /**
     * Final standings for a finished contest, read from {@code contest_final_score}. Answers an
     * empty view for a contest that has not been finalised, which is what "there are no final
     * standings yet" looks like.
     */
    @GetMapping("/api/contests/{contestId}/scoreboard/final")
    ContestScoreboardView finalScoreboard(@PathVariable long contestId,
                                          @RequestParam(required = false) Long cursor) {
        return assembler.assemble(contestId, true, null, false, cursor);
    }

    /**
     * The window of ranks around the signed-in user. Reads the contest first because a finished
     * contest's answer comes from a different store than a running one's - one query, on a request
     * a user makes about themselves rather than one a crowd makes about the leaders.
     */
    @GetMapping("/api/contests/{contestId}/scoreboard/around-me")
    ContestScoreboardView aroundMe(@PathVariable long contestId, @CurrentUser UserDto currentUser) {
        boolean finalized = contestService.getDetail(contestId).finalized();
        return assembler.assemble(contestId, finalized, currentUser, true, null);
    }
}
