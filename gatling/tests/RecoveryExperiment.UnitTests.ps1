# Unit tests for the recovery pilot harness.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.UnitTests.ps1
#
# Only pure functions are exercised here - no MySQL, no Redis, no containers - so this runs anywhere,
# in a second, and a failure means the harness is wrong rather than that an environment is missing.
# The parts that need a real server have their own tests: RecoveryExperiment.RedisTests.ps1 for the
# snapshot round trip and RecoveryExperiment.MySqlTests.ps1 for the oracle against real rows.
#
# The assertion harness is shared with the two suites that need a live server, so that "equal" means one
# thing across all three.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\..\lib\RecoveryExperiment.ps1"
. "$PSScriptRoot\RecoveryExperiment.TestHarness.ps1"

# --- Common ----------------------------------------------------------------------------------------

Test-Case "Format-InvariantNumber keeps the invariant decimal separator" {
    Assert-Equal "0.1" (Format-InvariantNumber -Value 0.1) "a fraction is formatted invariantly"
    Assert-Equal "1000" (Format-InvariantNumber -Value 1000) "an integer value has no trailing zeros"
}

Test-Case "ConvertTo-RequiredInt64 trims and rejects" {
    Assert-Equal 42 (ConvertTo-RequiredInt64 -Value " 42 " -Description "test") "a padded integer parses"
    Assert-Throws { ConvertTo-RequiredInt64 -Value "4.5" -Description "test" } "a fraction is not an Int64"
    Assert-Throws { ConvertTo-RequiredInt64 -Value "" -Description "test" } "an empty string is rejected"
}

Test-Case "ConvertTo-RequiredDouble rejects non-finite values" {
    Assert-Equal 1000.0 (ConvertTo-RequiredDouble -Value "1e3" -Description "test") "exponent notation parses"
    Assert-Throws { ConvertTo-RequiredDouble -Value "NaN" -Description "test" } "NaN is rejected"
    Assert-Throws { ConvertTo-RequiredDouble -Value "+Inf" -Description "test" } "infinity is rejected"
}

Test-Case "the pilot stack declares fifteen containers and no mysql" {
    $containers = Get-ExpectedPilotContainers
    Assert-Equal 15 $containers.Count "fifteen services are expected"
    foreach ($service in $containers.Keys) {
        Assert-True ($containers[$service] -like "oj-loadtest-*") "service '$service' has a project-scoped container name"
    }
    Assert-True (-not $containers.Contains("mysql")) "the stack declares no mysql container of its own"
    $services = Get-PilotStartServices
    Assert-Equal 15 $services.Count "fifteen services are started"
    Assert-Equal 0 (@($services | Where-Object { $_ -eq "mysql" }).Count) "mysql is not started"
}

Test-Case "the project declares exactly three queues" {
    $queues = Get-ProjectQueueNames
    Assert-Equal 3 $queues.Count "three queues are declared"
    Assert-True ($queues -contains "contest.judge.result.stream") "the stream queue is one of them"
}

Test-Case "Initialize-RecoveryExperiment guards the run id and the password" {
    $root = (Get-Item "$PSScriptRoot\..\..").FullName
    $artifacts = Join-Path ([IO.Path]::GetTempPath()) "sbrec-unittests"
    Assert-Throws {
        Initialize-RecoveryExperiment -WorktreeRoot $root -ArtifactDirectory $artifacts `
            -RunId "bad-run" -Mode "full-replay" -DbPassword "x"
    } "a run id with a hyphen is refused"
    Assert-Throws {
        Initialize-RecoveryExperiment -WorktreeRoot $root -ArtifactDirectory $artifacts `
            -RunId "good" -Mode "full-replay" -DbPassword "  "
    } "an empty password is refused"
}

Test-Case "Set-ExperimentContestScope moves the keys derived from the contest id" {
    $root = (Get-Item "$PSScriptRoot\..\..").FullName
    $artifacts = Join-Path ([IO.Path]::GetTempPath()) "sbrec-unittests"
    [void](Initialize-RecoveryExperiment -WorktreeRoot $root -ArtifactDirectory $artifacts `
            -RunId "unit" -Mode "stream-offset" -DbPassword "x")
    $config = Set-ExperimentContestScope -ContestId 7 -ProblemIdStart 11 -ProblemIdEnd 15 `
        -ContestStartTimeMysql "2026-01-01 00:00:00.000000"
    Assert-Equal "contest:scoreboard:7:processed" $config.ProcessedKey "the processed key follows the contest id"
    Assert-Equal "contest:scoreboard:7:ranking" $config.RankingKey "the ranking key follows the contest id"
    Assert-Equal 7 $config.ContestId "the contest id is recorded"
    Assert-Equal "contest:scoreboard:stream:offset" $config.CheckpointKey "the checkpoint key does not follow the contest id"
}

# --- Oracle ----------------------------------------------------------------------------------------

Test-Case "Add-CompetitionRanks ties equal and skips the ranks they share" {
    $standings = @(
        [pscustomobject]@{ UserId = 10L; Solved = 3; Penalty = 100L },
        [pscustomobject]@{ UserId = 11L; Solved = 3; Penalty = 100L },
        [pscustomobject]@{ UserId = 12L; Solved = 2; Penalty = 50L }
    )
    $ranked = @(Add-CompetitionRanks -Standings $standings)
    Assert-SequenceEqual @(1, 1, 3) @($ranked | ForEach-Object { $_.Rank }) "ranks are competition ranks"
}

Test-Case "New-StandingsDigest is a function of the standings in order" {
    $standings = @(
        [pscustomobject]@{ Rank = 1L; UserId = 10L; Solved = 3; Penalty = 100L },
        [pscustomobject]@{ Rank = 2L; UserId = 11L; Solved = 2; Penalty = 50L }
    )
    $first = New-StandingsDigest -ContestId 1 -Participants 2 -Standings $standings
    $second = New-StandingsDigest -ContestId 1 -Participants 2 -Standings $standings
    Assert-Equal $first.Digest $second.Digest "the same standings digest the same"

    $changed = @(
        [pscustomobject]@{ Rank = 1L; UserId = 10L; Solved = 3; Penalty = 101L },
        [pscustomobject]@{ Rank = 2L; UserId = 11L; Solved = 2; Penalty = 50L }
    )
    $third = New-StandingsDigest -ContestId 1 -Participants 2 -Standings $changed
    Assert-True ($first.Digest -ne $third.Digest) "one penalty second changes the digest"

    $reordered = @($standings[1], $standings[0])
    $fourth = New-StandingsDigest -ContestId 1 -Participants 2 -Standings $reordered
    Assert-True ($first.Digest -ne $fourth.Digest) "the same standings in another order digest differently"
    Assert-Equal 64 $first.Digest.Length "the digest is a SHA256 hex string"
}

Test-Case "Get-OracleStandingsSql counts only results the scoreboard has taken" {
    $sql = Get-OracleStandingsSql
    Assert-True ($sql -match 'scoreboard_applied_at IS NOT NULL') "the applied boundary is in the statement"
    Assert-True ($sql -match "<> 'PENDING'") "PENDING results are excluded"
    Assert-True ($sql -match 'ORDER BY score DESC, user_id ASC') "the order is the scoreboard's own"

    $all = Get-OracleStandingsSql -AllResolvedResults
    Assert-True (-not ($all -match 'scoreboard_applied_at IS NOT NULL')) "the boundary is absent when every resolved result is asked for"
    Assert-True ($all -match "<> 'PENDING'") "PENDING results are still excluded"
}

Test-Case "an ordered map is subscripted by key, and an integer subscript is a position" {
    # Why every map in the harness is keyed by a string. `OrderedDictionary` has an `Item[object]` and an
    # `Item[int]` indexer; PowerShell binds an integer subscript to the positional one. A percentile map
    # keyed 50/95/99 therefore stored its values as entries 50, 95 and 99 - and reading `[50]` back threw
    # `Parameter name: index` rather than returning what had just gone in.
    $byKey = [ordered]@{}
    $byKey["50"] = 1.5
    $byKey["95"] = 2.5
    Assert-Equal 1.5 $byKey["50"] "a string subscript reads the entry with that key"
    Assert-Equal 2 $byKey.Count "two entries, not a hundred"

    $byPosition = [ordered]@{}
    $byPosition["a"] = 1
    Assert-Equal 1 $byPosition[0] "an integer subscript reads the entry at that position"
    Assert-Throws { $null = $byPosition[50] } "an integer subscript past the end is an out-of-range index, not a missing key"
}

Test-Case "Get-PercentileOffset counts the way CEIL(count * p / 100) counts" {
    # These are the values MySQL would have to compute if the offset could be an expression there. They
    # are here because it cannot be: the statement takes a literal, so the arithmetic is this function's
    # job and this case is the only place it is checked against the rule rather than against itself.
    Assert-Equal 0 (Get-PercentileOffset -Count 1 -Percentile 50) "p50 of one value is the first value"
    Assert-Equal 1 (Get-PercentileOffset -Count 3 -Percentile 50) "p50 of three values is the second"
    Assert-Equal 1 (Get-PercentileOffset -Count 4 -Percentile 50) "p50 of four values is the second"
    Assert-Equal 4 (Get-PercentileOffset -Count 9 -Percentile 50) "p50 of nine values is the fifth"
    Assert-Equal 8 (Get-PercentileOffset -Count 9 -Percentile 95) "p95 of nine values is the last"
    Assert-Equal 8 (Get-PercentileOffset -Count 9 -Percentile 99) "p99 of nine values is the last"
    Assert-Equal 7 (Get-PercentileOffset -Count 8 -Percentile 95) "p95 of eight values is the eighth"
    Assert-Equal 98 (Get-PercentileOffset -Count 100 -Percentile 99) "p99 of a hundred values is the ninety-ninth"
    Assert-Equal 189 (Get-PercentileOffset -Count 200 -Percentile 95) "p95 of two hundred values is the hundred-and-ninetieth"
    Assert-Equal 0 (Get-PercentileOffset -Count 0 -Percentile 99) "an empty list has no offset to give"
    Assert-Throws { Get-PercentileOffset -Count 10 -Percentile 0 } "a percentile below one is refused"
    Assert-Throws { Get-PercentileOffset -Count 10 -Percentile 101 } "a percentile above a hundred is refused"
}

Test-Case "Get-ReflectLatencyStats validates a supplied bound and accepts an omitted one" {
    # The optional upper bound is what the omission test covers: a `[string]`-typed parameter defaults to
    # the empty string rather than to null, so before this was made untyped, leaving the argument out was
    # the same as passing `''` and was rejected as a datetime literal. The integration test found it; this
    # case pins the half that can be checked without a database, which is that a bad bound is refused.
    Assert-Throws {
        Get-ReflectLatencyStats -AppliedAtOrAfter "2026-09-21 10:00:00" -AppliedBefore "" -Description "test"
    } "an empty upper bound is not a datetime literal"
    Assert-Throws {
        Get-ReflectLatencyStats -AppliedAtOrAfter "2026-09-21 10:00:00" -AppliedBefore "2026-09-21" -Description "test"
    } "a date without a time is not a datetime literal"
    Assert-Throws {
        Get-ReflectLatencyStats -AppliedAtOrAfter "yesterday" -Description "test"
    } "a lower bound that is not a datetime literal is refused"
}

Test-Case "Get-StandingsDifference names what differs in both directions" {
    $api = @(
        [pscustomobject]@{ Rank = 1L; UserId = 10L; Solved = 3; Penalty = 100L },
        [pscustomobject]@{ Rank = 2L; UserId = 11L; Solved = 2; Penalty = 50L }
    )
    $oracle = @(
        [pscustomobject]@{ Rank = 1L; UserId = 10L; Solved = 3; Penalty = 100L },
        [pscustomobject]@{ Rank = 2L; UserId = 12L; Solved = 2; Penalty = 50L }
    )
    $difference = Get-StandingsDifference -Api $api -Oracle $oracle
    Assert-Equal 1 $difference.MissingFromScoreboard "the oracle's extra user is missing from the scoreboard"
    Assert-Equal 1 $difference.NotInOracle "the scoreboard's extra user is not in the oracle"

    $wrongPenalty = @(
        [pscustomobject]@{ Rank = 1L; UserId = 10L; Solved = 3; Penalty = 999L },
        [pscustomobject]@{ Rank = 2L; UserId = 11L; Solved = 2; Penalty = 50L }
    )
    $difference = Get-StandingsDifference -Api $wrongPenalty -Oracle $api
    Assert-Equal 1 $difference.WrongScoreOrPenalty "a penalty difference is reported"
    Assert-Equal 0 $difference.WrongRank "a penalty difference is not also a rank difference"
    Assert-Equal 0 $difference.MissingFromScoreboard "no user is missing"
}

# --- Injector --------------------------------------------------------------------------------------

Test-Case "Get-LostResultSet is set membership and never offset arithmetic" {
    # The fixture is the shape a real run has, in the direction a real run has it: the scoreboard gained
    # results between the snapshot and the rollback, so the later reading is the larger set and what the
    # rollback erased is the difference. The members are deliberately not a contiguous range - offsets are
    # not consecutive, and a set that could be reconstructed by arithmetic would hide that.
    $snapshot = @("1", "2", "3", "4", "9")
    $preRollback = @("1", "2", "3", "4", "9", "11", "12", "13")
    $lost = Get-LostResultSet -SnapshotMembers $snapshot -PreRollbackMembers $preRollback
    Assert-Equal 5 $lost.SnapshotCount "the snapshot count is the earlier set's size"
    Assert-Equal 8 $lost.PreRollbackCount "the pre-rollback count is the later set's size"
    Assert-Equal 3 $lost.LostCount "three members were applied after the snapshot and erased by the rollback"
    Assert-SequenceEqual @("11", "12", "13") @($lost.Lost | Sort-Object) "the lost set is the difference"

    # A rollback to the instant that was just captured erases nothing, which is the only case where the
    # empty answer is the right one.
    $identical = Get-LostResultSet -SnapshotMembers $snapshot -PreRollbackMembers $snapshot
    Assert-Equal 0 $identical.LostCount "a rollback to the instant just captured lost nothing"

    # The reversed arguments are the mistake this test exists to catch: they produce an empty lost set on
    # every healthy run, and an empty lost set reads as an instantaneous, complete recovery.
    $reversed = Get-LostResultSet -SnapshotMembers $preRollback -PreRollbackMembers $snapshot
    Assert-Equal 0 $reversed.LostCount "the reversed arguments silently report nothing lost"
}

Test-Case "Get-LostSetProgress counts what came back" {
    $lost = @("3", "4", "11")
    $none = Get-LostSetProgress -Lost $lost -CurrentMembers @("1", "2", "9")
    Assert-Equal 0 $none.ReappliedCount "nothing has come back yet"
    Assert-Equal 3 $none.LostCount "the lost set size is unchanged"
    Assert-True (-not $none.Complete) "an empty recovery is not complete"

    $partial = Get-LostSetProgress -Lost $lost -CurrentMembers @("1", "3", "11")
    Assert-Equal 2 $partial.ReappliedCount "two of three are back"
    Assert-True (-not $partial.Complete) "a partial recovery is not complete"

    $complete = Get-LostSetProgress -Lost $lost -CurrentMembers @("1", "2", "3", "4", "11", "12")
    Assert-Equal 3 $complete.ReappliedCount "all three are back"
    Assert-True $complete.Complete "a full recovery is complete"

    $vacuous = Get-LostSetProgress -Lost @() -CurrentMembers @("1")
    Assert-True $vacuous.Complete "a rollback that lost nothing is complete by definition"
}

Test-Case "the snapshot directories are run-scoped and label-scoped" {
    Assert-Equal "/tmp/sbrec-snapshot-unit" (Get-RecoveryConfig).SnapshotDirectory "the run id names the container directory"
    Assert-Equal "/tmp/sbrec-snapshot-unit/k" (Get-SnapshotDirectoryInContainer -Label "k") "each snapshot gets its own directory inside it"
    Assert-Equal "/tmp/sbrec-snapshot-unit/verify" (Get-SnapshotDirectoryInContainer -Label "verify") "the verification capture does not overwrite the snapshot"
    $hostPath = Get-SnapshotDirectoryOnHost -Label "k"
    Assert-True ($hostPath.EndsWith("snapshot\k")) "the host copy is under the artifact directory: $hostPath"
}

# --- Sampler ---------------------------------------------------------------------------------------

Test-Case "the sample schema has no duplicate columns" {
    $columns = Get-SampleColumnNames
    $unique = @($columns | Sort-Object -Unique)
    Assert-Equal $columns.Count $unique.Count "every column name is unique"
    foreach ($required in @(
            "phase", "elapsedMs", "pollDurationMs", "checkpointOffset", "digestMatches",
            "oracleObservedAtUtc", "digestObservedAtUtc", "lostTotal", "lostReapplied", "quiescent")) {
        Assert-True ($columns -contains $required) "the schema carries '$required'"
    }
}

Test-Case "a missing observation is unavailable, not zero" {
    $observation = [ordered]@{ "digestMatches" = $true; "streamPendingEvents" = 0; "oracleParticipants" = 12.5 }
    $row = New-RecoverySampleRow -Phase "recovery" -ElapsedMs 1500 -Observation $observation
    Assert-Equal "true" $row.digestMatches "a boolean is written as text"
    Assert-Equal "0" $row.streamPendingEvents "a measured zero stays a zero"
    Assert-Equal "12.5" $row.oracleParticipants "a double keeps its invariant separator"
    Assert-Equal "unavailable" $row.apiDigest "a column no source filled is unavailable"
    Assert-Equal "recovery" $row.phase "the phase is written"
    Assert-Equal "1500" $row.elapsedMs "the elapsed time is written"
}

Test-Case "ConvertTo-CsvField quotes only what has to be quoted" {
    Assert-Equal "plain" (ConvertTo-CsvField -Value "plain") "a bare value is not quoted"
    Assert-Equal '"a,b"' (ConvertTo-CsvField -Value "a,b") "a comma forces quoting"
    Assert-Equal '"a""b"' (ConvertTo-CsvField -Value 'a"b') "an embedded quote is doubled"
}

Test-Case "the sample CSV has one header, appended rows, and no byte order mark" {
    $path = Join-Path ([IO.Path]::GetTempPath()) ("sbrec-csv-" + [Guid]::NewGuid().ToString("N") + ".csv")
    try {
        $first = New-RecoverySampleRow -Phase "baseline" -ElapsedMs 0 -Observation @{}
        $second = New-RecoverySampleRow -Phase "recovery" -ElapsedMs 1000 -Observation @{}
        Write-RecoverySampleCsv -Path $path -Rows @($first)
        Write-RecoverySampleCsv -Path $path -Rows @($second)
        $bytes = [IO.File]::ReadAllBytes($path)
        Assert-True ($bytes[0] -ne 0xEF) "the file does not start with a byte order mark"
        $lines = @(Get-Content -LiteralPath $path)
        Assert-Equal 3 $lines.Count "a header and two rows are written"
        Assert-Equal (Get-SampleColumnNames).Count ($lines[0] -split ",").Count "the header carries every column"
        Assert-Equal "baseline" ($lines[1] -split ",")[3] "the first row is the first phase"
        Assert-Equal "recovery" ($lines[2] -split ",")[3] "the second row is the second phase"
    }
    finally {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

Test-Case "the histogram quantile interpolates inside the bucket that holds it" {
    $buckets = @(
        [pscustomobject]@{ UpperBound = 1.0; CumulativeCount = 0.0 },
        [pscustomobject]@{ UpperBound = 2.0; CumulativeCount = 5.0 },
        [pscustomobject]@{ UpperBound = [double]::PositiveInfinity; CumulativeCount = 5.0 }
    )
    Assert-Equal 1.5 (Get-PrometheusHistogramQuantile -Buckets $buckets -Quantile 0.5) "the median interpolates to the bucket's midpoint"

    $open = @(
        [pscustomobject]@{ UpperBound = 1.0; CumulativeCount = 1.0 },
        [pscustomobject]@{ UpperBound = [double]::PositiveInfinity; CumulativeCount = 3.0 }
    )
    Assert-Equal 1.0 (Get-PrometheusHistogramQuantile -Buckets $open -Quantile 0.9) "a quantile in the open bucket reports the last edge"

    Assert-Equal $null (Get-PrometheusHistogramQuantile -Buckets @() -Quantile 0.95) "no buckets has no answer"
    $empty = @([pscustomobject]@{ UpperBound = 1.0; CumulativeCount = 0.0 })
    Assert-Equal $null (Get-PrometheusHistogramQuantile -Buckets $empty -Quantile 0.95) "no observations has no answer"
    Assert-Throws { Get-PrometheusHistogramQuantile -Buckets $buckets -Quantile 1.5 } "an impossible quantile is refused"
}

Test-Case "Redis INFO pairs and Prometheus label escapes are read literally" {
    $pairs = ConvertFrom-RedisInfoPairs -Value "calls=12,usec=345,usec_per_call=28.75"
    Assert-Equal "12" $pairs["calls"] "the call count is read"
    Assert-Equal "28.75" $pairs["usec_per_call"] "the per-call cost is read"
    Assert-Equal "contest.judge.result.stream" (Remove-PrometheusLabelEscapes -Value 'contest\.judge\.result\.stream') "escaped dots are unescaped"
    Assert-Equal "contest.judge.result.stream" (Remove-PrometheusLabelEscapes -Value "contest.judge.result.stream") "unescaped dots are left alone"
}

Test-Case "Docker log timestamps parse at nanosecond precision" {
    $instant = ConvertFrom-DockerLogTimestamp -Value "2026-09-21T10:00:00.123456789Z"
    Assert-Equal "2026-09-21T10:00:00.1234567+00:00" $instant.ToString("yyyy-MM-ddTHH:mm:ss.fffffffzzz") "the fraction is truncated, not rejected"
    Assert-Throws { ConvertFrom-DockerLogTimestamp -Value "not a timestamp" } "an unparseable stamp is refused"
}

Test-Case "each recovery log line is recognised as the event it reports" {
    $lines = @(
        "2026-09-21T10:00:00.000000001Z 2026-09-21T10:00:00.000Z  WARN 1 --- [sched] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : Redis scoreboard offset rolled back from 41 to 33; mode=full-replay rebuilds history from its own basis and leaves the consumer where it is, so nothing published while it rebuilds is missed",
        "2026-09-21T10:00:01.000000001Z 2026-09-21T10:00:01.000Z  WARN 1 --- [sched] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : Rebuilt the scoreboard history through offset 41 with the full-replay basis; the consumer may now anchor the checkpoint past it",
        "2026-09-21T10:00:02.000000001Z 2026-09-21T10:00:02.000Z  WARN 1 --- [sched] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : Redis scoreboard offset rolled back from 12 to 5; resubscribing from the stored offset",
        "2026-09-21T10:00:03.000000001Z 2026-09-21T10:00:03.000Z  WARN 1 --- [sched] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : The redis-seq basis did not rebuild the history the rollback took away between offsets 5 and 12 (BUSY_RETRY_LATER); the range is unanswered and is asked about again on the next supervisor cycle",
        "2026-09-21T10:00:04.000000001Z 2026-09-21T10:00:04.000Z ERROR 1 --- [sched] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : The redis-seq basis cannot rebuild the history the rollback took away between offsets 5 and 12; the scoreboard stays short there and the checkpoint stays put until someone replays from MySQL or changes the mode",
        "2026-09-21T10:00:05.000000001Z 2026-09-21T10:00:05.000Z  WARN 1 --- [sched] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : Resubscribing the scoreboard stream consumer at 41 to re-read a failed batch",
        "2026-09-21T10:00:06.000000001Z 2026-09-21T10:00:06.000Z  INFO 1 --- [main] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : Started scoreboard stream consumer at 0",
        "2026-09-21T10:00:07.000000001Z 2026-09-21T10:00:07.000Z  INFO 1 --- [main] c.m.o.w.c.s.s.ContestScoreboardStreamLifecycle : Holding the scoreboard stream consumer until the stream-offset history recovery has run; the container will be started once it has",
        "not a log line at all"
    )
    $events = @(Select-RecoveryLogEvents -Lines $lines)
    Assert-SequenceEqual @(
        "detected-nonrewinding", "rebuilt", "detected-rewinding", "unanswered", "unrecoverable",
        "failed-batch-resubscribe", "consumer-started", "consumer-held"
    ) @($events | ForEach-Object { $_.Kind }) "every event is recognised in the order it happened"

    $detection = $events[0]
    Assert-SequenceEqual @("41", "33", "full-replay") @($detection.Fields) "the detection carries both offsets and the mode"
    Assert-Equal "2026-09-21T10:00:00.0000000+00:00" $detection.Instant.ToString("yyyy-MM-ddTHH:mm:ss.fffffffzzz") "the event carries its own instant"

    $rewinding = Get-FirstRecoveryEvent -Events $events -Kinds @("detected-rewinding", "detected-nonrewinding")
    Assert-Equal "detected-nonrewinding" $rewinding.Kind "the first detection is the earliest one, whichever shape it takes"
    Assert-Equal $null (Get-FirstRecoveryEvent -Events $events -Kinds @("nothing-like-this")) "an absent event is null rather than a fabricated one"
}

# --- leftovers -------------------------------------------------------------------------------------

Test-Case "a leftover row's name is attributed to the run id that wrote it" {
    Assert-Equal "fullreplay_1" (Get-RunIdFromContestName -Name "sbrec_fullreplay_1_contest") "the run id is read out of a contest name"
    Assert-Equal "redisseq_3" (Get-RunIdFromContestName -Name "sbrec_redisseq_3_contest") "and out of one whose run id carries no underscore"
    Assert-Equal "streamoffset_12" (Get-RunIdFromContestName -Name "sbrec_streamoffset_12_contest") "and out of a two-digit run index"

    Assert-Equal "fullreplay_1" (Get-RunIdFromUserName -Name "sbrec_fullreplay_1_user_7") "the run id is read out of a user name"
    Assert-Equal "streamoffset_12" (Get-RunIdFromUserName -Name "sbrec_streamoffset_12_user_200") "and out of the last user of the population"

    # Names this experiment never writes. Each has to answer `$null` rather than a substring, because a
    # wrong answer here is a leftover attributed to the wrong run - and the cleanup it feeds deletes
    # rows by run id.
    Assert-Equal $null (Get-RunIdFromContestName -Name "sbrec_fullreplay_1_problem_2") "a problem name is not a contest name"
    Assert-Equal $null (Get-RunIdFromContestName -Name "loadtest_contest") "a name without the prefix is not this experiment's"
    Assert-Equal $null (Get-RunIdFromContestName -Name "sbrec__contest") "an empty run id is refused rather than read as one"
    Assert-Equal $null (Get-RunIdFromUserName -Name "sbrec_fullreplay_1_contest") "a contest name is not a user name"
    Assert-Equal $null (Get-RunIdFromUserName -Name "sbrec_fullreplay_1_user_") "a user name with no index is not a seeded user"
    Assert-Equal $null (Get-RunIdFromUserName -Name "sbrec_fullreplay_1_user_7x") "a trailing character in the index is not a seeded user"
}

# --- the harness's own sources ---------------------------------------------------------------------
# The two defects below were found by running the harness and not by reading it, and each of them made a
# whole calibration suite say something untrue. Both are silent in the way that matters - the first
# crashed every run of a suite, and the second reported those crashes as three complete runs - so they
# are checked here rather than left to the next person to rediscover at the cost of a suite each.

function Get-HarnessSourceFiles {
    $directory = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
    $files = @(Get-ChildItem -Path $directory -Filter "*.ps1" -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike "*\tests\*" -and $_.FullName -notlike "*\build\*" })
    # Returned through the pipeline, so callers wrap the call in `@(...)`: `, $files` would instead hand
    # a `foreach` one object that is the whole array, and every file would then be read as a list of
    # paths rather than one at a time.
    return $files
}

# The argument list of every `Invoke-Docker -Arguments @( ... )` in a source file. The scan balances
# parentheses so that a call nested inside the list - `@(Invoke-Compose ...)` beside it, or a nested
# `@(...)` - does not end the list early.
function Get-DockerArgumentLists {
    param([Parameter(Mandatory = $true)][string]$Text)

    $lists = New-Object 'System.Collections.Generic.List[string]'
    $needle = "Invoke-Docker -Arguments @("
    $index = $Text.IndexOf($needle)
    while ($index -ge 0) {
        $start = $index + $needle.Length
        $depth = 1
        $position = $start
        while ($position -lt $Text.Length -and $depth -gt 0) {
            if ($Text[$position] -eq '(') { $depth++ }
            elseif ($Text[$position] -eq ')') { $depth-- }
            $position++
        }
        $lists.Add($Text.Substring($start, $position - $start - 1))
        $index = $Text.IndexOf($needle, $position)
    }
    # Same contract as Get-HarnessSourceFiles: callers wrap the call in `@(...)`, so an empty result is
    # an empty array rather than nothing, and a `foreach` sees the lists rather than one array of them.
    return $lists.ToArray()
}

Test-Case "no plain docker exec is given a flag only docker compose exec accepts" {
    # `-T` turns the pseudo-TTY off, and it belongs to `docker compose exec`. Plain `docker exec` has no
    # such flag: it exits 125 with 'unknown shorthand flag' instead of running the command, so the two
    # queue statements that carried it failed every run at step 1. The harness never needs a TTY flag -
    # nothing it execs is interactive - so none of these should appear at all.
    $composeOnly = @("-T", "--no-TTY", "-it")
    $checked = 0
    foreach ($source in @(Get-HarnessSourceFiles)) {
        $text = Get-Content -LiteralPath $source.FullName -Raw
        foreach ($list in @(Get-DockerArgumentLists -Text $text)) {
            $arguments = @([regex]::Matches($list, '"([^"]*)"') | ForEach-Object { $_.Groups[1].Value })
            if ($arguments.Count -eq 0 -or $arguments[0] -ne "exec") { continue }
            $checked++
            foreach ($flag in $composeOnly) {
                Assert-True ($arguments -notcontains $flag) `
                    "$($source.Name) passes '$flag' to docker exec, which rejects the call: docker $($arguments -join ' ')"
            }
        }
    }
    Assert-True ($checked -ge 3) "the scan found the harness's docker exec calls ($checked found)"
}

Test-Case "a process whose exit code is read has its handle read first" {
    # With redirected output, a Process object from `Start-Process -PassThru` reports `ExitCode` as null
    # until its handle has been touched. One `[int]` parameter then bound that null to 0, which is this
    # harness's word for a complete run, and a suite of three crashed runs was written down as three
    # complete ones. The check is ordering: `.Handle` has to come before the first `.ExitCode` in the
    # same file, because it is the same Process object that both are read from.
    foreach ($source in @(Get-HarnessSourceFiles)) {
        $text = Get-Content -LiteralPath $source.FullName -Raw
        $exitCode = $text.IndexOf(".ExitCode")
        if ($exitCode -lt 0) { continue }
        $handle = $text.IndexOf(".Handle")
        Assert-True ($handle -ge 0 -and $handle -lt $exitCode) `
            "$($source.Name) reads .ExitCode without reading .Handle first, so the code it reads is null"
    }
}

# --- report -------------------------------------------------------------------------------------

Write-TestSummary -Suite "RecoveryExperiment unit tests"
