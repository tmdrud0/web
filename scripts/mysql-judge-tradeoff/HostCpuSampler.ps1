# Host-wide CPU, once a second, without touching Docker.
#
# The container series (ContainerCpuSampler.ps1) says what each container used; it cannot say whether the
# machine as a whole was saturated - the load generator, the Docker VM's own overhead and everything else
# on the host are outside it. This reads Windows' raw processor counter for _Total
# (Win32_PerfRawData_PerfOS_Processor) and differentiates it: busy% = 100 * (1 - d(idle ticks) / d(time)).
# The raw counter is used rather than the formatted one because a formatted WMI value is computed over an
# interval WMI chooses, not over the second this series claims.
#
# It runs as a background job that writes host-cpu-1s.csv until a stop file appears, so the harness's own
# loop never blocks on it.

function Start-HostCpuSampler {
    param(
        [Parameter(Mandatory = $true)][string]$OutputDirectory,
        [int]$MaxSeconds = 7200
    )
    $csv = Join-Path $OutputDirectory "host-cpu-1s.csv"
    $stop = Join-Path $OutputDirectory "host-cpu.stop"
    Remove-Item -LiteralPath $stop -Force -ErrorAction SilentlyContinue
    $job = Start-Job -ArgumentList $csv, $stop, $MaxSeconds -ScriptBlock {
        param($Csv, $Stop, $MaxSeconds)
        $ErrorActionPreference = "Stop"
        $logical = [Environment]::ProcessorCount
        "timestamp,epochMillis,hostBusyPercent,hostBusyCores,logicalProcessors" | Set-Content -LiteralPath $Csv -Encoding utf8
        $previous = $null
        $deadline = (Get-Date).AddSeconds($MaxSeconds)
        $next = [datetimeoffset]::UtcNow
        while (-not (Test-Path -LiteralPath $Stop) -and (Get-Date) -lt $deadline) {
            try {
                $raw = Get-CimInstance -ClassName Win32_PerfRawData_PerfOS_Processor -Filter "Name='_Total'"
                $now = [datetimeoffset]::UtcNow
                $sample = @{ idle = [double]$raw.PercentProcessorTime; time = [double]$raw.Timestamp_Sys100NS }
                if ($null -ne $previous -and $sample.time -gt $previous.time) {
                    $busy = 100.0 * (1.0 - ($sample.idle - $previous.idle) / ($sample.time - $previous.time))
                    $busy = [math]::Max(0.0, [math]::Min(100.0, $busy))
                    $line = "{0},{1},{2},{3},{4}" -f $now.ToString("o"), $now.ToUnixTimeMilliseconds(),
                        [math]::Round($busy, 2).ToString([Globalization.CultureInfo]::InvariantCulture),
                        [math]::Round($busy / 100.0 * $logical, 3).ToString([Globalization.CultureInfo]::InvariantCulture), $logical
                    Add-Content -LiteralPath $Csv -Value $line -Encoding utf8
                }
                $previous = $sample
            } catch {
                Add-Content -LiteralPath ($Csv + ".errors.log") -Value ("{0} {1}" -f [datetimeoffset]::UtcNow.ToString("o"), $_) -Encoding utf8
            }
            $next = $next.AddSeconds(1)
            $wait = ($next - [datetimeoffset]::UtcNow).TotalMilliseconds
            if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) } else { $next = [datetimeoffset]::UtcNow }
        }
    }
    return [pscustomobject]@{ job = $job; csv = $csv; stopFile = $stop; startedAt = [datetimeoffset]::UtcNow.ToString("o") }
}

function Stop-HostCpuSampler {
    param([Parameter(Mandatory = $true)]$Sampler)
    if ($null -eq $Sampler) { return $null }
    try {
        Set-Content -LiteralPath $Sampler.stopFile -Value "stop" -Encoding utf8
        if (-not (Wait-Job -Job $Sampler.job -Timeout 10)) { Stop-Job -Job $Sampler.job -ErrorAction SilentlyContinue }
        Receive-Job -Job $Sampler.job -ErrorAction SilentlyContinue | Out-Null
    } finally {
        Remove-Job -Job $Sampler.job -Force -ErrorAction SilentlyContinue
    }
    return $Sampler.csv
}
