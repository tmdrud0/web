package my.oj.web.contest.scoreboard.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The write path of the {@code mysql-poll} delivery, plus the allocator fence.
 *
 * <p>Scoring is {@link ContestScoreboardRedisScript#SCORING}, the same Lua the Stream path runs, so the
 * two deliveries cannot disagree about the standings. What differs is the sequence contract: the poller
 * has no stream offset to store, so the script returns the sequence it issued, and it guards that
 * sequence against a Redis restore.</p>
 *
 * <h2>{@link #APPLY}</h2>
 *
 * <p>KEYS: [1] ranking, [2] summary, [3] problem, [4] processed set, [5] allocator, [6] submission-to-sequence
 * mapping. ARGV: [1] submission id, [2] result, [3] contest minutes, [4] wrong penalty, [5] solved weight,
 * [6] penalty weight, [7] user id, [8] expected durable watermark, [9] re-sequence floor.</p>
 *
 * <ol>
 *   <li>If the allocator is below ARGV[8] - the MySQL watermark the caller read, raised by every sequence
 *       it has issued since - Redis was restored to an older snapshot. The script returns {@code -1} having
 *       written nothing: no standings change and no sequence. Because this is checked inside the same
 *       {@code EVAL} that would issue the sequence, a restore that lands between the caller's own
 *       allocator/watermark check and this call still cannot make a sequence be issued twice.</li>
 *   <li>A submission the processed set does not hold is scored and issued {@code allocator + 1} (stepped
 *       past any mapping it still has), and the sequence is returned.</li>
 *   <li>A submission already processed changes no standings. Its mapped sequence is returned when it is
 *       above ARGV[9]; otherwise it is given a fresh sequence. The poller passes {@code 0}, so a re-polled
 *       row (Redis applied, MySQL marker lost) gets back the sequence it already has. Range recovery passes
 *       the range's upper bound, so every row it touches leaves the range - which is what lets it re-read
 *       the first page until the range is empty.</li>
 * </ol>
 *
 * <p>A {@code PENDING} result is refused: the processed set would swallow its real judgement.</p>
 *
 * <h2>{@link #FENCE}</h2>
 *
 * <p>{@code allocator = max(allocator, ARGV[1])}, atomically, returning the resulting allocator. A plain
 * {@code SET} could move the allocator backwards over sequences issued after the watermark was read.</p>
 */
final class ContestScoreboardRedisPollScript {

    /** Returned by {@link #APPLY} when the allocator is below the expected watermark. */
    static final long ROLLBACK = -1L;

    static final String APPLY_TEXT = ContestScoreboardRedisScript.HELPERS
            + ContestScoreboardRedisScript.SCORING + """
                    local contestMinutes = tonumber(ARGV[3])
                    local wrongPenalty = tonumber(ARGV[4])
                    local solvedWeight = tonumber(ARGV[5])
                    local penaltyWeight = tonumber(ARGV[6])
                    local userId = tonumber(ARGV[7])
                    if not contestMinutes or not wrongPenalty or not solvedWeight or not penaltyWeight or not userId then
                        return redis.error_reply('Invalid scoreboard numeric argument')
                    end
                    if not string.match(ARGV[1], '^%d+$') then
                        return redis.error_reply('Invalid scoreboard submission id argument')
                    end
                    local submissionId = ARGV[1]
                    local result = ARGV[2]
                    if result == '' or result == 'PENDING' then
                        return redis.error_reply('The MySQL poller applies judged results only')
                    end
                    local expectedWatermark = parseInteger(ARGV[8], 'expectedWatermark')
                    local resequenceFloor = parseInteger(ARGV[9], 'resequenceFloor')

                    assertKeyType(KEYS[1], 'zset')
                    assertKeyType(KEYS[2], 'hash')
                    assertKeyType(KEYS[3], 'hash')
                    assertKeyType(KEYS[4], 'set')
                    assertKeyType(KEYS[5], 'string')
                    assertKeyType(KEYS[6], 'hash')

                    local allocator = parseInteger(redis.call('get', KEYS[5]), 'allocatorSequence')
                    if allocator < 0 then
                        return redis.error_reply('Invalid negative scoreboard allocator sequence')
                    end
                    -- Before any write: a restored snapshot must not issue a sequence MySQL already holds.
                    if allocator < expectedWatermark then
                        return -1
                    end

                    local mappedSequence = nil
                    local mapped = redis.call('hget', KEYS[6], submissionId)
                    if mapped then
                        if not string.match(mapped, '^%d+$') then
                            return redis.error_reply('Invalid scoreboard submission sequence')
                        end
                        mappedSequence = tonumber(mapped)
                    end
                    local sequenceToIssue = allocator + 1
                    if mappedSequence and mappedSequence >= sequenceToIssue then
                        sequenceToIssue = mappedSequence + 1
                    end

                    if redis.call('sismember', KEYS[4], submissionId) == 1 then
                        if mappedSequence and mappedSequence > resequenceFloor then
                            return mappedSequence
                        end
                        redis.call('set', KEYS[5], tostring(sequenceToIssue))
                        redis.call('hset', KEYS[6], submissionId, tostring(sequenceToIssue))
                        return sequenceToIssue
                    end

                    local state, stateError = readScoringState(KEYS[2], KEYS[3])
                    if stateError then
                        return redis.error_reply(stateError)
                    end
                    writeJudgement(state, KEYS[1], KEYS[2], KEYS[3], result, contestMinutes,
                            submissionId, wrongPenalty, solvedWeight, penaltyWeight, userId, ARGV[7])
                    redis.call('sadd', KEYS[4], submissionId)
                    redis.call('set', KEYS[5], tostring(sequenceToIssue))
                    redis.call('hset', KEYS[6], submissionId, tostring(sequenceToIssue))
                    return sequenceToIssue
                    """;

    static final String FENCE_TEXT = """
                    local function parseInteger(value, fieldName)
                        if not value then
                            return 0
                        end
                        if not string.match(value, '^-?%d+$') then
                            error('Invalid integer value for ' .. fieldName)
                        end
                        return tonumber(value)
                    end
                    local current = parseInteger(redis.call('get', KEYS[1]), 'allocatorSequence')
                    local target = parseInteger(ARGV[1], 'fenceTarget')
                    if current < target then
                        redis.call('set', KEYS[1], tostring(target))
                        return target
                    end
                    return current
                    """;

    static final RedisScript<Long> APPLY = new DefaultRedisScript<>(APPLY_TEXT, Long.class);

    static final RedisScript<Long> FENCE = new DefaultRedisScript<>(FENCE_TEXT, Long.class);

    private ContestScoreboardRedisPollScript() {
    }
}
