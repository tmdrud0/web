# Reads the state of the running system and turns one reading into one row.
#
# The sampler observes; it never changes anything. Every write in this experiment is in the injector,
# the seeder or the runner's reset step, and the separation is what makes the sample stream safe to
# collect while a fault is being injected - a poll that could mutate state would be a second fault.
#
# Three properties of this file are load-bearing for the measurement rather than stylistic:
#
#   * The oracle is read BEFORE the API digest in the same poll, and the poll refuses to record a
#     comparison whose two readings are out of that order. The product writes Redis first and
#     `scoreboard_applied_at` after, so MySQL can lag Redis but never lead it. Reading the oracle at
#     t1 and the digest at t2 >= t1 gives oracle(t1) is a subset of Redis(t2); if the two digest the
#     same set, then Redis(t1) is squeezed between two equal sets and is therefore also equal. A match
#     is a proof of consistency, not an observation of it. Reversing the order would only ever produce
#     matches that prove nothing, and mismatches that are a race rather than a fault.
#   * Every Prometheus name here was read off the registering code rather than guessed, because
#     Micrometer appends a base unit to gauges and counters: `contest.scoreboard.pending` with
#     baseUnit `events` is scraped as `contest_scoreboard_pending_events`, and
#     `contest.scoreboard.applied.offset` as `contest_scoreboard_applied_offset`. A plausible name
#     that does not exist would be a silently empty vector, and an empty vector is indistinguishable
#     from a quiet system.
#   * A quantity nothing exposes is written as `unavailable`, never as 0. A zero is a measurement; a
#     missing source is the absence of one, and the two must not look the same in the CSV.

Set-StrictMode -Version Latest

# --- cheap readers, each one round trip ------------------------------------------------------------

# One `sh` in the Redis container for several scalars, so a poll costs one exec rather than one per
# key. `echo` labels each answer because a bare sequence of numbers could otherwise be shifted by a
# single unexpected line and read as a valid - but wrong - reading. The commands are built with -f
# rather than interpolation so that the shell's own `$( )` survives PowerShell's parser intact.
function Get-RedisMarkedValues {
    param([Parameter(Mandatory = $true)][string[]]$Commands)

    $config = Get-RecoveryConfig
    $parts = New-Object 'System.Collections.Generic.List[string]'
    foreach ($command in $Commands) {
        $parts.Add($command)
    }
    $shell = $parts -join '; '
    $lines = @(Invoke-Docker -Arguments @("exec", $config.RedisContainer, "sh", "-c", $shell))

    $values = [ordered]@{}
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $text = [string]$lines[$index]
        if (-not $text.StartsWith("M:")) {
            continue
        }
        $payload = $text.Substring(2)
        $separator = $payload.IndexOf(":")
        if ($separator -lt 1) {
            throw "Redis marker '$text' carries no value separator."
        }
        $values[$payload.Substring(0, $separator)] = $payload.Substring($separator + 1)
    }
    return $values
}

# INFO's own format: `name:value` lines and `# section` headers. `INFO all` carries the sub-structures
# too - `cmdstat_eval:...` and `latency_percentiles_usec_eval:...` arrive as ordinary keys of the same
# map - so one exec per poll produces every Redis figure this experiment records. A server that does
# not track one of them simply has no such key, and its reader records `unavailable`.
function Get-RedisInfoSection {
    param(
        [Parameter(Mandatory = $true)][string]$Section,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $lines = @(Invoke-RedisText -RedisArguments @("INFO", $Section))
    $values = [ordered]@{}
    foreach ($line in $lines) {
        $text = [string]$line
        if ([string]::IsNullOrWhiteSpace($text) -or $text.StartsWith("#")) {
            continue
        }
        $separator = $text.IndexOf(":")
        if ($separator -lt 1) {
            continue
        }
        $values[$text.Substring(0, $separator)] = $text.Substring($separator + 1)
    }
    if ($values.Count -eq 0) {
        throw "Redis INFO $Section returned nothing readable; $Description cannot be recorded."
    }
    return $values
}

# The second level of INFO's format, where a value is a comma separated list of `key=value` pairs.
function ConvertFrom-RedisInfoPairs {
    param([Parameter(Mandatory = $true)][string]$Value)

    $fields = [ordered]@{}
    foreach ($pair in $Value -split ",") {
        $separator = $pair.IndexOf("=")
        if ($separator -lt 1) {
            continue
        }
        $fields[$pair.Substring(0, $separator)] = $pair.Substring($separator + 1)
    }
    return $fields
}

# The per-command cost of the Lua script is the Redis-side price of a scoreboard write, and
# `cmdstat_eval` is the only place it is visible - the application's own timer measures the round trip,
# not the work the server did to answer it.
function Get-RedisCommandStats {
    param([Parameter(Mandatory = $true)]$Info)

    $stats = [ordered]@{}
    foreach ($name in $Info.Keys) {
        if (-not $name.StartsWith("cmdstat_")) {
            continue
        }
        $stats[$name.Substring("cmdstat_".Length)] = ConvertFrom-RedisInfoPairs -Value ([string]$Info[$name])
    }
    return $stats
}

# Absent on a server that does not track it, which the caller writes as `unavailable` rather than as a
# zero: a percentile nobody measured is not a percentile of zero microseconds.
function Get-RedisLatencyPercentiles {
    param([Parameter(Mandatory = $true)]$Info)

    $percentiles = [ordered]@{}
    foreach ($name in $Info.Keys) {
        if (-not $name.StartsWith("latency_percentiles_usec_")) {
            continue
        }
        $shortName = $name.Substring("latency_percentiles_usec_".Length)
        $percentiles[$shortName] = ConvertFrom-RedisInfoPairs -Value ([string]$Info[$name])
    }
    return $percentiles
}

# The server's own counters, which is the only view of database cost that does not go through the
# application being measured. An allowlist rather than the whole of SHOW STATUS: the full table is
# several hundred rows and a poll that reads all of them spends more time measuring than the workload
# spends working.
function Get-MySqlStatusCounters {
    $names = @(
        "Questions", "Com_select", "Com_insert", "Com_update", "Com_delete",
        "Innodb_rows_read", "Innodb_rows_inserted",
        "Innodb_buffer_pool_read_requests", "Innodb_buffer_pool_reads",
        "Threads_connected", "Threads_running", "Slow_queries"
    )
    $quoted = ($names | ForEach-Object { "'$_'" }) -join ","
    $rows = @(Invoke-SqlRows -Sql "SHOW GLOBAL STATUS WHERE Variable_name IN ($quoted);" `
            -Description "MySQL global status")
    $counters = [ordered]@{}
    foreach ($row in $rows) {
        if ($row.Count -lt 2) {
            continue
        }
        $counters[[string]$row[0]] = ConvertTo-RequiredInt64 -Value $row[1] -Description "MySQL status $($row[0])"
    }
    return $counters
}

# The queue table as the broker sees it. `docker exec` rather than `compose exec` because the poll runs
# once a second and compose's service resolution is a measurable part of that second on Windows. No `-T`
# for the same reason: it is `docker compose exec`'s flag for turning the pseudo-TTY off, and plain
# `docker exec` has no such flag - it rejects it outright, so the poll would fail rather than merely be
# noisy.
function Get-RabbitQueueState {
    $config = Get-RecoveryConfig
    $lines = @(Invoke-Docker -Arguments @(
            "exec", $config.RabbitContainer, "rabbitmqctl", "list_queues", "-q",
            "name", "messages_ready", "messages_unacknowledged", "consumers"
        ))
    return ConvertTo-RabbitQueueRows -Lines $lines
}

# `rabbitmqctl list_queues -q name messages_ready messages_unacknowledged consumers`, one row per queue,
# read from the end so that a queue name containing spaces still lands in the name.
#
# Separated from the docker call that fetches the lines because that call needs a broker and this does
# not, and because the failure it must not have is silent. A row it cannot parse is refused rather than
# skipped: a skipped row leaves the queue out of the map, and a missing queue reads as empty and
# consumer-less, so a stream queue holding real backlog would be reported as drained and the run would
# call the pipeline quiescent with messages still in it. Skipping was the dangerous direction, not
# crashing.
function ConvertTo-RabbitQueueRows {
    param([AllowEmptyCollection()]$Lines)

    $queues = [ordered]@{}
    foreach ($line in @($Lines)) {
        $fields = @(([string]$line -split '\s+') | Where-Object { $_ })
        if ($fields.Count -lt 4) {
            throw ("rabbitmqctl list_queues printed a row this reader cannot read ($($fields.Count) " +
                "field(s), need name, messages_ready, messages_unacknowledged and consumers): '${line}'.")
        }
        $ready = 0L
        $unacked = 0L
        $consumers = 0L
        if ([long]::TryParse($fields[$fields.Count - 3], [ref]$ready) -and
            [long]::TryParse($fields[$fields.Count - 2], [ref]$unacked) -and
            [long]::TryParse($fields[$fields.Count - 1], [ref]$consumers)) {
            $queues[$fields[0]] = [pscustomobject]@{
                Ready     = $ready
                Unacked   = $unacked
                Consumers = $consumers
            }
        }
    }
    return $queues
}

# One queue's counts, with absence read as empty rather than as an error.
#
# `Assert-OnlyProjectQueues` deliberately permits a project queue to be absent - it is the state a reset
# produces, and the state a broker the application has never started against is in - and the readers
# immediately after it dereferenced the queue anyway. Under `Set-StrictMode -Version Latest` that is not
# a null propagation: `$live.Ready` on a missing queue throws PropertyNotFoundException, from the middle
# of a poll, on exactly the state the check beside it had just declared legal. Two neighbours disagreeing
# about whether absence is allowed is the defect; absence being allowed is the correct half.
#
# A queue that is not there holds no messages and has no consumer, which is what these zeros say. The
# consumer count is the one that must not be guessed: `Consumers = 0` on an absent stream queue keeps the
# quiescence gate shut, which is right - a stream queue that has been deleted is not a drained pipeline.
function Get-QueueCounts {
    param(
        [Parameter(Mandatory = $true)]$Queues,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Queues.Contains($Name)) {
        return $Queues[$Name]
    }
    return [pscustomobject]@{ Ready = 0L; Unacked = 0L; Consumers = 0L }
}

# The three queues this project declares. Used both to read state and, before a reset deletes one, to
# prove the broker has nothing else of someone else's in it.
function Get-ProjectQueueNames {
    return @("contest.judge.live", "contest.judge.dead", "contest.judge.result.stream")
}

function Assert-OnlyProjectQueues {
    param([Parameter(Mandatory = $true)]$Queues)

    $unexpected = @($Queues.Keys | Where-Object { (Get-ProjectQueueNames) -notcontains $_ })
    if ($unexpected.Count -gt 0) {
        throw "The broker reports queues this project does not declare: $($unexpected -join ', '). " +
        "A reset that removed a queue here could remove someone else's."
    }
    # An absent project queue is not an error. This reads the dedicated `oj-loadtest-rabbitmq` container,
    # so the queues it does report are this project's by construction, and the reset's purpose is a
    # stream holding no unprocessed work - which is what an absent stream already is. Requiring all three
    # to be present made the first run against a broker the application had never started against fail
    # at step 1, on the exact state the reset exists to produce.
    # What is checked is the direction that can destroy something: a queue this project never declared.
}

# --- Prometheus ------------------------------------------------------------------------------------

# Prometheus escapes a label value that carries a dot as `\\.` in some exporter versions and leaves it
# alone in others. Stripping backslashes before comparing means the reader does not have to know which
# one is running, and a queue named `contest.judge.result.stream` is found either way.
function Remove-PrometheusLabelEscapes {
    # `AllowEmptyString` because a Prometheus label value may be empty, and the series that carry no such
    # label at all arrive the same way - `[string]$sample.metric.area` of a sample with no `area` is the
    # empty string. Mandatory alone refuses it, which turns "this series has no label here" into a
    # binding error thrown from inside a poll rather than into the answer "not this one".
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)

    return $Value.Replace("\", "")
}

# A label's value, or the empty string when the series carries no such label.
#
# `$sample.metric.area` does not answer that question under `Set-StrictMode -Version Latest`: a sample
# whose metric has no `area` property throws PropertyNotFoundException rather than yielding a null, so
# asking for the label and asking whether it is there have to be the same question. Most of the series
# one poll returns carry none of the labels a reading filters on - the filter is there for the few that
# do - so this is the common path rather than an edge, and getting it wrong stops the poll that was
# supposed to start the measurement.
function Get-PrometheusLabelValue {
    param(
        [Parameter(Mandatory = $true)]$Metric,
        [Parameter(Mandatory = $true)][string]$LabelName
    )

    if (-not ($Metric.PSObject.Properties.Name -contains $LabelName)) {
        return ""
    }
    return Remove-PrometheusLabelEscapes -Value ([string]$Metric.$LabelName)
}

# One request per poll. The whole metric surface of a single role is small next to the state it
# describes, so asking for it in one query with a name pattern is cheaper than a query per meter - and
# the pattern is anchored on prefixes the registering code owns, so a rename shows up as a missing
# series rather than as a wrong number under a plausible name.
#
# The samples are an argument rather than something this fetches, so that one query can be read more
# than one way. Reducing a sample list to one value per name is the step that loses the labels, and two
# of the meters here carry a label that separates things which must not be added together: the JVM
# reports `jvm_memory_used_bytes` once per pool, each labelled with the area it belongs to (three heap
# pools, five non-heap ones), and the rollback retry counter carries the outcome it was counted for.
# Summed by name the first answers "how much memory is in the JVM" under a column named for the heap -
# 3.07x and 3.26x the heap on the two live web nodes, measured - and the second adds a busy gate to a
# failed attempt. A caller that needs one label's series says so
# here, where the label is still visible, and pays for the query once: `-LabelName` and `-LabelValue`
# are a filter, not a requirement, and the callers that want every series pass neither.
function ConvertTo-PrometheusSampleMap {
    param(
        [AllowEmptyCollection()]$Samples,
        [Parameter(Mandatory = $true)][string]$Description,
        [string]$LabelName = "",
        [string]$LabelValue = ""
    )

    $values = [ordered]@{}
    foreach ($sample in @($Samples)) {
        if ($LabelName.Length -gt 0 -and
            (Get-PrometheusLabelValue -Metric $sample.metric -LabelName $LabelName) -ne $LabelValue) {
            continue
        }
        $name = [string]$sample.metric.__name__
        $pair = @($sample.value)
        if ($pair.Count -lt 2) {
            throw "Prometheus $Description returned a malformed sample for '$name'."
        }
        if ([string]::IsNullOrWhiteSpace($name)) {
            throw "Prometheus $Description returned a sample with no metric name."
        }
        $value = ConvertTo-RequiredDouble -Value $pair[1] -Description "$Description $name"
        if ($values.Contains($name)) {
            $values[$name] = $values[$name] + $value
        }
        else {
            $values[$name] = $value
        }
    }
    return $values
}

# The per-role prefix every meter this experiment reads is registered under, plus the process and
# container meters that say what the role cost. Histogram buckets are included so that the Lua
# pipeline's latency can be reconstructed from them rather than only its total.
#
# `jvm_memory_used_bytes` is selected whole here and separated by area in the reader, because the
# alternative - a matcher inside this pattern - would apply to every name in it, and a series without
# that label is not a series that fails the matcher, it is one that disappears from the poll.
function Get-RoleMetricQuery {
    param([Parameter(Mandatory = $true)][string]$Node)

    return '{job="oj-app",node="' + $Node + '",__name__=~"' +
        'contest_scoreboard_.*|contest_submission_.*|hikaricp_.*|process_cpu_usage|' +
        'jvm_memory_used_bytes|cgroup_.*"}'
}

# One series per name, selected by a label whose escaping the exporter may or may not apply. Two
# series under one name would be two brokers or two queues answering the same question, and summing
# them - which the map reader above does - would hide exactly that.
function Get-PrometheusLabeledSampleMap {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$LabelName,
        [Parameter(Mandatory = $true)][string]$LabelValue
    )

    $samples = @(Invoke-PrometheusQuery -Query $Query -Description $Description)
    $values = [ordered]@{}
    foreach ($sample in $samples) {
        $name = [string]$sample.metric.__name__
        $label = Get-PrometheusLabelValue -Metric $sample.metric -LabelName $LabelName
        if ($label -ne $LabelValue) {
            continue
        }
        if ($values.Contains($name)) {
            throw "Prometheus $Description returned more than one series named '$name' for $LabelName=$LabelValue."
        }
        $values[$name] = ConvertTo-RequiredDouble -Value @($sample.value)[1] -Description "$Description $name"
    }
    if ($values.Count -eq 0) {
        # A throughput counter that has not moved yet may simply not exist yet, so this is a warning
        # and the caller records `unavailable` - visibly, in the run log, rather than as a zero that
        # would read as "nothing was delivered".
        Write-Warning "Prometheus $Description found no series for $LabelName=$LabelValue."
    }
    return $values
}

# The cumulative buckets of one histogram, in the form the quantile function takes. `le` is the
# bucket's upper bound as the exporter printed it, with `+Inf` standing for the last one.
function Get-PrometheusHistogram {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $samples = @(Invoke-PrometheusQuery -Query $Query -Description $Description)
    $buckets = New-Object 'System.Collections.Generic.List[object]'
    foreach ($sample in $samples) {
        $bound = [string]$sample.metric.le
        if ([string]::IsNullOrWhiteSpace($bound)) {
            throw "Prometheus $Description returned a sample without an 'le' label."
        }
        $upperBound = 0d
        if ($bound -eq "+Inf") {
            $upperBound = [double]::PositiveInfinity
        }
        else {
            $upperBound = ConvertTo-RequiredDouble -Value $bound -Description "$Description le"
        }
        $buckets.Add([pscustomobject]@{
                UpperBound      = $upperBound
                CumulativeCount = ConvertTo-RequiredDouble -Value @($sample.value)[1] -Description "$Description count"
            })
    }
    return $buckets.ToArray()
}

# The standard Prometheus histogram_quantile over cumulative buckets: linear interpolation inside the
# bucket that contains the quantile. Written out rather than queried so that a bucket edge can be
# reported next to the value it produced, and so that the interpolation is a pure function the unit
# tests can hold to a known input.
function Get-PrometheusHistogramQuantile {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Buckets,
        [Parameter(Mandatory = $true)][double]$Quantile
    )

    if ($Quantile -lt 0 -or $Quantile -gt 1) {
        throw "Histogram quantile must be between 0 and 1, not $Quantile."
    }
    $sorted = @($Buckets | Sort-Object { [double]$_.UpperBound })
    if ($sorted.Count -eq 0) {
        return $null
    }
    $total = [double]$sorted[$sorted.Count - 1].CumulativeCount
    if ($total -le 0) {
        return $null
    }
    $target = $Quantile * $total
    $previousBound = 0d
    $previousCount = 0d
    foreach ($bucket in $sorted) {
        $bound = [double]$bucket.UpperBound
        $count = [double]$bucket.CumulativeCount
        if ($count -ge $target) {
            if ([double]::IsPositiveInfinity($bound)) {
                # The quantile falls in the open-ended bucket: the highest edge is the only honest
                # answer, and reporting a number below it would claim precision the buckets do not have.
                return $previousBound
            }
            if ($count -le $previousCount) {
                return $bound
            }
            $fraction = ($target - $previousCount) / ($count - $previousCount)
            return $previousBound + ($bound - $previousBound) * $fraction
        }
        $previousBound = $bound
        $previousCount = $count
    }
    return $previousBound
}

# --- the scoreboard's own state --------------------------------------------------------------------

# The checkpoint, the two cardinalities that say how much of the scoreboard survived the rollback, and
# the DB-pending set. Read together in one exec so that they describe one instant rather than four
# consecutive ones - a checkpoint read before a write and a cardinality read after it would disagree
# in a way that looks like a fault.
function Get-ScoreboardState {
    $config = Get-RecoveryConfig
    $values = Get-RedisMarkedValues -Commands @(
        ('echo M:ranking:$(redis-cli --raw ZCARD {0})' -f $config.RankingKey),
        ('echo M:processed:$(redis-cli --raw SCARD {0})' -f $config.ProcessedKey),
        ('echo M:dbPending:$(redis-cli --raw SCARD {0})' -f $config.StreamDbPendingKey),
        ('echo M:checkpointExists:$(redis-cli --raw EXISTS {0})' -f $config.CheckpointKey),
        ('echo M:checkpoint:$(redis-cli --raw GET {0})' -f $config.CheckpointKey),
        ('echo M:keyCount:$(redis-cli --raw --scan --pattern "{0}" | wc -l)' -f $config.ScoreboardKeyPattern)
    )
    foreach ($required in @("ranking", "processed", "dbPending", "checkpointExists", "checkpoint", "keyCount")) {
        if (-not $values.Contains($required)) {
            throw "The Redis scoreboard read did not return '$required'."
        }
    }

    $exists = ConvertTo-RequiredInt64 -Value $values["checkpointExists"] -Description "checkpoint presence"
    $checkpoint = $null
    if ($exists -gt 0) {
        $checkpoint = ConvertTo-RequiredInt64 -Value $values["checkpoint"] -Description "stream checkpoint"
    }

    return [pscustomobject][ordered]@{
        RankingCardinality = ConvertTo-RequiredInt64 -Value $values["ranking"] -Description "ranking cardinality"
        ProcessedCardinality = ConvertTo-RequiredInt64 -Value $values["processed"] -Description "processed cardinality"
        StreamDbPending = ConvertTo-RequiredInt64 -Value $values["dbPending"] -Description "stream DB-pending cardinality"
        CheckpointPresent = $exists -gt 0
        Checkpoint = $checkpoint
        # `--scan` can return a key twice when the keyspace is rehashing, and Redis says so; the count
        # is therefore a bound on the scoreboard's key population rather than an exact census, and it
        # is only ever used to notice a rollback that took the wrong scope.
        ScoreboardKeyCount = ConvertTo-RequiredInt64 -Value $values["keyCount"] -Description "scoreboard key count"
    }
}

# --- how far the pipeline has drained --------------------------------------------------------------

function Get-JudgeOutboxNonPublished {
    $config = Get-RecoveryConfig
    return Invoke-SqlInt64 `
        -Sql "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'" `
        -Description "non-published judge outbox rows"
}

# `scoreboard_applied_at IS NULL` is the scoreboard's own backlog count: results MySQL has already
# judged and that no scoreboard write has stamped. It is scoped to the contest because the instance is
# shared and another contest's backlog is not this run's queue.
function Get-UnappliedResultCount {
    $config = Get-RecoveryConfig
    return Invoke-SqlInt64 `
        -Sql "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = $($config.ContestId) AND scoreboard_applied_at IS NULL" `
        -Description "unapplied scoreboard result rows"
}

# Everything that has to be empty before the pipeline counts as drained, read as one observation so the
# quiescence decision and the row it is written into describe the same instant. `PendingEvents` is the
# only cross-role number here and it comes from Prometheus rather than from Redis: it is the stream's
# own lag in the scoreboard's units, which is the signal that does not change meaning between modes.
#
# `contest.judge.result.stream` is a RabbitMQ stream queue, and a stream's `messages_ready` is the log it
# retains rather than a backlog a consumer drains: reading a message removes nothing, and the consumer's
# progress is a separately stored offset - already read here as `PendingEvents` and `StreamDbPending`.
# Requiring `messages_ready = 0` therefore made quiescence unreachable once anything had been published.
# Observed 2026-09-25, with the consumer at the head: checkpoint 5091, `pendingEvents` 0, `dbPending` 0,
# `unapplied` 0, digest agreeing - and the queue reporting 5092 ready, having grown 0 -> 8 -> 71 -> 990
# -> 5092 with the published results and never fallen. The counts are still read and still columns of the
# row; what is gone is the claim that a stream's retained log has to be empty for the pipeline to be
# drained. Its `Consumers` stays, because a deleted stream has none and a consumer-less stream is not a
# drained pipeline either.
function Get-PipelineOperationalState {
    $pipelineWatch = [Diagnostics.Stopwatch]::StartNew()
    $judgeNonPublished = Get-JudgeOutboxNonPublished
    $scoreboardUnapplied = Get-UnappliedResultCount
    $rabbitWatch = [Diagnostics.Stopwatch]::StartNew()
    $queues = Get-RabbitQueueState
    $rabbitPollMs = $rabbitWatch.ElapsedMilliseconds
    Assert-OnlyProjectQueues -Queues $queues
    $scoreboard = Get-ScoreboardState

    $pendingSamples = @(Invoke-PrometheusQuery `
            -Query 'contest_scoreboard_pending_events{job="oj-app",node="batch-1"}' `
            -Description "stream pending events")
    if ($pendingSamples.Count -ne 1) {
        throw "Expected exactly one batch-1 contest_scoreboard_pending_events series, found $($pendingSamples.Count)."
    }
    $pendingEvents = ConvertTo-RequiredDouble -Value @($pendingSamples[0].value)[1] -Description "stream pending events"

    $live = Get-QueueCounts -Queues $queues -Name "contest.judge.live"
    $dead = Get-QueueCounts -Queues $queues -Name "contest.judge.dead"
    $stream = Get-QueueCounts -Queues $queues -Name "contest.judge.result.stream"

    # A consumer that has stopped is not a drained pipeline even when every queue it feeds is empty,
    # which is exactly the state `stream-offset` passes through while it resubscribes. The stream queue's
    # ready and unacked counts are deliberately not terms here - see the note above the function for what
    # they measure on a stream and why they made this unsatisfiable.
    $quiescent = $judgeNonPublished -eq 0L -and
        $scoreboardUnapplied -eq 0L -and
        $live.Ready -eq 0L -and $live.Unacked -eq 0L -and
        $dead.Ready -eq 0L -and $dead.Unacked -eq 0L -and
        $stream.Consumers -ge 1L -and
        $scoreboard.StreamDbPending -eq 0L -and
        $pendingEvents -eq 0d

    return [pscustomobject][ordered]@{
        Quiescent = $quiescent
        RabbitPollMs = $rabbitPollMs
        DurationMs = $pipelineWatch.ElapsedMilliseconds
        JudgeNonPublished = $judgeNonPublished
        ScoreboardUnapplied = $scoreboardUnapplied
        PendingEvents = $pendingEvents
        LiveReady = $live.Ready
        LiveUnacked = $live.Unacked
        DeadReady = $dead.Ready
        DeadUnacked = $dead.Unacked
        StreamReady = $stream.Ready
        StreamUnacked = $stream.Unacked
        StreamConsumers = $stream.Consumers
        StreamDbPending = $scoreboard.StreamDbPending
        RankingCardinality = $scoreboard.RankingCardinality
        ProcessedCardinality = $scoreboard.ProcessedCardinality
        CheckpointPresent = $scoreboard.CheckpointPresent
        Checkpoint = $scoreboard.Checkpoint
        ScoreboardKeyCount = $scoreboard.ScoreboardKeyCount
    }
}

# Waits for the drain without asserting container health, because the caller decides what else has to
# hold - after a rollback the batch role is legitimately mid-recovery and its health endpoint is not
# the thing being waited on.
function Wait-PipelineQuiescent {
    param(
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastState = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $lastState = Get-PipelineOperationalState
        if ($lastState.Quiescent) {
            return $lastState
        }
        Start-Sleep -Milliseconds 1000
    }
    $detail = if ($null -eq $lastState) { "no state" } else {
        "judge=$($lastState.JudgeNonPublished), unapplied=$($lastState.ScoreboardUnapplied), " +
        "pending=$($lastState.PendingEvents), live=$($lastState.LiveReady)/$($lastState.LiveUnacked), " +
        "dead=$($lastState.DeadReady)/$($lastState.DeadUnacked), " +
        "stream=$($lastState.StreamReady)/$($lastState.StreamUnacked)/c$($lastState.StreamConsumers), " +
        "dbPending=$($lastState.StreamDbPending)"
    }
    throw "$Description did not reach operational quiescence within $TimeoutSeconds seconds ($detail)."
}

# --- what the batch role was actually started with -------------------------------------------------

# Read from the container's own environment rather than from the file the runner passed in: the
# comparison this experiment makes is between three modes, and a run whose batch role silently came up
# in a different mode than the one recorded would be three runs of two modes.
function Get-BatchRuntimeConfig {
    $config = Get-RecoveryConfig
    $json = (Invoke-NativeCommand -Executable "docker" -Arguments @("inspect", $config.BatchContainer)) -join "`n"
    $containers = @($json | ConvertFrom-Json)
    if ($containers.Count -ne 1) {
        throw "Expected one container named '$($config.BatchContainer)', found $($containers.Count)."
    }
    $environment = [ordered]@{}
    foreach ($entry in @($containers[0].Config.Env)) {
        $text = [string]$entry
        $separator = $text.IndexOf("=")
        if ($separator -lt 1) {
            continue
        }
        $environment[$text.Substring(0, $separator)] = $text.Substring($separator + 1)
    }
    $mode = $null
    if ($environment.Contains("CONTEST_SCOREBOARD_RECOVERY_MODE")) {
        $mode = $environment["CONTEST_SCOREBOARD_RECOVERY_MODE"]
    }
    return [pscustomobject][ordered]@{
        Mode = $mode
        DeterministicJudging = if ($environment.Contains("JUDGE_DETERMINISTIC_ENABLED")) { $environment["JUDGE_DETERMINISTIC_ENABLED"] } else { $null }
        AcceptPermille = if ($environment.Contains("JUDGE_ACCEPT_PERMILLE")) { $environment["JUDGE_ACCEPT_PERMILLE"] } else { $null }
        DbHost = if ($environment.Contains("DB_HOST")) { $environment["DB_HOST"] } else { $null }
        DbName = if ($environment.Contains("DB_NAME")) { $environment["DB_NAME"] } else { $null }
        DbPort = if ($environment.Contains("DB_PORT")) { $environment["DB_PORT"] } else { $null }
        ConsumerEnabled = if ($environment.Contains("CONTEST_SCOREBOARD_STREAM_CONSUMER_ENABLED")) { $environment["CONTEST_SCOREBOARD_STREAM_CONSUMER_ENABLED"] } else { $null }
    }
}

function Assert-BatchRecoveryMode {
    $config = Get-RecoveryConfig
    $runtime = Get-BatchRuntimeConfig
    if ([string]::IsNullOrWhiteSpace([string]$runtime.Mode)) {
        throw "The batch role carries no CONTEST_SCOREBOARD_RECOVERY_MODE. The mode of this run is unknown, " +
        "so no figure from it can be attributed to one."
    }
    if ([string]$runtime.Mode -ne $config.Mode) {
        throw "The batch role is running mode '$($runtime.Mode)'; this run was initialized for '$($config.Mode)'."
    }
    return $runtime
}

# --- the batch role's own account of what happened -------------------------------------------------

# Docker's timestamps are RFC3339 with nanosecond precision and .NET parses at most seven fractional
# digits, so the fraction is truncated rather than handed to the parser to reject. The instant is the
# daemon's, taken when it read the line off the container's stdout - close enough to order events
# against each other, and the reason a detection latency is reported with its poll interval beside it.
function ConvertFrom-DockerLogTimestamp {
    param([Parameter(Mandatory = $true)][string]$Value)

    $normalized = [regex]::Replace($Value, '\.(\d{1,7})\d*Z$', '.$1Z')
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($normalized,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$parsed)) {
        throw "Docker log timestamp '$Value' is not a parseable instant."
    }
    return $parsed
}

# The patterns are the product's own log statements, read off ContestScoreboardStreamLifecycle. A
# recovery that did not log one of these did not take the path it claims to, which is why the timeline
# is evidence and not narration.
$script:recoveryLogPatterns = @(
    [pscustomobject]@{
        Kind      = "detected-nonrewinding"
        Pattern   = 'Redis scoreboard offset rolled back from (\d+) to (\d+); mode=(\S+) rebuilds history'
        HasOffsets = $true
    },
    [pscustomobject]@{
        Kind      = "detected-rewinding"
        Pattern   = 'Redis scoreboard offset rolled back from (\d+) to (\d+); resubscribing from the stored offset'
        HasOffsets = $true
    },
    [pscustomobject]@{
        Kind      = "rebuilt"
        Pattern   = 'Rebuilt the scoreboard history through offset (\d+) with the (\S+) basis'
        HasOffsets = $true
    },
    [pscustomobject]@{
        Kind      = "unanswered"
        Pattern   = 'The (\S+) basis did not rebuild the history the rollback took away between offsets (\d+) and (\d+) \((\S+)\)'
        HasOffsets = $true
    },
    [pscustomobject]@{
        Kind      = "unrecoverable"
        Pattern   = 'The (\S+) basis cannot rebuild the history the rollback took away between offsets (\d+) and (\d+)'
        HasOffsets = $true
    },
    [pscustomobject]@{
        Kind      = "consumer-held"
        Pattern   = 'Holding the scoreboard stream consumer until the (\S+) history recovery has run'
        HasOffsets = $false
    },
    [pscustomobject]@{
        Kind      = "failed-batch-resubscribe"
        Pattern   = 'Resubscribing the scoreboard stream consumer at (\d+) to re-read a failed batch'
        HasOffsets = $false
    },
    [pscustomobject]@{
        Kind      = "consumer-started"
        Pattern   = 'Started scoreboard stream consumer at (\d+)'
        HasOffsets = $false
    }
)

# `docker logs` writes the container's stdout to docker's stdout and its stderr to docker's stderr, so
# both streams are read and merged: which stream a line arrived on is a property of the logging
# configuration, not of the event.
function Get-BatchContainerLog {
    param([Parameter(Mandatory = $true)][string]$SinceUtc)

    $config = Get-RecoveryConfig
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& docker logs --timestamps --tail 20000 --since $SinceUtc $config.BatchContainer 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "docker logs failed for '$($config.BatchContainer)' (exit $exitCode)."
    }
    return @($lines | ForEach-Object { [string]$_ })
}

# The parsing half, kept separate from the reading half so that the patterns - which are the product's
# own log strings - can be held against fixture lines in a unit test. A pattern that drifts from the
# product would otherwise turn into a run that reports no detection, which reads exactly like a
# rollback nothing noticed.
function Select-RecoveryLogEvents {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines)

    $events = New-Object 'System.Collections.Generic.List[object]'
    foreach ($line in $Lines) {
        $match = [regex]::Match([string]$line, '^(?<ts>\S+Z)\s+(?<message>.*)$')
        if (-not $match.Success) {
            continue
        }
        $instant = ConvertFrom-DockerLogTimestamp -Value $match.Groups["ts"].Value
        $message = $match.Groups["message"].Value
        foreach ($definition in $script:recoveryLogPatterns) {
            $hit = [regex]::Match($message, $definition.Pattern)
            if (-not $hit.Success) {
                continue
            }
            $groups = @($hit.Groups | Select-Object -Skip 1 | ForEach-Object { $_.Value })
            $events.Add([pscustomobject][ordered]@{
                    Instant = $instant
                    Kind = $definition.Kind
                    Message = $message
                    Fields = $groups
                })
            break
        }
    }
    return @($events | Sort-Object Instant)
}

# The recovery timeline, as the batch role reported it. Returned as events with instants rather than as
# a summary, because the interesting questions - was the rollback detected at all, did the mode answer
# it or ask about it again, did the consumer stop - are each answered by the presence and order of one
# kind of event, and a caller that needs a summary can take the first of each kind itself.
function Get-BatchRecoveryTimeline {
    param([Parameter(Mandatory = $true)][string]$SinceUtc)

    return Select-RecoveryLogEvents -Lines (Get-BatchContainerLog -SinceUtc $SinceUtc)
}

function Get-FirstRecoveryEvent {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Events,
        [Parameter(Mandatory = $true)][string[]]$Kinds
    )

    foreach ($event in $Events) {
        if ($Kinds -contains $event.Kind) {
            return $event
        }
    }
    return $null
}

# --- one poll --------------------------------------------------------------------------------------

# The column set is fixed here rather than derived from whatever a poll happened to read, so that nine
# runs of three modes share one schema and a column that a source stopped exposing shows up as
# `unavailable` in a column that still exists. Adding a metric is a change to this list and to the
# observation map together.
function Get-SampleColumnNames {
    return @(
        "timestampUtc", "timestampMysql", "elapsedMs", "phase", "pollIndex", "pollDurationMs",
        "oraclePollMs", "apiPollMs", "promPollMs", "redisPollMs", "mysqlPollMs", "rabbitPollMs",
        "pipelinePollMs",
        "checkpointPresent", "checkpointOffset", "appliedOffsetMetric", "streamPendingEvents",
        "streamOldestReadySeconds", "streamDbPending", "rankingCardinality", "processedCardinality",
        "scoreboardKeyCount", "appliedTotal", "rollbackObservedTotal", "rollbackRestartsTotal",
        "rollbackUnrecoverableTotal", "rollbackRetryBusyTotal", "rollbackRetryRetryableTotal",
        "replayMarkerFailuresTotal",
        "streamFailuresTotal", "streamOffsetGapsTotal", "streamFailureRestartsTotal",
        "streamUnappliedRefusalsTotal", "streamTailProbeFailuresTotal", "redisLuaErrorsTotal",
        "sequenceRoundsTotal", "sequenceDuplicatesTotal", "sequenceReplayedTotal",
        "sequenceFailedTotal", "sequenceWindowsSaturatedTotal", "sequenceUnresolvedTotal",
        "sequenceMappingSize", "judgeOutboxNonPublished", "unappliedResults", "rabbitLiveReady",
        "rabbitLiveUnacked", "rabbitDeadReady", "rabbitDeadUnacked", "streamQueueReady",
        "streamQueueUnacked", "streamQueueConsumers", "quiescent", "quiescentObservedAtUtc",
        "oracleObservedAtUtc",
        "oracleDigest", "oracleParticipants", "oracleAppliedResults", "oracleResolvedResults",
        "digestObservedAtUtc", "apiDigest", "apiParticipants", "apiEntries", "apiPages",
        "digestMatches", "lostTotal", "lostReapplied", "lostRemaining", "lostComplete",
        "consistencyObservedAtUtc",
        "mysqlQuestions", "mysqlComSelect", "mysqlRowsRead", "mysqlInnodbBufferPoolReadRequests",
        "mysqlInnodbBufferPoolReads", "mysqlThreadsConnected", "mysqlThreadsRunning",
        "mysqlSlowQueries", "redisTotalCommands", "redisInstantaneousOps", "redisKeyspaceHits",
        "redisKeyspaceMisses", "redisUsedCpuSys", "redisUsedCpuUser", "redisEvictedKeys",
        "redisEvalCalls", "redisEvalUsec", "redisRestoreCalls", "redisDelCalls",
        "redisLatencyEvalP50Us", "redisLatencyEvalP99Us", "redisPipelineCount",
        "redisPipelineSumSeconds", "redisPipelineP95Seconds", "rabbitDeliveredAckTotal",
        "rabbitPublishedTotal", "hikariActive", "hikariPending",
        "appProcessCpu", "appHeapUsedBytes", "appCgroupCpuSeconds", "appCgroupMemoryWorkingSetBytes",
        "appCgroupThrottledPeriods", "appCgroupOomKills"
    )
}

function Get-ObservationValue {
    param(
        [Parameter(Mandatory = $true)]$Observation,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Observation -is [System.Collections.IDictionary]) {
        if ($Observation.Contains($Name)) {
            return $Observation[$Name]
        }
        return $null
    }
    $property = $Observation.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

# A column no source filled is `unavailable`. This is the one place the distinction is enforced, so a
# reader of the CSV never has to ask whether a blank means zero or means missing.
function New-RecoverySampleRow {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][long]$ElapsedMs,
        [Parameter(Mandatory = $true)]$Observation
    )

    $row = [ordered]@{}
    foreach ($column in Get-SampleColumnNames) {
        $value = Get-ObservationValue -Observation $Observation -Name $column
        if ($null -eq $value -or ([string]$value).Length -eq 0) {
            $row[$column] = "unavailable"
            continue
        }
        if ($value -is [bool]) {
            $row[$column] = if ($value) { "true" } else { "false" }
            continue
        }
        if ($value -is [double] -or $value -is [single] -or $value -is [decimal]) {
            $row[$column] = Format-InvariantNumber -Value ([double]$value)
            continue
        }
        $row[$column] = [string]$value
    }
    $row["phase"] = $Phase
    $row["elapsedMs"] = $ElapsedMs
    return [pscustomobject]$row
}

function ConvertTo-CsvField {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)

    if ($Value.IndexOfAny([char[]]@(",", '"', "`r", "`n")) -lt 0) {
        return $Value
    }
    return '"' + $Value.Replace('"', '""') + '"'
}

# Appended with an explicit UTF-8 encoding and no byte order mark: the file is read back by the
# summarizer and by whatever spreadsheet the reader opens it in, and a BOM in the middle of a
# concatenated CSV is a corrupted first column.
function Write-RecoverySampleCsv {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows
    )

    if ($Rows.Count -eq 0) {
        return
    }
    $builder = New-Object Text.StringBuilder
    if (-not (Test-Path -LiteralPath $Path)) {
        [void]$builder.AppendLine((@(Get-SampleColumnNames) -join ","))
    }
    foreach ($row in $Rows) {
        $fields = New-Object 'System.Collections.Generic.List[string]'
        foreach ($column in Get-SampleColumnNames) {
            $fields.Add((ConvertTo-CsvField -Value ([string]$row.$column)))
        }
        [void]$builder.AppendLine(($fields -join ","))
    }
    [IO.File]::AppendAllText($Path, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))
}

# --- the poll itself -------------------------------------------------------------------------------

# One reading of everything, with each reader's cost recorded separately. The durations are not
# decoration: they are what says whether a poll interval was short enough for the timing columns to
# mean anything, and a report that quotes a recovery time without them is quoting a number whose
# resolution is unknown. Every reader is `unavailable`-safe except the ones whose absence would
# invalidate the comparison itself, and those throw.
function Get-RecoveryObservation {
    param(
        [Parameter(Mandatory = $true)][int]$PollIndex,
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][long]$ElapsedMs,
        [AllowEmptyCollection()][string[]]$Lost = @()
    )

    $config = Get-RecoveryConfig
    $observation = [ordered]@{}
    $observation["timestampUtc"] = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
    $observation["timestampMysql"] = Get-MySqlNow
    $observation["pollIndex"] = $PollIndex

    $pollWatch = [Diagnostics.Stopwatch]::StartNew()

    $promWatch = [Diagnostics.Stopwatch]::StartNew()
    # One query, four readings of it. Each reading picks the series its column is actually about: the
    # whole surface for the meters that carry no separating label, the heap area alone for the memory
    # column, and one outcome each for the two retry columns. Fetching separately would be four requests
    # a second through the same socket, and would leave the four readings describing four different
    # instants - which is the one thing a poll is supposed to avoid.
    $roleSamples = @(Invoke-PrometheusQuery -Query (Get-RoleMetricQuery -Node "batch-1") `
            -Description "batch-1 metrics")
    $roleMetrics = ConvertTo-PrometheusSampleMap -Samples $roleSamples -Description "batch-1 metrics"
    $roleHeapMetrics = ConvertTo-PrometheusSampleMap -Samples $roleSamples `
        -Description "batch-1 heap" -LabelName "area" -LabelValue "heap"
    # `outcome` is the label the registering code puts on the retry counter, one series per retryable
    # outcome, spelled in the enum's own lower-hyphenated form. The two are read apart because they are
    # two different events - a pass that held the gate and an attempt that ran and failed - and the
    # column named for the first must not carry the second.
    $roleBusyRetryMetrics = ConvertTo-PrometheusSampleMap -Samples $roleSamples `
        -Description "batch-1 busy retry" -LabelName "outcome" -LabelValue "busy-retry-later"
    $roleRetryableRetryMetrics = ConvertTo-PrometheusSampleMap -Samples $roleSamples `
        -Description "batch-1 retryable retry" -LabelName "outcome" -LabelValue "retryable-failure"
    $pipelineBuckets = Get-PrometheusHistogram `
        -Query '{job="oj-app",node="batch-1",__name__="contest_scoreboard_redis_pipeline_seconds_bucket"}' `
        -Description "redis pipeline buckets"
    $rabbitMetrics = Get-PrometheusLabeledSampleMap `
        -Query '{job="rabbitmq-per-queue",__name__=~"rabbitmq_detailed_queue_(messages_delivered_ack_total|exchange_messages_published_total)"}' `
        -Description "rabbit stream throughput" -LabelName "queue" -LabelValue $config.QueueName
    $observation["promPollMs"] = $promWatch.ElapsedMilliseconds

    # --- checkpoint and scoreboard state ---------------------------------------------------------
    $redisWatch = [Diagnostics.Stopwatch]::StartNew()
    $scoreboard = Get-ScoreboardState
    $info = Get-RedisInfoSection -Section "all" -Description "Redis statistics"
    $commandStats = Get-RedisCommandStats -Info $info
    $latency = Get-RedisLatencyPercentiles -Info $info
    $observation["redisPollMs"] = $redisWatch.ElapsedMilliseconds

    $observation["checkpointPresent"] = $scoreboard.CheckpointPresent
    if ($null -ne $scoreboard.Checkpoint) {
        $observation["checkpointOffset"] = $scoreboard.Checkpoint
    }
    $observation["streamDbPending"] = $scoreboard.StreamDbPending
    $observation["rankingCardinality"] = $scoreboard.RankingCardinality
    $observation["processedCardinality"] = $scoreboard.ProcessedCardinality
    $observation["scoreboardKeyCount"] = $scoreboard.ScoreboardKeyCount

    $observation["redisTotalCommands"] = Get-RedisInfoCounter -Info $info -Name "total_commands_processed"
    $observation["redisInstantaneousOps"] = Get-RedisInfoCounter -Info $info -Name "instantaneous_ops_per_sec"
    $observation["redisKeyspaceHits"] = Get-RedisInfoCounter -Info $info -Name "keyspace_hits"
    $observation["redisKeyspaceMisses"] = Get-RedisInfoCounter -Info $info -Name "keyspace_misses"
    $observation["redisEvictedKeys"] = Get-RedisInfoCounter -Info $info -Name "evicted_keys"
    $observation["redisUsedCpuSys"] = Get-RedisInfoSeconds -Info $info -Name "used_cpu_sys"
    $observation["redisUsedCpuUser"] = Get-RedisInfoSeconds -Info $info -Name "used_cpu_user"
    if ($commandStats.Contains("eval")) {
        $eval = $commandStats["eval"]
        $observation["redisEvalCalls"] = Get-DictionaryValue -Map $eval -Name "calls"
        $observation["redisEvalUsec"] = Get-DictionaryValue -Map $eval -Name "usec"
    }
    if ($commandStats.Contains("restore")) {
        $observation["redisRestoreCalls"] = Get-DictionaryValue -Map $commandStats["restore"] -Name "calls"
    }
    if ($commandStats.Contains("del")) {
        $observation["redisDelCalls"] = Get-DictionaryValue -Map $commandStats["del"] -Name "calls"
    }
    if ($latency.Contains("eval")) {
        $observation["redisLatencyEvalP50Us"] = Get-DictionaryValue -Map $latency["eval"] -Name "p50"
        $observation["redisLatencyEvalP99Us"] = Get-DictionaryValue -Map $latency["eval"] -Name "p99"
    }

    # --- this role's meters ------------------------------------------------------------------------
    $observation["streamPendingEvents"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_pending_events"
    $observation["streamOldestReadySeconds"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_oldest_ready_seconds"
    $observation["appliedOffsetMetric"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_applied_offset"
    $observation["appliedTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_applied_total"
    $observation["rollbackObservedTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_rollback_observed_total"
    $observation["rollbackRestartsTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_rollback_restarts_total"
    $observation["rollbackUnrecoverableTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_rollback_unrecoverable_total"
    # The replay's own failure counter, which is a different event from the one above: this is a replayed
    # chunk whose applied marker could not be written to MySQL, while the rollback counter counts ranges a
    # mode's basis could not rebuild at all. The summary used to publish the rollback counter under this
    # name, which read as "the marker write failed" for a run where no marker write had failed.
    $observation["replayMarkerFailuresTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_recovery_marker_failed_total"
    $observation["rollbackRetryBusyTotal"] = Get-MetricValue -Metrics $roleBusyRetryMetrics -Name "contest_scoreboard_stream_rollback_retry_total"
    $observation["rollbackRetryRetryableTotal"] = Get-MetricValue -Metrics $roleRetryableRetryMetrics -Name "contest_scoreboard_stream_rollback_retry_total"
    $observation["streamFailuresTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_failures_total"
    $observation["streamOffsetGapsTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_offset_gaps_total"
    $observation["streamFailureRestartsTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_failure_restarts_total"
    $observation["streamUnappliedRefusalsTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_unapplied_refusals_total"
    $observation["streamTailProbeFailuresTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_stream_tail_probe_failures_total"
    $observation["redisLuaErrorsTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_lua_errors_total"
    $observation["sequenceRoundsTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_rounds_total"
    $observation["sequenceDuplicatesTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_duplicates_total"
    $observation["sequenceReplayedTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_replayed_total"
    $observation["sequenceFailedTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_failed_total"
    $observation["sequenceWindowsSaturatedTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_windows_saturated_total"
    $observation["sequenceUnresolvedTotal"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_unresolved_total"
    $observation["sequenceMappingSize"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_sequence_mapping_size"
    $observation["hikariActive"] = Get-MetricValue -Metrics $roleMetrics -Name "hikaricp_connections_active"
    $observation["hikariPending"] = Get-MetricValue -Metrics $roleMetrics -Name "hikaricp_connections_pending"
    $observation["appProcessCpu"] = Get-MetricValue -Metrics $roleMetrics -Name "process_cpu_usage"
    $observation["appHeapUsedBytes"] = Get-MetricValue -Metrics $roleHeapMetrics -Name "jvm_memory_used_bytes"
    $observation["appCgroupCpuSeconds"] = Get-MetricValue -Metrics $roleMetrics -Name "cgroup_cpu_usage_seconds_total"
    $observation["appCgroupMemoryWorkingSetBytes"] = Get-MetricValue -Metrics $roleMetrics -Name "cgroup_memory_working_set_bytes"
    $observation["appCgroupThrottledPeriods"] = Get-MetricValue -Metrics $roleMetrics -Name "cgroup_cpu_throttled_periods_total"
    $observation["appCgroupOomKills"] = Get-MetricValue -Metrics $roleMetrics -Name "cgroup_memory_oom_kills_total"
    $observation["redisPipelineCount"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_pipeline_seconds_count"
    $observation["redisPipelineSumSeconds"] = Get-MetricValue -Metrics $roleMetrics -Name "contest_scoreboard_redis_pipeline_seconds_sum"
    $pipelineP95 = Get-PrometheusHistogramQuantile -Buckets $pipelineBuckets -Quantile 0.95
    if ($null -ne $pipelineP95) {
        $observation["redisPipelineP95Seconds"] = $pipelineP95
    }
    $observation["rabbitDeliveredAckTotal"] = Get-DictionaryValue -Map $rabbitMetrics -Name "rabbitmq_detailed_queue_messages_delivered_ack_total"
    $observation["rabbitPublishedTotal"] = Get-DictionaryValue -Map $rabbitMetrics -Name "rabbitmq_detailed_queue_exchange_messages_published_total"

    $pipeline = Get-PipelineOperationalState
    # When the quiescence facts were all read, which is when a drained backlog was observed rather than
    # when the poll that observed it began. The poll stamps its start and then spends 2.3-3.2s reading
    # Prometheus, Redis, the pipeline, MySQL and the API, so an instant taken from the start places every
    # event up to a poll period early - and `T_backlog_drained` is derived from this one, so the error
    # would land in the figure rather than in the noise around it.
    $observation["quiescentObservedAtUtc"] = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")
    $observation["rabbitPollMs"] = $pipeline.RabbitPollMs
    $observation["pipelinePollMs"] = $pipeline.DurationMs
    $observation["judgeOutboxNonPublished"] = $pipeline.JudgeNonPublished
    $observation["unappliedResults"] = $pipeline.ScoreboardUnapplied
    $observation["rabbitLiveReady"] = $pipeline.LiveReady
    $observation["rabbitLiveUnacked"] = $pipeline.LiveUnacked
    $observation["rabbitDeadReady"] = $pipeline.DeadReady
    $observation["rabbitDeadUnacked"] = $pipeline.DeadUnacked
    $observation["streamQueueReady"] = $pipeline.StreamReady
    $observation["streamQueueUnacked"] = $pipeline.StreamUnacked
    $observation["streamQueueConsumers"] = $pipeline.StreamConsumers
    $observation["quiescent"] = $pipeline.Quiescent

    $mysqlWatch = [Diagnostics.Stopwatch]::StartNew()
    $status = Get-MySqlStatusCounters
    $observation["mysqlPollMs"] = $mysqlWatch.ElapsedMilliseconds
    foreach ($pair in @(
            @("mysqlQuestions", "Questions"), @("mysqlComSelect", "Com_select"),
            @("mysqlRowsRead", "Innodb_rows_read"),
            @("mysqlInnodbBufferPoolReadRequests", "Innodb_buffer_pool_read_requests"),
            @("mysqlInnodbBufferPoolReads", "Innodb_buffer_pool_reads"),
            @("mysqlThreadsConnected", "Threads_connected"),
            @("mysqlThreadsRunning", "Threads_running"),
            @("mysqlSlowQueries", "Slow_queries"))) {
        if ($status.Contains($pair[1])) {
            $observation[$pair[0]] = $status[$pair[1]]
        }
    }

    # --- consistency: the oracle first, the API second, and never the other way round ---------------
    $oracleWatch = [Diagnostics.Stopwatch]::StartNew()
    $oracle = Get-OracleDigest
    $oracleObservedAtUtc = [DateTimeOffset]::UtcNow
    $observation["oraclePollMs"] = $oracleWatch.ElapsedMilliseconds
    $counts = Get-ResultCounts
    $observation["oracleObservedAtUtc"] = $oracleObservedAtUtc.UtcDateTime.ToString("o")
    $observation["oracleDigest"] = $oracle.Digest
    $observation["oracleParticipants"] = $oracle.Participants
    $observation["oracleAppliedResults"] = $counts.AppliedResults
    $observation["oracleResolvedResults"] = $counts.ResolvedResults

    $apiWatch = [Diagnostics.Stopwatch]::StartNew()
    $api = Get-ApiScoreboardDigest
    $digestObservedAtUtc = [DateTimeOffset]::UtcNow
    $observation["apiPollMs"] = $apiWatch.ElapsedMilliseconds
    $observation["digestObservedAtUtc"] = $digestObservedAtUtc.UtcDateTime.ToString("o")
    $observation["apiDigest"] = $api.Digest
    $observation["apiParticipants"] = $api.Participants
    $observation["apiEntries"] = $api.Entries
    $observation["apiPages"] = $api.Pages

    if ($digestObservedAtUtc -lt $oracleObservedAtUtc) {
        throw "The API digest was read before the oracle, so a match would prove nothing: MySQL can lag " +
        "Redis but never lead it, and only a digest taken after the oracle squeezes the scoreboard " +
        "between two equal sets."
    }
    $observation["digestMatches"] = [string]$api.Digest -eq [string]$oracle.Digest

    if ($Lost.Count -gt 0) {
        $progress = Get-LostSetProgress -Lost $Lost -CurrentMembers (Get-RedisSetMembers -Key $config.ProcessedKey)
        $observation["lostTotal"] = $progress.LostCount
        $observation["lostReapplied"] = $progress.ReappliedCount
        $observation["lostRemaining"] = $progress.LostCount - $progress.ReappliedCount
        $observation["lostComplete"] = $progress.Complete
    }

    # When the recovery predicate's own facts were all read: the digest above and the lost set just below.
    # `T_consistent` is the first poll whose predicate held, and this is the instant at which it was seen
    # to hold - not the instant the poll began, which is up to a poll period earlier and would credit the
    # mode with a recovery it had not made yet.
    $observation["consistencyObservedAtUtc"] = [DateTimeOffset]::UtcNow.UtcDateTime.ToString("o")

    $observation["pollDurationMs"] = $pollWatch.ElapsedMilliseconds
    return $observation
}

# Both readers of a map that a source may or may not carry. `unavailable` is returned rather than a
# zero, because a counter that a server does not track is not a counter that did not move.
function Get-DictionaryValue {
    param(
        [Parameter(Mandatory = $true)]$Map,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Map.Contains($Name)) {
        return $Map[$Name]
    }
    return "unavailable"
}

function Get-RedisInfoCounter {
    param(
        [Parameter(Mandatory = $true)]$Info,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not $Info.Contains($Name)) {
        return "unavailable"
    }
    return ConvertTo-RequiredInt64 -Value $Info[$Name] -Description "Redis INFO $Name"
}

# Not every number INFO reports is a count. The CPU fields are seconds of CPU time, printed with six
# decimals (`used_cpu_sys:0.215420`), so the integer reader above refuses them - and it refused the
# first poll that reached them, taking the run with it. Reading them as the seconds they are is not a
# loosened parse: the value was never an integer, and rounding it to one would report a CPU that only
# ever ticked in whole seconds, which at this Redis's rate is a column of zeros.
function Get-RedisInfoSeconds {
    param(
        [Parameter(Mandatory = $true)]$Info,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not $Info.Contains($Name)) {
        return "unavailable"
    }
    return ConvertTo-RequiredDouble -Value $Info[$Name] -Description "Redis INFO $Name"
}

function Get-MetricValue {
    param(
        [Parameter(Mandatory = $true)]$Metrics,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Metrics.Contains($Name)) {
        return $Metrics[$Name]
    }
    return "unavailable"
}
