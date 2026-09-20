package my.oj.web.contest.scoreboard.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The whole write path for the live scoreboard, as one atomic script.
 *
 * <p>Deduplication keys off {@code contestSubmissionId} (ARGV[3]). KEYS[1] stores the highest
 * broker offset reflected in the scoreboard. A live event mutates both in this script, so a Redis
 * snapshot rollback rewinds the derived state and its replay position together.
 *
 * <p>KEYS[6] remains a per-contest processed-submission set. The commutative problem state is the
 * correctness rule; this set only avoids recalculating duplicate stream entries. A contest reset
 * clears it so a DB rebuild can repopulate empty standings without advancing KEYS[1].
 *
 * <p>When {@code ARGV[10]} is {@code 1} the script also issues a recovery sequence: it reads the
 * allocator in KEYS[7], takes the next value above it - stepped past any mapping already held for
 * this submission, so a rewound allocator cannot re-issue a sequence a surviving mapping owns - then
 * {@code SET}s the allocator to it and {@code HSET}s {@code submissionId -> sequence} in KEYS[8].
 * The sequence is issued inside this one invocation precisely so that it cannot diverge from the
 * scoreboard it describes - a snapshot that rolls the standings back rolls the allocator and the
 * mapping back with them. The reply is unchanged (the same stream offset as before), so the caller
 * reads the issued sequence back from KEYS[8] rather than from a second round trip. A submission
 * already in KEYS[6] returns before any of this, so a re-delivered event is issued no sequence at
 * all. With {@code ARGV[10]} at {@code 0} the script does no sequence work and behaves exactly as it
 * did before.
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
     * <p>KEYS[2] records submission IDs whose {@code scoreboard_applied_at} still needs a JDBC
     * batch completion. It is written with the offset so a crash between Redis and MySQL can be
     * repaired without making MySQL another scoreboard checkpoint.
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

                    local contestMinutes = tonumber(ARGV[5])
                    local wrongPenalty = tonumber(ARGV[6])
                    local solvedWeight = tonumber(ARGV[7])
                    local penaltyWeight = tonumber(ARGV[8])
                    local userId = tonumber(ARGV[9])
                    if not contestMinutes or not wrongPenalty or not solvedWeight or not penaltyWeight or not userId then
                        return redis.error_reply('Invalid scoreboard numeric argument')
                    end
                    if not string.match(ARGV[3], '^%d+$') then
                        return redis.error_reply('Invalid scoreboard submission id argument')
                    end
                    local submissionId = ARGV[3]
                    if ARGV[10] ~= '0' and ARGV[10] ~= '1' then
                        return redis.error_reply('Invalid scoreboard sequence tracking flag')
                    end
                    local trackSequence = ARGV[10] == '1'

                    assertKeyType(KEYS[1], 'string')
                    assertKeyType(KEYS[2], 'set')
                    assertKeyType(KEYS[3], 'zset')
                    assertKeyType(KEYS[4], 'hash')
                    assertKeyType(KEYS[5], 'hash')
                    assertKeyType(KEYS[6], 'set')
                    if trackSequence then
                        assertKeyType(KEYS[7], 'string')
                        assertKeyType(KEYS[8], 'hash')
                    end

                    local currentOffsetValue = redis.call('get', KEYS[1])
                    local currentOffset = -1
                    if currentOffsetValue then
                        currentOffset = parseInteger(currentOffsetValue, 'streamOffset')
                    end
                    if currentOffset < -1 then
                        return redis.error_reply('Invalid negative scoreboard stream offset')
                    end

                    local streamOffset = nil
                    if ARGV[1] ~= '' then
                        streamOffset = parseInteger(ARGV[1], 'incomingStreamOffset')
                        if streamOffset < 0 then
                            return redis.error_reply('Invalid negative incoming scoreboard stream offset')
                        end
                        if streamOffset <= currentOffset then
                            return currentOffset
                        end
                        if ARGV[2] ~= '1' and streamOffset ~= currentOffset + 1 then
                            return redis.error_reply(
                                    'Non-contiguous scoreboard stream offset: expected '
                                    .. tostring(currentOffset + 1) .. ' but received '
                                    .. tostring(streamOffset))
                        end
                    elseif ARGV[2] == '1' then
                        return redis.error_reply('Rebuild request cannot allow a scoreboard stream offset gap')
                    end

                    local alreadyProcessed = redis.call('sismember', KEYS[6], submissionId)
                    if alreadyProcessed == 1 then
                        if streamOffset then
                            redis.call('sadd', KEYS[2], submissionId)
                            redis.call('set', KEYS[1], streamOffset)
                            return streamOffset
                        end
                        return currentOffset
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

                    if ARGV[4] ~= 'PENDING' then
                        -- Resolved before the first write: a script that returns an error reply does
                        -- not roll back the writes it already made, so a corrupt sequence state has
                        -- to fail this event before the standings move rather than after.
                        local sequenceToIssue = nil
                        if trackSequence then
                            local allocator = parseInteger(
                                    redis.call('get', KEYS[7]),
                                    'allocatorSequence')
                            if allocator < 0 then
                                return redis.error_reply('Invalid negative scoreboard allocator sequence')
                            end
                            local mappedSequence = nil
                            local mapped = redis.call('hget', KEYS[8], submissionId)
                            if mapped then
                                if not string.match(mapped, '^%d+$') then
                                    return redis.error_reply('Invalid scoreboard submission sequence')
                                end
                                mappedSequence = tonumber(mapped)
                            end
                            -- A mapping at or above the next allocator value means the allocator was
                            -- rewound past a mapping the snapshot kept; stepping over it keeps the
                            -- sequence strictly increasing, which is what lets a lost-tail check
                            -- terminate instead of re-issuing a sequence a mapping already holds.
                            sequenceToIssue = allocator + 1
                            if mappedSequence and mappedSequence >= sequenceToIssue then
                                sequenceToIssue = mappedSequence + 1
                            end
                        end

                        if not initialized then
                            redis.call('hset', KEYS[4],
                                    'solved', '0',
                                    'penalty', '0',
                                    'initialized', '1')
                            redis.call('zadd', KEYS[3], -userId, ARGV[9])
                        end

                        if ARGV[4] == 'ACCEPTED' then
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
                        redis.call('zadd', KEYS[3], score, ARGV[9])

                        if sequenceToIssue then
                            redis.call('set', KEYS[7], tostring(sequenceToIssue))
                            redis.call('hset', KEYS[8], submissionId, tostring(sequenceToIssue))
                        end
                    end

                    redis.call('sadd', KEYS[6], submissionId)
                    if streamOffset then
                        redis.call('sadd', KEYS[2], submissionId)
                        redis.call('set', KEYS[1], streamOffset)
                        return streamOffset
                    end
                    return currentOffset
                    """;

    static final RedisScript<Long> APPLY = new DefaultRedisScript<>(TEXT, Long.class);

    private ContestScoreboardRedisScript() {
    }
}
