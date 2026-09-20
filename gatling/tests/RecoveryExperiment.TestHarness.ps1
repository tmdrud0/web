# The assertion harness the recovery pilot's three test suites share. Dot-source it, not call it:
#
#     Set-StrictMode -Version Latest
#     $ErrorActionPreference = "Stop"
#     . "$PSScriptRoot\RecoveryExperiment.TestHarness.ps1"
#
# Written rather than Pester: Pester 3.4 is what is installed here, its syntax differs from every later
# version, and a suite whose meaning depends on which Pester happens to be present is not a suite whose
# passing means anything.
#
# One implementation, shared, for the same reason the harness keeps one implementation of the standings
# digest: two implementations of a comparison are free to disagree, and a disagreement between them is
# indistinguishable from the thing under test being wrong.
#
# `Test-Case` invokes its body with `&`, which is a child scope: a plain assignment made inside a case is
# discarded when the case returns. Fixtures shared between cases belong in `$script:`.

$script:testCount = 0
$script:testFailures = New-Object 'System.Collections.Generic.List[string]'
# Set by a suite whose fixtures could not be built at all - a container that would not start, a database
# that would not answer. Reported as a setup failure rather than as a pile of case failures, because the
# cases did not run and saying they failed would be a different claim.
$script:setupError = $null

function Test-Case {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Body
    )

    $script:testCount++
    try {
        & $Body
        Write-Output "  ok   $Name"
    }
    catch {
        $script:testFailures.Add("$Name`: $($_.Exception.Message)")
        Write-Output "  FAIL $Name"
        Write-Output "       $($_.Exception.Message)"
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)]$Condition,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not $Condition) {
        throw "$Description (expected true)"
    }
}

# A number passed as a bareword argument to an untyped parameter keeps the text that was typed for its
# rendering. `Assert-Equal 3L $x` binds Int64 3 whose `[string]` is `"3L"`, while `$x`'s own Int64 3
# renders `"3"` - so the assertion fails on two values that are equal and the message reads
# `(expected '3L', got '3')`, naming no cause. Measured rather than inferred: the same probe binds
# `3kb` as Int32 3 rendering `"3kb"` and `0x1F` as Int32 31 rendering `"0x1F"`, while `[string]` of
# each literal in expression position is the numeral itself.
#
# Comparing by rendered text is this harness's whole method, so a value whose text is not its value
# cannot be compared by it at all. That is refused here, at the argument, rather than reported as a
# mismatch the reader has to diagnose. A quoted `"3L"` is a string and is left alone: the text is then
# what the caller meant.
function Assert-RendersAsItsValue {
    param(
        [Parameter(Mandatory = $true)][AllowNull()]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($null -eq $Value) { return }
    if ($Value -isnot [ValueType]) { return }
    $code = [Type]::GetTypeCode($Value.GetType())
    if ($code -lt [TypeCode]::SByte -or $code -gt [TypeCode]::Decimal) { return }
    $text = [string]$Value
    if ($text -match '^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$') { return }
    throw ("$Description is a number whose text is not its value ('$text'). A bareword argument keeps " +
        "the text that was typed - write a plain numeral such as '3', or quote the value if the text " +
        "is what you mean to compare.")
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)][AllowNull()]$Expected,
        [Parameter(Mandatory = $true)][AllowNull()]$Actual,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Assert-RendersAsItsValue -Value $Expected -Description "the expected value of '$Description'"
    Assert-RendersAsItsValue -Value $Actual -Description "the actual value of '$Description'"

    if ($Expected -is [double] -or $Actual -is [double]) {
        if ([Math]::Abs([double]$Expected - [double]$Actual) -gt 1e-9) {
            throw "$Description (expected '$Expected', got '$Actual')"
        }
        return
    }
    if ([string]$Expected -ne [string]$Actual) {
        throw "$Description (expected '$Expected', got '$Actual')"
    }
}

function Assert-SequenceEqual {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Expected,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Actual,
        [Parameter(Mandatory = $true)][string]$Description
    )

    # Same refusal as `Assert-Equal`, per element. Not redundant with it, and not triggerable the same
    # way: the `[object[]]` parameter converts a suffixed bareword written inside the literal, so
    # `Assert-SequenceEqual @(1L) @(1)` does not throw - measured - while the same value arriving
    # through a variable does, because whichever untyped parameter took the bareword first is where the
    # text was kept.
    foreach ($element in @($Expected)) {
        Assert-RendersAsItsValue -Value $element -Description "an expected element of '$Description'"
    }
    foreach ($element in @($Actual)) {
        Assert-RendersAsItsValue -Value $element -Description "an actual element of '$Description'"
    }
    $left = (@($Expected) | ForEach-Object { [string]$_ }) -join "|"
    $right = (@($Actual) | ForEach-Object { [string]$_ }) -join "|"
    if ($left -ne $right) {
        throw "$Description (expected '$left', got '$right')"
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $threw = $false
    try {
        [void](& $Action)
    }
    catch {
        $threw = $true
    }
    if (-not $threw) {
        throw "$Description (it did not throw)"
    }
}

# Records a fixture failure. A suite calls this from the `catch` around the code that builds its fixtures.
function Set-TestSetupError {
    param([Parameter(Mandatory = $true)]$Error)

    $script:setupError = $Error
}

# The last statement of every suite. It prints the verdict and exits the process with it, which is why it
# returns nothing: a value in the output stream would be merged into the text and the caller would have
# to tell them apart, and every attempt to do that has produced a suite that reported the wrong verdict.
#
# Exit codes: 0 passed, 1 failed or the fixtures could not be built, 3 the suite declined to run (used by
# the suites that need a live server, so that a suite which did not run cannot be read as one that
# passed).
function Write-TestSummary {
    param([Parameter(Mandatory = $true)][string]$Suite)

    Write-Output ""
    if ($null -ne $script:setupError) {
        Write-Output "$Suite`: SETUP FAILED before or during the cases."
        Write-Output "  $($script:setupError.Exception.Message)"
        exit 1
    }
    if ($script:testFailures.Count -eq 0) {
        Write-Output "$Suite`: $script:testCount passed."
        exit 0
    }
    Write-Output "$Suite`: $($script:testFailures.Count) of $script:testCount FAILED."
    foreach ($failure in $script:testFailures) {
        Write-Output "  - $failure"
    }
    exit 1
}

# For a suite that needs a live service and did not find one. Exit 3 says "did not run", which is a
# different result from both 0 and 1 and is the only honest one: the assertions were never made.
function Write-TestNotRun {
    param(
        [Parameter(Mandatory = $true)][string]$Suite,
        [Parameter(Mandatory = $true)][string]$Reason
    )

    Write-Output ""
    Write-Output "$Suite`: NOT RUN (exit 3, not a pass)."
    Write-Output "  $Reason"
    exit 3
}
