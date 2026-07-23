# OJ 성능 측정 환경 기준선

> 기준일: 2026-07-22
>
> 자원 기준 태그: `baseline-2026-07`
>
> 운영 구성 정리 브랜치: `codex/operability-cleanup`

이 문서는 OJ 파이프라인의 부하·장애 측정 전제를 고정한다. 이후 모든 측정 문서는 이 문서와 측정 시점의 Git 커밋 해시를 함께 기록한다.

부하기와 서버가 같은 머신에서 실행되므로 절대 수치는 상대 비교용이다. 다만 WSL·컨테이너 자원 상한과 미들웨어 내부 설정은 이 문서로 고정해 실행 간 전제를 같게 유지한다.

## 1. 하드웨어와 호스트

| 항목 | 실측값 |
|---|---|
| CPU | AMD Ryzen 5 5600, 6 cores / 12 logical processors |
| RAM | 17,085,710,336 bytes, 15.91 GiB |
| OS | Windows 10.0.26200.8875 |
| WSL | 2.7.10.0, Linux kernel 6.18.33.2-2 |
| Docker Desktop | 4.82.0 |
| Docker Engine | 29.6.1 |
| Docker Compose | 5.3.0 |

Gatling은 Windows 호스트에서 실행하고 서버·미들웨어는 Docker Desktop의 WSL 2 VM에서 실행한다.

## 2. WSL 2 예산

`C:\Users\Home\.wslconfig`:

```ini
[wsl2]
processors=8
memory=10GB
swap=0
localhostForwarding=true
```

재시작 후 `docker info` 실측값은 8 CPU, 10,428,743,680 bytes다.

적용 순서는 반드시 다음과 같다.

1. Docker 관련 프로세스를 모두 종료한다.
2. PowerShell에서 `wsl --shutdown`을 실행한다.
3. Docker Desktop을 다시 시작한다.

## 3. Compose 자원 상한

| 서비스 | 인스턴스 수 | 인스턴스당 CPU | 인스턴스당 메모리 | JVM 설정 |
|---|---:|---:|---:|---|
| web | 2 | 1 | 1280M | `-XX:MaxRAMPercentage=60` |
| judge | 2 | 0.75 | 768M | `-XX:MaxRAMPercentage=60` |
| batch | 1 | 0.5 | 1024M | `-XX:MaxRAMPercentage=60` |
| mysql | 1 | 2 | 2560M | 해당 없음 |
| redis | 1 | 0.5 | 512M | 해당 없음 |
| rabbitmq | 1 | 0.75 | 1024M | 해당 없음 |
| nginx | 1 | 0.25 | 128M | 해당 없음 |
| **Compose 합계** | **9** | **7.5** | **9344M** | |
| **관측 스택 예약** | | **0.5** | 별도 결정 | 다음 단계에서 사용 |
| **WSL CPU 예산** | | **8.0** | **10GB** | 상한 합이 VM 예산을 넘지 않음 |

JDK의 컨테이너 기본 최대 힙 비율은 25%이므로 애플리케이션 역할에는 `MaxRAMPercentage=60`을 명시한다. 나머지 40%는 metaspace, thread stack, direct buffer 등 비힙 메모리와 안전 여유다. Dockerfile에는 별도의 JVM 메모리 옵션이 없다.

Snowflake worker ID는 `web-1=1`, `web-2=2`, `batch-1=100`, `judge-1=200`, `judge-2=201`로 인스턴스마다 고유하다.

## 4. 미들웨어 내부 설정

| 구성요소 | 설정 | 값 | 이유 |
|---|---|---:|---|
| MySQL | `innodb_buffer_pool_size` | 1536M | 2560M 컨테이너 안에서 데이터 캐시와 서버 여유를 분리 |
| MySQL | `innodb_buffer_pool_instances` | 6 | 128M chunk 기준으로 1536M가 2GiB로 올림되지 않게 고정 |
| MySQL | `max_connections` | 500 | 현재 다중 역할 연결 상한 유지 |
| Redis | `maxmemory` | 384mb | 512M 컨테이너 안에서 런타임 여유 확보 |
| Redis | `maxmemory-policy` | `noeviction` | scoreboard 파생 상태를 조용히 지우지 않고 메모리 부족을 오류로 노출 |

`noeviction`은 메모리가 가득 찼을 때 기존 key를 제거하지 않고 쓰기 요청을 실패시키는 정책이다.

## 5. 역할별 기동 계약과 운영 설정

| Compose 서비스 | Spring profile | 기동 책임 |
|---|---|---|
| `web-1`, `web-2` | `multi-web` | HTTP, 제출 검증·저장 |
| `batch-1` | `multi-batch` | judge outbox relay, scoreboard 반영·복구, rank batch |
| `judge-1`, `judge-2` | `multi-judge` | Rabbit 소비, 채점, 결과 batch 저장 |

`@ConfigurationProperties`는 외부 설정 묶음을 애플리케이션 기동 시 타입이 있는 객체로 변환하는 Spring Boot 기능이다.

다음 운영 설정은 개별 필드의 문자열 주입 대신 타입이 있는 설정 객체로 한 번 바인딩한다. 속성 이름과 기본값, 각 컴포넌트에 적용하던 하한 보정 규칙은 유지한다.

| 속성 prefix | 대상 |
|---|---|
| `contest.submission.async` | 제출 비동기 실행기 크기와 대기열 |
| `contest.submission.bulk` | 제출 bulk 크기와 worker 수 |
| `contest.submission.completion` | 제출 완료 통지 실행기와 대기열 |
| `contest.outbox` | scoreboard outbox batch·복구·claim 시간 |
| `contest.submission.judge.rabbit.publisher` | judge outbox relay batch·claim·confirm 시간 |
| `contest.submission.judge.result-writer` | judge 결과 batch·worker·대기열·최대 대기시간 |

`RoleBasedSchedulerActivationTests`는 테스트용 속성 조합을 재현하는 대신 `src/main/resources`의 실제 `multi-web`, `multi-batch`, `multi-judge` 프로필 그룹을 기동해 역할별 scheduler/listener 활성 상태를 검증한다.

실행기 종료 의미는 이번 정리에서 바꾸지 않았다. 완료 통지 실행기와 judge 결과 batch writer가 종료 중 진행 작업을 기다리는 현재 동작은 수명주기 테스트로 고정했다. bulk writer의 즉시 중단 정책을 graceful drain으로 바꾸는 일은 메시지 처리 의미를 함께 검토해야 하므로 별도 운영 변경으로 남긴다.

## 6. 주요 소프트웨어 버전

| 구성요소 | 버전 또는 이미지 |
|---|---|
| Java | 17, 컨테이너 `eclipse-temurin:17-jre` |
| Spring Boot | 3.4.3 |
| Gradle wrapper | 8.12.1 |
| MySQL | `mysql:8.0` |
| Redis | `redis:7-alpine` |
| RabbitMQ | `rabbitmq:4.1-management` |
| Nginx | `nginx:1.27-alpine` |
| Gatling Gradle plugin | 3.10.5 |

## 7. Gatling 위치와 실행

Gatling은 Windows의 저장소 루트 `C:\Users\Home\spring\web\web`에서 실행한다. 서버 컨테이너가 WSL 예산을 사용하고 Gatling JVM은 Windows 예산을 사용하도록 분리한다.

```powershell
.\gradlew.bat :gatling:prepareStandaloneGatling

.\gatling\run-standalone.ps1 `
  -SimulationClass my.oj.perf.ContestSubmissionSimulation `
  -BaseUrl http://localhost:8080 `
  -TargetRps 1000 `
  -RampSeconds 10 `
  -HoldSeconds 10 `
  -UserIdStart 1 `
  -UserIdEnd 10000 `
  -ProblemIdStart 1 `
  -ProblemIdEnd 5
```

원시 결과인 `results-standalone/`, `var/*.gatling.log`, `var/*.jfr`는 로컬 산출물이며 Git에서 추적하지 않는다.

## 8. 실행 구성 검증

```powershell
.\gradlew.bat bootJar
.\gradlew.bat test
docker compose config
docker compose up -d --build
docker compose ps
docker stats --no-stream

docker exec oj-web-1 cat /sys/fs/cgroup/cpu.max
docker exec oj-mysql mysql -uroot -p1234 -Nse "SHOW VARIABLES LIKE 'innodb_buffer_pool_size';"
docker exec oj-redis redis-cli CONFIG GET maxmemory maxmemory-policy

$containers = docker compose ps -aq
docker inspect --format '{{.Name}} OOMKilled={{.State.OOMKilled}}' $containers
```

기대값:

- 모든 컨테이너가 `healthy`다.
- `docker stats --no-stream`의 LIMIT가 위 표와 일치한다.
- `oj-web-1`의 `cpu.max`는 `100000 100000`이다.
- MySQL buffer pool은 `1610612736` bytes다.
- Redis maxmemory는 `402653184` bytes이고 정책은 `noeviction`이다.
- 모든 컨테이너의 `OOMKilled`가 `false`다.

### 8.1 batch 메모리 상한 보정

512M 상한에서는 합산 139 RPS, 768M 상한에서는 합산 200 RPS 제출의 HTTP 단계가 끝난 뒤 outbox를 발행하는 동안 `batch-1`이 OOM 종료됐다. judge outbox relay와 기타 예약 작업이 같은 프로세스에 있으므로, 2026-07-23 실측을 근거로 상한을 1024M로 높였다. CPU 상한 합 7.5는 그대로이며 Compose 메모리 상한 합 9344M도 10GB VM 예산 안이다. 측정 전에는 계속 `batch-1`의 `healthy`, `OOMKilled=false`, 메모리 LIMIT를 확인한다.

## 9. 제출 파이프라인 스모크

제출 한 건마다 다음 연결을 확인한다.

1. Nginx를 통해 대회 문제 제출을 한 건 보낸다.
2. MySQL `contest_submission`과 `contest_judge_outbox`에 같은 제출 ID가 생긴다.
3. RabbitMQ live queue가 drain되고 `contest_submission_result`가 생성된다.
4. `contest_submission_outbox.status`가 `COMPLETED`가 된다.
5. Redis `contest:scoreboard:<contestId>:ranking`에 해당 user가 존재한다.

실제 스모크에 사용한 contest/user/problem/submission ID와 측정 커밋 해시는 측정 기록에 남기되, 이 기준선 문서에는 특정 데이터 ID를 고정하지 않는다.

## 10. 격리된 web×2 부하 스택

`compose.loadtest.yaml`은 `compose.yaml`, 프로젝트명 `oj-loadtest`와 함께 사용한다.
컨테이너·볼륨 이름과 DB를 `oj_loadtest`로 격리하므로 일반 `oj` DB와 볼륨을 건드리지 않는다.

```powershell
docker compose -p oj-loadtest -f compose.yaml -f compose.loadtest.yaml config
docker compose -p oj-loadtest -f compose.yaml -f compose.loadtest.yaml up -d --build
```

override에도 기본 스택과 같은 CPU 상한 합 7.5를 적용한다. 두 스택을 함께 실행하면 고정한
8-vCPU 예산을 넘으므로 동시에 기동하지 않는다. Gatling은 Docker Desktop의 불안정한 IPv6
localhost 포워딩을 피하도록 `http://127.0.0.1:18080`(nginx)을 사용하며, 모든 RPS는
`web-1 + web-2` 합산 값이다.

부하 프로필은 Redis 중복 방지는 유지하지만 사용자별 cooldown은 끈다. 의도한 HTTP 429와
서버 용량 부족을 섞지 않기 위해서다. 각 웹 노드는 처리 중이거나 대기 중인 제출을 최대
256건만 수용한다. 부하용 `/perf` API는 포화 시 HTTP 503과 `Retry-After`를 반환하며,
브라우저 제출 화면은 오류 flash message가 있는 302 redirect를 반환한다.

2026-07-23 기준 통과선은 제출 200 RPS와 scoreboard 조회 300 RPS다. 제출 27,015건이
성공률 100%, p95 159ms로 처리됐고 결과/outbox 건수가 모두 일치했으며, 조회는 실제
Redis 참가자 9,364명이 있는 scoreboard에서 40,515건, 성공률 100%, p95 24ms였다. 제출 1000 RPS와
조회 2000 RPS는 현재 통과선이 아니라 각각 애플리케이션 적체와 Windows→Docker 포트
포워딩 한계를 관찰하는 과부하 시나리오다. 정확한 실행법은 `gatling/README.md`를 따른다.
