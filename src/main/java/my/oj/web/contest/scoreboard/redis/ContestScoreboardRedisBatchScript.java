package my.oj.web.contest.scoreboard.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * {@link ContestScoreboardRedisScript} for a whole chunk of events in one {@code EVAL}.
 *
 * <p>Every event is applied with exactly the rules of the single-event script - the same dedupe on
 * the processed set, the same checkpoint claim, the same refusal of a claimless forward step, the same
 * sequence issue - so a chunk here and the same events sent one {@code EVAL} each leave the same
 * standings, checkpoint, processed set and sequence mapping behind. What changes is the number of
 * round trips, and two things the single script could not say:</p>
 *
 * <ul>
 *   <li><b>Stop at the first failure.</b> Events are applied in order and the first one that fails
 *       ends the chunk; nothing after it is attempted. A pipeline of single-event calls would keep
 *       going after a failed one, and a later event could then move the checkpoint over the failure.
 *       Each event runs under {@code pcall}, so an error raised half way through an event (a key of the
 *       wrong type, a corrupt field) is reported as that event's failure instead of aborting the chunk
 *       with the earlier events' results unknown.</li>
 *   <li><b>Checkpoint CAS.</b> {@code ARGV[2]} is the checkpoint the caller already observed or wrote for
 *       its consumer position. When it is not {@code -1} and the stored checkpoint is below it, Redis has
 *       been rolled back underneath the caller - a snapshot restore, a failover to a replica that had not
 *       caught up - and the chunk writes nothing and answers {@code ROLLBACK}. The check runs in the same
 *       script invocation as the first write, so no rollback can land between the two.</li>
 * </ul>
 *
 * <h2>Keys</h2>
 *
 * <p>Four global keys, then four keys per event at a fixed stride, so an event's keys are found by its
 * index rather than by a lookup table:</p>
 * <pre>
 *   KEYS[1] stream offset   KEYS[2] db-pending set   KEYS[3] sequence allocator   KEYS[4] submission-seq
 *   KEYS[4 + 4(i-1) + 1..4] ranking, summary, problem, processed   of event i
 * </pre>
 * <p>The keys are passed rather than built in the script so the script touches only keys it was given.
 * This deployment runs a single Redis node, not Redis Cluster: the single-event script already mixed
 * global and per-contest keys in one call, which Cluster would refuse as a cross-slot script, and this
 * one does the same.</p>
 *
 * <h2>Arguments</h2>
 * <pre>
 *   ARGV[1] event count      ARGV[2] expected checkpoint floor, -1 for none
 *   ARGV[3] track sequence (0|1)   ARGV[4] record db-pending (0|1)
 *   ARGV[5] wrong penalty    ARGV[6] solved weight   ARGV[7] penalty weight
 *   ARGV[7 + 6(i-1) + 1..6]  stream offset ('' for a rebuild), claim token, submission id, result,
 *                            contest minutes, user id   of event i
 * </pre>
 *
 * <h2>Reply</h2>
 *
 * <p>One entry per attempted event: {@code {status, checkpointAfter, sequence}}, with a fourth element,
 * the error text, on a failure. {@code sequence} is {@code -1} when none was issued. A chunk that stops
 * at a failure answers for the events before it and for the failed one only. A {@code ROLLBACK} is a
 * single entry carrying the checkpoint that was found.</p>
 *
 * <p>The checkpoint is written once, at the end, to the offset of the last event that moved it. Nothing
 * reads it in between but this script, which tracks it in a local, so the one {@code SET} is equivalent
 * to the single script's {@code SET} per event.</p>
 *
 * <p>With {@code ARGV[4]} at {@code 0} the db-pending set is never written - that set exists only so
 * the MySQL {@code scoreboard_applied_at} completion can be repaired, and a deployment that does not
 * track applied-at has nothing to repair. The set is not read here either way, so a set left over from
 * before the switch changes nothing.</p>
 */
final class ContestScoreboardRedisBatchScript {

    static final long APPLIED = 1L;
    static final long DUPLICATE = 2L;
    static final long FAILED = 3L;
    static final long ROLLBACK = 4L;

    static final int GLOBAL_KEYS = 4;
    static final int KEYS_PER_EVENT = 4;
    static final int HEADER_ARGS = 7;
    static final int ARGS_PER_EVENT = 6;

    static final String TEXT = ContestScoreboardRedisScript.HELPERS + ContestScoreboardRedisScript.SCORING + """
                    local APPLIED, DUPLICATE, FAILED, ROLLBACK = 1, 2, 3, 4
                    local GLOBAL_KEYS, KEYS_PER_EVENT, HEADER_ARGS, ARGS_PER_EVENT = 4, 4, 7, 6

                    local function errorText(value)
                        if type(value) == 'table' then
                            return tostring(value.err or value.message or 'Lua error')
                        end
                        return tostring(value)
                    end

                    local count = tonumber(ARGV[1])
                    if not count or count < 1 or count ~= math.floor(count)
                            or #KEYS ~= GLOBAL_KEYS + count * KEYS_PER_EVENT
                            or #ARGV ~= HEADER_ARGS + count * ARGS_PER_EVENT then
                        return redis.error_reply('Invalid scoreboard batch shape')
                    end
                    local floor = tonumber(ARGV[2])
                    local wrongPenalty = tonumber(ARGV[5])
                    local solvedWeight = tonumber(ARGV[6])
                    local penaltyWeight = tonumber(ARGV[7])
                    if not floor or not wrongPenalty or not solvedWeight or not penaltyWeight then
                        return redis.error_reply('Invalid scoreboard numeric argument')
                    end
                    if ARGV[3] ~= '0' and ARGV[3] ~= '1' then
                        return redis.error_reply('Invalid scoreboard sequence tracking flag')
                    end
                    if ARGV[4] ~= '0' and ARGV[4] ~= '1' then
                        return redis.error_reply('Invalid scoreboard db-pending tracking flag')
                    end
                    local trackSequence = ARGV[3] == '1'
                    local trackDbPending = ARGV[4] == '1'

                    local currentOffset = -1
                    local ok, failure = pcall(function()
                        assertKeyType(KEYS[1], 'string')
                        assertKeyType(KEYS[2], 'set')
                        if trackSequence then
                            assertKeyType(KEYS[3], 'string')
                            assertKeyType(KEYS[4], 'hash')
                        end
                        local stored = redis.call('get', KEYS[1])
                        if stored then
                            currentOffset = parseInteger(stored, 'streamOffset')
                        end
                        if currentOffset < -1 then
                            error('Invalid negative scoreboard stream offset')
                        end
                    end)
                    if not ok then
                        return {{FAILED, -1, -1, errorText(failure)}}
                    end

                    -- The CAS. Checked before the first event, in this same invocation: a stored
                    -- checkpoint below what the caller already saw for its position means Redis was
                    -- rolled back underneath it, and applying now would carry the checkpoint over the
                    -- range the rollback took away. Nothing is written.
                    if floor >= 0 and currentOffset < floor then
                        return {{ROLLBACK, currentOffset, -1}}
                    end

                    local offsetMoved = false

                    -- One event, with the single-event script's rules. Returns status, checkpoint after,
                    -- issued sequence (or nil), error text (failures only).
                    local function applyEvent(k, a)
                        local streamArgument = ARGV[a + 1]
                        local claim = ARGV[a + 2]
                        local submissionId = ARGV[a + 3]
                        local result = ARGV[a + 4]
                        local contestMinutes = tonumber(ARGV[a + 5])
                        local userId = tonumber(ARGV[a + 6])
                        if not contestMinutes or not userId then
                            return FAILED, currentOffset, nil, 'Invalid scoreboard numeric argument'
                        end
                        if not string.match(submissionId, '^%d+$') then
                            return FAILED, currentOffset, nil, 'Invalid scoreboard submission id argument'
                        end
                        local rankingKey = KEYS[k + 1]
                        local summaryKey = KEYS[k + 2]
                        local problemKey = KEYS[k + 3]
                        local processedKey = KEYS[k + 4]
                        assertKeyType(rankingKey, 'zset')
                        assertKeyType(summaryKey, 'hash')
                        assertKeyType(problemKey, 'hash')
                        assertKeyType(processedKey, 'set')

                        local streamOffset = nil
                        if streamArgument ~= '' then
                            streamOffset = parseInteger(streamArgument, 'incomingStreamOffset')
                            if streamOffset < 0 then
                                return FAILED, currentOffset, nil,
                                        'Invalid negative incoming scoreboard stream offset'
                            end
                            if streamOffset <= currentOffset then
                                return DUPLICATE, currentOffset, nil
                            end
                            if claim ~= 'continue' and claim ~= 'anchor' then
                                return FAILED, currentOffset, nil,
                                        'Unauthorized scoreboard stream offset advance: '
                                        .. 'a forward step must declare the checkpoint claim it verified, '
                                        .. 'but received "' .. tostring(claim) .. '"'
                            end
                        elseif claim ~= '' then
                            return FAILED, currentOffset, nil, 'A rebuild request cannot carry a checkpoint claim'
                        end

                        if redis.call('sismember', processedKey, submissionId) == 1 then
                            if streamOffset then
                                if trackDbPending then
                                    redis.call('sadd', KEYS[2], submissionId)
                                end
                                currentOffset = streamOffset
                                offsetMoved = true
                            end
                            return DUPLICATE, currentOffset, nil
                        end

                        local state, stateError = readScoringState(summaryKey, problemKey)
                        if stateError then
                            return FAILED, currentOffset, nil, stateError
                        end

                        local issued = nil
                        if result ~= 'PENDING' then
                            -- Resolved before the first write, as in the single-event script: a failure
                            -- after writing would leave the standings moved with the event reported failed.
                            local sequenceToIssue = nil
                            if trackSequence then
                                local allocator = parseInteger(redis.call('get', KEYS[3]), 'allocatorSequence')
                                if allocator < 0 then
                                    return FAILED, currentOffset, nil,
                                            'Invalid negative scoreboard allocator sequence'
                                end
                                local mappedSequence = nil
                                local mapped = redis.call('hget', KEYS[4], submissionId)
                                if mapped then
                                    if not string.match(mapped, '^%d+$') then
                                        return FAILED, currentOffset, nil, 'Invalid scoreboard submission sequence'
                                    end
                                    mappedSequence = tonumber(mapped)
                                end
                                sequenceToIssue = allocator + 1
                                if mappedSequence and mappedSequence >= sequenceToIssue then
                                    sequenceToIssue = mappedSequence + 1
                                end
                            end

                            writeJudgement(state, rankingKey, summaryKey, problemKey, result, contestMinutes,
                                    submissionId, wrongPenalty, solvedWeight, penaltyWeight, userId, ARGV[a + 6])

                            if sequenceToIssue then
                                redis.call('set', KEYS[3], tostring(sequenceToIssue))
                                redis.call('hset', KEYS[4], submissionId, tostring(sequenceToIssue))
                                issued = sequenceToIssue
                            end
                        end

                        redis.call('sadd', processedKey, submissionId)
                        if streamOffset then
                            if trackDbPending then
                                redis.call('sadd', KEYS[2], submissionId)
                            end
                            currentOffset = streamOffset
                            offsetMoved = true
                        end
                        return APPLIED, currentOffset, issued
                    end

                    local results = {}
                    for i = 1, count do
                        local called, status, offset, sequence, message = pcall(
                                applyEvent,
                                GLOBAL_KEYS + (i - 1) * KEYS_PER_EVENT,
                                HEADER_ARGS + (i - 1) * ARGS_PER_EVENT)
                        if not called then
                            results[i] = {FAILED, currentOffset, -1, errorText(status)}
                            break
                        end
                        if status == FAILED then
                            results[i] = {FAILED, currentOffset, -1, message or 'Scoreboard event failed'}
                            break
                        end
                        results[i] = {status, offset, sequence or -1}
                    end

                    if offsetMoved then
                        redis.call('set', KEYS[1], currentOffset)
                    end
                    return results
                    """;

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> APPLY = new DefaultRedisScript<>(TEXT, List.class);

    private ContestScoreboardRedisBatchScript() {
    }
}
