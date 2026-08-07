package my.oj.web.api;

/**
 * A request named something that does not exist.
 *
 * <p>One supertype so that {@link JsonApiExceptionHandler} maps 404 once rather than growing a
 * handler per resource. Each resource still throws its own type, so a caller reading the error
 * body sees which one was missing.
 *
 * <p>Deliberately not an {@link IllegalArgumentException}: that is what the API answers 400 with,
 * and a missing resource is not a malformed request.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
