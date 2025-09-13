package my.oj.web.user.dto;

import my.oj.web.user.Streak;
import my.oj.web.user.User;

public record UserDto(Long id, String name, Long solvedCount, Streak streak) {
    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getName(), user.getSolvedCount(), user.getStreak());
    }
}
