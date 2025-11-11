package my.oj.web.submission.judge;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class JudgementConfig {

    @Bean
    @Qualifier("fullJudge")
    public Judgement fullJudge() {
        return new MockJudgement();
    }

    @Bean
    public Judgement judgement(@Qualifier("fullJudge") Judgement fullJudge) {
        return fullJudge;
    }
}
