package my.oj.web.user.rank.dto;

import java.time.LocalDateTime;

public record RankItemDto(long rank, long userId, String name, long solvedCount, LocalDateTime lastSolvedTime) {
}
