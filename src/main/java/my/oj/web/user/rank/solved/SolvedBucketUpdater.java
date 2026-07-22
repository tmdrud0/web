package my.oj.web.user.rank.solved;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.rank.solved.solvedbucket.SolvedBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SolvedBucketUpdater {

    private final SolvedBucketRepository solvedBucketRepository;

    @Transactional
    public void incrementFrom(long oldSolved) {
        long newSolved = oldSolved + 1;

        if (oldSolved > 0) {
            solvedBucketRepository.decrementUserAndIncreaseHigher(oldSolved, 1);
        }

        solvedBucketRepository.incrementUserCount(newSolved);
    }
}
