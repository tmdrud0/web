package my.oj.web.contest.scoreboard.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The whole write path for the live scoreboard, as one atomic script.
 *
 * <p>Deduplication keys off {@code contestSubmissionId} (ARGV[1]) rather than the outbox row
 * id. {@code uk_cs_outbox_submission} makes those one-to-one, and keying off the submission
 * lets a rebuild replay the same judgement without inventing an id space that could collide
 * with the outbox's.
 *
 * <p>Two structures track a submission, and the difference matters. KEYS[2] maps submission to
 * sequence and is global, so a sequence stays stable for the lifetime of the deployment. KEYS[6]
 * records what has already been applied and is per-contest, so {@code reset} clears it and a
 * rebuild re-applies every judgement onto empty standings while sequences stay put.
 */
final class ContestScoreboardRedisScript {

    /**
     * Applies one judgement to the live scoreboard atomically.
     *
     * <p>The problem hash stores every attempt (see {@link ContestScoreboardRedisFields}) and the
     * summary contribution of that problem is recomputed from scratch on every event, so the
     * outcome does not depend on the order in which judgements arrive. Only the difference
     * against the previously recorded contribution is applied to the summary.
     *
     * <p>Sequence allocation, the {@code contestSubmissionId -> redis_seq} mapping and the
     * processed-event marker are unchanged: the recovery worker depends on them.
     */
    static final String TEXT = """
                    local function assertKeyType(key, expectedType)
                        local actualType = redis.call('type', key)['ok']
                        if actualType ~= 'none' and actualType ~= expectedType then
                            error('Unexpected Redis key type for ' .. key)
                        end
                    end

                    local function parseInteger(value, fieldName)
                        if not value then
                            return 0
                        end
                        if not string.match(value, '^-?%d+$') then
                            error('Invalid integer value for ' .. fieldName)
                        end
                        local parsed = tonumber(value)
                        if not parsed then
                            error('Invalid integer value for ' .. fieldName)
                        end
                        return parsed
                    end

                    local function parseSubmissionId(value, fieldName)
                        if not string.match(value, '^%d+$') then
                            error('Invalid submission id value for ' .. fieldName)
                        end
                        return value
                    end

                    -- Orders attempts by (contestMinutes, submissionId). Snowflake IDs need more
                    -- than the 53 bits a Lua number carries exactly, so they are compared as
                    -- decimal strings: shorter is smaller, equal length compares lexicographically.
                    local function isEarlierAttempt(minutes, submissionId, otherMinutes, otherSubmissionId)
                        if minutes ~= otherMinutes then
                            return minutes < otherMinutes
                        end
                        if #submissionId ~= #otherSubmissionId then
                            return #submissionId < #otherSubmissionId
                        end
                        return submissionId < otherSubmissionId
                    end

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

                    assertKeyType(KEYS[1], 'string')
                    assertKeyType(KEYS[2], 'hash')
                    assertKeyType(KEYS[3], 'zset')
                    assertKeyType(KEYS[4], 'hash')
                    assertKeyType(KEYS[5], 'hash')
                    assertKeyType(KEYS[6], 'set')

                    local allocatorSequence = parseInteger(
                            redis.call('get', KEYS[1]),
                            'allocatorSequence')
                    if allocatorSequence < 0 then
                        return redis.error_reply('Invalid negative scoreboard allocator sequence')
                    end

                    local existingSequence = redis.call('hget', KEYS[2], ARGV[1])
                    if existingSequence then
                        local mappedSequence = parseInteger(existingSequence, 'submissionSequence')
                        if mappedSequence < 1 then
                            return redis.error_reply('Invalid scoreboard submission sequence')
                        end
                        if mappedSequence > allocatorSequence then
                            redis.call('set', KEYS[1], mappedSequence)
                        end
                    end

                    local alreadyProcessed = redis.call('sismember', KEYS[6], ARGV[1])
                    if alreadyProcessed == 1 then
                        if not existingSequence then
                            return redis.error_reply('Processed scoreboard submission has no sequence mapping')
                        end
                        return tonumber(existingSequence)
                    end

                    local initialized = redis.call('hget', KEYS[4], 'initialized')
                    if initialized and initialized ~= '1' then
                        return redis.error_reply('Invalid scoreboard initialized flag')
                    end
                    local currentSolved = parseInteger(
                            redis.call('hget', KEYS[4], 'solved'),
                            'solved')
                    local currentPenalty = parseInteger(
                            redis.call('hget', KEYS[4], 'penalty'),
                            'penalty')

                    local acceptedMinutes = nil
                    local acceptedSubmissionId = nil
                    local contributedSolved = 0
                    local contributedPenalty = 0
                    local wrongMinutes = {}
                    local problemState = redis.call('hgetall', KEYS[5])
                    for index = 1, #problemState, 2 do
                        local field = problemState[index]
                        local value = problemState[index + 1]
                        if field == 'a:min' then
                            acceptedMinutes = parseInteger(value, 'a:min')
                        elseif field == 'a:sid' then
                            acceptedSubmissionId = parseSubmissionId(value, 'a:sid')
                        elseif field == 'c:solved' then
                            contributedSolved = parseInteger(value, 'c:solved')
                        elseif field == 'c:penalty' then
                            contributedPenalty = parseInteger(value, 'c:penalty')
                        elseif string.sub(field, 1, 2) == 'w:' then
                            wrongMinutes[string.sub(field, 3)] = parseInteger(value, field)
                        end
                    end
                    if (acceptedMinutes and not acceptedSubmissionId)
                            or (acceptedSubmissionId and not acceptedMinutes) then
                        return redis.error_reply('Incomplete scoreboard accepted attempt state')
                    end

                    local sequence = existingSequence
                    if not sequence then
                        sequence = redis.call('incr', KEYS[1])
                        redis.call('hset', KEYS[2], ARGV[1], sequence)
                    end

                    if ARGV[2] ~= 'PENDING' then
                        if not initialized then
                            redis.call('hset', KEYS[4],
                                    'solved', '0',
                                    'penalty', '0',
                                    'initialized', '1')
                            redis.call('zadd', KEYS[3], -userId, ARGV[7])
                        end

                        if ARGV[2] == 'ACCEPTED' then
                            if not acceptedMinutes or isEarlierAttempt(
                                    contestMinutes, submissionId,
                                    acceptedMinutes, acceptedSubmissionId) then
                                acceptedMinutes = contestMinutes
                                acceptedSubmissionId = submissionId
                                redis.call('hset', KEYS[5],
                                        'a:min', tostring(contestMinutes),
                                        'a:sid', submissionId)
                            end
                        else
                            wrongMinutes[submissionId] = contestMinutes
                            redis.call('hset', KEYS[5], 'w:' .. submissionId, tostring(contestMinutes))
                        end

                        local newSolved = 0
                        local newPenalty = 0
                        if acceptedMinutes then
                            newSolved = 1
                            local wrongBefore = 0
                            for wrongSubmissionId, minutes in pairs(wrongMinutes) do
                                if isEarlierAttempt(
                                        minutes, wrongSubmissionId,
                                        acceptedMinutes, acceptedSubmissionId) then
                                    wrongBefore = wrongBefore + 1
                                end
                            end
                            newPenalty = acceptedMinutes + wrongBefore * wrongPenalty
                        end

                        local solvedDelta = newSolved - contributedSolved
                        local penaltyDelta = newPenalty - contributedPenalty
                        local solved = currentSolved
                        local penalty = currentPenalty
                        if solvedDelta ~= 0 then
                            solved = redis.call('hincrby', KEYS[4], 'solved', solvedDelta)
                        end
                        if penaltyDelta ~= 0 then
                            penalty = redis.call('hincrby', KEYS[4], 'penalty', penaltyDelta)
                        end
                        if solvedDelta ~= 0 or penaltyDelta ~= 0 then
                            redis.call('hset', KEYS[5],
                                    'c:solved', tostring(newSolved),
                                    'c:penalty', tostring(newPenalty))
                        end

                        local score = solved * solvedWeight - penalty * penaltyWeight - userId
                        redis.call('zadd', KEYS[3], score, ARGV[7])
                    end

                    redis.call('sadd', KEYS[6], ARGV[1])
                    return tonumber(sequence)
                    """;

    static final RedisScript<Long> APPLY = new DefaultRedisScript<>(TEXT, Long.class);

    private ContestScoreboardRedisScript() {
    }
}
