package my.oj.web.contest.submission.messaging;

public final class ContestJudgeRabbitTopology {

    public static final String EXCHANGE = "contest.judge.exchange";
    public static final String LIVE_QUEUE = "contest.judge.live";
    public static final String LIVE_ROUTING_KEY = "contest.judge.live";
    public static final String RESULT_STREAM_QUEUE = "contest.judge.result.stream";
    public static final String RESULT_STREAM_ROUTING_KEY = "contest.judge.result.stream";
    public static final String RESULT_STREAM_MAX_AGE = "7D";
    public static final long RESULT_STREAM_MAX_LENGTH_BYTES = 10L * 1024 * 1024 * 1024;
    public static final String DEAD_LETTER_EXCHANGE = "contest.judge.dlx";
    public static final String DEAD_LETTER_QUEUE = "contest.judge.dead";
    public static final String DEAD_LETTER_ROUTING_KEY = "contest.judge.dead";

    private ContestJudgeRabbitTopology() {
    }
}
