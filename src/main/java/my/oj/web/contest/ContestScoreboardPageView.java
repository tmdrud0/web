package my.oj.web.contest;

import my.oj.web.contest.dto.ContestScoreboardRow;

import java.util.List;
import java.util.Optional;

record ContestScoreboardPageView(List<ContestScoreboardRow> rows,
                                 long startRank,
                                 long totalParticipants,
                                 int pageSize,
                                 boolean aroundMe,
                                 Optional<Long> previousCursor,
                                 Optional<Long> nextCursor,
                                 Optional<Long> cursor) {

    static ContestScoreboardPageView empty(int pageSize) {
        return new ContestScoreboardPageView(
                List.of(),
                1,
                0,
                pageSize,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }
}
