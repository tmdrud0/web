package my.oj.web;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import my.oj.web.contest.submission.config.ContestSubmissionExecutorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }

    @Bean(name = "contestSubmissionExecutor")
    public ThreadPoolTaskExecutor contestSubmissionExecutor(ContestSubmissionExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("contest-submission-");
        executor.setCorePoolSize(properties.effectiveCorePoolSize());
        executor.setMaxPoolSize(properties.effectiveMaxPoolSize());
        executor.setQueueCapacity(properties.effectiveQueueCapacity());
        return executor;
    }
}
