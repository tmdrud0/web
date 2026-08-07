package my.oj.web.user.api;

import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(@NotBlank String userName, @NotBlank String pass) {
}
