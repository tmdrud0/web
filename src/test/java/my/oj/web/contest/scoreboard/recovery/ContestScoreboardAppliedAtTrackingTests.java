package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code contest.scoreboard.stream-offset.applied-at-tracking}: what it resolves to, which modes may turn
 * it off, and that the harness's environment variable reaches it.
 */
class ContestScoreboardAppliedAtTrackingTests {

    @Test
    void unsetItFollowsTheMode() {
        assertThat(ContestScoreboardAppliedAtTracking.resolve(Optional.empty(), ContestScoreboardRecoveryMode.STREAM_OFFSET))
                .isFalse();
        assertThat(ContestScoreboardAppliedAtTracking.resolve(Optional.empty(), ContestScoreboardRecoveryMode.FULL_REPLAY))
                .isTrue();
        assertThat(ContestScoreboardAppliedAtTracking.resolve(Optional.empty(), ContestScoreboardRecoveryMode.REDIS_SEQ))
                .isTrue();
        // No recovery properties at all (a slice context): the historical behaviour.
        assertThat(ContestScoreboardAppliedAtTracking.resolve(Optional.empty(), null)).isTrue();
    }

    @Test
    void anExplicitValueWins() {
        assertThat(ContestScoreboardAppliedAtTracking.resolve(Optional.of(true), ContestScoreboardRecoveryMode.STREAM_OFFSET))
                .isTrue();
        assertThat(ContestScoreboardAppliedAtTracking.resolve(Optional.of(false), ContestScoreboardRecoveryMode.STREAM_OFFSET))
                .isFalse();
    }

    @Test
    void fullReplayAndRedisSeqRefuseToStartWithItOff() {
        assertThatThrownBy(() -> validator("full-replay", "rabbit-stream", "false").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContestScoreboardAppliedAtTracking.PROPERTY + "=false")
                .hasMessageContaining("full-replay");
        assertThatThrownBy(() -> validator("redis-seq", "mysql-poll", "false").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis-seq");
    }

    @Test
    void streamOffsetMayTurnItOffAndEveryModeMayKeepItOn() {
        assertThatCode(() -> validator("stream-offset", "rabbit-stream", "false").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("full-replay", "rabbit-stream", "true").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("redis-seq", "mysql-poll", "true").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("full-replay", "rabbit-stream", null).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    /**
     * The harness passes the switch as {@code CONTEST_SCOREBOARD_STREAM_OFFSET_APPLIED_AT_TRACKING} through
     * compose; the dashed segments reach the property through Boot's legacy environment-variable mapping.
     */
    @Test
    void theComposeEnvironmentVariableReachesTheProperty() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("CONTEST_SCOREBOARD_STREAM_OFFSET_APPLIED_AT_TRACKING", "false")));

        assertThat(ContestScoreboardAppliedAtTracking.configured(environment)).contains(false);
    }

    private static ContestScoreboardRecoveryValidator validator(String mode, String delivery, String tracking) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(ContestScoreboardRecoveryValidator.MODE_PROPERTY, mode);
        environment.setProperty(ContestScoreboardStoreProperty.NAME, "redis");
        environment.setProperty(ContestScoreboardRecoveryValidator.DELIVERY_PROPERTY, delivery);
        environment.setProperty(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY, "false");
        environment.setProperty(ContestScoreboardRecoveryValidator.OWNER_PROPERTY, "false");
        if (tracking != null) {
            environment.setProperty(ContestScoreboardAppliedAtTracking.PROPERTY, tracking);
        }
        ContestScoreboardRecoveryProperties properties = Binder.get(environment)
                .bindOrCreate("contest.scoreboard.recovery", ContestScoreboardRecoveryProperties.class);
        return new ContestScoreboardRecoveryValidator(properties, environment);
    }
}
