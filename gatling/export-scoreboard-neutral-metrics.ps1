# Exports the implementation-neutral scoreboard curves for one completed load-test phase.
#
# The load sampler already records the phase boundaries in pipeline.csv. This script uses those
# timestamps to fetch the corresponding Prometheus range samples without inventing a second clock
# or changing any application metric. A comparison artifact is accepted only when every expected
# target is currently healthy and batch-1 exposes exactly one usable series for each neutral metric.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory,

    [ValidateNotNullOrEmpty()]
    [string]$PrometheusUrl = "http://127.0.0.1:9090",

    [ValidateNotNullOrEmpty()]
    [string]$Phase = "submit-100",

    [ValidateRange(1, 3600)]
    [int]$StepSeconds = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$prometheusBaseUrl = $PrometheusUrl.TrimEnd('/')
$rawCsvName = "scoreboard-neutral-metrics-raw.csv"
$summaryCsvName = "scoreboard-neutral-metrics-summary.csv"
$prePaddingSteps = 1
$postPaddingSteps = 3
$maxWaitMilliseconds = 55000

function Format-InvariantNumber {
    param([Parameter(Mandatory = $true)][double]$Value)

    return $Value.ToString("R", [Globalization.CultureInfo]::InvariantCulture)
}

function Invoke-PrometheusApi {
    param(
        [Parameter(Mandatory = $true)][string]$Endpoint,
        [Parameter(Mandatory = $true)][string]$Description
    )

    try {
        $response = Invoke-RestMethod -Uri $Endpoint -TimeoutSec 10
    }
    catch {
        throw "Prometheus $Description request failed: $($_.Exception.Message)"
    }

    if ($null -eq $response -or [string]$response.status -ne "success") {
        $status = if ($null -eq $response) { "empty response" } else { [string]$response.status }
        throw "Prometheus $Description request did not succeed (status: $status)."
    }
    return $response
}

function ConvertTo-FiniteDouble {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $parsed = 0d
    if (-not [double]::TryParse(
            [string]$Value,
            [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed) -or
        [double]::IsNaN($parsed) -or
        [double]::IsInfinity($parsed)) {
        throw "$Description is not a finite number: '$Value'."
    }
    return $parsed
}

function ConvertTo-PipelineTimestamp {
    param([Parameter(Mandatory = $true)][string]$Value)

    $parsed = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::AllowWhiteSpaces -bor
        [Globalization.DateTimeStyles]::AssumeLocal
    if (-not [DateTimeOffset]::TryParse(
            $Value,
            [Globalization.CultureInfo]::InvariantCulture,
            $styles,
            [ref]$parsed)) {
        throw "pipeline.csv contains an invalid timestamp for phase '$Phase': '$Value'."
    }
    return $parsed
}

function ConvertTo-UtcTimestamp {
    param([Parameter(Mandatory = $true)][double]$UnixSeconds)

    $milliseconds = [int64][math]::Round($UnixSeconds * 1000d)
    return [DateTimeOffset]::FromUnixTimeMilliseconds($milliseconds).UtcDateTime.ToString(
        "o",
        [Globalization.CultureInfo]::InvariantCulture)
}

function Assert-TargetHealth {
    $query = [uri]::EscapeDataString('up')
    $response = Invoke-PrometheusApi `
        -Endpoint "$prometheusBaseUrl/api/v1/query?query=$query" `
        -Description "target-health"

    if ([string]$response.data.resultType -ne "vector") {
        throw "Prometheus target-health query returned '$($response.data.resultType)' instead of vector."
    }

    $samples = @($response.data.result)
    $values = @()
    foreach ($sample in $samples) {
        $pair = @($sample.value)
        if ($pair.Count -lt 2) {
            throw "Prometheus target-health query returned a sample without timestamp/value."
        }
        $values += ConvertTo-FiniteDouble -Value $pair[1] -Description "up value"
    }

    $totalSum = if ($values.Count -eq 0) { 0d } else { [double](($values | Measure-Object -Sum).Sum) }
    if ($samples.Count -ne 12 -or $totalSum -ne 12d) {
        throw "Prometheus target health is incomplete: count(up)=$($samples.Count), sum(up)=$(Format-InvariantNumber $totalSum); expected 12/12."
    }

    $ojApp = @($samples | Where-Object { [string]$_.metric.job -eq "oj-app" })
    $ojAppValues = @($ojApp | ForEach-Object {
        ConvertTo-FiniteDouble -Value @($_.value)[1] -Description "oj-app up value"
    })
    $ojAppSum = if ($ojAppValues.Count -eq 0) { 0d } else { [double](($ojAppValues | Measure-Object -Sum).Sum) }
    if ($ojApp.Count -ne 5 -or $ojAppSum -ne 5d) {
        throw "Prometheus oj-app targets are incomplete: count=$($ojApp.Count), sum=$(Format-InvariantNumber $ojAppSum); expected 5/5."
    }

    $rabbitDetailed = @($samples | Where-Object { [string]$_.metric.job -eq "rabbitmq-per-queue" })
    $rabbitValues = @($rabbitDetailed | ForEach-Object {
        ConvertTo-FiniteDouble -Value @($_.value)[1] -Description "rabbitmq-per-queue up value"
    })
    $rabbitSum = if ($rabbitValues.Count -eq 0) { 0d } else { [double](($rabbitValues | Measure-Object -Sum).Sum) }
    if ($rabbitDetailed.Count -ne 1 -or $rabbitSum -ne 1d) {
        throw "Prometheus rabbitmq-per-queue target is incomplete: count=$($rabbitDetailed.Count), sum=$(Format-InvariantNumber $rabbitSum); expected 1/1."
    }
}

function Get-RangeSeries {
    param(
        [Parameter(Mandatory = $true)][string]$Metric,
        [Parameter(Mandatory = $true)][double]$StartSeconds,
        [Parameter(Mandatory = $true)][double]$EndSeconds,
        [Parameter(Mandatory = $true)][double]$RequiredStartSeconds,
        [Parameter(Mandatory = $true)][double]$RequiredEndSeconds
    )

    # Filtering here deliberately selects the comparison owner rather than summing identical
    # registrations on other roles. Both implementations define batch-1 as the scoreboard worker.
    $selector = $Metric + '{job="oj-app",node="batch-1"}'
    $query = [uri]::EscapeDataString($selector)
    $start = Format-InvariantNumber $StartSeconds
    $end = Format-InvariantNumber $EndSeconds
    $response = Invoke-PrometheusApi `
        -Endpoint "$prometheusBaseUrl/api/v1/query_range?query=$query&start=$start&end=$end&step=$StepSeconds" `
        -Description "range query for $Metric"

    if ([string]$response.data.resultType -ne "matrix") {
        throw "Prometheus range query for $Metric returned '$($response.data.resultType)' instead of matrix."
    }

    $series = @($response.data.result)
    if ($series.Count -ne 1) {
        throw "Prometheus range query for $Metric returned $($series.Count) batch-1 series; expected exactly 1."
    }
    if ([string]$series[0].metric.node -ne "batch-1" -or [string]$series[0].metric.job -ne "oj-app") {
        throw "Prometheus range query for $Metric returned an unexpected owner; expected job=oj-app,node=batch-1."
    }

    $samples = @()
    foreach ($pairValue in @($series[0].values)) {
        $pair = @($pairValue)
        if ($pair.Count -lt 2) {
            throw "Prometheus range query for $Metric returned a sample without timestamp/value."
        }
        $timestamp = ConvertTo-FiniteDouble -Value $pair[0] -Description "$Metric timestamp"
        $value = ConvertTo-FiniteDouble -Value $pair[1] -Description "$Metric value"
        $samples += [pscustomobject]@{
            Timestamp = $timestamp
            Value = $value
        }
    }
    $samples = @($samples | Sort-Object Timestamp)
    if ($samples.Count -lt 2) {
        throw "Prometheus range query for $Metric returned $($samples.Count) samples; expected at least 2."
    }
    for ($index = 1; $index -lt $samples.Count; $index++) {
        if ($samples[$index].Timestamp -le $samples[$index - 1].Timestamp) {
            throw "Prometheus range query for $Metric returned duplicate or decreasing timestamps."
        }
    }
    if ($samples[0].Timestamp -gt $RequiredStartSeconds -or
        $samples[$samples.Count - 1].Timestamp -lt $RequiredEndSeconds) {
        throw "Prometheus range query for $Metric does not cover the pipeline phase boundaries " +
            "($($samples[0].Timestamp)-$($samples[$samples.Count - 1].Timestamp)); " +
            "required $RequiredStartSeconds-$RequiredEndSeconds."
    }

    return [pscustomobject]@{
        Metric = $Metric
        Node = "batch-1"
        Samples = $samples
    }
}

if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    throw "OutputDirectory does not exist or is not a directory: $OutputDirectory"
}
$resolvedOutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$pipelineCsv = Join-Path $resolvedOutputDirectory "pipeline.csv"
if (-not (Test-Path -LiteralPath $pipelineCsv -PathType Leaf)) {
    throw "The comparison artifact is missing pipeline.csv: $pipelineCsv"
}

$pipelineRows = @(Import-Csv -LiteralPath $pipelineCsv)
if ($pipelineRows.Count -eq 0) {
    throw "pipeline.csv is empty: $pipelineCsv"
}
$columns = @($pipelineRows[0].PSObject.Properties.Name)
foreach ($requiredColumn in @("timestamp", "phase")) {
    if ($requiredColumn -notin $columns) {
        throw "pipeline.csv is missing the required '$requiredColumn' column: $pipelineCsv"
    }
}

$phaseRows = @($pipelineRows | Where-Object { [string]$_.phase -eq $Phase })
if ($phaseRows.Count -lt 2) {
    throw "pipeline.csv has $($phaseRows.Count) rows for phase '$Phase'; at least 2 are required to infer a range."
}
$phaseTimestamps = @($phaseRows | ForEach-Object {
    ConvertTo-PipelineTimestamp -Value ([string]$_.timestamp)
} | Sort-Object)
$rangeStart = $phaseTimestamps[0]
$rangeEnd = $phaseTimestamps[$phaseTimestamps.Count - 1]
if ($rangeEnd -le $rangeStart) {
    throw "pipeline.csv phase '$Phase' does not span a positive time range."
}

# A pipeline sample and a Prometheus scrape have independent 5-second clocks. Querying exactly to
# the last pipeline row can therefore return the previous scrape, before the drain became visible.
# One leading step captures the true counter baseline; three trailing steps allow the terminal
# scrape to land. Never block a caller for a minute: a larger future range must be retried later.
$queryRangeStart = $rangeStart.AddSeconds(-1d * $prePaddingSteps * $StepSeconds)
$queryRangeEnd = $rangeEnd.AddSeconds($postPaddingSteps * $StepSeconds)
$waitMilliseconds = [math]::Ceiling(($queryRangeEnd - [DateTimeOffset]::Now).TotalMilliseconds)
if ($waitMilliseconds -gt $maxWaitMilliseconds) {
    throw "The padded Prometheus range ends at $($queryRangeEnd.ToString('o', $invariantCulture)), " +
        "which is more than $($maxWaitMilliseconds / 1000) seconds away. Wait and run the exporter again."
}
if ($waitMilliseconds -gt 0d) {
    Write-Host "Waiting $([math]::Round($waitMilliseconds / 1000d, 3)) seconds for the padded terminal scrape window."
    Start-Sleep -Milliseconds ([int]$waitMilliseconds)
}

Assert-TargetHealth

$metricKinds = [ordered]@{
    contest_scoreboard_pending_events = "gauge"
    contest_scoreboard_oldest_ready_seconds = "gauge"
    contest_scoreboard_applied_total = "counter"
}
$pipelineStartSeconds = [double]$rangeStart.ToUnixTimeMilliseconds() / 1000d
$pipelineEndSeconds = [double]$rangeEnd.ToUnixTimeMilliseconds() / 1000d
$rangeStartSeconds = [double]$queryRangeStart.ToUnixTimeMilliseconds() / 1000d
$rangeEndSeconds = [double]$queryRangeEnd.ToUnixTimeMilliseconds() / 1000d
$rawRows = @()
$summaryRows = @()

foreach ($metric in $metricKinds.Keys) {
    $rangeSeries = Get-RangeSeries `
        -Metric $metric `
        -StartSeconds $rangeStartSeconds `
        -EndSeconds $rangeEndSeconds `
        -RequiredStartSeconds $pipelineStartSeconds `
        -RequiredEndSeconds $pipelineEndSeconds
    $samples = @($rangeSeries.Samples)

    foreach ($sample in $samples) {
        $rawRows += [pscustomobject][ordered]@{
            phase = $Phase
            metric = $metric
            node = $rangeSeries.Node
            timestamp = Format-InvariantNumber $sample.Timestamp
            timestampUtc = ConvertTo-UtcTimestamp $sample.Timestamp
            value = Format-InvariantNumber $sample.Value
        }
    }

    $initial = [double]$samples[0].Value
    $final = [double]$samples[$samples.Count - 1].Value
    $delta = $final - $initial
    $peak = ""
    $resetDetected = ""
    $resetAdjustedDelta = ""

    if ($metricKinds[$metric] -eq "gauge") {
        $peak = Format-InvariantNumber ([double](($samples | Measure-Object -Property Value -Maximum).Maximum))
        if ($final -ne 0d) {
            throw "Prometheus range query for $metric ended at $(Format-InvariantNumber $final); " +
                "expected the terminal scoreboard gauge value 0 after the padded drain window."
        }
    }
    else {
        $reset = $false
        $adjusted = 0d
        for ($index = 1; $index -lt $samples.Count; $index++) {
            $change = [double]$samples[$index].Value - [double]$samples[$index - 1].Value
            if ($change -lt 0d) {
                $reset = $true
                $adjusted += [double]$samples[$index].Value
            }
            else {
                $adjusted += $change
            }
        }
        $resetDetected = $reset.ToString().ToLowerInvariant()
        $resetAdjustedDelta = Format-InvariantNumber $adjusted
    }

    $summaryRows += [pscustomobject][ordered]@{
        phase = $Phase
        metric = $metric
        node = $rangeSeries.Node
        kind = $metricKinds[$metric]
        samples = $samples.Count
        initial = Format-InvariantNumber $initial
        final = Format-InvariantNumber $final
        delta = Format-InvariantNumber $delta
        peak = $peak
        resetDetected = $resetDetected
        resetAdjustedDelta = $resetAdjustedDelta
        firstTimestampUtc = ConvertTo-UtcTimestamp $samples[0].Timestamp
        lastTimestampUtc = ConvertTo-UtcTimestamp $samples[$samples.Count - 1].Timestamp
        pipelineStartUtc = $rangeStart.UtcDateTime.ToString("o", $invariantCulture)
        pipelineEndUtc = $rangeEnd.UtcDateTime.ToString("o", $invariantCulture)
        queryStartUtc = $queryRangeStart.UtcDateTime.ToString("o", $invariantCulture)
        queryEndUtc = $queryRangeEnd.UtcDateTime.ToString("o", $invariantCulture)
    }
}

$rawCsv = Join-Path $resolvedOutputDirectory $rawCsvName
$summaryCsv = Join-Path $resolvedOutputDirectory $summaryCsvName
$rawRows | Sort-Object timestamp, metric | Export-Csv -LiteralPath $rawCsv -NoTypeInformation -Encoding utf8
$summaryRows | Export-Csv -LiteralPath $summaryCsv -NoTypeInformation -Encoding utf8

Write-Host "Exported scoreboard neutral metrics for phase '$Phase'."
Write-Host "pipeline $($rangeStart.ToString('o', $invariantCulture)) -> $($rangeEnd.ToString('o', $invariantCulture))"
Write-Host "query    $($queryRangeStart.ToString('o', $invariantCulture)) -> $($queryRangeEnd.ToString('o', $invariantCulture))"
Write-Host "raw    $rawCsv"
Write-Host "summary $summaryCsv"
