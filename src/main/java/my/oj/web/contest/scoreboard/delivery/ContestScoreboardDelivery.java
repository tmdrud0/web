package my.oj.web.contest.scoreboard.delivery;

import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * How judged results reach the live scoreboard.
 *
 * <p>The delivery is a separate axis from the recovery mode because the two used to be one thing: every
 * mode received results from the RabbitMQ Stream and differed only in how it recovered. {@code redis-seq}
 * now reads the MySQL ledger instead, so which transport feeds the scoreboard has to be stated rather
 * than implied. {@code ContestScoreboardRecoveryValidator} refuses the pairs that are not supported.</p>
 */
public enum ContestScoreboardDelivery {

    /** Judge result Stream publisher and scoreboard Stream consumer; {@code stream-offset}, {@code full-replay}. */
    RABBIT_STREAM("rabbit-stream"),

    /** A MySQL poller applies judged rows with no {@code scoreboard_applied_seq}; {@code redis-seq}. */
    MYSQL_POLL("mysql-poll");

    public static final String PROPERTY = "contest.scoreboard.delivery";

    private final String propertyValue;

    ContestScoreboardDelivery(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public String propertyValue() {
        return propertyValue;
    }

    /**
     * The configured delivery, read leniently: {@code MYSQL_POLL} and {@code mysql-poll} both mean the
     * poller. Bean conditions use this reading so that a misspelt value can never leave both transports
     * running; the validator then refuses the non-canonical spelling outright.
     *
     * @throws IllegalStateException for a value that names no delivery
     */
    public static ContestScoreboardDelivery of(Environment environment) {
        String configured = environment.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            return RABBIT_STREAM;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ContestScoreboardDelivery delivery : values()) {
            if (delivery.propertyValue.equals(normalized)) {
                return delivery;
            }
        }
        throw new IllegalStateException(PROPERTY + "=" + configured
                + " names no delivery; expected rabbit-stream or mysql-poll");
    }

    /** Like {@link #of}, but an unknown value reads as the poller so conditions keep the Stream path off. */
    public static boolean isMySqlPoll(Environment environment) {
        try {
            return of(environment) == MYSQL_POLL;
        } catch (IllegalStateException unknown) {
            return true;
        }
    }
}
