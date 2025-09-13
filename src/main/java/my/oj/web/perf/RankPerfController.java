package my.oj.web.perf;

import lombok.RequiredArgsConstructor;
import my.oj.web.perf.dto.BucketRebuildResult;
import my.oj.web.perf.dto.SeedRequest;
import my.oj.web.perf.dto.SeedResult;
import my.oj.web.perf.dto.SnapshotResult;
import my.oj.web.perf.dto.SolvedBenchResult;
import my.oj.web.perf.dto.StreakBenchResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/perf")
@Profile("perf")
@RequiredArgsConstructor
public class RankPerfController {

    private final RankPerfService perfService;

    @PostMapping("/seed")
    public SeedResult seed(@RequestBody(required = false) SeedRequest request,
                           @RequestParam(defaultValue = "200000") Integer total,
                           @RequestParam(defaultValue = "10") Integer batch) {
        SeedRequest effective = request != null ? request : new SeedRequest(total, batch);
        return perfService.seedUsers(effective);
    }

    @PostMapping("/snapshot")
    public SnapshotResult snapshot(@RequestParam(defaultValue = "10") int pageSize) {
        return perfService.rebuildSnapshot(pageSize);
    }

    @PostMapping("/buckets/rebuild")
    public BucketRebuildResult rebuildBuckets() {
        return perfService.rebuildSolvedBuckets();
    }

    @GetMapping("/bench/streak")
    public StreakBenchResult benchStreak(@RequestParam(defaultValue = "100000") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return perfService.benchmarkStreak(page, size);
    }

    @GetMapping("/bench/solved")
    public SolvedBenchResult benchSolved(@RequestParam(defaultValue = "100000") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return perfService.benchmarkSolved(page, size);
    }
}
