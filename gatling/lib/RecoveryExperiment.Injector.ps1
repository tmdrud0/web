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

# The canonical form of a key's content, per type: the part of a key that is *state*, with the layout
# Redis happened to choose taken out. Run on the server so that a set of a thousand members is sorted
# where it already is rather than being shipped to the shell to be sorted there.
#
# It exists because a payload is not always reproducible: `DUMP` of a hash-table-encoded set or hash
# iterates the table's buckets, and a reload rebuilds that table by inserting the members in the order
# they were serialized, which does not always reproduce the original bucket layout. The content survives
# the round trip exactly; the bytes do not. Measured on redis:7-alpine for a set of integer members:
# intset (up to `set-max-intset-entries`, 512) reloads byte-identically, hashtable (513 and up) does not,
# and the same holds for a hash past `hash-max-listpack-entries`. So the canonical form is what decides
# whether the restore reproduced the snapshot's state, and the payload comparison is kept for every
# encoding that *is* reproducible - which is all of them except `hashtable`.
CANON='
local k = KEYS[1]
local t = redis.call("TYPE", k)["ok"]
if t == "string" then
    return redis.call("GET", k)
elseif t == "list" then
    return table.concat(redis.call("LRANGE", k, 0, -1), "\n")
elseif t == "set" then
    local m = redis.call("SMEMBERS", k)
    table.sort(m)
    return table.concat(m, "\n")
elseif t == "hash" or t == "zset" then
    local flat
    if t == "hash" then flat = redis.call("HGETALL", k) else flat = redis.call("ZRANGE", k, 0, -1, "WITHSCORES") end
    local rows = {}
    for i = 1, #flat, 2 do rows[#rows + 1] = flat[i] .. "\t" .. flat[i + 1] end
    table.sort(rows)
    return table.concat(rows, "\n")
end
return nil
'

count=0
while IFS= read -r key; do
    count=$((count + 1))
    name=$(printf '%06d' "$count")
    type=$(redis-cli --raw TYPE "$key")
    enc=$(redis-cli --raw OBJECT ENCODING "$key")
    pttl=$(redis-cli --raw PTTL "$key")
    printf '%s\n' "$type" > "$out/$name.type"
    printf '%s\n' "$enc" > "$out/$name.enc"
    printf '%s\n' "$pttl" > "$out/$name.pttl"
    redis-cli --raw DUMP "$key" > "$out/$name.raw"
    size=$(wc -c < "$out/$name.raw")
    if [ "$size" -lt 1 ]; then
        echo "SBRE_CAPTURE_FAILED empty dump for $key" >&2
        exit 1
    fi
    head -c $((size - 1)) "$out/$name.raw" > "$out/$name.bin"
    rm -f "$out/$name.raw"
    redis-cli --raw EVAL "$CANON" 1 "$key" > "$out/$name.canon.raw"
    csize=$(wc -c < "$out/$name.canon.raw")
    if [ "$csize" -lt 1 ]; then
        # A type the script above does not know, so this key is left to the payload comparison alone.
        # Written as an empty file rather than a sentinel: an empty container of a known type also
        # produces an empty canonical form, and comparing two empties is the right answer for both.
        : > "$out/$name.canon"
    else
        head -c $((csize - 1)) "$out/$name.canon.raw" > "$out/$name.canon"
    fi
    rm -f "$out/$name.canon.raw"
    printf '%s\t%s\t%s\t%s\t%s\n' "$key" "$type" "$pttl" "$name" "$enc" >> "$out/manifest.txt"
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
# Five fields, and the fifth is read even though the restore does not use it: with a shorter list the
# trailing field would be appended to `name`, and `name` is the payload's file name.
while read -r key type pttl name enc; do
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
canonOnly=0
IFS=$(printf '\t')
while read -r key type pttl name enc; do
    if ! cmp -s "$a/$name.type" "$b/$name.type"; then
        echo "SBRE_COMPARE_FAILED type differs for $key" >&2
        diffs=$((diffs + 1))
    fi
    if ! cmp -s "$a/$name.enc" "$b/$name.enc"; then
        echo "SBRE_COMPARE_FAILED encoding differs for $key" >&2
        diffs=$((diffs + 1))
    fi
    if ! cmp -s "$a/$name.canon" "$b/$name.canon"; then
        echo "SBRE_COMPARE_FAILED content differs for $key" >&2
        diffs=$((diffs + 1))
    fi
    # The payload comparison is the stronger one and is asked for wherever it is meaningful. It is not
    # meaningful for `hashtable`: `DUMP` walks the hash table's buckets, so the same membership can
    # serialize in a different order after a reload. Those keys are decided by their content above, and
    # are counted so that a run saying "verified byte for byte" cannot quietly mean "for most of them".
    if [ "$enc" = "hashtable" ]; then
        canonOnly=$((canonOnly + 1))
    elif ! cmp -s "$a/$name.bin" "$b/$name.bin"; then
        echo "SBRE_COMPARE_FAILED payload differs for $key" >&2
        diffs=$((diffs + 1))
    fi
done < "$a/manifest.txt"
if [ "$diffs" -ne 0 ]; then
    exit 1
fi
echo "SBRE_COMPARE_OK=$(wc -l < "$a/manifest.txt")"
echo "SBRE_COMPARE_CANON_ONLY=$canonOnly"
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

# The namespace a key belongs to, as this experiment names it: the text through the *second* colon, or
# through the first when there is only one, or `(no prefix)` when there is none at all.
#
# Two segments, not one, because two is the depth at which this application's namespaces are distinct
# from each other. At one segment `contest:submission:dedup:` and `contest:submission:rate-limit:` both
# name `contest:`, and `spring:session:` names `spring:` - neither of which is a name that the set of
# namespaces this project owns can be compared against. The census and that set are two halves of one
# decision and have to be written at one depth; they were written at two, and the consequence was that
# the census reported the instance as an intruder on itself: a `spring:` bucket tested against a
# `spring:session:` expectation, so `Clear-RecoveryRedis` refused to flush, permanently, from the second
# run onward. Measured on the dedicated instance at the time: 200 keys, every one of them
# `spring:session:`, produced by this project's own app tier.
function Get-RedisKeyNamespace {
    param([Parameter(Mandatory = $true)][string]$Key)

    $text = $Key.Trim()
    $first = $text.IndexOf(":")
    if ($first -le 0) { return "(no prefix)" }
    $second = $text.IndexOf(":", $first + 1)
    if ($second -lt 0) { return $text.Substring(0, $first + 1) }
    return $text.Substring(0, $second + 1)
}

# Every namespace this application creates, written at the depth above and read off the product's own
# key definitions rather than guessed:
#
#   * `contest:scoreboard:`  the standings, the per-user and per-problem hashes, the `processed` set,
#                            and the repository's own `seq`, `stream:offset` and `stream:db-pending` keys
#   * `contest:submission:`  the duplicate registry and the submission rate limiter
#   * `spring:session:`      app-tier HTTP sessions (spring.session.store-type=redis)
#   * `(no prefix)`          a key with no colon carries no namespace to compare against, so it is
#                            allowed rather than refused. This is the list's one weak spot and it is
#                            deliberate: nothing in this application writes such a key - checked
#                            against the product's key definitions and against the census of the live
#                            instance, which held only `spring:session:` - but a colon-less key also
#                            cannot be attributed to an owner, so its presence is not evidence that the
#                            instance is someone else's. Stated here rather than left for a reader of
#                            the refusal message to discover.
#
# `contest:scoreboard:` is listed even though `Get-RedisCensus` counts those keys separately and never
# puts them in the histogram: the point of the list is to be the complete set of namespaces the
# application owns, not just the reachable part of it.
#
# This list is not what makes the instance safe to flush - that is `Assert-RedisIsDedicated`, which
# reads Compose's own project label and so cannot be satisfied by a shared instance. What the list adds
# is the second question, which the label cannot answer: an instance that is ours may still have been
# used for something else.
function Get-ProjectRedisNamespaces {
    return @(
        "contest:scoreboard:",
        "contest:submission:",
        "spring:session:",
        "(no prefix)"
    )
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
        # Two views of one computation rather than two computations: `Namespaces` is what the decision
        # reads (a name to compare, not a string to parse), `PrefixHistogram` is the same data as
        # `name=count` for whoever reads the evidence file. Derived from `Namespaces` below, so the two
        # cannot disagree.
        Namespaces = @()
        PrefixHistogram = @()
    }
    $census["Dbsize"] = Get-RedisInt64 -RedisArguments @("DBSIZE")
    # The `Where-Object` is applied to the array rather than to the pipeline, and that distinction is the
    # whole of the line. A pipeline that emits nothing assigns `$null`, and `$null.Count` is an error under
    # StrictMode - which is precisely the state this function is called in after `FLUSHALL`: the empty
    # instance is the *successful* reset, so the unguarded version failed the run it had just cleared.
    $keys = @(@(Invoke-RedisText -RedisArguments @("--scan")) |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $histogram = [ordered]@{}
    $scoreboard = 0
    foreach ($key in $keys) {
        $text = ([string]$key).Trim()
        if ($text.StartsWith($config.ScoreboardKeyPrefix)) {
            $scoreboard++
            continue
        }
        $prefix = Get-RedisKeyNamespace -Key $text
        if (-not $histogram.Contains($prefix)) { $histogram[$prefix] = 0 }
        $histogram[$prefix] = $histogram[$prefix] + 1
    }
    $census["ScoreboardKeys"] = $scoreboard
    $census["OtherKeys"] = $keys.Count - $scoreboard
    $census["Namespaces"] = @($histogram.Keys | Sort-Object | ForEach-Object {
            [pscustomobject][ordered]@{ name = $_; count = $histogram[$_] }
        })
    $census["PrefixHistogram"] = @($census["Namespaces"] | ForEach-Object { "$($_.name)=$($_.count)" })
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
    $encodingHistogram = @{}
    $payloadBytes = 0L
    foreach ($line in $manifest) {
        $fields = @(([string]$line -split "`t", -1))
        if ($fields.Count -lt 4) { continue }
        if (-not $typeHistogram.ContainsKey($fields[1])) { $typeHistogram[$fields[1]] = 0 }
        $typeHistogram[$fields[1]] = $typeHistogram[$fields[1]] + 1
        # The encoding decides which comparison the rollback verification will hold this key to, so the
        # snapshot records the distribution of them rather than only the types.
        if ($fields.Count -ge 5) {
            if (-not $encodingHistogram.ContainsKey($fields[4])) { $encodingHistogram[$fields[4]] = 0 }
            $encodingHistogram[$fields[4]] = $encodingHistogram[$fields[4]] + 1
        }
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
        EncodingHistogram = @($encodingHistogram.Keys | Sort-Object | ForEach-Object { "$_=$($encodingHistogram[$_])" })
        ProcessedCount = $processed.Count
        ProcessedPath = $processedPath
        Processed = $processed
        Checkpoint = $checkpoint
        HostDirectory = $hostDirectory
        ContainerDirectory = $containerDirectory
    }
}

# The rollback itself: delete the namespace, write the snapshot back, and prove it took by capturing
# the namespace again and comparing it with the snapshot - content for every key, and payload bytes for
# every key whose encoding Redis reproduces across a reload (`SBRE_COMPARE_CANON_ONLY` counts the ones
# it does not, so the evidence says which comparison each key was held to).
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
    $canonicalOnlyKeys = 0L
    foreach ($line in $compareOutput) {
        if ([string]$line -match '^SBRE_COMPARE_OK=(\d+)$') { $verifiedKeys = [long]$Matches[1] }
        if ([string]$line -match '^SBRE_COMPARE_CANON_ONLY=(\d+)$') { $canonicalOnlyKeys = [long]$Matches[1] }
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
        CanonicalOnlyKeys = $canonicalOnlyKeys
        Checkpoint = $checkpoint
        ObservedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
    }
}

# --- batch-1 ---------------------------------------------------------------------------------------

# Declared here rather than left to the first `Pause-Batch`, because "not paused" is the true state before
# anything runs and the failure path calls `Resume-Batch` unconditionally: on a run that died before it got
# as far as pausing, reading an undeclared variable is an error under StrictMode, so the cleanup path threw
# a second exception over the top of the one that had actually stopped the run.
$script:recoveryBatchPaused = $false

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
#   * the namespaces found are ones this application creates. The whole of them is
#     `Get-ProjectRedisNamespaces`, so a namespace outside it is evidence that something else is using
#     the instance - a reason to stop rather than to reason about.
#
# The comparison is by name and not by pattern. The names come from `Get-RedisKeyNamespace`, so there
# is nothing to escape and no depth for the two sides to disagree about; the earlier version built
# regexes by interpolating the scoreboard prefix and hand-writing the rest, which is how a one-segment
# census came to be tested against two-segment patterns.
function Clear-RecoveryRedis {
    param([Parameter(Mandatory = $true)][string]$EvidencePath)

    $identity = Assert-RedisIsDedicated
    $before = Get-RedisCensus
    $known = Get-ProjectRedisNamespaces
    $unexpected = @($before.Namespaces | Where-Object { $known -notcontains $_.name })
    $record = [pscustomobject][ordered]@{
        identity = $identity
        before = $before
        unexpectedNamespaces = @($unexpected | ForEach-Object { "$($_.name)=$($_.count)" })
        flushed = $false
        after = $null
    }
    if ($unexpected.Count -gt 0) {
        $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
        throw "The pilot redis instance holds namespaces this experiment does not own " +
        "($($record.unexpectedNamespaces -join ', ')). It is not a dedicated instance; refusing to flush. Evidence: $EvidencePath"
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

# How much of the lost set the scoreboard's `processed` set holds again, and what it now holds that the
# fault-time reading did not - the second being results that arrived after the rollback, which is the
# ingress the run was supposed to keep applying.
#
# "Back" means back in `processed`, which is not the same as back in the standings. The product adds a
# submission to `processed` outside the guard that decides whether its result moves anything, so a
# delivery whose result is still PENDING marks the submission processed without touching a rank. A
# `Complete = $true` here therefore says every lost submission has been delivered again - not that the
# scoreboard is right. That is why the caller's predicate is a conjunction of this and the digest match
# against MySQL, and why a report should never read this count as the scoreboard's own recovery.
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

# --- short-pause injector (live-impact experiment) --------------------------------------------------
#
# An option next to the injector above, not a replacement for it: nothing above calls anything below,
# and the recovery pilot keeps its key-by-key capture. This variant exists because that capture walks
# the namespace through one `docker exec` per key, which held batch-1 frozen for 32-47 s per pause in the
# pilot - for a live-impact measurement the injector would be most of what was measured.
#
# What changes is where the work happens and how much of it is inside the pause:
#
#   * Scope: one contest's keys (`contest:scoreboard:<contestId>:*`) plus the global keys a rollback of
#     that contest has to move with it - the stream checkpoint, its db-pending mark and the two sequence
#     allocators. Other contests' keys are not touched. The global keys are shared by every contest, so a
#     live-impact run assumes the experiment contest is the only one being written, which the runner checks.
#   * Snapshot: one Lua script copies every key in scope into a run-scoped shadow namespace
#     (`sbrec:snap:<runId>:<label>:`) with `COPY`, server-side. Nothing binary leaves Redis.
#   * Rollback: one Lua script deletes the keys in scope and copies the shadow back, then compares each
#     restored key with its shadow by type and cardinality. `COPY` duplicates the value, so this is the
#     content check; the byte-level payload check of the pilot injector is not repeated (see its §5.4 for
#     why bytes are not reproducible for `hashtable` encodings anyway).
#   * Inside the pause: the Lua script and one `SMEMBERS` of the contest's processed set to a file in the
#     container. MySQL is never read inside the pause; the caller reads it before or after.
#
# A Lua script blocks every Redis client while it runs, not only batch-1. That is the cost of making
# the snapshot one instant, and it is measured: the scripts report Redis's own TIME and the caller
# records both the pause and the script's wall time.

function Get-ShortPauseSnapshotPrefix {
    param([Parameter(Mandatory = $true)][string]$Label)

    $config = Get-RecoveryConfig
    if ($Label -notmatch '^[A-Za-z0-9_-]+$') {
        throw "Snapshot label '$Label' must be alphanumeric, underscore or hyphen: it becomes a Redis key prefix."
    }
    return "sbrec:snap:$($config.RunId):${Label}:"
}

# The global keys a contest rollback carries with it. Read from the product's key definitions
# (ContestScoreboardRedisKeys), not guessed: STREAM_OFFSET, STREAM_DB_PENDING, SEQUENCE, SUBMISSION_SEQUENCE.
function Get-ShortPauseGlobalKeys {
    $prefix = (Get-RecoveryConfig).ScoreboardKeyPrefix
    return @(
        "${prefix}stream:offset",
        "${prefix}stream:db-pending",
        "${prefix}seq",
        "${prefix}submission-seq"
    )
}

function Get-ShortPauseContestPattern {
    $config = Get-RecoveryConfig
    if (-not $config.ContestScopeFromSeed) {
        throw "Refusing a contest-scoped Redis operation before a seed has set the contest scope."
    }
    return "$($config.ScoreboardKeyPrefix)$($config.ContestId):*"
}

# ARGV: pattern, shadow prefix, checkpoint key, then the global keys.
$script:shortPauseSnapshotLua = @'
local t = redis.call("TIME")
local pattern, shadow, checkpointKey = ARGV[1], ARGV[2], ARGV[3]
if #redis.call("KEYS", shadow .. "*") > 0 then
    return redis.error_reply("SBRE shadow namespace " .. shadow .. " is not empty")
end
local copied = 0
for _, key in ipairs(redis.call("KEYS", pattern)) do
    redis.call("COPY", key, shadow .. key)
    copied = copied + 1
end
local globals = 0
for i = 4, #ARGV do
    if redis.call("EXISTS", ARGV[i]) == 1 then
        redis.call("COPY", ARGV[i], shadow .. ARGV[i])
        globals = globals + 1
    end
end
local checkpoint = redis.call("GET", checkpointKey)
return {t[1], t[2], copied, globals, checkpoint or "absent"}
'@

# ARGV: pattern, shadow prefix, checkpoint key, then the global keys.
$script:shortPauseRollbackLua = @'
local t = redis.call("TIME")
local pattern, shadow, checkpointKey = ARGV[1], ARGV[2], ARGV[3]
local saved = redis.call("KEYS", shadow .. "*")
if #saved == 0 then
    return redis.error_reply("SBRE shadow namespace " .. shadow .. " is empty")
end
local before = redis.call("GET", checkpointKey)
local deleted = 0
for _, key in ipairs(redis.call("KEYS", pattern)) do
    deleted = deleted + redis.call("DEL", key)
end
for i = 4, #ARGV do
    deleted = deleted + redis.call("DEL", ARGV[i])
end
local function size(key, kind)
    if kind == "string" then return redis.call("STRLEN", key)
    elseif kind == "set" then return redis.call("SCARD", key)
    elseif kind == "hash" then return redis.call("HLEN", key)
    elseif kind == "zset" then return redis.call("ZCARD", key)
    elseif kind == "list" then return redis.call("LLEN", key)
    end
    return -1
end
local restored, mismatched = 0, 0
local offset = #shadow + 1
for _, shadowKey in ipairs(saved) do
    local key = string.sub(shadowKey, offset)
    redis.call("COPY", shadowKey, key)
    restored = restored + 1
    local kind = redis.call("TYPE", shadowKey)["ok"]
    if redis.call("TYPE", key)["ok"] ~= kind or size(key, kind) ~= size(shadowKey, kind) then
        mismatched = mismatched + 1
    end
end
local after = redis.call("GET", checkpointKey)
return {t[1], t[2], before or "absent", after or "absent", deleted, restored, mismatched}
'@

# ARGV: pattern, batch size. Deletes in bounded batches so one call cannot hold Redis for the whole
# namespace. One line and no double quotes, because it is passed as a `docker exec` argument and Windows
# PowerShell 5.1 does not escape embedded double quotes for native commands.
$script:shortPauseDeleteLua = "local keys = redis.call('KEYS', ARGV[1]); local limit = tonumber(ARGV[2]); local deleted = 0; for i = 1, math.min(#keys, limit) do deleted = deleted + redis.call('UNLINK', keys[i]) end; return {deleted, #keys - deleted}"

# Runs one Lua script and one processed-set read inside the container, in one `docker exec`, so the
# pause the caller holds is one round trip rather than several. Output markers are parsed below.
$script:shortPauseRunScript = @'
set -e
dir="$1"; lua="$2"; processedKey="$3"; processedFile="$4"
shift 4
mkdir -p "$dir"
# Read first: for a rollback this is the processed set just before it, which defines the lost set, and
# for a snapshot the order does not matter because batch-1 is paused.
redis-cli --raw SMEMBERS "$processedKey" | grep -v '^$' > "$dir/$processedFile" || true
before=$(redis-cli --raw TIME | tr '\n' ' ')
status=0
redis-cli --raw EVAL "$(cat "$lua")" 0 "$@" > "$dir/eval.out" 2>&1 || status=$?
after=$(redis-cli --raw TIME | tr '\n' ' ')
if [ "$status" -ne 0 ] || grep -q -E '^(ERR|SBRE |WRONGTYPE|OOM|NOSCRIPT|BUSY)' "$dir/eval.out"; then
    echo "SBRE_EVAL_FAILED $(tr '\n' ' ' < "$dir/eval.out")" >&2
    exit 1
fi
echo "SBRE_TIME_BEFORE=$before"
echo "SBRE_TIME_AFTER=$after"
echo "SBRE_EVAL=$(tr '\n' '|' < "$dir/eval.out")"
echo "SBRE_PROCESSED=$(wc -l < "$dir/$processedFile")"
'@

function ConvertFrom-RedisTimePair {
    param([Parameter(Mandatory = $true)][string]$Text)

    $parts = @(([string]$Text).Trim() -split '\s+' | Where-Object { $_ -ne "" })
    if ($parts.Count -lt 2) {
        throw "Unreadable Redis TIME '$Text'."
    }
    return ([long]$parts[0]) * 1000L + [long][math]::Floor([long]$parts[1] / 1000.0)
}

function Invoke-ShortPauseScript {
    param(
        [Parameter(Mandatory = $true)][string]$Lua,
        [Parameter(Mandatory = $true)][string]$ProcessedFile,
        [Parameter(Mandatory = $true)][string[]]$LuaArguments,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $config = Get-RecoveryConfig
    $dir = "/tmp/sbrec-live-$($config.RunId)"
    $luaLocal = Join-Path ([IO.Path]::GetTempPath()) ("sbrec-" + [Guid]::NewGuid().ToString("N") + ".lua")
    try {
        [IO.File]::WriteAllText($luaLocal, ($Lua -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
        [void](Invoke-Docker -Arguments @("exec", $config.RedisContainer, "mkdir", "-p", $dir))
        [void](Invoke-Docker -Arguments @("cp", $luaLocal, "$($config.RedisContainer):$dir/script.lua"))
    }
    finally {
        Remove-Item -LiteralPath $luaLocal -Force -ErrorAction SilentlyContinue
    }
    $output = Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:shortPauseRunScript `
        -Description $Description -ScriptArguments (@($dir, "$dir/script.lua", $config.ProcessedKey, $ProcessedFile) + $LuaArguments)
    $values = @{}
    foreach ($line in $output) {
        if ([string]$line -match '^(SBRE_[A-Z_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    foreach ($required in @("SBRE_TIME_BEFORE", "SBRE_TIME_AFTER", "SBRE_EVAL", "SBRE_PROCESSED")) {
        if (-not $values.ContainsKey($required)) {
            throw "$Description did not report $required`: $($output -join ' | ')"
        }
    }
    return [pscustomobject][ordered]@{
        ContainerDirectory = $dir
        EvalStartMs = ConvertFrom-RedisTimePair -Text $values["SBRE_TIME_BEFORE"]
        EvalEndMs = ConvertFrom-RedisTimePair -Text $values["SBRE_TIME_AFTER"]
        Eval = @(([string]$values["SBRE_EVAL"]).TrimEnd('|') -split '\|', -1)
        ProcessedCount = [long]$values["SBRE_PROCESSED"]
        ProcessedPath = "$dir/$ProcessedFile"
    }
}

# Redis refuses writes at maxmemory under `noeviction`, which is how the stack runs it - a shadow copy
# that crossed the limit would fail the application's own writes, not the snapshot. Checked first.
function Assert-ShortPauseMemoryHeadroom {
    $info = @(Invoke-RedisText -RedisArguments @("INFO", "memory"))
    $used = $null
    $max = $null
    foreach ($line in $info) {
        if ([string]$line -match '^used_memory:(\d+)') { $used = [long]$Matches[1] }
        if ([string]$line -match '^maxmemory:(\d+)') { $max = [long]$Matches[1] }
    }
    if ($null -eq $used -or $null -eq $max) {
        throw "Could not read used_memory/maxmemory from INFO memory."
    }
    $pattern = Get-ShortPauseContestPattern
    $sample = [pscustomobject][ordered]@{ usedMemory = $used; maxMemory = $max; pattern = $pattern }
    # The shadow copy is at most the size of the keys in scope, which is at most everything in use.
    if ($max -gt 0 -and ($used * 2L + 64MB) -gt $max) {
        throw ("Redis uses $used of $max bytes; a shadow copy of the contest could reach maxmemory, where " +
            "noeviction refuses the application's own writes. Raise maxmemory or shrink the seed.")
    }
    return $sample
}

# Caller holds the batch-1 pause. Copies the contest's keys and the global keys into the shadow namespace
# and reads the processed set into a file, and nothing else.
function Export-ContestScoreboardShortPauseSnapshot {
    param([Parameter(Mandatory = $true)][string]$Label)

    $config = Get-RecoveryConfig
    $shadow = Get-ShortPauseSnapshotPrefix -Label $Label
    $run = Invoke-ShortPauseScript -Lua $script:shortPauseSnapshotLua -ProcessedFile "processed-$Label.txt" `
        -LuaArguments (@((Get-ShortPauseContestPattern), $shadow, $config.CheckpointKey) + (Get-ShortPauseGlobalKeys)) `
        -Description "short-pause snapshot '$Label'"
    if ($run.Eval.Count -lt 5) {
        throw "Snapshot '$Label' returned $($run.Eval.Count) value(s): $($run.Eval -join ' ')"
    }
    return [pscustomobject][ordered]@{
        Label = $Label
        ShadowPrefix = $shadow
        RedisTimeMs = ([long]$run.Eval[0]) * 1000L + [long][math]::Floor([long]$run.Eval[1] / 1000.0)
        EvalStartMs = $run.EvalStartMs
        EvalEndMs = $run.EvalEndMs
        ContestKeys = [long]$run.Eval[2]
        GlobalKeys = [long]$run.Eval[3]
        Checkpoint = [string]$run.Eval[4]
        ProcessedCount = $run.ProcessedCount
        ProcessedPath = $run.ProcessedPath
    }
}

# Caller holds the batch-1 pause. Reads the processed set as it is just before the rollback, then puts
# the shadow back. `PreRollbackCheckpoint` is H: the highest stream offset the scoreboard held when it
# was rolled back, which is what the summarizer calls a delivery "new" against.
function Invoke-ContestScoreboardShortPauseRollback {
    param([Parameter(Mandatory = $true)][string]$Label)

    $config = Get-RecoveryConfig
    $shadow = Get-ShortPauseSnapshotPrefix -Label $Label
    $run = Invoke-ShortPauseScript -Lua $script:shortPauseRollbackLua -ProcessedFile "processed-prerollback.txt" `
        -LuaArguments (@((Get-ShortPauseContestPattern), $shadow, $config.CheckpointKey) + (Get-ShortPauseGlobalKeys)) `
        -Description "short-pause rollback to '$Label'"
    if ($run.Eval.Count -lt 7) {
        throw "Rollback to '$Label' returned $($run.Eval.Count) value(s): $($run.Eval -join ' ')"
    }
    $mismatched = [long]$run.Eval[6]
    if ($mismatched -ne 0) {
        throw "Rollback to '$Label' restored $mismatched key(s) whose type or size differs from the snapshot."
    }
    return [pscustomobject][ordered]@{
        Label = $Label
        RedisTimeMs = ([long]$run.Eval[0]) * 1000L + [long][math]::Floor([long]$run.Eval[1] / 1000.0)
        EvalStartMs = $run.EvalStartMs
        EvalEndMs = $run.EvalEndMs
        PreRollbackCheckpoint = [string]$run.Eval[2]
        RestoredCheckpoint = [string]$run.Eval[3]
        DeletedKeys = [long]$run.Eval[4]
        RestoredKeys = [long]$run.Eval[5]
        MismatchedKeys = $mismatched
        PreRollbackProcessedCount = $run.ProcessedCount
        PreRollbackProcessedPath = $run.ProcessedPath
    }
}

# After the pause: the lost set L = pre-rollback processed - snapshot processed, computed in the
# container (the same direction as Get-LostResultSet, over files rather than PowerShell arrays), and
# loaded into a run-scoped set the tail poller intersects with the processed set.
$script:shortPauseLostSetScript = @'
set -e
dir="$1"; snapshotFile="$2"; preFile="$3"; lostKey="$4"
awk 'NR==FNR { seen[$0] = 1; next } !($0 in seen)' "$dir/$snapshotFile" "$dir/$preFile" > "$dir/lost.txt"
redis-cli --raw DEL "$lostKey" > /dev/null
if [ -s "$dir/lost.txt" ]; then
    sed "s/^/SADD $lostKey /" "$dir/lost.txt" | redis-cli > /dev/null
fi
echo "SBRE_LOST=$(wc -l < "$dir/lost.txt")"
echo "SBRE_LOST_KEY_CARD=$(redis-cli --raw SCARD "$lostKey")"
'@

function Get-ShortPauseLostKey {
    return "sbrec:lost:$((Get-RecoveryConfig).RunId)"
}

function New-ShortPauseLostSet {
    param(
        [Parameter(Mandatory = $true)]$Snapshot,
        [Parameter(Mandatory = $true)]$Rollback
    )

    $config = Get-RecoveryConfig
    $dir = "/tmp/sbrec-live-$($config.RunId)"
    $lostKey = Get-ShortPauseLostKey
    $output = Invoke-ContainerScript -Container $config.RedisContainer -ScriptText $script:shortPauseLostSetScript `
        -Description "lost set" -ScriptArguments @($dir, (Split-Path -Leaf $Snapshot.ProcessedPath),
        (Split-Path -Leaf $Rollback.PreRollbackProcessedPath), $lostKey)
    $lost = $null
    $card = $null
    foreach ($line in $output) {
        if ([string]$line -match '^SBRE_LOST=(\d+)') { $lost = [long]$Matches[1] }
        if ([string]$line -match '^SBRE_LOST_KEY_CARD=(\d+)') { $card = [long]$Matches[1] }
    }
    if ($null -eq $lost -or $null -eq $card -or $lost -ne $card) {
        throw "The lost set did not load: $($output -join ' | ')"
    }
    return [pscustomobject][ordered]@{
        LostCount = $lost
        LostKey = $lostKey
        LostPath = "$dir/lost.txt"
    }
}

# Deletes keys matching one run-owned pattern, in bounded batches. Refuses any pattern outside the two
# namespaces a live-impact run owns: its own contest's scoreboard and its own `sbrec:*:<runId>` keys.
function Remove-ShortPauseKeys {
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [int]$BatchSize = 5000
    )

    $config = Get-RecoveryConfig
    $allowed = @(
        "sbrec:snap:$($config.RunId):*",
        "sbrec:lost:$($config.RunId)"
    )
    if ($config.ContestScopeFromSeed) {
        $allowed += "$($config.ScoreboardKeyPrefix)$($config.ContestId):*"
    }
    if ($allowed -notcontains $Pattern) {
        throw "Refusing to delete Redis keys matching '$Pattern': not a namespace this run owns ($($allowed -join ', '))."
    }
    $total = 0L
    for ($round = 0; $round -lt 10000; $round++) {
        $result = @(Invoke-RedisText -RedisArguments @("EVAL", $script:shortPauseDeleteLua, "0", $Pattern, [string]$BatchSize))
        $values = @($result | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
        if ($values.Count -lt 2) {
            throw "Deleting '$Pattern' returned '$($result -join ' ')'."
        }
        $total += [long]$values[0]
        if ([long]$values[1] -le 0L) {
            return $total
        }
    }
    throw "Deleting '$Pattern' did not finish."
}
