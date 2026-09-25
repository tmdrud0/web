# Helpers only the live-impact runner uses (gatling/run-recovery-live-impact.ps1).
#
# Dot-sourced by that runner after RecoveryExperiment.ps1, and by nothing else, so the recovery pilot's
# runner loads exactly what it loaded before this file existed.
#
# Two things here are deliberately unlike the pilot, and both follow from the live-impact experiment's
# scale and its safety rules rather than from preference:
#
#   * The contest is seeded with judged results directly in MySQL. The pilot seeds none and lets the load
#     create every submission; a live-impact run needs about 10^5 results at the fault, and producing
#     them through the API would take the better part of an hour per run. The rows use Snowflake ids
#     with worker id 1023, which no role of the stack is configured with (1, 2, 100, 200, 201), so they
#     cannot collide with an id the application assigns, and they sort before every live id because
#     their timestamps are earlier.
#   * The final consistency check is a digest over each participant's (solved, penalty), without ranks.
#     The pilot's rank digest holds only while the board fits one API page (200) and user ids span less
#     than the penalty weight (1000) - see Assert-OraclePreconditions - and 10^4 participants violate
#     both. Rank order is a function of (solved, penalty, user id), so equal per-user totals on both
#     sides is the same claim without the page and tie-break conditions.

# The worker id the seeded rows carry. Checked against the stack's own configuration by the runner.
$script:liveImpactSeedWorkerId = 1023
# ContestSubmissionIdGenerator's default epoch (contest.submission.id.epoch-millis).
$script:liveImpactSnowflakeEpochMs = 1704067200000

function Get-LiveImpactSeedWorkerId {
    return $script:liveImpactSeedWorkerId
}

# Inserts `Count` judged results into the seeded contest, spread over its users and problems:
# result n goes to user (n mod U)+1 and problem ((n div U) mod P)+1, so every user has a result before
# any user has two on the same problem. `AcceptPermille` of them are ACCEPTED, decided by a hash of the
# row rather than by position so the accepted ones are not clustered.
#
# Submitted times are spread over the contest's elapsed window, and each result is judged 10 ms after its
# submission. Everything is in the database's own clock, like the contest window the seeder wrote.
function Add-LiveImpactSeedResults {
    param(
        [Parameter(Mandatory = $true)]$Seed,
        [Parameter(Mandatory = $true)][long]$Count,
        [int]$AcceptPermille = 400
    )

    $config = Get-RecoveryConfig
    if ($Count -lt 0) { throw "Seed result count must not be negative ($Count)." }
    if ($Count -eq 0) {
        return [pscustomobject][ordered]@{ Count = 0L; SubmissionRows = 0L; ResultRows = 0L }
    }
    $maxCells = [long]$Seed.UserCount * [long]$Seed.ProblemCount
    if ($Count -gt $maxCells) {
        # One result per (user, problem) cell keeps the seeded board ordinary; more would repeat cells,
        # which is allowed by the product but not what the plan's distribution describes.
        throw "Seeding $Count results needs more than $($Seed.UserCount) users x $($Seed.ProblemCount) problems = $maxCells cells."
    }
    $contestId = [long]$config.ContestId
    $prefix = $config.SeedPrefix
    $worker = [long]$script:liveImpactSeedWorkerId
    $tUser = '`user`'
    $sql = @"
SET SESSION cte_max_recursion_depth = $($Count + 100);
SET @start_ms = (SELECT CAST(FLOOR(UNIX_TIMESTAMP(start_time) * 1000) AS SIGNED) FROM contest WHERE id = $contestId);
SET @now_ms = CAST(FLOOR(UNIX_TIMESTAMP(NOW(6)) * 1000) AS SIGNED) - 1000;
SET @span_ms = GREATEST(@now_ms - @start_ms, 1);
INSERT INTO contest_submission (id, contest_id, problem_id, user_id, submitted_time, code, code_hash)
WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < $($Count - 1)),
cells AS (
    SELECT n,
           @start_ms + FLOOR(n * @span_ms / $Count) AS at_ms,
           MOD(n, $($Seed.UserCount)) + 1 AS user_index,
           MOD(FLOOR(n / $($Seed.UserCount)), $($Seed.ProblemCount)) + 1 AS problem_num
      FROM seq
)
SELECT ((c.at_ms - $script:liveImpactSnowflakeEpochMs) << 22) | ($worker << 12) | MOD(c.n, 4096),
       $contestId, p.id, u.id,
       FROM_UNIXTIME(c.at_ms / 1000),
       CONCAT('// seed ', c.n),
       SHA2(CONCAT('$prefix', 'seed_', c.n), 256)
  FROM cells c
  JOIN problem p ON p.contest_id = $contestId AND p.contest_num = c.problem_num
  JOIN $tUser u ON u.name = CONCAT('${prefix}user_', c.user_index);
INSERT INTO contest_submission_result
    (submission_id, contest_id, provisional_result, provisional_judged_at, result_saved_at)
SELECT cs.id, cs.contest_id,
       IF(MOD(CRC32(cs.code_hash), 1000) < $AcceptPermille, 'ACCEPTED', 'WRONG_ANSWER'),
       cs.submitted_time + INTERVAL 10000 MICROSECOND,
       cs.submitted_time + INTERVAL 10000 MICROSECOND
  FROM contest_submission cs
 WHERE cs.contest_id = $contestId AND ((cs.id >> 12) & 1023) = $worker;
SELECT CONCAT('SBRE_SEEDED=', COUNT(*), '|', SUM(r.submission_id IS NOT NULL))
  FROM contest_submission cs
  LEFT JOIN contest_submission_result r ON r.submission_id = cs.id
 WHERE cs.contest_id = $contestId AND ((cs.id >> 12) & 1023) = $worker;
"@
    $lines = @(Invoke-SqlScript -Sql $sql -Description "seed $Count judged result(s) into contest $contestId")
    $marker = @($lines | Where-Object { [string]$_ -match '^SBRE_SEEDED=' } | Select-Object -Last 1)
    if ($marker.Count -ne 1) {
        throw "Seeding results did not report its counts: $($lines -join ' | ')"
    }
    $parts = @(([string]$marker[0]).Substring("SBRE_SEEDED=".Length) -split '\|')
    $submissions = [long]$parts[0]
    $results = [long]$parts[1]
    if ($submissions -ne $Count -or $results -ne $Count) {
        throw "Asked for $Count seeded result(s); contest $contestId holds $submissions submission(s) and $results result(s) from worker $worker."
    }
    return [pscustomobject][ordered]@{
        Count = $Count
        SubmissionRows = $submissions
        ResultRows = $results
        WorkerId = $worker
        AcceptPermille = $AcceptPermille
    }
}

# Asks the batch role to rebuild one contest's scoreboard from MySQL (the product's own actuator
# operation, ContestScoreboardRebuildEndpoint). Sent from inside the Redis container, which is on the
# stack's network and carries busybox wget; the body is written by the script rather than passed as an
# argument, because Windows PowerShell 5.1 does not escape double quotes for native commands.
$script:liveImpactRebuildScript = @'
contestId="$1"
status=0
wget -q -T 900 -O - --header "Content-Type: application/json" \
     --post-data "{\"contestId\":$contestId}" http://batch-1:9000/actuator/contestscoreboard || status=$?
echo
echo "SBRE_REBUILD_EXIT=$status"
'@

function Invoke-LiveImpactScoreboardRebuild {
    $config = Get-RecoveryConfig
    if (-not $config.ContestScopeFromSeed) {
        throw "Refusing a rebuild before a seed has set the contest scope."
    }
    $output = Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:liveImpactRebuildScript `
        -Description "scoreboard rebuild of contest $($config.ContestId)" -ScriptArguments @([string]$config.ContestId)
    if (-not (@($output) -match '"status"\s*:\s*"rebuilt"')) {
        throw "The rebuild of contest $($config.ContestId) did not answer 'rebuilt': $($output -join ' | ')"
    }
    return ($output -join ' ')
}

# The offset that moves a Windows-clock instant into the container frame: containerMs = windowsMs +
# offset. The container instant is MySQL's NOW(6), read through `docker exec`, and the Windows instant is
# the midpoint of the round trip; the sample with the shortest round trip is kept, and half of it is
# the offset's uncertainty. Redis's TIME is read the same way as a cross-check that MySQL and Redis
# share one clock - they are both containers on the same Docker VM.
function Measure-LiveImpactClockOffset {
    param([int]$Samples = 7)

    $best = $null
    for ($i = 0; $i -lt $Samples; $i++) {
        $before = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $mysqlMs = Invoke-SqlInt64 -Sql "SELECT CAST(FLOOR(UNIX_TIMESTAMP(NOW(6)) * 1000) AS SIGNED);" -Description "database clock in epoch ms"
        $after = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $rtt = $after - $before
        if ($null -eq $best -or $rtt -lt $best.RoundTripMs) {
            $best = [pscustomobject][ordered]@{
                OffsetMs = $mysqlMs - [long][math]::Floor(($before + $after) / 2.0)
                RoundTripMs = $rtt
            }
        }
    }
    $redisBest = $null
    for ($i = 0; $i -lt $Samples; $i++) {
        $before = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $time = @(Invoke-RedisText -RedisArguments @("TIME")) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
        $after = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $redisMs = ConvertFrom-RedisTimePair -Text (($time | Select-Object -First 2) -join ' ')
        $rtt = $after - $before
        if ($null -eq $redisBest -or $rtt -lt $redisBest.RoundTripMs) {
            $redisBest = [pscustomobject][ordered]@{
                OffsetMs = $redisMs - [long][math]::Floor(($before + $after) / 2.0)
                RoundTripMs = $rtt
            }
        }
    }
    return [pscustomobject][ordered]@{
        OffsetMs = $best.OffsetMs
        UncertaintyMs = [long][math]::Ceiling($best.RoundTripMs / 2.0)
        MySqlRoundTripMs = $best.RoundTripMs
        RedisOffsetMs = $redisBest.OffsetMs
        RedisRoundTripMs = $redisBest.RoundTripMs
        MySqlMinusRedisMs = $best.OffsetMs - $redisBest.OffsetMs
    }
}

function ConvertTo-ContainerMs {
    param(
        [Parameter(Mandatory = $true)][DateTimeOffset]$WindowsInstant,
        [Parameter(Mandatory = $true)]$Clock
    )

    return $WindowsInstant.ToUnixTimeMilliseconds() + [long]$Clock.OffsetMs
}

# One row per judged result of the contest, in epoch ms read as UTC (the frame the trace's
# judgedAtEpochMsUtc uses), and whether the seeder wrote it.
function Export-LiveImpactJudged {
    param([Parameter(Mandatory = $true)][string]$Path)

    $config = Get-RecoveryConfig
    $worker = [long]$script:liveImpactSeedWorkerId
    $lines = @(Invoke-SqlScript -Sql @"
SET time_zone = '+00:00';
SELECT r.submission_id,
       CAST(FLOOR(UNIX_TIMESTAMP(COALESCE(r.final_judged_at, r.provisional_judged_at)) * 1000) AS SIGNED),
       IF(((r.submission_id >> 12) & 1023) = $worker, 1, 0)
  FROM contest_submission_result r
 WHERE r.contest_id = $($config.ContestId)
   AND COALESCE(r.final_result, r.provisional_result) <> 'PENDING';
"@ -Description "judged results of contest $($config.ContestId)")
    $builder = New-Object Text.StringBuilder
    [void]$builder.Append("submissionId,judgedAtEpochMs,seed`n")
    $rows = 0L
    foreach ($line in $lines) {
        $text = [string]$line
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        $fields = $text -split "`t", -1
        if ($fields.Length -lt 3) { continue }
        $judgedAt = if ($fields[1] -eq "NULL") { "" } else { $fields[1] }
        [void]$builder.Append($fields[0]).Append(',').Append($judgedAt).Append(',').Append($fields[2]).Append("`n")
        $rows++
    }
    [IO.File]::WriteAllText($Path, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))
    return $rows
}

# Judged results (seeded and live) with a judged time at or before an instant in the container frame,
# and the rows full-replay's replayAllContests would scan at that moment across every contest.
function Get-LiveImpactResultCounts {
    param([Parameter(Mandatory = $true)][long]$AtContainerMs)

    $config = Get-RecoveryConfig
    $row = @(Invoke-SqlRows -Sql @"
SET time_zone = '+00:00';
SELECT (SELECT COUNT(*) FROM contest_submission_result
         WHERE contest_id = $($config.ContestId)
           AND COALESCE(final_result, provisional_result) <> 'PENDING'
           AND COALESCE(final_judged_at, provisional_judged_at) <= FROM_UNIXTIME($AtContainerMs / 1000)),
       (SELECT COUNT(*) FROM contest_submission_result
         WHERE COALESCE(final_result, provisional_result) <> 'PENDING'),
       (SELECT COUNT(DISTINCT contest_id) FROM contest_submission_result);
"@ -Description "result counts at the fault")[0]
    return [pscustomobject][ordered]@{
        N = ConvertTo-RequiredInt64 -Value $row[0] -Description "judged results of the contest at the instant"
        NTotalNow = ConvertTo-RequiredInt64 -Value $row[1] -Description "judged results of every contest"
        ContestsWithResults = ConvertTo-RequiredInt64 -Value $row[2] -Description "contests with results"
    }
}

# A digest over (userId, solved, penalty), sorted by user id. No ranks, so no page or tie-break
# condition - see the header of this file.
function Get-UserTotalsDigest {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Standings)

    $sorted = @($Standings | Sort-Object -Property UserId)
    $canonical = New-Object Text.StringBuilder
    foreach ($entry in $sorted) {
        [void]$canonical.Append("$($entry.UserId)|$($entry.Solved)|$($entry.Penalty)`n")
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($canonical.ToString())
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
    return [pscustomobject]@{ Digest = $digest; Participants = $sorted.Count }
}

# The scoreboard API against every judged result MySQL holds for the contest. Called once, after the
# drain - never during the measured window, where its reads would be part of what is measured.
function Compare-LiveImpactUserTotals {
    $api = Get-ApiScoreboardDigest
    $oracle = @(Get-OracleStandings -AllResolvedResults)
    $apiTotals = Get-UserTotalsDigest -Standings @($api.Standings)
    $oracleTotals = Get-UserTotalsDigest -Standings $oracle
    $difference = $null
    if ($apiTotals.Digest -ne $oracleTotals.Digest) {
        $difference = Get-StandingsDifference -Api @($api.Standings) -Oracle $oracle
    }
    return [pscustomobject][ordered]@{
        Matches = ($apiTotals.Digest -eq $oracleTotals.Digest)
        ApiDigest = $apiTotals.Digest
        OracleDigest = $oracleTotals.Digest
        ApiParticipants = $apiTotals.Participants
        OracleParticipants = $oracleTotals.Participants
        Difference = $difference
    }
}

# CPU and memory limits of every container the run measures, as Docker reports them (0 = unlimited).
function Get-LiveImpactContainerLimits {
    $limits = [ordered]@{}
    foreach ($name in @("oj-loadtest-web-1", "oj-loadtest-web-2", "oj-loadtest-batch-1", "oj-loadtest-judge-1",
            "oj-loadtest-judge-2", "oj-loadtest-redis", "oj-loadtest-rabbitmq", "oj-loadtest-nginx", (Get-RecoveryConfig).DbContainer)) {
        try {
            $json = (Invoke-Docker -Arguments @("inspect", "--format", "{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}", $name)) -join " "
            $parts = @(([string]$json).Trim() -split '\s+')
            $limits[$name] = [pscustomobject][ordered]@{
                cpus = if ([long]$parts[0] -gt 0) { [double]$parts[0] / 1e9 } else { "unlimited" }
                memoryBytes = if ([long]$parts[1] -gt 0) { [long]$parts[1] } else { "unlimited" }
            }
        }
        catch {
            $limits[$name] = "unavailable"
        }
    }
    return $limits
}

# The counters read at the two ends of the window the recovery happens in. Each source is optional:
# one that cannot be read is recorded as unavailable rather than failing the run or reading as zero.
function Get-LiveImpactCounters {
    $counters = [ordered]@{ observedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o") }
    try {
        $mysql = Get-MySqlStatusCounters
        $counters["mysql.Innodb_rows_read"] = $mysql["Innodb_rows_read"]
        $counters["mysql.Com_select"] = $mysql["Com_select"]
        $counters["mysql.Com_update"] = $mysql["Com_update"]
    }
    catch { $counters["mysql"] = "unavailable" }
    try {
        $stats = Get-RedisCommandStats -Info (Get-RedisInfoSection -Section "commandstats" -Description "command stats")
        $total = 0L
        foreach ($name in $stats.Keys) {
            $total += [long]$stats[$name]["calls"]
        }
        $counters["redis.calls"] = $total
        foreach ($name in @("eval", "evalsha", "copy", "sismember", "smismember", "sintercard", "smembers")) {
            $counters["redis.calls.$name"] = if ($stats.Contains($name)) { [long]$stats[$name]["calls"] } else { 0L }
        }
    }
    catch { $counters["redis"] = "unavailable" }
    try {
        $counters["batch.gcPauseSecondsSum"] = Get-PrometheusScalar -Query 'sum(jvm_gc_pause_seconds_sum{node="batch-1"})' -Description "batch GC pause sum"
        $counters["batch.gcPauseCount"] = Get-PrometheusScalar -Query 'sum(jvm_gc_pause_seconds_count{node="batch-1"})' -Description "batch GC pause count"
        $counters["batch.gcPauseMaxSeconds"] = Get-PrometheusScalar -Query 'max(jvm_gc_pause_seconds_max{node="batch-1"})' -Description "batch GC pause max"
    }
    catch { $counters["batch.gc"] = "unavailable" }
    return $counters
}

# The difference between two counter readings, per key present in both, as text; anything missing on
# either side is `unavailable`.
function Get-LiveImpactCounterDelta {
    param(
        [Parameter(Mandatory = $true)]$Before,
        [Parameter(Mandatory = $true)]$After
    )

    $delta = [ordered]@{}
    foreach ($key in $After.Keys) {
        if ($key -eq "observedAtUtc") { continue }
        if (-not $Before.Contains($key)) { $delta[$key] = "unavailable"; continue }
        $a = $After[$key]
        $b = $Before[$key]
        if ($a -is [string] -or $b -is [string]) { $delta[$key] = "unavailable"; continue }
        if ($key -eq "batch.gcPauseMaxSeconds") {
            # A gauge over a rolling window, not a counter: the later reading is the figure.
            $delta[$key] = Format-InvariantNumber -Value ([double]$a)
            continue
        }
        $delta[$key] = Format-InvariantNumber -Value ([double]$a - [double]$b)
    }
    return $delta
}
