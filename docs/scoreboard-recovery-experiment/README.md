# Redis 스코어보드 복구 pilot — 실행 안내

이 디렉터리는 **동일 조건에서 복구 3방식을 재는 실험**의 실행 안내와 결과를 담는다.

| 문서 | 내용 |
|---|---|
| [EXPERIMENT_PLAN.md](EXPERIMENT_PLAN.md) | 측정 **전에** 고정한 가설·변수·지표·완료 기준 |
| `PILOT_REPORT.md` | 실측 결과 (측정 후 작성) |
| `results/` | 요약 산출물 (`summary.csv`, `runs.csv`, `run-metadata.json`, `calibration.md`) |
| [`../SCOREBOARD_RECOVERY_FINAL_REPORT.md`](../SCOREBOARD_RECOVERY_FINAL_REPORT.md) | 세 모드의 구현 내역. 이 실험은 그 보고서가 **측정하지 않았다고 밝힌 항목**(§10)만 다룬다 |
| [live-impact/](live-impact/README.md) | 후속 실험: 복구 중 신규 처리(접수·채점·반영)가 줄거나 멈추는가, tail이 언제 돌아오는가. 별도 runner(`gatling/run-recovery-live-impact.ps1`) |

이 실험은 기존 `gatling/run-scoreboard-rdb-recovery.ps1`과 **다른 질문**에 답한다. 그 스크립트는 outbox vs
stream **전송 방식** 비교용이고 rollback 전에 부하를 멈춘다. 여기서는 rollback **중에도 신규 유입이
계속되는** 조건을 잰다. 기존 스크립트와 기존 harness는 **수정하지 않는다**.

---

## 1. 사전 조건

| 항목 | 요구 사항 | 확인 방법 |
|---|---|---|
| Docker | 실행 중, `oj-loadtest-*` 이미지 5개 빌드됨 | `docker images oj-loadtest-web-1` |
| JDK | Gatling을 띄울 `java.exe` (기본 `C:\Program Files\Java\jdk-17\bin\java.exe`) | `& $javaExe -version` |
| Gatling 클래스 | `gatling\build\classes` 컴파일 완료 | `gatling\classpath.txt` 존재 + `DeterministicPayload.class` 등 |
| MySQL | **기존 인스턴스**(`oj-test-mysql`)가 이미 실행 중이고 `oj_test` schema에 Flyway 18개 적용됨 | 접속 후 `select count(*) from flyway_schema_history` = 18 |
| DB 포트 | `DB_PORT` 환경변수. **스택과 harness가 같은 값에서 해석한다** — overlay가 `${DB_PORT:-3306}`을 앱 tier에 넣고, harness는 실행에 쓴 포트를 metadata에 기록하며 batch 컨테이너의 값과 다르면 거부한다 | 미설정이면 3306. 이 호스트는 `MySQL80`이 3306을 쥐고 있어 3307이다 |
| Redis / RabbitMQ | 실험이 **전용 컨테이너**(`oj-loadtest-redis`, `oj-loadtest-rabbitmq`)를 직접 띄운다 | 별도 준비 불필요 |
| 비밀번호 | `DB_PASSWORD` 환경변수. **파일에서 읽지 않는다** | `$env:DB_PASSWORD` |

비밀번호와 접속 정보는 **문서·commit에 기록하지 않는다.** compose overlay는 `${DB_PASSWORD}`를
참조하고, harness는 프로세스 환경에서만 읽는다.

### 1.1 먼저 확인할 것

```powershell
docker ps --format '{{.Names}}\t{{.Ports}}'          # oj-test-mysql 이 3307 로 보여야 한다 (loopback)
docker images --format '{{.Repository}}' | Select-String oj-loadtest
$env:DB_PASSWORD = '<password>'
$env:DB_PORT = '3307'                                 # 미설정이면 overlay와 harness 모두 3306으로 본다
```

세 가지 중 하나라도 없으면 **수치를 만들지 않는다.** §7을 본다.

`DB_PORT`를 빼고 돌리면 스택은 3306의 다른 서버에 붙고 harness는 3307을 읽는다 — 하필 이 호스트가
그렇게 생겼다. 그래서 run은 batch 컨테이너가 실제로 들고 있는 `DB_PORT`를 읽어 자기 값과 대조하고,
다르면 **측정 전에 멈춘다.**

### 1.2 harness 자체 점검 (측정 전에 돌릴 것)

수치를 만드는 harness도 검증 대상이다. 네 suite가 각각 다른 것을 증명하며, **측정을 시작하기 전에**
전부 통과해야 한다 — harness 결함으로 만들어진 수치는 모드의 성질이 아니라 harness의 성질을 보고한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.UnitTests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.SummarizerTests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.RedisTests.ps1
$env:DB_PASSWORD = '<password>'   # 같은 셸에서. MySqlTests는 없으면 exit 3
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\tests\RecoveryExperiment.MySqlTests.ps1
```

| suite | 필요한 것 | 증명하는 것 |
|---|---|---|
| `UnitTests` | 없음 (순수 함수 + 소스 가드) | 파생값·정렬·digest 정규화·CSV 규약·제품 로그 패턴 인식 |
| `SummarizerTests` | 없음 (임시 디렉터리에 가짜 suite를 만들어 실행) | 요약기가 **측정된** run을 실제로 요약하는지 — 조건·commit·run 수를 어디서 읽는지 |
| `RedisTests` | docker (throwaway 컨테이너) | DUMP/RESTORE 왕복, 0x0A payload, TTL, rollback 경계, 손실 방향, **해시 테이블 인코딩이 바이트로는 재현되지 않는다는 성질**(§5.4) |
| `MySqlTests` | `DB_PASSWORD` (공유 `oj_test`) | seeder 문장과 oracle SQL이 실제 행에서 규칙대로 나오는지, 정리 경로가 자기 행만 지우는지 |

세 가지 주의:

- **측정 중에 `gradlew test`를 돌리지 않는다.** `build.gradle`이 모든 `gradlew test`에 `test`
  프로필을 강제하고 그 프로필은 `oj_test`를 대상으로 `clean-on-validation-error=true`를 켜므로,
  돌리는 순간 진행 중인 run의 schema가 날아간다. 매 poll의 Flyway 18 assert가 그 상황을 잡아내지만
  잡아내는 것과 피하는 것은 다르다.
- `MySqlTests`는 공유 `oj_test`를 쓰므로 스스로 만든 행만 지우고, 이 실험이 소유하지 않은 잔여 행은
  건드리지 않는다(사전·사후 비교를 assert한다). 실행하면 그 run의 stdout에 삭제 건수와 SQL이 찍힌다.
- `RedisTests`는 이 프로젝트의 Compose 라벨을 단 **일회용** 컨테이너만 쓴다. 공유 `oj-test-redis`는
  건드리지 않는다.

---

## 2. 이 실험이 기존 DB를 어떻게 쓰는가

**기존 `oj_test` schema를 그대로 쓴다.** 새 schema도, 새 MySQL 인스턴스도 만들지 않는다. 이 결정은
실험계획의 전제이며, 아래 규칙이 그 범위를 강제한다.

### 2.1 식별자 규칙

모든 실험 행은 run id 하나로 추적된다. run id는 `모드(하이픈 제거)_회차`다 (`fullreplay_1`,
`redisseq_2`, `streamoffset_3`). Calibration은 회차 `0`을 쓰므로 pilot 9회와 섞이지 않는다.

| 대상 | 이름 규칙 |
|---|---|
| contest / problem 이름 | `sbrec_<runId>_...` |
| user 이름 | `sbrec_<runId>_user_<n>` (비밀번호 `pass`) |
| 정리 조건 | `WHERE name LIKE 'sbrec_<runId>_%'` 또는 `WHERE contest_id = <이 run의 contest id>` |

### 2.2 건드리지 않는 것

`oj_test`에 **이미 있던** 행은 삭제하지 않는다. 매 run 시작과 끝에 그대로인지 assert하고, 사후 비교
결과를 `non-interference-after.json`으로 남긴다(사전 값은 run stdout에 찍힌다). 2026-09-21 기준 잔여 행:

| 테이블 | 행 수 |
|---|---|
| `user` | 1 |
| `daily_active_users` | 1 |
| `longest_streak_bucket` | 1 |
| `longest_streak_rank_snapshot` | 3 |
| contest / problem / submission / result 계열 | 0 |

### 2.3 매 poll마다 확인하는 것

`flyway_schema_history` 건수가 18인지 매 poll마다 assert한다. 다른 worktree의 테스트가
`clean-on-validation-error`로 schema를 날리면 **즉시 abort하고 raw failure로 남긴다** — 스코어보드가
안 움직이는 원인이 실험에 있는지 남의 테스트에 있는지 구분하기 위해서다.

### 2.4 실행 후 보고

실험이 끝나면 `oj_test`에서 **생성·수정·삭제한 범위를 테이블별 건수와 SQL로 보고한다.** 정상 종료한
run은 자기 행을 전부 지우므로 최종 상태는 실행 전과 같아야 한다. 다르면 그 차이를 그대로 적는다.

---

## 3. 안전 규칙

실험계획에 명시된 규칙을 그대로 옮긴다. harness는 이 규칙을 **거부권으로** 구현한다 — 어길 수 있는
경로에서는 실행하지 않고 실패한다.

### 3.1 MySQL

> 이번에는 별도 MySQL을 만들지 말고 프로젝트에 현재 설정된 기존 MySQL을 사용한다. 기존 MySQL 사용은
> 허용한다. 다만 프로젝트와 무관한 DB·테이블·행을 삭제하거나 MySQL 인스턴스 전체를 초기화하지 않는다.

> MySQL 인스턴스 전체 DROP, schema 전체 DROP, 무관한 테이블 truncate 금지

> ddl-auto=create로 기존 schema가 의도치 않게 초기화되지 않도록 확인

> 실행 전에 정리 대상 ID와 SQL 범위를 로그로 남김

구현: 정리 경로는 `DELETE`만 쓴다. 모든 문장이 이 run이 만든 contest id 또는 `sbrec_<runId>_%`
패턴으로 한정되며, **삭제 전 `SELECT COUNT`로 대상을 검증**하고 삭제 후 `removed-rows.json`에 테이블별
건수를 남긴다. 범위 밖 행이 하나라도 잡히면 이름 검사에서 실패한다.

### 3.2 Redis / RabbitMQ

> Redis와 RabbitMQ도 기존 로컬 서비스를 사용할 수 있지만, 다른 데이터에 영향을 줄 수 있는 전체 flush,
> 전체 queue 삭제, vhost 초기화는 금지한다. snapshot 복원이 인스턴스 전체를 변경한다면 먼저 해당
> 인스턴스가 이 프로젝트 전용인지 확인한다. 전용이 아니면 실험용 prefix·queue·Redis 인스턴스로 격리한다.

구현: 실험은 **전용** `oj-loadtest-redis` / `oj-loadtest-rabbitmq`만 쓴다. 롤백 주입기는 컨테이너가 이
프로젝트의 서비스가 아니면 **거부한다**(`Assert-RedisIsDedicated`). 큐는 `contest.judge.result.stream`
**하나만** 삭제·재선언하며, 삭제 전 `list_queues`로 이 프로젝트 큐 3개만 존재함을 assert한다. vhost
초기화는 하지 않는다. run 사이 Redis 초기화(`FLUSHALL`)도 전용 인스턴스에만, 실행 직전 `DBSIZE`와 키
prefix 분포를 기록한 뒤 수행한다.

공유 인스턴스 `oj-test-redis`(16379)는 **절대 건드리지 않는다.**

### 3.3 그 밖

> 기존 사용자 변경을 덮어쓰지 않는다.

> 비밀번호나 접속 정보를 문서·commit에 기록하지 않음

### 3.4 RabbitMQ Stream offset

> RabbitMQ Stream offset은 연속 정수라고 가정하지 않는다. `offset + 1` 개수 계산으로 유실 이벤트 수를
> 추정하지 말고, 실제 전달·적용된 이벤트와 저장 checkpoint를 기준으로 판단한다.

구현: 유실 집합은 `Get-LostResultSet`이 **K 시점에 적용돼 있던 결과 집합과 rollback 직후 실제로
적용돼 있는 결과 집합의 차집합**으로 구한다. offset 산술로 개수를 만들지 않는다. backlog 판정도
offset이 아니라 `contest_scoreboard_pending_events`, 큐 ready/unacked, `scoreboard_applied_at IS NULL`
개수를 쓴다.

---

## 4. 실행 명령

### 4.1 Calibration (측정 전 1회)

고정 변수로 쓸 값을 **실제로 재서** 정한다. 데이터 크기와 유입률을 임의로 "운영 규모"라고 부르지
않는다. **현재 환경에서 안정적으로 반복 가능한 값을 먼저 calibration하고 그 값을 기록한다.**

```powershell
$env:DB_PASSWORD = '<password>'
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-pilot-suite.ps1 -Phase calibration -HoldSeconds 120
```

모드당 1회, 회차 `0`. 결과를 보고 `-UserCount`, `-TargetRps`, `-HoldSeconds`, `-BaselineResults`,
`-TailResults`, timeout을 확정한 뒤 `results/calibration.md`에 기록한다.

### 4.2 Pilot (모드당 3회)

```powershell
$env:DB_PASSWORD = '<password>'
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-pilot-suite.ps1 -Phase pilot `
  -UserCount <calibrated> -TargetRps <calibrated> -HoldSeconds <calibrated> `
  -BaselineResults <calibrated> -TailResults <calibrated>
```

9개 run을 순서대로 돌린다. 각 run은 **별도 프로세스**이므로 한 run이 실패해도 다음 run의 상태를
오염시키지 않는다.

### 4.3 요약

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\summarize-recovery-pilot.ps1
```

`results/summary.csv`(모드별 중앙값 + min..max), `results/runs.csv`(9행), `results/run-metadata.json`을
만든다. **3회는 분포가 아니므로 중앙값과 범위만 쓰고, 평균으로 두 run 사이의 값을 만들지 않는다.**

### 4.4 단일 run (디버깅용)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-pilot.ps1 `
  -Mode full-replay -RunIndex 9 -UserCount 200 -TargetRps 40 -HoldSeconds 240
```

`-RunIndex 9`처럼 pilot 대역(1–3) 밖의 값을 주면 pilot 산출물과 섞이지 않는다.

**스택을 직접 올려야 할 때 — 서비스 이름을 반드시 지정한다.** 병합된 compose 파일들에
서비스 이름 없이 `up -d`를 실행하면 base `compose.yaml`의 `mysql` 서비스까지 기동해
`oj-loadtest-mysql`이 생긴다. 이 실험은 "프로젝트에 이미 설정된 외부 MySQL을 쓴다"는 결정 위에
있으므로 그것은 측정 조건을 바꾸는 일이고, `Assert-PilotStackHealthy`가 16개 컨테이너를 세어
run을 중단시킨다(15개 기대). 하네스가 쓰는 기동 목록은 `Get-PilotStartServices`에 있고,
그 밖의 목적으로 올릴 때도 같은 목록을 쓴다:

```powershell
# 올바름: 하네스가 쓰는 것과 같은 서비스 목록
docker compose -p oj-loadtest --project-directory . `
  -f compose.yaml -f compose.loadtest.yaml -f compose.observability.yaml -f compose.recovery-pilot.yaml `
  up -d nginx redis rabbitmq web-1 web-2 batch-1 judge-1 judge-2 `
        prometheus grafana alertmanager cadvisor mysqld-exporter redis-exporter nginx-exporter

# 틀림: mysql 까지 올라온다
docker compose -p oj-loadtest ... up -d
```

**nginx는 앱 tier보다 나중에 떠야 한다.** nginx는 `upstream oj_web { server web-1:8080; server web-2:8080; }`을
`resolver` 없이 쓰므로 **자기 기동 시점에 한 번만** 이름을 해석하고 그 주소를 계속 쓴다. 앱 tier는 run마다
새 컨테이너이므로, 이전 run의 nginx가 남아 있으면 **없어진 tier의 주소로 다이얼**한다. 이때 `nginx -t`도
통과하고 web의 TCP healthcheck도 통과한다 — 새 컨테이너가 8080에서 답하기 때문이다. 이 상태를 처음
알아채는 것은 스코어보드 조회이고, 그것은 **측정 단계**라서 run 하나가 502로 사라진다.

그래서 run은 앱 tier가 healthy해진 뒤 nginx를 `--force-recreate`로 다시 만들고, 자기 자신을 통해 실제
요청 하나가 성공할 때까지 기다린다(`Reset-EdgeRouting`). 수동으로 스택을 올릴 때도 앱 tier가 뜬 뒤
nginx를 재생성한다:

```powershell
docker compose -p oj-loadtest ... up -d --force-recreate --no-deps nginx
```

### 4.5 종료 코드 — run의 완결성이지 모드의 성질이 아니다

| 코드 | 의미 | suite의 처리 |
|---|---|---|
| 0 | `complete` — 수치가 완결 | 계속 |
| 2 | 측정됨, 그러나 불완결 (예: ingress가 잠깐 막힘) | **계속.** 수치를 **버리지 않는다.** 모드가 ingress SLO를 깨는 것 자체가 결과다 |
| 1 | 측정 실패 — 수치 없음 | 계속 (`-StopOnFirstFailure`면 중단) |

suite 자체도 같은 규약으로 종료한다: 수치를 못 낸 run이 있으면 1, 불완결 run이 있으면 2, 전부
완결이면 0.

### 4.6 자주 쓰는 스위치

| 스위치 | 용도 |
|---|---|
| `-Build` | 앱 이미지 5개 재빌드. 기본 off (이 실험은 앱을 바꾸지 않는다) |
| `-KeepStackRunning` | run 종료 후 앱 tier를 남긴다. 기본 off — 다음 run의 큐 초기화가 **소비 중인 큐 삭제를 거부**하기 때문 |
| `-StopOnFirstFailure` | 첫 측정 실패에서 중단. 깨진 사전 조건을 9번 진단하지 않기 위해 |
| `-ArtifactRoot` | 산출물 루트 (기본 `var\scoreboard-recovery`) |
| `-DbName` | 비우면 `RECOVERY_PILOT_DB_NAME` → `DB_NAME` → `oj_test` 순으로 해석 |
| `-DbPort` | 비우면 `DB_PORT` → `3306`(overlay의 기본값) 순으로 해석. 앱 tier가 실제로 그 포트를 들고 있는지 run이 검사한다 |

`-DbName`을 비워 두면 harness가 읽는 schema와 스택이 접속하는 schema가 **같은 곳에서 해석된다.**
그래도 run은 batch 역할이 실제 접속한 DB 이름과 대조해 다르면 실패한다 — 한쪽만 다른 schema를 보면
"움직이지 않는 스코어보드"를 재면서 그 사실이 수치에 안 드러나기 때문이다.

---

## 5. Rollback(장애 주입) 절차

`run-recovery-pilot.ps1`의 7–8단계가 수행한다. **자동이며, 수동으로 재현할 때도 같은 순서다.**

### 5.1 K 캡처 (7단계)

1. batch-1 `docker pause` — Redis 스코어보드의 **유일한 writer**를 세워 checkpoint와 스코어보드 내용을
   **자기정합**하게 만든다 (torn state 방지). 이 pause가 멈추는 것은 **Redis 쪽**뿐이다: MySQL의
   `scoreboard_applied_at`은 제품이 스스로 *non-authoritative*라 부르는 값이고(`ContestScoreboardAppliedAtCompletion`
   — "Repairs the non-authoritative MySQL staleness timestamp") 비동기 batch로 채워지므로, 정지 구간을
   걸쳐 수백 행이 움직여도 스코어보드는 한 건도 움직이지 않을 수 있다. 그래서 이 값은 **시계로 쓰지
   않는다**(§5.2).
2. `SCAN MATCH contest:scoreboard:*`로 키 목록 수집 → 키별 `TYPE` / `OBJECT ENCODING` / `DUMP` / `PTTL` /
   **타입별 canonical 내용**(문자열은 값, list는 순서, set은 정렬된 멤버, hash·zset은 필드별 정렬) 캡처
3. MySQL에서 oracle digest + 적용 결과 수 스냅샷
4. **K 시점 digest == 제품 API digest** 확인 (다르면 run 실패 — 측정 한계가 아니라 harness 결함 신호)
5. `docker unpause`. **pause 소요를 기록한다**(`recovery-summary.csv`의 `injectorCapturePauseMs`,
   `rollback.json`의 `capturePauseMs`) — 작지 않고, 그 시간은 측정 대상 applier가 얼어 있던 시간이다(§5.3)

증거: `k-snapshot.json` (type/encoding 분포 포함).

### 5.2 장애 주입 (8단계)

1. K 대비 **Redis에 적용된 결과 수가 `-TailResults` 이상**이 되는 순간 batch-1 `docker pause`.
   판정은 `processed` 집합의 크기(K 스냅샷 대비)로 한다 — **롤백이 깎는 축이 정확히 이 집합**이므로,
   같은 축에서 기다려야 고정 변수인 rollback depth가 지켜진다. MySQL의 `scoreboard_applied_at`으로
   기다리면 안 된다: 그 값은 권위가 없고 비동기로 채워지므로 스코어보드가 한 건도 안 움직이는데도
   수백 행이 한꺼번에 늘어난다(2026-09-25 실측: 같은 구간에서 MySQL 1264→2306 대 `processed` 1264→1265,
   그래서 20건을 요구한 run이 1건만 지우고 측정 불가로 끝났다). 지켜졌는지는 run이 스스로 검사한다 —
   유실 집합이 `-TailResults`보다 작으면 **거부한다**
2. `contest:scoreboard:*` 전체 `DEL`
3. 캡처한 payload를 `RESTORE key <pttl|0> payload REPLACE`
4. **검증**: 키 집합 일치 + 타입·인코딩 일치 + canonical 내용 일치(전 키) + **payload 바이트 일치**
   (`hashtable` 인코딩 키는 제외 — §5.4) + `storedOffset == K`
5. `T_fault` 기록
6. `docker unpause` — **이후에도 신규 유입은 계속된다**. pause 소요를 기록한다
   (`injectorFaultPauseMs` / `faultPauseMs`). 유실 집합이 `-TailResults`보다 작으면 여기서 거부된다

증거: `rollback.json`. 검증에서 바이트 대신 내용으로 판정한 키 수를 함께 기록하며, run stdout에도 찍는다.

### 5.3 무엇을 재현하고, 무엇을 재현하지 않는가

**재현한다**: Redis 스코어보드가 과거 RDB snapshot과 동등한 상태로 되돌아간 상황. `RESTORE`는 K 시점
키의 직렬화 바이트를 그대로 되돌린다.

**재현하지 않는다**: (a) RDB 파일 로드 경로 자체, (b) 세션·dedup·rate-limit 키의 rollback.

전체 인스턴스 RDB를 교체하지 **않는** 이유는 그것이 컨테이너 kill로 수 초의 Redis 무응답을 만들고,
그 시간이 "신규 유입 실패"에 섞여 주입기와 장애를 구분할 수 없게 만들기 때문이다. 키 범위 복원은
세션·dedup 키를 건드리지 않아 **주입기 자체의 가용성 교란이 없다.**

이 한계는 **세 모드에 동일하게 적용**되므로 모드 간 비교는 성립한다. 다만 결과를 "RDB에서 로드했을
때의 복구 시간"으로 일반화할 수 없다.

**pause footprint는 작지 않다.** 실험계획 §6은 이 구간을 "수백 ms"로 적어 두었지만, 캡처와 롤백은 키를
하나씩 `docker exec`로 왕복한다(947키). 2026-09-25 실측에서 캡처 pause는 34초 이내, 장애 주입 pause는
48초 안팎이었다(둘 다 poll 간격으로 잰 상한이며, 이제 run이 직접 기록한다). 즉 그 시간만큼 **측정 대상
applier가 얼어 있었고**, `consistencyOutageMs`에는 그 시간이 들어 있다. 세 모드 모두 같으므로 **비교는
성립하지만**, 절대 수치를 "모드가 복구하는 데 걸린 시간"으로 읽으려면 기록된 pause 값을 빼야 한다.
계획의 "수백 ms"는 실측과 100배 어긋나며, 동결된 계획은 고치지 않고 `PILOT_REPORT.md`의 측정 한계에 적는다.

### 5.4 검증이 무엇을 증명하고, 무엇을 증명하지 않는가

복원 검증은 **키 집합·타입·인코딩·내용(canonical)** 을 전 키에 대해 확인하고, **payload 바이트**는
인코딩이 재현 가능한 키에 대해서만 확인한다. 이 구분은 harness의 편의가 아니라 **Redis의 성질**이다.

`DUMP`는 해시 테이블 인코딩된 set·hash에서 **버킷 순회 순서**로 멤버를 쓴다. `RESTORE`는 그 순서대로
다시 삽입하므로 해시 테이블이 재구성되는데, 그 결과 버킷 배치는 원래의 삽입 이력이 만든 배치와
같아지지 않는다. **내용은 정확히 보존되고 바이트는 보존되지 않는다.** 이 이미지(`redis:7-alpine`)에서
실측한 결과:

| 키 | 인코딩 | reload 후 내용 | reload 후 payload 바이트 |
|---|---|---|---|
| 정수 멤버 50·512개 set | `intset` | 동일 | **동일** |
| 정수 멤버 513·1266개 set | `hashtable` | 동일 | **다름** |
| 필드 200개 hash(긴 값) | `hashtable` | 동일 | **다름** |
| 짧은 hash / `listpack` hash / `skiplist` zset / list / string | 그 밖 | 동일 | 동일 |

이 구분이 없던 첫 calibration run은 `processed` set(멤버 1266개 → `hashtable`)에서 **복원이 정확했는데도
검증 실패로 기록**되었다. 즉 **바이트 일치는 이 인코딩에서 달성 불가능한 기준**이며, 달성 가능한 기준으로
판정해야 한다. 검증 강도를 낮춘 것이 아니라 **주장의 범위를 실제와 맞춘 것**이다.

바이트 비교는 지금도 유효한 모든 키에 적용되고, 그 수를 `SBRE_COMPARE_CANON_ONLY`로 세어
`rollback.json`에 남긴다. 그러므로 "전 키 검증"은 "대부분 검증"으로 읽히지 않는다.

**증명하지 않는 것**: 이 검증은 Redis가 **메모리 안에서** 같은 상태가 되었음을 증명한다. RDB 파일에서
로드했을 때와 동일하다는 것, 즉 디스크 경로까지 같다는 것은 증명하지 않는다(§5.3의 한계와 같은 이유).

---

## 6. 산출물

### 6.1 raw (untracked)

`var/scoreboard-recovery/<timestamp>-<runId>/` — repo의 untracked 산출물 규칙을 따른다.

| 파일 | 내용 |
|---|---|
| `recovery-summary.csv` | run 1행, 40+ 컬럼. 이 run의 모든 수치 |
| `samples/polls.csv` | **poll 원본 전량.** 마지막 값만이 아니라 매 관측 |
| `run-metadata.json` | 실제로 쓴 설정값 (설정값을 측정 결과로 쓰지 않기 위한 기록) |
| `k-snapshot.json` / `rollback.json` | K 캡처와 rollback 검증 |
| `recovery-log-events.json` | batch-1 복구 로그 이벤트 (`detected-*`, `rebuilt`, `consumer-held`, …) |
| `seed.json` / `leftovers.json` / `removed-rows.json` | seed와 정리 증거 |
| `non-interference-after.json` | 기존 행 비간섭 assert의 사후 비교. 사전 값은 파일이 아니라 run stdout에 찍힌다 |
| `run-verdict.csv` | 성공/실패 판정 |
| `gatling/` | Gatling stdout/stderr + report 디렉터리 |
| `failure.json` | 실패 시 원인 (있는 경우) |

suite 디렉터리(`var/scoreboard-recovery/<timestamp>-suite-<phase>/`)에 `suite-summary.csv`,
`suite-metadata.json`, run별 stdout/stderr가 쌓인다.

### 6.2 curated (commit)

`results/` — `summary.csv`, `runs.csv`, `run-metadata.json`, `calibration.md`. **작은 요약만** commit하고
raw는 commit하지 않는다.

---

## 7. Blocker 시 대응

> Redis/RabbitMQ가 없거나 snapshot rollback을 안전하게 수행할 수 없으면 수치를 만들지 말고 정확한
> blocker와 필요한 실행 명령을 남긴다.

수치를 만들어내지 않는다. 실패한 run도 **원인과 함께 raw result로 보존**한다. 확인 순서:

1. `oj_test` 접속이 되는가 — `$env:DB_PASSWORD` 설정 후 `flyway_schema_history` = 18
2. `oj-loadtest-*` 이미지 5개가 있는가 — 없으면 `-Build`, 그래도 없으면 사전 조건 미충족
3. Gatling 클래스가 컴파일돼 있는가 — `gatling\classpath.txt` + `build\classes`
4. 전용 Redis에 rollback을 걸 수 있는가 — `Assert-RedisIsDedicated`가 거부하면 격리가 안 된 것
5. **이전 run이 judge 큐에 남긴 작업** — reset은 `contest.judge.live`/`dead`가 비어 있지 않으면
   **일부러 중단**한다(`Draining them is the previous run's result, not this reset's`). 중단된 run은
   채점 대기 메시지를 남기므로 다음 run이 시작조차 못 한다. harness가 자동으로 비우지 **않는** 이유는
   그것이 이전 run의 결과를 이번 측정에 섞는 일이기 때문이고, 그래서 **앱 tier가 멈춘 유휴 시점에
   사람이** 한다:

   ```powershell
   # 전용 인스턴스인지 먼저 확인한다 (project=oj-loadtest, service=rabbitmq, published port 없음)
   docker inspect oj-loadtest-rabbitmq --format 'project={{index .Config.Labels "com.docker.compose.project"}} service={{index .Config.Labels "com.docker.compose.service"}}'

   # 건수를 기록한 뒤 judge 큐 하나만 비운다 (전체 queue 삭제도 vhost 초기화도 아니다)
   docker exec oj-loadtest-rabbitmq rabbitmqctl list_queues name type messages_ready messages_unacknowledged
   docker exec oj-loadtest-rabbitmq rabbitmqctl purge_queue contest.judge.live -p /
   docker exec oj-loadtest-rabbitmq rabbitmqctl list_queues name type messages_ready messages_unacknowledged
   ```

   `-p /`를 Git Bash에서 쓰면 경로가 `C:/Program Files/Git/`로 바뀌어 **없는 vhost**를 가리키고
   `Object was not found`로 끝난다 — 조용히 아무것도 지우지 않으므로 `MSYS_NO_PATHCONV=1`을 준다.
   `contest.judge.live`는 **quorum** 큐라 비운 직후 조회가 잠깐 옛 값을 보여줄 수 있다.
   `contest.judge.result.stream`은 건드리지 않는다 — 다음 run의 reset이 삭제·재선언한다.

각 경우에 **필요한 실행 명령을 `PILOT_REPORT.md`에 그대로 적는다.**

---

## 8. 해석법

- **설정값을 측정 결과로 쓰지 않는다.** `run-metadata.json`의 값은 조건이지 결과가 아니다
- **단위 테스트 통과를 물리적 복구 성공으로 쓰지 않는다.** `T_consistent`는 digest 일치로만 판정한다.
  다만 9단계 대기는 여기에 `lostComplete`(롤백이 지운 제출이 전부 `processed` 집합에 돌아왔는가)를
  **추가로** 요구한다 — 계획에 없는 조건이다. 2026-09-25 run들이 이 조건에서 끝까지 false로 남아
  측정 불가로 끝난 것을 처음에는 모드의 성질로 읽었지만, 실은 **harness 결함**이었다: 롤백 직전
  멤버 목록을 `@(Get-RedisSetMembers ...)`로 읽으면서 수천 개 id가 공백으로 이어붙은 **한 개의
  문자열**이 되었고, 그래서 `lostTotal`은 항상 1, `lostReapplied`는 항상 0이었다 — 즉 그 run들에서
  "순위에는 돌아왔지만 `processed`에는 돌아오지 않았다"고 읽을 근거 자체가 없었다. 결함은 고쳤고
  같은 모양이 다시 들어오지 못하도록 source guard를 넣었다(`var/deferred-harness-fixes.md` 항목 8).
  이 조건 자체가 정당한지는 이제 **별개의 질문**이며 재측정으로 판정한다(항목 4)
- **`quiescent`는 stream 큐의 `messages_ready`로 판정하지 않는다.** RabbitMQ stream의 ready는 소비로
  줄어드는 backlog가 아니라 **retain된 로그**다(2026-09-25 실측: consumer가 head까지 따라잡은 상태에서
  checkpoint 5091·`pendingEvents` 0인데도 5092 ready). stream의 실제 지연은 `pendingEvents`와
  `streamDbPending`이 본다. 소비자 수(`Consumers ≥ 1`)는 계속 조건이다
- 복구 메서드의 반환값이나 "rebuilt" 로그를 완료 판정으로 쓰지 않는다
- **1회 run을 일반 성능으로 주장하지 않는다**
- **서로 다른 데이터 크기·fault 조건의 숫자로 개선율을 계산하지 않는다**
- 모든 수치에 **전체 데이터 수 · 유실 수 · 유입률 · 환경**을 함께 적는다
- 미측정은 `unmeasured`, 수집 불가는 `unavailable`. **0으로 적지 않는다**
- 설계상 기대와 실측을 구분한다 ([EXPERIMENT_PLAN.md](EXPERIMENT_PLAN.md) §1.1의 H1–H5는 실측 전까지 가설)
- 결론은 "어느 기술이 항상 우월"이 아니라 **복구 SLO · 신규 유입 지연 · DB/Redis/Rabbit 비용에 따른
  선택 기준**으로 쓴다
- 3회 반복은 분포가 아니다. 중앙값과 범위를 쓴다

### 8.1 수집할 수 없는 항목

`replayOfferedCount`, `replayFoundAlreadyAppliedCount`는 **제품에 카운터가 없다**
(`ContestScoreboardReplayApplication.apply(...)`가 `void`를 반환하고 marker 실패만 기록한다). 0으로
적지 않고 `unavailable`로 표시하며, 사용 가능한 대리 지표인 `appliedDeltaDuringRecovery`를 함께
기록한다(복구 구간의 `contest_scoreboard_applied_total` 증분 — live 적용과 replay 적용이 섞여 있어
분리할 수 없다).
