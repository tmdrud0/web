# Replays the same Redis RDB rollback experiment against either scoreboard transport.
#
# This script deliberately uses the product scoreboard API as the state oracle. Redis DUMP/RDB
# bytes are implementation details and can change while the logical standings stay identical.
# scoreboard_applied_at is checked only as an operational drain condition; it is never accepted as
# proof that the rolled-back Redis state converged.
# It also deliberately ignores contest.judge.result.stream messages_ready as lag: a stream retains
# acknowledged entries, and the B-stage tail probe can briefly add deliveries/a consumer of its
# own. contest_scoreboard_pending_events is the implementation-neutral lag signal.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$WorktreeRoot,

    [Parameter(Mandatory = $true)]
    [ValidateSet("outbox", "stream")]
    [string]$Candidate,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory,

    [ValidateRange(1, [long]::MaxValue)]
    [long]$ContestId = 1,

    [ValidateRange(1, [long]::MaxValue)]
    [long]$ProblemIdStart = 1,

    [ValidateRange(1, [long]::MaxValue)]
    [long]$ProblemIdEnd = 5,

    [ValidateNotNullOrEmpty()]
    [string]$BaseUrl = "http://localhost:18080",

    [ValidateRange(0.1, 10000)]
    [double]$TailTargetRps = 20,

    [ValidateRange(1, 3600)]
    [int]$TailRampSeconds = 5,

    [ValidateRange(1, 3600)]
    [int]$TailHoldSeconds = 20,

    [ValidateRange(1, [int]::MaxValue)]
    [int]$TailUserIndexStart = 301,

    [ValidateRange(1, [int]::MaxValue)]
    [int]$TailUserIndexEnd = 360,

    [ValidateRange(1, [long]::MaxValue)]
    [long]$TailSubmitIntervalMillis = 3000,

    [ValidateRange(1, 3600)]
    [int]$DrainTimeoutSeconds = 600,

    [ValidateRange(1, 3600)]
    [int]$ConvergenceTimeoutSeconds = 900,

    [ValidateRange(100, 1000)]
    [int]$PollIntervalMilliseconds = 500,

    [ValidateRange(1, 300)]
    [int]$RedisReadyTimeoutSeconds = 60,

    [ValidateNotNullOrEmpty()]
    [string]$PrometheusUrl = "http://127.0.0.1:9090",

    [ValidateNotNullOrEmpty()]
    [string]$DbName = "oj_loadtest",

    [ValidateNotNullOrEmpty()]
    [string]$JavaExe = "C:\Program Files\Java\jdk-17\bin\java.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$projectName = "oj-loadtest"
$redisContainer = "oj-loadtest-redis"
$batchContainer = "oj-loadtest-batch-1"
$checkpointKey = if ($Candidate -eq "outbox") {
    "contest:scoreboard:outbox:seq"
}
else {
    "contest:scoreboard:stream:offset"
}
$streamPendingKey = "contest:scoreboard:stream:db-pending"
$prometheusBaseUrl = $PrometheusUrl.TrimEnd('/')
$recoverySamples = New-Object 'System.Collections.Generic.List[object]'
$batchPaused = $false
$redisKilled = $false
$failure = $null
$completed = $false

function Format-InvariantNumber {
    param([Parameter(Mandatory = $true)][double]$Value)

    return $Value.ToString("R", $invariantCulture)
}

function ConvertTo-RequiredInt64 {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $parsed = 0L
    if (-not [long]::TryParse(
            ([string]$Value).Trim(),
            [Globalization.NumberStyles]::Integer,
            $invariantCulture,
            [ref]$parsed)) {
        throw "$Description is not an Int64: '$Value'."
    }
    return $parsed
}

function ConvertTo-RequiredDouble {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $parsed = 0d
    if (-not [double]::TryParse(
            ([string]$Value).Trim(),
            [Globalization.NumberStyles]::Float,
            $invariantCulture,
            [ref]$parsed) -or
        [double]::IsNaN($parsed) -or
        [double]::IsInfinity($parsed)) {
        throw "$Description is not a finite number: '$Value'."
    }
    return $parsed
}

function Invoke-Compose {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    # Windows PowerShell 5.1 wraps native stderr (including harmless mysql client
    # warnings) in ErrorRecord objects when ErrorActionPreference is Stop. Judge
    # native success by its exit code and keep stderr suppressed here.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& docker compose @script:composeArgs @Arguments 2>$null)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "docker compose failed (exit $exitCode): $($Arguments -join ' ')"
    }
    return $output
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& docker @Arguments 2>$null)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "docker failed (exit $exitCode): $($Arguments -join ' ')"
    }
    return $output
}

function Invoke-SqlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $lines = @(Invoke-Compose -Arguments @(
        "exec", "-T", "mysql", "mysql", "-uroot", "-p1234", "-D", $DbName, "-Nse", $Sql
    ))
    $value = $lines | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Last 1
    if ($null -eq $value) {
        throw "MySQL returned no value for $Description."
    }
    return ConvertTo-RequiredInt64 -Value $value -Description $Description
}

function Invoke-Redis {
    param([Parameter(Mandatory = $true)][string[]]$RedisArguments)

    return @(Invoke-Compose -Arguments (@(
        "exec", "-T", "redis", "redis-cli", "--raw"
    ) + $RedisArguments))
}

function Get-RedisConfigValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    $lines = @(Invoke-Redis -RedisArguments @("CONFIG", "GET", $Name))
    for ($index = 0; $index -lt $lines.Count - 1; $index++) {
        if ([string]$lines[$index] -eq $Name) {
            return [string]$lines[$index + 1]
        }
    }
    throw "Redis CONFIG GET $Name returned no value."
}

function Get-Checkpoint {
    $lines = @(Invoke-Redis -RedisArguments @("GET", $checkpointKey))
    $value = $lines | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Last 1
    if ($null -eq $value) {
        throw "Redis checkpoint '$checkpointKey' is missing. A stream run with no events is invalid for this experiment."
    }
    return ConvertTo-RequiredInt64 -Value $value -Description "Redis checkpoint '$checkpointKey'"
}

function Get-StreamDbPending {
    $lines = @(Invoke-Redis -RedisArguments @("SCARD", $streamPendingKey))
    return ConvertTo-RequiredInt64 -Value ($lines | Select-Object -Last 1) `
        -Description "Redis stream DB-pending cardinality"
}

function Invoke-PrometheusQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $encoded = [uri]::EscapeDataString($Query)
    try {
        $response = Invoke-RestMethod `
            -Uri "$prometheusBaseUrl/api/v1/query?query=$encoded" `
            -TimeoutSec 10
    }
    catch {
        throw "Prometheus $Description query failed: $($_.Exception.Message)"
    }
    if ($null -eq $response -or [string]$response.status -ne "success") {
        $status = if ($null -eq $response) { "empty" } else { [string]$response.status }
        throw "Prometheus $Description query returned status '$status'."
    }
    if ([string]$response.data.resultType -ne "vector") {
        throw "Prometheus $Description query returned '$($response.data.resultType)', expected vector."
    }
    return @($response.data.result)
}

function Assert-PrometheusTargetsHealthy {
    $samples = @(Invoke-PrometheusQuery -Query "up" -Description "target health")
    $up = 0d
    foreach ($sample in $samples) {
        $pair = @($sample.value)
        if ($pair.Count -lt 2) {
            throw "Prometheus target health returned a malformed sample."
        }
        $up += ConvertTo-RequiredDouble -Value $pair[1] -Description "Prometheus up"
    }
    if ($samples.Count -ne 12 -or $up -ne 12d) {
        throw "Prometheus targets are incomplete: count(up)=$($samples.Count), sum(up)=$up; expected 12/12."
    }

    $apps = @($samples | Where-Object { [string]$_.metric.job -eq "oj-app" })
    $rabbitDetailed = @($samples | Where-Object { [string]$_.metric.job -eq "rabbitmq-per-queue" })
    if ($apps.Count -ne 5 -or $rabbitDetailed.Count -ne 1) {
        throw "Prometheus comparison targets are incomplete: oj-app=$($apps.Count)/5, rabbitmq-per-queue=$($rabbitDetailed.Count)/1."
    }
}

function Get-NeutralPending {
    $samples = @(Invoke-PrometheusQuery `
        -Query 'contest_scoreboard_pending_events{job="oj-app",node="batch-1"}' `
        -Description "neutral scoreboard pending")
    if ($samples.Count -ne 1) {
        throw "Expected one batch-1 contest_scoreboard_pending_events series, found $($samples.Count)."
    }
    return ConvertTo-RequiredDouble -Value @($samples[0].value)[1] -Description "neutral scoreboard pending"
}

function ConvertTo-LabelText {
    param([Parameter(Mandatory = $true)]$Metric)

    $labels = @()
    foreach ($property in @($Metric.PSObject.Properties | Sort-Object Name)) {
        if ($property.Name -eq "__name__") {
            continue
        }
        $value = ([string]$property.Value).Replace(";", "%3B").Replace("=", "%3D")
        $labels += "$($property.Name)=$value"
    }
    return $labels -join ";"
}

function Capture-PrometheusSamples {
    param(
        [Parameter(Mandatory = $true)][DateTimeOffset]$ObservedAt,
        [Parameter(Mandatory = $true)][long]$ElapsedMilliseconds
    )

    $queries = @(
        [pscustomobject]@{
            Source = "oj-app"
            Query = '{job="oj-app",node="batch-1",__name__=~"contest_scoreboard_(pending_events|oldest_ready_seconds|applied_total|redis_pipeline_seconds_bucket|redis_lua_errors_total|stream_rollback_restarts_total)"}'
        },
        [pscustomobject]@{
            Source = "rabbitmq-per-queue"
            Query = '{job="rabbitmq-per-queue",queue=~"contest\\.judge\\.(live|dead|result\\.stream)",__name__=~"rabbitmq_detailed_queue_(messages_ready|messages_unacked|consumers|messages_delivered_ack_total|messages_delivered_total|exchange_messages_published_total)"}'
        }
    )
    $valuesByMetric = @{}
    foreach ($querySpec in $queries) {
        $samples = @(Invoke-PrometheusQuery -Query $querySpec.Query -Description "$($querySpec.Source) recovery metrics")
        foreach ($sample in $samples) {
            $metricName = [string]$sample.metric.__name__
            $pair = @($sample.value)
            if ([string]::IsNullOrWhiteSpace($metricName) -or $pair.Count -lt 2) {
                throw "Prometheus $($querySpec.Source) recovery metrics returned a malformed sample."
            }
            $value = ConvertTo-RequiredDouble -Value $pair[1] -Description "Prometheus $metricName"
            $sampleUnixSeconds = ConvertTo-RequiredDouble -Value $pair[0] -Description "Prometheus $metricName timestamp"
            $sampleUnixMilliseconds = [long][math]::Round($sampleUnixSeconds * 1000d)
            $recoverySamples.Add([pscustomobject][ordered]@{
                timestampUtc = $ObservedAt.UtcDateTime.ToString("o", $invariantCulture)
                sampleTimestampUtc = [DateTimeOffset]::FromUnixTimeMilliseconds($sampleUnixMilliseconds).UtcDateTime.ToString(
                    "o",
                    $invariantCulture)
                elapsedMs = $ElapsedMilliseconds
                source = $querySpec.Source
                metric = $metricName
                labels = ConvertTo-LabelText -Metric $sample.metric
                value = Format-InvariantNumber $value
            })
            if (-not $valuesByMetric.ContainsKey($metricName)) {
                $valuesByMetric[$metricName] = 0d
            }
            $valuesByMetric[$metricName] += $value
        }
    }
    return $valuesByMetric
}

function Get-ExpectedContainers {
    return [ordered]@{
        nginx = "oj-loadtest-nginx"
        mysql = "oj-loadtest-mysql"
        redis = "oj-loadtest-redis"
        rabbitmq = "oj-loadtest-rabbitmq"
        "web-1" = "oj-loadtest-web-1"
        "web-2" = "oj-loadtest-web-2"
        "batch-1" = "oj-loadtest-batch-1"
        "judge-1" = "oj-loadtest-judge-1"
        "judge-2" = "oj-loadtest-judge-2"
        prometheus = "oj-prometheus"
        grafana = "oj-grafana"
        alertmanager = "oj-alertmanager"
        cadvisor = "oj-cadvisor"
        "mysqld-exporter" = "oj-mysqld-exporter"
        "redis-exporter" = "oj-redis-exporter"
        "nginx-exporter" = "oj-nginx-exporter"
    }
}

function Get-ProjectContainers {
    $ids = @(& docker ps -aq --filter "label=com.docker.compose.project=$projectName" 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not list Docker containers for project '$projectName'."
    }
    $ids = @($ids | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($ids.Count -eq 0) {
        return @()
    }
    $json = (& docker inspect $ids 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
        throw "Could not inspect Docker containers for project '$projectName'."
    }
    # Windows PowerShell 5.1 emits a top-level JSON array from ConvertFrom-Json as
    # one pipeline object. Enumerate it explicitly so the exact-container gate
    # counts containers instead of counting the array wrapper.
    $parsed = $json | ConvertFrom-Json
    return @($parsed | ForEach-Object { $_ })
}

function Assert-AllStackContainersHealthy {
    $expected = Get-ExpectedContainers
    $containers = @(Get-ProjectContainers)
    if ($containers.Count -ne $expected.Count) {
        $actualNames = @($containers | ForEach-Object { ([string]$_.Name).TrimStart('/') } | Sort-Object)
        throw "Expected exactly $($expected.Count) '$projectName' containers, found $($containers.Count): $($actualNames -join ', ')."
    }

    $actualByService = @{}
    foreach ($container in $containers) {
        $project = [string]$container.Config.Labels.'com.docker.compose.project'
        $service = [string]$container.Config.Labels.'com.docker.compose.service'
        $name = ([string]$container.Name).TrimStart('/')
        if ($project -ne $projectName -or [string]::IsNullOrWhiteSpace($service)) {
            throw "Container '$name' has unexpected Compose labels (project='$project', service='$service')."
        }
        if ($actualByService.ContainsKey($service)) {
            throw "Compose project '$projectName' has more than one container for service '$service'."
        }
        $actualByService[$service] = $container
    }

    foreach ($service in $expected.Keys) {
        if (-not $actualByService.ContainsKey($service)) {
            throw "Compose project '$projectName' is missing service '$service'."
        }
        $container = $actualByService[$service]
        $name = ([string]$container.Name).TrimStart('/')
        if ($name -ne $expected[$service]) {
            throw "Service '$service' is container '$name'; expected exact name '$($expected[$service])'."
        }
        if ([string]$container.State.Status -ne "running" -or [bool]$container.State.Paused -or
            [bool]$container.State.Restarting -or [bool]$container.State.OOMKilled) {
            throw "Container '$name' is not a clean running container " +
                "(status=$($container.State.Status), paused=$($container.State.Paused), " +
                "restarting=$($container.State.Restarting), OOMKilled=$($container.State.OOMKilled))."
        }
        $healthProperty = $container.State.PSObject.Properties["Health"]
        if ($null -ne $healthProperty -and $null -ne $healthProperty.Value -and
            [string]$healthProperty.Value.Status -ne "healthy") {
            throw "Container '$name' health is '$($healthProperty.Value.Status)', expected healthy."
        }
    }
}

function Wait-AllStackContainersHealthy {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($RedisReadyTimeoutSeconds)
    $lastError = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            Assert-AllStackContainersHealthy
            return
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    throw "Stack health did not recover within $RedisReadyTimeoutSeconds seconds: $lastError"
}

function Get-ContainerStatus {
    param([Parameter(Mandatory = $true)][string]$Name)

    $json = (& docker inspect $Name 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
        return $null
    }
    $containers = @($json | ConvertFrom-Json)
    if ($containers.Count -ne 1) {
        return $null
    }
    return [string]$containers[0].State.Status
}

function Get-ContainerIdentity {
    param([Parameter(Mandatory = $true)][string]$Name)

    $json = (& docker inspect $Name 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
        throw "Could not inspect container identity for '$Name'."
    }
    $containers = @($json | ConvertFrom-Json)
    if ($containers.Count -ne 1) {
        throw "Expected one container named '$Name', found $($containers.Count)."
    }
    return [pscustomobject]@{
        Id = [string]$containers[0].Id
        StartedAt = [string]$containers[0].State.StartedAt
        RestartCount = [long]$containers[0].RestartCount
    }
}

function Get-StableContainerIdentitySnapshot {
    $snapshot = @{}
    foreach ($name in (Get-ExpectedContainers).Values) {
        # Redis is the one container this experiment intentionally kills and starts again.
        if ($name -ne $redisContainer) {
            $snapshot[$name] = Get-ContainerIdentity -Name $name
        }
    }
    return $snapshot
}

function Assert-StableContainerIdentities {
    param([Parameter(Mandatory = $true)]$Before)

    foreach ($name in @($Before.Keys | Sort-Object)) {
        $old = $Before[$name]
        $current = Get-ContainerIdentity -Name $name
        if ($current.Id -ne $old.Id -or
            $current.StartedAt -ne $old.StartedAt -or
            $current.RestartCount -ne $old.RestartCount) {
            throw "Container '$name' restarted during the rollback experiment. The run is not comparable."
        }
    }
}

function Wait-RedisReady {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($RedisReadyTimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $pong = @(Invoke-Redis -RedisArguments @("PING")) | Select-Object -Last 1
            $health = @(Invoke-Docker -Arguments @(
                "inspect", "--format",
                "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                $redisContainer
            )) | Select-Object -Last 1
            if ([string]$pong -eq "PONG" -and
                ([string]$health -eq "healthy" -or [string]$health -eq "none")) {
                return
            }
        }
        catch {
            # The container can be running before Redis has loaded the RDB.
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Redis did not answer PONG within $RedisReadyTimeoutSeconds seconds."
}

function Pause-Batch {
    if ($script:batchPaused) {
        throw "Batch container is already paused by this script."
    }
    [void](Invoke-Docker -Arguments @("pause", $batchContainer))
    $script:batchPaused = $true
}

function Resume-Batch {
    if (-not $script:batchPaused) {
        return
    }
    [void](Invoke-Docker -Arguments @("unpause", $batchContainer))
    $script:batchPaused = $false
}

function Restore-RecoveryContainers {
    $redisStatus = Get-ContainerStatus -Name $redisContainer
    if ($redisStatus -ne "running") {
        try {
            [void](Invoke-Compose -Arguments @("start", "redis"))
            $script:redisKilled = $false
            Wait-RedisReady
        }
        catch {
            Write-Warning "Could not restore Redis: $($_.Exception.Message)"
        }
    }

    $batchStatus = Get-ContainerStatus -Name $batchContainer
    if ($batchStatus -eq "paused" -or $script:batchPaused) {
        try {
            [void](Invoke-Docker -Arguments @("unpause", $batchContainer))
            $script:batchPaused = $false
        }
        catch {
            Write-Warning "Could not unpause batch-1: $($_.Exception.Message)"
        }
    }
    elseif ($batchStatus -ne "running") {
        try {
            [void](Invoke-Compose -Arguments @("start", "batch-1"))
        }
        catch {
            Write-Warning "Could not restore batch-1: $($_.Exception.Message)"
        }
    }
}

function Get-RabbitQueueState {
    $lines = @(Invoke-Compose -Arguments @(
        "exec", "-T", "rabbitmq", "rabbitmqctl", "list_queues", "-q",
        "name", "messages_ready", "messages_unacknowledged", "consumers"
    ))
    $queues = @{}
    foreach ($line in $lines) {
        $fields = @(([string]$line -split '\s+') | Where-Object { $_ })
        if ($fields.Count -lt 4) {
            continue
        }
        $ready = 0L
        $unacked = 0L
        $consumers = 0L
        if ([long]::TryParse($fields[$fields.Count - 3], [ref]$ready) -and
            [long]::TryParse($fields[$fields.Count - 2], [ref]$unacked) -and
            [long]::TryParse($fields[$fields.Count - 1], [ref]$consumers)) {
            $queues[$fields[0]] = [pscustomobject]@{
                Ready = $ready
                Unacked = $unacked
                Consumers = $consumers
            }
        }
    }
    foreach ($required in @("contest.judge.live", "contest.judge.dead")) {
        if (-not $queues.ContainsKey($required)) {
            throw "RabbitMQ did not report required queue '$required'."
        }
    }
    if ($Candidate -eq "stream" -and -not $queues.ContainsKey("contest.judge.result.stream")) {
        throw "RabbitMQ did not report contest.judge.result.stream for the stream candidate."
    }
    return $queues
}

function Get-OperationalState {
    $judgeNonPublished = Invoke-SqlScalar `
        -Sql "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'" `
        -Description "non-published judge outbox rows"
    $scoreboardUnapplied = Invoke-SqlScalar `
        -Sql "SELECT COUNT(*) FROM contest_submission_result WHERE scoreboard_applied_at IS NULL" `
        -Description "unapplied scoreboard result rows"
    $queues = Get-RabbitQueueState
    $live = $queues["contest.judge.live"]
    $dead = $queues["contest.judge.dead"]
    $neutralPending = Get-NeutralPending

    $outboxNonterminal = 0L
    $duplicateGroups = 0L
    $streamDbPending = 0L
    if ($Candidate -eq "outbox") {
        $outboxNonterminal = Invoke-SqlScalar `
            -Sql "SELECT COUNT(*) FROM contest_submission_outbox WHERE status <> 'COMPLETED'" `
            -Description "nonterminal scoreboard outbox rows"
        $duplicateGroups = Invoke-SqlScalar `
            -Sql ("SELECT COUNT(*) FROM (" +
                "SELECT redis_seq FROM contest_submission_outbox " +
                "WHERE redis_seq IS NOT NULL AND status <> 'PROCESSING' " +
                "GROUP BY redis_seq HAVING COUNT(*) > 1) duplicate_sequences") `
            -Description "duplicate scoreboard redis_seq groups"
    }
    else {
        $streamDbPending = Get-StreamDbPending
    }

    $quiescent = $judgeNonPublished -eq 0L -and
        $scoreboardUnapplied -eq 0L -and
        $live.Ready -eq 0L -and $live.Unacked -eq 0L -and
        $dead.Ready -eq 0L -and $dead.Unacked -eq 0L -and
        $neutralPending -eq 0d -and
        (($Candidate -eq "outbox" -and $outboxNonterminal -eq 0L -and $duplicateGroups -eq 0L) -or
         ($Candidate -eq "stream" -and $streamDbPending -eq 0L))

    return [pscustomobject]@{
        Quiescent = $quiescent
        JudgeNonPublished = $judgeNonPublished
        ScoreboardUnapplied = $scoreboardUnapplied
        NeutralPending = $neutralPending
        LiveReady = $live.Ready
        LiveUnacked = $live.Unacked
        DeadReady = $dead.Ready
        DeadUnacked = $dead.Unacked
        OutboxNonterminal = $outboxNonterminal
        DuplicateGroups = $duplicateGroups
        StreamDbPending = $streamDbPending
    }
}

function Wait-PipelineDrain {
    param(
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastState = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        Assert-AllStackContainersHealthy
        $lastState = Get-OperationalState
        if ($lastState.Quiescent) {
            return $lastState
        }
        Start-Sleep -Milliseconds 1000
    }
    $detail = if ($null -eq $lastState) { "no state" } else {
        "judge=$($lastState.JudgeNonPublished), unapplied=$($lastState.ScoreboardUnapplied), " +
        "neutral=$($lastState.NeutralPending), live=$($lastState.LiveReady)/$($lastState.LiveUnacked), " +
        "dead=$($lastState.DeadReady)/$($lastState.DeadUnacked), outbox=$($lastState.OutboxNonterminal), " +
        "duplicates=$($lastState.DuplicateGroups), streamDbPending=$($lastState.StreamDbPending)"
    }
    throw "$Description did not reach operational quiescence within $TimeoutSeconds seconds ($detail)."
}

function Get-ContestResultCount {
    return Invoke-SqlScalar `
        -Sql "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = $ContestId" `
        -Description "contest $ContestId result count"
}

function Get-ScoreboardDigest {
    $pageSize = 200
    $startRank = 1L
    $totalParticipants = $null
    $entryCount = 0L
    $pageCount = 0
    $canonical = New-Object Text.StringBuilder

    while ($null -eq $totalParticipants -or $startRank -le $totalParticipants) {
        $uri = "$($BaseUrl.TrimEnd('/'))/api/contests/$ContestId/scoreboard?startRank=$startRank&size=$pageSize"
        try {
            $page = Invoke-RestMethod -Uri $uri -TimeoutSec 15
        }
        catch {
            throw "Could not read scoreboard page at startRank=${startRank}: $($_.Exception.Message)"
        }
        if ($null -eq $page) {
            throw "Scoreboard API returned no body at startRank=$startRank."
        }

        $pageContestId = ConvertTo-RequiredInt64 -Value $page.contestId -Description "scoreboard contestId"
        $pageStartRank = ConvertTo-RequiredInt64 -Value $page.startRank -Description "scoreboard startRank"
        $pageTotal = ConvertTo-RequiredInt64 -Value $page.totalParticipants -Description "scoreboard totalParticipants"
        if ($pageContestId -ne $ContestId -or $pageStartRank -ne $startRank) {
            throw "Scoreboard page identity mismatch: contest=$pageContestId, startRank=$pageStartRank."
        }
        if ($null -eq $totalParticipants) {
            $totalParticipants = $pageTotal
            [void]$canonical.Append("contestId=$ContestId`nparticipants=$totalParticipants`n")
        }
        elseif ($pageTotal -ne $totalParticipants) {
            throw "Scoreboard participant count changed during one digest ($totalParticipants -> $pageTotal)."
        }

        $entries = @($page.entries)
        $expectedEntries = [int][math]::Max(
            0L,
            [math]::Min([long]$pageSize, $totalParticipants - $startRank + 1L)
        )
        if ($entries.Count -ne $expectedEntries) {
            throw "Scoreboard page at rank $startRank returned $($entries.Count) entries; expected $expectedEntries."
        }
        foreach ($entry in $entries) {
            $rank = ConvertTo-RequiredInt64 -Value $entry.rank -Description "scoreboard entry rank"
            $userId = ConvertTo-RequiredInt64 -Value $entry.userId -Description "scoreboard entry userId"
            $solved = ConvertTo-RequiredInt64 -Value $entry.solvedCount -Description "scoreboard entry solvedCount"
            $penalty = ConvertTo-RequiredInt64 -Value $entry.penalty -Description "scoreboard entry penalty"
            [void]$canonical.Append("$rank|$userId|$solved|$penalty`n")
            $entryCount++
        }
        $pageCount++
        if ($entries.Count -eq 0) {
            break
        }
        $startRank += $entries.Count
    }
    if ($entryCount -ne $totalParticipants) {
        throw "Full scoreboard digest read $entryCount entries; expected $totalParticipants."
    }

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($canonical.ToString())
        $digestBytes = $sha.ComputeHash($bytes)
        $digest = ([BitConverter]::ToString($digestBytes)).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
    return [pscustomobject]@{
        Digest = $digest
        Participants = [long]$totalParticipants
        Entries = $entryCount
        Pages = $pageCount
        CanonicalBytes = $bytes.Length
    }
}

function Invoke-TailLoad {
    $classpathFile = Join-Path $script:resolvedWorktreeRoot "gatling\build\standalone-gatling\classpath.txt"
    if (-not (Test-Path -LiteralPath $classpathFile -PathType Leaf)) {
        throw "Missing standalone Gatling classpath '$classpathFile'. Run gradlew.bat :gatling:prepareStandaloneGatling in this worktree first."
    }
    if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
        throw "Java executable not found: $JavaExe"
    }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($classpath)) {
        throw "Standalone Gatling classpath is empty: $classpathFile"
    }

    $concurrentUsers = [int][math]::Ceiling($TailTargetRps * $TailSubmitIntervalMillis / 1000d)
    $availableUsers = $TailUserIndexEnd - $TailUserIndexStart + 1
    if ($concurrentUsers -gt $availableUsers) {
        throw "Tail needs $concurrentUsers users but indexes $TailUserIndexStart..$TailUserIndexEnd provide only $availableUsers."
    }

    $resultsRoot = Join-Path $script:resolvedArtifactDirectory "gatling-results"
    [void](New-Item -ItemType Directory -Path $resultsRoot -Force)
    $before = @(Get-ChildItem -LiteralPath $resultsRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $javaArguments = @(
        "-Dperf.baseUrl=$BaseUrl",
        "-Dperf.targetRps=$(Format-InvariantNumber $TailTargetRps)",
        "-Dperf.rampSeconds=$TailRampSeconds",
        "-Dperf.holdSeconds=$TailHoldSeconds",
        "-Dperf.userPrefix=loadtest",
        "-Dperf.userIndex.start=$TailUserIndexStart",
        "-Dperf.userIndex.end=$TailUserIndexEnd",
        "-Dperf.problemId.start=$ProblemIdStart",
        "-Dperf.problemId.end=$ProblemIdEnd",
        "-Dperf.submitIntervalMillis=$TailSubmitIntervalMillis",
        "-cp", $classpath,
        "io.gatling.app.Gatling",
        "-s", "my.oj.perf.ContestSubmissionSimulation",
        "-rf", $resultsRoot,
        "-rd", "C-stage $Candidate Redis RDB recovery tail"
    )
    # Windows PowerShell turns native stderr into ErrorRecord objects. Gatling and the JVM may
    # write harmless diagnostics there, so capture them without letting ErrorActionPreference=Stop
    # abort before the native exit code and HTTP assertions can be evaluated.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $JavaExe @javaArguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $logPath = Join-Path $script:resolvedArtifactDirectory "gatling-tail.log"
    $output | Set-Content -LiteralPath $logPath -Encoding utf8
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Gatling tail failed its HTTP assertions (exit $exitCode). See $logPath"
    }

    $after = @(Get-ChildItem -LiteralPath $resultsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notin $before } |
        Sort-Object LastWriteTimeUtc -Descending)
    if ($after.Count -eq 0) {
        throw "Gatling succeeded but created no result directory under $resultsRoot."
    }
    return $after[0].FullName
}

function Stop-ForPollInterval {
    param([Parameter(Mandatory = $true)][long]$LoopStartedMilliseconds)

    $remaining = $PollIntervalMilliseconds - ([long]$script:recoveryStopwatch.ElapsedMilliseconds - $LoopStartedMilliseconds)
    if ($remaining -gt 0L) {
        Start-Sleep -Milliseconds ([int]$remaining)
    }
}

if ($ProblemIdEnd -lt $ProblemIdStart) {
    throw "ProblemIdEnd must be greater than or equal to ProblemIdStart."
}
if ($TailUserIndexEnd -lt $TailUserIndexStart) {
    throw "TailUserIndexEnd must be greater than or equal to TailUserIndexStart."
}
if (-not (Test-Path -LiteralPath $WorktreeRoot -PathType Container)) {
    throw "WorktreeRoot does not exist: $WorktreeRoot"
}
$resolvedWorktreeRoot = (Resolve-Path -LiteralPath $WorktreeRoot).Path
$composeFiles = @(
    (Join-Path $resolvedWorktreeRoot "compose.yaml"),
    (Join-Path $resolvedWorktreeRoot "compose.loadtest.yaml"),
    (Join-Path $resolvedWorktreeRoot "compose.observability.yaml")
)
foreach ($composeFile in $composeFiles) {
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "Required Compose file is missing: $composeFile"
    }
}
$composeArgs = @(
    "-p", $projectName,
    "--project-directory", $resolvedWorktreeRoot,
    "-f", $composeFiles[0],
    "-f", $composeFiles[1],
    "-f", $composeFiles[2]
)

if (-not (Test-Path -LiteralPath $ArtifactDirectory)) {
    [void](New-Item -ItemType Directory -Path $ArtifactDirectory -Force)
}
if (-not (Test-Path -LiteralPath $ArtifactDirectory -PathType Container)) {
    throw "ArtifactDirectory is not a directory: $ArtifactDirectory"
}
$resolvedArtifactDirectory = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
$reservedArtifacts = @(
    "recovery-summary.csv", "recovery-samples.csv", "rdb-metadata.csv",
    "redis-k-dump.rdb", "gatling-tail.log", "recovery-failure.txt"
)
foreach ($name in $reservedArtifacts) {
    $path = Join-Path $resolvedArtifactDirectory $name
    if (Test-Path -LiteralPath $path) {
        throw "ArtifactDirectory already contains '$name'; use a fresh directory to avoid mixed evidence."
    }
}

try {
    Write-Host "Preflight: exact oj-loadtest stack, Prometheus targets, Redis persistence, and drained pipeline."
    Assert-AllStackContainersHealthy
    Assert-PrometheusTargetsHealthy
    Wait-RedisReady
    $stableContainerIdentities = Get-StableContainerIdentitySnapshot
    $batchIdentityBefore = $stableContainerIdentities[$batchContainer]

    $appendonly = Get-RedisConfigValue -Name "appendonly"
    $redisDir = Get-RedisConfigValue -Name "dir"
    $redisDbFilename = Get-RedisConfigValue -Name "dbfilename"
    if ($appendonly -ne "no" -or $redisDir -ne "/data" -or $redisDbFilename -ne "dump.rdb") {
        throw "Redis persistence preflight failed: appendonly=$appendonly, dir=$redisDir, dbfilename=$redisDbFilename."
    }
    [void](Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds -Description "Preflight pipeline")

    $commit = @(& git -C $resolvedWorktreeRoot rev-parse HEAD 2>$null) | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace([string]$commit)) {
        throw "Could not resolve the candidate commit in $resolvedWorktreeRoot."
    }

    Write-Host "Capturing rollback point K with only batch-1 paused."
    Pause-Batch
    try {
        $saveResult = @(Invoke-Redis -RedisArguments @("SAVE")) | Select-Object -Last 1
        if ([string]$saveResult -ne "OK") {
            throw "Redis SAVE returned '$saveResult', expected OK."
        }
        $rdbPath = Join-Path $resolvedArtifactDirectory "redis-k-dump.rdb"
        [void](Invoke-Docker -Arguments @("cp", "$redisContainer`:/data/dump.rdb", $rdbPath))
        if (-not (Test-Path -LiteralPath $rdbPath -PathType Leaf)) {
            throw "docker cp did not create $rdbPath."
        }
        $rdbFile = Get-Item -LiteralPath $rdbPath
        $rdbSha256 = (Get-FileHash -LiteralPath $rdbPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $kCapturedAt = [DateTimeOffset]::UtcNow
        $kCheckpoint = Get-Checkpoint
        $kResultCount = Get-ContestResultCount
        $kScoreboard = Get-ScoreboardDigest
    }
    finally {
        Resume-Batch
    }
    $rdbMetadata = [pscustomobject][ordered]@{
        candidate = $Candidate
        commit = ([string]$commit).Trim()
        capturedAtUtc = $kCapturedAt.UtcDateTime.ToString("o", $invariantCulture)
        checkpointKey = $checkpointKey
        checkpoint = $kCheckpoint
        contestResultCount = $kResultCount
        scoreboardDigestSha256 = $kScoreboard.Digest
        scoreboardParticipants = $kScoreboard.Participants
        appendonly = $appendonly
        redisDir = $redisDir
        redisDbFilename = $redisDbFilename
        rdbPath = $rdbPath
        rdbSha256 = $rdbSha256
        rdbBytes = $rdbFile.Length
    }
    $rdbMetadata | Export-Csv `
        -LiteralPath (Join-Path $resolvedArtifactDirectory "rdb-metadata.csv") `
        -NoTypeInformation `
        -Encoding utf8

    Write-Host "Adding the controlled full-API tail."
    $gatlingResultPath = Invoke-TailLoad
    [void](Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds -Description "Tail pipeline")
    Assert-StableContainerIdentities -Before $stableContainerIdentities
    Assert-PrometheusTargetsHealthy
    $nCapturedAt = [DateTimeOffset]::UtcNow
    $nCheckpoint = Get-Checkpoint
    $nResultCount = Get-ContestResultCount
    $tailResultCount = $nResultCount - $kResultCount
    $nScoreboard = Get-ScoreboardDigest
    if ($tailResultCount -le 0L) {
        throw "Tail produced $tailResultCount new contest results; expected M > 0."
    }
    if ($nCheckpoint -le $kCheckpoint) {
        throw "Checkpoint did not advance across the tail: K=$kCheckpoint, N=$nCheckpoint."
    }
    if ($nScoreboard.Digest -eq $kScoreboard.Digest) {
        throw "Semantic scoreboard digest did not change across a $tailResultCount-result tail."
    }

    Write-Host "Injecting Redis rollback to K (SIGKILL, stopped-container RDB overwrite, restart)."
    Pause-Batch
    [void](Invoke-Docker -Arguments @("kill", $redisContainer))
    $redisKilled = $true
    $stoppedStatus = Get-ContainerStatus -Name $redisContainer
    if ($stoppedStatus -eq "running") {
        throw "Redis remained running after docker kill."
    }
    [void](Invoke-Docker -Arguments @("cp", $rdbPath, "$redisContainer`:/data/dump.rdb"))
    [void](Invoke-Compose -Arguments @("start", "redis"))
    $redisKilled = $false
    Wait-RedisReady

    $restoredCheckpoint = Get-Checkpoint
    $restoredScoreboard = Get-ScoreboardDigest
    if ($restoredCheckpoint -ne $kCheckpoint) {
        throw "Restored Redis checkpoint is $restoredCheckpoint; expected K=$kCheckpoint."
    }
    if ($restoredScoreboard.Digest -ne $kScoreboard.Digest) {
        throw "Restored scoreboard digest '$($restoredScoreboard.Digest)' does not match K '$($kScoreboard.Digest)'."
    }

    $rollbackMetricBaseline = 0d
    if ($Candidate -eq "stream") {
        $rollbackSamples = @(Invoke-PrometheusQuery `
            -Query 'contest_scoreboard_stream_rollback_restarts_total{job="oj-app",node="batch-1"}' `
            -Description "stream rollback restart baseline")
        if ($rollbackSamples.Count -eq 1) {
            $rollbackMetricBaseline = ConvertTo-RequiredDouble `
                -Value @($rollbackSamples[0].value)[1] `
                -Description "stream rollback restart baseline"
        }
    }

    $recoveryStartedAt = [DateTimeOffset]::UtcNow
    $recoveryStopwatch = [Diagnostics.Stopwatch]::StartNew()
    Resume-Batch

    $detectionMilliseconds = $null
    $detectionSignal = ""
    $firstProgressMilliseconds = $null
    $checkpointConvergedMilliseconds = $null
    $digestConvergedMilliseconds = $null
    $primaryConvergenceMilliseconds = $null
    $lastCheckpoint = $kCheckpoint
    $deadlineMilliseconds = [long]$ConvergenceTimeoutSeconds * 1000L

    while ($recoveryStopwatch.ElapsedMilliseconds -lt $deadlineMilliseconds) {
        $loopStarted = [long]$recoveryStopwatch.ElapsedMilliseconds
        $observedAt = [DateTimeOffset]::UtcNow
        $lastCheckpoint = Get-Checkpoint
        if ($lastCheckpoint -gt $nCheckpoint) {
            throw "Recovery checkpoint overshot N: current=$lastCheckpoint, N=$nCheckpoint."
        }
        if ($null -eq $firstProgressMilliseconds -and $lastCheckpoint -gt $kCheckpoint) {
            $firstProgressMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
        }

        $prometheusValues = Capture-PrometheusSamples `
            -ObservedAt $observedAt `
            -ElapsedMilliseconds ([long]$recoveryStopwatch.ElapsedMilliseconds)

        if ($null -eq $detectionMilliseconds) {
            if ($Candidate -eq "stream" -and
                $prometheusValues.ContainsKey("contest_scoreboard_stream_rollback_restarts_total") -and
                [double]$prometheusValues["contest_scoreboard_stream_rollback_restarts_total"] -gt $rollbackMetricBaseline) {
                $detectionMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
                $detectionSignal = "contest_scoreboard_stream_rollback_restarts_total"
            }
            elseif ($Candidate -eq "outbox") {
                $nonterminal = Invoke-SqlScalar `
                    -Sql "SELECT COUNT(*) FROM contest_submission_outbox WHERE status <> 'COMPLETED'" `
                    -Description "outbox recovery detection"
                if ($nonterminal -gt 0L) {
                    $detectionMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
                    $detectionSignal = "contest_submission_outbox nonterminal rows"
                }
            }
            if ($null -eq $detectionMilliseconds -and $lastCheckpoint -gt $kCheckpoint) {
                $detectionMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
                $detectionSignal = "checkpoint progress (detection transition was shorter than polling interval)"
            }
        }

        if ($lastCheckpoint -eq $nCheckpoint) {
            if ($null -eq $checkpointConvergedMilliseconds) {
                $checkpointConvergedMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
            }
            $currentScoreboard = Get-ScoreboardDigest
            if ($currentScoreboard.Digest -eq $nScoreboard.Digest) {
                $digestConvergedMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
                $primaryConvergenceMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
                break
            }
        }
        Stop-ForPollInterval -LoopStartedMilliseconds $loopStarted
    }
    if ($null -eq $primaryConvergenceMilliseconds) {
        throw "Scoreboard did not converge to checkpoint/digest N within $ConvergenceTimeoutSeconds seconds (last checkpoint=$lastCheckpoint, N=$nCheckpoint)."
    }

    Write-Host "Primary scoreboard state converged; waiting for operational quiescence."
    $finalState = $null
    while ($recoveryStopwatch.ElapsedMilliseconds -lt $deadlineMilliseconds) {
        $loopStarted = [long]$recoveryStopwatch.ElapsedMilliseconds
        $observedAt = [DateTimeOffset]::UtcNow
        [void](Capture-PrometheusSamples `
            -ObservedAt $observedAt `
            -ElapsedMilliseconds ([long]$recoveryStopwatch.ElapsedMilliseconds))
        $finalState = Get-OperationalState
        if ($finalState.Quiescent) {
            $operationalQuiescenceMilliseconds = [long]$recoveryStopwatch.ElapsedMilliseconds
            break
        }
        Stop-ForPollInterval -LoopStartedMilliseconds $loopStarted
    }
    if ($null -eq $finalState -or -not $finalState.Quiescent) {
        throw "Primary scoreboard converged, but the pipeline did not become operationally quiescent within $ConvergenceTimeoutSeconds seconds."
    }

    $recoveryStopwatch.Stop()
    Wait-AllStackContainersHealthy
    Assert-StableContainerIdentities -Before $stableContainerIdentities
    Assert-PrometheusTargetsHealthy
    $finalCheckpoint = Get-Checkpoint
    $finalScoreboard = Get-ScoreboardDigest
    if ($finalCheckpoint -ne $nCheckpoint -or $finalScoreboard.Digest -ne $nScoreboard.Digest) {
        throw "Final verification moved away from N after quiescence."
    }

    $summary = [pscustomobject][ordered]@{
        candidate = $Candidate
        commit = ([string]$commit).Trim()
        worktreeRoot = $resolvedWorktreeRoot
        contestId = $ContestId
        problemIdStart = $ProblemIdStart
        problemIdEnd = $ProblemIdEnd
        baseUrl = $BaseUrl
        tailTargetRps = Format-InvariantNumber $TailTargetRps
        tailRampSeconds = $TailRampSeconds
        tailHoldSeconds = $TailHoldSeconds
        tailUserIndexStart = $TailUserIndexStart
        tailUserIndexEnd = $TailUserIndexEnd
        tailSubmitIntervalMillis = $TailSubmitIntervalMillis
        drainTimeoutSeconds = $DrainTimeoutSeconds
        convergenceTimeoutSeconds = $ConvergenceTimeoutSeconds
        checkpointKey = $checkpointKey
        batchContainerId = $batchIdentityBefore.Id
        batchStartedAt = $batchIdentityBefore.StartedAt
        batchRestartCount = $batchIdentityBefore.RestartCount
        kCapturedAtUtc = $kCapturedAt.UtcDateTime.ToString("o", $invariantCulture)
        nCapturedAtUtc = $nCapturedAt.UtcDateTime.ToString("o", $invariantCulture)
        recoveryStartedAtUtc = $recoveryStartedAt.UtcDateTime.ToString("o", $invariantCulture)
        kCheckpoint = $kCheckpoint
        nCheckpoint = $nCheckpoint
        checkpointDelta = $nCheckpoint - $kCheckpoint
        kResultCount = $kResultCount
        nResultCount = $nResultCount
        tailResultCount = $tailResultCount
        kScoreboardDigestSha256 = $kScoreboard.Digest
        nScoreboardDigestSha256 = $nScoreboard.Digest
        kParticipants = $kScoreboard.Participants
        nParticipants = $nScoreboard.Participants
        restoredCheckpoint = $restoredCheckpoint
        restoredScoreboardDigestSha256 = $restoredScoreboard.Digest
        detectionMs = $detectionMilliseconds
        detectionSignal = $detectionSignal
        firstProgressMs = $firstProgressMilliseconds
        checkpointConvergedMs = $checkpointConvergedMilliseconds
        digestConvergedMs = $digestConvergedMilliseconds
        primaryConvergenceMs = $primaryConvergenceMilliseconds
        operationalQuiescenceMs = $operationalQuiescenceMilliseconds
        manualIntervention = "false"
        pollIntervalMs = $PollIntervalMilliseconds
        prometheusSamples = $recoverySamples.Count
        finalNeutralPending = Format-InvariantNumber ([double]$finalState.NeutralPending)
        finalOutboxNonterminal = $finalState.OutboxNonterminal
        finalDuplicateRedisSeqGroups = $finalState.DuplicateGroups
        finalStreamDbPending = $finalState.StreamDbPending
        finalRabbitLiveReady = $finalState.LiveReady
        finalRabbitLiveUnacked = $finalState.LiveUnacked
        finalRabbitDeadReady = $finalState.DeadReady
        finalRabbitDeadUnacked = $finalState.DeadUnacked
        rdbPath = $rdbPath
        rdbSha256 = $rdbSha256
        rdbBytes = $rdbFile.Length
        gatlingResultPath = $gatlingResultPath
    }
    $summary | Export-Csv `
        -LiteralPath (Join-Path $resolvedArtifactDirectory "recovery-summary.csv") `
        -NoTypeInformation `
        -Encoding utf8

    $completed = $true
}
catch {
    $failure = $_
    try {
        $_ | Out-String | Set-Content `
            -LiteralPath (Join-Path $resolvedArtifactDirectory "recovery-failure.txt") `
            -Encoding utf8
    }
    catch {
        Write-Warning "Could not write recovery-failure.txt."
    }
}
finally {
    Restore-RecoveryContainers
    if ($recoverySamples.Count -gt 0) {
        $recoverySamples | Export-Csv `
            -LiteralPath (Join-Path $resolvedArtifactDirectory "recovery-samples.csv") `
            -NoTypeInformation `
            -Encoding utf8
    }
}

if (-not $completed) {
    throw $failure
}

Write-Host "Redis RDB recovery experiment passed for '$Candidate'."
Write-Host "Summary: $(Join-Path $resolvedArtifactDirectory 'recovery-summary.csv')"
Write-Host "Samples: $(Join-Path $resolvedArtifactDirectory 'recovery-samples.csv')"
Write-Host "RDB:     $(Join-Path $resolvedArtifactDirectory 'rdb-metadata.csv')"
