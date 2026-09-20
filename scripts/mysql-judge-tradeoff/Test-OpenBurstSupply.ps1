[CmdletBinding()]
<#
Checks for the open-arrival supply verdict.

These are the parts of the burst's judgement that can be decided without a stack, and they are the
parts a mistake in would be invisible in a run: a start count that quietly came from the completion
log (so a saturated stack looks like a generator shortfall), a category precedence that names the
generator when the preparation was short, or a missing field read as a zero. Everything else about a
burst is a measurement, and a measurement is judged by its own evidence.

Run directly: powershell -ExecutionPolicy Bypass -File Test-OpenBurstSupply.ps1
Exits 0 when every check passes, 1 otherwise, naming each failure.
#>
param()

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "OpenBurstSupply.ps1")

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]

function Assert-Equal {
    param($Expected, $Actual, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if ($Expected -ne $Actual) {
        $script:failures.Add("$Because`: expected [$Expected], got [$Actual]")
    }
}

function Assert-True {
    param($Value, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if (-not $Value) {
        $script:failures.Add("$Because`: expected true, got [$Value]")
    }
}

function Assert-Contains {
    param([string]$Text, [string]$Fragment, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if ($Text -notlike "*$Fragment*") {
        $script:failures.Add("$Because`: [$Text] does not contain [$Fragment]")
    }
}

function New-Recorder {
    <#
    A recorder document as the simulation writes it. Every field is overridable so a case can change
    exactly the one thing it is about, and the defaults describe a burst whose offer was delivered.
    #>
    param(
        [long]$ArrivalCount = 10480,
        [long]$ArrivalsInWindow = 10020,
        [double]$ObservedRate = 1002.0,
        [double]$WorstDeviation = 3.2,
        [long]$EngineErrors = 0,
        [long]$DroppedRecords = 0,
        [string]$AnchorSource = "marker-user-start",
        [long]$AuthContextReuse = 0,
        [long]$AuthContextsLoaded = 12000,
        [long]$PlannedStarts = 10501,
        [long]$Completed = 10480,
        [long]$Incomplete = 0,
        [long]$Ok = 10300,
        [long]$Ko = 180
    )
    return [pscustomobject]@{
        arrivalCount = $ArrivalCount
        arrivalsInWindow = $ArrivalsInWindow
        observedRateInWindow = $ObservedRate
        worstBucketDeviationPercent = $WorstDeviation
        engineErrors = $EngineErrors
        droppedRecords = $DroppedRecords
        anchorSource = $AnchorSource
        authContextReuse = $AuthContextReuse
        authContextsLoaded = $AuthContextsLoaded
        authContextsServed = $ArrivalCount
        plannedStarts = $PlannedStarts
        plannedSteadyArrivals = 10000
        completedAttempts = $Completed
        incompleteAttempts = $Incomplete
        okAttempts = $Ok
        koAttempts = $Ko
        forcedTermination = ($Incomplete -gt 0)
        bucketAlignment = "the steady window cut into whole seconds from its own start, not epoch-aligned seconds"
        steadyStartUtc = "2026-09-20T12:00:01.000Z"
        steadyEndUtc = "2026-09-20T12:00:11.000Z"
        injectionEndUtc = "2026-09-20T12:00:11.000Z"
    }
}

function New-Prep {
    param(
        [long]$ContextsPrepared = 12000,
        [long]$LoginFailures = 0,
        [long]$NoCookie = 0,
        [long]$UnexpectedCookie = 0,
        [long]$PlannedLogins = 12000
    )
    return [pscustomobject]@{
        phase = "auth-preparation"
        contextsPrepared = $ContextsPrepared
        loginFailures = $LoginFailures
        responsesWithoutASessionCookie = $NoCookie
        responsesWithAnUnexpectedCookieName = $UnexpectedCookie
        plannedLogins = $PlannedLogins
        availableAccounts = 12000
        prepRps = 200
        prepSeconds = 60
    }
}

function Get-Verdict {
    param($Recorder, $Prep = $null, $LoginsInWindow = 0, $ConnectRefusals = 0, $ServerRefusals = 0, $Unauthenticated = 0)
    if ($null -eq $Prep) { $Prep = New-Prep }
    return Get-OpenBurstSupplyVerdict -Recorder $Recorder -Prep $Prep -LoginsInWindow $LoginsInWindow `
        -ConnectRefusals $ConnectRefusals -ServerRefusals $ServerRefusals -Unauthenticated $Unauthenticated
}

# --- a delivered offer ---------------------------------------------------------------------------
$clean = Get-Verdict (New-Recorder)
Assert-Equal "target-supplied" $clean.verdict "a burst that started 10,480 of its 10,501 arrivals inside every band is supplied"
Assert-True $clean.supplySucceeded "a supplied burst reports the offer as delivered"
Assert-Equal 10480 $clean.starts "the start count is the recorder's arrival count"
Assert-Equal 10020 $clean.startsInWindow "the in-window count is reported beside the total"

# The point of counting starts where they are dispatched: a submission that was never answered is a
# start that still happened. Reading the completion log as the offer is what made an earlier run
# report 382.3/s against the 653.8/s the database recorded for the same load.
$halfAnswered = New-Recorder -Completed 7000 -Incomplete 3480 -Ok 6900 -Ko 100
$unanswered = Get-Verdict $halfAnswered
Assert-Equal "target-supplied" $unanswered.verdict "an offer whose completions are partly missing is still the offer that was delivered"
Assert-Equal 10480 $unanswered.starts "3480 unanswered submissions do not lower the start count"
Assert-Equal 7000 $unanswered.completedAttempts "the completions are reported beside the starts, not instead of them"
Assert-Equal 3480 $unanswered.incompleteAttempts "the unanswered attempts are reported rather than dropped"

# --- the generator's own findings -----------------------------------------------------------------
$short = Get-Verdict (New-Recorder -ArrivalCount 9100 -ArrivalsInWindow 8900 -ObservedRate 890.0 -Completed 9100)
Assert-Equal "load-generator-rate-failed" $short.verdict "9,100 starts is outside the 9,500..10,500 band"
Assert-True (-not $short.supplySucceeded) "a failed offer is not a delivered one"

$oneBadSecond = Get-Verdict (New-Recorder -WorstDeviation 12.5)
Assert-Equal "load-generator-rate-failed" $oneBadSecond.verdict "a bucket 12.5% off target fails the offer even though the ten-second total is inside its band"

$lumpy = Get-Verdict (New-Recorder -ObservedRate 930.0)
Assert-Equal "load-generator-rate-failed" $lumpy.verdict "an in-window rate of 930/s is outside the 950..1050 band"

$engineError = Get-Verdict (New-Recorder -EngineErrors 2)
Assert-Equal "load-generator-rate-failed" $engineError.verdict "two generator-internal errors fail the offer"

$droppedRecords = Get-Verdict (New-Recorder -DroppedRecords 5)
Assert-Equal "load-generator-rate-failed" $droppedRecords.verdict "dropped per-arrival records make the per-attempt evidence incomplete"

$anchoredFromAnArrival = Get-Verdict (New-Recorder -AnchorSource "first-arrival")
Assert-Equal "load-generator-rate-failed" $anchoredFromAnArrival.verdict "a window anchored on the first arrival instead of the marker is not the injector's window"

# --- preparation ----------------------------------------------------------------------------------
$shortPrep = Get-Verdict (New-Recorder -ArrivalCount 9000 -ArrivalsInWindow 8900 -ObservedRate 890.0) (New-Prep -ContextsPrepared 9000)
Assert-Equal "auth-preparation-failed" $shortPrep.verdict "a short preparation is named ahead of the generator: both are short, and only one of them explains why"

$failedLogins = Get-Verdict (New-Recorder) (New-Prep -ContextsPrepared 11900 -LoginFailures 100)
Assert-Equal "auth-preparation-failed" $failedLogins.verdict "100 failed logins leave 100 arrivals without a session"

$noCookie = Get-Verdict (New-Recorder) (New-Prep -NoCookie 1)
Assert-Equal "auth-preparation-failed" $noCookie.verdict "a login response that set no session cookie is a preparation failure"

$renamedCookie = Get-Verdict (New-Recorder) (New-Prep -UnexpectedCookie 1)
Assert-Equal "auth-preparation-failed" $renamedCookie.verdict "a response that set a different cookie is a preparation failure rather than a replayed session"

$sharedSession = Get-Verdict (New-Recorder -AuthContextReuse 3)
Assert-Equal "auth-preparation-failed" $sharedSession.verdict "three arrivals sharing a session is exactly how short the preparation was"

$leakedLogin = Get-Verdict (New-Recorder) $null 7
Assert-Equal "auth-preparation-failed" $leakedLogin.verdict "seven logins inside the measured window is a preparation leak, not a load-generator shortfall"
Assert-Equal 7 $leakedLogin.loginsInWindow "the logins found inside the window are reported"

$noPrep = Get-OpenBurstSupplyVerdict -Recorder (New-Recorder) -Prep $null -LoginsInWindow 0 -ConnectRefusals 0 -ServerRefusals 0 -Unauthenticated 0
Assert-Equal "auth-preparation-failed" $noPrep.verdict "a burst whose preparation recorded nothing is not supplied, whatever the generator did"

# A 401/403 is a refusal at the application, but it is not the application declining work it could
# see: the session the arrival carried was not accepted. Reporting it as backpressure would attribute
# the one finding this comparison exists to make to the wrong layer.
$rejected = Get-Verdict (New-Recorder -Ok 7000 -Ko 3480) $null 0 0 0 3480
Assert-Equal "auth-preparation-failed" $rejected.verdict "3,480 unauthenticated submissions name the preparation, not the stack"
Assert-True (-not $rejected.supplySucceeded) "a burst whose sessions were not accepted is not a delivered offer"
Assert-Equal 3480 $rejected.unauthenticatedSubmissions "the unauthenticated submissions are reported beside the offer"
Assert-Equal 0 $rejected.serverRefusals "and they are not counted as application refusals"

# --- below the application ------------------------------------------------------------------------
$refused = Get-Verdict (New-Recorder) $null 0 12
Assert-Equal "ingress-connect-failed" $refused.verdict "12 submissions that could not connect are a fact about the ingress"
Assert-True (-not $refused.supplySucceeded) "an offer that reached no socket was not delivered"

$shed = Get-Verdict (New-Recorder -Ok 7000 -Ko 3480) $null 0 0 3480
Assert-Equal "application-backpressure-observed" $shed.verdict "the application refusing what was offered is a finding about the stack"
Assert-True $shed.supplySucceeded "the offer was delivered even though the stack refused 3,480 of it"
Assert-Equal 3480 $shed.serverRefusals "the refusals are reported beside the offer"
Assert-Equal 1 $shed.failedFindings.Count "a shed offer implicates exactly one category"
Assert-Equal "application-backpressure-observed" $shed.failedFindings[0] "and that category is the stack's answer, not the generator's"

# --- missing evidence is not a pass ---------------------------------------------------------------
$noRecorder = Get-OpenBurstSupplyVerdict -Recorder $null -Prep (New-Prep) -LoginsInWindow 0 -ConnectRefusals 0 -ServerRefusals 0 -Unauthenticated 0
Assert-Equal "load-generator-rate-failed" $noRecorder.verdict "a burst that wrote no recorder file is not supplied"
Assert-Equal $null $noRecorder.starts "a missing recorder leaves the start count unavailable rather than zero"

# A record that is missing fields is missing evidence, not a record of nothing: the fields the
# simulation always writes are removed here, and each one that is gone must be reported as
# unavailable rather than quietly counted as zero.
$missingFields = New-Recorder
foreach ($name in @("engineErrors", "droppedRecords", "anchorSource")) {
    $missingFields.PSObject.Properties.Remove($name) | Out-Null
}
$incomplete = Get-Verdict $missingFields
Assert-Equal "load-generator-rate-failed" $incomplete.verdict "a record with no engine-error count is not read as having none"
$engineFinding = @($incomplete.findings | Where-Object { $_.detail -like "*generator-internal errors*" })
Assert-Equal 1 $engineFinding.Count "the engine-error check is reported"
Assert-Contains $engineFinding[0].detail "unavailable" "an absent count is reported as unavailable rather than as zero"

# --- the file the harness actually reads ----------------------------------------------------------
$directory = Join-Path ([System.IO.Path]::GetTempPath()) ("open-burst-supply-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $directory | Out-Null
try {
    $recorderPath = Join-Path $directory "open-burst-recorder.json"
    (New-Recorder) | ConvertTo-Json -Depth 4 | Set-Content $recorderPath -Encoding utf8
    $readBack = Read-OpenBurstJson -Path $recorderPath
    Assert-Equal 10480 $readBack.arrivalCount "the recorder file round trips through the reader the harness uses"
    Assert-Contains $readBack.bucketAlignment "cut into whole seconds" "the reader keeps the field that says how the buckets were cut"

    $absent = Read-OpenBurstJson -Path (Join-Path $directory "no-such-file.json")
    Assert-Equal $null $absent "an absent artifact reads as null rather than throwing"

    Set-Content (Join-Path $directory "broken.json") "{ this is not json" -Encoding utf8
    $threw = $false
    try { Read-OpenBurstJson -Path (Join-Path $directory "broken.json") | Out-Null } catch { $threw = $true }
    Assert-True $threw "a present but corrupt artifact throws rather than reading as absent"
} finally {
    Remove-Item -Recurse -Force $directory -ErrorAction SilentlyContinue
}

if ($script:failures.Count -gt 0) {
    Write-Host "OpenBurstSupply: $($script:failures.Count) of $($script:checks) checks failed"
    foreach ($failure in $script:failures) { Write-Host "  FAILED $failure" }
    exit 1
}
Write-Host "OpenBurstSupply: all $($script:checks) checks passed"
exit 0
