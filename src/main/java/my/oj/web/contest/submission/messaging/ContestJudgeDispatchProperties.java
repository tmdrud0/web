package my.oj.web.contest.submission.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("contest.submission.judge")
public record ContestJudgeDispatchProperties(@DefaultValue("rabbit") DispatchMode dispatchMode) {

    public enum DispatchMode {
        RABBIT,
        MYSQL
    }
}
