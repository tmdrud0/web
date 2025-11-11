package my.oj.web.perf;

import lombok.RequiredArgsConstructor;
import my.oj.web.perf.dto.AroundBenchResult;
import my.oj.web.perf.dto.BucketRebuildResult;
import my.oj.web.perf.dto.LongestBenchResult;
import my.oj.web.perf.dto.SolvedBenchResult;
import my.oj.web.perf.dto.StreakBenchResult;
import my.oj.web.user.rank.dto.RankItemDto;
import my.oj.web.user.rank.streak.StreakRankBatchService;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/perf")
@Profile("perf")
@RequiredArgsConstructor
public class RankPerfController {

    private final RankPerfService perfService;
    private final StreakRankBatchService streakRankBatchService;

    @PostMapping("/buckets/rebuild")
    public BucketRebuildResult rebuildBuckets() {
        return perfService.rebuildSolvedBuckets();
    }

        @PostMapping("/streak/rebuild")
    public Map<String, Object> rebuildStreakSnapshot(@RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate targetDay) {
        if (targetDay != null) {
            streakRankBatchService.rebuildFor(targetDay);
            return Map.of("status", "ok", "targetDay", targetDay.toString());
        }
        streakRankBatchService.rebuildForYesterday();
        return Map.of("status", "ok", "targetDay", LocalDate.now().minusDays(1).toString());
    }

    @GetMapping("/bench/streak")
    public StreakBenchResult benchStreak(@RequestParam(defaultValue = "100000") int page,
                                         @RequestParam(defaultValue = "100") int size) {
        return perfService.benchmarkStreak(page, size);
    }

    @GetMapping("/bench/solved")
    public SolvedBenchResult benchSolved(@RequestParam(defaultValue = "100000") int page,
                                         @RequestParam(defaultValue = "100") int size) {
        return perfService.benchmarkSolved(page, size);
    }

    @GetMapping("/bench/longest")
    public LongestBenchResult benchLongest(@RequestParam(defaultValue = "100000") int page,
                                           @RequestParam(defaultValue = "100") int size) {
        return perfService.benchmarkLongest(page, size);
    }

    @GetMapping("/bench/solved/around")
    public AroundBenchResult benchSolvedAround(@RequestParam long rank,
                                               @RequestParam(defaultValue = "100") int size) {
        return perfService.benchmarkSolvedAroundRank(rank, size);
    }

    @GetMapping("/bench/streak/around")
    public AroundBenchResult benchStreakAround(@RequestParam long rank,
                                               @RequestParam(defaultValue = "100") int size) {
        return perfService.benchmarkStreakAroundRank(rank, size);
    }

    @GetMapping("/bench/longest/around")
    public AroundBenchResult benchLongestAround(@RequestParam long rank,
                                                @RequestParam(defaultValue = "100") int size) {
        return perfService.benchmarkLongestAroundRank(rank, size);
    }

    @GetMapping("/rank/solved/nth")
    public RankItemDto solvedNth(@RequestParam long rank) {
        return perfService.getSolvedRank(rank);
    }

    @GetMapping("/rank/streak/nth")
    public RankItemDto streakNth(@RequestParam long rank) {
        return perfService.getStreakRank(rank);
    }

    @GetMapping("/rank/longest/nth")
    public RankItemDto longestNth(@RequestParam long rank) {
        return perfService.getLongestRank(rank);
    }
}
