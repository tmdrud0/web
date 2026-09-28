package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Ownership as a MySQL named lock ({@code GET_LOCK}) held on one dedicated connection.
 *
 * <p>A named lock belongs to the session that took it and is released when that session ends, so a JVM
 * that dies gives it up without anyone cleaning up. Every {@link #holds()} re-checks that this connection
 * is still the holder; a connection that went away is dropped and the lock is taken again only if it is
 * free.</p>
 */
@Slf4j
public class MySqlNamedLockPollOwnership implements ContestScoreboardPollOwnership {

    private final DataSource dataSource;
    private final String lockName;
    private Connection connection;
    private boolean warnedNotOwner;

    public MySqlNamedLockPollOwnership(DataSource dataSource, String lockName) {
        this.dataSource = dataSource;
        this.lockName = lockName;
    }

    @Override
    public synchronized boolean holds() {
        if (connection != null && stillHeld()) {
            return true;
        }
        closeQuietly();
        try {
            Connection candidate = dataSource.getConnection();
            if (candidate == null) {
                return false;
            }
            if (acquire(candidate)) {
                connection = candidate;
                warnedNotOwner = false;
                log.info("This instance holds the scoreboard poller lock '{}'", lockName);
                return true;
            }
            candidate.close();
        } catch (SQLException failure) {
            log.warn("Could not check the scoreboard poller lock '{}'", lockName, failure);
            return false;
        }
        if (!warnedNotOwner) {
            warnedNotOwner = true;
            log.error("Another instance holds the scoreboard poller lock '{}'; this instance will not poll or"
                    + " recover. Only one recovery owner may run the mysql-poll delivery.", lockName);
        }
        return false;
    }

    @Override
    public synchronized void release() {
        if (connection == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the session releases it anyway.
        }
        closeQuietly();
    }

    private boolean acquire(Connection candidate) throws SQLException {
        try (PreparedStatement statement = candidate.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean stillHeld() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        } catch (SQLException lost) {
            log.warn("Lost the connection holding the scoreboard poller lock '{}'", lockName, lost);
            return false;
        }
    }

    private void closeQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing to do: the session is gone either way.
        }
        connection = null;
    }
}
