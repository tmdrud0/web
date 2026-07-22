package my.oj.web.perf.dto;

import java.util.List;

import my.oj.web.user.rank.dto.RankItemDto;

public record AroundBenchResult(
        long targetRank,
        long userId,
        RankItemDto target,
        long optimizedMyRank,
        long optimizedPageStart,
        double optimizedMillis,
        double naiveMillis
) {}
