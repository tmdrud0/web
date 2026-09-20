# The consistency oracle: what the scoreboard must say, computed from MySQL rather than read from
# Redis.
#
# The question this answers is not "is the scoreboard the same as MySQL". MySQL holds every judged
# result, the scoreboard holds the ones the pipeline has applied, and while new results are arriving
# the two sets differ by what is in flight - by design, at every instant, in every mode. Asking the
# cruder question would make the answer depend on how fast results happened to be arriving rather than
# on whether recovery worked.
#
# So the oracle is restricted to the results MySQL says were applied, and asks a question that has an
# exact answer at any instant: *given the set of results the scoreboard claims to hold, does it hold
# them correctly?* The boundary is `contest_submission_result.scoreboard_applied_at`, which is written
# by the applying path after Redis has taken the result, never before - so MySQL's claim can lag
# Redis but can never run ahead of it. A disagreement is therefore always a real one: a scoreboard
# that is missing something it was marked for, or has credited something the marker does not cover.
#
# That restriction is what makes the same comparison answer the recovery question as well. A rollback
# does not clear `scoreboard_applied_at` - the column records the first application and is written
# with COALESCE, so it never changes afterwards - so the lost results stay inside the oracle's set and
# the scoreboard has to put them back before the two digests can agree again.
#
# What this does not catch, and is measured separately instead: results that MySQL has judged and the
# pipeline has not yet applied at all. That is lag, not corruption, and the harness reads it as a lag
# figure (`Get-ResultCounts`) rather than folding it into the consistency verdict.

# One implementation of the canonical form, used by both sides. Two implementations of a format are
# free to disagree, and a disagreement would look exactly like a scoreboard fault.
function New-StandingsDigest {
    param(
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)][long]$Participants,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Standings
    )

    $canonical = New-Object Text.StringBuilder
    [void]$canonical.Append("contestId=$ContestId`nparticipants=$Participants`n")
    foreach ($entry in $Standings) {
        [void]$canonical.Append("$($entry.Rank)|$($entry.UserId)|$($entry.Solved)|$($entry.Penalty)`n")
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($canonical.ToString())
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
    return [pscustomobject]@{
        Digest = $digest
        Participants = $Participants
        Entries = $Standings.Count
        CanonicalBytes = $bytes.Length
    }
}

# Competition rank over (solved, penalty), which is the order the standings arrive in: entries tied on
# both share the rank of the first of them and the ranks the tie consumes are skipped. The API
# computes this same rule per page; the oracle computes it over the whole board, so the two agree only
# because the whole board is read as one page - see Assert-OraclePreconditions.
function Add-CompetitionRanks {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Standings)

    $ranked = New-Object 'System.Collections.Generic.List[object]'
    $displayRank = 0
    $previousSolved = [int]::MinValue
    $previousPenalty = [long]::MinValue
    for ($index = 0; $index -lt $Standings.Count; $index++) {
        $entry = $Standings[$index]
        if ($entry.Solved -ne $previousSolved -or $entry.Penalty -ne $previousPenalty) {
            $displayRank = $index + 1
            $previousSolved = $entry.Solved
            $previousPenalty = $entry.Penalty
        }
        $ranked.Add([pscustomobject][ordered]@{
                Rank = [long]$displayRank
                UserId = [long]$entry.UserId
                Solved = [int]$entry.Solved
                Penalty = [long]$entry.Penalty
            })
    }
    return $ranked.ToArray()
}

# The scoreboard as the product serves it, read until every participant has been seen.
function Get-ApiScoreboardDigest {
    param([int]$PageSize = 200)

    $config = Get-RecoveryConfig
    $startRank = 1L
    $totalParticipants = $null
    $standings = New-Object 'System.Collections.Generic.List[object]'
    $pages = 0

    while ($null -eq $totalParticipants -or $startRank -le $totalParticipants) {
        $uri = "$($config.BaseUrl)/api/contests/$($config.ContestId)/scoreboard?startRank=$startRank&size=$PageSize"
        try {
            $page = Invoke-RestMethod -Uri $uri -TimeoutSec 15
        }
        catch {
            throw "Could not read scoreboard page at startRank=$startRank`: $($_.Exception.Message)"
        }
        if ($null -eq $page) {
            throw "Scoreboard API returned no body at startRank=$startRank."
        }
        $pageContestId = ConvertTo-RequiredInt64 -Value $page.contestId -Description "scoreboard contestId"
        $pageStartRank = ConvertTo-RequiredInt64 -Value $page.startRank -Description "scoreboard startRank"
        $pageTotal = ConvertTo-RequiredInt64 -Value $page.totalParticipants -Description "scoreboard totalParticipants"
        if ($pageContestId -ne $config.ContestId -or $pageStartRank -ne $startRank) {
            throw "Scoreboard page identity mismatch: contest=$pageContestId, startRank=$pageStartRank."
        }
        if ($null -eq $totalParticipants) {
            $totalParticipants = $pageTotal
        }
        elseif ($pageTotal -ne $totalParticipants) {
            throw "Scoreboard participant count changed during one digest ($totalParticipants -> $pageTotal)."
        }

        $entries = @($page.entries)
        $expected = [int][math]::Max(0L, [math]::Min([long]$PageSize, $totalParticipants - $startRank + 1L))
        if ($entries.Count -ne $expected) {
            throw "Scoreboard page at rank $startRank returned $($entries.Count) entries; expected $expected."
        }
        foreach ($entry in $entries) {
            $standings.Add([pscustomobject][ordered]@{
                    Rank = ConvertTo-RequiredInt64 -Value $entry.rank -Description "scoreboard entry rank"
                    UserId = ConvertTo-RequiredInt64 -Value $entry.userId -Description "scoreboard entry userId"
                    Solved = [int](ConvertTo-RequiredInt64 -Value $entry.solvedCount -Description "scoreboard entry solvedCount")
                    Penalty = ConvertTo-RequiredInt64 -Value $entry.penalty -Description "scoreboard entry penalty"
                })
        }
        $pages++
        if ($entries.Count -eq 0) { break }
        $startRank += $entries.Count
    }

    $digest = New-StandingsDigest -ContestId $config.ContestId -Participants $totalParticipants -Standings $standings.ToArray()
    return [pscustomobject]@{
        Digest = $digest.Digest
        Participants = $digest.Participants
        Entries = $digest.Entries
        Pages = $pages
        CanonicalBytes = $digest.CanonicalBytes
        Standings = $standings.ToArray()
    }
}

# The statement the standings are computed from, kept in one piece so its restriction is visible: only
# results MySQL says were applied, and never a PENDING one - the scoreboard's write path skips a
# PENDING result entirely, so counting one as a wrong attempt would make the oracle disagree with a
# correct scoreboard.
function Get-OracleStandingsSql {
    param([switch]$AllResolvedResults)

    $config = Get-RecoveryConfig
    $boundary = if ($AllResolvedResults) { "1 = 1" } else { "r.scoreboard_applied_at IS NOT NULL" }
    return @"
WITH resolved AS (
    SELECT cs.user_id AS user_id,
           cs.problem_id AS problem_id,
           cs.id AS submission_id,
           GREATEST(CEIL(TIMESTAMPDIFF(SECOND, c.start_time, cs.submitted_time) / 60), 0) AS minutes,
           CASE WHEN COALESCE(r.final_result, r.provisional_result) = 'ACCEPTED' THEN 1 ELSE 0 END AS is_accepted
      FROM contest_submission cs
      JOIN contest c ON c.id = cs.contest_id
      JOIN contest_submission_result r ON r.submission_id = cs.id
     WHERE cs.contest_id = $($config.ContestId)
       AND COALESCE(r.final_result, r.provisional_result) <> 'PENDING'
       AND $boundary
),
earliest_accepted AS (
    SELECT user_id, problem_id, minutes AS accepted_minutes, submission_id AS accepted_submission_id
      FROM (SELECT user_id, problem_id, minutes, submission_id,
                   ROW_NUMBER() OVER (PARTITION BY user_id, problem_id
                                      ORDER BY minutes, submission_id) AS rn
              FROM resolved
             WHERE is_accepted = 1) ranked
     WHERE rn = 1
),
user_problem AS (
    SELECT r.user_id, r.problem_id, ea.accepted_minutes,
           SUM(CASE WHEN r.is_accepted = 0
                     AND (r.minutes < ea.accepted_minutes
                          OR (r.minutes = ea.accepted_minutes
                              AND r.submission_id < ea.accepted_submission_id))
                    THEN 1 ELSE 0 END) AS wrong_before
      FROM resolved r
      JOIN earliest_accepted ea ON ea.user_id = r.user_id AND ea.problem_id = r.problem_id
     GROUP BY r.user_id, r.problem_id, ea.accepted_minutes
),
totals AS (
    SELECT user_id,
           COUNT(*) AS solved,
           SUM(accepted_minutes + wrong_before * 5) AS penalty,
           COUNT(*) * 1000000000 - SUM(accepted_minutes + wrong_before * 5) * 1000 - user_id AS score
      FROM user_problem
     GROUP BY user_id
)
SELECT user_id, solved, penalty
  FROM totals
 ORDER BY score DESC, user_id ASC;
"@
}

function Get-OracleStandings {
    param([switch]$AllResolvedResults)

    $rows = @(Invoke-SqlRows -Sql (Get-OracleStandingsSql -AllResolvedResults:$AllResolvedResults) -Description "oracle standings")
    $standings = New-Object 'System.Collections.Generic.List[object]'
    foreach ($row in $rows) {
        if ($row.Count -lt 3) {
            throw "Oracle standings row has $($row.Count) columns, expected 3: '$($row -join '|')'."
        }
        $standings.Add([pscustomobject][ordered]@{
                UserId = ConvertTo-RequiredInt64 -Value $row[0] -Description "oracle userId"
                Solved = [int](ConvertTo-RequiredInt64 -Value $row[1] -Description "oracle solved")
                Penalty = ConvertTo-RequiredInt64 -Value $row[2] -Description "oracle penalty"
            })
    }
    return Add-CompetitionRanks -Standings $standings.ToArray()
}

function Get-OracleDigest {
    param([switch]$AllResolvedResults)

    $config = Get-RecoveryConfig
    $standings = @(Get-OracleStandings -AllResolvedResults:$AllResolvedResults)
    $digest = New-StandingsDigest -ContestId $config.ContestId -Participants $standings.Count -Standings $standings
    return [pscustomobject]@{
        Digest = $digest.Digest
        Participants = $digest.Participants
        Entries = $digest.Entries
        CanonicalBytes = $digest.CanonicalBytes
        Standings = $standings
    }
}

# --- preconditions ---------------------------------------------------------------------------------

# Conditions the comparison rests on. Each is a property of the data rather than of the harness, so
# each is checked before a run is allowed to produce numbers: a run whose contest violates one of
# these would compute a digest that cannot be compared with the scoreboard's, and the honest outcome
# is a stopped run rather than a figure with an asterisk.
function Assert-OraclePreconditions {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $config = Get-RecoveryConfig
    $observed = [ordered]@{}

    # The API computes a display rank within the page it was asked for, so a contest that fits in one
    # page is what makes its ranks comparable with the oracle's whole-board ranks. The digest asks for
    # 200 rows, which is the endpoint's own maximum.
    $observed["digestPageSize"] = 200

    # Every contest that has a result is replayed by full-replay, not just this one. A second contest
    # with stored results would make that mode do work the other modes do not do, and would put keys
    # into the rollback scope that this run never measured.
    $contestIds = @(Invoke-SqlRows -Sql @"
SELECT DISTINCT contest_id FROM contest_submission_result ORDER BY contest_id;
"@ -Description "contests with stored results")
    $foreign = @($contestIds | Where-Object { (ConvertTo-RequiredInt64 -Value $_[0] -Description "result contest id") -ne $config.ContestId })
    $observed["contestsWithResults"] = ($contestIds.Count)
    if ($foreign.Count -gt 0) {
        throw "Contest(s) $($foreign -join ', ') also have stored results. full-replay would replay them and the " +
        "other two modes would not, so the three would not be measured under the same conditions (phase $Phase)."
    }

    # score = solved*1e9 - penalty*1e3 - userId is injective over this contest only while the user ids
    # fit inside the penalty weight: two users a multiple of 1000 apart would tie on score, and the
    # ZSET's tie order is on the member string rather than on the id, so the standings order would stop
    # being a property of the data.
    $spread = @(Invoke-SqlRows -Sql @"
SELECT MIN(user_id), MAX(user_id), COUNT(DISTINCT user_id) FROM (
    SELECT cs.user_id AS user_id
      FROM contest_submission cs
      JOIN contest_submission_result r ON r.submission_id = cs.id
     WHERE cs.contest_id = $($config.ContestId)
       AND r.scoreboard_applied_at IS NOT NULL
) participants;
"@ -Description "participant id spread")
    $minimum = ConvertTo-RequiredInt64 -Value $spread[0][0] -Description "minimum participant id"
    $maximum = ConvertTo-RequiredInt64 -Value $spread[0][1] -Description "maximum participant id"
    $observed["participantIdMin"] = $minimum
    $observed["participantIdMax"] = $maximum
    if (($maximum - $minimum) -ge 1000) {
        throw "Participant ids span $minimum..$maximum, which is a spread of $($maximum - $minimum) - not less " +
        "than the penalty weight of 1000. Two participants could then tie on ZSET score and the standings " +
        "order would depend on the member string instead of the data (phase $Phase)."
    }

    # The scoreboard's own tie-break compares submission ids as decimal strings, so it orders them the
    # way arithmetic does only while they are all the same length. The oracle compares them as numbers.
    $lengths = @(Invoke-SqlRows -Sql @"
SELECT MIN(LENGTH(id)), MAX(LENGTH(id)) FROM contest_submission WHERE contest_id = $($config.ContestId);
"@ -Description "submission id width")
    if ($null -ne $lengths[0][0] -and -not [string]::IsNullOrWhiteSpace([string]$lengths[0][0])) {
        $minLength = ConvertTo-RequiredInt64 -Value $lengths[0][0] -Description "minimum submission id width"
        $maxLength = ConvertTo-RequiredInt64 -Value $lengths[0][1] -Description "maximum submission id width"
        $observed["submissionIdDigits"] = "$minLength..$maxLength"
        if ($minLength -ne $maxLength) {
            throw "Submission ids in contest $($config.ContestId) are $minLength..$maxLength digits wide. The " +
            "scoreboard orders attempts by submission id as a string, so a mixed width would make its " +
            "tie-break disagree with this oracle's numeric one (phase $Phase)."
        }
    }

    # A result MySQL says was applied is one the scoreboard's write path took, and that path skips a
    # PENDING result. If one were marked anyway, the oracle would either have to count it as a wrong
    # attempt or drop it, and both would be guesses.
    $pendingApplied = Invoke-SqlInt64 -Sql @"
SELECT COUNT(*) FROM contest_submission_result
 WHERE contest_id = $($config.ContestId)
   AND scoreboard_applied_at IS NOT NULL
   AND COALESCE(final_result, provisional_result) = 'PENDING';
"@ -Description "applied PENDING results"
    $observed["appliedPendingResults"] = $pendingApplied
    if ($pendingApplied -ne 0) {
        throw "$pendingApplied result(s) are marked applied but still PENDING. The scoreboard's write path " +
        "skips PENDING results, so this row's contribution is not something this oracle can predict " +
        "(phase $Phase)."
    }

    return $observed
}

# --- comparisons and counts ------------------------------------------------------------------------

# One poll's comparison. `Matches` is the whole verdict: the scoreboard holds exactly the results MySQL
# says it was given, and holds them with the right solved count, penalty and order.
function Compare-ScoreboardWithOracle {
    param([Parameter(Mandatory = $true)][switch]$AllResolvedResults)

    $api = Get-ApiScoreboardDigest
    $oracle = Get-OracleDigest -AllResolvedResults:$AllResolvedResults
    $matches = $api.Digest -eq $oracle.Digest

    $difference = $null
    if (-not $matches) {
        $difference = Get-StandingsDifference -Api $api.Standings -Oracle $oracle.Standings
    }
    return [pscustomobject][ordered]@{
        Matches = $matches
        ApiDigest = $api.Digest
        OracleDigest = $oracle.Digest
        ApiParticipants = $api.Participants
        OracleParticipants = $oracle.Participants
        ApiEntries = $api.Entries
        OracleEntries = $oracle.Entries
        Difference = $difference
    }
}

# A description of how two standings lists differ, for the sample file. Bounded on purpose: an
# unhelpful dump of a thousand mismatched rows is worse evidence than a count and the first few.
function Get-StandingsDifference {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Api,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Oracle
    )

    $byUser = @{}
    foreach ($entry in $Oracle) { $byUser[[long]$entry.UserId] = $entry }

    $missing = New-Object 'System.Collections.Generic.List[long]'
    $wrongScore = New-Object 'System.Collections.Generic.List[string]'
    $wrongOrder = New-Object 'System.Collections.Generic.List[long]'
    $wrongRank = New-Object 'System.Collections.Generic.List[long]'

    $apiUsers = @{}
    for ($index = 0; $index -lt $Api.Count; $index++) {
        $entry = $Api[$index]
        $apiUsers[[long]$entry.UserId] = $true
        if (-not $byUser.ContainsKey([long]$entry.UserId)) {
            $wrongOrder.Add([long]$entry.UserId)
            continue
        }
        $expected = $byUser[[long]$entry.UserId]
        if ($entry.Solved -ne $expected.Solved -or $entry.Penalty -ne $expected.Penalty) {
            $wrongScore.Add("$($entry.UserId):got($($entry.Solved),$($entry.Penalty))want($($expected.Solved),$($expected.Penalty))")
        }
        elseif ($entry.Rank -ne $expected.Rank) {
            $wrongRank.Add([long]$entry.UserId)
        }
        elseif ($Oracle[$index].UserId -ne $entry.UserId) {
            $wrongOrder.Add([long]$entry.UserId)
        }
    }
    foreach ($entry in $Oracle) {
        if (-not $apiUsers.ContainsKey([long]$entry.UserId)) {
            $missing.Add([long]$entry.UserId)
        }
    }

    $limit = 8
    return [pscustomobject][ordered]@{
        MissingFromScoreboard = $missing.Count
        MissingSample = @($missing | Select-Object -First $limit)
        NotInOracle = $wrongOrder.Count
        NotInOracleSample = @($wrongOrder | Select-Object -First $limit)
        WrongScoreOrPenalty = $wrongScore.Count
        WrongScoreOrPenaltySample = @($wrongScore | Select-Object -First $limit)
        WrongRank = $wrongRank.Count
        WrongRankSample = @($wrongRank | Select-Object -First $limit)
    }
}

# The lag side of the same picture, and the resource counts the report needs. Everything here is read
# from MySQL, so it is available whether or not the scoreboard is answering.
function Get-ResultCounts {
    $config = Get-RecoveryConfig
    $row = @(Invoke-SqlRows -Sql @"
SELECT
    (SELECT COUNT(*) FROM contest_submission WHERE contest_id = $($config.ContestId)),
    (SELECT COUNT(*) FROM contest_submission_result
      WHERE contest_id = $($config.ContestId)
        AND COALESCE(final_result, provisional_result) <> 'PENDING'),
    (SELECT COUNT(*) FROM contest_submission_result
      WHERE contest_id = $($config.ContestId)
        AND COALESCE(final_result, provisional_result) <> 'PENDING'
        AND scoreboard_applied_at IS NOT NULL),
    (SELECT COUNT(*) FROM contest_submission_result
      WHERE contest_id = $($config.ContestId)
        AND COALESCE(final_result, provisional_result) = 'ACCEPTED'
        AND scoreboard_applied_at IS NOT NULL),
    (SELECT COUNT(DISTINCT user_id) FROM contest_submission WHERE contest_id = $($config.ContestId));
"@ -Description "contest result counts")[0]
    return [pscustomobject][ordered]@{
        Submissions = ConvertTo-RequiredInt64 -Value $row[0] -Description "submission count"
        ResolvedResults = ConvertTo-RequiredInt64 -Value $row[1] -Description "resolved result count"
        AppliedResults = ConvertTo-RequiredInt64 -Value $row[2] -Description "applied result count"
        AppliedAcceptedResults = ConvertTo-RequiredInt64 -Value $row[3] -Description "applied accepted count"
        SubmittingUsers = ConvertTo-RequiredInt64 -Value $row[4] -Description "submitting user count"
    }
}

# How long a judged result waited to be reflected on the scoreboard. This is the new-ingress latency
# the report needs, and it is read from MySQL rather than sampled from the scoreboard, so it covers
# results the scoreboard has not applied yet as well as ones it has.
#
# The lower bound is a value read from the database's own clock - see Get-MySqlNow - so the comparison
# happens entirely inside one frame.
#
# The zero-based offset of the p-th percentile in an ordered list of `Count` values, counting the way
# `CEIL(count * p / 100)` counts: the value at rank `CEIL(count * p / 100)`, so p50 of four values is the
# second and p99 of one hundred is the ninety-ninth.
#
# It is computed here, in integer arithmetic, because MySQL's LIMIT accepts an integer literal or a
# placeholder and nothing else. Adding 99 before dividing by 100 is an exact ceiling of an exact integer
# product, where comparing two doubles can land on the wrong side of an integer.
function Get-PercentileOffset {
    param(
        [Parameter(Mandatory = $true)][long]$Count,
        [Parameter(Mandatory = $true)][int]$Percentile
    )

    if ($Count -lt 1) {
        return 0L
    }
    if ($Percentile -lt 1 -or $Percentile -gt 100) {
        throw "Percentile $Percentile is outside 1..100."
    }
    $rank = [long][Math]::Floor(($Count * $Percentile + 99) / 100.0)
    if ($rank -lt 1) {
        return 0L
    }
    return $rank - 1
}

# Percentiles come from COUNT plus an ordered LIMIT/OFFSET rather than a window function, because a poll
# wants a number and not a thousand rows.
function Get-ReflectLatencyStats {
    param(
        [Parameter(Mandatory = $true)][string]$AppliedAtOrAfter,
        [Parameter(Mandatory = $true)][string]$Description,
        # Deliberately untyped. A `[string]` parameter defaults to the empty string rather than to null
        # - the type constraint converts the default on assignment - so `-AppliedBefore` omitted would
        # arrive as `''` and be rejected as a datetime literal, making the optional argument impossible
        # to leave out. The integration test caught this by calling the function without it.
        $AppliedBefore = $null
    )

    if ($AppliedAtOrAfter -notmatch '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,6})?$') {
        throw "AppliedAtOrAfter '$AppliedAtOrAfter' is not a MySQL datetime literal."
    }
    if ($null -ne $AppliedBefore -and $AppliedBefore -notmatch '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,6})?$') {
        throw "AppliedBefore '$AppliedBefore' is not a MySQL datetime literal."
    }

    $config = Get-RecoveryConfig
    $upperBound = if ($null -eq $AppliedBefore) { "" } else { "AND scoreboard_applied_at < '$AppliedBefore'" }
    $samples = @"
SELECT TIMESTAMPDIFF(MICROSECOND,
                     COALESCE(final_judged_at, provisional_judged_at),
                     scoreboard_applied_at) / 1000 AS latency_ms
  FROM contest_submission_result
 WHERE contest_id = $($config.ContestId)
   AND scoreboard_applied_at IS NOT NULL
   AND COALESCE(final_judged_at, provisional_judged_at) IS NOT NULL
   AND scoreboard_applied_at >= '$AppliedAtOrAfter'
   $upperBound
"@

    $row = @(Invoke-SqlRows -Sql @"
SELECT COUNT(*), IFNULL(ROUND(MIN(latency_ms)), 0), IFNULL(ROUND(MAX(latency_ms)), 0), IFNULL(ROUND(AVG(latency_ms)), 0)
  FROM ($samples) summary;
"@ -Description "$Description latency summary")[0]
    $count = ConvertTo-RequiredInt64 -Value $row[0] -Description "$Description latency sample count"
    if ($count -eq 0) {
        return [pscustomobject][ordered]@{
            Samples = 0
            MinMs = "unavailable"
            P50Ms = "unavailable"
            P95Ms = "unavailable"
            P99Ms = "unavailable"
            MaxMs = "unavailable"
            MeanMs = "unavailable"
        }
    }

    $percentiles = [ordered]@{}
    foreach ($percentile in @(50, 95, 99)) {
        # The offset is a literal because MySQL's LIMIT takes an integer literal or a placeholder and
        # nothing else, so `LIMIT 1 OFFSET GREATEST(0, CEIL(...))` is a syntax error. The first version of
        # this was written that way and reported only a non-zero exit code, which is what a failure looks
        # like when the server's explanation is thrown away.
        $offset = Get-PercentileOffset -Count $count -Percentile $percentile
        # String keys. `OrderedDictionary` has both an `Item[object]` and an `Item[int]` indexer, and
        # PowerShell binds an integer subscript to the positional one - so `$percentiles[50]` reads the
        # fifty-first entry rather than the entry named 50, and past the end it throws `Parameter name:
        # index` instead of returning the percentile that was just stored.
        $percentiles["$percentile"] = ConvertTo-RequiredDouble -Value (Invoke-SqlScalar -Sql @"
SELECT ROUND(latency_ms)
  FROM ($samples) ordered
 ORDER BY latency_ms
 LIMIT 1 OFFSET $offset;
"@ -Description "$Description p$percentile latency") -Description "$Description p$percentile latency"
    }

    return [pscustomobject][ordered]@{
        Samples = $count
        MinMs = Format-InvariantNumber (ConvertTo-RequiredDouble -Value $row[1] -Description "$Description min latency")
        P50Ms = Format-InvariantNumber $percentiles["50"]
        P95Ms = Format-InvariantNumber $percentiles["95"]
        P99Ms = Format-InvariantNumber $percentiles["99"]
        MaxMs = Format-InvariantNumber (ConvertTo-RequiredDouble -Value $row[2] -Description "$Description max latency")
        MeanMs = Format-InvariantNumber (ConvertTo-RequiredDouble -Value $row[3] -Description "$Description mean latency")
    }
}
