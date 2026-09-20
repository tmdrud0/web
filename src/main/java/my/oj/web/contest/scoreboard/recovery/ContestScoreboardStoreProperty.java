package my.oj.web.contest.scoreboard.recovery;

import org.springframework.core.env.Environment;

/**
 * Reads {@code contest.scoreboard.store} the same way {@code ContestScoreboardStoreConfig} does,
 * so the recovery code reasons about the store that was actually selected.
 */
public final class ContestScoreboardStoreProperty {

    static final String NAME = "contest.scoreboard.store";

    /** Mirrors the {@code matchIfMissing = true} on the memory branch of the store config. */
    static final String DEFAULT_STORE = "memory";

    private ContestScoreboardStoreProperty() {
    }

    public static String value(Environment environment) {
        return environment.getProperty(NAME, DEFAULT_STORE);
    }
}
