# Tests for the runner's per-node unacknowledged reader.
#
# Why this file exists: the first fault run was lost to this reader. It read the unacknowledged count
# from the live queue's consumer_details, which on RabbitMQ 4.1 carries no such field, so every
# channel read as null; null means "unreadable" to the trigger, so a node that was demonstrably
# mid-judgement was recorded as having no active work and the run was refused as a recovery result.
# The count lives on /api/channels, and the two endpoints have to be joined by channel name.
#
# The runner is a script with top-level side effects, so it cannot be dot-sourced and the readers
# cannot be imported normally. They are lifted here by parsing the runner and taking the function
# definitions' own source text: the file is never executed, and what these tests exercise is the code
# the runner will run rather than a retyped copy of it. The broker is then replaced with a stub, so
# the tests say what the reader does with a given payload without needing a broker to be up.
$ErrorActionPreference = "Stop"

$script:Failures = 0
function Assert-Equal {
    param($Expected, $Actual, [string]$Label)
    if ($Expected -eq $Actual) {
        Write-Host "  ok   $Label"
    } else {
        Write-Host "  FAIL $Label (expected '$Expected', got '$Actual')"
        $script:Failures++
    }
}
function Assert-True {
    param($Condition, [string]$Label)
    if ($Condition) { Write-Host "  ok   $Label" } else { Write-Host "  FAIL $Label"; $script:Failures++ }
}

$runnerPath = Join-Path $PSScriptRoot "Run-TradeoffExperiment.ps1"
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) { throw "the runner did not parse" }

$wanted = @("ConvertTo-DoubleOrNull", "Get-RabbitApiBody", "Get-RabbitNumber", "Get-RabbitAddressMap", "Get-RabbitChannels", "Get-RabbitNodeUnacked")
$definitions = $ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and ($wanted -contains $node.Name)
}, $true)
$lifted = @($definitions | ForEach-Object { $_.Name })
foreach ($name in $wanted) {
    if ($lifted -notcontains $name) { throw "function $name was not found in the runner" }
}
foreach ($definition in $definitions) { . ([scriptblock]::Create($definition.Extent.Text)) }

# The broker and the container lookup are replaced after the lift, so the lifted reader runs unchanged
# against a payload this file controls. A reader tested against a live broker could only be tested on
# whatever the broker happened to be holding.
$script:ApiBodies = @{}
$script:AddressMap = @{}
function Get-RabbitApiBody {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not $script:ApiBodies.ContainsKey($Path)) { return $null }
    return $script:ApiBodies[$Path]
}
function Get-RabbitAddressMap { return $script:AddressMap }

$script:rabbitLiveQueue = "contest.judge.live"
$queuePath = "/api/queues/%2F/contest.judge.live"
$channelsPath = "/api/channels"

# Builds a live-queue body whose consumer_details name the given channels, exactly as RabbitMQ 4.1
# does: the entry carries channel_details.name and channel_details.peer_host, and no unacknowledged
# count of its own. The peer host is taken from the channel name, because the broker's channel names
# are what carry it ("<peer_host>:<port> -> <server>:<port> (<n>)") and each judge node's sixteen
# channels have to resolve to that node rather than to whoever is listed first.
#
# Every body is round-tripped through JSON. A PowerShell literal is not a safe stand-in for the
# payload here: an empty array in a hashtable literal and an empty array straight off the wire do not
# always come back the same way, and "empty" versus "absent" is one of the distinctions these readers
# are required to keep.
function New-QueueBody {
    param([string[]]$ChannelNames)
    $entries = @()
    foreach ($name in $ChannelNames) {
        $peer = ([string]$name).Split(":")[0]
        $entries += [pscustomobject]@{
            arguments = @{}
            channel_details = [pscustomobject]@{ name = $name; peer_host = $peer; peer_port = 5672; node = "rabbit@rabbitmq" }
            ack_required = $true
            active = $true
            consumer_tag = "amq.ctag-$name"
            exclusive = $false
            prefetch_count = 1
            queue = [pscustomobject]@{ name = "contest.judge.live"; vhost = "/" }
        }
    }
    $json = [pscustomobject]@{
        name = "contest.judge.live"
        consumers = $entries.Count
        consumer_details = $entries
    } | ConvertTo-Json -Depth 6 -Compress
    return ($json | ConvertFrom-Json)
}

# The channel list is where the count actually lives.
function New-ChannelBody {
    param([hashtable]$UnackedByName)
    $rows = @()
    foreach ($name in $UnackedByName.Keys) {
        $peer = ([string]$name).Split(":")[0]
        $rows += [pscustomobject]@{
            name = $name
            node = "rabbit@rabbitmq"
            number = 1
            prefetch_count = 1
            messages_unacknowledged = $UnackedByName[$name]
            consumer_count = 1
            connection_details = [pscustomobject]@{ peer_host = $peer; peer_port = 5672; name = "$peer`:5672 -> 172.26.0.3:5672" }
        }
    }
    $json = @($rows) | ConvertTo-Json -Depth 6 -Compress
    if ($rows.Count -eq 0) { return @() }
    return ($json | ConvertFrom-Json)
}

Write-Host "RabbitNodeUnackedReader:"

# -- 1. the count comes from the channel list, joined by name ---------------------------------------
$script:AddressMap = @{ "172.26.0.9" = "judge-1"; "172.26.0.10" = "judge-2" }
$j1 = @(); $j2 = @()
foreach ($i in 1..16) { $j1 += "172.26.0.9:5672 -> 172.26.0.3:5672 ($i)" }
foreach ($i in 17..32) { $j2 += "172.26.0.10:5672 -> 172.26.0.3:5672 ($i)" }
$script:ApiBodies[$queuePath] = New-QueueBody -ChannelNames ($j1 + $j2)
$j1Unacked = @{}; $j2Unacked = @{}
foreach ($name in $j1) { $j1Unacked[$name] = 0 }
$j1Unacked[$j1[0]] = 1; $j1Unacked[$j1[1]] = 1; $j1Unacked[$j1[2]] = 1; $j1Unacked[$j1[3]] = 1
foreach ($name in $j2) { $j2Unacked[$name] = 0 }
$j2Unacked[$j2[0]] = 1; $j2Unacked[$j2[1]] = 1
$script:ApiBodies[$channelsPath] = @((New-ChannelBody -UnackedByName $j1Unacked)) + @((New-ChannelBody -UnackedByName $j2Unacked))

$channels = Get-RabbitChannels
Assert-Equal 32 @($channels).Count "the queue's 32 consumer channels are all read"
Assert-Equal 0 @($channels | Where-Object { $null -eq $_.unacked }).Count "every channel yielded an unacknowledged count"
$node1 = Get-RabbitNodeUnacked -Node "judge-1"
Assert-Equal 4 $node1.unacked "judge-1's four held messages are summed"
Assert-Equal 16 $node1.channels "judge-1's sixteen channels are counted"
Assert-Equal 32 $node1.totalChannels "the live queue's whole consumer set is reported alongside"
$node2 = Get-RabbitNodeUnacked -Node "judge-2"
Assert-Equal 2 $node2.unacked "judge-2's two held messages are summed separately"
Assert-Equal 16 $node2.channels "judge-2's sixteen channels are counted"

# -- 2. a channel whose peer resolves to no node is not attributed to one --------------------------
$strayName = "172.26.0.99:5672 -> 172.26.0.3:5672 (1)"
$script:ApiBodies[$queuePath] = New-QueueBody -ChannelNames @($strayName)
$script:ApiBodies[$channelsPath] = New-ChannelBody -UnackedByName @{ $strayName = 1 }
$stray = Get-RabbitChannels
Assert-True ($null -eq @($stray)[0].node) "an address the map does not know leaves the node null rather than guessing one"
Assert-Equal 0 (Get-RabbitNodeUnacked -Node "judge-1").unacked "a stray channel is not counted against judge-1"

# -- 3. a live queue with no consumers is a real zero, not an unreadable broker --------------------
$script:ApiBodies[$queuePath] = New-QueueBody -ChannelNames @()
$script:ApiBodies[$channelsPath] = @()
$empty = Get-RabbitChannels
Assert-True ($null -ne $empty) "an empty consumer list is not reported as an unreadable broker"
Assert-Equal 0 @($empty).Count "an empty consumer list yields no rows"
$emptyNode = Get-RabbitNodeUnacked -Node "judge-1"
Assert-True ($null -ne $emptyNode) "the node reading is a zero rather than null when the queue has no consumers"
Assert-Equal 0 $emptyNode.unacked "the zero is reported as zero"
Assert-Equal 0 $emptyNode.channels "and with no channels counted"

# -- 4. an unreadable endpoint is null, and stays distinguishable from the zero above ---------------
$script:ApiBodies.Remove($queuePath)
Assert-True ($null -eq (Get-RabbitChannels)) "an unreadable queue endpoint is null"
Assert-True ($null -eq (Get-RabbitNodeUnacked -Node "judge-1")) "and the node reading is null with it"

$n1 = "172.26.0.9:5672 -> 172.26.0.3:5672 (1)"
$n2 = "172.26.0.9:5672 -> 172.26.0.3:5672 (2)"
$script:ApiBodies[$queuePath] = New-QueueBody -ChannelNames @($n1, $n2)
$script:ApiBodies.Remove($channelsPath)
Assert-True ($null -eq (Get-RabbitChannels)) "an unreadable channel list is null rather than a set of zeros"

# -- 5. channels that resolve but carry no count are unreadable, not "nothing held" -----------------
# This is the shape of the original defect: the payload has the channels but not the field. Reporting
# it as zero is what let a mid-judgement node be recorded as idle.
$script:ApiBodies[$queuePath] = New-QueueBody -ChannelNames @($n1, $n2)
$script:ApiBodies[$channelsPath] = @(
    [pscustomobject]@{ name = $n1; node = "rabbit@rabbitmq"; prefetch_count = 1; consumer_count = 1
        connection_details = [pscustomobject]@{ peer_host = "172.26.0.9"; peer_port = 5672 } },
    [pscustomobject]@{ name = $n2; node = "rabbit@rabbitmq"; prefetch_count = 1; consumer_count = 1
        connection_details = [pscustomobject]@{ peer_host = "172.26.0.9"; peer_port = 5672 } }
)
$noCount = Get-RabbitChannels
Assert-Equal 2 @($noCount).Count "the channels are still listed"
Assert-Equal 2 @($noCount | Where-Object { $null -eq $_.unacked }).Count "with no count on either"
$noCountNode = Get-RabbitNodeUnacked -Node "judge-1"
Assert-True ($null -eq $noCountNode) "a payload with channels but no counts reads as unreadable, not as an idle node"

# -- 6. a channel the queue names but the channel list does not answer for --------------------------
$script:ApiBodies[$queuePath] = New-QueueBody -ChannelNames @($n1, $n2)
$script:ApiBodies[$channelsPath] = New-ChannelBody -UnackedByName @{ $n1 = 1 }
$partial = Get-RabbitChannels
Assert-Equal 2 @($partial).Count "both channels are listed"
Assert-Equal 1 @($partial | Where-Object { $null -eq $_.unacked }).Count "the one the channel list did not answer for stays null"
$partialNode = Get-RabbitNodeUnacked -Node "judge-1"
Assert-Equal 1 $partialNode.unacked "the resolved channel is still summed"
Assert-Equal 1 $partialNode.unreadChannels "and the unresolved one is counted rather than dropped"

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "RabbitNodeUnackedReader: all checks passed"
    exit 0
}
Write-Host "RabbitNodeUnackedReader: $script:Failures check(s) failed"
exit 1
