<#
Open-arrival burst supply verdict.

The question this file answers is not "did the stack keep up" but "was the stack offered what the
experiment claims to offer". They are different questions and a run cannot answer the second by
looking at the server: a generator that could not schedule a thousand arrivals a second, a
preparation phase that produced too few sessions, and a connection refused below the application all
show up on the server side as "less work arrived", which is indistinguishable from "the system
saturated". Section 3's criterion is therefore the number of submission *starts* - recorded by the
load generator at the instant it dispatches, before any response exists - against the schedule it
was given.

So the verdict is computed from the generator's own record, and every check is kept separately. The
categories are never averaged into a score: `load-generator-rate-failed` (the schedule was not
delivered), `auth-preparation-failed` (the sessions were not there to submit with),
`ingress-connect-failed` (the offer reached a socket that refused it) and
`application-backpressure-observed` (the offer was delivered and the application refused it) are
different findings about different layers, and merging them would make a generator problem read as a
capacity result - which is exactly the mistake the closed-model runs made when 382.3/s offered was
read as a property of the server.

This is a library, not a script: `Run-TradeoffExperiment.ps1` calls it during a run and
`Test-OpenBurstSupply.ps1` calls it on synthetic records. It has no parameters and no top-level
side effects, so it can be dot-sourced from either.
#>

# No Set-StrictMode here on purpose: this file is dot-sourced into the harness's own scope, and
# StrictMode Latest would then apply to all 3,500 lines of it rather than to these functions. The
# checks below are written defensively instead - every field is read through Get-OpenBurstField, and
# a field that is absent is reported as unavailable rather than compared as zero.

function Read-OpenBurstJson {
    <#
    Reads one of the burst's JSON artifacts, or returns $null when it is not there.

    A missing artifact is a fact the verdict must be able to report ("the recorder wrote nothing") and
    not an exception that takes the caller down before it can say so; an artifact that is present but
    unreadable is a real failure and does throw, because a corrupt record is not an absent one.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $raw = Get-Content -LiteralPath $Path -Raw
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    try {
        return ($raw | ConvertFrom-Json)
    } catch {
        throw "$Path is present but not readable as JSON: $_"
    }
}

function Get-OpenBurstField {
    <#
    One field of a ConvertFrom-Json object, or $null when the object or the field is absent.

    Every check below reads through this so that a field the writer never emitted is reported as
    unavailable rather than compared as zero: a missing count is not a count of none.
    #>
    param($Document, [Parameter(Mandatory = $true)][string]$Name)
    if ($null -eq $Document) { return $null }
    $property = $Document.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function ConvertTo-OpenBurstDouble {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string] -and [string]::IsNullOrWhiteSpace($Value)) { return $null }
    $parsed = 0.0
    if (-not [double]::TryParse([string]$Value, [ref]$parsed)) { return $null }
    return $parsed
}

function ConvertTo-OpenBurstLong {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string] -and [string]::IsNullOrWhiteSpace($Value)) { return $null }
    $parsed = 0L
    if (-not [long]::TryParse([string]$Value, [ref]$parsed)) { return $null }
    return $parsed
}

function Get-OpenBurstSupplyVerdict {
    <#
    The supply verdict for one open-arrival burst.

    Inputs:
      -Recorder        the burst's own open-burst-recorder.json (starts, buckets, engine errors)
      -Prep            the preparation phase's auth-prep.json (contexts, login failures)
      -LoginsInWindow  logins the client started inside the measured window
      -ConnectRefusals submissions the client could not connect for at all
      -ServerRefusals  submissions the application answered with a refusal (429/5xx)
      -Unauthenticated submissions the application answered with 401/403
      -TolerancePercent  the per-second bucket tolerance the offer is judged against

    Every check is reported whether it passed or failed. `verdict` is the first category in the fixed
    precedence order that has a failing check, so the categories stay separate findings rather than
    being folded into one; `supplySucceeded` is the narrower statement that the schedule itself was
    delivered, which stays true when the application refused what was offered.

    `-Unauthenticated` is kept apart from `-ServerRefusals` although both are refusals: a 401/403 says
    the session an arrival carried was not accepted, which is a fact about the preparation, while a
    429/5xx says the stack declined work it could see, which is a fact about the stack. Folding the
    two together would let an expired or mis-scoped session be reported as backpressure - the very
    finding this comparison exists to make - attributed to the wrong layer.
    #>
    param(
        # AllowNull on both: an absent recorder or preparation is one of the things this verdict has
        # to be able to report ("it wrote nothing"), so the caller must be able to pass that absence
        # in rather than being refused at binding time before the verdict can say anything.
        [Parameter(Mandatory = $true)][AllowNull()]$Recorder,
        [AllowNull()]$Prep,
        [Parameter(Mandatory = $true)]$LoginsInWindow,
        [Parameter(Mandatory = $true)]$ConnectRefusals,
        [Parameter(Mandatory = $true)]$ServerRefusals,
        [Parameter(Mandatory = $true)][AllowNull()]$Unauthenticated,
        [double]$TolerancePercent = 10,
        [double]$TargetRps = 1000,
        # The steady window's length, and the schedule it implies. Passed rather than assumed: the rate
        # band and the planned steady count are both computed from it, and a run whose hold was not ten
        # seconds would otherwise have its offer judged against a schedule it never had.
        [int]$SteadySeconds = 10,
        [long]$PlannedStarts = 10501
    )

    $findings = New-Object System.Collections.Generic.List[object]
    $failed = New-Object System.Collections.Generic.List[string]
    $addCheck = {
        param([string]$Code, [bool]$Passed, [string]$Detail)
        $findings.Add([pscustomobject]@{ code = $Code; passed = $Passed; detail = $Detail })
        if (-not $Passed) { $failed.Add($Code) }
    }

    $plannedSteady = [long][math]::Round($TargetRps * $SteadySeconds)
    # The band is taken from the schedule itself rather than from a rate times a remembered window: the
    # schedule is the number the run was parameterised with, so a run that asked for a different shape
    # is judged against its own shape and not against this file's default one.
    $lowerStarts = [long][math]::Round($PlannedStarts * 0.95)
    $upperStarts = [long][math]::Round($PlannedStarts * 1.05)
    $lowerRate = $TargetRps * 0.95
    $upperRate = $TargetRps * 1.05

    if ($null -eq $Recorder) {
        & $addCheck "load-generator-rate-failed" $false "the burst wrote no recorder file, so the number of starts it delivered is unavailable"
        return New-OpenBurstVerdict -Findings $findings -Failed $failed -Recorder $null -Prep $Prep `
            -LoginsInWindow $LoginsInWindow -ConnectRefusals $ConnectRefusals -ServerRefusals $ServerRefusals `
            -Unauthenticated $Unauthenticated `
            -TolerancePercent $TolerancePercent -TargetRps $TargetRps -PlannedStarts $PlannedStarts `
            -LowerStarts $lowerStarts -UpperStarts $upperStarts -LowerRate $lowerRate -UpperRate $upperRate `
            -PlannedSteady $plannedSteady
    }

    $starts = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "arrivalCount")
    $inWindow = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "arrivalsInWindow")
    $observedRate = ConvertTo-OpenBurstDouble (Get-OpenBurstField $Recorder "observedRateInWindow")
    $worstDeviation = ConvertTo-OpenBurstDouble (Get-OpenBurstField $Recorder "worstBucketDeviationPercent")
    $engineErrors = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "engineErrors")
    $dropped = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "droppedRecords")
    $anchorSource = Get-OpenBurstField $Recorder "anchorSource"
    $reuse = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "authContextReuse")
    $loaded = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "authContextsLoaded")
    $recorderPlanned = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "plannedStarts")

    # --- the preparation, which is judged first because a burst without sessions cannot be offered ---
    if ($null -eq $Prep) {
        & $addCheck "auth-preparation-failed" $false "the preparation phase wrote no auth-prep.json, so neither the contexts it produced nor the logins it failed are known"
    } else {
        $prepared = ConvertTo-OpenBurstLong (Get-OpenBurstField $Prep "contextsPrepared")
        $loginFailures = ConvertTo-OpenBurstLong (Get-OpenBurstField $Prep "loginFailures")
        $noCookie = ConvertTo-OpenBurstLong (Get-OpenBurstField $Prep "responsesWithoutASessionCookie")
        $unexpectedCookie = ConvertTo-OpenBurstLong (Get-OpenBurstField $Prep "responsesWithAnUnexpectedCookieName")
        $plannedLogins = ConvertTo-OpenBurstLong (Get-OpenBurstField $Prep "plannedLogins")
        & $addCheck "auth-preparation-failed" `
            ([bool]($null -ne $prepared -and $prepared -ge $PlannedStarts)) `
            "the preparation produced $(Format-OpenBurstValue $prepared) sessions for a schedule of $PlannedStarts arrivals (it planned $(Format-OpenBurstValue $plannedLogins) logins): fewer sessions than arrivals means an arrival has to submit with another arrival's session"
        & $addCheck "auth-preparation-failed" `
            ([bool]($null -ne $loginFailures -and $loginFailures -eq 0)) `
            "$(Format-OpenBurstValue $loginFailures) logins failed during preparation"
        & $addCheck "auth-preparation-failed" `
            ([bool]($null -ne $noCookie -and $noCookie -eq 0)) `
            "$(Format-OpenBurstValue $noCookie) login responses set no session cookie"
        & $addCheck "auth-preparation-failed" `
            ([bool]($null -ne $unexpectedCookie -and $unexpectedCookie -eq 0)) `
            "$(Format-OpenBurstValue $unexpectedCookie) login responses set a cookie that is not the configured session cookie"
    }
    & $addCheck "auth-preparation-failed" `
        ([bool]($null -ne $reuse -and $reuse -eq 0)) `
        "$(Format-OpenBurstValue $reuse) arrivals shared a session with an earlier arrival (the recorder had $(Format-OpenBurstValue $loaded) contexts loaded); the pool is served by rotation, so a nonzero count is exactly how short the preparation was"
    & $addCheck "auth-preparation-failed" `
        ([bool]($null -ne $LoginsInWindow -and [long]$LoginsInWindow -eq 0)) `
        "$(Format-OpenBurstValue $LoginsInWindow) logins were started inside the measured window; the window is meant to contain submissions and nothing else"
    & $addCheck "auth-preparation-failed" `
        ([bool]($null -ne $Unauthenticated -and [long]$Unauthenticated -eq 0)) `
        "the application rejected $(Format-OpenBurstValue $Unauthenticated) submissions as unauthenticated (401/403) inside the measured window; the sessions the arrivals carried were not accepted, which is a preparation finding and not the stack declining work it could see"

    # --- the offer itself --------------------------------------------------------------------------
    if ($null -eq $starts) {
        & $addCheck "load-generator-rate-failed" $false "the recorder reported no arrival count at all"
    } else {
        & $addCheck "load-generator-rate-failed" `
            ([bool]($starts -ge $lowerStarts -and $starts -le $upperStarts)) `
            "the generator started $starts submissions against a schedule of $PlannedStarts (accepted band $lowerStarts..$upperStarts)"
    }
    if ($null -eq $observedRate) {
        & $addCheck "load-generator-rate-failed" $false "the recorder reported no in-window rate"
    } else {
        & $addCheck "load-generator-rate-failed" `
            ([bool]($observedRate -ge $lowerRate -and $observedRate -le $upperRate)) `
            "the in-window rate was $([math]::Round($observedRate, 3))/s against the target $TargetRps/s (accepted band $lowerRate..$upperRate)"
    }
    if ($null -eq $worstDeviation) {
        & $addCheck "load-generator-rate-failed" $false "the recorder reported no per-second buckets, so the offer has no second-by-second reading"
    } else {
        & $addCheck "load-generator-rate-failed" `
            ([bool]($worstDeviation -le $TolerancePercent)) `
            "the worst one-second bucket was off its $TargetRps target by $([math]::Round($worstDeviation, 3))%, against a tolerance of $TolerancePercent%"
    }
    & $addCheck "load-generator-rate-failed" `
        ([bool]($null -ne $engineErrors -and $engineErrors -eq 0)) `
        "$(Format-OpenBurstValue $engineErrors) generator-internal errors were recorded"
    & $addCheck "load-generator-rate-failed" `
        ([bool]($null -ne $dropped -and $dropped -eq 0)) `
        "$(Format-OpenBurstValue $dropped) per-arrival records were dropped by the recorder, so the per-attempt evidence is incomplete even though the counts are not"
    & $addCheck "load-generator-rate-failed" `
        ([bool]($anchorSource -eq "marker-user-start")) `
        "the arrival schedule's anchor came from '$anchorSource': the measured window is only the injector's own window when the marker user that runs at injector time zero wrote it"
    & $addCheck "load-generator-rate-failed" `
        ([bool]($null -ne $recorderPlanned -and $recorderPlanned -eq $PlannedStarts)) `
        "the recorder's own plan says $(Format-OpenBurstValue $recorderPlanned) arrivals where the harness scheduled $PlannedStarts"

    # --- below the application ---------------------------------------------------------------------
    & $addCheck "ingress-connect-failed" `
        ([bool]($null -ne $ConnectRefusals -and [long]$ConnectRefusals -eq 0)) `
        "$(Format-OpenBurstValue $ConnectRefusals) submission starts could not connect to the ingress at all, which is a fact about the ingress and not about the judges"
    & $addCheck "application-backpressure-observed" `
        ([bool]($null -ne $ServerRefusals -and [long]$ServerRefusals -eq 0)) `
        "the application refused $(Format-OpenBurstValue $ServerRefusals) of the submissions offered in the measured window (429/5xx); the offer was delivered, so this is the stack's answer to it rather than a load-generator shortfall"

    return New-OpenBurstVerdict -Findings $findings -Failed $failed -Recorder $Recorder -Prep $Prep `
        -LoginsInWindow $LoginsInWindow -ConnectRefusals $ConnectRefusals -ServerRefusals $ServerRefusals `
        -Unauthenticated $Unauthenticated `
        -TolerancePercent $TolerancePercent -TargetRps $TargetRps -PlannedStarts $PlannedStarts `
        -LowerStarts $lowerStarts -UpperStarts $upperStarts -LowerRate $lowerRate -UpperRate $upperRate `
        -PlannedSteady $plannedSteady
}

function Format-OpenBurstValue {
    param($Value)
    if ($null -eq $Value) { return "unavailable" }
    return [string]$Value
}

function New-OpenBurstVerdict {
    param(
        $Findings, $Failed, $Recorder, $Prep, $LoginsInWindow, $ConnectRefusals, $ServerRefusals,
        $Unauthenticated,
        [double]$TolerancePercent, [double]$TargetRps, [long]$PlannedStarts,
        [long]$LowerStarts, [long]$UpperStarts, [double]$LowerRate, [double]$UpperRate, [long]$PlannedSteady
    )

    # The precedence is fixed and documented rather than derived from the findings' order: a
    # preparation shortfall and a refused connection both lower the number of starts, so the category
    # that explains it must be decided before the start count is allowed to name the generator.
    $precedence = @("auth-preparation-failed", "ingress-connect-failed", "load-generator-rate-failed", "application-backpressure-observed")
    $verdict = "target-supplied"
    foreach ($category in $precedence) {
        if ($Failed -contains $category) { $verdict = $category; break }
    }
    $rateFailed = $Failed -contains "load-generator-rate-failed"
    $incomplete = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "incompleteAttempts")
    $completed = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "completedAttempts")

    return [pscustomobject]@{
        verdict = $verdict
        # The schedule was delivered. Kept apart from the verdict on purpose: a delivered offer that
        # the application refused is a supply success and a capacity finding at the same time, and
        # the comparison is allowed to read the arrival rate while still reporting the refusals.
        supplySucceeded = (-not $rateFailed -and -not ($Failed -contains "auth-preparation-failed") -and -not ($Failed -contains "ingress-connect-failed"))
        targetRps = $TargetRps
        plannedStarts = $PlannedStarts
        plannedSteadyArrivals = $PlannedSteady
        starts = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "arrivalCount")
        startsInWindow = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "arrivalsInWindow")
        acceptedStartsBand = @($LowerStarts, $UpperStarts)
        observedRatePerSecond = ConvertTo-OpenBurstDouble (Get-OpenBurstField $Recorder "observedRateInWindow")
        acceptedRateBand = @($LowerRate, $UpperRate)
        worstBucketDeviationPercent = ConvertTo-OpenBurstDouble (Get-OpenBurstField $Recorder "worstBucketDeviationPercent")
        tolerancePercent = $TolerancePercent
        bucketAlignment = Get-OpenBurstField $Recorder "bucketAlignment"
        anchorSource = Get-OpenBurstField $Recorder "anchorSource"
        steadyStartUtc = Get-OpenBurstField $Recorder "steadyStartUtc"
        steadyEndUtc = Get-OpenBurstField $Recorder "steadyEndUtc"
        injectionEndUtc = Get-OpenBurstField $Recorder "injectionEndUtc"
        # Completions are recorded beside the starts and never subtracted from them: a submission that
        # was never answered is a start that still happened, and reading the completion log as the
        # offer is what made an earlier run report 382.3/s against the 653.8/s the database held.
        completedAttempts = $completed
        incompleteAttempts = $incomplete
        okAttempts = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "okAttempts")
        koAttempts = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "koAttempts")
        forcedTermination = Get-OpenBurstField $Recorder "forcedTermination"
        authContextsLoaded = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "authContextsLoaded")
        authContextsServed = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "authContextsServed")
        authContextReuse = ConvertTo-OpenBurstLong (Get-OpenBurstField $Recorder "authContextReuse")
        loginsInWindow = $LoginsInWindow
        connectRefusals = $ConnectRefusals
        serverRefusals = $ServerRefusals
        unauthenticatedSubmissions = $Unauthenticated
        findings = [object[]]$Findings
        failedFindings = [object[]]$Failed
        basis = "starts are counted where the load generator dispatches them, before any response exists (open-burst-recorder.json), so the offered rate does not depend on the server answering; logins, connect refusals and application refusals are read from the client's own request log"
    }
}
