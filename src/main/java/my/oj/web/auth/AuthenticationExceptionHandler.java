package my.oj.web.auth;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class AuthenticationExceptionHandler {

    @ExceptionHandler(UnauthenticatedException.class)
    public String handleUnauthenticated(UnauthenticatedException ex) {
        return "redirect:/login";
    }
}
