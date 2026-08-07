package my.oj.web.user.api;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import my.oj.web.user.UserService;
import my.oj.web.user.dto.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication for JSON callers.
 *
 * <p>Deliberately still the session the page login creates - the same {@code "user"} attribute
 * read by {@code @CurrentUser} - so both entry points share one notion of who is signed in and
 * one place where that can go wrong. A token scheme alongside it would be a second answer to the
 * same question during a change that is already replacing the view layer.
 *
 * <p>The caller keeps the session cookie and sends it on later requests. Under load that means
 * authentication is paid once per user rather than once per submission, which is what the page
 * flow already did; what it removes is the login page GET and the form GET around it.
 */
@RestController
class LoginApiController {

    private final UserService userService;

    LoginApiController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/api/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        return userService.findByCredentials(request.userName(), request.pass())
                .map(user -> {
                    UserDto dto = UserDto.from(user);
                    session.setAttribute("user", dto);
                    return ResponseEntity.ok((Object) new LoginResponse(dto.id(), dto.name()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "InvalidCredentials", "message", "Invalid credentials.")));
    }

    public record LoginResponse(Long id, String name) {
    }
}
