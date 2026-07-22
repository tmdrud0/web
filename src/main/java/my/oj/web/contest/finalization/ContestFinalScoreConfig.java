package my.oj.web.contest.finalization;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.InMemoryContestScoreboardStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ContestFinalScoreConfig {

    @Bean
    @Qualifier("contestFinalScoreboardService")
    public ContestScoreboardService contestFinalScoreboardService() {
        return new ContestScoreboardService(new InMemoryContestScoreboardStore());
    }
}
