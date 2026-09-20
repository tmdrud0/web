# The scoreboard recovery pilot harness, in one dot-source.
#
#   . "$PSScriptRoot\lib\RecoveryExperiment.ps1"
#
# The order is not arbitrary. Common writes `$script:recoveryConfig` and the SQL, Redis and container
# primitives every other module calls; Oracle and Injector both build on it; the Sampler reads the
# state the Injector restores between; and the Seeder is the only module that creates or removes rows,
# so it loads last and can use all of them.
#
# Dot-sourced rather than imported as a module on purpose: a module has its own scope, and these files
# share `$script:` state - the configuration, the snapshot manifest, the batch-pause flag - which a
# module boundary would hide from the runner. Verified rather than assumed: a `$script:` variable set
# in Common is visible to a function defined in Seeder when both are dot-sourced into the same runner.
#
# Nothing here is loaded by the application. The whole directory is measurement code, and the only
# thing it needs from the product is the instrumentation the product already carries.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:recoveryHarnessRoot = $PSScriptRoot

. "$PSScriptRoot\RecoveryExperiment.Common.ps1"
. "$PSScriptRoot\RecoveryExperiment.Oracle.ps1"
. "$PSScriptRoot\RecoveryExperiment.Injector.ps1"
. "$PSScriptRoot\RecoveryExperiment.Sampler.ps1"
. "$PSScriptRoot\RecoveryExperiment.Seeder.ps1"
