# Shared primitives for the scoreboard recovery pilot.
#
# Every other module in this directory reads the state Initialize-RecoveryExperiment writes into the
# script scope, so this file has to be dot-sourced before them. RecoveryExperiment.ps1 does that for
# all of them, in order, so a runner only ever sources one file.
#
# Three things here are deliberately unlike their counterparts in run-scoreboard-rdb-recovery.ps1,
# and each is a consequence of the conditions this experiment fixed rather than a preference:
#
#   * SQL goes to the MySQL instance the project is already configured against, reached through the
#     container that instance runs in, rather than through a `mysql` service this stack would own.
#     The pilot overlay creates no such container, because the experiment was required to reuse the
#     configured instance.
#   * Redis commands go through `docker exec` on the pilot's own container rather than through
#     `docker compose exec`. The rollback injector has to place files in that container's filesystem
#     and read them back, and `docker cp` addresses a container, not a service.
#   * Every SQL statement that touches a table the experiment shares carries the run's seed prefix in
#     its own text, so a cleanup cannot be widened by accident and a SELECT COUNT of the target set
#     can always be run before the DELETE.
#
# run-scoreboard-rdb-recovery.ps1 itself is not modified or sourced: it assumes `oj-loadtest-mysql`
# is in the stack, which is exactly the assumption this experiment's MySQL decision removes.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:recoveryInvariantCulture = [Globalization.CultureInfo]::InvariantCulture

# Declared, so that a run which fails before `Initialize-RecoveryExperiment` gets as far as setting it can
# still be described by the code that reports the failure. Reading an undeclared variable is an error
# under StrictMode, and the cleanup paths are exactly where that is least affordable: the first
# calibration attempt lost the real error behind a second one raised by its own failure handler.
$script:recoveryConfig = $null

# The one namespace every rollback and every key census is scoped to. Confirmed single-valued in the
# product: ContestScoreboardRedisKeys.PREFIX is `contest:scoreboard:` and no other key family in the
# scoreboard write path leaves it.
$script:recoveryScoreboardKeyPattern = "contest:scoreboard:*"
$script:recoveryScoreboardKeyPrefix = "contest:scoreboard:"

# Services the pilot stack must have, by Compose service name, mapped to the exact container name the
# merged overlay produces. Counted and asserted as a set rather than by `docker ps | grep`, so a
# service that quietly failed to start is a stopped run instead of a thinner measurement.
function Get-ExpectedPilotContainers {
    return [ordered]@{
        nginx = "oj-loadtest-nginx"
        redis = "oj-loadtest-redis"
        rabbitmq = "oj-loadtest-rabbitmq"
        "web-1" = "oj-loadtest-web-1"
        "web-2" = "oj-loadtest-web-2"
        "batch-1" = "oj-loadtest-batch-1"
        "judge-1" = "oj-loadtest-judge-1"
        "judge-2" = "oj-loadtest-judge-2"
        prometheus = "oj-loadtest-prometheus"
        grafana = "oj-loadtest-grafana"
        alertmanager = "oj-loadtest-alertmanager"
        cadvisor = "oj-loadtest-cadvisor"
        "mysqld-exporter" = "oj-loadtest-mysqld-exporter"
        "redis-exporter" = "oj-loadtest-redis-exporter"
        "nginx-exporter" = "oj-loadtest-nginx-exporter"
    }
}

# The services a run starts. `mysql` is absent on purpose - the overlay points every application at
# the instance already running on the host, and starting a container of our own would be a second,
# differently provisioned server wearing the same name in the reports.
function Get-PilotStartServices {
    return @(
        "nginx", "redis", "rabbitmq",
        "web-1", "web-2", "batch-1", "judge-1", "judge-2",
        "prometheus", "grafana", "alertmanager", "cadvisor",
        "mysqld-exporter", "redis-exporter", "nginx-exporter"
    )
}

function Initialize-RecoveryExperiment {
    param(
        [Parameter(Mandatory = $true)][string]$WorktreeRoot,
        [Parameter(Mandatory = $true)][string]$ArtifactDirectory,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][ValidateSet("full-replay", "redis-seq", "stream-offset")][string]$Mode,
        [Parameter(Mandatory = $true)][string]$DbPassword,
        [string]$ProjectName = "oj-loadtest",
        [string]$DbContainer = "oj-test-mysql",
        [string]$DbName = "oj_test",
        [string]$DbUser = "root",
        [long]$ContestId = 1,
        [long]$ProblemIdStart = 1,
        [long]$ProblemIdEnd = 5,
        [string]$BaseUrl = "http://localhost:18080",
        [string]$PrometheusUrl = "http://127.0.0.1:9090",
        [string]$JavaExe = "C:\Program Files\Java\jdk-17\bin\java.exe",
        [int]$ReadyTimeoutSeconds = 300,
        # Overridable so the parts of the harness that write - the rollback injector, the redis reset -
        # can be tested against a throwaway container instead of the pilot's own. A test that had to
        # point at the real instance to run is a test nobody dares run.
        [string]$RedisContainer = "oj-loadtest-redis",
        [string]$RabbitContainer = "oj-loadtest-rabbitmq",
        [string]$BatchContainer = "oj-loadtest-batch-1"
    )

    if ($RunId -notmatch '^[A-Za-z0-9_]+$') {
        throw "RunId '$RunId' must be alphanumeric or underscore: it becomes a SQL LIKE pattern and a Redis key suffix."
    }
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        throw "DbPassword is empty. Pass it from the DB_PASSWORD environment variable; it is never written to an artifact."
    }

    $resolvedRoot = (Resolve-Path -LiteralPath $WorktreeRoot).Path
    if (-not (Test-Path -LiteralPath $ArtifactDirectory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $ArtifactDirectory -Force)
    }
    $resolvedArtifacts = (Resolve-Path -LiteralPath $ArtifactDirectory).Path

    $script:recoveryConfig = [pscustomobject][ordered]@{
        WorktreeRoot = $resolvedRoot
        ArtifactDirectory = $resolvedArtifacts
        RunId = $RunId
        Mode = $Mode
        ProjectName = $ProjectName
        ComposeArgs = @(
            "-p", $ProjectName,
            "--project-directory", $resolvedRoot,
            "-f", "compose.yaml",
            "-f", "compose.loadtest.yaml",
            "-f", "compose.observability.yaml",
            "-f", "compose.recovery-pilot.yaml"
        )
        DbContainer = $DbContainer
        DbName = $DbName
        DbUser = $DbUser
        DbPassword = $DbPassword
        ContestId = $ContestId
        ProblemIdStart = $ProblemIdStart
        ProblemIdEnd = $ProblemIdEnd
        ContestStartTimeMysql = $null
        # Set to true only by Set-ExperimentContestScope, which is called only by the seeder after it
        # has read back the row it inserted. Every destructive statement in this harness is scoped by
        # the contest id, and the default contest id (1) is a plausible id for somebody else's data. A
        # cleanup that runs before a seed has therefore to be refused rather than allowed to delete
        # `contest_id = 1`.
        ContestScopeFromSeed = $false
        BaseUrl = $BaseUrl.TrimEnd('/')
        PrometheusUrl = $PrometheusUrl.TrimEnd('/')
        JavaExe = $JavaExe
        ReadyTimeoutSeconds = $ReadyTimeoutSeconds
        RedisContainer = $RedisContainer
        RabbitContainer = $RabbitContainer
        BatchContainer = $BatchContainer
        ScoreboardKeyPattern = $script:recoveryScoreboardKeyPattern
        ScoreboardKeyPrefix = $script:recoveryScoreboardKeyPrefix
        CheckpointKey = "$($script:recoveryScoreboardKeyPrefix)stream:offset"
        StreamDbPendingKey = "$($script:recoveryScoreboardKeyPrefix)stream:db-pending"
        ProcessedKey = "$($script:recoveryScoreboardKeyPrefix)${ContestId}:processed"
        RankingKey = "$($script:recoveryScoreboardKeyPrefix)${ContestId}:ranking"
        SnapshotDirectory = "/tmp/sbrec-snapshot-$RunId"
        SeedPrefix = "sbrec_${RunId}_"
        QueueName = "contest.judge.result.stream"
        ExpectedPrometheusTargets = 12
    }
    return $script:recoveryConfig
}

function Get-RecoveryConfig {
    if ($null -eq $script:recoveryConfig) {
        throw "The recovery experiment is not initialized. Call Initialize-RecoveryExperiment first."
    }
    return $script:recoveryConfig
}

# The contest is inserted rather than assumed, so its id is not known when the experiment is
# initialized - and two of the Redis keys this harness reads are derived from that id. This is the one
# place those are recomputed, so a caller cannot update the id without also moving the keys that
# address the scoreboard it just created.
function Set-ExperimentContestScope {
    param(
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)][long]$ProblemIdStart,
        [Parameter(Mandatory = $true)][long]$ProblemIdEnd,
        [Parameter(Mandatory = $true)][string]$ContestStartTimeMysql
    )

    $config = Get-RecoveryConfig
    $config.ContestId = $ContestId
    $config.ProblemIdStart = $ProblemIdStart
    $config.ProblemIdEnd = $ProblemIdEnd
    $config.ContestStartTimeMysql = $ContestStartTimeMysql
    $config.ContestScopeFromSeed = $true
    $config.ProcessedKey = "$($config.ScoreboardKeyPrefix)${ContestId}:processed"
    $config.RankingKey = "$($config.ScoreboardKeyPrefix)${ContestId}:ranking"
    return $config
}

function Format-InvariantNumber {
    param([Parameter(Mandatory = $true)][double]$Value)

    return $Value.ToString("R", $script:recoveryInvariantCulture)
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
            $script:recoveryInvariantCulture,
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
            $script:recoveryInvariantCulture,
            [ref]$parsed) -or
        [double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "$Description is not a finite number: '$Value'."
    }
    return $parsed
}

# Windows PowerShell 5.1 turns a native command's stderr into ErrorRecord objects while
# ErrorActionPreference is Stop, which would abort on a harmless client warning before the exit code
# that decides the matter is even read. Every native call here judges success by exit code, and stdout is
# what comes back.
#
# stderr is not discarded, though: it goes to a file and is carried into the failure. A statement MySQL
# rejected explains itself there, and the exit code alone reads as `docker failed (exit 1)` - a failure
# with the reason removed, which is worse than no failure report at all because it looks like one.
function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$StandardInput = $null
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $stderrPath = [IO.Path]::GetTempFileName()
    try {
        if ($null -eq $StandardInput) {
            $output = @(& $Executable @Arguments 2>$stderrPath)
        }
        else {
            $output = @($StandardInput | & $Executable @Arguments 2>$stderrPath)
        }
        $exitCode = $LASTEXITCODE
        $stderr = ""
        if (Test-Path -LiteralPath $stderrPath) {
            $stderr = [string](Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue)
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }
    if ($exitCode -ne 0) {
        $reason = if ([string]::IsNullOrWhiteSpace($stderr)) { "" } else { ": $($stderr.Trim())" }
        throw "$Executable failed (exit $exitCode)$reason [$(($Arguments -join ' ').Trim())]"
    }
    return $output
}

function Invoke-Compose {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    return Invoke-NativeCommand -Executable "docker" -Arguments (@("compose") + (Get-RecoveryConfig).ComposeArgs + $Arguments)
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    return Invoke-NativeCommand -Executable "docker" -Arguments $Arguments
}

# The SQL is written to the server's stdin rather than to its argv. The oracle statement is a few
# kilobytes of window function and the argv limit on Windows is a real ceiling; more importantly a
# statement passed through stdin cannot be re-quoted by a shell, so the text that runs is the text
# that was written.
function Invoke-SqlScript {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $config = Get-RecoveryConfig
    $output = Invoke-NativeCommand -Executable "docker" -StandardInput $Sql -Arguments @(
        "exec", "-i",
        "-e", "MYSQL_PWD=$($config.DbPassword)",
        $config.DbContainer,
        "mysql", "-u$($config.DbUser)", "-D", $config.DbName, "-N", "-B"
    )
    if (@($output | Where-Object { $_ -match '^ERROR \d+' }).Count -gt 0) {
        throw "MySQL rejected $Description`: $(@($output | Where-Object { $_ -match '^ERROR \d+' }) -join ' ')"
    }
    return @($output)
}

# `mysql -N -B` has no types to carry: it prints SQL NULL as the four characters NULL. So an aggregate
# over no rows - `MIN(user_id)`, `MAX(LENGTH(id))` - arrives as the string 'NULL', which passes both
# `$null -ne $cell` and `[string]::IsNullOrWhiteSpace($cell)`, and a caller asking "is this aggregate
# absent?" gets told no. Normalizing here, at the one boundary where MySQL's text becomes values, is what
# makes that question answerable; nothing this harness asks of MySQL returns the text NULL as data, since
# every cell is an id, a count, a timestamp or a digest. An empty cell is left alone: it is an empty
# string, which is a value, and only NULL is the absence of one.
function ConvertFrom-SqlCell {
    param([string]$Value)

    if ($Value -eq "NULL") {
        return $null
    }
    return $Value
}

# Batched, tab separated, no header. Callers are numeric or single-token projections: a value with an
# embedded tab or newline would be silently ambiguous here, and none of the statements this harness
# runs can produce one.
#
# Rows are `object[]` and not `string[]` so that a normalized NULL survives, and the array is built
# explicitly rather than cast: `[object[]]$stringArray` is a reference conversion, because .NET arrays
# are covariant, so the cast hands back the same `string[]` and assigning null into it coerces the
# absence to an empty string again - the whole normalization undone by a cast that looks like a copy.
function Invoke-SqlRows {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $lines = @(Invoke-SqlScript -Sql $Sql -Description $Description)
    $rows = New-Object 'System.Collections.Generic.List[object[]]'
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) {
            continue
        }
        $fields = [string]$line -split "`t", -1
        $cells = New-Object 'object[]' $fields.Length
        for ($i = 0; $i -lt $fields.Length; $i++) {
            $cells[$i] = ConvertFrom-SqlCell -Value $fields[$i]
        }
        $rows.Add($cells)
    }
    return $rows.ToArray()
}

function Invoke-SqlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $lines = @(Invoke-SqlScript -Sql $Sql -Description $Description)
    $value = $lines | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Last 1
    if ($null -eq $value) {
        throw "MySQL returned no value for $Description."
    }
    # A scalar is one cell, so it is normalized the same way: callers such as the latency percentiles ask
    # whether the aggregate is absent, and must not be handed the text NULL and told it is a number.
    return ConvertFrom-SqlCell -Value ([string]$value)
}

function Invoke-SqlInt64 {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Description
    )

    return ConvertTo-RequiredInt64 -Value (Invoke-SqlScalar -Sql $Sql -Description $Description) -Description $Description
}

# --- Redis -----------------------------------------------------------------------------------------

function Invoke-RedisText {
    param([Parameter(Mandatory = $true)][string[]]$RedisArguments)

    $config = Get-RecoveryConfig
    return @(Invoke-NativeCommand -Executable "docker" -Arguments (@(
                "exec", $config.RedisContainer, "redis-cli", "--raw"
            ) + $RedisArguments))
}

function Get-RedisText {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $value = @(Invoke-RedisText -RedisArguments @("GET", $Key)) |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
        Select-Object -Last 1
    if ($null -eq $value) {
        throw "Redis key '$Key' is missing ($Description)."
    }
    return [string]$value
}

function Get-RedisSetMembers {
    param([Parameter(Mandatory = $true)][string]$Key)

    $members = @(Invoke-RedisText -RedisArguments @("SMEMBERS", $Key)) |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
        ForEach-Object { ([string]$_).Trim() }
    return , @($members | Sort-Object -Unique)
}

# Writes a shell script into the container and runs it there. The injector's capture and restore are
# shell programs because they have to be: Redis DUMP payloads are binary and cannot survive a trip
# through PowerShell's string-oriented stdout. Keeping them as files also means the shell text that
# runs is reviewable in one piece instead of being assembled by nested quoting.
function Invoke-ContainerScript {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$ScriptText,
        [Parameter(Mandatory = $true)][string]$Description,
        [string[]]$ScriptArguments = @()
    )

    $localPath = Join-Path ([IO.Path]::GetTempPath()) ("sbrec-" + [Guid]::NewGuid().ToString("N") + ".sh")
    $containerPath = "/tmp/sbrec-script.sh"
    try {
        # LF endings: the script runs under the container's /bin/sh, where a CR would be part of the
        # last token on every line.
        $normalized = $ScriptText -replace "`r`n", "`n"
        [IO.File]::WriteAllText($localPath, $normalized, (New-Object Text.UTF8Encoding($false)))
        [void](Invoke-Docker -Arguments @("cp", $localPath, "${Container}:${containerPath}"))

        $arguments = @("exec", $Container, "sh", $containerPath) + $ScriptArguments
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = @(& docker @arguments 2>&1)
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($exitCode -ne 0) {
            throw "$Description failed in $Container (exit $exitCode): $($output -join ' | ')"
        }
        return @($output)
    }
    finally {
        if (Test-Path -LiteralPath $localPath) {
            Remove-Item -LiteralPath $localPath -Force -ErrorAction SilentlyContinue
        }
    }
}

# --- Prometheus ------------------------------------------------------------------------------------

function Invoke-PrometheusQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $encoded = [uri]::EscapeDataString($Query)
    try {
        $response = Invoke-RestMethod -Uri "$((Get-RecoveryConfig).PrometheusUrl)/api/v1/query?query=$encoded" -TimeoutSec 10
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

# Zero when the series has never been scraped, which is the honest reading for a counter that has not
# moved - not an absence of data, because a scrape that has never seen the metric is how a counter
# that was never incremented looks. Distinct from `unavailable`, which this harness reserves for a
# quantity no source exposes at all.
function Get-PrometheusScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $samples = @(Invoke-PrometheusQuery -Query $Query -Description $Description)
    $total = 0d
    foreach ($sample in $samples) {
        $pair = @($sample.value)
        if ($pair.Count -lt 2) {
            throw "Prometheus $Description returned a malformed sample."
        }
        $total += ConvertTo-RequiredDouble -Value $pair[1] -Description $Description
    }
    return $total
}

function Assert-PrometheusTargetsHealthy {
    $config = Get-RecoveryConfig
    $samples = @(Invoke-PrometheusQuery -Query "up" -Description "target health")
    $up = 0d
    $down = New-Object 'System.Collections.Generic.List[string]'
    foreach ($sample in $samples) {
        $pair = @($sample.value)
        if ($pair.Count -lt 2) {
            throw "Prometheus target health returned a malformed sample."
        }
        $value = ConvertTo-RequiredDouble -Value $pair[1] -Description "Prometheus up"
        $up += $value
        if ($value -ne 1d) {
            $down.Add("$([string]$sample.metric.job)/$([string]$sample.metric.instance)")
        }
    }
    if ($samples.Count -ne $config.ExpectedPrometheusTargets -or $up -ne [double]$config.ExpectedPrometheusTargets) {
        # Named, not only counted. `sum(up)=10` says an input to the measurement is missing without saying
        # which one, and those two numbers were the whole of what the first run to reach this gate reported.
        $missing = if ($down.Count -gt 0) { " down: $($down -join ', ')" } else { "" }
        throw "Prometheus targets are incomplete: count(up)=$($samples.Count), sum(up)=$up; " +
        "expected $($config.ExpectedPrometheusTargets)/$($config.ExpectedPrometheusTargets).$missing"
    }
    $apps = @($samples | Where-Object { [string]$_.metric.job -eq "oj-app" })
    $rabbitDetailed = @($samples | Where-Object { [string]$_.metric.job -eq "rabbitmq-per-queue" })
    if ($apps.Count -ne 5 -or $rabbitDetailed.Count -ne 1) {
        throw "Prometheus comparison targets are incomplete: oj-app=$($apps.Count)/5, " +
        "rabbitmq-per-queue=$($rabbitDetailed.Count)/1."
    }
}

# The app tier's targets appear in Prometheus well after their containers report healthy: a JVM has to
# finish starting before `/actuator/prometheus` answers, and Prometheus scrapes on its own interval.
# Measured on this machine, the gap is about forty seconds - at the moment `Wait-PilotStackHealthy`
# returns, 7 of the 12 targets are scrapeable, and the last two arrive around forty seconds later. So the
# assertion above, made once at that moment, could not pass at all.
#
# Waiting is not the same as waiting for a recovery, and the difference is what makes this safe: the load
# has not started, no fault has been injected, and no scoreboard exists yet. What it buys is that the
# baseline window's resource metrics are complete. A run that began with two targets unscraped would
# report app CPU and memory for part of its baseline as missing, and the honest consequence of that is a
# measurement that cannot be compared with the others - not one with a quiet gap in it.
function Wait-PrometheusTargetsHealthy {
    $config = Get-RecoveryConfig
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($config.ReadyTimeoutSeconds)
    $lastError = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            Assert-PrometheusTargetsHealthy
            return
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "Prometheus did not report every target healthy within $($config.ReadyTimeoutSeconds) seconds: $lastError"
}

# --- containers ------------------------------------------------------------------------------------

function Get-ProjectContainers {
    $config = Get-RecoveryConfig
    # The `@(...)` is around the pipeline and not around `Invoke-NativeCommand`, because with no project
    # container running `docker ps -aq` prints nothing: an unwrapped pipeline would assign null and the
    # `.Count` on the next line would throw on the very state this branch exists to answer for.
    $ids = @(@(Invoke-NativeCommand -Executable "docker" -Arguments @(
                "ps", "-aq", "--filter", "label=com.docker.compose.project=$($config.ProjectName)"
            )) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($ids.Count -eq 0) {
        return @()
    }
    $json = (Invoke-NativeCommand -Executable "docker" -Arguments (@("inspect") + @($ids))) -join "`n"
    if ([string]::IsNullOrWhiteSpace($json)) {
        throw "Could not inspect the '$($config.ProjectName)' containers."
    }
    return @($json | ConvertFrom-Json | ForEach-Object { $_ })
}

function Assert-PilotStackHealthy {
    $expected = Get-ExpectedPilotContainers
    $containers = @(Get-ProjectContainers)
    if ($containers.Count -ne $expected.Count) {
        $actualNames = @($containers | ForEach-Object { ([string]$_.Name).TrimStart('/') } | Sort-Object)
        throw "Expected exactly $($expected.Count) pilot containers, found $($containers.Count): $($actualNames -join ', ')."
    }

    $actualByService = @{}
    foreach ($container in $containers) {
        $service = [string]$container.Config.Labels.'com.docker.compose.service'
        $name = ([string]$container.Name).TrimStart('/')
        if ([string]::IsNullOrWhiteSpace($service)) {
            throw "Container '$name' carries no Compose service label."
        }
        if ($actualByService.ContainsKey($service)) {
            throw "Compose project has more than one container for service '$service'."
        }
        $actualByService[$service] = $container
    }

    foreach ($service in $expected.Keys) {
        if (-not $actualByService.ContainsKey($service)) {
            throw "The pilot stack is missing service '$service'."
        }
        $container = $actualByService[$service]
        $name = ([string]$container.Name).TrimStart('/')
        if ($name -ne $expected[$service]) {
            throw "Service '$service' is container '$name'; expected exact name '$($expected[$service])'."
        }
        if ([string]$container.State.Status -ne "running" -or [bool]$container.State.Paused -or
            [bool]$container.State.Restarting -or [bool]$container.State.OOMKilled) {
            throw "Container '$name' is not a clean running container (status=$($container.State.Status), " +
            "paused=$($container.State.Paused), restarting=$($container.State.Restarting), " +
            "OOMKilled=$($container.State.OOMKilled))."
        }
        $healthProperty = $container.State.PSObject.Properties["Health"]
        if ($null -ne $healthProperty -and $null -ne $healthProperty.Value -and
            [string]$healthProperty.Value.Status -ne "healthy") {
            throw "Container '$name' health is '$($healthProperty.Value.Status)', expected healthy."
        }
    }
}

function Wait-PilotStackHealthy {
    $config = Get-RecoveryConfig
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($config.ReadyTimeoutSeconds)
    $lastError = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            Assert-PilotStackHealthy
            return
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    throw "The pilot stack did not become healthy within $($config.ReadyTimeoutSeconds) seconds: $lastError"
}

# nginx resolves its upstream once, when it starts, and keeps those addresses for its lifetime: the
# configuration is `upstream oj_web { server web-1:8080; server web-2:8080; }` with no resolver
# directive, so the names are looked up as the configuration loads and never again. A run stops the app
# tier when it finishes and recreates it when the next one starts, while nginx - which is not part of
# that tier - keeps running with the addresses of the tier that has just been destroyed. Every gate
# passes anyway: `nginx -t` is satisfied by a configuration that was always fine, and the web nodes
# answer on 8080, because they are the *new* nodes. The first thing to notice is the first scoreboard
# read, which is a measurement step, and it reports a 502 - a run lost to a stack detail that nothing
# looking at the stack could see.
#
# So the edge is recreated once the tier it fronts is up, which is a moment when those names resolve to
# the containers this run will measure, and then made to answer a real request through itself. This is
# part of bringing the run's stack up: no load has started and no fault has been injected, so there is
# nothing here that a recovery could hide behind.
function Reset-EdgeRouting {
    $config = Get-RecoveryConfig

    [void](Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "--no-deps", "nginx"))

    $uri = "$($config.BaseUrl)/api/contests/$($config.ContestId)/scoreboard?startRank=1&size=1"
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($config.ReadyTimeoutSeconds)
    $lastError = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            [void](Invoke-RestMethod -Uri $uri -TimeoutSec 15)
            return
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw ("nginx did not route a scoreboard read to the app tier within " +
        "$($config.ReadyTimeoutSeconds) seconds: $lastError")
}

# The broker and Redis are started before the resets, and `docker compose up -d` returns when the
# containers exist rather than when the services inside them answer. On a cold start that gap is real:
# `rabbitmqctl list_queues` inside it exits 64 with "this command requires the 'rabbit' app to be
# running", which stopped a calibration run at step 1 - after the stack had been brought down and back
# up, which is exactly what a reader following the README from a clean machine will do.
#
# Waiting here cannot hide anything being measured: no load is running and no scoreboard exists yet, so
# this waits for the reset's target to exist and not for a recovery to finish. Readiness is tested with
# the reset's own calls rather than a proxy - a `list_queues` that succeeds is the precondition
# `Reset-ExperimentQueue` needs, and `PING` is the one `Clear-RecoveryRedis` needs.
function Wait-ResetTargetsReady {
    $config = Get-RecoveryConfig
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($config.ReadyTimeoutSeconds)
    $lastError = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            [void](Get-RabbitQueueState)
            $reply = @(Invoke-RedisText -RedisArguments @("PING"))
            if ($reply.Count -eq 0 -or [string]$reply[0] -notmatch "PONG") {
                throw "The dedicated redis instance did not answer PING (got '$($reply -join ' ')')."
            }
            return
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    throw "The reset's targets did not become ready within $($config.ReadyTimeoutSeconds) seconds: $lastError"
}

function Get-ContainerIdentity {
    param([Parameter(Mandatory = $true)][string]$Name)

    $json = (Invoke-NativeCommand -Executable "docker" -Arguments @("inspect", $Name)) -join "`n"
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

# batch-1 is paused and unpaused by the injector; nothing in this experiment restarts a container, so a
# changed identity anywhere means the run measured a different process than the one it seeded.
function Get-ContainerIdentitySnapshot {
    $snapshot = @{}
    foreach ($name in (Get-ExpectedPilotContainers).Values) {
        $snapshot[$name] = Get-ContainerIdentity -Name $name
    }
    return $snapshot
}

function Assert-ContainerIdentitiesStable {
    param([Parameter(Mandatory = $true)]$Before)

    foreach ($name in @($Before.Keys | Sort-Object)) {
        $old = $Before[$name]
        $current = Get-ContainerIdentity -Name $name
        if ($current.Id -ne $old.Id -or
            $current.StartedAt -ne $old.StartedAt -or
            $current.RestartCount -ne $old.RestartCount) {
            throw "Container '$name' was replaced or restarted during the run. The measurement is not comparable."
        }
    }
}

# --- the shared MySQL instance ---------------------------------------------------------------------

# Reads the run's own evidence about tables it does not own, so that "we did not touch anything else"
# is a comparison of two readings rather than an assertion of intent. Each entry is a single value:
# a GROUP_CONCAT in primary-key order, which is deterministic for these tables. The concat limit is
# raised inside the statement because the server default (1024 bytes) would truncate and hide a change
# rather than report one.
function Get-NonInterferenceSentinel {
    $seedPrefix = (Get-RecoveryConfig).SeedPrefix
    $script = @"
SET SESSION group_concat_max_len = 8388608;
SELECT CONCAT('users=', IFNULL(GROUP_CONCAT(CONCAT_WS(':', id, IFNULL(name, ''), solved_count, IFNULL(streak_last_solved_date, ''), streak_current_streak, streak_longest_streak) ORDER BY id SEPARATOR '|'), ''))
  FROM user WHERE name NOT LIKE '$seedPrefix%';
SELECT CONCAT('daily_active=', IFNULL(GROUP_CONCAT(CONCAT_WS(':', day, user_id, last_active_time) ORDER BY day, user_id SEPARATOR '|'), ''))
  FROM daily_active_users d
  WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.id = d.user_id AND u.name LIKE '$seedPrefix%');
SELECT CONCAT('streak_buckets=', IFNULL(GROUP_CONCAT(n ORDER BY n), '')) FROM longest_streak_bucket;
SELECT CONCAT('longest_streak_snapshot=', IFNULL(GROUP_CONCAT(CONCAT_WS(':', snapshot_rank, user_id, longest_streak, IFNULL(last_solved_time, '')) ORDER BY snapshot_rank SEPARATOR '|'), ''))
  FROM longest_streak_rank_snapshot s
  WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.id = s.user_id AND u.name LIKE '$seedPrefix%');
SELECT CONCAT('flyway=', COUNT(*), ':', IFNULL(MAX(installed_rank), 0), ':', IFNULL(MAX(version), '')) FROM flyway_schema_history;
"@
    $lines = @(Invoke-SqlScript -Sql $script -Description "non-interference sentinel")
    $sentinel = [ordered]@{}
    foreach ($line in $lines) {
        $text = [string]$line
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        $separator = $text.IndexOf("=")
        if ($separator -lt 1) {
            throw "Non-interference sentinel returned an unreadable line: '$text'."
        }
        $sentinel[$text.Substring(0, $separator)] = $text.Substring($separator + 1)
    }
    foreach ($required in @("users", "daily_active", "streak_buckets", "longest_streak_snapshot", "flyway")) {
        if (-not $sentinel.Contains($required)) {
            throw "Non-interference sentinel is missing '$required'."
        }
    }
    return $sentinel
}

function Get-ResidualRowCounts {
    return [ordered]@{
        user = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM user" -Description "user row count"
        daily_active_users = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM daily_active_users" -Description "daily_active_users row count"
        longest_streak_bucket = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM longest_streak_bucket" -Description "longest_streak_bucket row count"
        longest_streak_rank_snapshot = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM longest_streak_rank_snapshot" -Description "longest_streak_rank_snapshot row count"
        contest = Invoke-SqlInt64 -Sql "SELECT COUNT(*) FROM contest" -Description "contest row count"
    }
}

# Every instant this harness compares against a stored column is taken from the database's own clock
# rather than from the harness's. The columns are wall-clock values with no zone attached, so a cutoff
# computed elsewhere would be correct only while the harness, the JVM and MySQL all agreed on the zone
# - and a silent few-hour offset would look like a latency distribution, not like a bug.
function Get-MySqlNow {
    return Invoke-SqlScalar -Sql "SELECT CURRENT_TIMESTAMP(6);" -Description "database clock"
}

# The frame the timestamps are written in, recorded rather than assumed: the app writes the judged-at
# columns and MySQL writes the applied-at column, so if the two disagreed about the zone every
# reflect latency in the report would be off by the difference.
function Get-ClockFrameDiagnostic {
    $row = @(Invoke-SqlRows -Sql @"
SELECT @@global.time_zone, @@session.time_zone, NOW(6), UTC_TIMESTAMP(6),
       (SELECT IFNULL(MAX(scoreboard_applied_at), 'none') FROM contest_submission_result);
"@ -Description "clock frame")[0]
    return [pscustomobject][ordered]@{
        globalTimeZone = [string]$row[0]
        sessionTimeZone = [string]$row[1]
        now = [string]$row[2]
        utcNow = [string]$row[3]
        latestAppliedAt = [string]$row[4]
    }
}

# The two clocks the latency columns are built from, compared on rows that carry one value from each.
#
# `provisional_judged_at` is written by the judging JVM - the batch passes the instant it judged at, and
# the driver formats it in the session's zone - while `result_saved_at` on the same row is MySQL's
# `CURRENT_TIMESTAMP(6)`. One INSERT writes both, milliseconds apart, so a difference between them past
# the tolerance is not a delay: it is the two clocks disagreeing, and the disagreement worth catching is
# a zone offset of hours. The tolerance sits orders of magnitude from both - well above the sub-second
# gap the write leaves, well below any frame error - so it is not a threshold anything normal drifts to.
#
# This is the comparison the applied-at column cannot make, which is what it was previously asked to do.
# `scoreboard_applied_at` is written by MySQL as well (`COALESCE(scoreboard_applied_at,
# CURRENT_TIMESTAMP(6))`), so reading it against MySQL's own clock compares MySQL with itself and
# measures pipeline staleness under a message about clock frames. Those two need opposite responses: a
# stale pipeline drains on its own, a wrong frame stays wrong and every latency figure computed across it
# is offset by the difference.
#
# Nothing to compare yet is a state and not a zero. Before the load no result has been judged, so the
# guard reports `unavailable` rather than a 0 that would read as a measured agreement.
function Assert-ClockFramesAligned {
    # Not `Mandatory`: the default is the intended tolerance, and PowerShell treats a mandatory parameter
    # as one the caller must supply even when it declares a default, so `Mandatory` here made both call
    # sites - which pass nothing - fail to bind at all. The tolerance is documented by the default.
    param([int]$ToleranceSeconds = 60)

    $row = @(Invoke-SqlRows -Sql @"
SELECT MAX(ABS(TIMESTAMPDIFF(SECOND, provisional_judged_at, result_saved_at))), COUNT(*)
  FROM contest_submission_result
 WHERE contest_id = $((Get-RecoveryConfig).ContestId)
   AND provisional_judged_at IS NOT NULL
   AND result_saved_at IS NOT NULL;
"@ -Description "judged-at against saved-at")[0]
    $compared = ConvertTo-RequiredInt64 -Value $row[1] -Description "results carrying both timestamps"
    if ($compared -eq 0L) {
        return "unavailable (no judged result carries both timestamps yet)"
    }
    $skew = ConvertTo-RequiredInt64 -Value $row[0] -Description "judged-at against saved-at skew"
    if ($skew -gt $ToleranceSeconds) {
        # The zones are what a reader needs to act on this, and they are one query away rather than
        # something to reconstruct from the number: which zone the session is in against which one the
        # server is in is the whole difference when the two clocks are this far apart.
        $frame = Get-ClockFrameDiagnostic
        throw ("The judging JVM's clock and MySQL's are ${skew}s apart over $compared result(s), which is " +
            "a frame error and not a delay: every reflect latency computed across the two would be off by " +
            "that difference. MySQL reports global time_zone '$($frame.globalTimeZone)' and session " +
            "time_zone '$($frame.sessionTimeZone)'; the server reads $($frame.now) and " +
            "$($frame.utcNow) as UTC, against the applied-at column's newest value of " +
            "'$($frame.latestAppliedAt)'.")
    }
    return "the JVM's and MySQL's clocks are ${skew}s apart over $compared result(s)"
}

function Assert-NonInterferenceIntact {
    param(
        [Parameter(Mandatory = $true)]$Before,
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][string]$EvidencePath
    )

    $after = Get-NonInterferenceSentinel
    $differences = New-Object 'System.Collections.Generic.List[string]'
    foreach ($key in $Before.Keys) {
        $old = [string]$Before[$key]
        $new = [string]$after[$key]
        if ($old -eq $new) {
            continue
        }
        if ($key -eq "streak_buckets") {
            # A bucket value the experiment's own users pushed into existence is a new row in a table
            # this run does not own, and it is not a changed pre-existing row. Every bucket that was
            # there before has to still be there.
            $oldBuckets = @($old -split '\|' | Where-Object { $_ -ne "" })
            $newBuckets = @($new -split '\|' | Where-Object { $_ -ne "" })
            $missing = @($oldBuckets | Where-Object { $newBuckets -notcontains $_ })
            if ($missing.Count -eq 0) {
                continue
            }
            $differences.Add("streak_buckets lost: $($missing -join ',')")
            continue
        }
        $differences.Add($key)
    }

    $record = [pscustomobject][ordered]@{
        phase = $Phase
        observedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        identical = $differences.Count -eq 0
        # `ToArray()` for the reason recorded in the seeder's record building: `@(...)` around a
        # `List[object]` throws under strict mode, and this one only escapes that because its element
        # type is `string`.
        differences = $differences.ToArray()
        before = $Before
        after = $after
    }
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $EvidencePath -Encoding utf8

    if ($differences.Count -gt 0) {
        throw "Pre-existing rows changed during $Phase` ($($differences -join ', ')). Evidence: $EvidencePath"
    }
    return $record
}
