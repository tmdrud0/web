package my.oj.web.user.rank;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import my.oj.web.user.rank.dto.RankItemDto;

public final class RankPageAssembler {
    private RankPageAssembler() {}

    public static <T> List<RankItemDto> toRankItems(
            long startRank,
            List<T> rows,
            Function<T, Long> idExtractor,
            Function<T, String> nameExtractor,
            ToLongFunction<T> metricExtractor,
            Function<T, LocalDateTime> lastSolvedExtractor
    ) {
        List<RankItemDto> items = new ArrayList<>(rows.size());
        long runningRank = startRank;
        for (T row : rows) {
            items.add(new RankItemDto(
                    runningRank++,
                    idExtractor.apply(row),
                    nameExtractor.apply(row),
                    metricExtractor.applyAsLong(row),
                    lastSolvedExtractor.apply(row)
            ));
        }
        return items;
    }
}
