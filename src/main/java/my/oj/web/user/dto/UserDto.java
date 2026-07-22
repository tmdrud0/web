package my.oj.web.user.dto;

import my.oj.web.user.Streak;
import my.oj.web.user.User;

import java.io.Serializable;

public record UserDto(Long id, String name, Long solvedCount, Streak streak) implements Serializable {
    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getName(), user.getSolvedCount(), user.getStreak());
    }
}
