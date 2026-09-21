# One run of the scoreboard recovery pilot.
#
#   $env:DB_PASSWORD = '<password>'
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-pilot.ps1 `
#     -Mode full-replay -RunIndex 1
#
# The run does eleven things, in this order, and every one of them is a step the experiment plan fixes
# so that three modes can be compared:
#
#   1. reset      take back an earlier attempt at this run id, empty the stream queue, empty the
#                 dedicated Redis instance, and record the rows this run must not touch
#   2. seed       create the contest, its problems and its users, under a name prefix that is this
#                 run's alone
#   3. start      bring the pilot stack up with the mode in the batch role's environment, and read the
#                 mode back out of the container rather than trusting the file that set it
#   4. validate   the oracle and the scoreboard agree *before* any load, which is what makes a later
#                 disagreement evidence about the fault rather than about the harness
#   5. load       start Gatling in the background and leave it running; from here to the end of the run
#                 new submissions and new judged results keep arriving, which is the condition the
#                 whole experiment is about
#   6. baseline   wait for enough applied results, then hold one window of normal operation and take
#                 the latency distribution that every later figure is compared against
#   7. capture    pause the batch role, snapshot the scoreboard namespace key by key, and read back the
#                 contest window - one instant, so the checkpoint and the standings describe the same
#                 set of applied results
#   8. tail       let exactly `-TailResults` more results be applied past that instant, then inject the
#                 fault: delete the namespace and write the snapshot back byte for byte, verifying it
#                 took before the batch role is resumed
#   9. observe    poll once per `-PollIntervalSeconds` until the scoreboard agrees with MySQL again and
#                 the pipeline is quiet, recording every reading rather than only the last
#  10. finish     stop the load, wait for the queue to drain, and validate the end state the same way
#                 the start state was validated
#  11. clean up   delete this run's rows by scope, and report what was deleted, table by table
#
# The one figure this run exists to produce is `T_consistent`, and it is decided by comparing a digest
# of the product's own scoreboard API with a digest of the standings MySQL implies. A mode that reports
# its recovery complete, or logs that it rebuilt something, has not been shown to have recovered: only
# the agreement of the two digests is that, which is why the run keeps polling until they agree and
# records the instant they first did.
#
# Nothing here writes to MySQL except through the seeder, which scopes every statement to a contest it
# inserted and a user prefix this run owns. Nothing writes to Redis except the rollback injector, which
# refuses to run unless the container is this project's own `redis` service.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet("full-replay", "redis-seq", "stream-offset")][string]$Mode,
    # Only names the run. It is part of the run id, so a repeat of the same mode is a different row in
    # the database and a different key in the artifacts rather than an overwrite of the last attempt.
    [Parameter(Mandatory = $true)][int]$RunIndex,

    # --- calibrated conditions, one value for all three modes ---------------------------------------
    [int]$UserCount = 200,
    [int]$ProblemCount = 5,
    [int]$ContestDurationMinutes = 90,
    [double]$TargetRps = 40,
    [long]$SubmitIntervalMillis = 5000,
    [int]$RampSeconds = 15,
    [int]$HoldSeconds = 240,
    [int]$BaselineResults = 60,
    [int]$BaselineWindowSeconds = 20,
    [int]$TailResults = 20,
    [int]$PollIntervalSeconds = 2,
    [int]$SettleTimeoutSeconds = 120,
    [int]$DrainTimeoutSeconds = 180,
    [int]$GatlingTimeoutSeconds = 900,
    # A ceiling the run is not expected to reach, on purpose. The pilot measures how new results are
    # delayed while the scoreboard recovers, so a p95 assertion set at the service level objective would
    # turn the measurement into a failed run and discard the figures that describe the thing being
    # measured. What is asserted is that the ingress was answered: every request either succeeded or did
    # not, and a run that stopped answering at all is a failure of the run.
    [int]$IngressSloP95Millis = 60000,

    [string]$ArtifactRoot = "var\scoreboard-recovery",
    # Left empty so that the database the harness reads and the one the stack connects to are resolved
    # from the same place. The overlay names it `RECOVERY_PILOT_DB_NAME`, and a run whose harness read
    # one schema while the application wrote another would report a scoreboard that never moved.
    [string]$DbName = "",
    # Rebuild the five application images first. Off by default because it costs minutes and nothing in
    # this experiment changes the application between runs.
    [switch]$Build,
    # Leave the application tier running when the run ends. Off by default: the next run's queue reset
    # requires that nothing is consuming the stream, and it refuses rather than deleting a queue out
    # from under a live consumer.
    [switch]$KeepStackRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\lib\RecoveryExperiment.ps1"

# --- naming -----------------------------------------------------------------------------------------

# The mode without its hyphen, because a run id becomes a SQL LIKE pattern and a Redis key suffix and
# both are easier to reason about without one. `fullreplay_1` is the first full-replay run.
function Get-PilotRunId {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$RunIndex
    )

    return ("{0}_{1}" -f ($Mode -replace '-', ''), $RunIndex)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Object
    )

    $Object | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding utf8
}

# --- reading a poll row -----------------------------------------------------------------------------

# `unavailable` is a string in the sample schema and a number in the summary. These three are the only
# places the two are told apart, so a figure the harness could not read is never silently summed as a
# zero - which for a counter delta would read as "this cost nothing".
function Get-RowNumber {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $text = [string]$Row.$Name
    if ($text -eq "unavailable" -or [string]::IsNullOrWhiteSpace($text)) { return $null }
    $value = 0d
    if (-not [double]::TryParse($text, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
        throw "Column '$Name' holds '$text', which is neither a number nor 'unavailable'."
    }
    return $value
}

function Get-RowTruth {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$Name
    )

    return ([string]$Row.$Name -eq "true")
}

# The instant a poll observed the fact it is being read for, rather than the instant the poll began.
#
# A poll stamps its start and then spends 2.3-3.2s reading Prometheus, Redis, the pipeline, MySQL and the
# API, so a moment placed at the poll's start is placed up to a poll period early - and `T_consistent`,
# `T_backlog_drained` and everything derived from them are exactly such moments. The sampler records the
# observation instants as its readings land, so a figure taken from them describes the recovery instead
# of the harness's polling.
#
# A row recorded before these columns existed, or a poll where the reader did not run, has no value for
# them; both answer the fallback, so a figure degrades to what the harness reported before rather than
# failing outright.
function Get-PollObservationInstant {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$Field,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Fallback
    )

    $value = [string]$Row.$Field
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq "unavailable") { return $Fallback }
    return [DateTimeOffset]$value
}

function Get-MaxAcross {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $max = $null
    foreach ($row in $Rows) {
        $value = Get-RowNumber -Row $row -Name $Name
        if ($null -eq $value) { continue }
        if ($null -eq $max -or $value -gt $max) { $max = $value }
    }
    return $max
}

# The least reading over a slice, for the columns whose interesting direction is downwards - the
# ranking's size while a rollback has taken results away, and the consumer count while a mode has
# stopped its consumer on purpose.
function Get-MinAcross {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $min = $null
    foreach ($row in $Rows) {
        $value = Get-RowNumber -Row $row -Name $Name
        if ($null -eq $value) { continue }
        if ($null -eq $min -or $value -lt $min) { $min = $value }
    }
    return $min
}

# The last reading minus the first, over a slice of polls. Counters only ever climb for the life of a
# process, so the difference is that window's cost - and when either end is `unavailable` the answer is
# `unavailable` rather than the other end's value.
function Get-DeltaAcross {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Rows.Count -eq 0) { return $null }
    $first = Get-RowNumber -Row $Rows[0] -Name $Name
    $last = Get-RowNumber -Row $Rows[$Rows.Count - 1] -Name $Name
    if ($null -eq $first -or $null -eq $last) { return $null }
    return $last - $first
}

function Format-PilotNumber {
    param($Value)

    if ($null -eq $Value) { return "unavailable" }
    return Format-InvariantNumber -Value ([double]$Value)
}

function Format-PilotElapsed {
    param($FromUtc, $ToUtc)

    if ($null -eq $FromUtc -or $null -eq $ToUtc) { return "unavailable" }
    return Format-InvariantNumber -Value ([math]::Round(([DateTimeOffset]$ToUtc - [DateTimeOffset]$FromUtc).TotalMilliseconds, 1))
}

# The first poll that satisfies a predicate, or `$null`. Written once because six of the run's figures
# are "the first poll where X" and each of them has to be the *first*, not the last or any.
function Select-FirstRow {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][scriptblock]$Predicate
    )

    foreach ($row in $Rows) {
        if (& $Predicate $row) { return $row }
    }
    return $null
}

# --- the run ----------------------------------------------------------------------------------------

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
$runId = Get-PilotRunId -Mode $Mode -RunIndex $RunIndex
$artifacts = Join-Path $repoRoot (Join-Path $ArtifactRoot ("{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $runId))

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw "DB_PASSWORD is not set. Export it before running: the harness never reads it from a file, and it is never written to an artifact."
}

# The same variable the overlay reads, so the schema this harness measures is the schema the stack was
# pointed at. A mismatch is not silent: `Assert-BatchRecoveryMode` reads the batch role's own DB_NAME
# back out of its environment and this run refuses when the two disagree.
$resolvedDbName = if (-not [string]::IsNullOrWhiteSpace($DbName)) { $DbName }
elseif (-not [string]::IsNullOrWhiteSpace($env:RECOVERY_PILOT_DB_NAME)) { $env:RECOVERY_PILOT_DB_NAME }
elseif (-not [string]::IsNullOrWhiteSpace($env:DB_NAME)) { $env:DB_NAME }
else { "oj_test" }

[void](Initialize-RecoveryExperiment -WorktreeRoot $repoRoot -ArtifactDirectory $artifacts `
        -RunId $runId -Mode $Mode -DbPassword $env:DB_PASSWORD -DbName $resolvedDbName)
$config = Get-RecoveryConfig

$concurrentUsers = [long][math]::Ceiling($TargetRps * $SubmitIntervalMillis / 1000.0)
$scheduledSubmissions = [long][math]::Floor((((1 + $TargetRps) / 2.0) * $RampSeconds) + ($TargetRps * $HoldSeconds))
$expectedRequests = $scheduledSubmissions + $concurrentUsers
$minRequests = [long][math]::Floor($expectedRequests * 0.96)
if ($concurrentUsers -gt $UserCount) {
    throw ("A rate of $TargetRps/s at a ${SubmitIntervalMillis}ms pace needs $concurrentUsers sessions " +
        "but the seed creates $UserCount users. Lower -TargetRps, shorten -SubmitIntervalMillis, or raise -UserCount.")
}

$samplesPath = Join-Path $artifacts "samples\polls.csv"
[void](New-Item -ItemType Directory -Path (Split-Path -Parent $samplesPath) -Force)

$startedAtUtc = [DateTimeOffset]::UtcNow
$runRecord = [ordered]@{
    runId = $runId
    mode = $Mode
    runIndex = $RunIndex
    startedAtUtc = $startedAtUtc.UtcDateTime.ToString("o")
    worktreeRoot = $repoRoot
    artifactDirectory = $artifacts
    dbName = $config.DbName
    targetRps = $TargetRps
    submitIntervalMillis = $SubmitIntervalMillis
    concurrentUsers = $concurrentUsers
    rampSeconds = $RampSeconds
    holdSeconds = $HoldSeconds
    baselineResults = $BaselineResults
    baselineWindowSeconds = $BaselineWindowSeconds
    tailResults = $TailResults
    pollIntervalSeconds = $PollIntervalSeconds
    userCount = $UserCount
    problemCount = $ProblemCount
    contestDurationMinutes = $ContestDurationMinutes
    scheduledSubmissions = $scheduledSubmissions
    expectedRequests = $expectedRequests
    minRequests = $minRequests
    ingressSloP95Millis = $IngressSloP95Millis
    gitHead = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    gitStatusPorcelain = @(& git -C $repoRoot status --porcelain 2>$null)
}

$script:polls = New-Object 'System.Collections.Generic.List[object]'
$script:pollIndex = 0
$script:gatlingProcess = $null
$script:stackStarted = $false
$script:cleanupDone = $false
$script:seed = $null

function Invoke-PilotPoll {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        [AllowEmptyCollection()][string[]]$Lost = @()
    )

    $script:pollIndex = $script:pollIndex + 1
    $elapsedMs = [long]([DateTimeOffset]::UtcNow - $startedAtUtc).TotalMilliseconds
    $observation = Get-RecoveryObservation -PollIndex $script:pollIndex -Phase $Phase -ElapsedMs $elapsedMs -Lost $Lost
    $row = New-RecoverySampleRow -Phase $Phase -ElapsedMs $elapsedMs -Observation $observation
    # Appended as it is produced rather than saved at the end: a run that fails during the recovery
    # still leaves the polls that describe what it saw.
    Write-RecoverySampleCsv -Path $samplesPath -Rows @($row)
    $script:polls.Add($row)
    return $row
}

function Wait-PilotCondition {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][scriptblock]$Predicate,
        [AllowEmptyCollection()][string[]]$Lost = @(),
        # The load generator, for a wait that is for something the load is supposed to cause. Without it a
        # generator that died one second in is indistinguishable from a pipeline that never applied a
        # result: the wait runs out its timeout and reports the absence, which reads as the mode's
        # behaviour rather than as the harness's. Measured - a run whose logins all answered 401 and whose
        # feeder then ran dry was reported as "at least 60 applied results did not happen", a sentence
        # about the pipeline, two minutes after its load had stopped.
        [Diagnostics.Process]$LoadProcess = $null
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $last = $null
    while ($true) {
        $last = Invoke-PilotPoll -Phase $Phase -Lost $Lost
        # The predicate is asked first: a load generator that has finished is not a fault when the thing
        # being waited for has arrived, and every wait here is for that thing, not for the load.
        if (& $Predicate $last) { return $last }
        if ($null -ne $LoadProcess -and $LoadProcess.HasExited) {
            # Touched before `ExitCode` for the reason the launch site gives: with redirected output,
            # `ExitCode` on a Process from `Start-Process -PassThru` answers null until its handle has
            # been read, and a null that reaches an `[int]` binds to 0 - this harness's word for a
            # complete run. Read here rather than relied on from the launch site, because this function
            # is handed a Process and cannot see how it was started.
            $null = $LoadProcess.Handle
            $loadExitCode = $LoadProcess.ExitCode
            # Two things can end the load, and the message says which evidence separates them rather
            # than picking one. Either way the wait cannot be satisfied, so both readings end the wait
            # now instead of at the timeout - which is the point, because the timeout's own message
            # describes the pipeline and reads as the mode's behaviour.
            throw ("The load generator exited with code $loadExitCode while waiting for " +
                "$Description, so it was producing nothing further. Either it finished its ramp and hold " +
                "before that could happen - raise -HoldSeconds - or it failed; the requests it made and " +
                "the statuses it was answered with are in $artifacts\gatling\stdout.txt. Last reading: " +
                "applied=$($last.oracleAppliedResults), digestMatches=$($last.digestMatches), " +
                "quiescent=$($last.quiescent), pending=$($last.streamPendingEvents), " +
                "checkpoint=$($last.checkpointOffset).")
        }
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw ("$Description did not happen within $TimeoutSeconds seconds. Last reading: " +
                "applied=$($last.oracleAppliedResults), digestMatches=$($last.digestMatches), " +
                "quiescent=$($last.quiescent), pending=$($last.streamPendingEvents), " +
                "checkpoint=$($last.checkpointOffset).")
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
}

Write-Output "run $runId ($Mode #$RunIndex)"
Write-Output "  artifacts: $artifacts"
Write-Output "  load: $TargetRps/s from $concurrentUsers sessions pacing ${SubmitIntervalMillis}ms, ramp ${RampSeconds}s hold ${HoldSeconds}s"
Write-Output ""

try {
    # --- 1. reset ---------------------------------------------------------------------------------

    $script:sentinelBefore = Get-NonInterferenceSentinel
    $script:residualBefore = Get-ResidualRowCounts
    Write-Output "  residual rows before: $(($script:residualBefore.Keys | ForEach-Object { "$_=$($script:residualBefore[$_])" }) -join ' ')"

    $leftovers = Remove-ExperimentLeftovers -EvidenceDirectory $artifacts
    if ($leftovers.cleaned) {
        Write-Output "  took back an earlier attempt at this run id: $($leftovers.removed.totalDeleted) row(s)"
    }
    if (@($leftovers.otherRunsContests).Count -gt 0) {
        # Reported, not deleted: each of those is removable by repeating *its* run id, and deleting
        # another run's rows from here would be guessing at a scope this run has no evidence about.
        Write-Output "  note: other runs' experiment rows are present and were left alone: $(@($leftovers.otherRunsContests) -join ', ')"
    }
    [void](Assert-ExperimentDataAbsent -Phase "before this run seeds")

    # The dedup and rate-limit stores and the broker are emptied before anything else, so that no
    # reading in this run can be a leftover of the last one. Two things about the order matter:
    #
    #   - the broker and Redis services are brought up first, because both reset functions inspect the
    #     container they write to and refuse to write to one they cannot identify. On the very first run
    #     there is nothing to inspect yet.
    #   - the application tier is stopped before either reset, so that deleting the stream queue and
    #     emptying Redis is not itself the cause of anything the next poll observes.
    [void](Invoke-Compose -Arguments @("up", "-d", "redis", "rabbitmq"))
    # `up -d` returns when the containers exist, not when the services answer, and the resets below read
    # both of them. On a cold start - a reader's first run, or any run after the stack has been taken
    # down - the broker is still booting here, and the first `list_queues` exits 64.
    Wait-ResetTargetsReady
    [void](Invoke-Compose -Arguments (@("stop") + @("web-1", "web-2", "batch-1", "judge-1", "judge-2")))
    [void](Reset-ExperimentQueue -EvidencePath (Join-Path $artifacts "queue-reset.json"))
    [void](Clear-RecoveryRedis -EvidencePath (Join-Path $artifacts "redis-reset.json"))
    Write-Output "  stream queue deleted and the dedicated redis instance emptied"

    # --- 2. seed ----------------------------------------------------------------------------------

    $script:seed = New-ExperimentSeed -UserCount $UserCount -ProblemCount $ProblemCount `
        -ContestDurationMinutes $ContestDurationMinutes -EvidenceDirectory $artifacts
    Write-Output ("  seeded contest {0} '{1}' {2}..{3} for {4} users over {5} problems" -f `
            $script:seed.ContestId, $script:seed.ContestName, $script:seed.StartTimeMysql, $script:seed.EndTimeMysql, `
        $script:seed.UserCount, $script:seed.ProblemCount)

    # --- 3. start ---------------------------------------------------------------------------------

    # The mode reaches the stack through the environment variable the overlay reads, so the container
    # that comes up is the one this run is measuring. It is read back out of that container below.
    $env:CONTEST_SCOREBOARD_RECOVERY_MODE = $Mode
    $upArguments = @("up", "-d")
    if ($Build) { $upArguments += "--build" }
    [void](Invoke-Compose -Arguments ($upArguments + (Get-PilotStartServices)))
    $script:stackStarted = $true
    Wait-PilotStackHealthy
    $runtime = Assert-BatchRecoveryMode
    if ([string]$runtime.DbName -ne $config.DbName) {
        # Read back from the container rather than assumed from the overlay: the two names come from
        # different variables, and a run whose harness read one schema while the application wrote
        # another would measure a scoreboard that never moved, with nothing in the figures to say so.
        throw ("The batch role is connected to database '$($runtime.DbName)' but this run reads " +
            "'$($config.DbName)'. Point both at the same schema - the overlay takes " +
            "RECOVERY_PILOT_DB_NAME or the harness takes -DbName - before measuring anything.")
    }
    Write-Output "  stack healthy; batch-1 environment: mode=$($runtime.Mode) deterministic=$($runtime.DeterministicJudging) acceptPermille=$($runtime.AcceptPermille) db=$($runtime.DbHost)/$($runtime.DbName)"

    # The app tier this run measures is new; the edge in front of it may not be. Recreated here, after
    # the tier is up, so that it resolves web-1 and web-2 to this run's containers rather than to the
    # ones the previous run's teardown destroyed.
    Reset-EdgeRouting
    Write-Output "  edge recreated and routing to this run's app tier"

    # The observation pipeline has to be complete before the baseline, and the app tier's targets reach
    # Prometheus about forty seconds after their containers report healthy.
    Wait-PrometheusTargetsHealthy
    [void](Assert-ExperimentSeedUsable -Phase "before the load")

    # --- 4. validate ------------------------------------------------------------------------------

    [void](Wait-PipelineQuiescent -TimeoutSeconds $DrainTimeoutSeconds -Description "the pipeline before the load")
    [void](Assert-OraclePreconditions -Phase "before the load")
    $clockSkew = Assert-ClockFramesAligned
    $beforeLoad = Compare-ScoreboardWithOracle -AllResolvedResults:$false
    if (-not $beforeLoad.Matches) {
        # A digest that already disagrees with an empty scoreboard and an empty contest is a harness
        # fault, not a measurement. Producing figures from it would describe the harness, not the mode.
        throw ("The oracle and the scoreboard already disagree before any load " +
            "(api=$($beforeLoad.ApiDigest) oracle=$($beforeLoad.OracleDigest), " +
            "$($beforeLoad.ApiParticipants) vs $($beforeLoad.OracleParticipants) participants). " +
            "Stopping: no figure from this run would be about a recovery.")
    }
    Write-Output "  pre-load: scoreboard and oracle agree, clock frame: $clockSkew, pipeline quiescent"

    # One real login through the edge, as the load generator's own first user.
    #
    # The seeder already checks that the name the load will ask for is the name it inserted, but nothing
    # until now asked the application whether that name and password authenticate: a wrong password
    # encoding, a moved endpoint or an edge route left over from a previous stack would all first appear
    # as every session in the load answering 401 - which is twenty minutes later, at the end of the ramp,
    # and reads as an ingress failure rather than as a broken precondition. This costs one request.
    #
    # It goes through `$config.BaseUrl` (the edge) rather than a container directly, because the edge is
    # what the load uses.
    $loginUser = "$($script:seed.FeederUserPrefix)_user_1"
    $loginBody = @{ userName = $loginUser; pass = $script:seed.Password } | ConvertTo-Json -Compress
    $loginSession = $null
    try {
        $loginResponse = Invoke-WebRequest -Uri "$($config.BaseUrl)/api/login" -Method Post `
            -ContentType "application/json" -Body $loginBody -UseBasicParsing `
            -SessionVariable loginSession -TimeoutSec 30
    }
    catch {
        $status = ""
        if ($null -ne $_.Exception.Response) { $status = " (HTTP $([int]$_.Exception.Response.StatusCode))" }
        throw ("A real login as '$loginUser' through the edge failed before the load started$status. " +
            "The load authenticates every session the same way, so its 401s would be the whole of what " +
            "this run measured. Stopping before the ramp rather than producing ingress figures for it. " +
            "Underlying error: $($_.Exception.Message)")
    }
    if ([int]$loginResponse.StatusCode -ne 200) {
        throw "The login as '$loginUser' answered HTTP $([int]$loginResponse.StatusCode) rather than 200."
    }
    # The session is the useful part of a login: a 200 that set no cookie would leave every later request
    # in the load unauthenticated, which is the same failure one step further along.
    $loginCookies = @($loginSession.Cookies.GetCookies($config.BaseUrl))
    if ($loginCookies.Count -lt 1) {
        throw ("The login as '$loginUser' answered 200 but set no session cookie, so the load's later " +
            "requests would not be authenticated as anyone.")
    }
    Write-Output "  pre-load: login as '$loginUser' answered 200 and set $($loginCookies.Count) session cookie(s)"
    Write-Output ""

    # --- 5. load ----------------------------------------------------------------------------------

    $classpathFile = Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt"
    if (-not (Test-Path $classpathFile)) {
        throw "The standalone Gatling classpath is missing. Run: gradlew.bat :gatling:prepareStandaloneGatling"
    }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $logbackConfig = (Resolve-Path (Join-Path $repoRoot "gatling\src\gatling\resources\logback.xml")).Path
    $resultsFolder = Join-Path $repoRoot "gatling\build\reports\gatling"
    [void](New-Item -ItemType Directory -Path $resultsFolder -Force)

    # Checked below, because Start-Process joins its arguments with spaces and an element containing one
    # would silently become two arguments - which surfaces as a missing property or a class not found, a
    # long way from the cause. The classpath is the one that can carry a space, since it is built from
    # the worktree's own path.
    $javaArgs = @(
        "-Xms512m", "-Xmx1g",
        "-Dlogback.configurationFile=$logbackConfig",
        "-Dperf.baseUrl=$($config.BaseUrl)",
        "-Dperf.deterministic=true",
        "-Dperf.problemId.start=$($script:seed.ProblemIdStart)",
        "-Dperf.problemId.end=$($script:seed.ProblemIdEnd)",
        "-Dperf.userPrefix=$($script:seed.FeederUserPrefix)",
        "-Dperf.userIndex.start=1",
        "-Dperf.userIndex.end=$($script:seed.UserCount)",
        "-Dperf.targetRps=$TargetRps",
        "-Dperf.rampSeconds=$RampSeconds",
        "-Dperf.holdSeconds=$HoldSeconds",
        "-Dperf.submitIntervalMillis=$SubmitIntervalMillis",
        "-Dperf.assert.minSuccessPercent=99",
        "-Dperf.assert.p95Millis=$IngressSloP95Millis",
        "-Dperf.assert.minRequests=$minRequests",
        "-cp", $classpath,
        "io.gatling.app.Gatling",
        "-s", "my.oj.perf.ContestSubmissionSimulation",
        "-rf", $resultsFolder
    )
    foreach ($argument in $javaArgs) {
        if ($argument -match '\s') {
            throw ("A Gatling argument contains whitespace, so Start-Process would pass it as two " +
                "arguments: '$argument'. This is almost always the worktree path, which reaches the JVM " +
                "through -cp; the run has to be launched from a path without spaces.")
        }
    }

    $gatlingStdOut = Join-Path $artifacts "gatling\stdout.txt"
    $gatlingStdErr = Join-Path $artifacts "gatling\stderr.txt"
    [void](New-Item -ItemType Directory -Path (Split-Path -Parent $gatlingStdOut) -Force)
    $loadStartedAtUtc = [DateTimeOffset]::UtcNow
    $script:gatlingProcess = Start-Process -FilePath $config.JavaExe -ArgumentList $javaArgs -PassThru -NoNewWindow `
        -RedirectStandardOutput $gatlingStdOut -RedirectStandardError $gatlingStdErr
    # Read before the process is waited on: with redirected output, a Process object from `Start-Process
    # -PassThru` reports `ExitCode` as null unless its handle has been touched first, and a null exit code
    # here would read as "the load generator's assertions failed" on every run.
    $null = $script:gatlingProcess.Handle
    Write-Output "  load started at $($loadStartedAtUtc.UtcDateTime.ToString('o')) (pid $($script:gatlingProcess.Id))"

    # --- 6. baseline ------------------------------------------------------------------------------

    $baselineRow = Wait-PilotCondition -Phase "loading" -TimeoutSeconds $SettleTimeoutSeconds `
        -LoadProcess $script:gatlingProcess `
        -Description "at least $BaselineResults applied results" `
        -Predicate { param($row) $n = Get-RowNumber -Row $row -Name "oracleAppliedResults"; $null -ne $n -and $n -ge $BaselineResults }

    $windowStartRow = Invoke-PilotPoll -Phase "baseline"
    Start-Sleep -Seconds $BaselineWindowSeconds
    $windowEndRow = Invoke-PilotPoll -Phase "baseline"
    $baselineLatency = Get-ReflectLatencyStats -AppliedAtOrAfter $windowStartRow.timestampMysql `
        -AppliedBefore $windowEndRow.timestampMysql -Description "the baseline window"
    Write-Output "  baseline: $($baselineRow.oracleAppliedResults) applied; new-result latency over ${BaselineWindowSeconds}s: n=$($baselineLatency.Samples) p50=$($baselineLatency.P50Ms)ms p95=$($baselineLatency.P95Ms)ms max=$($baselineLatency.MaxMs)ms"

    # --- 7. capture -------------------------------------------------------------------------------

    $kRow = Invoke-PilotPoll -Phase "k-capture"
    Pause-Batch
    $kObservedAtMysql = Get-MySqlNow
    $kSnapshot = Export-ScoreboardSnapshot -Label "k" -ObservedAtMysql $kObservedAtMysql
    $kCounts = Get-ResultCounts
    # The oracle is read while the batch role is frozen, so the standings in it and the snapshot's
    # `processed` set describe the same set of applied results - which is exactly what the rollback is
    # supposed to reproduce.
    $kOracle = Get-OracleDigest
    $kCompare = Compare-ScoreboardWithOracle -AllResolvedResults:$false
    Resume-Batch
    if (-not $kCompare.Matches) {
        throw ("At the capture instant the scoreboard and the oracle disagree " +
            "(api=$($kCompare.ApiDigest) oracle=$($kCompare.OracleDigest)). The snapshot is not a state " +
            "the scoreboard ever reached, so a rollback to it would measure nothing.")
    }
    Write-Output "  captured K: $($kSnapshot.KeyCount) key(s), $($kSnapshot.ProcessedCount) processed results, checkpoint $($kSnapshot.Checkpoint), $($kCounts.AppliedResults) applied"

    # --- 8. tail, then the fault ------------------------------------------------------------------

    [void](Wait-PilotCondition -Phase "tail" -TimeoutSeconds $SettleTimeoutSeconds `
            -Description "$TailResults applied results past the capture instant" `
            -Predicate {
                param($row)
                $n = Get-RowNumber -Row $row -Name "oracleAppliedResults"
                $null -ne $n -and ($n - $kCounts.AppliedResults) -ge $TailResults
            })

    if ($script:gatlingProcess.HasExited) {
        throw ("The load finished before the fault could be injected, so the recovery would not have " +
            "been observed under new ingress. Raise -HoldSeconds (currently $HoldSeconds).")
    }

    # Paused first and read second, so the pre-rollback reading and the rollback are one step from the
    # batch role's point of view: nothing can be applied between the reading that defines the lost set
    # and the rollback that creates it.
    Pause-Batch
    $preRollbackMembers = @(Get-RedisSetMembers -Key $config.ProcessedKey)
    $faultAtUtc = [DateTimeOffset]::UtcNow
    $rollback = Invoke-ScoreboardRollback -SnapshotLabel "k"
    $faultAtMysql = Get-MySqlNow
    Resume-Batch

    $lost = Get-LostResultSet -SnapshotMembers $kSnapshot.Processed -PreRollbackMembers $preRollbackMembers
    if ($lost.LostCount -lt 1) {
        throw ("The rollback took nothing away ($($lost.LostCount) lost of $($lost.PreRollbackCount) applied). " +
            "There is no backlog to measure, so no recovery figure from this run would mean anything.")
    }
    Write-Output "  fault injected at $($faultAtUtc.UtcDateTime.ToString('o')): $($rollback.DeletedKeys) key(s) deleted, $($rollback.RestoredKeys) restored and verified, checkpoint $($rollback.Checkpoint)"
    if ($rollback.CanonicalOnlyKeys -gt 0) {
        Write-Output "    of those, $($rollback.CanonicalOnlyKeys) hash-table-encoded key(s) were verified by content, not payload bytes (Redis does not serialize that encoding reproducibly)"
    }
    Write-Output "  lost set: $($lost.LostCount) result(s) the rollback erased"

    # --- 9. observe -------------------------------------------------------------------------------

    $recoveryRow = Wait-PilotCondition -Phase "recovery" -TimeoutSeconds $DrainTimeoutSeconds `
        -Description "the scoreboard to agree with MySQL again with every lost result back" `
        -Lost $lost.Lost `
        -Predicate {
            param($row)
            (Get-RowTruth -Row $row -Name "digestMatches") -and (Get-RowTruth -Row $row -Name "lostComplete")
        }
    # The instant the predicate was seen to hold, not the instant the poll that saw it began: the digest
    # and the lost set are read partway through a poll that takes seconds, and the difference lands
    # directly in `consistencyOutageMs`.
    $consistentAtUtc = Get-PollObservationInstant -Row $recoveryRow -Field "consistencyObservedAtUtc" `
        -Fallback ([DateTimeOffset]$recoveryRow.timestampUtc)
    Write-Output "  consistent again at $($consistentAtUtc.UtcDateTime.ToString('o')): digest $($recoveryRow.apiDigest.Substring(0, 12))..., $($recoveryRow.lostReapplied)/$($recoveryRow.lostTotal) lost submissions delivered again"

    # Quiescence is a different question from consistency and is asked separately, because they are not
    # the same instant: a mode can rebuild the scoreboard from MySQL while the stream still holds events
    # it has not consumed, and `stream-offset` passes through the opposite - every queue empty while its
    # consumer is stopped. Whichever comes second is the end of the recovery.
    $drainedRow = Wait-PilotCondition -Phase "recovery" -TimeoutSeconds $DrainTimeoutSeconds `
        -Description "the pipeline to be quiet again" -Lost $lost.Lost `
        -Predicate { param($row) Get-RowTruth -Row $row -Name "quiescent" }
    # Same again, for the quiescence facts.
    $drainedAtUtc = Get-PollObservationInstant -Row $drainedRow -Field "quiescentObservedAtUtc" `
        -Fallback ([DateTimeOffset]$drainedRow.timestampUtc)
    Write-Output "  pipeline quiet again at $($drainedAtUtc.UtcDateTime.ToString('o'))"

    # --- 10. finish -------------------------------------------------------------------------------

    if (-not $script:gatlingProcess.HasExited) {
        if (-not $script:gatlingProcess.WaitForExit($GatlingTimeoutSeconds * 1000)) {
            $script:gatlingProcess.Kill()
            throw "The load did not finish within $GatlingTimeoutSeconds seconds and was stopped."
        }
    }
    $gatlingExitCode = $script:gatlingProcess.ExitCode
    $script:polls.Add((Invoke-PilotPoll -Phase "settling" -Lost $lost.Lost))

    $finalQuiescent = Wait-PipelineQuiescent -TimeoutSeconds $DrainTimeoutSeconds -Description "the pipeline at the end of the run"
    $finalSeedState = Assert-ExperimentSeedUsable -Phase "at the end of the run"
    [void](Assert-OraclePreconditions -Phase "at the end of the run")
    [void](Assert-ClockFramesAligned)
    $finalCompare = Compare-ScoreboardWithOracle -AllResolvedResults:$false
    $finalRow = Invoke-PilotPoll -Phase "final" -Lost $lost.Lost
    Assert-PilotStackHealthy

    $afterLatency = Get-ReflectLatencyStats -AppliedAtOrAfter $recoveryRow.timestampMysql -Description "after the scoreboard was consistent"
    $recoveryLatency = Get-ReflectLatencyStats -AppliedAtOrAfter $faultAtMysql -AppliedBefore $recoveryRow.timestampMysql `
        -Description "while the scoreboard was recovering"

    $script:recoveryEvents = @(Get-BatchRecoveryTimeline -SinceUtc $faultAtUtc.AddSeconds(-10).UtcDateTime.ToString("o"))
    $detection = Get-FirstRecoveryEvent -Events $script:recoveryEvents -Kinds @("detected-rewinding", "detected-nonrewinding")
    # A detection earlier than the fault is not a detection, and it must not become a negative latency in
    # a published column. The window above opens ten seconds before the fault on purpose - a log line can
    # be stamped by a container whose clock is a little behind - so the event is kept as evidence and the
    # figure is withdrawn rather than reported with the sign it happens to carry.
    $detectionPrecedesFault = $null -ne $detection -and $detection.Instant -lt $faultAtUtc
    if ($detectionPrecedesFault) {
        Write-Output "  batch-1's first detection event is stamped $($detection.Instant.UtcDateTime.ToString('o')), before the fault at $($faultAtUtc.UtcDateTime.ToString('o')): reported as unavailable rather than as a negative latency"
        $detection = $null
    }

    Write-Output ""
    Write-Output "  final: digestMatches=$($finalCompare.Matches) quiescent=$($finalQuiescent.Quiescent) processed=$($finalRow.processedCardinality) applied=$($finalRow.oracleAppliedResults)"
    Write-Output "  new-result latency while recovering: n=$($recoveryLatency.Samples) p50=$($recoveryLatency.P50Ms)ms p95=$($recoveryLatency.P95Ms)ms max=$($recoveryLatency.MaxMs)ms"
    if ($null -ne $detection) {
        Write-Output "  batch-1 detected the rollback at $($detection.Instant.UtcDateTime.ToString('o')) ($($detection.Kind))"
    }
    else {
        Write-Output "  batch-1 logged no detection event for this rollback; the timeline has $($script:recoveryEvents.Count) other event(s)"
    }

    # --- the run's figures -------------------------------------------------------------------------

    $recoveryPolls = @($script:polls | Where-Object { $_.phase -eq "recovery" })
    $faultIndex = 0
    for ($i = 0; $i -lt $script:polls.Count; $i++) {
        if ([DateTimeOffset]$script:polls[$i].timestampUtc -le $faultAtUtc) { $faultIndex = $i }
    }
    $consistentIndex = 0
    for ($i = 0; $i -lt $script:polls.Count; $i++) {
        if ([DateTimeOffset]$script:polls[$i].timestampUtc -le $consistentAtUtc) { $consistentIndex = $i }
    }
    $recoveryWindow = @($script:polls[$faultIndex..$consistentIndex])
    # How long the window the cost counters are taken over lasts. It is the mode's own fault-to-consistent
    # span, so it differs between modes by design - that span is the thing being measured - which is
    # exactly why the deltas over it cannot be compared with each other as they stand.
    $recoveryWindowSeconds = ($consistentAtUtc - $faultAtUtc).TotalSeconds
    $observedTotal = Get-DeltaAcross -Rows $recoveryWindow -Name "rollbackObservedTotal"
    $restartsTotal = Get-DeltaAcross -Rows $recoveryWindow -Name "rollbackRestartsTotal"
    $unrecoverableTotal = Get-DeltaAcross -Rows $recoveryWindow -Name "rollbackUnrecoverableTotal"

    $summary = [ordered]@{
        runId = $runId
        mode = $Mode
        runIndex = $RunIndex
        startedAtUtc = $startedAtUtc.UtcDateTime.ToString("o")
        finishedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        contestId = $script:seed.ContestId
        seedPrefix = $config.SeedPrefix
        userCount = $script:seed.UserCount
        problemCount = $script:seed.ProblemCount
        contestStartMysql = $script:seed.StartTimeMysql
        contestEndMysql = $script:seed.EndTimeMysql
        targetRps = Format-PilotNumber $TargetRps
        submitIntervalMillis = $SubmitIntervalMillis
        concurrentUsers = $concurrentUsers
        rampSeconds = $RampSeconds
        holdSeconds = $HoldSeconds
        pollIntervalSeconds = $PollIntervalSeconds
        baselineWindowSeconds = $BaselineWindowSeconds
        tailResults = $TailResults

        # --- the four instants the experiment is defined by, plus what they derive ------------------
        loadStartedAtUtc = $loadStartedAtUtc.UtcDateTime.ToString("o")
        faultAtUtc = $faultAtUtc.UtcDateTime.ToString("o")
        faultAtMysql = $faultAtMysql
        detectedAtUtc = if ($null -eq $detection) { "unavailable" } else { $detection.Instant.UtcDateTime.ToString("o") }
        detectedKind = if ($null -eq $detection) { "unavailable" } else { $detection.Kind }
        # True when batch-1's first recovery log line is stamped before the fault was injected, which is the
        # one thing that makes `T_detected` meaningless rather than merely imprecise. Recorded so that a
        # withdrawn detection reads as a clock or log-order question rather than as a mode that never noticed.
        detectionPrecedesFault = $detectionPrecedesFault
        consistentAtUtc = $consistentAtUtc.UtcDateTime.ToString("o")
        consistentAtMysql = $recoveryRow.timestampMysql
        drainedAtUtc = $drainedAtUtc.UtcDateTime.ToString("o")
        detectionLatencyMs = Format-PilotElapsed $(if ($null -eq $detection) { $null } else { $detection.Instant }) $faultAtUtc
        repairDurationMs = Format-PilotElapsed $consistentAtUtc $drainedAtUtc
        consistencyOutageMs = Format-PilotElapsed $faultAtUtc $consistentAtUtc
        backlogDrainMs = Format-PilotElapsed $faultAtUtc $drainedAtUtc
        fullRecoveryMs = Format-PilotElapsed $faultAtUtc (if ($consistentAtUtc -gt $drainedAtUtc) { $consistentAtUtc } else { $drainedAtUtc })
        drainedBeforeConsistent = ($drainedAtUtc -lt $consistentAtUtc)
        recoveryCompletedInsideLoad = ($consistentAtUtc -lt $loadStartedAtUtc.AddSeconds($RampSeconds + $HoldSeconds))
        pollsTotal = $script:polls.Count
        pollsDuringRecovery = $recoveryWindow.Count
        maxPollDurationMs = Format-PilotNumber (Get-MaxAcross -Rows @($script:polls.ToArray()) -Name "pollDurationMs")
        maxPollOracleMs = Format-PilotNumber (Get-MaxAcross -Rows @($script:polls.ToArray()) -Name "oraclePollMs")
        maxPollApiMs = Format-PilotNumber (Get-MaxAcross -Rows @($script:polls.ToArray()) -Name "apiPollMs")

        # --- the capture and the fault -------------------------------------------------------------
        kKeyCount = $kSnapshot.KeyCount
        kPayloadBytes = $kSnapshot.PayloadBytes
        kProcessedCount = $kSnapshot.ProcessedCount
        kCheckpoint = $kSnapshot.Checkpoint
        kAppliedResults = $kCounts.AppliedResults
        kResolvedResults = $kCounts.ResolvedResults
        kOracleDigest = $kOracle.Digest
        kOracleParticipants = $kOracle.Participants
        faultDeletedKeys = $rollback.DeletedKeys
        faultRestoredKeys = $rollback.RestoredKeys
        faultVerifiedKeys = $rollback.VerifiedKeys
        faultCheckpointAfter = $rollback.Checkpoint
        lostSnapshotCount = $lost.SnapshotCount
        lostPreRollbackCount = $lost.PreRollbackCount
        lostCount = $lost.LostCount
        rollbackObservedDelta = Format-PilotNumber $observedTotal
        rollbackRestartsDelta = Format-PilotNumber $restartsTotal
        rollbackUnrecoverableDelta = Format-PilotNumber $unrecoverableTotal
        # Two outcomes, two columns. The counter the registering code exposes carries the outcome it was
        # counted for, and the two are different events: a pass that held the gate retried nothing, while
        # an attempt that ran and failed is work this mode did and could not finish. One delta named for
        # the retries would have reported the second as though it were the first.
        rollbackRetryBusyDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "rollbackRetryBusyTotal")
        rollbackRetryRetryableDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "rollbackRetryRetryableTotal")
        recoveryLogEvents = $script:recoveryEvents.Count
        recoveryLogKinds = (@($script:recoveryEvents | ForEach-Object { $_.Kind } | Select-Object -Unique) -join " ")

        # --- new ingress, in the three windows -----------------------------------------------------
        baselineLagSamples = $baselineLatency.Samples
        baselineLagP50Ms = $baselineLatency.P50Ms
        baselineLagP95Ms = $baselineLatency.P95Ms
        baselineLagP99Ms = $baselineLatency.P99Ms
        baselineLagMaxMs = $baselineLatency.MaxMs
        recoveryLagSamples = $recoveryLatency.Samples
        recoveryLagP50Ms = $recoveryLatency.P50Ms
        recoveryLagP95Ms = $recoveryLatency.P95Ms
        recoveryLagP99Ms = $recoveryLatency.P99Ms
        recoveryLagMaxMs = $recoveryLatency.MaxMs
        afterLagSamples = $afterLatency.Samples
        afterLagP50Ms = $afterLatency.P50Ms
        afterLagP95Ms = $afterLatency.P95Ms
        afterLagP99Ms = $afterLatency.P99Ms
        afterLagMaxMs = $afterLatency.MaxMs
        lagP95IncreaseMs = if ([string]$baselineLatency.P95Ms -eq "unavailable" -or [string]$recoveryLatency.P95Ms -eq "unavailable") {
            "unavailable"
        }
        else {
            Format-PilotNumber ((([double]$recoveryLatency.P95Ms) - ([double]$baselineLatency.P95Ms)))
        }

        # --- backlog ------------------------------------------------------------------------------
        maxStreamPendingEvents = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "streamPendingEvents")
        maxStreamOldestReadySeconds = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "streamOldestReadySeconds")
        maxStreamQueueReady = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "streamQueueReady")
        maxStreamQueueUnacked = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "streamQueueUnacked")
        # Downwards, because a mode that stops its consumer to rewind from the checkpoint is supposed to
        # take this to zero, and that zero is the figure - a maximum would report the consumers it had
        # before it stopped them.
        minStreamQueueConsumers = Format-PilotNumber (Get-MinAcross -Rows $recoveryWindow -Name "streamQueueConsumers")
        maxRabbitLiveReady = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "rabbitLiveReady")
        maxRabbitLiveUnacked = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "rabbitLiveUnacked")
        maxJudgeOutboxNonPublished = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "judgeOutboxNonPublished")
        maxUnappliedResults = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "unappliedResults")
        # Downwards too: the ranking shrinks by exactly the results the rollback took away, and how far it
        # shrank is what says the fault was real rather than nominal.
        minRankingCardinality = Format-PilotNumber (Get-MinAcross -Rows $recoveryWindow -Name "rankingCardinality")
        pollsWithoutConsumer = @($recoveryWindow | Where-Object {
                $n = Get-RowNumber -Row $_ -Name "streamQueueConsumers"
                $null -ne $n -and $n -lt 1
            }).Count

        # --- what the recovery cost ----------------------------------------------------------------
        #
        # Every counter below is a delta over `recoveryWindow`, whose length is the mode's own
        # fault-to-consistent span and therefore differs between modes - deliberately, because that span is
        # the thing being measured. A raw delta is a total and not a rate, so a mode that took twice as long
        # shows roughly twice the work for that reason alone. `recoveryWindowSeconds` is recorded beside
        # them so the comparison can be made per second, and the summariser presents the ratio.
        recoveryWindowSeconds = Format-PilotNumber ($recoveryWindowSeconds)
        appliedDeltaDuringRecovery = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "appliedTotal")
        mysqlQuestionsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "mysqlQuestions")
        mysqlRowsReadDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "mysqlRowsRead")
        mysqlSlowQueriesDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "mysqlSlowQueries")
        maxMysqlThreadsRunning = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "mysqlThreadsRunning")
        maxHikariActive = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "hikariActive")
        maxHikariPending = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "hikariPending")
        redisEvalCallsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisEvalCalls")
        redisRestoreCallsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisRestoreCalls")
        redisDelCallsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisDelCalls")
        redisTotalCommandsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisTotalCommands")
        redisLuaErrorsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisLuaErrorsTotal")
        redisEvictedKeysDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisEvictedKeys")
        redisPipelineSumSecondsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "redisPipelineSumSeconds")
        redisPipelineP95MaxSeconds = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "redisPipelineP95Seconds")
        sequenceRoundsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "sequenceRoundsTotal")
        sequenceDuplicatesDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "sequenceDuplicatesTotal")
        sequenceReplayedDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "sequenceReplayedTotal")
        sequenceFailedDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "sequenceFailedTotal")
        sequenceWindowsSaturatedDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "sequenceWindowsSaturatedTotal")
        sequenceUnresolvedDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "sequenceUnresolvedTotal")
        sequenceMappingSizeMax = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "sequenceMappingSize")
        maxAppProcessCpu = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "appProcessCpu")
        maxAppHeapUsedBytes = Format-PilotNumber (Get-MaxAcross -Rows $recoveryWindow -Name "appHeapUsedBytes")
        appCgroupThrottledPeriodsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "appCgroupThrottledPeriods")
        appCgroupOomKillsDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "appCgroupOomKills")
        # No counter exists for how many results the replay offered or found already applied:
        # `ContestScoreboardReplayApplication.apply` returns void and records only marker failures.
        # Recorded as unavailable rather than as the applied delta, which counts live and replayed
        # applications together and cannot be split.
        replayOfferedCount = "unavailable"
        replayFoundAlreadyAppliedCount = "unavailable"
        replayMarkerFailuresDelta = Format-PilotNumber (Get-DeltaAcross -Rows $recoveryWindow -Name "replayMarkerFailuresTotal")

        # --- consistency ---------------------------------------------------------------------------
        finalDigestMatches = $finalCompare.Matches
        finalApiDigest = $finalCompare.ApiDigest
        finalOracleDigest = $finalCompare.OracleDigest
        finalApiParticipants = $finalCompare.ApiParticipants
        finalOracleParticipants = $finalCompare.OracleParticipants
        finalApiEntries = $finalCompare.ApiEntries
        finalProcessedCardinality = Format-PilotNumber (Get-RowNumber -Row $finalRow -Name "processedCardinality")
        finalRankingCardinality = Format-PilotNumber (Get-RowNumber -Row $finalRow -Name "rankingCardinality")
        finalAppliedResults = Format-PilotNumber (Get-RowNumber -Row $finalRow -Name "oracleAppliedResults")
        finalResolvedResults = Format-PilotNumber (Get-RowNumber -Row $finalRow -Name "oracleResolvedResults")
        processedExcessOverApplied = if ($null -eq (Get-RowNumber -Row $finalRow -Name "processedCardinality") -or
            $null -eq (Get-RowNumber -Row $finalRow -Name "oracleAppliedResults")) {
            "unavailable"
        }
        else {
            Format-PilotNumber ((Get-RowNumber -Row $finalRow -Name "processedCardinality") -
                (Get-RowNumber -Row $finalRow -Name "oracleAppliedResults"))
        }
        finalMissingFromScoreboard = if ($null -eq $finalCompare.Difference) { 0 } else { $finalCompare.Difference.MissingFromScoreboard }
        finalWrongRank = if ($null -eq $finalCompare.Difference) { 0 } else { $finalCompare.Difference.WrongRank }
        finalWrongScoreOrPenalty = if ($null -eq $finalCompare.Difference) { 0 } else { $finalCompare.Difference.WrongScoreOrPenalty }
        finalNotInOracle = if ($null -eq $finalCompare.Difference) { 0 } else { $finalCompare.Difference.NotInOracle }
        # A checkpoint ahead of what the scoreboard actually holds is the failure the three modes exist
        # to avoid, so it is recorded as its own figure rather than left to be inferred from the digest.
        checkpointAheadOfApplied = Format-PilotNumber (Get-RowNumber -Row $finalRow -Name "checkpointOffset")
        checkpointAtConsistency = [string]$recoveryRow.checkpointOffset
        finalSubmissionsWithoutContestRouting = $finalSeedState.submissionsOutsideTheContestPath
        finalContestSecondsUntilEnd = $finalSeedState.secondsUntilEnd

        # --- the load generator's own verdict ------------------------------------------------------
        gatlingExitCode = if ($null -eq $gatlingExitCode) { "unavailable" } else { $gatlingExitCode }
        gatlingP95Millis = $IngressSloP95Millis
        expectedRequests = $expectedRequests
        minRequests = $minRequests
    }

    # The load generator's own report is read after the fact rather than trusted from the exit code
    # alone: an exit code says whether an assertion held, and the request counts say what was offered
    # and what failed, which is the part that describes the ingress.
    #
    # Found by write time rather than by name, because Gatling names the directory after the simulation
    # class and there is no way to make it carry the run id. The two-second slack is for a filesystem
    # whose timestamps round up; two runs of one mode are minutes apart and cannot be confused.
    $report = Get-ChildItem -Path $resultsFolder -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $loadStartedAtUtc.LocalDateTime.AddSeconds(-2) } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($report) {
        $summary["gatlingReportDirectory"] = $report.Name
        $globalStatsPath = Join-Path $report.FullName "js\global_stats.json"
        if (Test-Path -LiteralPath $globalStatsPath) {
            $stats = Get-Content -LiteralPath $globalStatsPath -Raw | ConvertFrom-Json
            $gatlingTotal = [int64]$stats.numberOfRequests.total
            $gatlingOk = [int64]$stats.numberOfRequests.ok
            $summary["gatlingTotalRequests"] = $gatlingTotal
            $summary["gatlingSuccessfulRequests"] = $gatlingOk
            $summary["gatlingFailedRequests"] = [int64]$stats.numberOfRequests.ko
            # A generator that injected nothing has no success percentage, and 0/0 is a terminating error
            # in PowerShell rather than a NaN. `unavailable` is this experiment's word for a value that
            # cannot be collected; the counts above still say 0, which is the real fact about that run.
            $summary["gatlingSuccessPercent"] = if ($gatlingTotal -gt 0) {
                [math]::Round(100d * $gatlingOk / $gatlingTotal, 4)
            }
            else { "unavailable" }
            $summary["gatlingObservedP95Millis"] = [int64]$stats.percentiles3.total
            $summary["gatlingObservedMaxMillis"] = [int64]$stats.maxResponseTime.total
        }
        else {
            $summary["gatlingReportDirectory"] = $report.Name
            $summary["gatlingTotalRequests"] = "unavailable"
            $summary["gatlingSuccessfulRequests"] = "unavailable"
            $summary["gatlingFailedRequests"] = "unavailable"
            $summary["gatlingSuccessPercent"] = "unavailable"
            $summary["gatlingObservedP95Millis"] = "unavailable"
            $summary["gatlingObservedMaxMillis"] = "unavailable"
        }
    }
    else {
        $summary["gatlingReportDirectory"] = "unavailable"
        $summary["gatlingTotalRequests"] = "unavailable"
        $summary["gatlingSuccessfulRequests"] = "unavailable"
        $summary["gatlingFailedRequests"] = "unavailable"
        $summary["gatlingSuccessPercent"] = "unavailable"
        $summary["gatlingObservedP95Millis"] = "unavailable"
        $summary["gatlingObservedMaxMillis"] = "unavailable"
    }

    # A run is complete when every one of these held. Each is a property of the measurement rather than
    # of the mode, so a run that fails one is discarded rather than reported with an asterisk.
    $incomplete = New-Object 'System.Collections.Generic.List[string]'
    if (-not $finalCompare.Matches) { $incomplete.Add("the final digest still disagrees with the oracle") }
    if (-not $summary["recoveryCompletedInsideLoad"]) { $incomplete.Add("the recovery did not finish before the load's hold ended") }
    if ($null -eq $detection) { $incomplete.Add("batch-1 logged no detection event") }
    if ($null -eq $gatlingExitCode) {
        # Not folded into the line below: "the assertions failed" and "the generator's exit code could
        # not be read" are different findings, and a run that cannot say which happened should say that.
        $incomplete.Add("the load generator's exit code could not be read")
    }
    elseif ($gatlingExitCode -ne 0) { $incomplete.Add("the load generator's assertions failed (exit $gatlingExitCode)") }
    if ($finalQuiescent.Quiescent -ne $true) { $incomplete.Add("the pipeline was not quiet at the end") }
    $oomKills = Get-RowNumber -Row $finalRow -Name "appCgroupOomKills"
    if ($null -ne $oomKills -and $oomKills -gt 0) { $incomplete.Add("a container was OOM-killed ($oomKills)") }
    if (-not $summary["finalDigestMatches"]) { $incomplete.Add("the digest did not match at the end") }
    $summary["incompleteReasons"] = (@($incomplete) -join "; ")
    $summary["complete"] = ($incomplete.Count -eq 0)

    Write-JsonFile -Path (Join-Path $artifacts "run-metadata.json") -Object $runRecord
    Write-JsonFile -Path (Join-Path $artifacts "k-snapshot.json") -Object ([pscustomobject][ordered]@{
            label           = $kSnapshot.Label
            capturedAtUtc   = $kSnapshot.CapturedAtUtc
            capturedAtMysql = $kSnapshot.CapturedAtMysql
            keyCount        = $kSnapshot.KeyCount
            payloadBytes    = $kSnapshot.PayloadBytes
            typeHistogram   = $kSnapshot.TypeHistogram
            processedCount  = $kSnapshot.ProcessedCount
            checkpoint      = $kSnapshot.Checkpoint
            hostDirectory   = $kSnapshot.HostDirectory
        })
    Write-JsonFile -Path (Join-Path $artifacts "rollback.json") -Object ([pscustomobject][ordered]@{
            snapshotLabel   = $rollback.SnapshotLabel
            deletedKeys     = $rollback.DeletedKeys
            restoredKeys    = $rollback.RestoredKeys
            verifiedKeys    = $rollback.VerifiedKeys
            checkpointAfter = $rollback.Checkpoint
            observedAtUtc   = $rollback.ObservedAtUtc
            faultAtUtc      = $faultAtUtc.UtcDateTime.ToString("o")
            faultAtMysql    = $faultAtMysql
            lostCount       = $lost.LostCount
            lost            = $lost.Lost
        })
    Write-JsonFile -Path (Join-Path $artifacts "recovery-log-events.json") -Object $script:recoveryEvents

    # One row, replaced on each attempt: a rerun of this run id supersedes the earlier attempt's row
    # rather than sitting beside it, so the summary never holds two rows for one run.
    $summaryPath = Join-Path $artifacts "recovery-summary.csv"
    $builder = New-Object Text.StringBuilder
    [void]$builder.AppendLine((@($summary.Keys) -join ","))
    [void]$builder.AppendLine((@($summary.Keys | ForEach-Object { ConvertTo-CsvField -Value ([string]$summary[$_]) }) -join ","))
    [IO.File]::WriteAllText($summaryPath, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))

    # The convention `summarize-c-stage-runs.ps1` reads: written last, after every assertion above, so
    # its presence is a claim about a run that got all the way through.
    $summary["runVerdict"] = $summary["complete"]
    $verdictPath = Join-Path $artifacts "run-verdict.csv"
    $verdict = "scenario,completedSuccessfully,oomKilledContainers`r`n" +
        "$runId,$($summary['complete'].ToString().ToLowerInvariant()),$(if ($null -eq $oomKills) { 'unavailable' } else { [long]$oomKills })`r`n"
    [IO.File]::WriteAllText($verdictPath, $verdict, (New-Object Text.UTF8Encoding($false)))
    $summary["runVerdict"] = $summary["complete"]
    # Rewritten so the row and the verdict file cannot disagree: `runVerdict` is a column of the row.
    $builder = New-Object Text.StringBuilder
    [void]$builder.AppendLine((@($summary.Keys) -join ","))
    [void]$builder.AppendLine((@($summary.Keys | ForEach-Object { ConvertTo-CsvField -Value ([string]$summary[$_]) }) -join ","))
    [IO.File]::WriteAllText($summaryPath, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))

    Write-Output ""
    Write-Output "  summary: $summaryPath"
    if ($incomplete.Count -gt 0) {
        Write-Output "  INCOMPLETE: $(@($incomplete) -join '; ')"
    }
    else {
        Write-Output "  complete"
    }

    # --- 11. clean up -----------------------------------------------------------------------------

    $removal = Remove-ExperimentData -EvidenceDirectory $artifacts
    $script:cleanupDone = $true
    Write-Output "  removed $($removal.totalDeleted) row(s) in $(@($removal.steps).Count) scoped statement(s)"
    [void](Assert-NonInterferenceIntact -Before $script:sentinelBefore -Phase "after this run" `
            -EvidencePath (Join-Path $artifacts "non-interference-after.json"))

    if ($incomplete.Count -gt 0) { exit 2 }
    exit 0
}
catch {
    $failure = $_
    Write-Output ""
    Write-Output "  FAILED: $($failure.Exception.Message)"
    Write-JsonFile -Path (Join-Path $artifacts "failure.json") -Object ([pscustomobject][ordered]@{
            runId         = $runId
            mode          = $Mode
            runIndex      = $RunIndex
            failedAtUtc   = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
            message       = $failure.Exception.Message
            categoryInfo  = [string]$failure.CategoryInfo
            fullyQualifiedErrorId = [string]$failure.FullyQualifiedErrorId
            scriptStackTrace = [string]$failure.ScriptStackTrace
        })
    exit 1
}
finally {
    # The batch role is resumed first. Both captures pause it, and either can fail between the pause and
    # the resume - a digest that disagrees at the capture instant, a rollback whose verification does
    # not confirm the keys it wrote. Left paused, the container is frozen for every later run and the
    # next one's failures would be this one's, arriving in a place with no connection to their cause.
    try { Resume-Batch } catch { Write-Output "  could not resume batch-1: $($_.Exception.Message)" }
    if ($null -ne $script:gatlingProcess -and -not $script:gatlingProcess.HasExited) {
        # The load is stopped before anything else, so a failure cannot leave a generator running into
        # the next run's baseline.
        try { $script:gatlingProcess.Kill() } catch { Write-Output "  could not stop the load generator: $($_.Exception.Message)" }
    }
    if ($script:stackStarted -and -not $KeepStackRunning) {
        try {
            [void](Invoke-Compose -Arguments (@("stop") + @("web-1", "web-2", "batch-1", "judge-1", "judge-2")))
        }
        catch { Write-Output "  could not stop the application tier: $($_.Exception.Message)" }
    }
    if (-not $script:cleanupDone -and $null -ne $script:seed -and (Get-RecoveryConfig).ContestScopeFromSeed) {
        try {
            $removal = Remove-ExperimentData -EvidenceDirectory $artifacts
            Write-Output "  cleanup after a failure removed $($removal.totalDeleted) row(s)"
        }
        catch {
            Write-Output "  cleanup after a failure also failed: $($_.Exception.Message)"
        }
    }
}
