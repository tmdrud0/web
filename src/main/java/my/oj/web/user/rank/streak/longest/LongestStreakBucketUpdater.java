package my.oj.web.user.rank.streak.longest;

import lombok.RequiredArgsConstructor;
import my.oj.web.user.rank.streak.longest.longestbucket.LongestStreakBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LongestStreakBucketUpdater {

    private final LongestStreakBucketRepository longestStreakBucketRepository;

    @Transactional
    public void handleIncrease(int oldLongest, int newLongest) {
        if (newLongest <= oldLongest || newLongest <= 0) {
            return;
        }

        if (oldLongest > 0) {
            longestStreakBucketRepository.decrementUserAndIncreaseHigher(oldLongest, 1L);
        }

        longestStreakBucketRepository.incrementUserCount(newLongest);
    }
}
