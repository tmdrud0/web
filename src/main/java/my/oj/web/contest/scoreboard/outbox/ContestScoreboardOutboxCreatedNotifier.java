package my.oj.web.contest.scoreboard.outbox;

public interface ContestScoreboardOutboxCreatedNotifier {

    void notifyCreated(Long contestSubmissionId);
}
