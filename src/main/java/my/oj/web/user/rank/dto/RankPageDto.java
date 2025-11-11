package my.oj.web.user.rank.dto;

import java.util.List;

public record RankPageDto(
        long myRank,
        long pageStartRank,
        int pageSize,
        long totalItems,
        Long previousCursor,
        Long nextCursor,
        List<RankItemDto> items
) {
    public RankPageDto {
        items = List.copyOf(items);
    }
}
