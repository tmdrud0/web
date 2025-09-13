package my.oj.web.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findByCredentials(String name, String pass) {
        return Optional.ofNullable(userRepository.findByNameAndPass(name, pass));
    }

    public boolean isUserExists(String name, String pass) {
        return userRepository.findByNameAndPass(name, pass) != null;
    }

    public User register(String name, String pass) {
        User user = User.create(name, pass);
        return userRepository.save(user);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

}
