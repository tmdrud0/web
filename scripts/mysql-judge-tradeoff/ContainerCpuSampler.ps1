# Per-container CPU series for a run, read from the kernel's own cgroup counters.
#
# Dot-sourced by Run-TradeoffExperiment.ps1, and usable on its own by any later experiment:
#
#   . .\scripts\mysql-judge-tradeoff\ContainerCpuSampler.ps1
#   $sampler = Start-ContainerCpuSampler -OutputDirectory $dir -Containers @("oj-loadtest-mysql", "oj-loadtest-judge-1")
#   ... load ...
#   Stop-ContainerCpuSampler -Sampler $sampler     # writes container-cpu-1s.csv beside the raw log
#
# Why a helper container and not `docker stats`: `docker stats --no-stream` blocks for two samples
# per call (about 2s), so a 1s series from it would have to run several CLI processes in parallel on
# the machine under test, and its percentages are computed by the daemon over intervals the caller
# does not see. The helper below is one busybox shell in the Docker VM that reads each container's
# cgroup v2 `cpu.stat` once a second - a handful of small file reads - and prints the raw cumulative
# counters with the VM's monotonic clock. Rates are computed here, afterwards, from the actual
# interval between two readings, so a late tick lengthens its interval instead of distorting a rate.
#
# The helper has no network (`--network none`), mounts the host cgroup tree read-only, and runs with
# the host cgroup namespace only so it can see its siblings' counters. Its own CPU use is recorded in
# the same series under the name `cpu-sampler` so its cost is visible rather than assumed.
#
# Output files, all in -OutputDirectory:
#   container-cpu-meta.json  name -> id, configured CPU limit, cgroup path, clock anchor, sampler window
#   container-cpu-raw.txt    the helper's stdout, verbatim
#   container-cpu-1s.csv     one row per container per tick:
#       epochMillis     end of the interval, host UTC epoch via the VM clock (see the anchor below)
#       intervalMs      length of the interval the rate is averaged over
#       container       compose container name
#       cpuCores        usage_usec delta / interval: 1.0 = one CPU fully busy for the whole interval
#       cpuLimitCores   configured limit (NanoCpus), empty when unlimited
#       throttledMs     throttled_usec delta in ms: time the CFS quota held the cgroup back
#       nrThrottled     nr_throttled delta: CFS periods in which the quota was exhausted
#       counterReset    1 when usage went backwards (container restarted); the row's rate is then empty
#
# Clock: each tick reads /proc/uptime (10ms resolution, monotonic). Once at start the helper spins to a
# `date +%s` boundary and records the uptime there, which maps uptime onto epoch seconds to within a
# few milliseconds of the VM's wall clock. Docker Desktop keeps the VM clock synchronised with the
# Windows host; any residual offset is the same for every row in a run.

function Invoke-DockerCli {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFailure)
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & docker @Arguments 2>$stderrFile
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previous }
        if ($exitCode -ne 0 -and -not $AllowFailure) {
            $detail = (Get-Content $stderrFile -Raw -ErrorAction SilentlyContinue)
            throw "docker failed (exit $exitCode): $($Arguments -join ' ')`n$detail"
        }
        # Unrolled into the pipeline, one line per item; callers that need a count wrap the call in @().
        return $output
    } finally {
        Remove-Item $stderrFile -Force -ErrorAction SilentlyContinue
    }
}

function Start-ContainerCpuSampler {
    param(
        [Parameter(Mandatory = $true)][string]$OutputDirectory,
        [Parameter(Mandatory = $true)][string[]]$Containers,
        [string]$SamplerName = "",
        [string]$Image = "alpine:latest",
        [double]$IntervalSeconds = 1.0
    )
    if (-not $SamplerName) { $SamplerName = "cpu-sampler-" + [guid]::NewGuid().ToString("N").Substring(0, 12) }

    $format = '{{.Name}}|{{.Id}}|{{.HostConfig.NanoCpus}}'
    $lines = Invoke-DockerCli -Arguments (@("inspect", "--format", $format) + $Containers)
    $targets = New-Object System.Collections.Generic.List[object]
    foreach ($line in $lines) {
        $text = [string]$line
        if (-not $text.Trim()) { continue }
        $parts = $text.Trim().Split("|")
        if ($parts.Count -lt 3) { continue }
        $nano = 0L
        [void][long]::TryParse($parts[2], [ref]$nano)
        $targets.Add([pscustomobject]@{
            name = $parts[0].TrimStart("/")
            id = $parts[1]
            cpuLimitCores = if ($nano -gt 0) { [math]::Round($nano / 1e9, 3) } else { $null }
        })
    }
    if ($targets.Count -eq 0) { throw "None of the requested containers could be inspected: $($Containers -join ', ')" }

    # The helper resolves each id to its cgroup directory once (cgroupfs driver: /docker/<id>; systemd
    # driver: /system.slice/docker-<id>.scope), then reads every readable cpu.stat in one awk per tick.
    # A container that stops simply drops out of that tick's line; one that restarts comes back with a
    # new, smaller usage counter, which the converter marks as a reset rather than a negative rate.
    $script = @'
set -u
paths=""
for id in $IDS; do
  p="/cg/docker/$id/cpu.stat"
  [ -r "$p" ] || p="/cg/system.slice/docker-$id.scope/cpu.stat"
  echo "PATH $id $p"
  paths="$paths $p"
done
self=$(awk -F: '$1=="0"{print $3}' /proc/self/cgroup)
echo "SELF $self"
s0=$(date +%s)
while [ "$(date +%s)" = "$s0" ]; do :; done
read up rest < /proc/uptime
echo "ANCHOR $(date +%s) $up"
while :; do
  read up rest < /proc/uptime
  existing=""
  for p in $paths /cg$self/cpu.stat; do [ -r "$p" ] && existing="$existing $p"; done
  awk -v up="$up" '
    function flush() { if (f != "") line = line " " f "=" u ":" t ":" n }
    BEGIN { line = "T " up; f = "" }
    FNR == 1 { flush(); f = FILENAME; u = "NA"; t = "NA"; n = "NA" }
    $1 == "usage_usec" { u = $2 }
    $1 == "throttled_usec" { t = $2 }
    $1 == "nr_throttled" { n = $2 }
    END { flush(); print line }' $existing
  sleep INTERVAL
done
'@
    $script = $script.Replace("INTERVAL", ([string]$IntervalSeconds).Replace(",", "."))
    # Windows line endings would reach busybox as literal carriage returns.
    $script = $script -replace "`r", ""
    # Windows PowerShell 5.1 does not escape embedded double quotes when it builds a native command
    # line, so the script travels base64-encoded and is decoded inside the helper.
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))
    $ids = (@($targets | ForEach-Object { $_.id }) -join " ")

    Invoke-DockerCli -Arguments @("rm", "-f", $SamplerName) -AllowFailure | Out-Null
    $startedAt = [datetimeoffset]::UtcNow
    $idLine = Invoke-DockerCli -Arguments @("run", "-d", "--name", $SamplerName, "--network", "none",
        "--cgroupns=host", "-v", "/sys/fs/cgroup:/cg:ro", "-e", "IDS=$ids", $Image, "sh", "-c", "echo $encoded | base64 -d > /tmp/s.sh && exec sh /tmp/s.sh")
    $samplerId = ([string]($idLine | Where-Object { $_ } | Select-Object -Last 1)).Trim()

    $sampler = [pscustomobject]@{
        name = $SamplerName
        id = $samplerId
        image = $Image
        intervalSeconds = $IntervalSeconds
        outputDirectory = $OutputDirectory
        startedAt = $startedAt.ToString("o")
        stoppedAt = $null
        targets = $targets.ToArray()
    }
    return $sampler
}

function Stop-ContainerCpuSampler {
    param([Parameter(Mandatory = $true)]$Sampler)
    if ($null -eq $Sampler) { return $null }
    $rawPath = Join-Path $Sampler.outputDirectory "container-cpu-raw.txt"
    $raw = Invoke-DockerCli -Arguments @("logs", $Sampler.name) -AllowFailure
    $raw | Set-Content $rawPath -Encoding utf8
    Invoke-DockerCli -Arguments @("rm", "-f", $Sampler.name) -AllowFailure | Out-Null
    $Sampler.stoppedAt = [datetimeoffset]::UtcNow.ToString("o")
    return Convert-ContainerCpuRaw -Sampler $Sampler -RawPath $rawPath
}

function Convert-ContainerCpuRaw {
    param([Parameter(Mandatory = $true)]$Sampler, [Parameter(Mandatory = $true)][string]$RawPath)
    $outputDirectory = $Sampler.outputDirectory
    $nameById = @{}
    $limitByName = @{}
    foreach ($target in $Sampler.targets) {
        $nameById[$target.id] = $target.name
        $limitByName[$target.name] = $target.cpuLimitCores
    }
    $nameByPath = @{}
    $cgroupPathByName = [ordered]@{}
    $anchorEpoch = $null
    $anchorUptime = $null
    $ticks = New-Object System.Collections.Generic.List[object]
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    foreach ($line in @(Get-Content $RawPath)) {
        $text = ([string]$line).Trim()
        if (-not $text) { continue }
        $tokens = $text.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
        switch ($tokens[0]) {
            "PATH" {
                if ($tokens.Count -ge 3 -and $nameById.ContainsKey($tokens[1])) {
                    $nameByPath[$tokens[2]] = $nameById[$tokens[1]]
                    $cgroupPathByName[$nameById[$tokens[1]]] = $tokens[2]
                }
            }
            "SELF" {
                if ($tokens.Count -ge 2) {
                    $selfPath = "/cg$($tokens[1])/cpu.stat"
                    $nameByPath[$selfPath] = "cpu-sampler"
                    $cgroupPathByName["cpu-sampler"] = $selfPath
                }
            }
            "ANCHOR" {
                $anchorEpoch = [double]::Parse($tokens[1], $culture)
                $anchorUptime = [double]::Parse($tokens[2], $culture)
            }
            "T" {
                $uptime = [double]::Parse($tokens[1], $culture)
                $readings = @{}
                for ($i = 2; $i -lt $tokens.Count; $i++) {
                    $eq = $tokens[$i].LastIndexOf("=")
                    if ($eq -lt 0) { continue }
                    $path = $tokens[$i].Substring(0, $eq)
                    if (-not $nameByPath.ContainsKey($path)) { continue }
                    $values = $tokens[$i].Substring($eq + 1).Split(":")
                    $readings[$nameByPath[$path]] = $values
                }
                $ticks.Add([pscustomobject]@{ uptime = $uptime; readings = $readings })
            }
        }
    }

    $csvPath = Join-Path $outputDirectory "container-cpu-1s.csv"
    $rows = New-Object System.Collections.Generic.List[string]
    $rows.Add("epochMillis,intervalMs,container,cpuCores,cpuLimitCores,throttledMs,nrThrottled,counterReset")
    $previous = @{}
    $previousUptime = @{}
    $names = @($cgroupPathByName.Keys)
    if ($null -ne $anchorEpoch) {
        foreach ($tick in $ticks) {
            $epochMillis = [long][math]::Round(($anchorEpoch + ($tick.uptime - $anchorUptime)) * 1000.0)
            foreach ($name in $names) {
                if (-not $tick.readings.ContainsKey($name)) { continue }
                $values = $tick.readings[$name]
                $usage = 0.0; $throttled = 0.0; $periods = 0.0
                $haveUsage = [double]::TryParse($values[0], [System.Globalization.NumberStyles]::Float, $culture, [ref]$usage)
                $haveThrottled = $values.Count -ge 2 -and [double]::TryParse($values[1], [System.Globalization.NumberStyles]::Float, $culture, [ref]$throttled)
                $havePeriods = $values.Count -ge 3 -and [double]::TryParse($values[2], [System.Globalization.NumberStyles]::Float, $culture, [ref]$periods)
                if (-not $haveUsage) { continue }
                if ($previous.ContainsKey($name)) {
                    $last = $previous[$name]
                    $intervalMs = ($tick.uptime - $previousUptime[$name]) * 1000.0
                    $limit = if ($null -eq $limitByName[$name]) { "" } else { ([string]$limitByName[$name]).Replace(",", ".") }
                    if ($intervalMs -gt 0) {
                        if ($usage -lt $last.usage) {
                            $rows.Add("$epochMillis,$([math]::Round($intervalMs, 1).ToString($culture)),$name,,$limit,,,1")
                        } else {
                            $cores = ($usage - $last.usage) / 1000.0 / $intervalMs
                            $throttledMs = if ($haveThrottled -and $null -ne $last.throttled) { [math]::Round(($throttled - $last.throttled) / 1000.0, 3).ToString($culture) } else { "" }
                            $nr = if ($havePeriods -and $null -ne $last.periods) { [string]([long]($periods - $last.periods)) } else { "" }
                            $rows.Add("$epochMillis,$([math]::Round($intervalMs, 1).ToString($culture)),$name,$([math]::Round($cores, 4).ToString($culture)),$limit,$throttledMs,$nr,0")
                        }
                    }
                }
                $previous[$name] = [pscustomobject]@{
                    usage = $usage
                    throttled = if ($haveThrottled) { $throttled } else { $null }
                    periods = if ($havePeriods) { $periods } else { $null }
                }
                $previousUptime[$name] = $tick.uptime
            }
        }
    }
    [System.IO.File]::WriteAllLines($csvPath, $rows.ToArray())

    $meta = [ordered]@{
        sampler = $Sampler.name
        image = $Sampler.image
        intervalSeconds = $Sampler.intervalSeconds
        startedAt = $Sampler.startedAt
        stoppedAt = $Sampler.stoppedAt
        clockAnchor = [ordered]@{ epochSeconds = $anchorEpoch; uptimeSeconds = $anchorUptime
            basis = "VM monotonic uptime mapped to epoch at a date +%s boundary observed by the helper; Docker Desktop keeps the VM clock synchronised with the host" }
        ticks = $ticks.Count
        rows = $rows.Count - 1
        containers = @($Sampler.targets | ForEach-Object { [ordered]@{ name = $_.name; id = $_.id; cpuLimitCores = $_.cpuLimitCores; cgroupStat = $cgroupPathByName[$_.name] } })
        samplerSelfCgroupStat = $cgroupPathByName["cpu-sampler"]
        units = "cpuCores = usage_usec delta / interval (1.0 = one CPU busy for the whole interval); throttledMs and nrThrottled are CFS quota deltas over the same interval"
    }
    $meta | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outputDirectory "container-cpu-meta.json") -Encoding utf8
    if ($null -eq $anchorEpoch) { Write-Warning "container CPU sampler recorded no clock anchor; container-cpu-1s.csv has no rows" }
    return $csvPath
}

# Mean CPU per container over [StartMillis, EndMillis): the time-weighted mean of the rows whose
# interval ends inside the window. Returns an ordered map container -> stats, or $null when the run
# has no CPU series.
function Get-ContainerCpuWindow {
    param(
        [Parameter(Mandatory = $true)][object[]]$Rows,
        [Parameter(Mandatory = $true)][long]$StartMillis,
        [Parameter(Mandatory = $true)][long]$EndMillis
    )
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    $result = [ordered]@{}
    $inWindow = @($Rows | Where-Object { [long]$_.epochMillis -ge $StartMillis -and [long]$_.epochMillis -lt $EndMillis -and $_.cpuCores -ne "" })
    foreach ($group in @($inWindow | Group-Object container)) {
        $weighted = 0.0; $total = 0.0; $max = 0.0; $throttledMs = 0.0; $nr = 0L
        foreach ($row in $group.Group) {
            $interval = [double]::Parse($row.intervalMs, $culture)
            $cores = [double]::Parse($row.cpuCores, $culture)
            $weighted += $cores * $interval
            $total += $interval
            if ($cores -gt $max) { $max = $cores }
            if ($row.throttledMs -ne "") { $throttledMs += [double]::Parse($row.throttledMs, $culture) }
            if ($row.nrThrottled -ne "") { $nr += [long]$row.nrThrottled }
        }
        $limit = @($group.Group | Select-Object -First 1)[0].cpuLimitCores
        $mean = if ($total -gt 0) { $weighted / $total } else { $null }
        $result[$group.Name] = [ordered]@{
            meanCores = if ($null -eq $mean) { $null } else { [math]::Round($mean, 4) }
            maxCores = [math]::Round($max, 4)
            cpuLimitCores = if ($limit -eq "") { $null } else { [double]::Parse($limit, $culture) }
            meanOfLimitPercent = if ($null -ne $mean -and $limit -ne "") { [math]::Round(100.0 * $mean / [double]::Parse($limit, $culture), 2) } else { $null }
            throttledMsPerSecond = if ($total -gt 0) { [math]::Round($throttledMs / ($total / 1000.0), 3) } else { $null }
            throttledPeriods = $nr
            samples = $group.Count
            coveredSeconds = [math]::Round($total / 1000.0, 3)
        }
    }
    return $result
}
