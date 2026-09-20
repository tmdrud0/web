# What this experiment creates in the shared database, and how it is taken back out.
#
# The database is `oj_test` on the shared instance, which is not this experiment's to own. Every row
# this module touches is therefore identified by a scope that cannot match a row it did not create: the
# contest id this module inserted, the problem ids that reference it, and the `sbrec_<runId>_` name
# prefix. There is no statement here without one of those three in its WHERE clause, and there is no
# TRUNCATE, DROP or statement that names a table without a scope.
#
# Three facts about this schema decide the shape of the delete order, and each of them is the kind of
# thing that fails quietly:
#
#   * `problem.contest_id` is `ON DELETE SET NULL`, not `ON DELETE CASCADE`. Deleting the contest first
#     would leave the experiment's problems in the table with a NULL contest - and a problem whose
#     contest is NULL is exactly what routes a submission to the plain `submission` table instead of the
#     contest pipeline. The next run's load would then measure an empty scoreboard and report it as a
#     fast recovery. Problems are deleted before the contest for that reason.
#   * `contest_submission` cascades from `contest`, and `contest_submission_result` and
#     `contest_judge_outbox` cascade from `contest_submission`. Relying on the cascade would delete the
#     rows but would report no count for them, and a scope whose size is not measured cannot be
#     verified. They are deleted explicitly, innermost first.
#   * `contest_submission.id` is not AUTO_INCREMENT (V10 removed it) - the application assigns it - so
#     nothing here may insert a submission row directly; the load generator creates those through the
#     API. This module seeds only the contest, its problems and its users.
#
# What is deliberately not deleted: bucket rows the experiment's own users pushed into existence
# (`longest_streak_bucket` and friends). Those tables have no user column to scope by, the sentinel in
# Common treats an added bucket as acceptable and a removed one as interference, and deleting a bucket
# would risk removing one that was there before this experiment ran. Snapshot rows for this run's users
# are deleted, because those do carry a user id.

# The tables this experiment can account for, with the scope that identifies its own rows in each. Order
# is the delete order: children before parents, and `problem` before `contest`.
function Get-ExperimentTableScope {
    $config = Get-RecoveryConfig
    $contestId = [string]$config.ContestId
    $prefix = $config.SeedPrefix
    # Single-quoted so the backticks reach MySQL as identifier quotes rather than being read as
    # PowerShell's escape character.
    $tUser = '`user`'
    $ourUsers = "SELECT id FROM $tUser WHERE name LIKE '$prefix%'"
    $ourProblems = "SELECT id FROM problem WHERE contest_id = $contestId"

    return @(
        [pscustomobject]@{
            Table = "contest_submission_outbox"
            Where = "contest_id = $contestId"
            Note = "legacy outbox; no current code writes it, but it is contest-scoped and this experiment owns the contest"
        }
        [pscustomobject]@{
            Table = "contest_submission_result"
            Where = "contest_id = $contestId"
            Note = "one row per judged submission; carries scoreboard_applied_at"
        }
        [pscustomobject]@{
            Table = "contest_judge_outbox"
            Where = "submission_id IN (SELECT id FROM contest_submission WHERE contest_id = $contestId)"
            Note = "the live judge outbox, cascaded from contest_submission"
        }
        [pscustomobject]@{
            Table = "contest_submission"
            Where = "contest_id = $contestId"
            Note = "the contest submissions themselves"
        }
        [pscustomobject]@{
            Table = "contest_final_score"
            Where = "contest_id = $contestId"
            Note = "written only by finalization, which this experiment never runs; counted to prove it stayed empty"
        }
        [pscustomobject]@{
            Table = "accepted_submission"
            Where = "user_id IN ($ourUsers) OR problem_id IN ($ourProblems)"
            Note = "plain-path table; scoped because a submission outside the contest window would land here instead of the contest pipeline"
        }
        [pscustomobject]@{
            Table = "submission"
            Where = "user_id IN ($ourUsers) OR problem_id IN ($ourProblems)"
            Note = "plain-path table, same reason; a non-empty count here is evidence the contest window closed mid-run"
        }
        [pscustomobject]@{
            Table = "user_problem_guard"
            Where = "user_id IN ($ourUsers) OR problem_id IN ($ourProblems)"
            Note = "plain-path guard row"
        }
        [pscustomobject]@{
            Table = "daily_active_users"
            Where = "user_id IN ($ourUsers)"
            Note = "date-keyed, so it is scoped by user rather than by contest"
        }
        [pscustomobject]@{
            Table = "longest_streak_rank_snapshot"
            Where = "user_id IN ($ourUsers)"
            Note = "a scheduled snapshot; this run's users can appear in it"
        }
        [pscustomobject]@{
            Table = "problem"
            Where = "contest_id = $contestId"
            Note = "deleted before the contest, because the FK is ON DELETE SET NULL and an orphaned problem silently routes to the plain path"
        }
        [pscustomobject]@{
            Table = "contest"
            Where = "id = $contestId"
            Note = "the contest itself"
        }
        [pscustomobject]@{
            Table = $tUser
            Where = "name LIKE '$prefix%'"
            Note = "the experiment's users, deleted last because the tables above reference them"
        }
    )
}

# The counts, per table, of the rows the scope above would delete right now. This is what gets written
# to the evidence file before a delete and what is asserted to be zero after one.
function Get-ExperimentScopeCounts {
    $counts = [ordered]@{}
    foreach ($entry in Get-ExperimentTableScope) {
        $counts["$($entry.Table)"] = Invoke-SqlInt64 `
            -Sql "SELECT COUNT(*) FROM $($entry.Table) WHERE $($entry.Where)" `
            -Description "scope count for $($entry.Table)"
    }
    return $counts
}

# Nothing of this run is in the database. Checked before seeding, so a seed cannot double-create users
# under a name that `uq_user_name` would then reject halfway through, and checked after cleanup, so
# "the rows are gone" is a reading rather than a hope.
function Assert-ExperimentDataAbsent {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $config = Get-RecoveryConfig
    $counts = Get-ExperimentScopeCounts
    $present = New-Object 'System.Collections.Generic.List[string]'
    foreach ($table in $counts.Keys) {
        if ([long]$counts[$table] -gt 0) {
            $present.Add("$table=$($counts[$table])")
        }
    }
    if ($present.Count -gt 0) {
        throw "Rows belonging to run '$($config.RunId)' are present $Phase`: $($present -join ', '). " +
        "Seeding or measuring on top of them would mix two runs' data."
    }
    return $counts
}

# Creates the contest, its problems and its users, in that order, in one connection.
#
# The window is expressed relative to the database's own clock rather than to the harness's: the JVM
# compares `LocalDateTime.now()` against the stored `start_time`/`end_time`, so a window computed from
# the host clock would be correct only while host and database agreed on the zone. Every instant this
# experiment reasons about is read back from the column that was written.
function New-ExperimentSeed {
    param(
        [Parameter(Mandatory = $true)][int]$UserCount,
        [Parameter(Mandatory = $true)][int]$ProblemCount,
        [Parameter(Mandatory = $true)][int]$ContestDurationMinutes,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )

    $config = Get-RecoveryConfig
    if ($UserCount -lt 1 -or $ProblemCount -lt 1) {
        throw "A seed needs at least one user and one problem (users=$UserCount, problems=$ProblemCount)."
    }
    if ($ContestDurationMinutes -lt 1) {
        throw "A contest window of $ContestDurationMinutes minute(s) would already be closed."
    }

    $tUser = '`user`'
    $prefix = $config.SeedPrefix
    $existingUsers = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM $tUser WHERE name LIKE '$prefix%'" `
        -Description "users left over from an earlier attempt at run '$($config.RunId)'"
    if ($existingUsers -gt 0) {
        throw "$existingUsers user row(s) already carry the prefix '$prefix'. Remove them before seeding."
    }

    $problemValues = @(1..$ProblemCount | ForEach-Object { "('${prefix}p$_', @contest_id, $_)" }) -join ", "
    $userValues = @(1..$UserCount | ForEach-Object { "('${prefix}user_$_', 'pass', 0, NULL, 0, 0)" }) -join ", "

    $script = @"
SET SESSION group_concat_max_len = 8388608;
INSERT INTO contest (name, start_time, end_time)
VALUES ('${prefix}contest', NOW(6) - INTERVAL 1 MINUTE, NOW(6) + INTERVAL $ContestDurationMinutes MINUTE);
SET @contest_id = LAST_INSERT_ID();
INSERT INTO problem (name, contest_id, contest_num) VALUES $problemValues;
INSERT INTO $tUser (name, pass, solved_count, streak_last_solved_date, streak_current_streak, streak_longest_streak) VALUES $userValues;
SELECT CONCAT('SBRE_SEED_CONTEST=', @contest_id);
SELECT CONCAT('SBRE_SEED_WINDOW=', DATE_FORMAT(start_time, '%Y-%m-%d %H:%i:%s.%f'), '|', DATE_FORMAT(end_time, '%Y-%m-%d %H:%i:%s.%f'), '|', IFNULL(DATE_FORMAT(finalized_at, '%Y-%m-%d %H:%i:%s.%f'), 'NULL'))
  FROM contest WHERE id = @contest_id;
SELECT CONCAT('SBRE_SEED_PROBLEMS=', MIN(id), '|', MAX(id), '|', COUNT(*), '|', SUM(contest_id <> @contest_id), '|', SUM(contest_id IS NULL))
  FROM problem WHERE contest_id = @contest_id;
SELECT CONCAT('SBRE_SEED_USERS=', MIN(id), '|', MAX(id), '|', COUNT(*), '|', SUM(pass <> 'pass'), '|', SUM(name NOT LIKE '${prefix}user_%'))
  FROM $tUser WHERE name LIKE '$prefix%';
"@

    $lines = @(Invoke-SqlScript -Sql $script -Description "seed run '$($config.RunId)'")
    $values = [ordered]@{}
    foreach ($line in $lines) {
        $text = ([string]$line).Trim()
        if (-not $text.StartsWith("SBRE_SEED_")) { continue }
        $separator = $text.IndexOf("=")
        if ($separator -lt 0) { continue }
        $values[$text.Substring(0, $separator)] = $text.Substring($separator + 1)
    }
    foreach ($required in @("SBRE_SEED_CONTEST", "SBRE_SEED_WINDOW", "SBRE_SEED_PROBLEMS", "SBRE_SEED_USERS")) {
        if (-not $values.Contains($required)) {
            throw "Seeding did not report '$required': $($lines -join ' | ')"
        }
    }

    $window = @([string]$values["SBRE_SEED_WINDOW"] -split '\|', -1)
    $problems = @([string]$values["SBRE_SEED_PROBLEMS"] -split '\|', -1)
    $users = @([string]$values["SBRE_SEED_USERS"] -split '\|', -1)
    if ($window.Count -ne 3 -or $problems.Count -ne 5 -or $users.Count -ne 5) {
        throw "Seeding reported an unreadable scope: window='$($values['SBRE_SEED_WINDOW'])' " +
        "problems='$($values['SBRE_SEED_PROBLEMS'])' users='$($values['SBRE_SEED_USERS'])'"
    }
    foreach ($field in @($problems + $users)) {
        if ([string]$field -eq "NULL") {
            throw "Seeding inserted nothing: problems='$($values['SBRE_SEED_PROBLEMS'])' users='$($values['SBRE_SEED_USERS'])'"
        }
    }

    $seed = [pscustomobject][ordered]@{
        RunId = $config.RunId
        ContestId = [long]$values["SBRE_SEED_CONTEST"]
        ContestName = "${prefix}contest"
        StartTimeMysql = $window[0]
        EndTimeMysql = $window[1]
        FinalizedAtMysql = $window[2]
        ProblemIdStart = [long]$problems[0]
        ProblemIdEnd = [long]$problems[1]
        ProblemCount = [long]$problems[2]
        UserIdStart = [long]$users[0]
        UserIdEnd = [long]$users[1]
        UserCount = [long]$users[2]
        UserPrefix = "${prefix}user"
        Password = "pass"
        SeededAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        SeededAtMysql = Get-MySqlNow
    }

    # Each of these is a property the load path depends on, and each fails quietly if it does not hold.
    if ($seed.FinalizedAtMysql -ne "NULL") {
        throw "The seeded contest is finalized ($($seed.FinalizedAtMysql)); submissions to it are refused with 500."
    }
    if ($seed.ProblemCount -ne $ProblemCount -or [long]$problems[3] -ne 0 -or [long]$problems[4] -ne 0) {
        throw "The seeded problems do not all belong to contest $($seed.ContestId): ${problems -join '|'}"
    }
    if ($seed.UserCount -ne $UserCount -or [long]$users[3] -ne 0 -or [long]$users[4] -ne 0) {
        throw "The seeded users are not all '$prefix' users with password 'pass': ${users -join '|'}"
    }

    if (-not (Test-Path -LiteralPath $EvidenceDirectory)) {
        [void](New-Item -ItemType Directory -Path $EvidenceDirectory -Force)
    }
    $seed | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "seed.json") -Encoding utf8

    [void](Set-ExperimentContestScope -ContestId $seed.ContestId -ProblemIdStart $seed.ProblemIdStart `
            -ProblemIdEnd $seed.ProblemIdEnd -ContestStartTimeMysql $seed.StartTimeMysql)
    return $seed
}

# Whether the contest is still one the application will route submissions into.
#
# `SubmissionStoreStrategySelector.onContest` answers this on every submission, and a `false` there does
# not fail the request - it writes the submission to the plain `submission` table and returns 202. So a
# run whose window closed halfway through keeps accepting load, keeps reporting no errors, and measures
# a scoreboard that stopped receiving anything. Checking the window is how that becomes visible.
function Assert-ExperimentSeedUsable {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $config = Get-RecoveryConfig
    $tUser = '`user`'
    $contest = @(Invoke-SqlRows -Sql @"
SELECT IFNULL(DATE_FORMAT(finalized_at, '%Y-%m-%d %H:%i:%s.%f'), 'NULL'),
       NOW(6) BETWEEN start_time AND end_time,
       TIMESTAMPDIFF(SECOND, NOW(6), end_time)
  FROM contest WHERE id = $($config.ContestId);
"@ -Description "contest usability $Phase")
    if ($contest.Count -ne 1 -or $contest[0].Count -lt 3) {
        throw "Contest $($config.ContestId) is gone; run '$($config.RunId)' can no longer be measured ($Phase)."
    }
    # Counted separately rather than read from the same statement, so that a contest which is *still*
    # inside its window but whose submissions went to the plain table is still visible.
    $outsideSubmissions = Invoke-SqlInt64 -Sql @"
SELECT COUNT(*) FROM $tUser u JOIN submission s ON s.user_id = u.id WHERE u.name LIKE '$($config.SeedPrefix)%';
"@ -Description "plain-path submissions from this run's users $Phase"

    $record = [pscustomobject][ordered]@{
        phase = $Phase
        contestId = $config.ContestId
        observedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        finalizedAt = [string]$contest[0][0]
        insideWindow = ([string]$contest[0][1] -eq "1")
        secondsUntilEnd = [long]$contest[0][2]
        submissionsOutsideTheContestPath = $outsideSubmissions
    }
    if ($record.finalizedAt -ne "NULL") {
        throw "Contest $($config.ContestId) was finalized during $Phase; every result of this run after that point is invalid."
    }
    if (-not $record.insideWindow) {
        throw "Contest $($config.ContestId) is outside its window during $Phase (ends in $($record.secondsUntilEnd)s). " +
        "Submissions are being routed to the plain 'submission' table, so the scoreboard is no longer receiving them."
    }
    return $record
}

# Deletes this run's rows, innermost first, with the target counted before each statement and the SQL
# recorded. The counts are the point: a delete that reports what it removed is evidence, and a delete
# that reports nothing is an assumption.
function Remove-ExperimentData {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)

    $config = Get-RecoveryConfig
    if (-not $config.ContestScopeFromSeed) {
        throw ("Refusing to delete: no seed has set the contest scope for this run, so ContestId is " +
            "still the default $($config.ContestId) and the scoped statements would delete another " +
            "contest's rows. Call New-ExperimentSeed first, or clear the data by hand after checking " +
            "which contest it belongs to.")
    }
    # The scope is only as good as the id it carries: this reads the row back and checks that it is
    # the seeder's own naming. A wrong id that happens to exist is the failure mode a scope cannot
    # catch, and it is caught here instead.
    $ownContest = Invoke-SqlInt64 -Sql (
        "SELECT COUNT(*) FROM contest WHERE id = $($config.ContestId) AND name = '$($config.SeedPrefix)contest'"
    ) -Description "the scoped contest carries this run's name"
    if ($ownContest -ne 1) {
        # Refused even when nothing is left, because "the row this run seeded is not there" and "this id
        # was never this run's contest" read identically from here, and only one of them means the rows
        # are already out. Guessing between them would be the kind of quiet success that leaves a run's
        # rows in the database while reporting a cleanup; the caller that has already cleaned up says so
        # by not calling again.
        throw ("Refusing to delete: contest $($config.ContestId) is not named " +
            "'$($config.SeedPrefix)contest', so it is not the row this run seeded. Nothing was deleted. " +
            "If this run's cleanup has already run, its rows are already out.")
    }
    if (-not (Test-Path -LiteralPath $EvidenceDirectory)) {
        [void](New-Item -ItemType Directory -Path $EvidenceDirectory -Force)
    }
    $steps = New-Object 'System.Collections.Generic.List[object]'
    foreach ($entry in Get-ExperimentTableScope) {
        $before = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM $($entry.Table) WHERE $($entry.Where)" `
            -Description "rows to delete from $($entry.Table)"
        $sql = "DELETE FROM $($entry.Table) WHERE $($entry.Where)"
        if ($before -gt 0) {
            [void](Invoke-SqlScript -Sql $sql -Description "delete $before row(s) from $($entry.Table)")
        }
        $after = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM $($entry.Table) WHERE $($entry.Where)" `
            -Description "rows left in $($entry.Table)"
        if ($after -ne 0) {
            throw "Deleting from $($entry.Table) left $after row(s): $sql"
        }
        $steps.Add([pscustomobject][ordered]@{
            table = $entry.Table
            sql = $sql
            deleted = $before
            remaining = $after
            note = $entry.Note
        })
    }

    $record = [pscustomobject][ordered]@{
        runId = $config.RunId
        contestId = $config.ContestId
        seedPrefix = $config.SeedPrefix
        removedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        removedAtMysql = Get-MySqlNow
        totalDeleted = ($steps | Measure-Object -Property deleted -Sum).Sum
        # `.ToArray()`, not `@(...)`. Under `Set-StrictMode -Version Latest` on Windows PowerShell 5.1,
        # the array subexpression applied to a `List[object]` throws `Argument types do not match` from
        # the binder that decides whether to treat the list as one item or many - and it throws while
        # evaluating the enclosing statement, so the failure names the record-building line rather than
        # the conversion. `List[string]` and `List[int]` are unaffected, which is what makes it worth a
        # comment: the same expression is safe one line away.
        steps = $steps.ToArray()
        residualRows = Get-ResidualRowCounts
    }
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "removed-rows.json") -Encoding utf8

    [void](Assert-ExperimentDataAbsent -Phase "after cleanup of run '$($config.RunId)'")

    # Cleared once the rows are gone, because the scope no longer names anything: the contest it was
    # set from has just been deleted. Left set, a second cleanup would run its statements against an id
    # that no longer exists and fail inside the name check - which reads as a broken harness rather
    # than as the cleanup that has already happened. Cleared, the second call refuses at the guard
    # above and says so.
    $config.ContestScopeFromSeed = $false
    return $record
}

# --- the broker ------------------------------------------------------------------------------------

# Between runs the scoreboard's stream has to be empty, because a run's backlog has to be its own: a
# message left from the previous mode would be re-consumed by the next one and counted as its recovery.
#
# The queue is deleted rather than purged, and only after two things hold: the broker reports exactly
# this project's three queues, and nothing is consuming the stream. The consumer check is the load-
# bearing one - deleting a queue out from under a live consumer would make the *reset* the cause of the
# next run's failure observations. The application re-declares the queue when it starts.
function Reset-ExperimentQueue {
    param([Parameter(Mandatory = $true)][string]$EvidencePath)

    $config = Get-RecoveryConfig
    $before = Get-RabbitQueueState
    Assert-OnlyProjectQueues -Queues $before
    $stream = $before[$config.QueueName]

    $record = [pscustomobject][ordered]@{
        runId = $config.RunId
        queue = $config.QueueName
        observedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        queuePresentBefore = ($null -ne $stream)
        queuesBefore = @($before.Keys | Sort-Object | ForEach-Object {
                "$_=ready:$($before[$_].Ready),unacked:$($before[$_].Unacked),consumers:$($before[$_].Consumers)"
            })
        deleted = $false
        queuesAfter = @()
    }

    # `$null -ne` first on each of these, because an absent queue and a queue with no consumers are
    # different facts and only one of them is this branch's business: absent means the application has
    # not declared the queues yet, which is a state the reset is happy to produce and nothing for it to
    # delete. `-and` short-circuits, so the property is only read once the queue is known to be there.
    if ($null -ne $stream -and $stream.Consumers -ne 0) {
        $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
        throw "Queue '$($config.QueueName)' has $($stream.Consumers) consumer(s). Stop the application " +
        "containers before resetting the queue, so the reset is not what the next run measures."
    }
    $live = $before["contest.judge.live"]
    $dead = $before["contest.judge.dead"]
    $liveReady = if ($null -eq $live) { 0L } else { [long]$live.Ready }
    $liveUnacked = if ($null -eq $live) { 0L } else { [long]$live.Unacked }
    $deadReady = if ($null -eq $dead) { 0L } else { [long]$dead.Ready }
    $deadUnacked = if ($null -eq $dead) { 0L } else { [long]$dead.Unacked }
    if ($liveReady -ne 0 -or $liveUnacked -ne 0 -or $deadReady -ne 0 -or $deadUnacked -ne 0) {
        $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
        throw "The previous run left work in the judge queues (live ready=$liveReady unacked=$liveUnacked, " +
        "dead ready=$deadReady unacked=$deadUnacked). Draining them is the previous run's result, not this reset's."
    }

    if ($null -ne $stream) {
        # No `-T`: that is `docker compose exec`'s flag for turning the pseudo-TTY off, and `docker exec`
        # does not have it - it rejects the command rather than running it, which is how the first
        # calibration run found this line.
        [void](Invoke-Docker -Arguments @(
                "exec", $config.RabbitContainer, "rabbitmqctl", "delete_queue", $config.QueueName, "-p", "/"))
    }
    $after = Get-RabbitQueueState
    $record.deleted = ($null -ne $stream)
    $record.queuesAfter = @($after.Keys | Sort-Object | ForEach-Object {
            "$_=ready:$($after[$_].Ready),unacked:$($after[$_].Unacked),consumers:$($after[$_].Consumers)"
        })
    $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $EvidencePath -Encoding utf8

    if ($after.Contains($config.QueueName)) {
        throw "Queue '$($config.QueueName)' is still present after deletion."
    }
    foreach ($name in @("contest.judge.live", "contest.judge.dead")) {
        if (-not $after.Contains($name)) {
            throw "Deleting '$($config.QueueName)' also removed '$name', which this reset must not touch."
        }
    }
    return $record
}

# --- leftovers from a run that did not finish -------------------------------------------------------

# The run id a seeded row's name encodes, or `$null` when the name is not one of this experiment's.
#
# Anchored on both ends and read with a capture group rather than by substring arithmetic: the run id
# contains underscores of its own, so a length-based slice would have to count them correctly on every
# call, and one off-by-one there would attribute a leftover to the wrong run and delete the wrong rows.
# Two spellings exist because the seed writes two shapes - `sbrec_<runId>_contest` and
# `sbrec_<runId>_user_<n>` - and each function answers for exactly the shape it is named after.
function Get-RunIdFromContestName {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Name)

    if ($Name -match '^sbrec_(?<runId>.+)_contest$') { return $Matches["runId"] }
    return $null
}

function Get-RunIdFromUserName {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Name)

    if ($Name -match '^sbrec_(?<runId>.+)_user_\d+$') { return $Matches["runId"] }
    return $null
}

# Every row in the database that carries this experiment's name prefix, whoever's run it belongs to.
#
# The prefix is the only thing that identifies an experiment row, and it is matched loosely here on
# purpose: a LIKE pattern that tried to carry a run id would have to escape the underscores that run
# ids legitimately contain, and MySQL's LIKE treats `_` as a single-character wildcard. So the read is
# broad and the attribution happens above it, in a language that has a real string comparison. The set
# is at most one contest and one user per run, so reading all of it costs nothing.
function Get-ExperimentRowsInDatabase {
    $tUser = '`user`'
    $contests = New-Object 'System.Collections.Generic.List[object]'
    foreach ($row in @(Invoke-SqlRows -Sql @"
SELECT id, name, DATE_FORMAT(start_time, '%Y-%m-%d %H:%i:%s.%f'),
       DATE_FORMAT(end_time, '%Y-%m-%d %H:%i:%s.%f')
  FROM contest WHERE name LIKE 'sbrec%';
"@ -Description "contests that carry the experiment prefix")) {
        $name = [string]$row[1]
        $runId = Get-RunIdFromContestName -Name $name
        if ($null -eq $runId) { continue }
        $contests.Add([pscustomobject][ordered]@{
                Id = ConvertTo-RequiredInt64 -Value $row[0] -Description "leftover contest id"
                Name = $name
                RunId = $runId
                StartTimeMysql = [string]$row[2]
                EndTimeMysql = [string]$row[3]
            })
    }

    $users = New-Object 'System.Collections.Generic.List[object]'
    foreach ($row in @(Invoke-SqlRows -Sql @"
SELECT id, name FROM $tUser WHERE name LIKE 'sbrec%' ORDER BY id;
"@ -Description "users that carry the experiment prefix")) {
        $name = [string]$row[1]
        $runId = Get-RunIdFromUserName -Name $name
        if ($null -eq $runId) { continue }
        $users.Add([pscustomobject][ordered]@{
                Id = ConvertTo-RequiredInt64 -Value $row[0] -Description "leftover user id"
                Name = $name
                RunId = $runId
            })
    }

    return [pscustomobject][ordered]@{
        Contests = $contests.ToArray()
        Users = $users.ToArray()
        ContestCount = $contests.Count
        UserCount = $users.Count
    }
}

# Takes back what an *interrupted* attempt at this run id left behind, so a run can be repeated from the
# same command instead of from hand-written SQL. Without this, one crashed run makes its run id
# unusable - the seeder refuses to insert users under a prefix that already exists - and the only way
# forward is a DELETE nobody reviewed.
#
# The scope is discovered rather than assumed: the contest's id comes from reading back the exact name
# this run's seed writes, and adopting it goes through the same Set-ExperimentContestScope the seeder
# uses. So a leftover cleanup and the cleanup at the end of a completed run delete through one scope
# with one definition, and nothing here can widen it.
#
# Another run's leftovers are reported, never deleted. This run has no evidence about which rows under
# another run's contest are its own, and "delete everything with the prefix" is precisely the
# widening the scope exists to prevent. Each is removable by repeating *that* run id, which is how the
# suite clears all nine before it starts.
function Remove-ExperimentLeftovers {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)

    $config = Get-RecoveryConfig
    $tUser = '`user`'
    if (-not (Test-Path -LiteralPath $EvidenceDirectory)) {
        # Created here rather than left to Remove-ExperimentData, which only runs on one of the three
        # paths below: the record is written on all of them, and the no-op path - the common one, since
        # most runs start with nothing to take back - would otherwise fail on a directory that only a
        # completed run had made. The integration test found this by calling it first.
        [void](New-Item -ItemType Directory -Path $EvidenceDirectory -Force)
    }
    $found = Get-ExperimentRowsInDatabase
    $ours = @($found.Contests | Where-Object { $_.RunId -eq $config.RunId })
    $theirs = @($found.Contests | Where-Object { $_.RunId -ne $config.RunId })

    $record = [pscustomobject][ordered]@{
        runId = $config.RunId
        observedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        observedAtMysql = Get-MySqlNow
        ourContest = @($ours | ForEach-Object { "$($_.Name) (id=$($_.Id))" })
        otherRunsContests = @($theirs | ForEach-Object { "$($_.Name) (id=$($_.Id))" })
        ourUsers = @($found.Users | Where-Object { $_.Name.StartsWith("$($config.SeedPrefix)user_") } |
            ForEach-Object { $_.Name })
        cleaned = $false
        removed = $null
    }

    if ($ours.Count -gt 1) {
        $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "leftovers.json") -Encoding utf8
        throw "Run id '$($config.RunId)' names $($ours.Count) contests ($(@($ours | ForEach-Object { $_.Id }) -join ', ')). " +
        "Two rows with one name is a state this harness never creates, so the scope cannot be chosen safely. Evidence: $EvidenceDirectory"
    }

    if ($ours.Count -eq 1) {
        [void](Set-ExperimentContestScope -ContestId $ours[0].Id -ProblemIdStart $config.ProblemIdStart `
                -ProblemIdEnd $config.ProblemIdEnd -ContestStartTimeMysql $ours[0].StartTimeMysql)
        $record.removed = Remove-ExperimentData -EvidenceDirectory $EvidenceDirectory
        $record.cleaned = $true
    }
    elseif ($record.ourUsers.Count -gt 0) {
        # The contest is gone but its users are not, which is what a crash between the two deletes
        # leaves. The prefix is the whole scope here: these names are written by one function from one
        # run id, and no other row in this schema can match them.
        $before = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM $tUser WHERE name LIKE '$($config.SeedPrefix)%'" `
            -Description "leftover users of run '$($config.RunId)'"
        [void](Invoke-SqlScript -Sql "DELETE FROM $tUser WHERE name LIKE '$($config.SeedPrefix)%'" `
                -Description "delete $before leftover user row(s) of run '$($config.RunId)'")
        $after = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM $tUser WHERE name LIKE '$($config.SeedPrefix)%'" `
            -Description "leftover users of run '$($config.RunId)' after deletion"
        if ($after -ne 0) {
            throw "Deleting run '$($config.RunId)'s users left $after row(s)."
        }
        $record.ourUsers = @()
        $record.cleaned = $true
        $record.removed = [pscustomobject][ordered]@{
            runId = $config.RunId
            scope = "user WHERE name LIKE '$($config.SeedPrefix)%'"
            totalDeleted = $before
            note = "users only: the contest row was already gone, so there was nothing left to scope by"
        }
    }

    # The scope is cleared on every path out of here. After the branches above, either the contest this
    # run seeded has just been deleted, or there was none of this run's to begin with - and in both
    # cases a scope still pointing at an id names nothing. Left armed, the next cleanup would run its
    # statements against that dead id and fail inside the name check, which is a failure a reader has to
    # go and diagnose rather than the refusal it should have been.
    $config.ContestScopeFromSeed = $false

    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "leftovers.json") -Encoding utf8
    return $record
}
