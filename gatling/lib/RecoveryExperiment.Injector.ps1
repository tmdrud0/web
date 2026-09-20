# The fault injector: rolls the scoreboard namespace back to a snapshot taken from the same instance.
#
# The fault this reproduces is "the scoreboard is Redis, and Redis came back from an older snapshot
# than the one that was running". It is injected at the only place that is both faithful and safe: the
# scoreboard's own key namespace, replaced key by key with the bytes those keys held at the snapshot
# instant. Everything else in the instance - sessions, dedup markers, rate-limit counters - is left
# alone, so the failure being measured is the scoreboard's and not the injector's. Swapping the whole
# RDB file would have been the more literal reading, and it would have cost several seconds of Redis
# being absent, which lands in the same "new ingress failed" counter as a genuine fault.
#
# What this does not reproduce, and is therefore the same for all three modes rather than a difference
# between them: the RDB load path itself, and a rollback of anything outside `contest:scoreboard:`.
#
# Binary payloads never pass through PowerShell. `redis-cli --raw DUMP` appends a newline to the
# payload, so reading it into a string and writing it back would either corrupt the last byte or rely
# on the client stripping exactly the right one from the right place. Instead the dump is trimmed by
# one byte inside the container, kept there as a file, and sent back as a RESP command stream, which
# is what `redis-cli --pipe` speaks. Restoring is checked by re-capturing the same keys and comparing
# the payload bytes, so a restore that silently dropped a key or a byte is a stopped run.

# Snapshots live in the container under a run-scoped directory and are copied to the artifact directory
# as evidence. The container is the authority - it is where the bytes were read and where they are
# written back from - and the host copy is what makes the run reviewable afterwards.
function Get-SnapshotDirectoryInContainer {
    param([Parameter(Mandatory = $true)][string]$Label)

    return "$((Get-RecoveryConfig).SnapshotDirectory)/$Label"
}

function Get-SnapshotDirectoryOnHost {
    param([Parameter(Mandatory = $true)][string]$Label)

    return Join-Path (Get-RecoveryConfig).ArtifactDirectory "snapshot/$Label"
}

$script:recoveryCaptureScript = @'
set -e
pattern="$1"
out="$2"
rm -rf "$out"
mkdir -p "$out"
redis-cli --raw --scan --pattern "$pattern" | LC_ALL=C sort > "$out/keys.txt"
: > "$out/manifest.txt"
count=0
while IFS= read -r key; do
    count=$((count + 1))
    name=$(printf '%06d' "$count")
    type=$(redis-cli --raw TYPE "$key")
    pttl=$(redis-cli --raw PTTL "$key")
    printf '%s\n' "$type" > "$out/$name.type"
    printf '%s\n' "$pttl" > "$out/$name.pttl"
    redis-cli --raw DUMP "$key" > "$out/$name.raw"
    size=$(wc -c < "$out/$name.raw")
    if [ "$size" -lt 1 ]; then
        echo "SBRE_CAPTURE_FAILED empty dump for $key" >&2
        exit 1
    fi
    head -c $((size - 1)) "$out/$name.raw" > "$out/$name.bin"
    rm -f "$out/$name.raw"
    printf '%s\t%s\t%s\t%s\n' "$key" "$type" "$pttl" "$name" >> "$out/manifest.txt"
done < "$out/keys.txt"
echo "SBRE_CAPTURE_KEYS=$count"
'@

# Deletes every key in the namespace and then restores the snapshot. The delete pass is over the keys
# that exist *now*, not over the keys the snapshot holds: a key created after the snapshot instant is
# part of what a rollback to that instant has to take away, and deleting only the snapshot's keys would
# leave the namespace a union of two states rather than a past one.
$script:recoveryRestoreScript = @'
set -e
out="$1"
pattern="$2"
if [ ! -f "$out/manifest.txt" ]; then
    echo "SBRE_RESTORE_FAILED no manifest in $out" >&2
    exit 1
fi

deleteFile="$out/delete.resp"
: > "$deleteFile"
redis-cli --raw --scan --pattern "$pattern" | while IFS= read -r key; do
    klen=$(printf %s "$key" | wc -c)
    printf '*2\r\n$3\r\nDEL\r\n$%s\r\n%s\r\n' "$klen" "$key" >> "$deleteFile"
done
deleted=$(redis-cli --raw --scan --pattern "$pattern" | wc -l)
if [ "$deleted" -gt 0 ]; then
    redis-cli --pipe < "$deleteFile" > "$out/delete-pipe.log" 2>&1
fi
remaining=$(redis-cli --raw --scan --pattern "$pattern" | wc -l)
if [ "$remaining" -ne 0 ]; then
    echo "SBRE_RESTORE_FAILED $remaining key(s) remain after the delete pass" >&2
    exit 1
fi

restoreFile="$out/restore.resp"
: > "$restoreFile"
IFS=$(printf '\t')
while read -r key type pttl name; do
    plen=$(wc -c < "$out/$name.bin")
    klen=$(printf %s "$key" | wc -c)
    if [ "$pttl" -lt 0 ]; then ttl=0; else ttl=$pttl; fi
    tlen=$(printf %s "$ttl" | wc -c)
    {
        printf '*5\r\n'
        printf '$7\r\nRESTORE\r\n'
        printf '$%s\r\n%s\r\n' "$klen" "$key"
        printf '$%s\r\n%s\r\n' "$tlen" "$ttl"
        printf '$%s\r\n' "$plen"
        cat "$out/$name.bin"
        printf '\r\n'
        printf '$7\r\nREPLACE\r\n'
    } >> "$restoreFile"
done < "$out/manifest.txt"
redis-cli --pipe < "$restoreFile" > "$out/restore-pipe.log" 2>&1
cat "$out/restore-pipe.log"
echo "SBRE_RESTORE_DELETED=$deleted"
echo "SBRE_RESTORE_KEYS=$(wc -l < "$out/manifest.txt")"
'@

# Byte-for-byte comparison of two captures of the same namespace. Types and payloads are compared;
# remaining TTLs are not, because time passes between the snapshot and the check and a restored key is
# supposed to have less of its life left than it did.
$script:recoveryCompareScript = @'
set -e
a="$1"
b="$2"
if [ ! -f "$a/manifest.txt" ] || [ ! -f "$b/manifest.txt" ]; then
    echo "SBRE_COMPARE_FAILED missing manifest" >&2
    exit 1
fi
if ! cmp -s "$a/keys.txt" "$b/keys.txt"; then
    echo "SBRE_COMPARE_FAILED key sets differ" >&2
    diff "$a/keys.txt" "$b/keys.txt" | head -20 >&2
    exit 1
fi
diffs=0
IFS=$(printf '\t')
while read -r key type pttl name; do
    if ! cmp -s "$a/$name.type" "$b/$name.type"; then
        echo "SBRE_COMPARE_FAILED type differs for $key" >&2
        diffs=$((diffs + 1))
    fi
    if ! cmp -s "$a/$name.bin" "$b/$name.bin"; then
        echo "SBRE_COMPARE_FAILED payload differs for $key" >&2
        diffs=$((diffs + 1))
    fi
done < "$a/manifest.txt"
if [ "$diffs" -ne 0 ]; then
    exit 1
fi
echo "SBRE_COMPARE_OK=$(wc -l < "$a/manifest.txt")"
'@

function Assert-RedisIsDedicated {
    $config = Get-RecoveryConfig
    $json = (Invoke-NativeCommand -Executable "docker" -Arguments @("inspect", $config.RedisContainer)) -join "`n"
    $containers = @($json | ConvertFrom-Json)
    if ($containers.Count -ne 1) {
        throw "Expected one container named '$($config.RedisContainer)', found $($containers.Count)."
    }
    $project = [string]$containers[0].Config.Labels.'com.docker.compose.project'
    $service = [string]$containers[0].Config.Labels.'com.docker.compose.service'
    if ($project -ne $config.ProjectName -or $service -ne "redis") {
        throw "Container '$($config.RedisContainer)' is not this project's redis service " +
        "(project='$project', service='$service'). Nothing in this experiment may write to a shared instance."
    }
    return [pscustomobject][ordered]@{
        container = $config.RedisContainer
        project = $project
        service = $service
        image = [string]$containers[0].Config.Image
    }
}

# What is in the instance before anything writes to it, in the form the decision needs: how many keys,
# which namespaces, and whether any of them is outside the scoreboard. A namespace this experiment does
# not own appearing here would mean the instance is carrying someone else's data.
function Get-RedisCensus {
    $config = Get-RecoveryConfig
    $census = [ordered]@{
        ObservedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        Dbsize = 0
        ScoreboardKeys = 0
        OtherKeys = 0
        PrefixHistogram = @()
    }
    $census["Dbsize"] = Get-RedisInt64 -RedisArguments @("DBSIZE")
    $keys = @(Invoke-RedisText -RedisArguments @("--scan")) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    $histogram = @{}
    $scoreboard = 0
    foreach ($key in $keys) {
        $text = ([string]$key).Trim()
        if ($text.StartsWith($config.ScoreboardKeyPrefix)) {
            $scoreboard++
            continue
        }
        $separator = $text.IndexOf(":")
        $prefix = if ($separator -gt 0) { $text.Substring(0, $separator + 1) } else { "(no prefix)" }
        if (-not $histogram.ContainsKey($prefix)) { $histogram[$prefix] = 0 }
        $histogram[$prefix] = $histogram[$prefix] + 1
    }
    $census["ScoreboardKeys"] = $scoreboard
    $census["OtherKeys"] = $keys.Count - $scoreboard
    $census["PrefixHistogram"] = @($histogram.Keys | Sort-Object | ForEach-Object { "$_=$($histogram[$_])" })
    return $census
}

function Get-RedisInt64 {
    param([Parameter(Mandatory = $true)][string[]]$RedisArguments)

    $value = @(Invoke-RedisText -RedisArguments $RedisArguments) | Select-Object -Last 1
    return ConvertTo-RequiredInt64 -Value $value -Description "redis $($RedisArguments -join ' ')"
}

# Captures the namespace as it is now. Batch-1 is paused by the caller, which is what makes the capture
# a single instant rather than a walk over a namespace that is being written to underneath it - the
# checkpoint and the standings in the snapshot then describe the same set of applied results, which is
# the property the rollback is supposed to reproduce.
function Export-ScoreboardSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$ObservedAtMysql
    )

    $config = Get-RecoveryConfig
    $containerDirectory = Get-SnapshotDirectoryInContainer -Label $Label
    $output = Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:recoveryCaptureScript `
        -Description "scoreboard snapshot '$Label'" -ScriptArguments @($config.ScoreboardKeyPattern, $containerDirectory)

    $keyCount = $null
    foreach ($line in $output) {
        if ([string]$line -match '^SBRE_CAPTURE_KEYS=(\d+)$') { $keyCount = [long]$Matches[1] }
    }
    if ($null -eq $keyCount) {
        throw "Snapshot '$Label' did not report its key count: $($output -join ' | ')"
    }

    $hostDirectory = Get-SnapshotDirectoryOnHost -Label $Label
    if (Test-Path -LiteralPath $hostDirectory) {
        Remove-Item -LiteralPath $hostDirectory -Recurse -Force
    }
    [void](New-Item -ItemType Directory -Path $hostDirectory -Force)
    [void](Invoke-Docker -Arguments @("cp", "$($config.RedisContainer):$containerDirectory/.", $hostDirectory))

    $manifest = @(Get-Content -LiteralPath (Join-Path $hostDirectory "manifest.txt") -ErrorAction SilentlyContinue)
    $typeHistogram = @{}
    $payloadBytes = 0L
    foreach ($line in $manifest) {
        $fields = @(([string]$line -split "`t", -1))
        if ($fields.Count -lt 4) { continue }
        if (-not $typeHistogram.ContainsKey($fields[1])) { $typeHistogram[$fields[1]] = 0 }
        $typeHistogram[$fields[1]] = $typeHistogram[$fields[1]] + 1
        $payloadBytes += (Get-Item -LiteralPath (Join-Path $hostDirectory "$($fields[3]).bin")).Length
    }
    if ($manifest.Count -ne $keyCount) {
        throw "Snapshot '$Label' has $($manifest.Count) manifest rows for $keyCount keys."
    }

    $processed = @()
    $processedKeyExists = Get-RedisInt64 -RedisArguments @("EXISTS", $config.ProcessedKey)
    if ($processedKeyExists -eq 1) {
        $processed = Get-RedisSetMembers -Key $config.ProcessedKey
    }
    $processedPath = Join-Path $hostDirectory "processed.txt"
    $processed | Set-Content -LiteralPath $processedPath -Encoding utf8

    $checkpointExists = Get-RedisInt64 -RedisArguments @("EXISTS", $config.CheckpointKey)
    $checkpoint = if ($checkpointExists -eq 1) {
        Get-RedisText -Key $config.CheckpointKey -Description "checkpoint at snapshot '$Label'"
    }
    else { "absent" }

    return [pscustomobject][ordered]@{
        Label = $Label
        CapturedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        CapturedAtMysql = $ObservedAtMysql
        KeyCount = $keyCount
        PayloadBytes = $payloadBytes
        TypeHistogram = @($typeHistogram.Keys | Sort-Object | ForEach-Object { "$_=$($typeHistogram[$_])" })
        ProcessedCount = $processed.Count
        ProcessedPath = $processedPath
        Processed = $processed
        Checkpoint = $checkpoint
        HostDirectory = $hostDirectory
        ContainerDirectory = $containerDirectory
    }
}

# The rollback itself: delete the namespace, write the snapshot back, and prove it took by capturing
# the namespace again and comparing it with the snapshot byte for byte.
#
# The returned instant is the host clock's. The database's own clock is deliberately not read here: the
# fault is a Redis fact, and a function that needs a database connection in order to annotate it cannot
# be exercised against a throwaway instance - which is the only way the byte-level restore path gets
# tested at all. The caller stamps the database clock when it records T_fault, which is where that
# frame is actually needed.
function Invoke-ScoreboardRollback {
    param([Parameter(Mandatory = $true)][string]$SnapshotLabel)

    $config = Get-RecoveryConfig
    $containerDirectory = Get-SnapshotDirectoryInContainer -Label $SnapshotLabel
    $verifyContainerDirectory = Get-SnapshotDirectoryInContainer -Label "verify"

    $restoreOutput = Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:recoveryRestoreScript `
        -Description "scoreboard rollback to '$SnapshotLabel'" -ScriptArguments @($containerDirectory, $config.ScoreboardKeyPattern)
    $pipeSummary = $restoreOutput | Where-Object { $_ -match 'errors:' }
    foreach ($summary in $pipeSummary) {
        if ([string]$summary -notmatch 'errors:\s*0') {
            throw "Rollback to '$SnapshotLabel' reported a failed command: $summary"
        }
    }
    $deleted = 0L
    $restored = 0L
    foreach ($line in $restoreOutput) {
        if ([string]$line -match '^SBRE_RESTORE_DELETED=(\d+)$') { $deleted = [long]$Matches[1] }
        if ([string]$line -match '^SBRE_RESTORE_KEYS=(\d+)$') { $restored = [long]$Matches[1] }
    }

    [void](Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:recoveryCaptureScript `
            -Description "post-rollback verification capture" `
            -ScriptArguments @($config.ScoreboardKeyPattern, $verifyContainerDirectory))
    $compareOutput = Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:recoveryCompareScript `
        -Description "post-rollback verification comparison" `
        -ScriptArguments @($containerDirectory, $verifyContainerDirectory)

    $verifiedKeys = $null
    foreach ($line in $compareOutput) {
        if ([string]$line -match '^SBRE_COMPARE_OK=(\d+)$') { $verifiedKeys = [long]$Matches[1] }
    }
    if ($null -eq $verifiedKeys -or $verifiedKeys -ne $restored) {
        throw "Post-rollback verification did not confirm $restored key(s): $($compareOutput -join ' | ')"
    }

    $checkpoint = Get-RedisText -Key $config.CheckpointKey -Description "checkpoint after rollback"
    return [pscustomobject][ordered]@{
        SnapshotLabel = $SnapshotLabel
        DeletedKeys = $deleted
        RestoredKeys = $restored
        VerifiedKeys = $verifiedKeys
        Checkpoint = $checkpoint
        ObservedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
    }
}

# --- batch-1 ---------------------------------------------------------------------------------------

function Pause-Batch {
    if ($script:recoveryBatchPaused) {
        throw "batch-1 is already paused by this script."
    }
    $config = Get-RecoveryConfig
    [void](Invoke-Docker -Arguments @("pause", $config.BatchContainer))
    $script:recoveryBatchPaused = $true
}

function Resume-Batch {
    if (-not $script:recoveryBatchPaused) {
        return
    }
    $config = Get-RecoveryConfig
    [void](Invoke-Docker -Arguments @("unpause", $config.BatchContainer))
    $script:recoveryBatchPaused = $false
}

function Get-BatchPaused {
    return [bool]$script:recoveryBatchPaused
}

# --- clearing the instance between runs -------------------------------------------------------------

# Between runs the dedicated instance is emptied, because a run has to start from a scoreboard that
# describes only its own contest. Two separate facts decide whether that is allowed, and they are
# checked separately because only one of them is a real gate:
#
#   * the container is this project's `redis` service. That is what "dedicated" means, and it is read
#     from Compose's own labels, so it cannot be true of a shared instance.
#   * the namespaces found are ones this application creates. The four below are the whole of them
#     (scoreboard, submission dedup, submission rate limit, Spring Session), so a fifth is evidence
#     that something else is using the instance - a reason to stop rather than to reason about.
function Clear-RecoveryRedis {
    param([Parameter(Mandatory = $true)][string]$EvidencePath)

    $identity = Assert-RedisIsDedicated
    $before = Get-RedisCensus
    $known = @(
        "^$([regex]::Escape((Get-RecoveryConfig).ScoreboardKeyPrefix))=",
        "^contest:submission:=",
        "^spring:session:=",
        "^\(no prefix\)="
    )
    $unexpected = @($before.PrefixHistogram | Where-Object {
            $prefix = $_
            -not ($known | Where-Object { $prefix -match $_ })
        })
    $record = [pscustomobject][ordered]@{
        identity = $identity
        before = $before
        unexpectedPrefixes = @($unexpected)
        flushed = $false
        after = $null
    }
    if ($unexpected.Count -gt 0) {
        $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
        throw "The pilot redis instance holds namespaces this experiment does not own " +
        "($($unexpected -join ', ')). It is not a dedicated instance; refusing to flush. Evidence: $EvidencePath"
    }

    [void](Invoke-RedisText -RedisArguments @("FLUSHALL"))
    $after = Get-RedisCensus
    $record.flushed = $true
    $record.after = $after
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $EvidencePath -Encoding utf8

    if ($after.Dbsize -ne 0) {
        throw "Redis DBSIZE is $($after.Dbsize) after FLUSHALL."
    }
    return $record
}

# --- set arithmetic on `processed` -----------------------------------------------------------------

# The exact set of results a rollback took away.
#
# The direction is the whole of this function and it is easy to get backwards, so it is named rather than
# commented: the scoreboard only ever gains processed results while it is healthy, so the reading taken
# just before the rollback is a superset of the reading taken at the snapshot. What the rollback erases
# is `PreRollbackMembers` minus `SnapshotMembers` - the results that were applied between the two
# instants and are no longer in the scoreboard. Computing the other difference yields the empty set on
# every healthy run, and an empty lost set makes the recovery look instantaneous and complete.
#
# Both readings are set memberships rather than counts, so the set is named and not merely sized - which
# is what lets the harness ask "has every one of these come back" instead of "are there enough of them
# again".
#
# No offset arithmetic is involved and none would be correct: stream offsets are not consecutive.
function Get-LostResultSet {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$SnapshotMembers,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$PreRollbackMembers
    )

    $snapshot = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($member in $SnapshotMembers) { [void]$snapshot.Add([string]$member) }
    $lost = New-Object 'System.Collections.Generic.List[string]'
    foreach ($member in $PreRollbackMembers) {
        if (-not $snapshot.Contains([string]$member)) {
            $lost.Add([string]$member)
        }
    }
    return [pscustomobject][ordered]@{
        SnapshotCount = $SnapshotMembers.Count
        PreRollbackCount = $PreRollbackMembers.Count
        LostCount = $lost.Count
        Lost = $lost.ToArray()
    }
}

# How much of the lost set the scoreboard holds again, and what it now holds that the fault-time reading
# did not - the second being results that arrived after the rollback, which is the ingress the run was
# supposed to keep applying.
function Get-LostSetProgress {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lost,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$CurrentMembers
    )

    $current = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($member in $CurrentMembers) { [void]$current.Add([string]$member) }
    $reapplied = 0
    foreach ($member in $Lost) {
        if ($current.Contains([string]$member)) { $reapplied++ }
    }
    return [pscustomobject][ordered]@{
        LostCount = $Lost.Count
        ReappliedCount = $reapplied
        Complete = ($reapplied -eq $Lost.Count)
        CurrentCount = $CurrentMembers.Count
    }
}
