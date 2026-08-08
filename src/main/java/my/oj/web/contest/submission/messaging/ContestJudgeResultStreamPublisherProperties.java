package my.oj.web.contest.submission.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("contest.submission.judge.result-stream.publisher")
public record ContestJudgeResultStreamPublisherProperties(
        @DefaultValue("10s") Duration confirmTimeout
) {
}
