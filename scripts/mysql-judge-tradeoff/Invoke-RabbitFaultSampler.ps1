[CmdletBinding()]
<#
Broker-side and container-side observation for a RabbitMQ judge fault run.

This runs as its own process, started by Run-TradeoffExperiment.ps1 with Start-Job and stopped by a
stop file. It is separate for the same reason the staircase's sampler is separate: the fault loop is
deadline-first, and the two seconds before a restart belong to the clock. A broker round trip inside
those two seconds would move the deadline it is measured against, so the observation cannot live in
that loop.

Everything it writes is raw observation. It decides nothing: whether the consumers dropping from 32
to 16 was the kill, and whether a redelivery is the killed node's unacked return, are questions the
analyzer answers against events.json. A sampler that drew those conclusions would be a sampler whose
mistake is invisible in its own output.

The counters come from the management HTTP API rather than from rabbitmqctl because RabbitMQ 4.1
rejects `list_queues` with message_stats keys, and publish/deliver/ack/redeliver are exactly what
this experiment needs. A counter that could not be read is written as an empty cell, never as 0, and
the reason travels with it in the same row.

Run directly (it stops on its own stop file, or on -MaxSeconds):
  powershell -ExecutionPolicy Bypass -File Invoke-RabbitFaultSampler.ps1 -RunDirectory <dir> `
      -StopFile <file> -ManagementUrl http://127.0.0.1:15672 -User oj -Password oj-password

Or with the whole configuration as one positional JSON document, which is how the runner starts it:
  Start-Job -FilePath Invoke-RabbitFaultSampler.ps1 -ArgumentList $configJson

The JSON form exists because Start-Job -FilePath binds -ArgumentList positionally: ten separate
arguments would silently mis-bind the day either side's parameter order changed, and the failure
would look like a sampler reading the wrong queue rather than like a binding error. The named
parameters remain the primary form; the JSON is a second front door onto exactly the same values.
#>
param(
    # The single-argument form. Bound by position so the runner's one JSON argument lands here rather
    # than on whatever happens to be parameter zero.
    [Parameter(Position = 0)][string]$ConfigJson,
    # Required in the named form, and required after the JSON is merged in the positional form; the
    # check is below rather than in a Mandatory attribute because a Mandatory parameter cannot be
    # supplied by the JSON that arrives in the same invocation.
    [string]$RunDirectory,
    # The file whose appearance ends the sampler. The runner creates it after the drain, so the
    # sampler's lifetime is the run's lifetime rather than a guess at how long the run will take.
    [string]$StopFile,
    [string]$ManagementUrl = "http://127.0.0.1:15672",
    [string]$User = "oj",
    [string]$Password = "oj-password",
    [string]$LiveQueue = "contest.judge.live",
    [string]$DeadQueue = "contest.judge.dead",
    # The queue tick. The request allows 200-500ms; the floor is what the fault's own resolution
    # needs - a 15s outage sampled at 1s has 15 points in it, which is not enough to place a
    # consumer drop and a first redelivery inside the outage.
    [int]$IntervalMilliseconds = 250,
    # The connection/channel and container tick. Both are cheap to read but expensive to read four
    # times a second, and neither changes on a 250ms clock: a consumer appearing is already visible
    # in the queue's own `consumers` count, which is sampled fast.
    [int]$SlowIntervalMilliseconds = 1000,
    [string[]]$Containers = @(),
    # A backstop only. The stop file is the real termination, so a run that dies without writing it
    # leaves a sampler that must not outlive the stack it is watching.
    [int]$MaxSeconds = 3600
)
$ErrorActionPreference = "Continue"

if (-not [string]::IsNullOrWhiteSpace($ConfigJson)) {
    # Only the keys this sampler defines are read, and a key the JSON does not carry leaves the
    # parameter's own default in place - so a configuration that omits MaxSeconds gets the default
    # rather than a zero-second lifetime.
    $config = $null
    try {
        $config = $ConfigJson | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "the configuration JSON could not be parsed, so no observation settings were applied: $_"
    }
    if ($null -ne $config.RunDirectory) { $RunDirectory = [string]$config.RunDirectory }
    if ($null -ne $config.StopFile) { $StopFile = [string]$config.StopFile }
    if ($null -ne $config.ManagementUrl) { $ManagementUrl = [string]$config.ManagementUrl }
    if ($null -ne $config.User) { $User = [string]$config.User }
    if ($null -ne $config.Password) { $Password = [string]$config.Password }
    if ($null -ne $config.LiveQueue) { $LiveQueue = [string]$config.LiveQueue }
    if ($null -ne $config.DeadQueue) { $DeadQueue = [string]$config.DeadQueue }
    if ($null -ne $config.IntervalMilliseconds) { $IntervalMilliseconds = [int]$config.IntervalMilliseconds }
    if ($null -ne $config.SlowIntervalMilliseconds) { $SlowIntervalMilliseconds = [int]$config.SlowIntervalMilliseconds }
    if ($null -ne $config.Containers) { $Containers = @($config.Containers) }
    if ($null -ne $config.MaxSeconds) { $MaxSeconds = [int]$config.MaxSeconds }
}
if ([string]::IsNullOrWhiteSpace($RunDirectory) -or [string]::IsNullOrWhiteSpace($StopFile)) {
    throw "RunDirectory and StopFile are both required, whether they are passed by name or carried in -ConfigJson"
}

$script:queuePath = Join-Path $RunDirectory "rabbit-queue-samples.csv"
$script:rawPath = Join-Path $RunDirectory "rabbit-queue-raw.jsonl"
$script:containerPath = Join-Path $RunDirectory "container-watch.csv"
$script:errorPath = Join-Path $RunDirectory "sampler-errors.log"
$script:phasePath = Join-Path $RunDirectory "sampler-phase.txt"
$script:authHeader = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$User`:$Password")) }
$script:queueColumns = "timestamp,epochMillis,phase,tickMs,ready,unacked,readyPlusUnacked,consumers," +
    "connections,channels,publish,deliver,ack,redeliver,deadReady,deadUnacked,deadLetters,deadConsumers,error"

function Write-SamplerError {
    param([string]$Message)
    try {
        Add-Content -Path $script:errorPath -Value "$([datetimeoffset]::UtcNow.ToString('o')) $Message" -Encoding utf8
    } catch { }
}

function Get-PhaseLabel {
    # The runner writes a one-line phase marker as it moves between phases. Reading it here rather
    # than being told over a channel keeps the two processes decoupled: a phase the runner does not
    # announce is recorded as "unknown" instead of silently stamping the previous phase's name on
    # samples that belong to the next one.
    try {
        if (-not (Test-Path $script:phasePath)) { return "unknown" }
        $text = (Get-Content -Path $script:phasePath -Raw -ErrorAction Stop).Trim()
        if ([string]::IsNullOrWhiteSpace($text)) { return "unknown" }
        return $text
    } catch { return "unknown" }
}

function Get-JsonPayload {
    <#
    One management API read. Returns the parsed body plus the raw text, or a failure record. A 404 is
    kept apart from an unreachable API because they mean different things: the dead-letter queue not
    existing is a finding about the run (nothing was dead-lettered and nothing declared it), while an
    unreachable API is a finding about this sampler.
    #>
    param([Parameter(Mandatory = $true)][string]$Uri)
    $result = [pscustomobject]@{ ok = $false; status = $null; body = $null; text = $null; error = $null }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri $Uri -Headers $script:authHeader -ErrorAction Stop
        $result.text = $response.Content
        $result.body = $response.Content | ConvertFrom-Json
        $result.ok = $true
    } catch {
        $status = $null
        try { if ($null -ne $_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode } } catch { }
        $result.status = $status
        $result.error = $_.Exception.Message
    }
    return $result
}

function Get-Number {
    <#
    A numeric field, or null. The distinction is the whole point: Micrometer and the management API
    both omit a counter that has never moved, and a missing counter read as 0 is a claim that nothing
    happened rather than a claim that nothing was recorded.
    #>
    param($Object, [Parameter(Mandatory = $true)][string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $null }
    $value = 0.0
    if (-not [double]::TryParse([string]$property.Value, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { return $null }
    return $value
}

function Get-Nested {
    <#
    A nested object, or null. The broker omits both `message_stats` and `object_totals` from a
    payload until something has moved, so every read of them goes through here rather than through
    a chain of property accesses whose failure mode is a silent null that reads like a zero.
    #>
    param($Object, [Parameter(Mandatory = $true)][string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-MessageStat {
    param($QueueBody, [Parameter(Mandatory = $true)][string]$Name)
    return Get-Number -Object (Get-Nested -Object $QueueBody -Name "message_stats") -Name $Name
}

function Get-ObjectTotal {
    param($OverviewBody, [Parameter(Mandatory = $true)][string]$Name)
    return Get-Number -Object (Get-Nested -Object $OverviewBody -Name "object_totals") -Name $Name
}

function Format-Cell {
    param($Value)
    if ($null -eq $Value) { return "" }
    return [string]$Value
}

function Get-ContainerRows {
    <#
    One docker inspect for every container. The state, the restart count, the OOM flag and the exit
    code are what separate an intentionally killed judge-1 from an abnormal termination of anything
    else - and they are read here rather than inferred from the run's own timeline, because the
    timeline is what is being checked.

    The IP is read in the same call so a broker connection's peer_host can be attributed to a
    container. It is refreshed every slow tick rather than once at the start: a restarted container
    can come back on a different address, and an attribution map captured before the restart would
    silently point the killed node's redelivery at the replacement.
    #>
    if ($Containers.Count -eq 0) { return @() }
    $format = "{{.Name}}|{{.State.Status}}|{{.RestartCount}}|{{.State.OOMKilled}}|{{.State.ExitCode}}|{{.State.StartedAt}}|{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}"
    $arguments = @("inspect", "--format", $format) + $Containers
    try {
        $lines = @(& docker @arguments 2>&1)
    } catch {
        Write-SamplerError "docker inspect failed: $_"
        return @()
    }
    $rows = @()
    foreach ($line in $lines) {
        $text = [string]$line
        if ($text -notmatch "\|") { continue }
        $parts = $text.Split("|")
        if ($parts.Count -lt 7) { continue }
        $rows += [pscustomobject]@{
            container = $parts[0].TrimStart("/")
            state = $parts[1]
            restartCount = $parts[2]
            oomKilled = $parts[3]
            exitCode = $parts[4]
            startedAt = $parts[5]
            ip = $parts[6].Trim()
        }
    }
    # A header-only container-watch.csv is the failure mode worth shouting about, because it looks
    # like a quiet run: nothing crashed, so nothing was written. The request makes this file
    # mandatory, so the reason it has no rows has to be in the log rather than inferred from an empty
    # file. Logged on the transition only - a per-second repeat would bury every other line.
    if ($rows.Count -eq 0) {
        if (-not $script:containerRowsMissingLogged) {
            $first = ($lines | Select-Object -First 1)
            Write-SamplerError "docker inspect returned no state row for any of the $($Containers.Count) requested containers, so container-watch.csv has no data rows. First output line: $first"
            $script:containerRowsMissingLogged = $true
        }
    } else {
        $script:containerRowsMissingLogged = $false
    }
    return $rows
}

function Get-ChannelRows {
    <#
    Per-channel unacknowledged counts, which is the only per-node reading the broker offers: the
    judge's own actuator gauges are mysql-only, so "does judge-1 hold work" has to be answered from
    the broker side. The peer host travels with each row so the analyzer can attribute a channel to a
    container instead of assuming the count belongs to whoever is left.
    #>
    param($ChannelsBody, $ContainerRows)
    $rows = @()
    if ($null -eq $ChannelsBody) { return $rows }
    $byIp = @{}
    foreach ($entry in $ContainerRows) {
        if (-not [string]::IsNullOrWhiteSpace($entry.ip)) { $byIp[$entry.ip] = $entry.container }
    }
    foreach ($channel in @($ChannelsBody)) {
        $peerHost = $null
        try { $peerHost = $channel.connection_details.peer_host } catch { }
        $container = $null
        if (-not [string]::IsNullOrWhiteSpace($peerHost)) {
            $hostOnly = ([string]$peerHost).Split(":")[0]
            if ($byIp.ContainsKey($hostOnly)) { $container = $byIp[$hostOnly] }
        }
        $rows += [pscustomobject]@{
            name = $channel.name
            peerHost = $peerHost
            container = $container
            unacked = Get-Number -Object $channel -Name "messages_unacknowledged"
            prefetchCount = Get-Number -Object $channel -Name "prefetch_count"
            consumerCount = Get-Number -Object $channel -Name "consumer_count"
            ack = Get-Number -Object $channel -Name "ack"
            deliver = Get-Number -Object $channel -Name "deliver"
            redeliver = Get-Number -Object $channel -Name "redeliver"
            confirm = Get-Number -Object $channel -Name "confirm"
        }
    }
    return $rows
}

function Write-QueueRow {
    param($Live, $Dead, $Overview, [string]$Phase, [double]$TickMs, [string]$Error)
    if (-not (Test-Path $script:queuePath)) {
        Set-Content -Path $script:queuePath -Value $script:queueColumns -Encoding utf8
    }
    $liveBody = if ($Live.ok) { $Live.body } else { $null }
    $deadBody = if ($Dead.ok) { $Dead.body } else { $null }
    $overviewBody = if ($Overview.ok) { $Overview.body } else { $null }
    $ready = Get-Number -Object $liveBody -Name "messages_ready"
    $unacked = Get-Number -Object $liveBody -Name "messages_unacknowledged"
    $readyPlusUnacked = $null
    if ($null -ne $ready -and $null -ne $unacked) { $readyPlusUnacked = $ready + $unacked }
    $deadReady = Get-Number -Object $deadBody -Name "messages_ready"
    $deadUnacked = Get-Number -Object $deadBody -Name "messages_unacknowledged"
    $deadLetters = $null
    if ($null -ne $deadReady -and $null -ne $deadUnacked) { $deadLetters = $deadReady + $deadUnacked }
    $row = "$([datetimeoffset]::UtcNow.ToString('o')),$([datetimeoffset]::UtcNow.ToUnixTimeMilliseconds()),$Phase," +
        "$([math]::Round($TickMs, 1))," +
        "$(Format-Cell $ready),$(Format-Cell $unacked),$(Format-Cell $readyPlusUnacked)," +
        "$(Format-Cell (Get-Number -Object $liveBody -Name 'consumers'))," +
        "$(Format-Cell (Get-ObjectTotal $overviewBody 'connections'))," +
        "$(Format-Cell (Get-ObjectTotal $overviewBody 'channels'))," +
        "$(Format-Cell (Get-MessageStat $liveBody 'publish'))," +
        "$(Format-Cell (Get-MessageStat $liveBody 'deliver'))," +
        "$(Format-Cell (Get-MessageStat $liveBody 'ack'))," +
        "$(Format-Cell (Get-MessageStat $liveBody 'redeliver'))," +
        "$(Format-Cell $deadReady),$(Format-Cell $deadUnacked),$(Format-Cell $deadLetters)," +
        "$(Format-Cell (Get-Number -Object $deadBody -Name 'consumers'))," +
        "$($Error -replace ',', ';')"
    try {
        Add-Content -Path $script:queuePath -Value $row -Encoding utf8 -ErrorAction Stop
    } catch {
        Write-SamplerError "queue row append failed: $_"
    }
}

function Write-RawTick {
    <#
    The raw payload, projected to the fields this experiment reads and no further. Kept whole rather
    than summarised so a reader who disagrees with the analyzer's attribution has the broker's own
    answer to check it against - and projected rather than dumped because the live queue's
    consumer_details repeats the same channel list in every 250ms tick.
    #>
    param($Live, $Dead, $Overview, $Channels, $ContainerRows, [string]$Phase, [bool]$SlowTick)
    $document = [ordered]@{
        at = [datetimeoffset]::UtcNow.ToString("o")
        phase = $Phase
        slowTick = $SlowTick
        live = [ordered]@{
            ok = $Live.ok; status = $Live.status; error = $Live.error
            messagesReady = Get-Number -Object $(if ($Live.ok) { $Live.body } else { $null }) -Name "messages_ready"
            messagesUnacknowledged = Get-Number -Object $(if ($Live.ok) { $Live.body } else { $null }) -Name "messages_unacknowledged"
            consumers = Get-Number -Object $(if ($Live.ok) { $Live.body } else { $null }) -Name "consumers"
            publish = Get-MessageStat $(if ($Live.ok) { $Live.body } else { $null }) "publish"
            deliver = Get-MessageStat $(if ($Live.ok) { $Live.body } else { $null }) "deliver"
            ack = Get-MessageStat $(if ($Live.ok) { $Live.body } else { $null }) "ack"
            redeliver = Get-MessageStat $(if ($Live.ok) { $Live.body } else { $null }) "redeliver"
        }
        dead = [ordered]@{
            ok = $Dead.ok; status = $Dead.status; error = $Dead.error
            messagesReady = Get-Number -Object $(if ($Dead.ok) { $Dead.body } else { $null }) -Name "messages_ready"
            messagesUnacknowledged = Get-Number -Object $(if ($Dead.ok) { $Dead.body } else { $null }) -Name "messages_unacknowledged"
            consumers = Get-Number -Object $(if ($Dead.ok) { $Dead.body } else { $null }) -Name "consumers"
        }
        overview = [ordered]@{
            ok = $Overview.ok; status = $Overview.status; error = $Overview.error
            connections = Get-ObjectTotal $Overview.body "connections"
            channels = Get-ObjectTotal $Overview.body "channels"
        }
    }
    if ($SlowTick) {
        $document.channels = @($Channels)
        $document.containers = @($ContainerRows | ForEach-Object {
            [ordered]@{ container = $_.container; ip = $_.ip; state = $_.state; restartCount = $_.restartCount }
        })
    }
    try {
        Add-Content -Path $script:rawPath -Value ($document | ConvertTo-Json -Depth 6 -Compress) -Encoding utf8 -ErrorAction Stop
    } catch {
        Write-SamplerError "raw tick append failed: $_"
    }
}

function Write-ContainerRows {
    param($ContainerRows, [string]$Phase)
    if (-not (Test-Path $script:containerPath)) {
        Set-Content -Path $script:containerPath -Value "timestamp,epochMillis,phase,container,state,restartCount,oomKilled,exitCode,startedAt,ip" -Encoding utf8
    }
    $stamp = [datetimeoffset]::UtcNow
    foreach ($entry in $ContainerRows) {
        $row = "$($stamp.ToString('o')),$($stamp.ToUnixTimeMilliseconds()),$Phase,$($entry.container)," +
            "$($entry.state),$($entry.restartCount),$($entry.oomKilled),$($entry.exitCode),$($entry.startedAt),$($entry.ip)"
        try {
            Add-Content -Path $script:containerPath -Value $row -Encoding utf8 -ErrorAction Stop
        } catch {
            Write-SamplerError "container row append failed: $_"
        }
    }
}

$startedAt = [datetimeoffset]::UtcNow
$nextQueueTick = $startedAt
$nextSlowTick = $startedAt
$tickCount = 0
$slowTickCount = 0
$lastError = ""

while ($true) {
    if (Test-Path $StopFile) { break }
    if (([datetimeoffset]::UtcNow - $startedAt).TotalSeconds -ge $MaxSeconds) {
        Write-SamplerError "sampler reached -MaxSeconds $MaxSeconds without a stop file; exiting so it cannot outlive the stack"
        break
    }

    $now = [datetimeoffset]::UtcNow
    if ($now -lt $nextQueueTick) {
        Start-Sleep -Milliseconds ([int][math]::Max(1, [math]::Min(100, ($nextQueueTick - $now).TotalMilliseconds)))
        continue
    }

    $tickStart = [datetimeoffset]::UtcNow
    $phase = Get-PhaseLabel
    $slowTick = ($now -ge $nextSlowTick)
    # Not $error: that name is PowerShell's own automatic error collection, and assigning to it
    # throws rather than recording anything - which would fail every tick with a message about the
    # variable rather than about the broker.
    $tickError = ""
    try {
        $live = Get-JsonPayload -Uri "$ManagementUrl/api/queues/%2F/$LiveQueue"
        $dead = Get-JsonPayload -Uri "$ManagementUrl/api/queues/%2F/$DeadQueue"
        $overview = Get-JsonPayload -Uri "$ManagementUrl/api/overview"
        $channels = @()
        $containerRows = @()
        if ($slowTick) {
            $containerRows = @(Get-ContainerRows)
            # read after the containers so a channel's peer host has an address to be matched against
            $channels = @(Get-ChannelRows -ChannelsBody (Get-JsonPayload -Uri "$ManagementUrl/api/channels").body -ContainerRows $containerRows)
        }
        if (-not $live.ok) { $tickError = "live queue read failed: $($live.error)" }
        elseif (-not $overview.ok) { $tickError = "overview read failed: $($overview.error)" }
        if ($tickError -ne $lastError) { Write-SamplerError $tickError; $lastError = $tickError }
        $tickMs = ([datetimeoffset]::UtcNow - $tickStart).TotalMilliseconds
        Write-QueueRow -Live $live -Dead $dead -Overview $overview -Phase $phase -TickMs $tickMs -Error $tickError
        Write-RawTick -Live $live -Dead $dead -Overview $overview -Channels $channels -ContainerRows $containerRows -Phase $phase -SlowTick $slowTick
        if ($slowTick) {
            Write-ContainerRows -ContainerRows $containerRows -Phase $phase
            $slowTickCount++
            $nextSlowTick = $now.AddMilliseconds($SlowIntervalMilliseconds)
        }
        $tickCount++
    } catch {
        # A tick that threw must not end the series: the ticks either side of it are the evidence, and
        # a sampler that dies on one bad read leaves a hole where the fault's own window is.
        Write-SamplerError "tick failed: $_"
        Write-QueueRow -Live ([pscustomobject]@{ ok = $false; status = $null; body = $null; error = "$_" }) `
            -Dead ([pscustomobject]@{ ok = $false; status = $null; body = $null; error = "$_" }) `
            -Overview ([pscustomobject]@{ ok = $false; status = $null; body = $null; error = "$_" }) `
            -Phase $phase -TickMs (([datetimeoffset]::UtcNow - $tickStart).TotalMilliseconds) -Error "tick failed: $_"
    }

    # A tick that overran its interval resumes at the next interval boundary rather than firing a
    # burst of catch-up reads: the series is a clock, and a burst would put four samples at one
    # instant and then a hole where the outage is.
    $nextQueueTick = $now.AddMilliseconds($IntervalMilliseconds)
    if (([datetimeoffset]::UtcNow - $nextQueueTick).TotalMilliseconds -gt $IntervalMilliseconds) {
        $nextQueueTick = [datetimeoffset]::UtcNow
    }
}

try {
    Add-Content -Path $script:errorPath -Value "$([datetimeoffset]::UtcNow.ToString('o')) sampler stopped after $tickCount queue ticks and $slowTickCount slow ticks" -Encoding utf8
} catch { }
