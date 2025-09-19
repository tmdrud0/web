package my.oj.web.auth;

public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException() {
        super("User session is required");
    }
}
