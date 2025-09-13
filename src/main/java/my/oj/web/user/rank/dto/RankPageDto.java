package my.oj.web.user.rank.dto;

import java.util.List;

public record RankPageDto(long myRank, long pageStartRank, int pageSize, List<RankItemDto> items) {
    public RankPageDto {
        items = List.copyOf(items);
    }
}
