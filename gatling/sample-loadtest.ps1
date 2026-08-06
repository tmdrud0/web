# Samples the isolated load-test stack while Gatling drives it.
#
# run-loadtest.ps1 starts this as a background job around each Gatling run. Gatling only reports
# what the client saw, so a pass/fail number cannot say which container ran out of CPU or where
# work piled up. These samples do: container CPU/memory, the two outbox backlogs, the Rabbit
# queue depth, and the persisted submission count, all on one timeline.
#
# Samples land in two CSVs because they have different shapes and cadences. Every row carries the
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

# One statement keeps the whole pipeline snapshot on a single MySQL round trip. due_at is the
# indexed "claimable at" column, so the largest positive age over non-COMPLETED rows is how long
# the oldest undrained scoreboard event has been waiting.
$pipelineSql = @(
    "SELECT"
    "(SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'),"
    "(SELECT COUNT(*) FROM contest_submission_outbox WHERE status = 'PENDING'),"
    "(SELECT COUNT(*) FROM contest_submission_outbox WHERE status = 'PROCESSING'),"
    "(SELECT COUNT(*) FROM contest_submission_outbox WHERE status = 'FAILED'),"
    "(SELECT COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND, due_at, CURRENT_TIMESTAMP(6))), 0) DIV 1000 FROM contest_submission_outbox WHERE status <> 'COMPLETED' AND due_at IS NOT NULL),"
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

        Write-CsvLine -Path $containerCsv -Line "$Timestamp,$Phase,$name,$cpu,$memUsed,$memLimit"
    }
}

function Sample-Pipeline {
    param([string]$Timestamp)

    Push-Location $RepoRoot
    try {
        $row = & docker compose @composeArgs exec -T mysql mysql -uroot -p1234 -D $DbName -Nse $pipelineSql 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $row) { return }
        $values = ($row | Select-Object -Last 1) -split "`t"
        if ($values.Count -lt 9) { return }

        $rabbitReady = 0L
        $rabbitUnacked = 0L
        $queues = @(& docker compose @composeArgs exec -T rabbitmq rabbitmqctl list_queues -q name messages_ready messages_unacknowledged 2>$null)
        if ($LASTEXITCODE -eq 0) {
            foreach ($queue in $queues) {
                $fields = ($queue -split '\s+') | Where-Object { $_ }
                $ready = 0L
                $unacked = 0L
                if ($fields.Count -ge 3 -and
                    [int64]::TryParse($fields[$fields.Count - 2], [ref]$ready) -and
                    [int64]::TryParse($fields[$fields.Count - 1], [ref]$unacked)) {
                    $rabbitReady += $ready
                    $rabbitUnacked += $unacked
                }
            }
        }

        Write-CsvLine -Path $pipelineCsv -Line ("$Timestamp,$Phase," + ($values -join ',') + ",$rabbitReady,$rabbitUnacked")
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $containerCsv)) {
    Write-CsvLine -Path $containerCsv -Line "timestamp,phase,container,cpuPercent,memUsedMb,memLimitMb"
}
if (-not (Test-Path $pipelineCsv)) {
    Write-CsvLine -Path $pipelineCsv -Line ("timestamp,phase,judgeOutboxPending,scoreboardPending,scoreboardProcessing," +
        "scoreboardFailed,oldestPendingLagMs,submissionRows,resultRows,mysqlThreadsConnected,mysqlThreadsRunning," +
        "rabbitReady,rabbitUnacked")
}

$lastPipelineSample = [DateTime]::MinValue
while (-not (Test-Path $StopFile)) {
    $now = Get-Date
    $timestamp = $now.ToString("yyyy-MM-ddTHH:mm:ss.fff")

    Sample-Containers -Timestamp $timestamp
    if (($now - $lastPipelineSample).TotalSeconds -ge $PipelineIntervalSeconds) {
        Sample-Pipeline -Timestamp $timestamp
        $lastPipelineSample = $now
    }

    Start-Sleep -Seconds $ContainerIntervalSeconds
}
