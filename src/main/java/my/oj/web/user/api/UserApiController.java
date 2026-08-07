package my.oj.web.user.api;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import my.oj.web.user.User;
import my.oj.web.user.UserNotFoundException;
import my.oj.web.user.UserService;
import my.oj.web.user.dto.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Registration, sign-out, and reading a user.
 *
 * <p>Sign-out is a POST. It was a GET because a link in a rendered header could only be a GET, and
 * a GET that destroys the session is a request any prefetcher or crawler can make on the user's
 * behalf.
 */
@RestController
class UserApiController {

    private final UserService userService;

    UserApiController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/api/register")
    ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userService.isUserExists(request.userName(), request.pass())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "UserAlreadyExists", "message", "User already exists."));
        }
        User created = userService.register(request.userName(), request.pass());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserDto.from(created));
    }

    @PostMapping("/api/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpSession session) {
        session.invalidate();
    }

    @GetMapping("/api/users/{userId}")
    UserDto user(@PathVariable long userId) {
        return userService.findById(userId)
                .map(UserDto::from)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
