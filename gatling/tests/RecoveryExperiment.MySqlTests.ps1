# Integration test for the seeder and the oracle against the shared `oj_test` database.
#
#   $env:DB_PASSWORD = "<the password the other worktrees use>"
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.MySqlTests.ps1
#
# Gated on DB_PASSWORD. With it unset the suite exits 3 rather than 0: a suite that did not run is not a
# suite that passed, and a silent pass is the one result a test may never report.
#
# What this proves that the unit tests cannot:
#
#   * The seeder's statements run on the real schema, and the rows it reads back are the rows it inserted
#     - including the two checks that make the scope trustworthy (every problem carries the seeded
#     contest, every user carries the prefix and the password the load generator logs in with).
#   * The oracle's SQL produces, from real rows, exactly the standings the contest rules predict. The
#     expectation below is written out by hand from the rules and compared as a digest, so the test does
#     not agree with the oracle by asking it the same question twice: the oracle's numbers and the
#     hand-computed numbers have one implementation between them, and it is the same one the pilot uses
#     to declare a scoreboard consistent.
#   * The delete path takes out this run's rows and nothing else. The sentinel and the residual counts
#     are read before the seed and again after the cleanup, and both have to be identical.
#
# The submissions are inserted directly rather than posted through the API, because the subject here is
# the oracle's reading of the schema. `contest_submission.id` is not AUTO_INCREMENT, so the ids are
# supplied: they are derived from the contest id and are the same width, which the oracle's own
# preconditions require (its tie-break compares submission ids as numbers, the scoreboard's as strings,
# and those two orders agree only while the widths match).
#
# Deliberately included in the fixture, because each is a way the oracle could be wrong without looking
# wrong:
#
#   * A tie on (solved, penalty) - two users on 2 problems and 25 penalty minutes. The ranks they share
#     and the rank they skip are asserted, and the user with the lower id has to come first because the
#     scoreboard's own score ends in `- userId`.
#   * A non-ACCEPTED result that is not a WRONG_ANSWER (`PARTIAL_ACCEPTED`). It counts as a wrong attempt
#     for penalty, and a rule that only knew the word WRONG_ANSWER would score this user 30 minutes
#     instead of 35.
#   * A result whose `provisional_result` is still PENDING and whose `final_result` is WRONG_ANSWER. The
#     oracle reads `COALESCE(final_result, provisional_result)`; reading the provisional column alone
#     would drop this attempt and quietly lower that user's penalty by 5 minutes.
#   * An accepted submission with `scoreboard_applied_at IS NULL` - judged, not yet applied. It has to be
#     absent from the default standings and present in the one that asks for every resolved result.
#   * A submission at minute 0 exactly, the boundary of `GREATEST(CEIL(...), 0)`.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\..\lib\RecoveryExperiment.ps1"
. "$PSScriptRoot\RecoveryExperiment.TestHarness.ps1"

$suiteName = "Seeder and oracle integration test"

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    Write-TestNotRun -Suite $suiteName -Reason (
        "DB_PASSWORD is not set, so there is no password to connect to the shared MySQL with. Run it as:`n" +
        "  `$env:DB_PASSWORD = '<password>'; powershell -NoProfile -ExecutionPolicy Bypass " +
        "-File gatling\tests\RecoveryExperiment.MySqlTests.ps1")
}

$root = (Get-Item "$PSScriptRoot\..\..").FullName
# Untracked (`var/` is ignored), so the evidence this test writes cannot be committed by accident.
$artifacts = Join-Path $root "var\scoreboard-recovery-mysqltest"

$initialized = $false
$cleanupDone = $false
$script:removal = $null
$script:seed = $null

Write-Output $suiteName
Write-Output "  database: $(if ($env:DB_NAME) { $env:DB_NAME } else { 'oj_test' }) on the shared instance"
Write-Output "  evidence: $artifacts"
Write-Output ""

try {
    [void](Initialize-RecoveryExperiment -WorktreeRoot $root -ArtifactDirectory $artifacts `
            -RunId "sqltest" -Mode "stream-offset" -DbPassword $env:DB_PASSWORD `
            -DbName $(if ($env:DB_NAME) { $env:DB_NAME } else { "oj_test" }))
    $initialized = $true
    $config = Get-RecoveryConfig

    # Read before anything is written, so that "unchanged" is a comparison rather than an assumption.
    $script:residualBefore = Get-ResidualRowCounts
    $script:sentinelBefore = Get-NonInterferenceSentinel
    Write-Output "  residual rows before: $(($script:residualBefore.Keys | ForEach-Object { "$_=$($script:residualBefore[$_])" }) -join ' ')"
    Write-Output ""

    [void](Assert-ExperimentDataAbsent -Phase "before the integration test")

    $seed = New-ExperimentSeed -UserCount 4 -ProblemCount 2 -ContestDurationMinutes 30 `
        -EvidenceDirectory $artifacts
    $script:seed = $seed
    Write-Output ("  seeded contest {0} '{1}' {2}..{3}, problems {4}..{5}, users {6}..{7}" -f `
            $seed.ContestId, $seed.ContestName, $seed.StartTimeMysql, $seed.EndTimeMysql, `
        $seed.ProblemIdStart, $seed.ProblemIdEnd, $seed.UserIdStart, $seed.UserIdEnd)
    Write-Output ""

    [void](Assert-ExperimentSeedUsable -Phase "after seeding")
    [void](Assert-NonInterferenceIntact -Before $script:sentinelBefore -Phase "after seeding" `
            -EvidencePath (Join-Path $artifacts "non-interference-after-seed.json"))

    # --- the fixture -------------------------------------------------------------------------------
    #
    # Ten submissions by four users on two problems. Minutes are counted from the contest's own start
    # time read out of the database, not from the host clock, because that is what the application and
    # the oracle both count from.
    $u1 = $seed.UserIdStart
    $u2 = $seed.UserIdStart + 1
    $u3 = $seed.UserIdStart + 2
    $u4 = $seed.UserIdStart + 3
    $p1 = $seed.ProblemIdStart
    $p2 = $seed.ProblemIdStart + 1
    # The ids are derived from the contest id so that a leftover row from another run cannot collide, and
    # they are the same width as each other so that both tie-breaks agree.
    $submissionBase = 7000000000000L + ($seed.ContestId * 100)
    $script:submissionIds = @(1..10 | ForEach-Object { $submissionBase + $_ })

    $insertSql = @"
SET @contest = $($seed.ContestId);
SET @start = (SELECT start_time FROM contest WHERE id = @contest);
SET @base = $submissionBase;

INSERT INTO contest_submission (id, contest_id, problem_id, user_id, submitted_time, code, code_hash) VALUES
  (@base + 1,  @contest, $p1, $u1, @start + INTERVAL 5 MINUTE,  'inttest-1',  'sbrec_inttest_01'),
  (@base + 2,  @contest, $p2, $u1, @start + INTERVAL 20 MINUTE, 'inttest-2',  'sbrec_inttest_02'),
  (@base + 3,  @contest, $p1, $u2, @start + INTERVAL 3 MINUTE,  'inttest-3',  'sbrec_inttest_03'),
  (@base + 4,  @contest, $p2, $u2, @start + INTERVAL 4 MINUTE,  'inttest-4',  'sbrec_inttest_04'),
  (@base + 5,  @contest, $p2, $u2, @start + INTERVAL 10 MINUTE, 'inttest-5',  'sbrec_inttest_05'),
  (@base + 6,  @contest, $p2, $u2, @start + INTERVAL 12 MINUTE, 'inttest-6',  'sbrec_inttest_06'),
  (@base + 7,  @contest, $p1, $u3, @start + INTERVAL 2 MINUTE,  'inttest-7',  'sbrec_inttest_07'),
  (@base + 8,  @contest, $p1, $u3, @start + INTERVAL 30 MINUTE, 'inttest-8',  'sbrec_inttest_08'),
  (@base + 9,  @contest, $p1, $u4, @start,                      'inttest-9',  'sbrec_inttest_09'),
  (@base + 10, @contest, $p2, $u4, @start,                      'inttest-10', 'sbrec_inttest_10');

INSERT INTO contest_submission_result
  (submission_id, contest_id, provisional_result, provisional_judged_at, final_result, final_judged_at, result_saved_at, scoreboard_applied_at)
VALUES
  (@base + 1,  @contest, 'ACCEPTED',         @start + INTERVAL 5 MINUTE + INTERVAL 1 SECOND,  NULL,           NULL,                    @start + INTERVAL 5 MINUTE + INTERVAL 2 SECOND,  @start + INTERVAL 5 MINUTE + INTERVAL 3 SECOND),
  (@base + 2,  @contest, 'ACCEPTED',         @start + INTERVAL 20 MINUTE + INTERVAL 1 SECOND, NULL,           NULL,                    @start + INTERVAL 20 MINUTE + INTERVAL 2 SECOND, @start + INTERVAL 20 MINUTE + INTERVAL 3 SECOND),
  (@base + 3,  @contest, 'ACCEPTED',         @start + INTERVAL 3 MINUTE + INTERVAL 1 SECOND,  NULL,           NULL,                    @start + INTERVAL 3 MINUTE + INTERVAL 2 SECOND,  @start + INTERVAL 3 MINUTE + INTERVAL 3 SECOND),
  (@base + 4,  @contest, 'WRONG_ANSWER',     @start + INTERVAL 4 MINUTE + INTERVAL 1 SECOND,  NULL,           NULL,                    @start + INTERVAL 4 MINUTE + INTERVAL 2 SECOND,  @start + INTERVAL 4 MINUTE + INTERVAL 3 SECOND),
  (@base + 5,  @contest, 'PENDING',          NULL,                                            'WRONG_ANSWER', @start + INTERVAL 10 MINUTE + INTERVAL 2 SECOND, @start + INTERVAL 10 MINUTE + INTERVAL 2 SECOND, @start + INTERVAL 10 MINUTE + INTERVAL 3 SECOND),
  (@base + 6,  @contest, 'ACCEPTED',         @start + INTERVAL 12 MINUTE + INTERVAL 1 SECOND, NULL,           NULL,                    @start + INTERVAL 12 MINUTE + INTERVAL 2 SECOND, @start + INTERVAL 12 MINUTE + INTERVAL 3 SECOND),
  (@base + 7,  @contest, 'PARTIAL_ACCEPTED', @start + INTERVAL 2 MINUTE + INTERVAL 1 SECOND,  NULL,           NULL,                    @start + INTERVAL 2 MINUTE + INTERVAL 2 SECOND,  @start + INTERVAL 2 MINUTE + INTERVAL 3 SECOND),
  (@base + 8,  @contest, 'ACCEPTED',         @start + INTERVAL 30 MINUTE + INTERVAL 1 SECOND, NULL,           NULL,                    @start + INTERVAL 30 MINUTE + INTERVAL 2 SECOND, @start + INTERVAL 30 MINUTE + INTERVAL 3 SECOND),
  (@base + 9,  @contest, 'ACCEPTED',         @start + INTERVAL 1 SECOND,                      NULL,           NULL,                    @start + INTERVAL 2 SECOND,                      @start + INTERVAL 3 SECOND),
  (@base + 10, @contest, 'ACCEPTED',         @start + INTERVAL 1 SECOND,                      NULL,           NULL,                    @start + INTERVAL 2 SECOND,                      NULL);

SELECT CONCAT('SBRE_SQLTEST_INSERTED=', (SELECT COUNT(*) FROM contest_submission WHERE contest_id = @contest), '|', (SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = @contest));
"@
    $insertLines = @(Invoke-SqlScript -Sql $insertSql -Description "integration test fixture for contest $($seed.ContestId)")
    $inserted = @($insertLines | Where-Object { ([string]$_).Trim().StartsWith("SBRE_SQLTEST_INSERTED=") })
    if ($inserted.Count -ne 1) {
        throw "Inserting the fixture reported nothing readable: $($insertLines -join ' | ')"
    }
    Assert-Equal "SBRE_SQLTEST_INSERTED=10|10" ([string]$inserted[0]).Trim() "ten submissions and ten results are in the database"

    # The standings the contest rules predict, written out by hand. Competition rank over (solved,
    # penalty): u1 and u2 tie on (2, 25) and share rank 1, rank 2 is skipped, u4 is 3rd and u3 is 4th.
    #
    #   u1  p1 accepted at 5, p2 accepted at 20                        -> 2 solved, 5 + 20        = 25
    #   u2  p1 accepted at 3, p2 wrong at 4 and 10, accepted at 12     -> 2 solved, 3 + 12 + 2*5  = 25
    #   u4  p1 accepted at 0 (p2 is judged but not applied)           -> 1 solved, 0             = 0
    #   u3  p1 partial-accepted at 2, accepted at 30                   -> 1 solved, 30 + 1*5     = 35
    $expectedApplied = @(
        [pscustomobject]@{ Rank = 1L; UserId = $u1; Solved = 2; Penalty = 25L },
        [pscustomobject]@{ Rank = 1L; UserId = $u2; Solved = 2; Penalty = 25L },
        [pscustomobject]@{ Rank = 3L; UserId = $u4; Solved = 1; Penalty = 0L },
        [pscustomobject]@{ Rank = 4L; UserId = $u3; Solved = 1; Penalty = 35L }
    )
    # With every resolved result counted, u4's second problem arrives: 2 solved and 0 penalty beats the
    # tied pair, so u4 leads and the pair becomes 2nd with rank 3 skipped.
    $expectedAllResolved = @(
        [pscustomobject]@{ Rank = 1L; UserId = $u4; Solved = 2; Penalty = 0L },
        [pscustomobject]@{ Rank = 2L; UserId = $u1; Solved = 2; Penalty = 25L },
        [pscustomobject]@{ Rank = 2L; UserId = $u2; Solved = 2; Penalty = 25L },
        [pscustomobject]@{ Rank = 4L; UserId = $u3; Solved = 1; Penalty = 35L }
    )

    $oracle = Get-OracleDigest
    $oracleAll = Get-OracleDigest -AllResolvedResults
    $script:oracle = $oracle
    $script:oracleAll = $oracleAll
    $expectedDigest = New-StandingsDigest -ContestId $seed.ContestId -Participants 4 -Standings $expectedApplied
    $expectedDigestAll = New-StandingsDigest -ContestId $seed.ContestId -Participants 4 -Standings $expectedAllResolved

    $script:preconditions = Assert-OraclePreconditions -Phase "on the fixture"

    # --- the cases ---------------------------------------------------------------------------------

    Test-Case "the seeded contest is usable and the seeded rows are the ones that were read back" {
        Assert-Equal 4 $script:seed.UserCount "four users were seeded"
        Assert-Equal 2 $script:seed.ProblemCount "two problems were seeded"
        Assert-True ($script:seed.UserIdStart -ge 1) "the users have real ids"
        Assert-Equal ($script:seed.UserIdStart + 3) $script:seed.UserIdEnd "the seeded users are contiguous, so the fixture can name them"
        $usability = Assert-ExperimentSeedUsable -Phase "during the cases"
        Assert-True $usability.insideWindow "the contest window is still open"
        Assert-Equal 0 $usability.submissionsOutsideTheContestPath "no submission of this run went to the plain submission table"
    }

    Test-Case "the oracle ranks the applied results the way the contest rules do" {
        Assert-Equal 4 $script:oracle.Participants "four users have an accepted result and so appear"
        Assert-SequenceEqual @("1|$u1|2|25", "1|$u2|2|25", "3|$u4|1|0", "4|$u3|1|35") `
            @($script:oracle.Standings | ForEach-Object { "$($_.Rank)|$($_.UserId)|$($_.Solved)|$($_.Penalty)" }) `
            "the standings are the hand-computed ones, tie included"
    }

    Test-Case "the oracle's digest equals the digest of the hand-computed standings" {
        Assert-Equal $expectedDigest.Digest $script:oracle.Digest "the digest of the oracle's standings is the digest of the expected ones"
        Assert-Equal 64 $script:oracle.Digest.Length "the digest is a SHA256 hex string"
    }

    Test-Case "a result MySQL has judged but the scoreboard has not applied is outside the default standings" {
        Assert-SequenceEqual @("1|$u4|2|0", "2|$u1|2|25", "2|$u2|2|25", "4|$u3|1|35") `
            @($script:oracleAll.Standings | ForEach-Object { "$($_.Rank)|$($_.UserId)|$($_.Solved)|$($_.Penalty)" }) `
            "the unapplied result appears only when every resolved result is asked for"
        Assert-Equal $expectedDigestAll.Digest $script:oracleAll.Digest "the all-resolved digest is the expected one"
        Assert-True ($script:oracle.Digest -ne $script:oracleAll.Digest) "the boundary changes the standings, so the default is not silently reading everything"
    }

    Test-Case "the partial-accepted attempt and the final result are both counted as wrong attempts" {
        $u2Entry = @($script:oracle.Standings | Where-Object { $_.UserId -eq $u2 })[0]
        $u3Entry = @($script:oracle.Standings | Where-Object { $_.UserId -eq $u3 })[0]
        # u2's penalty is 12 + 2*5 = 22 on p2 plus 3 on p1. Reading the provisional column alone would
        # drop the PENDING-then-final row and give 12 + 1*5 = 17.
        Assert-Equal 25 $u2Entry.Penalty "the final result is preferred to the provisional one, so both wrong attempts count"
        # u3's penalty is 30 + 1*5 = 35. A rule that only knew the word WRONG_ANSWER would give 30.
        Assert-Equal 35 $u3Entry.Penalty "a partial-accepted attempt before the accepted one counts as a wrong attempt"
    }

    Test-Case "the oracle's preconditions hold over this fixture" {
        Assert-Equal 0 $script:preconditions.appliedPendingResults "no result is both applied and PENDING"
        Assert-Equal 1 $script:preconditions.contestsWithResults "only this contest has stored results"
        Assert-Equal 3 ($script:preconditions.participantIdMax - $script:preconditions.participantIdMin) "the participant ids span less than the penalty weight"
        Assert-Equal "13..13" $script:preconditions.submissionIdDigits "every submission id is the same width, so both tie-breaks order them alike"
    }

    Test-Case "the result counts separate judged from applied" {
        $counts = Get-ResultCounts
        Assert-Equal 10 $counts.Submissions "ten submissions"
        Assert-Equal 10 $counts.ResolvedResults "ten resolved results"
        Assert-Equal 9 $counts.AppliedResults "nine of them applied"
        Assert-Equal 6 $counts.AppliedAcceptedResults "six applied accepted results"
        Assert-Equal 4 $counts.SubmittingUsers "four users submitted"
    }

    Test-Case "the reflection latency is measured from the judgement to the applied mark" {
        $stats = Get-ReflectLatencyStats -AppliedAtOrAfter $script:seed.StartTimeMysql -Description "integration test fixture"
        Assert-Equal 9 $stats.Samples "nine applied results carry a latency"
        Assert-Equal "1000" $stats.MinMs "the result judged two seconds after submission reflects in one"
        Assert-Equal "2000" $stats.MaxMs "the rest reflect two seconds after their judgement"
        Assert-Equal "2000" $stats.P50Ms "the median is two seconds"
        Assert-Equal "1889" $stats.MeanMs "the mean carries the one shorter sample"
    }

    Test-Case "a window that contains nothing reports unavailable rather than zero" {
        # A lower bound after every applied mark. Zero samples is the shape a broken poll would produce,
        # and the difference between "no latency" and "zero latency" is the difference between an absent
        # measurement and a perfect one.
        $stats = Get-ReflectLatencyStats -AppliedAtOrAfter $script:seed.EndTimeMysql -Description "empty window"
        Assert-Equal 0 $stats.Samples "the window is empty"
        Assert-Equal "unavailable" $stats.P50Ms "an empty window reports unavailable"
        Assert-Equal "unavailable" $stats.MaxMs "an empty window reports unavailable"
    }

    Test-Case "a cleanup without a seed refuses to run" {
        # The failure this guards against is a cleanup that runs before the seed set the scope and
        # therefore scopes its statements to the default contest id, which belongs to somebody else.
        $config = Get-RecoveryConfig
        $config.ContestScopeFromSeed = $false
        try {
            Assert-Throws { [void](Remove-ExperimentData -EvidenceDirectory $artifacts) } "a cleanup without a seeded scope is refused"
        }
        finally {
            $config.ContestScopeFromSeed = $true
        }
    }

    Test-Case "a scope that names a contest this run did not seed is refused" {
        # The other half: even with the flag set, the row the scope points at has to carry this run's
        # name. A cleanup aimed at a plausible-looking id it did not create is the failure a scope alone
        # cannot catch.
        $config = Get-RecoveryConfig
        $realContest = $config.ContestId
        $config.ContestId = 1
        try {
            if ($realContest -ne 1) {
                Assert-Throws { [void](Remove-ExperimentData -EvidenceDirectory $artifacts) } "the contest name is checked against the scope"
            }
            else {
                Assert-True $true "the seeded contest happens to be contest 1, so this case cannot distinguish"
            }
        }
        finally {
            $config.ContestId = $realContest
        }
    }

    # --- cleanup -----------------------------------------------------------------------------------
    #
    # Inside the try, so that a failure in the cases still takes the rows out. The guard is the seed's
    # own flag: the scope is only set after the seeder has read back the row it inserted, so this cannot
    # delete a contest that was already there.
    $script:removal = Remove-ExperimentData -EvidenceDirectory $artifacts
    $cleanupDone = $true

    Write-Output ""
    Write-Output "  removed $($script:removal.totalDeleted) row(s) in $(@($script:removal.steps).Count) scoped statement(s); evidence: $(Join-Path $artifacts 'removed-rows.json')"
    Write-Output ""

    Test-Case "the cleanup removed every row the run created, and only those" {
        [void](Assert-ExperimentDataAbsent -Phase "after the integration test")
        $steps = @($script:removal.steps)
        Assert-True ($steps.Count -ge 10) "the cleanup reports a count for every scoped table, not just the ones it deleted from"

        # The composition, not a total: the ten submissions and their ten results are the fixture, the
        # two problems and the contest are the seed, and the four users are the seed's. Asserting a bare
        # sum would pass just as well if the scope had reached a row this run did not create.
        $expectedDeleted = [ordered]@{
            "contest_submission_result" = 10
            "contest_submission" = 10
            "problem" = 2
            "contest" = 1
            '`user`' = 4
        }
        foreach ($step in $steps) {
            $expected = if ($expectedDeleted.Contains($step.table)) { $expectedDeleted[$step.table] } else { 0 }
            Assert-Equal $expected $step.deleted "'$($step.table)' lost the rows this run put in it"
            Assert-Equal 0 $step.remaining "'$($step.table)' has no scoped row left"
            Assert-True (-not [string]::IsNullOrWhiteSpace([string]$step.sql)) "the statement run against '$($step.table)' is recorded"
            Assert-True (-not [string]::IsNullOrWhiteSpace([string]$step.note)) "why '$($step.table)' is in scope is recorded"
        }
        Assert-Equal 27 $script:removal.totalDeleted "the total is the fixture, the seed and the seeded users, and nothing else"
    }

    Test-Case "the cleanup left the rows the experiment does not own exactly as they were" {
        $residualAfter = Get-ResidualRowCounts
        foreach ($key in $script:residualBefore.Keys) {
            Assert-Equal $script:residualBefore[$key] $residualAfter[$key] "the residual row count of '$key' is unchanged"
        }
        [void](Assert-NonInterferenceIntact -Before $script:sentinelBefore -Phase "after the cleanup" `
                -EvidencePath (Join-Path $artifacts "non-interference-after-cleanup.json"))
    }

    Test-Case "a cleanup that has already run refuses a second one instead of failing inside the name check" {
        # The run's own finally block calls the cleanup again when it cannot tell whether the first call
        # got through. Whether it can tell depends on this flag, which the cleanup clears once its rows
        # are out: the scope then names a contest that no longer exists, so a second call has to be
        # refused at the guard rather than run its statements against a stale id and throw from the name
        # check - a failure a reader would have to go and diagnose, and the wrong one at that.
        $config = Get-RecoveryConfig
        Assert-Equal $false $config.ContestScopeFromSeed "cleaning up cleared the scope it used"
        Assert-Throws { [void](Remove-ExperimentData -EvidenceDirectory $artifacts) } `
            "a second cleanup is refused"
        Assert-Equal $false $config.ContestScopeFromSeed "and refusing it does not re-arm the scope"
    }

    # --- leftovers from an interrupted run ---------------------------------------------------------
    #
    # Last, and after the fixture's cleanup, because these cases write rows on purpose. A run that was
    # interrupted has to be repeatable from the same command, and the only way to know that the
    # recovery path works is to leave the two shapes an interruption leaves behind and take them out
    # again. Its own evidence directory, so re-seeding does not overwrite the fixture's seed.json.
    $leftoverArtifacts = Join-Path $artifacts "leftovers"

    Test-Case "a prefix-wide read finds nothing once this run has been cleaned up" {
        $found = Get-ExperimentRowsInDatabase
        Assert-Equal 0 $found.ContestCount "no contest carries the experiment prefix"
        Assert-Equal 0 $found.UserCount "no user carries the experiment prefix"
    }

    Test-Case "taking back leftovers that do not exist is a no-op, not a failure" {
        $leftovers = Remove-ExperimentLeftovers -EvidenceDirectory $leftoverArtifacts
        Assert-Equal $false $leftovers.cleaned "it reports that it cleaned nothing"
        Assert-Equal 0 @($leftovers.ourContest).Count "and names no contest as its own"
        Assert-Equal 0 @($leftovers.otherRunsContests).Count "and blames no other run"
    }

    # The first shape: the seed completed and the run died before the cleanup, so the contest and its
    # users are both there.
    $script:leftoverSeed = New-ExperimentSeed -UserCount 3 -ProblemCount 2 -ContestDurationMinutes 30 `
        -EvidenceDirectory $leftoverArtifacts
    try {
        Test-Case "leftover rows are attributed to the run id that wrote them" {
            $found = Get-ExperimentRowsInDatabase
            Assert-Equal 1 $found.ContestCount "the seeded contest is found by prefix"
            Assert-Equal 3 $found.UserCount "the seeded users are found by prefix"
            Assert-Equal (Get-RecoveryConfig).RunId $found.Contests[0].RunId "the contest is attributed to this run"
            Assert-SequenceEqual @("sqltest", "sqltest", "sqltest") @($found.Users | ForEach-Object { $_.RunId }) `
                "and so is every user"

            $leftovers = Remove-ExperimentLeftovers -EvidenceDirectory $leftoverArtifacts
            Assert-Equal $true $leftovers.cleaned "the leftovers are taken back"
            Assert-Equal 6 $leftovers.removed.totalDeleted "one contest, two problems and three users"
            Assert-Equal 1 @($leftovers.ourContest).Count "the record names the contest it adopted"
            Assert-Equal 0 @($leftovers.otherRunsContests).Count "and found no other run's rows to report"
            Assert-Equal $false (Get-RecoveryConfig).ContestScopeFromSeed `
                "taking a leftover back clears the scope it adopted on the way"
            [void](Assert-ExperimentDataAbsent -Phase "after taking back an interrupted run")
        }

        # The second shape: the contest is gone but its users are not, which is what an interruption
        # between the two deletes leaves. The contest has to go first for the scope to be gone with it,
        # and its problems before it - `problem.contest_id` is ON DELETE SET NULL, so deleting the
        # contest first would leave them orphaned with no column left to scope them by.
        $script:leftoverSeed = New-ExperimentSeed -UserCount 3 -ProblemCount 2 -ContestDurationMinutes 30 `
            -EvidenceDirectory $leftoverArtifacts
        $orphanedContest = $script:leftoverSeed.ContestId
        [void](Invoke-SqlScript -Sql "DELETE FROM problem WHERE contest_id = $orphanedContest" `
                -Description "problems of the interrupted run's contest")
        [void](Invoke-SqlScript -Sql "DELETE FROM contest WHERE id = $orphanedContest" `
                -Description "the interrupted run's contest, leaving its users")

        Test-Case "a run whose contest is gone still takes its own users back" {
            $found = Get-ExperimentRowsInDatabase
            Assert-Equal 0 $found.ContestCount "the contest is gone"
            Assert-Equal 3 $found.UserCount "its users are not"

            $leftovers = Remove-ExperimentLeftovers -EvidenceDirectory $leftoverArtifacts
            Assert-Equal $true $leftovers.cleaned "the users are taken back"
            Assert-Equal 3 $leftovers.removed.totalDeleted "three users and nothing else"
            Assert-True (([string]$leftovers.removed.scope) -like "*user*") "the record names the scope it used"
            Assert-Equal $false (Get-RecoveryConfig).ContestScopeFromSeed `
                "the users-only path clears the scope too, though it never used it"
            [void](Assert-ExperimentDataAbsent -Phase "after taking back the users of an interrupted run")
        }
    }
    finally {
        # The cases above clean up after themselves when they pass. A failure would leave rows behind
        # with the fixture's own cleanup already done, so the guard is repeated here.
        if ((Get-RecoveryConfig).ContestScopeFromSeed) {
            $remaining = Get-ExperimentRowsInDatabase
            if ($remaining.ContestCount -gt 0 -or $remaining.UserCount -gt 0) {
                Write-Output ""
                Write-Output "  leftovers after a failure: $($remaining.ContestCount) contest(s), $($remaining.UserCount) user(s)"
                try {
                    [void](Remove-ExperimentLeftovers -EvidenceDirectory $leftoverArtifacts)
                }
                catch {
                    Write-Output "  taking them back also failed: $($_.Exception.Message)"
                    if ($null -eq $script:setupError) { Set-TestSetupError $_ }
                }
            }
        }
    }

}
catch {
    Set-TestSetupError $_
}
finally {
    if ($initialized -and -not $cleanupDone -and (Get-RecoveryConfig).ContestScopeFromSeed) {
        # A failure before the cleanup still takes the rows out, and if that also fails it is reported
        # without replacing the original error - the first failure is the one worth reading.
        try {
            $script:removal = Remove-ExperimentData -EvidenceDirectory $artifacts
            Write-Output ""
            Write-Output "  cleanup after a failure removed $($script:removal.totalDeleted) row(s)"
        }
        catch {
            Write-Output ""
            Write-Output "  cleanup after a failure also failed: $($_.Exception.Message)"
            if ($null -eq $script:setupError) {
                Set-TestSetupError $_
            }
        }
    }
}

Write-TestSummary -Suite $suiteName
