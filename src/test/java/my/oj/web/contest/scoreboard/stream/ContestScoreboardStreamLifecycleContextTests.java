package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryCutover;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The held consumer against the <em>real</em> Spring lifecycle, in a real context, closed for real.
 *
 * <p>Why this is not another unit test. {@code ContestScoreboardStreamLifecycleTests} calls
 * {@code stop()} itself, which proves the guard inside this class but says nothing about whether the
 * guard is ever reached: Spring's {@code DefaultLifecycleProcessor} only asks a bean it considers
 * running to stop ({@code doStop} is entered under {@code bean.isRunning()}), so a lifecycle that
 * reports itself stopped while it is merely waiting for the history boundary is a bean Spring closes
 * the context without stopping. A held consumer that Spring never stops is a held consumer whose
 * {@code stopping} flag is still false when the recovery pass finishes late - and the release then
 * starts a listener container into a context that is already going down.</p>
 *
 * <p>That is what these two tests exercise, and it is why they run a real {@code SpringApplication}
 * rather than assembling the lifecycle by hand. The container is the only thing mocked (it is the
 * broker), and its {@code stop(Runnable)} completes the callback a real container completes, so the
 * context does not wait out Spring's shutdown timeout.</p>
 *
 * <p>What the mock does <em>not</em> reproduce is the real container's own shutdown ordering: a real
 * {@code SimpleMessageListenerContainer} hands the wait to a task executor and can return from
 * {@code stop(callback)} before its consumers are down. So the {@code verify(container).stop(...)} below
 * pins that the context close reaches this lifecycle, not that the real container is fully down by the
 * time the close returns.</p>
 */
class ContestScoreboardStreamLifecycleContextTests {

    /**
     * The mode holds its consumer until its own history recovery has run, and the close arrives first.
     *
     * <p>The pass then finishes late - a full replay of every contest is allowed to - and reports the
     * boundary it owns. Nothing may start a listener container at that point: the context is closing,
     * the broker connection is going with it, and a container started here would consume into a
     * shutdown.</p>
     */
    @Test
    void aConsumerReleasedAfterTheContextHasClosedIsNotStarted() {
        ConfigurableApplicationContext context = run();
        try {
            SimpleMessageListenerContainer container = container(context);
            ContestScoreboardStreamLifecycle lifecycle = lifecycle(context);
            // Held before the close: a closed context hands out no beans, and the pass that reports the
            // boundary late still holds the objects it was given.
            ContestScoreboardRecoveryCutover cutover = cutover(context);

            // The hold: this mode's history recovery is a startup pass, so nothing consumes yet.
            verify(container, never()).start();
            assertThat(lifecycle.consuming()).isFalse();

            context.close();

            cutover.markCovered("the mode's startup pass");

            verify(container, never()).start();
            assertThat(lifecycle.consuming())
                    .as("a container started here would consume into a closing context")
                    .isFalse();
        } finally {
            context.close();
        }
    }

    /**
     * The ordinary case, through the same path: the pass covers the history while the context is up, the
     * consumer starts - exactly once, however many triggers report the boundary - and the context close
     * stops it.
     */
    @Test
    void aConsumerReleasedBeforeTheCloseStartsOnceAndIsStoppedWithTheContext() {
        ConfigurableApplicationContext context = run();
        try {
            SimpleMessageListenerContainer container = container(context);
            ContestScoreboardStreamLifecycle lifecycle = lifecycle(context);

            cutover(context).markCovered("the mode's startup pass");

            verify(container, times(1)).start();
            assertThat(lifecycle.consuming()).isTrue();
            assertThat(consumerArguments(container))
                    .as("the stored checkpoint itself, not its successor and not a broker tail")
                    .containsEntry("x-stream-offset", 4L);

            // The redis-seq periodic checks report the same boundary again; a second report is not a
            // second start.
            cutover(context).markCovered("the redis-seq duplicate-check check");
            verify(container, times(1)).start();

            context.close();

            verify(container).stop(any(Runnable.class));
            assertThat(lifecycle.consuming()).isFalse();
        } finally {
            context.close();
        }
    }

    private ConfigurableApplicationContext run() {
        SpringApplication application = new SpringApplication(HeldConsumerConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(
                "--contest.scoreboard.stream.consumer.enabled=true",
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false"
        );
    }

    private static SimpleMessageListenerContainer container(ConfigurableApplicationContext context) {
        return context.getBean("contestScoreboardStreamListenerContainer",
                SimpleMessageListenerContainer.class);
    }

    private static ContestScoreboardStreamLifecycle lifecycle(ConfigurableApplicationContext context) {
        return context.getBean(ContestScoreboardStreamLifecycle.class);
    }

    private static ContestScoreboardRecoveryCutover cutover(ConfigurableApplicationContext context) {
        return context.getBean(ContestScoreboardRecoveryCutover.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> consumerArguments(SimpleMessageListenerContainer container) {
        org.mockito.ArgumentCaptor<Map<String, Object>> arguments =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(container, atLeastOnce()).setConsumerArguments(arguments.capture());
        return arguments.getValue();
    }

    /**
     * The components a context needs to hold a consumer, with the broker and the mode substituted.
     *
     * <p>The strategy is the one thing that decides whether the consumer is held at all
     * ({@code recoversHistoryBeforeConsuming()}), so it is stubbed to the modes that do hold - which is
     * the configuration this whole class is about.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    @Import({
            ContestScoreboardStreamLifecycle.class,
            ContestScoreboardStreamRollbackSignal.class,
            ContestScoreboardStreamPosition.class,
            ContestScoreboardStreamMetrics.class,
            ContestScoreboardRecoveryCutover.class
    })
    static class HeldConsumerConfiguration {

        @Bean("contestScoreboardStreamListenerContainer")
        SimpleMessageListenerContainer contestScoreboardStreamListenerContainer() {
            SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
            // A real container completes the stop callback once it has stopped. A mock that leaves it
            // uncalled makes the context close wait out Spring's whole shutdown timeout.
            doAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            }).when(container).stop(any(Runnable.class));
            // And a real container that started has consumers; the lifecycle asks for the count rather
            // than believing start() returned. What the mock cannot reproduce is the real container's
            // own shutdown ordering (its stop callback is completed here immediately, where a real one
            // hands the wait to a task executor) - see the class javadoc.
            when(container.getActiveConsumerCount()).thenReturn(1);
            return container;
        }

        @Bean
        ContestScoreboardApplier scoreboardApplier() {
            ContestScoreboardApplier applier = mock(ContestScoreboardApplier.class);
            when(applier.currentStreamOffset()).thenReturn(4L);
            return applier;
        }

        @Bean
        ContestScoreboardAppliedAtCompletion appliedAtCompletion() {
            return mock(ContestScoreboardAppliedAtCompletion.class);
        }

        @Bean
        ContestScoreboardRecoveryStrategy recoveryStrategy() {
            ContestScoreboardRecoveryStrategy strategy = mock(ContestScoreboardRecoveryStrategy.class);
            when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
            when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.FULL_REPLAY);
            return strategy;
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
