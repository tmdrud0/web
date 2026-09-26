# A light, independent read load against the scoreboard API, for workloads (W2 of report3-aof.md)
# whose submission scenario carries no scoreboard reads of its own
# (gatling/src/gatling/scala/my/oj/perf/ContestSubmissionSimulation.scala only logs in, jitters and
# submits - see its `feed`/`exec` chain). Single-threaded and best-effort paced, not a load-test tool
# in its own right: it exists to put a measurable, recorded ranking-read rate alongside a submission
# workload, not to stress the read path.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-scoreboard-query-load.ps1 `
#     -BaseUrl http://localhost:18080 -ContestId 1 -Rps 50 -DurationSeconds 300 -OutputCsv out.csv

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:18080",
    [Parameter(Mandatory = $true)][long]$ContestId,
    [double]$Rps = 50,
    [int]$DurationSeconds = 300,
    [Parameter(Mandatory = $true)][string]$OutputCsv,
    [int]$Size = 50
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$uri = "$BaseUrl/api/contests/$ContestId/scoreboard?startRank=1&size=$Size"
$intervalMs = 1000.0 / $Rps
"epochMs,elapsedMs,statusCode,error" | Set-Content -LiteralPath $OutputCsv -Encoding utf8

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($DurationSeconds)
$sent = 0
$nextAt = [DateTimeOffset]::UtcNow
while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $now = [DateTimeOffset]::UtcNow
    if ($now -lt $nextAt) {
        $sleepMs = [int]($nextAt - $now).TotalMilliseconds
        if ($sleepMs -gt 0) { Start-Sleep -Milliseconds $sleepMs }
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $statusCode = -1
    $errorText = ""
    try {
        $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 10
        $statusCode = [int]$response.StatusCode
    }
    catch {
        $errorText = ($_.Exception.Message -replace ',', ';').Trim()
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
    }
    $sw.Stop()
    "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()),$($sw.Elapsed.TotalMilliseconds),$statusCode,$errorText" |
        Add-Content -LiteralPath $OutputCsv -Encoding utf8
    $sent++
    $nextAt = $nextAt.AddMilliseconds($intervalMs)
}
Write-Output "scoreboard query load: sent $sent request(s) targeting ${Rps}/s for ${DurationSeconds}s -> $OutputCsv"
