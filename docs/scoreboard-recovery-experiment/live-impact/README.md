# 복구 중 신규 처리 영향 실험 — 실행 안내

질문, 고정 변수, 지표 정의, 가설, 판정 기준, 코드 분석 결과는 [PLAN.md](PLAN.md)에 있다. 이 문서는 **어떻게 돌리고
어떻게 읽는가**만 다룬다.

> 이 harness는 아직 실행된 적이 없다. 첫 실행 전에 §2의 로컬 검증부터 한다.

## 1. 구성

| 파일 | 역할 |
|---|---|
| `gatling/run-recovery-live-impact.ps1` | runner. `-Mode full-replay\|redis-seq\|stream-offset`, `-Phase calibration\|run`, `-StackMySql`(기본 실행 방법) |
| `gatling/lib/RecoveryExperiment.LiveImpact.ps1` | 결과 seed, rebuild 호출, 시계 차이, judged 내보내기, 순위 없는 digest, 카운터 |
| `gatling/lib/RecoveryExperiment.Injector.ps1` (끝부분) | 짧은 pause 롤백 injector (`*ShortPause*`). 기존 pilot injector는 그대로 |
| `gatling/lib/RecoveryExperiment.TailPoller.ps1` | Redis 컨테이너 안에서 100 ms마다 잃은 집합의 복귀를 세는 poller |
| `gatling/src/main/java/my/oj/perf/liveimpact/` | 요약기(1초 시계열, 지표, A/B/C 판정, calibration 판정). JUnit 테스트 있음 |
| `compose.live-impact.yaml` | batch-1의 trace 설정만 켜는 overlay |
| `compose.recovery-pilot.stack-mysql.yaml` | `-StackMySql`용 overlay. `compose.recovery-pilot.yaml`을 **대신**한다(같이 쓰지 않음) — DB 관련 부분(host.docker.internal, `DB_PASSWORD` 필수 보간, mysql을 depends_on에서 빼는 부분)을 빼고, 나머지(judge 설정, 복구 모드, observability 컨테이너 이름)는 그대로 둔다 |
| `src/main/java/.../scoreboard/experiment/` | 제품 쪽 계측. `contest.scoreboard.experiment.trace.enabled=false`가 기본값이며, 꺼져 있으면 빈(bean)이 없다 |

Windows PowerShell 5.1 기준이다. 기존 recovery pilot의 lib(`RecoveryExperiment.*.ps1`)를 그대로 쓰고, 그 runner는 바꾸지 않았다. `Invoke-SqlScript`(공용 lib)만 두 인증 모드를 갖도록 넓혔다 — 기존 외부 DB 모드는 그대로 동작한다.

## 2. 사전 조건과 첫 실행 전 점검

**기본 실행 방법은 스택 MySQL 모드다(`-StackMySql`).** DB는 loadtest 스택 자신의 `mysql` 서비스
(컨테이너 `oj-loadtest-mysql`, 데이터베이스 `oj_loadtest`)를 쓴다. 이 컨테이너는 `compose.loadtest.yaml`에
커밋된 테스트 root 비밀번호(`1234`)로 스스로 초기화하고, harness는 그 안에서 컨테이너 자신의 환경변수로
인증한다(`docker exec -i oj-loadtest-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D oj_loadtest -N -B'`).
그래서 `DB_PASSWORD`를 셸에 두거나 찾을 필요가 없다. 필요한 것: Docker, JDK 17. `DB_PASSWORD`·`DB_PORT`
환경변수는 필요 없다.

외부 DB 모드(`-StackMySql` 없이 실행)는 대안으로 남아 있다: 기존 pilot의 사전 조건
([../README.md](../README.md) §1)과 같다 — Docker, `oj-test-mysql`(oj_test, Flyway 18),
`DB_PASSWORD`·`DB_PORT` 환경변수, JDK 17.

공통으로 추가할 것:

```powershell
# 1) 제품 jar와 이미지: 계측이 들어간 jar여야 한다 (runner가 trace 파일 존재로 확인하고, 없으면 거부)
.\gradlew.bat bootJar
# 2) Gatling 클래스, standalone classpath, 요약기 클래스
.\gradlew.bat :gatling:classes :gatling:compileGatlingScala :gatling:prepareStandaloneGatling
# 3) 요약기·계측 단위 테스트 (DB 불필요)
.\gradlew.bat :gatling:test
.\gradlew.bat :test --tests "*ExperimentTrace*" --tests "*ProcessorTraceTests" --tests "*RecoveryTraceTests"
# 4) 구문 검사만 된 스크립트다. PS 5.1에서 한 번 파싱해 본다
powershell -NoProfile -Command "foreach(`$f in 'gatling\run-recovery-live-impact.ps1','gatling\lib\RecoveryExperiment.LiveImpact.ps1','gatling\lib\RecoveryExperiment.TailPoller.ps1','gatling\lib\RecoveryExperiment.Injector.ps1'){ `$e=`$null; [void][Management.Automation.Language.Parser]::ParseFile((Resolve-Path `$f),[ref]`$null,[ref]`$e); `"`$f `$(`$e.Count) error(s)`" }"
```

**측정 중에 `gradlew test`를 돌리지 않는다.** `test` 프로필이 `oj_test`에 `clean-on-validation-error`를 켠다(pilot README §1.2).

## 3. 실행

스택 MySQL 모드(기본 실행 방법). 비밀번호를 셸에 두지 않는다:

```powershell
# 3.0 처음 한 번, 또는 스키마를 비우고 다시 시작하고 싶을 때만: -ResetMySqlVolume가 오직
#     `oj-loadtest-mysql-live-impact-data` 볼륨만 지우고 다시 만든다. 그 밖에는 필요 없다 - runner가 mysql을
#     띄우고 healthy를 기다린 뒤 flyway_schema_history를 읽어 스키마가 최신이 아니면 web-1 하나만
#     띄워 migration을 끝내고 다시 내린다.

# 3.1 calibration: 복구 없이 steady 60초. 1,000/s부터 최대 3단계
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
  -Mode full-replay -Phase calibration -StackMySql -TargetRps 1000 -Build
#   -> summarizer: calibration=stable|ko|backlog-growing|under-target
#   stable이 아니면 -TargetRps 850, 700 순으로. stable인 가장 높은 값과 그 run의
#   live-impact-calibration.csv의 calibration.judgedPerSecond를 기록한다.

# 3.2 본 run: 모드마다 1회, 같은 값으로
foreach ($mode in 'full-replay','redis-seq','stream-offset') {
  powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
    -Mode $mode -Phase run -StackMySql -TargetRps <calibrated> -JudgedRatePerSecond <calibrated>
}

# 3.3 Run B (C2 브랜치 codex/full-replay-background-replay): 그 브랜치에서 bootJar 후
#     같은 명령에 -Mode full-replay -StackMySql -Build
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
  -Mode full-replay -Phase run -StackMySql -FullReplayRollbackReplay background -TargetRps <calibrated> -JudgedRatePerSecond <calibrated> -Build
#   기본값이 background이므로 -FullReplayRollbackReplay는 생략해도 된다. 같은 C2 이미지에서
#   C1 동작(동기 replay)을 다시 보고 싶으면 -FullReplayRollbackReplay synchronous.
#   C1 jar는 이 설정을 모르므로 항상 동기다. 실제로 쓰인 값은 batch-1 로그의 startup 보고
#   "rollback-replay=" 와 run-events의 fullReplayRollbackReplay(컨테이너 환경변수)로 확인한다.

# 3.4 요약만 다시 (임계값을 바꿔 보고 싶을 때). 산출물 디렉터리를 준다
& "C:\Program Files\Java\jdk-17\bin\java.exe" -cp gatling\build\classes\java\main `
  my.oj.perf.liveimpact.LiveImpactSummarizer var\scoreboard-recovery-live-impact\<runId> --stall-seconds 2
```

외부 DB 모드(대안). `-StackMySql`을 빼고, `DB_PASSWORD`·`DB_PORT`를 셸에 둔다:

```powershell
$env:DB_PASSWORD = '<password>'     # 파일이나 문서에 적지 않는다
$env:DB_PORT = '3307'

powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
  -Mode full-replay -Phase calibration -TargetRps 1000 -Build
foreach ($mode in 'full-replay','redis-seq','stream-offset') {
  powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
    -Mode $mode -Phase run -TargetRps <calibrated> -JudgedRatePerSecond <calibrated>
}
```

자주 쓰는 스위치: `-TailSeconds`(기본 5), `-RecoveryBudgetSeconds`(300), `-ObserveAfterRecoverySeconds`(60),
`-OraclePollSeconds`(0 = 측정 구간 oracle 판독 끔), `-FullReplayRollbackReplay`(background), `-AllowUnflatBaseline`, `-SkipCleanup`, `-KeepStackRunning`, `-JavaExe`.

스택 MySQL 모드 전용: `-StackMySql`(DB를 `oj-loadtest-mysql`/`oj_loadtest`로 전환), `-ResetMySqlVolume`
(`oj-loadtest-mysql-live-impact-data` 볼륨만 지우고 다시 만든다; 다른 볼륨은 건드리지 않는다), `-MigrationAppService`
(스키마가 없거나 오래됐을 때 먼저 띄워 migration을 끝낼 앱 컨테이너, 기본 `web-1`).

종료 코드: 0 complete / 2 측정됐지만 불완결 / 1 실패 (PLAN §6.3).

## 4. 안전 규칙 (runner가 거부권으로 구현)

| 대상 | 규칙 | 구현 |
|---|---|---|
| MySQL | 기본(`-StackMySql`): loadtest 스택 자신의 `mysql`(컨테이너 `oj-loadtest-mysql`, DB `oj_loadtest`). 자격 증명은 컨테이너 자신의 `MYSQL_ROOT_PASSWORD`로, 컨테이너 안에서만 | harness 프로세스는 비밀번호를 절대 읽거나 담거나 출력하지 않는다. `docker exec -i oj-loadtest-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D oj_loadtest -N -B'`(`Invoke-SqlScript`). DB 이름은 `^[A-Za-z0-9_]+$`로 검증(`sh -c` 문자열에 들어가므로) |
| MySQL | 대안(외부 DB): `oj_test @ 127.0.0.1:3307`(컨테이너 `oj-test-mysql`). 자격 증명은 `DB_PASSWORD` 환경변수로만 | 파일에서 읽지 않고, artifact에 쓰지 않는다 |
| MySQL | schema·인스턴스 DROP, 무관한 테이블 truncate 금지 | `DELETE`만, 이 run의 contest id·`sbrec_<runId>_` prefix로 한정(`Get-ExperimentTableScope`) |
| MySQL | run마다 고유 prefix/contest | runId = `li<mode>_<phase><index>_<yyyyMMddHHmmss>`. 같은 prefix를 재사용하면 이전 run의 contest까지 지워지므로 호출마다 새로 만든다 |
| MySQL | 정리 전 대상 로그 | 삭제 전에 contest id, 테이블별 건수, `DELETE` 문장을 출력하고 `cleanup-scope.json`에 남긴다. 삭제 후 `removed-rows.json` |
| Redis | FLUSHALL/FLUSHDB 금지, 실험 대회 키만 | `contest:scoreboard:<contestId>:*`, `sbrec:snap:<runId>:*`, `sbrec:lost:<runId>` 외의 삭제는 거부(`Remove-ShortPauseKeys`). 전용 컨테이너만(`Assert-RedisIsDedicated`) |
| Redis | 롤백 범위 | 대회 키 + 전역 키 4개(`stream:offset`, `stream:db-pending`, `seq`, `submission-seq`). 전역 키는 모든 대회가 공유하므로 **실험 대회만 쓰기가 일어나는 스택**에서만 돌린다 |
| Redis | 메모리 | 스냅샷 전 `used_memory × 2 + 64MB < maxmemory` 확인(`noeviction`이라 넘으면 앱 쓰기가 실패한다) |
| RabbitMQ | queue/stream 삭제, vhost 초기화 금지 | 하지 않는다. judge 큐가 비어 있지 않으면 **시작을 거부**하고, 비우는 것은 사람이 한다(pilot README §7-5) |
| Gatling | 로그인 거절 → 세션 교체 → 비순환 feeder 소진으로 엔진이 죽은 적 있음 | `perf.feeder.circular=true`, 사용자 1만 명 = 세션 수. 공유 계정의 rate-limit 거절은 KO로 남는다 |
| PS 5.1 | `List[object]`에 `@()`, if 식의 배열 unroll, 중첩 `Where-Object`의 `$_` | runner는 `.ToArray()`/스칼라 대입만 쓰고, 네이티브 명령에 큰따옴표가 든 인자를 넘기지 않는다(Lua·JSON은 파일로 컨테이너에 복사) |

## 5. 산출물 (`var/scoreboard-recovery-live-impact/<runId>/`, git-ignored)

| 파일 | 내용 |
|---|---|
| `run-events.properties` / `calibration-events.properties` | runner가 기록한 조건과 시각(컨테이너 ms), pause·Lua 시간, H, N, N_total, 카운터 증분 |
| `trace/live-apply.csv` | 적용된 live 이벤트마다 `appliedAtEpochMs,offset,submissionId,judgedAt,judgedAtEpochMsUtc,batchSize` |
| `trace/recovery-trace.csv` | `PASS_START/END/SKIPPED`, `CHUNK`, `GAP` — 스레드 이름 포함 |
| `trace/trace-status.properties` | 기록·누락 건수. 누락이 있으면 `traceComplete=false` |
| `tail-poll.csv` | `atEpochMicros,present,total` (Redis TIME, ~100 ms) |
| `judged.csv` | 대회 판정 결과 `submissionId,judgedAtEpochMs,seed` |
| `injector/` | 스냅샷·롤백 직전 processed set, 잃은 집합 |
| `gatling/` | stdout/stderr, 리포트 디렉터리(`simulation.log`) |
| `live-impact-timeseries.csv` | 1초 bin: 접수 OK/KO, 채점, 첫 반영, 신규 반영, 재소비, backlog, tail 복귀 수, 구간 |
| `live-impact-summary.csv` / `.md` | 모든 지표, 판정, 임계값, `run.*`로 옮긴 조건 |
| `consistency-before-load.json`, `consistency-final.json` | 부하 전·drain 후 digest |
| `recovery-log-events.json` | batch-1 복구 로그 이벤트 (T_detected 보완) |
| `clock.json`, `container-limits.json`, `seed.json`, `seed-results.json`, `cleanup-scope.json`, `removed-rows.json`, `non-interference-after.json`, `failure.json` | 조건·정리·실패 증거 |

## 6. 해석법

- **판정은 `live-impact-summary.md` 첫 줄**이다(A/B/C와 이유). 근거는 `live-impact-timeseries.csv`를 `secondsFromFault`로
  그리면 보인다: `judged`는 계속 오는데 `newApplied`가 0인 구간이 정지, `backlog`가 그 동안 쌓이는 양이다.
- `reconsumed`는 신규가 아니다. stream-offset의 tail 재소비와 full-replay의 재구독 재읽기가 여기에 나온다(PLAN §3.2, §7-4).
- `replayThread`가 consumer 스레드 이름이면 replay가 소비 스레드 자체를 붙잡았다는 뜻이다(PLAN §7-2).
  `passesAfterRollback > 1`이면 PLAN §7-5의 두 번째 replay가 실제로 일어났다.
- injector 비용: `snapshotPauseMs`, `faultPauseMs`(batch-1 정지), `snapshotEvalMs`, `rollbackEvalMs`(Redis 전체 정지).
  이 시간의 ingress 흔들림은 모드가 아니라 injector 몫이다.
- 시계: Gatling 기반 수치와 `T_fault`는 `clockOffsetUncertaintyMs`만큼 흔들린다. `clockMySqlMinusRedisMs`가 0에서
  크게 벗어나면 시각 정렬을 믿지 않는다.
- `traceComplete=false`면 live 쪽 수치는 하한이다. `observationSufficient=false`면 after 구간이 짧다(`-RecoveryBudgetSeconds`를 늘린다).
- 모드당 1 run이다. 분포나 평균으로 말하지 않고, 모든 수치에 N, N_total, lostCount, 달성 유입률, 컨테이너 제한을 붙인다.
- 측정하지 못한 값은 `unavailable`이다. 0으로 읽지 않는다.
