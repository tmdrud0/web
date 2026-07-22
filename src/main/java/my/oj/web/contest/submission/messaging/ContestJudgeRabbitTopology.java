package my.oj.web.contest.submission.messaging;

public final class ContestJudgeRabbitTopology {

    public static final String EXCHANGE = "contest.judge.exchange";
    public static final String LIVE_QUEUE = "contest.judge.live";
    public static final String LIVE_ROUTING_KEY = "contest.judge.live";
    public static final String DEAD_LETTER_EXCHANGE = "contest.judge.dlx";
    public static final String DEAD_LETTER_QUEUE = "contest.judge.dead";
    public static final String DEAD_LETTER_ROUTING_KEY = "contest.judge.dead";

    private ContestJudgeRabbitTopology() {
    }
}
