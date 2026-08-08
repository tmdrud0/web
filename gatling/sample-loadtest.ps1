# Samples the isolated load-test stack while Gatling drives it.
#
# run-loadtest.ps1 starts this as a background job around each Gatling run. Gatling only reports
# what the client saw, so a pass/fail number cannot say which container ran out of CPU or where
# work piled up. These samples do: container CPU/memory, the two outbox backlogs, the Rabbit
# queue depth, and the persisted submission count, all on one timeline.
#
# Samples land in four CSVs because they have different shapes and cadences. Every row carries the
# phase name, so one output directory can hold several scenarios back to back.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepoRoot,
    [Parameter(Mandatory = $true)][string]$ProjectName,
    [Parameter(Mandatory = $true)][string]$DbName,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$StopFile,
    [string]$Phase = "run",
    [int]$ContainerIntervalSeconds = 2,
    [int]$PipelineIntervalSeconds = 5
)

$ErrorActionPreference = "Continue"

$composeArgs = @("-p", $ProjectName, "-f", "compose.yaml", "-f", "compose.loadtest.yaml")
$containerCsv = Join-Path $OutputDirectory "containers.csv"
$pipelineCsv = Join-Path $OutputDirectory "pipeline.csv"
$jvmCsv = Join-Path $OutputDirectory "jvm-metrics.csv"
$rabbitmqCsv = Join-Path $OutputDirectory "rabbitmq-metrics.csv"
$redisScoreboardCsv = Join-Path $OutputDirectory "redis-scoreboard-metrics.csv"
$prometheusUrl = "http://127.0.0.1:9090"

# One statement keeps the MySQL side of the pipeline snapshot on a single round trip. The
# scoreboard SQL count is a fallback when the optional observability overlay is absent. When Prometheus is
# available, Sample-Pipeline replaces it with the implementation-neutral stream-tail minus Redis
# applied-offset gauge exported by batch-1.
$pipelineSql = @(
    "SELECT"
    "(SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'),"
    "(SELECT COALESCE((SELECT TIMESTAMPDIFF(MICROSECOND, created_at, CURRENT_TIMESTAMP(6)) DIV 1000 FROM contest_judge_outbox WHERE status = 'PENDING' ORDER BY claimed_at, id LIMIT 1), 0)),"
    "(SELECT COUNT(*) FROM contest_judge_outbox WHERE status = 'PUBLISHED'),"
    "(SELECT COUNT(*) FROM contest_submission_result WHERE scoreboard_applied_at IS NULL),"
    "0,"
    "0,"
    "(SELECT COALESCE((SELECT TIMESTAMPDIFF(MICROSECOND, result_saved_at, CURRENT_TIMESTAMP(6)) DIV 1000 FROM contest_submission_result WHERE scoreboard_applied_at IS NULL ORDER BY result_saved_at, submission_id LIMIT 1), 0)),"
    "(SELECT COUNT(*) FROM contest_submission),"
    "(SELECT COUNT(*) FROM contest_submission_result),"
    "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_connected'),"
    "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_running')"
) -join " "

function Write-CsvLine {
    param([string]$Path, [string]$Line)
    try {
        Add-Content -Path $Path -Value $Line -Encoding utf8
    }
    catch {
        # A sampler must never take the run down. Losing one row is acceptable.
    }
}

function ConvertTo-Megabytes {
    param([string]$Value)
    if (-not $Value) { return $null }
    $trimmed = $Value.Trim()
    if ($trimmed -match '^(?<number>[0-9.]+)\s*(?<unit>[A-Za-z]+)$') {
        $number = [double]$Matches.number
        switch ($Matches.unit.ToLowerInvariant()) {
            "b"   { return [math]::Round($number / 1MB, 2) }
            "kib" { return [math]::Round($number / 1024, 2) }
            "kb"  { return [math]::Round($number / 1000, 2) }
            "mib" { return [math]::Round($number, 2) }
            "mb"  { return [math]::Round($number, 2) }
            "gib" { return [math]::Round($number * 1024, 2) }
            "gb"  { return [math]::Round($number * 1000, 2) }
            default { return $null }
        }
    }
    return $null
}

function Get-CpuThrottleCounters {
    param([string]$ContainerName)

    if ($ContainerName -notmatch '-(?:web-[12]|batch-1|judge-[12])$') {
        return $null
    }

    $stats = @(& docker exec $ContainerName cat /sys/fs/cgroup/cpu.stat 2>$null)
    if ($LASTEXITCODE -ne 0 -or $stats.Count -eq 0) {
        $stats = @(& docker exec $ContainerName cat /sys/fs/cgroup/cpu/cpu.stat 2>$null)
    }
    if ($LASTEXITCODE -ne 0 -or $stats.Count -eq 0) {
        return $null
    }

    $periods = $null
    $throttled = $null
    foreach ($stat in $stats) {
        if ($stat -match '^nr_periods\s+(?<value>[0-9]+)$') {
            $periods = [int64]$Matches.value
        }
        elseif ($stat -match '^nr_throttled\s+(?<value>[0-9]+)$') {
            $throttled = [int64]$Matches.value
        }
    }
    if ($null -eq $periods -or $null -eq $throttled) {
        return $null
    }
    return [pscustomobject]@{ Periods = $periods; Throttled = $throttled }
}

function Get-PrometheusValuesByNode {
    param([Parameter(Mandatory = $true)][string]$Query)

    try {
        $encodedQuery = [uri]::EscapeDataString($Query)
        $response = Invoke-RestMethod -Uri "$prometheusUrl/api/v1/query?query=$encodedQuery" -TimeoutSec 4
        if ($response.status -ne "success") {
            return $null
        }

        $values = @{}
        foreach ($sample in @($response.data.result)) {
            $node = [string]$sample.metric.node
            if (-not [string]::IsNullOrWhiteSpace($node) -and $sample.value.Count -ge 2) {
                $values[$node] = [double]::Parse(
                    [string]$sample.value[1],
                    [Globalization.CultureInfo]::InvariantCulture)
            }
        }
        return $values
    }
    catch {
        # The observability overlay is optional for other harness users. Missing one scrape must
        # not take the load run down; the final summary makes incomplete JVM evidence visible.
        return $null
    }
}

function Format-InvariantNumber {
    param($Value)

    if ($null -eq $Value) { return "" }
    return ([double]$Value).ToString("R", [Globalization.CultureInfo]::InvariantCulture)
}

function Sample-JvmMetrics {
    param([string]$Timestamp)

    # The 30-second rate window contains six app scrapes. It is long enough not to turn one
    # scheduler period into a spike, and short enough that the 180-second hold dominates it.
    $throttle = Get-PrometheusValuesByNode -Query (
        'sum by (node) (rate(cgroup_cpu_throttled_periods_total{job="oj-app"}[30s])) / ' +
        'sum by (node) (rate(cgroup_cpu_periods_total{job="oj-app"}[30s]))')
    $processStart = Get-PrometheusValuesByNode -Query `
        'max by (node) (process_start_time_seconds{job="oj-app"})'
    $gcTime = Get-PrometheusValuesByNode -Query `
        'sum by (node) (jvm_gc_pause_seconds_sum{job="oj-app"})'
    $heapUsed = Get-PrometheusValuesByNode -Query `
        'sum by (node) (jvm_memory_used_bytes{job="oj-app",area="heap"})'

    if ($null -eq $processStart -or $processStart.Count -eq 0) { return }
    foreach ($node in @($processStart.Keys | Sort-Object)) {
        $throttleValue = if ($null -ne $throttle -and $throttle.ContainsKey($node)) { $throttle[$node] } else { $null }
        $gcValue = if ($null -ne $gcTime -and $gcTime.ContainsKey($node)) { $gcTime[$node] } else { $null }
        $heapValue = if ($null -ne $heapUsed -and $heapUsed.ContainsKey($node)) { $heapUsed[$node] } else { $null }
        $line = @(
            $Timestamp,
            $Phase,
            $node,
            (Format-InvariantNumber $throttleValue),
            (Format-InvariantNumber $processStart[$node]),
            (Format-InvariantNumber $gcValue),
            (Format-InvariantNumber $heapValue)
        ) -join ','
        Write-CsvLine -Path $jvmCsv -Line $line
    }
}

function Sample-RabbitMqMetrics {
    param([string]$Timestamp)

    try {
        # One instant query returns all retained queue-level gauges and counters. Keeping raw
        # counters in the artifact lets the summary calculate rates over exactly the load phase,
        # without depending on a later Prometheus retention window.
        $query = '{job="rabbitmq-per-queue",queue=~"contest\\.judge\\.(live|dead|result\\.stream)"}'
        $encodedQuery = [uri]::EscapeDataString($query)
        $response = Invoke-RestMethod -Uri "$prometheusUrl/api/v1/query?query=$encodedQuery" -TimeoutSec 4
        if ($response.status -ne "success") { return }

        $fieldsByMetric = @{
            rabbitmq_detailed_queue_messages_ready = "Ready"
            rabbitmq_detailed_queue_messages_unacked = "Unacked"
            rabbitmq_detailed_queue_consumers = "Consumers"
            rabbitmq_detailed_queue_exchange_messages_published_total = "PublishedTotal"
            rabbitmq_detailed_queue_messages_delivered_ack_total = "DeliveredAckTotal"
            rabbitmq_detailed_queue_messages_delivered_total = "DeliveredAutoTotal"
        }
        $queues = @{
            "contest.judge.live" = @{
                Ready = 0d; Unacked = 0d; Consumers = 0d; PublishedTotal = 0d
                DeliveredAckTotal = 0d; DeliveredAutoTotal = 0d; Seen = $false
            }
            "contest.judge.dead" = @{
                Ready = 0d; Unacked = 0d; Consumers = 0d; PublishedTotal = 0d
                DeliveredAckTotal = 0d; DeliveredAutoTotal = 0d; Seen = $false
            }
            "contest.judge.result.stream" = @{
                Ready = 0d; Unacked = 0d; Consumers = 0d; PublishedTotal = 0d
                DeliveredAckTotal = 0d; DeliveredAutoTotal = 0d; Seen = $false
            }
        }

        foreach ($sample in @($response.data.result)) {
            $queue = [string]$sample.metric.queue
            $metricName = [string]$sample.metric.__name__
            if (-not $queues.ContainsKey($queue) -or -not $fieldsByMetric.ContainsKey($metricName) -or
                $sample.value.Count -lt 2) {
                continue
            }
            $queues[$queue][$fieldsByMetric[$metricName]] = [double]::Parse(
                [string]$sample.value[1],
                [Globalization.CultureInfo]::InvariantCulture)
            $queues[$queue].Seen = $true
        }

        if (-not ($queues.Values | Where-Object { $_.Seen })) { return }
        foreach ($queue in @($queues.Keys | Sort-Object)) {
            $values = $queues[$queue]
            if (-not $values.Seen) { continue }
            $line = @(
                $Timestamp,
                $Phase,
                $queue,
                (Format-InvariantNumber $values.Ready),
                (Format-InvariantNumber $values.Unacked),
                (Format-InvariantNumber $values.Consumers),
                (Format-InvariantNumber $values.PublishedTotal),
                (Format-InvariantNumber $values.DeliveredAckTotal),
                (Format-InvariantNumber $values.DeliveredAutoTotal)
            ) -join ','
            Write-CsvLine -Path $rabbitmqCsv -Line $line
        }
    }
    catch {
        # The observability overlay remains optional for harness users. No queue rows means the
        # final baseline summary is visibly unavailable rather than manufacturing zeroes.
    }
}

function Sample-RedisScoreboardMetrics {
    param([string]$Timestamp)

    try {
        # Keep cumulative bucket/counter values so the final report can calculate an exact phase
        # delta even when JVMs have been running across several scenarios.
        $query = '{job="oj-app",__name__=~"contest_scoreboard_redis_(pipeline_seconds_bucket|lua_errors_total|wrong_attempt_fields|wrong_attempt_poll_failures_total|wrong_attempt_poll_seconds_bucket)"}'
        $encodedQuery = [uri]::EscapeDataString($query)
        $response = Invoke-RestMethod -Uri "$prometheusUrl/api/v1/query?query=$encodedQuery" -TimeoutSec 4
        if ($response.status -ne "success") { return }

        $totals = @{}
        foreach ($sample in @($response.data.result)) {
            if ($sample.value.Count -lt 2) { continue }
            $metric = [string]$sample.metric.__name__
            $label = if ($metric -in @(
                "contest_scoreboard_redis_pipeline_seconds_bucket",
                "contest_scoreboard_redis_wrong_attempt_poll_seconds_bucket"
            )) {
                [string]$sample.metric.le
            }
            elseif ($metric -eq "contest_scoreboard_redis_lua_errors_total") {
                [string]$sample.metric.kind
            }
            else {
                "all"
            }
            $key = "$metric|$label"
            $value = [double]::Parse(
                [string]$sample.value[1],
                [Globalization.CultureInfo]::InvariantCulture)
            if (-not $totals.ContainsKey($key)) { $totals[$key] = 0d }
            $totals[$key] += $value
        }

        foreach ($key in @($totals.Keys | Sort-Object)) {
            $parts = $key -split '\|', 2
            Write-CsvLine -Path $redisScoreboardCsv -Line (@(
                $Timestamp,
                $Phase,
                $parts[0],
                $parts[1],
                (Format-InvariantNumber $totals[$key])
            ) -join ',')
        }
    }
    catch {
        # The optional observability overlay has the same contract as the JVM and RabbitMQ
        # samplers: missing evidence is reported later, never converted into a healthy zero.
    }
}

function Sample-Containers {
    param([string]$Timestamp)

    $lines = @(& docker stats --no-stream --format "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}" 2>$null)
    if ($LASTEXITCODE -ne 0) { return }

    foreach ($line in $lines) {
        if (-not $line) { continue }
        $parts = $line -split '\|'
        if ($parts.Count -lt 3) { continue }

        $name = $parts[0].Trim()
        if (-not $name.StartsWith($ProjectName)) { continue }

        $cpu = $parts[1].Trim().TrimEnd('%')
        $memParts = $parts[2] -split '/'
        $memUsed = if ($memParts.Count -ge 1) { ConvertTo-Megabytes $memParts[0] } else { $null }
        $memLimit = if ($memParts.Count -ge 2) { ConvertTo-Megabytes $memParts[1] } else { $null }
        $throttle = Get-CpuThrottleCounters -ContainerName $name
        $cpuPeriods = if ($null -ne $throttle) { $throttle.Periods } else { $null }
        $cpuThrottledPeriods = if ($null -ne $throttle) { $throttle.Throttled } else { $null }

        Write-CsvLine -Path $containerCsv -Line "$Timestamp,$Phase,$name,$cpu,$memUsed,$memLimit,$cpuPeriods,$cpuThrottledPeriods"
    }
}

function Sample-Pipeline {
    param([string]$Timestamp)

    Push-Location $RepoRoot
    try {
        $row = & docker compose @composeArgs exec -T mysql mysql -uroot -p1234 -D $DbName -Nse $pipelineSql 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $row) { return }
        $values = ($row | Select-Object -Last 1) -split "`t"
        if ($values.Count -lt 11) { return }

        $streamPending = Get-PrometheusValuesByNode -Query `
            'max by (node) (contest_scoreboard_pending_events)'
        if ($null -ne $streamPending -and $streamPending.Count -gt 0) {
            $values[3] = [double](($streamPending.Values | Measure-Object -Sum).Sum)
        }

        $rabbitReady = 0L
        $rabbitUnacked = 0L
        $rabbitLiveReady = 0L
        $rabbitLiveUnacked = 0L
        $rabbitLiveConsumers = 0L
        $rabbitDeadReady = 0L
        $rabbitDeadUnacked = 0L
        $rabbitDeadConsumers = 0L
        $queues = @(& docker compose @composeArgs exec -T rabbitmq rabbitmqctl list_queues -q name messages_ready messages_unacknowledged consumers 2>$null)
        if ($LASTEXITCODE -eq 0) {
            foreach ($queue in $queues) {
                $fields = ($queue -split '\s+') | Where-Object { $_ }
                $ready = 0L
                $unacked = 0L
                $consumers = 0L
                if ($fields.Count -ge 4 -and
                    [int64]::TryParse($fields[$fields.Count - 3], [ref]$ready) -and
                    [int64]::TryParse($fields[$fields.Count - 2], [ref]$unacked) -and
                    [int64]::TryParse($fields[$fields.Count - 1], [ref]$consumers)) {
                    if ($fields[0] -eq "contest.judge.live") {
                        $rabbitReady += $ready
                        $rabbitUnacked += $unacked
                        $rabbitLiveReady = $ready
                        $rabbitLiveUnacked = $unacked
                        $rabbitLiveConsumers = $consumers
                    }
                    elseif ($fields[0] -eq "contest.judge.dead") {
                        $rabbitReady += $ready
                        $rabbitUnacked += $unacked
                        $rabbitDeadReady = $ready
                        $rabbitDeadUnacked = $unacked
                        $rabbitDeadConsumers = $consumers
                    }
                }
            }
        }

        Write-CsvLine -Path $pipelineCsv -Line ("$Timestamp,$Phase," + ($values -join ',') +
            ",$rabbitReady,$rabbitUnacked,$rabbitLiveReady,$rabbitLiveUnacked,$rabbitLiveConsumers," +
            "$rabbitDeadReady,$rabbitDeadUnacked,$rabbitDeadConsumers")
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $containerCsv)) {
    Write-CsvLine -Path $containerCsv -Line "timestamp,phase,container,cpuPercent,memUsedMb,memLimitMb,cpuPeriods,cpuThrottledPeriods"
}
if (-not (Test-Path $pipelineCsv)) {
    Write-CsvLine -Path $pipelineCsv -Line ("timestamp,phase,judgeOutboxPending,judgeHeadLagMs,judgeOutboxPublished,scoreboardPending,scoreboardProcessing," +
        "scoreboardFailed,oldestPendingLagMs,submissionRows,resultRows,mysqlThreadsConnected,mysqlThreadsRunning," +
        "rabbitReady,rabbitUnacked,rabbitLiveReady,rabbitLiveUnacked,rabbitLiveConsumers," +
        "rabbitDeadReady,rabbitDeadUnacked,rabbitDeadConsumers")
}
if (-not (Test-Path $jvmCsv)) {
    Write-CsvLine -Path $jvmCsv -Line "timestamp,phase,node,throttleRatio,processStartTimeSeconds,gcPauseSeconds,heapUsedBytes"
}
if (-not (Test-Path $rabbitmqCsv)) {
    Write-CsvLine -Path $rabbitmqCsv -Line "timestamp,phase,queue,ready,unacked,consumers,publishedTotal,deliveredAckTotal,deliveredAutoTotal"
}
if (-not (Test-Path $redisScoreboardCsv)) {
    Write-CsvLine -Path $redisScoreboardCsv -Line "timestamp,phase,metric,label,value"
}

$lastPipelineSample = [DateTime]::MinValue
$lastJvmSample = [DateTime]::MinValue
while (-not (Test-Path $StopFile)) {
    $now = Get-Date
    $timestamp = $now.ToString("yyyy-MM-ddTHH:mm:ss.fff")

    Sample-Containers -Timestamp $timestamp
    if (($now - $lastPipelineSample).TotalSeconds -ge $PipelineIntervalSeconds) {
        Sample-Pipeline -Timestamp $timestamp
        Sample-RabbitMqMetrics -Timestamp $timestamp
        Sample-RedisScoreboardMetrics -Timestamp $timestamp
        $lastPipelineSample = $now
    }
    if (($now - $lastJvmSample).TotalSeconds -ge $PipelineIntervalSeconds) {
        Sample-JvmMetrics -Timestamp $timestamp
        $lastJvmSample = $now
    }

    Start-Sleep -Seconds $ContainerIntervalSeconds
}
