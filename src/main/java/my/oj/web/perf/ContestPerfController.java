package my.oj.web.perf;

import lombok.RequiredArgsConstructor;
import my.oj.web.perf.dto.ContestSeedRequest;
import my.oj.web.perf.dto.ContestSeedResult;
import my.oj.web.perf.dto.ContestScoreboardPerfResult;
import my.oj.web.perf.dto.ContestSubmissionBulkStatsResult;
import my.oj.web.perf.dto.ContestSubmissionPerfResult;
import my.oj.web.perf.dto.ContestSubmissionRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletionStage;

@RestController
@RequestMapping("/perf")
@Profile("perf")
@RequiredArgsConstructor
public class ContestPerfController {

    private final ContestPerfService contestPerfService;

    @PostMapping("/contest/seed")
    public ContestSeedResult seed(@RequestBody(required = false) ContestSeedRequest request) {
        ContestSeedRequest effective = request == null ? new ContestSeedRequest(null, null, null, null, null, null) : request;
        return contestPerfService.seedContest(effective);
    }

    @PostMapping("/contest/submit")
    public CompletionStage<ContestSubmissionPerfResult> submit(@RequestBody ContestSubmissionRequest request) {
        return contestPerfService.submitContestSolutionAsync(request);
    }

    @PostMapping("/submission/seed")
    public ContestSeedResult seedPractice(@RequestBody(required = false) ContestSeedRequest request) {
        ContestSeedRequest effective = request == null ? new ContestSeedRequest(null, null, null, null, null, null) : request;
        return contestPerfService.seedPractice(effective);
    }

    @PostMapping("/submission/submit")
    public ContestSubmissionPerfResult submitPractice(@RequestBody ContestSubmissionRequest request) {
        return contestPerfService.submitContestSolution(request);
    }

    @GetMapping("/contest/scoreboard")
    public ContestScoreboardPerfResult readScoreboard(@RequestParam long contestId,
                                                      @RequestParam(defaultValue = "1") long startRank,
                                                      @RequestParam(defaultValue = "100") int size) {
        return contestPerfService.readScoreboard(contestId, startRank, size);
    }

    @GetMapping("/contest/submission-bulk-stats")
    public ContestSubmissionBulkStatsResult getBulkStats() {
        return contestPerfService.getBulkStats();
    }

    @PostMapping("/contest/submission-bulk-stats/reset")
    public ContestSubmissionBulkStatsResult resetBulkStats() {
        return contestPerfService.resetBulkStats();
    }
}
