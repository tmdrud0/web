package my.oj.web.submission.store;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SubmissionHashDeduplicator {

    private static final int MAX_HASH_COLLISION_RETRY = 5;

    public <T> Result<T> save(Command<T> command) {
        int attempt = 0;
        command.resetCandidate(attempt);

        while (true) {
            Optional<T> duplicate = command.findDuplicate();
            if (duplicate.isPresent()) {
                if (command.isSameCode(duplicate.get())) {
                    return Result.duplicate(duplicate.get());
                }
                attempt = nextAttempt(attempt, command, null);
                command.resetCandidate(attempt);
                continue;
            }

            try {
                T saved = command.save();
                return Result.saved(saved);
            } catch (DataIntegrityViolationException ex) {
                Optional<T> racedDuplicate = command.findDuplicate();
                if (racedDuplicate.isPresent() && command.isSameCode(racedDuplicate.get())) {
                    return Result.duplicate(racedDuplicate.get());
                }
                attempt = nextAttempt(attempt, command, ex);
                command.resetCandidate(attempt);
            }
        }
    }

    private <T> int nextAttempt(int attempt, Command<T> command, @Nullable DataIntegrityViolationException ex) {
        int next = attempt + 1;
        if (next > MAX_HASH_COLLISION_RETRY) {
            throw command.collisionLimitExceeded(next, ex);
        }
        return next;
    }

    public interface Command<T> {
        void resetCandidate(int attempt);

        Optional<T> findDuplicate();

        boolean isSameCode(T duplicate);

        T save();

        RuntimeException collisionLimitExceeded(int attempt, @Nullable DataIntegrityViolationException ex);
    }

    public record Result<T>(T entity, boolean duplicate) {
        public static <T> Result<T> saved(T entity) {
            return new Result<>(entity, false);
        }

        public static <T> Result<T> duplicate(T entity) {
            return new Result<>(entity, true);
        }
    }
}
