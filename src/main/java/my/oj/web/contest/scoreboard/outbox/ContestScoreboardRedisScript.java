package my.oj.web.contest.scoreboard.outbox;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

final class ContestScoreboardRedisScript {

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

                    local contestMinutes = tonumber(ARGV[4])
                    local wrongPenalty = tonumber(ARGV[5])
                    local solvedWeight = tonumber(ARGV[6])
                    local penaltyWeight = tonumber(ARGV[7])
                    local userId = tonumber(ARGV[8])
                    if not contestMinutes or not wrongPenalty or not solvedWeight or not penaltyWeight or not userId then
                        return redis.error_reply('Invalid scoreboard numeric argument')
                    end

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

                    local alreadyProcessed = redis.call('sismember', KEYS[6], ARGV[2])
                    if alreadyProcessed == 1 then
                        if not existingSequence then
                            return redis.error_reply('Processed scoreboard event has no sequence mapping')
                        end
                        return tonumber(existingSequence)
                    end

                    local initialized = redis.call('hget', KEYS[4], 'initialized')
                    local accepted = redis.call('hget', KEYS[5], 'accepted')
                    if initialized and initialized ~= '1' then
                        return redis.error_reply('Invalid scoreboard initialized flag')
                    end
                    if accepted and accepted ~= '0' and accepted ~= '1' then
                        return redis.error_reply('Invalid scoreboard accepted flag')
                    end
                    local wrongAttempts = parseInteger(
                            redis.call('hget', KEYS[5], 'wrongAttempts'),
                            'wrongAttempts')
                    local currentSolved = parseInteger(
                            redis.call('hget', KEYS[4], 'solved'),
                            'solved')
                    local currentPenalty = parseInteger(
                            redis.call('hget', KEYS[4], 'penalty'),
                            'penalty')

                    local sequence = existingSequence
                    if not sequence then
                        sequence = redis.call('incr', KEYS[1])
                        redis.call('hset', KEYS[2], ARGV[1], sequence)
                    end

                    if ARGV[3] ~= 'PENDING' then
                        if not initialized then
                            redis.call('hset', KEYS[4],
                                    'solved', '0',
                                    'penalty', '0',
                                    'initialized', '1')
                            redis.call('zadd', KEYS[3], -userId, ARGV[8])
                        end

                        if accepted ~= '1' then
                            if ARGV[3] == 'ACCEPTED' then
                                local penaltyIncrement = contestMinutes + wrongAttempts * wrongPenalty
                                redis.call('hset', KEYS[5],
                                        'accepted', '1',
                                        'wrongAttempts', tostring(wrongAttempts))
                                local solved = redis.call('hincrby', KEYS[4], 'solved', 1)
                                local penalty = redis.call('hincrby', KEYS[4], 'penalty', penaltyIncrement)
                                local score = solved * solvedWeight - penalty * penaltyWeight - userId
                                redis.call('zadd', KEYS[3], score, ARGV[8])
                            else
                                redis.call('hset', KEYS[5], 'accepted', '0')
                                redis.call('hincrby', KEYS[5], 'wrongAttempts', 1)
                                local score = currentSolved * solvedWeight - currentPenalty * penaltyWeight - userId
                                redis.call('zadd', KEYS[3], score, ARGV[8])
                            end
                        end
                    end

                    redis.call('sadd', KEYS[6], ARGV[2])
                    return tonumber(sequence)
                    """;

    static final RedisScript<Long> APPLY = new DefaultRedisScript<>(TEXT, Long.class);

    private ContestScoreboardRedisScript() {
    }
}
