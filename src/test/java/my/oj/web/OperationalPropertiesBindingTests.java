package my.oj.web;

import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxProperties;
import my.oj.web.contest.submission.config.ContestSubmissionExecutorProperties;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultWriterProperties;
import my.oj.web.contest.submission.messaging.ContestJudgeOutboxRelayProperties;
import my.oj.web.contest.submission.queue.ContestSubmissionBulkProperties;
import my.oj.web.contest.submission.queue.ContestSubmissionCompletionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalPropertiesBindingTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsOperationalPropertiesWithTheirExistingDefaults() {
        contextRunner.run(context -> {
            ContestSubmissionExecutorProperties executor =
                    context.getBean(ContestSubmissionExecutorProperties.class);
            ContestScoreboardOutboxProperties scoreboard =
                    context.getBean(ContestScoreboardOutboxProperties.class);
            ContestJudgeOutboxRelayProperties judgeRelay =
                    context.getBean(ContestJudgeOutboxRelayProperties.class);
            ContestSubmissionBulkProperties bulk = context.getBean(ContestSubmissionBulkProperties.class);
            ContestSubmissionCompletionProperties completion =
                    context.getBean(ContestSubmissionCompletionProperties.class);
            ContestSubmissionJudgeResultWriterProperties resultWriter =
                    context.getBean(ContestSubmissionJudgeResultWriterProperties.class);

            assertThat(executor.corePoolSize()).isEqualTo(2);
            assertThat(executor.maxPoolSize()).isEqualTo(4);
            assertThat(executor.queueCapacity()).isEqualTo(1000);
            assertThat(scoreboard.batchSize()).isEqualTo(50);
            assertThat(scoreboard.recoveryBatchSize()).isEqualTo(50);
            assertThat(scoreboard.claimTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(judgeRelay.batchSize()).isEqualTo(50);
            assertThat(judgeRelay.claimTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(judgeRelay.confirmTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(bulk.batchSize()).isEqualTo(100);
            assertThat(bulk.workerCount()).isEqualTo(1);
            assertThat(bulk.maxInFlight()).isEqualTo(2000);
            assertThat(completion.threadCount()).isEqualTo(8);
            assertThat(completion.queueCapacity()).isEqualTo(256);
            assertThat(resultWriter.batchSize()).isEqualTo(16);
            assertThat(resultWriter.workerCount()).isEqualTo(1);
            assertThat(resultWriter.queueCapacity()).isEqualTo(256);
            assertThat(resultWriter.maxWait()).isEqualTo(Duration.ofMillis(5));
        });
    }

    @Test
    void preservesExistingLowerBoundNormalization() {
        contextRunner
                .withPropertyValues(
                        "contest.submission.async.core-pool-size=1",
                        "contest.submission.async.max-pool-size=0",
                        "contest.submission.async.queue-capacity=0",
                        "contest.outbox.batch-size=0",
                        "contest.outbox.recovery-batch-size=0",
                        "contest.submission.judge.rabbit.publisher.batch-size=0",
                        "contest.submission.bulk.batch-size=0",
                        "contest.submission.bulk.worker-count=0",
                        "contest.submission.bulk.max-in-flight=0",
                        "contest.submission.completion.thread-count=0",
                        "contest.submission.completion.queue-capacity=0",
                        "contest.submission.judge.result-writer.batch-size=4",
                        "contest.submission.judge.result-writer.worker-count=0",
                        "contest.submission.judge.result-writer.queue-capacity=0",
                        "contest.submission.judge.result-writer.max-wait=-1ms"
                )
                .run(context -> {
                    ContestSubmissionExecutorProperties executor =
                            context.getBean(ContestSubmissionExecutorProperties.class);
                    ContestScoreboardOutboxProperties scoreboard =
                            context.getBean(ContestScoreboardOutboxProperties.class);
                    ContestJudgeOutboxRelayProperties judgeRelay =
                            context.getBean(ContestJudgeOutboxRelayProperties.class);
                    ContestSubmissionBulkProperties bulk = context.getBean(ContestSubmissionBulkProperties.class);
                    ContestSubmissionCompletionProperties completion =
                            context.getBean(ContestSubmissionCompletionProperties.class);
                    ContestSubmissionJudgeResultWriterProperties resultWriter =
                            context.getBean(ContestSubmissionJudgeResultWriterProperties.class);

                    assertThat(executor.effectiveCorePoolSize()).isEqualTo(1);
                    assertThat(executor.effectiveMaxPoolSize()).isEqualTo(1);
                    assertThat(executor.effectiveQueueCapacity()).isEqualTo(1);
                    assertThat(scoreboard.effectiveBatchSize()).isEqualTo(1);
                    assertThat(scoreboard.effectiveRecoveryBatchSize()).isEqualTo(1);
                    assertThat(judgeRelay.effectiveBatchSize()).isEqualTo(1);
                    assertThat(bulk.effectiveBatchSize()).isEqualTo(1);
                    assertThat(bulk.effectiveWorkerCount()).isEqualTo(1);
                    assertThat(bulk.effectiveMaxInFlight()).isEqualTo(1);
                    assertThat(completion.effectiveThreadCount()).isEqualTo(1);
                    assertThat(completion.effectiveQueueCapacity()).isEqualTo(1);
                    assertThat(resultWriter.effectiveBatchSize()).isEqualTo(4);
                    assertThat(resultWriter.effectiveWorkerCount()).isEqualTo(1);
                    assertThat(resultWriter.effectiveQueueCapacity()).isEqualTo(4);
                    assertThat(resultWriter.effectiveMaxWaitNanos()).isZero();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ContestSubmissionExecutorProperties.class,
            ContestScoreboardOutboxProperties.class,
            ContestJudgeOutboxRelayProperties.class,
            ContestSubmissionBulkProperties.class,
            ContestSubmissionCompletionProperties.class,
            ContestSubmissionJudgeResultWriterProperties.class
    })
    static class PropertiesConfiguration {
    }
}
