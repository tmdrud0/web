package my.oj.web.contest.scoreboard;

/**
 * Publishes a durable request to update the live contest scoreboard.
 */
public interface ContestScoreboardUpdatePublisher {

    boolean publishIfAbsent(ContestScoreboardUpdate update);
}
