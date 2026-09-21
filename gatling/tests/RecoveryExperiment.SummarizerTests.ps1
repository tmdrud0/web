# Tests for the pilot summarizer.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.SummarizerTests.ps1
#
# The summarizer is a script rather than a library of pure functions, so it cannot be tested by calling
# its parts - and the parts that break are not the arithmetic but the wiring around it: which property the
# conditions come from, which file the commit is read from, and whether a suite whose runs were measured
# can be summarized at all. Each case below builds a small suite in a temp directory, runs the summarizer
# against it, and reads what it wrote.
#
# What that costs is speed, and it is paid deliberately. Every case here would have passed against a
# summarizer that threw on its first measured suite (`$modeRuns.ToArray()` on an `@()` array), because a
# suite with no measured runs exits before the throw. So these cases build suites that *were* measured -
# the numbers in them are invented, and the point is the path, not the figures.
#
# No MySQL, no Redis, no containers: this runs anywhere, like the unit tests. Only the summarizer's own
# behaviour is under test; whether a real run produces sane figures is what the pilot itself answers.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\RecoveryExperiment.TestHarness.ps1"

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..\..")).FullName
$summarizerPath = Join-Path $repoRoot "gatling\summarize-recovery-pilot.ps1"

# Every column the summarizer reads off a run row. Under `Set-StrictMode -Version Latest` a missing one
# throws, so this list has to keep up with the summarizer's figure table - and when it falls behind, the
# failure names the column, which is the reminder to add it here.
$script:figureColumns = @(
    "detectionLatencyMs", "consistencyOutageMs", "backlogDrainMs", "repairDurationMs", "fullRecoveryMs",
    "recoveryLagP50Ms", "recoveryLagP95Ms", "recoveryLagMaxMs", "baselineLagP95Ms", "lagP95IncreaseMs",
    "maxStreamPendingEvents", "maxStreamOldestReadySeconds", "minStreamQueueConsumers", "maxUnappliedResults",
    "pollsWithoutConsumer", "appliedDeltaDuringRecovery", "mysqlQuestionsDelta", "mysqlRowsReadDelta",
    "redisTotalCommandsDelta", "redisEvalCallsDelta", "redisRestoreCallsDelta", "redisDelCallsDelta",
    "sequenceRoundsDelta", "sequenceReplayedDelta", "rollbackObservedDelta", "rollbackRestartsDelta",
    "maxAppProcessCpu", "gatlingSuccessPercent", "gatlingObservedP95Millis", "gatlingFailedRequests"
)

$script:scratchRoot = Join-Path ([IO.Path]::GetTempPath()) ("sbrec-summarizer-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
[void](New-Item -ItemType Directory -Path $script:scratchRoot -Force)

# A run row that a measured run would have produced: a verdict, the instants, and one figure per column.
# `Figures` names the columns to give a real number instead of `unavailable`.
function New-SummarizerRow {
    param(
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$Mode,
        [int]$RunIndex = 0,
        [string]$Complete = "True",
        [hashtable]$Figures = @{}
    )

    $row = [ordered]@{
        runId                 = $RunId
        mode                  = $Mode
        runIndex              = "$RunIndex"
        complete              = $Complete
        incompleteReasons     = ""
        faultAtUtc            = "2026-09-21T00:00:00.0000000Z"
        detectedAtUtc         = "2026-09-21T00:00:01.0000000Z"
        consistentAtUtc       = "2026-09-21T00:00:05.0000000Z"
        drainedAtUtc          = "2026-09-21T00:00:09.0000000Z"
        lostCount             = "20"
        kProcessedCount       = "60"
        kCheckpoint           = "12345"
        finalDigestMatches    = "True"
        recoveryWindowSeconds = "9"
    }
    foreach ($figure in $script:figureColumns) { $row[$figure] = "unavailable" }
    foreach ($name in $Figures.Keys) {
        if (-not $row.Contains($name)) { throw "the fixture names '$name', which is not a column of a run row" }
        $row[$name] = [string]$Figures[$name]
    }
    return $row
}

function Write-RunArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)]$Row
    )

    [void](New-Item -ItemType Directory -Path $Directory -Force)
    $csv = ($Row.Keys -join ",") + "`n" + (@($Row.Values | ForEach-Object {
                $value = [string]$_
                if ($value -match '[,"]') { '"' + $value.Replace('"', '""') + '"' } else { $value }
            }) -join ",") + "`n"
    [IO.File]::WriteAllText((Join-Path $Directory "recovery-summary.csv"), $csv, (New-Object Text.UTF8Encoding($false)))
}

# A suite directory whose metadata says what the runs ran under, with one entry per run given.
function New-SummarizerSuite {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Modes,
        [Parameter(Mandatory = $true)][object[]]$Runs,
        [string]$GitHead = "1111111111111111111111111111111111111111",
        [int]$SuiteHoldSeconds = 120
    )

    $suiteDirectory = Join-Path $script:scratchRoot $Name
    [void](New-Item -ItemType Directory -Path $suiteDirectory -Force)
    $metadata = [pscustomobject][ordered]@{
        phase                  = "calibration"
        startedAtUtc           = "20260921-000000"
        repeats                = 1
        modes                  = $Modes
        userCount              = 200
        problemCount           = 5
        contestDurationMinutes = 90
        targetRps              = 40
        submitIntervalMillis   = 5000
        rampSeconds            = 15
        holdSeconds            = $SuiteHoldSeconds
        baselineResults        = 60
        baselineWindowSeconds  = 20
        tailResults            = 20
        pollIntervalSeconds    = 2
        settleTimeoutSeconds   = 120
        drainTimeoutSeconds    = 180
        gatlingTimeoutSeconds  = 900
        ingressSloP95Millis    = 60000
        dbName                 = "oj_test"
        gitHead                = $GitHead
        runs                   = $Runs
    }
    $metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $suiteDirectory "suite-metadata.json") -Encoding utf8
    return $suiteDirectory
}

function New-SuiteRunEntry {
    param(
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$Mode,
        [int]$RunIndex = 0,
        [string]$ArtifactDirectory,
        [string]$ExitMeaning = "complete",
        [int]$ExitCode = 0
    )

    return [pscustomobject][ordered]@{
        runId             = $RunId
        mode              = $Mode
        runIndex          = $RunIndex
        exitCode          = $ExitCode
        exitMeaning       = $ExitMeaning
        artifactDirectory = $ArtifactDirectory
        stdout            = ""
        stderr            = ""
    }
}

function Invoke-Summarizer {
    param(
        [Parameter(Mandatory = $true)][string]$SuiteDirectory,
        [string]$OutputDirectory,
        [hashtable]$Conditions = @{}
    )

    $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $summarizerPath,
        "-SuiteDirectory", $SuiteDirectory, "-OutputDirectory", $OutputDirectory)
    foreach ($name in $Conditions.Keys) { $arguments += @("-$name", [string]$Conditions[$name]) }
    # A summarizer that refuses its input throws, and Windows PowerShell 5.1 turns a *native* command's
    # stderr into a terminating error while `$ErrorActionPreference` is Stop - so the child's refusal would
    # end this case as an unhandled error and hide the message the case is asserting on. Relaxed for the
    # call only, and the exit code is read as the child's.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell @arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject][ordered]@{
        ExitCode = $exitCode
        Output   = (@($output | ForEach-Object { [string]$_ }) -join "`n")
    }
}

# --- cases ------------------------------------------------------------------------------------------

Test-Case "a suite whose runs were measured is summarized, and the commit comes from the suite" {
    $runDirectory = Join-Path $script:scratchRoot "measured-run"
    Write-RunArtifact -Directory $runDirectory -Row (New-SummarizerRow -RunId "fullreplay_0" -Mode "full-replay" `
            -Figures @{ detectionLatencyMs = 1000; consistencyOutageMs = 5000 })
    $suiteDirectory = New-SummarizerSuite -Name "measured" -Modes @("full-replay") -Runs @(
        (New-SuiteRunEntry -RunId "fullreplay_0" -Mode "full-replay" -ArtifactDirectory $runDirectory))
    $outDirectory = Join-Path $script:scratchRoot "measured-out"

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory

    # Before the fix this exited 1 with "does not contain a method named 'ToArray'", after summary.csv and
    # runs.csv had been copied - a summarisation that had done its work and then failed at the last step.
    Assert-Equal 0 $result.ExitCode "a measured suite is summarized rather than failing at the last step"
    foreach ($fileName in @("summary.csv", "runs.csv", "run-metadata.json")) {
        Assert-True (Test-Path -LiteralPath (Join-Path $outDirectory $fileName)) "the curated $fileName is written"
    }
    $metadata = Get-Content -LiteralPath (Join-Path $outDirectory "run-metadata.json") -Raw | ConvertFrom-Json
    Assert-Equal "1111111111111111111111111111111111111111" ([string]$metadata.gitHead) `
        "the suite's commit reaches the curated metadata"
    $rows = @(Import-Csv -LiteralPath (Join-Path $outDirectory "runs.csv"))
    Assert-Equal 1 $rows.Count "one measured run is one row"
    Assert-Equal "full-replay" ([string]$rows[0].mode) "the row is the run's"
    # Every per-run row of the curated metadata carries the commit. `recovery-summary.csv` has no gitHead
    # column - runs.csv reproduces that file's columns - so it comes from the suite for each row.
    $runRows = @($metadata.runs)
    Assert-Equal 1 $runRows.Count "the curated metadata carries one row per measured run"
    Assert-Equal "1111111111111111111111111111111111111111" ([string]$runRows[0].gitHead) `
        "and every row carries the commit, rather than reading a column recovery-summary.csv does not have"
    Assert-Equal "2026-09-21T00:00:01.0000000Z" ([string]$runRows[0].detectedAtUtc) `
        "the row's instants are the run's own"
    # The table is keyed by figure, with one column group per mode.
    $table = @(Import-Csv -LiteralPath (Join-Path $outDirectory "summary.csv"))
    $detection = @($table | Where-Object { [string]$_.figure -eq "detectionLatencyMs" })
    Assert-Equal 1 $detection.Count "each figure is one row of the table"
    Assert-Equal "1000" ([string]$detection[0]."full-replay median") "the mode's median is the run's own value"
    Assert-Equal "ms" ([string]$detection[0].unit) "and it carries its unit"
}

Test-Case "the conditions written are the suite's, not the script's parameter defaults" {
    # The summarizer's parameters default to the pilot's settings; a calibration suite runs a different
    # hold. Printing the default would describe runs that did not happen.
    $runDirectory = Join-Path $script:scratchRoot "conditions-run"
    Write-RunArtifact -Directory $runDirectory -Row (New-SummarizerRow -RunId "redisseq_0" -Mode "redis-seq")
    $suiteDirectory = New-SummarizerSuite -Name "conditions" -Modes @("redis-seq") -SuiteHoldSeconds 120 -Runs @(
        (New-SuiteRunEntry -RunId "redisseq_0" -Mode "redis-seq" -ArtifactDirectory $runDirectory))
    $outDirectory = Join-Path $script:scratchRoot "conditions-out"

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory
    Assert-Equal 0 $result.ExitCode "the suite is summarized"
    Assert-True ($result.Output -match "holdSeconds=120") "the hold reported is the one the suite ran under"
    Assert-True ($result.Output -notmatch "holdSeconds=240") "and not the parameter's default"
    $metadata = Get-Content -LiteralPath (Join-Path $outDirectory "run-metadata.json") -Raw | ConvertFrom-Json
    Assert-Equal 120 ([int]$metadata.conditions.holdSeconds) "the written conditions agree with the printed line"
    Assert-Equal 200 ([int]$metadata.conditions.userCount) "and so does a condition left at its default"

    # An explicitly bound parameter is still the reader's to override; the suite is the fallback, not a veto.
    $overridden = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory `
        -Conditions @{ HoldSeconds = 99; UserCount = 7 }
    Assert-True ($overridden.Output -match "holdSeconds=99") "a bound hold overrides the suite's"
    Assert-True ($overridden.Output -match "users=7") "and so does a bound user count"
}

Test-Case "a run with no figures is listed rather than dropped into a clean-looking median" {
    $runDirectory = Join-Path $script:scratchRoot "mixed-measured"
    Write-RunArtifact -Directory $runDirectory -Row (New-SummarizerRow -RunId "streamoffset_0" -Mode "stream-offset" `
            -Figures @{ detectionLatencyMs = 1200; consistencyOutageMs = 4000 })
    $suiteDirectory = New-SummarizerSuite -Name "mixed" -Modes @("stream-offset") -Runs @(
        (New-SuiteRunEntry -RunId "streamoffset_0" -Mode "stream-offset" -ArtifactDirectory $runDirectory),
        (New-SuiteRunEntry -RunId "streamoffset_1" -Mode "stream-offset" -RunIndex 1 `
            -ArtifactDirectory (Join-Path $script:scratchRoot "mixed-absent") -ExitMeaning "failed to measure" -ExitCode 1))
    $outDirectory = Join-Path $script:scratchRoot "mixed-out"

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory
    Assert-Equal 0 $result.ExitCode "the one measured run is still summarized"
    Assert-True ($result.Output -match "1 run\(s\) summarized; 1 produced no figures") `
        "the run without figures is counted as producing none"
    Assert-True ($result.Output -match "streamoffset_1") "and is named in the output rather than silently dropped"
    Assert-True ($result.Output -match "stream-offset : 1 of 2 runs complete \(1 produced no figures\)") `
        "the mode's completeness counts the suite's two runs, not the one that was measured"
    $metadata = Get-Content -LiteralPath (Join-Path $outDirectory "run-metadata.json") -Raw | ConvertFrom-Json
    Assert-Equal 1 @($metadata.runsWithNoFigures).Count "the run with no figures is recorded for the reader"
    Assert-Equal "streamoffset_1" ([string]@($metadata.runsWithNoFigures)[0].runId) "and it is the right run"
    # The median is taken over what was measured, which here is one run - not two with a zero in the second.
    $table = @(Import-Csv -LiteralPath (Join-Path $outDirectory "summary.csv"))
    $detection = @($table | Where-Object { [string]$_.figure -eq "detectionLatencyMs" })
    Assert-Equal "1/2" ([string]$detection[0]."stream-offset runs") "the column reports how many of the runs it covers"
}

Test-Case "a suite where no run produced figures writes no table and says why" {
    $suiteDirectory = New-SummarizerSuite -Name "unmeasured" -Modes @("full-replay") -Runs @(
        (New-SuiteRunEntry -RunId "fullreplay_0" -Mode "full-replay" `
            -ArtifactDirectory (Join-Path $script:scratchRoot "unmeasured-absent") -ExitMeaning "failed to measure" -ExitCode 1))
    $outDirectory = Join-Path $script:scratchRoot "unmeasured-out"

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory
    Assert-Equal 1 $result.ExitCode "with nothing measured the summarizer reports failure rather than an empty table"
    Assert-True ($result.Output -match "no run of this suite produced figures") "and says so in words"
    Assert-True ($result.Output -match "fullreplay_0") "naming the run that produced none"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $outDirectory "summary.csv"))) `
        "a table of `unavailable`s is not written in place of a result"
}

Test-Case "a cost total is also reported per second of the mode's own recovery window" {
    # The deltas are totals over windows of different lengths, so the totals alone would credit whichever
    # mode recovered faster with doing less work. The per-second figure is the comparable one.
    $tenSeconds = Join-Path $script:scratchRoot "ratio-ten"
    Write-RunArtifact -Directory $tenSeconds -Row (New-SummarizerRow -RunId "fullreplay_0" -Mode "full-replay" `
            -Figures @{ mysqlQuestionsDelta = 300; redisTotalCommandsDelta = 500; recoveryWindowSeconds = 10 })
    # A window of zero cannot be divided by. That run's ratio is missing, not infinite and not zero - it
    # must not enter the median as either. (`unavailable` reaches the same branch, through `Get-Number`.)
    $noWindow = Join-Path $script:scratchRoot "ratio-none"
    Write-RunArtifact -Directory $noWindow -Row (New-SummarizerRow -RunId "fullreplay_1" -Mode "full-replay" -RunIndex 1 `
            -Figures @{ mysqlQuestionsDelta = 900; redisTotalCommandsDelta = 900; recoveryWindowSeconds = 0 })
    $suiteDirectory = New-SummarizerSuite -Name "ratio" -Modes @("full-replay") -Runs @(
        (New-SuiteRunEntry -RunId "fullreplay_0" -Mode "full-replay" -ArtifactDirectory $tenSeconds),
        (New-SuiteRunEntry -RunId "fullreplay_1" -Mode "full-replay" -RunIndex 1 -ArtifactDirectory $noWindow))
    $outDirectory = Join-Path $script:scratchRoot "ratio-out"

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory
    Assert-Equal 0 $result.ExitCode "the suite is summarized"
    $table = @(Import-Csv -LiteralPath (Join-Path $outDirectory "summary.csv"))
    $questions = @($table | Where-Object { [string]$_.figure -eq "mysqlQuestionsPerSecond" })
    Assert-Equal 1 $questions.Count "the per-second figure has its own row"
    Assert-Equal "30" ([string]$questions[0]."full-replay median") "300 questions over a 10s window is 30/s"
    Assert-Equal "1/2" ([string]$questions[0]."full-replay runs") `
        "the run with no window is counted as missing rather than divided by zero"
    $commands = @($table | Where-Object { [string]$_.figure -eq "redisTotalCommandsPerSecond" })
    Assert-Equal "50" ([string]$commands[0]."full-replay median") "and the same figure is taken for another counter"
    Assert-Equal "commands/s" ([string]$commands[0].unit) "with its own unit"
    # The total is still reported beside it: the per-second figure is a reading of the same measurement,
    # not a replacement for it.
    $totals = @($table | Where-Object { [string]$_.figure -eq "mysqlQuestionsDelta" })
    Assert-Equal "300" ([string]$totals[0]."full-replay median") "the total it was derived from is still in the table"
    $window = @($table | Where-Object { [string]$_.figure -eq "recoveryWindowSeconds" })
    Assert-Equal "s" ([string]$window[0].unit) "the window length is a figure in its own right, so any other delta can be read against it"
}

Test-Case "a run whose summary carries no verdict is kept out of the medians" {
    # A run that failed to measure can still leave a recovery-summary.csv behind - a header and no verdict,
    # or a row written before the run died. Its figures are not a measurement of anything.
    $runDirectory = Join-Path $script:scratchRoot "no-verdict"
    $row = New-SummarizerRow -RunId "redisseq_9" -Mode "redis-seq" -Figures @{ detectionLatencyMs = 99999 }
    $row["complete"] = "unavailable"
    Write-RunArtifact -Directory $runDirectory -Row $row
    $measuredDirectory = Join-Path $script:scratchRoot "verdict-measured"
    Write-RunArtifact -Directory $measuredDirectory -Row (New-SummarizerRow -RunId "redisseq_0" -Mode "redis-seq" `
            -Figures @{ detectionLatencyMs = 800 })
    $suiteDirectory = New-SummarizerSuite -Name "verdict" -Modes @("redis-seq") -Runs @(
        (New-SuiteRunEntry -RunId "redisseq_0" -Mode "redis-seq" -ArtifactDirectory $measuredDirectory),
        (New-SuiteRunEntry -RunId "redisseq_9" -Mode "redis-seq" -RunIndex 1 -ArtifactDirectory $runDirectory `
            -ExitMeaning "failed to measure" -ExitCode 1))
    $outDirectory = Join-Path $script:scratchRoot "verdict-out"

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory $outDirectory
    Assert-Equal 0 $result.ExitCode "the suite still summarizes"
    Assert-True ($result.Output -match "carries no verdict") "the reason names the missing verdict"
    $table = @(Import-Csv -LiteralPath (Join-Path $outDirectory "summary.csv"))
    $detection = @($table | Where-Object { [string]$_.figure -eq "detectionLatencyMs" })
    Assert-Equal "800" ([string]$detection[0]."redis-seq median") `
        "a row without a verdict does not pull the median toward its unrunnable figure"
}

Test-Case "a suite whose metadata cannot say what its runs ran under is refused" {
    # Without suite-metadata.json the conditions are unknown, and a table of figures with unknown
    # conditions is the thing this experiment exists to avoid producing.
    $suiteDirectory = Join-Path $script:scratchRoot "no-metadata"
    [void](New-Item -ItemType Directory -Path $suiteDirectory -Force)

    $result = Invoke-Summarizer -SuiteDirectory $suiteDirectory -OutputDirectory (Join-Path $script:scratchRoot "no-metadata-out")
    Assert-Equal 1 $result.ExitCode "a suite with no metadata is refused"
    Assert-True ($result.Output -match "has no suite-metadata.json") "and the reason names the missing file"
}

# --- report -----------------------------------------------------------------------------------------

try {
    Remove-Item -LiteralPath $script:scratchRoot -Recurse -Force -ErrorAction SilentlyContinue
}
catch {
    # A leftover temp directory is not a test failure; the cases above have already reported.
}

Write-TestSummary -Suite "RecoveryExperiment summarizer tests"
