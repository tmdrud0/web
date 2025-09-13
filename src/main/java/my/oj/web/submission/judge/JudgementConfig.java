package my.oj.web.submission.judge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class JudgementConfig {
    @Bean
    public Judgement judgement(){
        return new MockJudgement();
    }
}
