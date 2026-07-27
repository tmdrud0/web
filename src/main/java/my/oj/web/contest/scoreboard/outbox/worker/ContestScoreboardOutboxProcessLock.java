package my.oj.web.contest.scoreboard.outbox.worker;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ContestScoreboardOutboxProcessLock {

    private static final String LOCK_PREFIX = "oj:scoreboard-outbox:";

    private final DataSource dataSource;

    public ContestScoreboardOutboxProcessLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> Optional<T> executeIfAcquired(Supplier<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            String lockName = lockName(connection);
            if (!acquire(connection, lockName)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(action.get());
            } finally {
                release(connection, lockName);
            }
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    "Failed to coordinate the contest scoreboard outbox worker",
                    exception
            );
        }
    }

    private static boolean acquire(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private static void release(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.executeQuery().close();
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    "Failed to release the contest scoreboard outbox worker lock",
                    exception
            );
        }
    }

    private static String lockName(Connection connection) throws SQLException {
        String catalog = connection.getCatalog();
        String suffix = catalog == null || catalog.isBlank() ? "default" : catalog;
        return LOCK_PREFIX + UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8));
    }
}
