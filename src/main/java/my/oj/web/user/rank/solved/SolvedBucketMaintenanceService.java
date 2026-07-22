package my.oj.web.user.rank.solved;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.rank.solved.solvedbucket.SolvedBucketRepository;
import my.oj.web.user.rank.solved.solvedbucket.SolvedCountBucket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SolvedBucketMaintenanceService {

    private final SolvedRankRepository rankRepository;
    private final SolvedBucketRepository solvedBucketRepository;

    @Transactional
    public void rebuildSolvedBuckets() {
        List<SolvedRankRepository.SolvedDistProjection> dist = rankRepository.solvedCountDistribution();
        long cumHigher = 0L;
        List<SolvedCountBucket> buckets = new ArrayList<>();
        for (SolvedRankRepository.SolvedDistProjection row : dist) {
            long n = row.getN();
            long cnt = row.getCnt();
            buckets.add(new SolvedCountBucket(n, cnt, cumHigher));
            cumHigher += cnt;
        }
        solvedBucketRepository.deleteAllInBatch();
        solvedBucketRepository.saveAll(buckets);
    }
}

