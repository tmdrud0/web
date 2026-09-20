# Integration test for the rollback injector, against a throwaway Redis container.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.RedisTests.ps1
#
# The injector is the only part of this harness that writes to Redis, and it is the part whose failure
# mode is silent: a restore that drops one byte produces a scoreboard that is wrong in a way no counter
# reports. So it is tested against a real server rather than a mock, and the test is written to fail on
# exactly the things that would otherwise be discovered as an unexplained mismatch in a run report:
#
#   * a payload whose last byte is 0x0A. `redis-cli --raw DUMP` appends a newline to the payload, so the
#     transport has to trim exactly one byte and no more - and the payload that ends in 0x0A is the case
#     where a trim-one-byte-too-many and a trim-nothing implementation differ. Which value has such a
#     payload depends on its trailing CRC, so it is searched for before the snapshot rather than assumed.
#   * the boundary of the rollback. A key created after the snapshot must be gone and a key outside the
#     scoreboard namespace must be untouched - the first is what makes the fault real, the second is what
#     keeps the injector from being the fault.
#   * a TTL. A key restored without its expiry is a second, quieter fault.
#   * the direction of the loss. The scoreboard gains results between the snapshot and the rollback, so
#     the reading taken just before the rollback is the larger set; what the rollback erases is that set
#     minus the snapshot's. Reversed, every run would report that nothing was lost.
#
# The throwaway container wears this project's Compose labels on purpose. `Assert-RedisIsDedicated`
# decides whether a container is this project's redis service from those labels, so the test is only
# faithful if that gate is exercised - and the labels are exactly the kind of property a shared instance
# does not have by accident.
#
# What it needs: docker, and either the pilot stack running or the compose file readable (the image comes
# from whichever is available, because DUMP payloads are version tagged). No MySQL, no RabbitMQ, no app.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\..\lib\RecoveryExperiment.ps1"
. "$PSScriptRoot\RecoveryExperiment.TestHarness.ps1"

# Fixtures the cases share. They live in the script scope because `Test-Case` runs its body with `&`,
# which is a child scope: a plain assignment inside a case would be discarded when the case returns.
$script:snapshot = $null
$script:lost = $null
$script:newlineValue = $null

$root = (Get-Item "$PSScriptRoot\..\..").FullName
$workDirectory = Join-Path ([IO.Path]::GetTempPath()) ("sbrec-redistest-" + [Guid]::NewGuid().ToString("N"))
$containerName = "sbrec-redis-test"
$containerStarted = $false

# The namespace the seed below creates: 40 probe keys, the key the 0x0A search leaves behind, and one key
# for each shape the injector has to carry - a ranking, a processed set, two summary hashes, the
# checkpoint, a key with an expiry and a value large enough to leave the short-string encoding. Plus one
# key from another namespace, which the injector must not touch.
#
# `Get-RedisSetMembers` returns its array wrapped with `,` so that PowerShell does not unroll it, so
# `@(Get-RedisSetMembers ...)` would produce a one-element array holding the set. Call it bare.
$probeCount = 40
$expectedScoreboardKeys = $probeCount + 8
$expectedDbsize = $expectedScoreboardKeys + 1

function Get-PilotRedisImage {
    $existing = @(Invoke-NativeCommand -Executable "docker" -Arguments @("ps", "-a", "--filter", "name=^/$containerName$", "--format", "{{.Names}}"))
    if ($existing.Count -gt 0) {
        throw "A container named '$containerName' already exists. Remove it before running this test."
    }
    $pilot = @(Invoke-NativeCommand -Executable "docker" -Arguments @("ps", "-a", "--filter", "name=^/oj-loadtest-redis$", "--format", "{{.Names}}"))
    if ($pilot.Count -gt 0) {
        $image = ((Invoke-NativeCommand -Executable "docker" -Arguments @("inspect", "oj-loadtest-redis", "--format", "{{.Config.Image}}")) -join "").Trim()
        if (-not [string]::IsNullOrWhiteSpace($image)) { return $image }
    }
    # No pilot stack: take the image the compose file declares, so the test uses the same server version
    # the experiment will. Payloads are serialized per version and the 0x0A search depends on the bytes.
    $json = @(Invoke-NativeCommand -Executable "docker" -Arguments @(
            "compose", "--project-directory", $root, "-f", "compose.yaml", "config", "--format", "json")) -join "`n"
    $image = [string](($json | ConvertFrom-Json).services.redis.image)
    if ([string]::IsNullOrWhiteSpace($image)) {
        throw "The compose file declares no redis image and no pilot container is running."
    }
    return $image
}

try {
    $image = Get-PilotRedisImage
    Write-Output "Rollback injector integration test against image '$image'."
    [void](New-Item -ItemType Directory -Path $workDirectory -Force)
    [void](Invoke-NativeCommand -Executable "docker" -Arguments @(
            "run", "-d", "--name", $containerName,
            "--label", "com.docker.compose.project=oj-loadtest",
            "--label", "com.docker.compose.service=redis",
            $image))
    $containerStarted = $true

    # DbPassword is never used by this test - the injector reads no database - but initialization
    # requires a non-empty one, so that a caller cannot silently run the experiment without a password.
    [void](Initialize-RecoveryExperiment -WorktreeRoot $root -ArtifactDirectory $workDirectory `
            -RunId "redistest" -Mode "stream-offset" -DbPassword "unused-by-this-test" `
            -RedisContainer $containerName)
    $config = Get-RecoveryConfig

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    $ready = $false
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $pong = @(Invoke-RedisText -RedisArguments @("PING")) | Select-Object -Last 1
        if ([string]$pong -eq "PONG") { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "The throwaway Redis container did not answer PING within 60 seconds." }

    $seedOutput = @(Invoke-ContainerScript -Container $containerName -Description "seed throwaway scoreboard" -ScriptText @'
set -e
i=1
while [ "$i" -le 40 ]; do
    redis-cli SET "contest:scoreboard:probe:v$i" "v$i" > /dev/null
    i=$((i + 1))
done
i=1
while [ "$i" -le 60 ]; do
    redis-cli ZADD "contest:scoreboard:1:ranking" $((3000000000 - i)) "$i" > /dev/null
    i=$((i + 1))
done
i=1
while [ "$i" -le 50 ]; do
    redis-cli SADD "contest:scoreboard:1:processed" "$i" > /dev/null
    i=$((i + 1))
done
redis-cli HSET "contest:scoreboard:1:u:10" solved 3 penalty 145 > /dev/null
redis-cli HSET "contest:scoreboard:1:p:1" accepted 1 wrong 2 > /dev/null
redis-cli SET "contest:scoreboard:stream:offset" 100 > /dev/null
redis-cli SETEX "contest:scoreboard:tmp:window" 600 "still here" > /dev/null
redis-cli SET "contest:scoreboard:big:payload" "$(head -c 1536 /dev/urandom | base64 | tr -d '\n')" > /dev/null
redis-cli SET "spring:session:s1" "untouched" > /dev/null
echo SEEDED
'@)
    Assert-True (@($seedOutput | Where-Object { [string]$_ -match 'SEEDED' }).Count -eq 1) "the throwaway namespace was seeded"

    # A value whose DUMP payload ends in 0x0A. The payload depends on the value's trailing CRC64, so the
    # search is over values rather than over keys, and it keeps one key: 256 tries on average, and the
    # bound of 4000 makes a miss a wrong-image problem rather than bad luck (P(miss) is about 1e-7).
    $probeOutput = @(Invoke-ContainerScript -Container $containerName -Description "find a payload ending in 0x0A" -ScriptText @'
set -e
key="contest:scoreboard:probe:newline"
i=1
while [ "$i" -le 4000 ]; do
    redis-cli SET "$key" "v$i" > /dev/null
    redis-cli --raw DUMP "$key" > /tmp/sbrec-probe.raw
    size=$(wc -c < /tmp/sbrec-probe.raw)
    if [ "$size" -ge 1 ]; then
        last=$(head -c $((size - 1)) /tmp/sbrec-probe.raw | tail -c 1 | od -An -tu1 | tr -d ' \n')
        if [ "$last" = "10" ]; then
            echo "SBRE_PROBE_FOUND=$i"
            exit 0
        fi
    fi
    i=$((i + 1))
done
echo "SBRE_PROBE_MISS"
'@)
    # A foreach rather than a -match inside Where-Object: $Matches is set in the scope the operator runs
    # in, and a Where-Object body is a child scope whose automatic variables do not survive.
    $probeValue = $null
    foreach ($line in $probeOutput) {
        if ([string]$line -match '^SBRE_PROBE_FOUND=(\d+)$') { $probeValue = [int]$Matches[1] }
    }
    if ($null -eq $probeValue) {
        throw "No value under 4000 has a DUMP payload ending in 0x0A, so the transport's hardest case cannot be tested: $($probeOutput -join ' | ')"
    }
    $script:newlineValue = "v$probeValue"
    $newlineKey = "contest:scoreboard:probe:newline"
    Write-Output "       payload ending in 0x0A: $newlineKey = '$($script:newlineValue)'"
    Assert-Equal $script:newlineValue (Get-RedisText -Key $newlineKey -Description "0x0A candidate") "the search left its key holding the value it found"

    Test-Case "Assert-RedisIsDedicated reads the Compose labels" {
        $identity = Assert-RedisIsDedicated
        Assert-Equal "oj-loadtest" $identity.project "the project label is read"
        Assert-Equal "redis" $identity.service "the service label is read"
    }

    Test-Case "the census separates the scoreboard namespace from everything else" {
        $census = Get-RedisCensus
        Assert-Equal $expectedScoreboardKeys $census.ScoreboardKeys "the scoreboard namespace is counted"
        Assert-Equal $expectedDbsize $census.Dbsize "the instance holds the seeded keys and nothing else"
        Assert-Equal 1 $census.OtherKeys "the session key is counted as another namespace"
        # The exact bucket, not `-like "spring:*"`. That pattern is satisfied by `spring:` as well as by
        # `spring:session:`, so it held at either depth and pinned neither - which is how the census came
        # to bucket at one segment while `Clear-RecoveryRedis` compared against two, refusing to flush on
        # the instance's own data. The census and the project's namespace list are two halves of one
        # comparison, so the test that keeps them together has to name the depth they share.
        $sessionNamespace = @($census.Namespaces | Where-Object { $_.name -eq "spring:session:" })
        Assert-Equal 1 $sessionNamespace.Count "the session key is named at the depth the known list is written at"
        Assert-Equal 1 $sessionNamespace[0].count "the one session key is counted under it"
        # And the property the reset depends on: the census reports nothing outside the project's own
        # namespaces, so `Clear-RecoveryRedis` would not refuse. The histogram is the thing read, not the
        # text rendering of it, so this asserts the same field the refusal reads.
        $known = Get-ProjectRedisNamespaces
        Assert-Equal 0 @($census.Namespaces | Where-Object { $known -notcontains $_.name }).Count "every namespace in the census is one this project owns"
        Assert-Equal @($census.Namespaces | ForEach-Object { "$($_.name)=$($_.count)" }) $census.PrefixHistogram "the human-readable histogram is derived from the same data"
    }

    Test-Case "the snapshot captures every key with its type, payload and expiry" {
        $script:snapshot = Export-ScoreboardSnapshot -Label "k" -ObservedAtMysql "unavailable"
        $snapshot = $script:snapshot
        Assert-Equal $expectedScoreboardKeys $snapshot.KeyCount "every scoreboard key is captured"
        # The large value is base64 of random bytes rather than a repeated character, because Redis
        # compresses a payload it can - a 2 KB string of 'x' dumps to about thirty bytes and would leave
        # the long-payload path in the transport untested while still looking like a large value.
        $largestPayload = (Get-ChildItem -Path (Join-Path $snapshot.HostDirectory "*.bin") |
                Measure-Object -Property Length -Maximum).Maximum
        Assert-True ($largestPayload -gt 2000) "one payload is large enough to exercise the long-payload path ($largestPayload bytes)"
        Assert-True ($snapshot.PayloadBytes -gt $largestPayload) "the payload bytes are counted across every key ($($snapshot.PayloadBytes))"
        Assert-Equal 100 $snapshot.Checkpoint "the checkpoint is captured"
        Assert-Equal 50 $snapshot.ProcessedCount "the processed set is captured"
        Assert-True (@($snapshot.TypeHistogram | Where-Object { [string]$_ -like "zset=*" }).Count -eq 1) "the ranking is captured as a zset"
        Assert-Equal ($probeCount + 1) @(Get-Content -LiteralPath (Join-Path $snapshot.HostDirectory "manifest.txt") |
                Where-Object { [string]$_ -like "contest:scoreboard:probe:*" }).Count "every probe key including the 0x0A one is in the manifest"
        Assert-True (Test-Path -LiteralPath (Join-Path $snapshot.HostDirectory "manifest.txt")) "the manifest is copied to the artifact directory"
        Assert-True (Test-Path -LiteralPath (Join-Path $snapshot.HostDirectory "keys.txt")) "the key list is copied to the artifact directory"
    }

    # The captured artifact itself has to show the case, not just the round trip: the point is that a
    # payload ending in 0x0A reached the snapshot, so the bytes on disk are the evidence.
    Test-Case "the captured bytes of the 0x0A payload end in 0x0A" {
        $snapshot = $script:snapshot
        if ($null -eq $snapshot) { throw "the snapshot was not captured, so this case cannot run" }
        $entry = @(Get-Content -LiteralPath (Join-Path $snapshot.HostDirectory "manifest.txt") |
                Where-Object { [string]$_ -like "$newlineKey`t*" })
        Assert-Equal 1 $entry.Count "the 0x0A key is in the manifest"
        $fields = @([string]$entry[0] -split "`t", -1)
        $bytes = [IO.File]::ReadAllBytes((Join-Path $snapshot.HostDirectory "$($fields[3]).bin"))
        Assert-True ($bytes.Length -gt 0) "the captured payload is not empty"
        Assert-Equal 10 $bytes[$bytes.Length - 1] "the captured payload's last byte is 0x0A"
    }

    # The state the rollback replaces: the scoreboard kept taking results after the snapshot was taken,
    # which is what a real run's ingress does. Every mutation below moves in the direction the scoreboard
    # actually moves - results added, values advanced, the checkpoint ahead - so that what the rollback
    # takes away is the interval between the two instants, not a state a healthy scoreboard could not be
    # in. This is the reading the lost set is computed against, and it is taken before the rollback.
    [void](Invoke-ContainerScript -Container $containerName -Description "advance the scoreboard past the snapshot" -ScriptText @'
set -e
i=51
while [ "$i" -le 61 ]; do
    redis-cli SADD "contest:scoreboard:1:processed" "$i" > /dev/null
    i=$((i + 1))
done
redis-cli SET "contest:scoreboard:probe:v1" "CHANGED" > /dev/null
redis-cli SET "contest:scoreboard:new:key" "created after the snapshot" > /dev/null
redis-cli SET "contest:scoreboard:probe:v40" "changed after the snapshot" > /dev/null
redis-cli ZADD "contest:scoreboard:1:ranking" 1 "1" > /dev/null
redis-cli HSET "contest:scoreboard:1:u:10" solved 5 penalty 200 > /dev/null
redis-cli SET "contest:scoreboard:stream:offset" 150 > /dev/null
redis-cli SET "spring:session:s1" "CHANGED BY SOMETHING ELSE" > /dev/null
redis-cli SET "spring:session:s2" "added after the snapshot" > /dev/null
echo ADVANCED
'@)

    $preRollbackProcessed = Get-RedisSetMembers -Key $config.ProcessedKey
    $preRollbackCheckpoint = Get-RedisInt64 -RedisArguments @("GET", $config.CheckpointKey)

    Test-Case "the lost set is the results applied after the snapshot that the rollback erases" {
        if ($null -eq $script:snapshot) { throw "the snapshot was not captured, so this case cannot run" }
        $script:lost = Get-LostResultSet -SnapshotMembers $script:snapshot.Processed -PreRollbackMembers $preRollbackProcessed
        $lost = $script:lost
        Assert-Equal 50 $lost.SnapshotCount "the snapshot held fifty processed results"
        Assert-Equal 61 $lost.PreRollbackCount "the scoreboard held sixty-one when the rollback was injected"
        Assert-Equal 11 $lost.LostCount "eleven results arrived after the snapshot and were erased"
        Assert-Equal 0 @($lost.Lost | Where-Object { [int]$_ -le 50 }).Count "only the results above fifty were lost"
    }

    Test-Case "the rollback restores the captured bytes and removes what came after" {
        if ($null -eq $script:snapshot) { throw "the snapshot was not captured, so this case cannot run" }
        $rollback = Invoke-ScoreboardRollback -SnapshotLabel "k"
        Assert-True ($rollback.DeletedKeys -ge 1) "the current namespace was deleted first ($($rollback.DeletedKeys) keys)"
        Assert-Equal $script:snapshot.KeyCount $rollback.RestoredKeys "every captured key was restored"
        Assert-Equal $script:snapshot.KeyCount $rollback.VerifiedKeys "every restored key was verified byte for byte by the injector's own comparison"
    }

    Test-Case "the restored namespace is exactly the snapshot" {
        if ($null -eq $script:snapshot) { throw "the snapshot was not captured, so this case cannot run" }
        $expected = @(Get-Content -LiteralPath (Join-Path $script:snapshot.HostDirectory "keys.txt")) |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | ForEach-Object { ([string]$_).Trim() }
        $actual = @(Invoke-RedisText -RedisArguments @("--scan", "--pattern", "$($config.ScoreboardKeyPrefix)*")) |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | ForEach-Object { ([string]$_).Trim() }
        $missing = @($expected | Where-Object { $actual -notcontains $_ })
        $extra = @($actual | Where-Object { $expected -notcontains $_ })
        Assert-Equal 0 $missing.Count "no captured key is missing (missing: $($missing -join ', '))"
        Assert-Equal 0 $extra.Count "no key created after the snapshot survived (extra: $($extra -join ', '))"
    }

    Test-Case "a value changed after the snapshot is back to the byte the snapshot held" {
        Assert-Equal "v1" (Get-RedisText -Key "contest:scoreboard:probe:v1" -Description "changed probe") "the changed value is restored"
        Assert-Equal "v40" (Get-RedisText -Key "contest:scoreboard:probe:v40" -Description "re-changed probe") "the re-changed value is restored"
        Assert-Equal 0 (Get-RedisInt64 -RedisArguments @("EXISTS", "contest:scoreboard:new:key")) "a key created after the snapshot is gone"
    }

    # Redis validates the CRC64 of a RESTORE payload, so a transport that dropped or added a byte would
    # be rejected rather than accepted silently - which is why this case asserts the value as well as the
    # absence of an error: the injector's own comparison could pass while the value was never restored.
    Test-Case "a payload ending in 0x0A survives the round trip intact" {
        if ($null -eq $script:newlineValue) { throw "no 0x0A candidate was found, so the transport's hardest case went untested" }
        $value = Get-RedisText -Key $newlineKey -Description "0x0A candidate"
        Assert-Equal $script:newlineValue $value "the value restored to its own bytes, not to a payload trimmed one byte short"
    }

    Test-Case "the processed set and the ranking are back at their captured cardinality" {
        $processed = Get-RedisSetMembers -Key $config.ProcessedKey
        Assert-Equal 50 $processed.Count "the set is the snapshot's set again"
        Assert-Equal 60 (Get-RedisInt64 -RedisArguments @("ZCARD", $config.RankingKey)) "the ranking has its own cardinality again"
        $score = @(Invoke-RedisText -RedisArguments @("ZSCORE", $config.RankingKey, "1")) |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Last 1
        Assert-Equal "2999999999" ([string]$score) "the member's captured score is restored, not the one written after the snapshot"
    }

    Test-Case "a summary hash rewritten after the snapshot is back to its captured fields" {
        Assert-Equal 3 (Get-RedisInt64 -RedisArguments @("HGET", "contest:scoreboard:1:u:10", "solved")) "the solved count is the captured one"
        Assert-Equal 145 (Get-RedisInt64 -RedisArguments @("HGET", "contest:scoreboard:1:u:10", "penalty")) "the penalty is the captured one"
    }

    # The checkpoint going backwards is the fault the three recovery modes exist to detect, so the
    # injector has to be what produces it: ahead before the rollback, back to the snapshot after. A
    # rollback that left the checkpoint where it was would make all three modes report nothing to do.
    Test-Case "the checkpoint regresses to the captured offset" {
        Assert-Equal 150 $preRollbackCheckpoint "the checkpoint was ahead of the snapshot's when the rollback was injected"
        Assert-Equal 100 (Get-RedisInt64 -RedisArguments @("GET", $config.CheckpointKey)) "the checkpoint is back to the snapshot's offset"
    }

    # The injector produces the fault; it does not repair it. If the lost results were present here, the
    # run would measure a scoreboard that never lost anything, and every mode would look instantaneous.
    Test-Case "the loss is outstanding after the rollback, because closing it is the run's subject" {
        if ($null -eq $script:lost) { throw "the lost set was not computed, so this case cannot run" }
        $processed = Get-RedisSetMembers -Key $config.ProcessedKey
        $progress = Get-LostSetProgress -Lost $script:lost.Lost -CurrentMembers $processed
        Assert-Equal 0 $progress.ReappliedCount "the injector recovered none of the lost results"
        Assert-True (-not $progress.Complete) "the loss is still outstanding"
        Assert-Equal 50 $progress.CurrentCount "and the set is the snapshot's exactly, not a union of two instants"
    }

    Test-Case "a key with an expiry keeps an expiry no longer than the captured one" {
        $remaining = Get-RedisInt64 -RedisArguments @("PTTL", "contest:scoreboard:tmp:window")
        Assert-True ($remaining -gt 0) "the restored key has an expiry"
        Assert-True ($remaining -le 600000) "the expiry is not longer than the one captured"
        Assert-Equal "still here" (Get-RedisText -Key "contest:scoreboard:tmp:window" -Description "expiring key") "the value survived"
    }

    Test-Case "the injector did not reach outside the scoreboard namespace" {
        Assert-Equal "CHANGED BY SOMETHING ELSE" (Get-RedisText -Key "spring:session:s1" -Description "foreign key") "a foreign key's value is untouched"
        Assert-Equal "added after the snapshot" (Get-RedisText -Key "spring:session:s2" -Description "foreign key created later") "a foreign key created after the snapshot survives"
    }
}
catch {
    Set-TestSetupError $_
}
finally {
    if ($containerStarted) {
        try {
            [void](Invoke-NativeCommand -Executable "docker" -Arguments @("rm", "-f", $containerName))
        }
        catch {
            Write-Warning "Could not remove the throwaway container '$containerName': $($_.Exception.Message)"
        }
    }
    if (Test-Path -LiteralPath $workDirectory) {
        Remove-Item -LiteralPath $workDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-TestSummary -Suite "Rollback injector integration test"
