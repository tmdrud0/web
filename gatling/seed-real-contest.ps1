param(
    [string]$Prefix = ("realgatling_" + (Get-Date -Format "yyyyMMddHHmmss")),
    [int]$UserCount = 1000,
    [int]$ProblemCount = 5,
    [int]$DurationMinutes = 240,
    [int]$StartOffsetMinutes = 10
)

$ErrorActionPreference = "Stop"

if ($UserCount -lt 1) {
    throw "UserCount must be >= 1"
}
if ($ProblemCount -lt 1) {
    throw "ProblemCount must be >= 1"
}

$contestName = "${Prefix}_contest"
$sql = @"
SET SESSION cte_max_recursion_depth = GREATEST($UserCount, $ProblemCount) + 100;

INSERT INTO contest (name, start_time, end_time)
VALUES (
  '$contestName',
  DATE_SUB(UTC_TIMESTAMP(), INTERVAL $StartOffsetMinutes MINUTE),
  DATE_ADD(UTC_TIMESTAMP(), INTERVAL $DurationMinutes MINUTE)
);

SET @contest_id := LAST_INSERT_ID();

INSERT INTO problem (name, contest_id, contest_num)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < $ProblemCount
)
SELECT CONCAT('${Prefix}_problem_', n), @contest_id, n
FROM seq;

INSERT INTO ``user`` (name, pass, solved_count, streak_last_solved_date, streak_current_streak, streak_longest_streak)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < $UserCount
)
SELECT CONCAT('${Prefix}_user_', n), 'pass', 0, DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY), 0, 0
FROM seq;

SELECT
  @contest_id AS contest_id,
  (SELECT MIN(id) FROM problem WHERE contest_id = @contest_id) AS problem_id_start,
  (SELECT MAX(id) FROM problem WHERE contest_id = @contest_id) AS problem_id_end,
  (SELECT MIN(id) FROM ``user`` WHERE name LIKE '${Prefix}_user_%') AS user_id_start,
  (SELECT MAX(id) FROM ``user`` WHERE name LIKE '${Prefix}_user_%') AS user_id_end;
"@

$escapedSql = $sql.Replace('"', '\"').Replace("`r", "").Replace("`n", " ")
$result = docker exec oj-mysql mysql -uroot -p1234 -D oj -N -B -e "$escapedSql"

if (-not $result) {
    throw "Failed to seed contest data"
}

$parts = $result.Trim().Split("`t")
if ($parts.Length -lt 5) {
    throw "Unexpected seed output: $result"
}

[pscustomobject]@{
    prefix = $Prefix
    contestId = [long]$parts[0]
    problemIdStart = [long]$parts[1]
    problemIdEnd = [long]$parts[2]
    userIdStart = [long]$parts[3]
    userIdEnd = [long]$parts[4]
    userIndexStart = 1
    userIndexEnd = $UserCount
    durationMinutes = $DurationMinutes
}
