package my.oj.web.user;

import my.oj.web.api.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(long userId) {
        super("User not found: " + userId);
    }
}
