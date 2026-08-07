package my.oj.web.perf;

import lombok.RequiredArgsConstructor;
import my.oj.web.perf.dto.ContestSeedRequest;
import my.oj.web.perf.dto.ContestSeedResult;
import my.oj.web.perf.dto.ContestSubmissionBulkStatsResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test infrastructure: build a contest to load, and read the writer's own counters.
 *
 * <p>What used to be here as well was a way to submit and a way to read the scoreboard, taking a
 * user id in the body instead of authenticating. Those were shortcuts around the product, and
 * every figure measured through them described a system nobody uses. The load generator drives
 * {@code /api/...} now, so the only thing left on this path is the setup the product has no
 * reason to expose and the metrics the product does not serve.
 */
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

    @PostMapping("/submission/seed")
    public ContestSeedResult seedPractice(@RequestBody(required = false) ContestSeedRequest request) {
        ContestSeedRequest effective = request == null ? new ContestSeedRequest(null, null, null, null, null, null) : request;
        return contestPerfService.seedPractice(effective);
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
