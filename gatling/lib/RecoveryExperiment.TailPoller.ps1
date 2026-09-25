# The tail poller: when does every result the rollback took away reappear in the processed set?
#
# It runs outside the application and outside the harness process, inside the dedicated Redis
# container, as a detached shell loop. Three things follow from that placement, and each is why it is
# there rather than in PowerShell:
#
#   * Every reading is stamped with Redis's own `TIME`, returned by the same script that counts, so the
#     instant and the count are one observation in the container clock frame - the frame the batch
#     trace and MySQL share. PowerShell on Windows would stamp it with the Windows clock plus a
#     `docker exec` round trip.
#   * The cadence is ~100 ms. A `docker exec` from Windows costs about that much on its own.
#   * The count is `SINTERCARD` of the processed set against a run-scoped copy of the lost set, so a
#     reading is one small command however large either set is.
#
# The lost set is L = (processed just before the rollback) - (processed at the snapshot), built by
# `New-ShortPauseLostSet`. T_tail_returned is the first reading whose count equals |L|.
#
# "Back in processed" is not "back in the standings": the product adds a submission to `processed`
# outside the guard that decides whether its result changes a rank (see Get-LostSetProgress). The final
# digest after the drain is what says the standings are right.

$script:tailPollerScript = @'
dir="$1"; processedKey="$2"; lostKey="$3"; intervalMs="$4"; maxSeconds="$5"; stableTicks="$6"
out="$dir/tail-poll.csv"
stop="$dir/tail-poll.stop"
doneFile="$dir/tail-poll.done"
rm -f "$stop" "$doneFile"
total=$(redis-cli --raw SCARD "$lostKey")
echo "atEpochMicros,present,total" > "$out"
lua='local t = redis.call("TIME"); return {t[1], t[2], redis.call("SINTERCARD", 2, KEYS[1], KEYS[2])}'
started=$(date +%s)
stable=0
while [ ! -f "$stop" ]; do
    reading=$(redis-cli --raw EVAL "$lua" 2 "$lostKey" "$processedKey" | tr '\n' ' ')
    sec=$(echo "$reading" | awk '{print $1}')
    usec=$(echo "$reading" | awk '{print $2}')
    present=$(echo "$reading" | awk '{print $3}')
    if [ -n "$present" ]; then
        printf '%s%06d,%s,%s\n' "$sec" "$usec" "$present" "$total" >> "$out"
        if [ "$present" -ge "$total" ]; then stable=$((stable + 1)); else stable=0; fi
        if [ "$stable" -ge "$stableTicks" ]; then break; fi
    fi
    now=$(date +%s)
    if [ $((now - started)) -ge "$maxSeconds" ]; then break; fi
    usleep $((intervalMs * 1000)) 2>/dev/null || sleep 0.1
done
echo "finished $(date +%s)" > "$doneFile"
'@

function Get-TailPollerDirectory {
    return "/tmp/sbrec-live-$((Get-RecoveryConfig).RunId)"
}

# Starts the poller detached and returns at once. `docker exec -d` leaves it running after this call.
function Start-TailPoller {
    param(
        [Parameter(Mandatory = $true)][string]$LostKey,
        [int]$IntervalMilliseconds = 100,
        [int]$MaxSeconds = 1800,
        # Readings in a row with the whole lost set present before the poller stops on its own.
        [int]$StableTicks = 5
    )

    $config = Get-RecoveryConfig
    $dir = Get-TailPollerDirectory
    $localPath = Join-Path ([IO.Path]::GetTempPath()) ("sbrec-" + [Guid]::NewGuid().ToString("N") + ".sh")
    try {
        [IO.File]::WriteAllText($localPath, ($script:tailPollerScript -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
        [void](Invoke-Docker -Arguments @("exec", $config.RedisContainer, "mkdir", "-p", $dir))
        [void](Invoke-Docker -Arguments @("cp", $localPath, "$($config.RedisContainer):$dir/tail-poller.sh"))
    }
    finally {
        Remove-Item -LiteralPath $localPath -Force -ErrorAction SilentlyContinue
    }
    [void](Invoke-Docker -Arguments @("exec", "-d", $config.RedisContainer, "sh", "$dir/tail-poller.sh", $dir,
            $config.ProcessedKey, $LostKey, [string]$IntervalMilliseconds, [string]$MaxSeconds, [string]$StableTicks))
    return [pscustomobject][ordered]@{
        Directory = $dir
        StartedAtUtc = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
        IntervalMilliseconds = $IntervalMilliseconds
        StableTicks = $StableTicks
    }
}

# The last reading, for progress output. Returns $null before the first one.
function Get-TailPollerLastReading {
    $config = Get-RecoveryConfig
    $dir = Get-TailPollerDirectory
    $line = @(Invoke-Docker -Arguments @("exec", $config.RedisContainer, "sh", "-c", "tail -n 1 $dir/tail-poll.csv 2>/dev/null; test -f $dir/tail-poll.done && echo SBRE_DONE || true"))
    $finished = @($line | Where-Object { [string]$_ -eq "SBRE_DONE" }).Count -gt 0
    $reading = @($line | Where-Object { [string]$_ -match '^\d+,\d+,\d+$' } | Select-Object -Last 1)
    if ($reading.Count -eq 0) {
        return [pscustomobject]@{ Present = $null; Total = $null; Finished = $finished }
    }
    $fields = ([string]$reading[0]) -split ','
    return [pscustomobject]@{ Present = [long]$fields[1]; Total = [long]$fields[2]; Finished = $finished }
}

# Asks the poller to stop, waits for it to say it has, and copies its readings out.
function Stop-TailPoller {
    param(
        [Parameter(Mandatory = $true)][string]$DestinationPath,
        [int]$TimeoutSeconds = 10
    )

    $config = Get-RecoveryConfig
    $dir = Get-TailPollerDirectory
    [void](Invoke-Docker -Arguments @("exec", $config.RedisContainer, "touch", "$dir/tail-poll.stop"))
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if ((Get-TailPollerLastReading).Finished) { break }
        Start-Sleep -Milliseconds 200
    }
    [void](Invoke-Docker -Arguments @("cp", "$($config.RedisContainer):$dir/tail-poll.csv", $DestinationPath))
}
