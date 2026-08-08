# web×2 Gatling 부하 검증

이 구성은 다음 실제 분산 경로를 검증한다.

`nginx → web-1/web-2 → MySQL → batch relay → RabbitMQ → judge-1/judge-2 → scoreboard outbox → Redis`

RPS(requests per second)는 초당 요청 수다. 이 문서의 모든 RPS는 한 인스턴스의 값이 아니라
nginx가 `web-1`과 `web-2`로 나눈 합산 값이다.

## 격리 원칙

- `compose.loadtest.yaml`은 `oj-loadtest` 프로젝트와 `oj_loadtest` DB를 사용한다.
- 일반 `oj` 스택의 컨테이너·볼륨·DB는 건드리지 않는다.
- 일반 스택과 부하 스택은 각각 CPU 상한 합이 7.5이므로 동시에 실행하지 않는다.
- Windows의 `localhost`가 Docker Desktop IPv6 포워딩으로 흔들리는 것을 피하기 위해
  자동화는 `http://127.0.0.1:18080`을 사용한다.
- `18081`, `18082`는 노드별 초기화와 진단에만 쓰고 부하 트래픽은 반드시 nginx `18080`으로 보낸다.

## 시나리오

모든 시나리오는 제품의 API(`POST /api/login`, `POST /api/problems/{id}/submissions`,
`GET /api/contests/{id}/scoreboard`)를 사용한다. `/perf`에 있던 제출·조회 엔드포인트는 없어졌다.
그 둘은 body에 userId를 받아 인증을 건너뛰었고 조회는 행이 아니라 행 수를 돌려줬으므로,
거기서 나온 수치는 아무도 쓰지 않는 시스템을 설명했다. `/perf`에 남은 것은 seed와 bulk-stats,
즉 제품이 노출할 이유가 없는 시험 도구뿐이다.

| 이름 | 제출 합산 RPS | scoreboard 합산 RPS | 용도 |
|---|---:|---:|---|
| `smoke` | 139 | 200 | 짧은 회귀 확인 |
| `target` | 200 | 300 | 현재 고정 자원에서 통과해야 하는 기준선 |
| `submit-1000` | 1000 | - | 통과 기준이 아닌 과부하/장애 관찰 |
| `scoreboard-2000` | 선행 제출 139 | 2000 | 통과 기준이 아닌 로컬 네트워크 한계 관찰 |
| `mixed-target` | 139 평상시 → 200 피크 | 300 | 제출과 조회가 겹칠 때의 기준선, staleness 분포의 근거 |
| `mixed` | 139 평상시 → 1000 피크 | 2000 | 통과 기준이 아닌 과부하 관찰 |

`target`은 제출 부하가 끝난 뒤 judge와 scoreboard 파이프라인이 완전히 drain될 때까지 기다리고,
DB 제출·결과·outbox 완료 건수가 같은지, DB 고유 참가자 수와 Redis scoreboard 참가자 수가
같은지 확인한 후 조회 부하를 실행한다. 따라서 비어 있거나 일부만 채워진 scoreboard를 조회해
빠르게 통과하는 오탐이 없다.

### 제출은 닫힌 모델, 조회는 열린 모델

API가 세션 인증을 하므로 제출을 열린 모델로 두면 가상 사용자마다 로그인이 한 번씩 붙어
로그인을 재게 된다. 그래서 제출은 **동시 사용자 `ceil(rps × interval)`명이 각각 한 번
로그인한 뒤 `interval` 간격으로 제출**한다. 조회는 익명이라 열린 모델 그대로다.

닫힌 모델에서 간격은 자유 변수가 아니다. 같은 RPS를 몇 명이 만드는지를 정하고, 그 인원이 곧
**대회 참가자 수이자 로그인 횟수이자 조회가 훑는 scoreboard의 크기**다. `run-loadtest.ps1`은
`interval = floor(0.5 × UserCount / peakRps)`로 seed한 사용자 풀의 절반을 쓰도록 유도하며,
per-user 쿨다운(2초)보다 짧아지지 않게 하한을 둔다. 쿨다운보다 빠른 pace를 요구하는 조합은
시뮬레이션 시작 단계에서 실패한다 — 제품이 허용하지 않는 속도를 부하가 만들어낼 수는 없다.

`step`은 RPS 계단이 아니라 **인원 계단**이다. 닫힌 모델은 서버가 느려지면 사용자가 응답을
기다리므로 열린 모델처럼 요청을 쌓아 과부하를 만들 수 없다. 포화는 오류가 아니라 "인원을
늘려도 처리량이 목표를 따라가지 못하는 지점"으로 나타나고, 그것이 계단이 찾는 값이다.

### 조회 범위는 실측한 참가자 수에서 나온다

`startRank`는 1..참가자 수에서 뽑는다. 범위 밖 조회는 리더가 ZCARD 한 번만 하고 조기 반환하므로
Redis 명령 1회, 정상 페이지는 102회다. 예전에는 이 상한이 100,000으로 박혀 있고 실제 참가자는
약 9,720명이었으므로 대부분의 조회가 아무것도 재지 않았다. 이제 하네스가 선행 제출이 drain된 뒤
scoreboard에서 참가자 수를 읽어 넘기고, 읽기 시뮬레이션은 그 값 없이는 시작을 거부한다.

조회 체크는 응답이 스스로 보고한 `totalParticipants`와 요청한 `startRank`로
`min(pageSize, total - startRank + 1)`행을 요구한다. 리더가 ZCARD를 한 번만 호출해 범위 클램프와
응답 총계 양쪽에 쓰므로 이 값은 근사가 아니라 정확값이고, scoreboard가 채워지는 중에도 맞다.

각 Gatling 실행의 기본 assertion은 시나리오별 최소 요청 수, 성공률 99% 이상, p95 10초 이하다.
최소 요청 수에는 로그인도 포함된다(동시 사용자 1명당 1회). HTTP 500도 실패로 판정한다.
assertion 실패는 프로세스 종료 코드 실패로 전달된다.

## 반복 실행

```powershell
docker compose down

.\gatling\run-loadtest.ps1 -Scenario smoke -KeepStack
.\gatling\run-loadtest.ps1 -Scenario target -RemoveData

# 제출과 조회가 겹치는 기준선. 과부하 관찰은 mixed.
.\gatling\run-loadtest.ps1 -Scenario mixed-target -UserCount 10000 -ProblemCount 5
.\gatling\run-loadtest.ps1 -Scenario mixed -UserCount 10000 -ProblemCount 5

docker compose up -d --build
```

`run-loadtest.ps1`이 다음 작업을 자동화한다.

1. Compose 구성과 bootJar/Gatling classpath 준비
2. 격리 스택 기동 및 9개 컨테이너 health 확인
3. Redis 초기화, 전용 대회·사용자·문제 seed, 두 웹 노드 지표 초기화
4. Windows의 독립 Gatling JVM(`-Xmx2g`)으로 nginx 경유 부하 실행
5. judge outbox, RabbitMQ, scoreboard outbox drain 확인
6. 결과 건수와 실제 Redis scoreboard 확인
7. 노드별 수용·거절·in-flight 지표와 OOM 상태 확인
8. HTTP 성공률/p95, web peak CPU·CPU throttled ratio, 두 outbox peak backlog/head lag와
   RabbitMQ live/DLQ별 depth·unacked·consumer·publish/deliver rate 출력
9. 제출→결과 조회 가능 및 제출→scoreboard 반영 p50/p95/p99/max와 표본 수, 같은 제출의
   `result_saved_at → scoreboard_applied_at` 분포와 Redis pipeline p99 출력
10. `-KeepStack`이 없으면 격리 스택 종료, `-RemoveData`면 격리 볼륨도 제거

독립 Gatling JVM은 Gradle daemon의 GC가 서버 한계로 오인되는 것을 막는다. 요청별 DEBUG
로그도 끄되 진행 통계와 assertion 결과는 그대로 출력한다.

실행별 `var/loadtest-<timestamp>/`에는 원본 `rabbitmq-metrics.csv`와 집계
`rabbitmq-summary.csv`가 남는다. 전자는 5초마다 큐별 gauge와 누적 counter를 보존하고, 후자는
phase별 peak depth, consumer 범위, publish/deliver 평균·최대 rate와 counter reset 수를 계산한다.
observability overlay가 없으면 두 파일의 데이터는 비며, 하네스는 이를 0으로 간주하지 않고
`baseline unavailable`로 표시한다.

Redis scoreboard 구간은 원본 `redis-scoreboard-metrics.csv`와 집계
`redis-scoreboard-summary.csv`에 남는다. 후자는 phase histogram delta로 계산한 `applyAll`
pipeline p99, 같은 제출의 scoreboard 적용 구간 p99와 두 값의 비율, pipeline 호출 수, Lua 오류,
`w:*` 추정 총량, `w:*` poll p99/실패 수와 counter reset을 담는다. paired scoreboard 구간은
`end-to-end-staleness.csv`의 `scoreboard-apply-segment` 행에도 남는다. observability overlay가
없으면 이 요약도 0으로 간주하지 않고 unavailable로 표시한다.

## 2026-07-23 고정 예산 실측

> 아래 수치는 `/perf` 경로를 열린 모델로 몰던 시절의 것이다. 제출에는 로그인도 per-user
> 쿨다운도 없었고, 조회는 참가자 약 9,720명에 대해 `startRank`를 1..100,000에서 뽑아 대부분
> 빈 페이지를 쟀다. 제출 건수와 후단 검증은 그대로 유효하지만 **조회 쪽 수치와 CPU는 현재
> 경로의 기준선이 아니다.** API 전환 이후 부하 실행은 아직 없다.

| 단계 | 결과 | 성공률 | p95 | 후단 검증 |
|---|---:|---:|---:|---|
| 제출 139 RPS | 27,120건 | 100% | 154ms | 결과/outbox 27,120건, Redis 9,379명 |
| scoreboard 200 RPS | 완료 | 100% | 23ms | 실제 채워진 scoreboard 조회 |
| 제출 200 RPS | 27,015건 | 100% | 159ms | 결과/outbox 27,015건, Redis 9,364명 |
| scoreboard 300 RPS | 40,515건 | 100% | 24ms | 실제 채워진 scoreboard 조회 |

최종 `target` 실행에서 두 웹의 제출 분배는 13,508/13,507건이었고 거절은 0건이었다.
각 웹의 최대 동시 처리 수는 44/33이었으며, 모든 컨테이너에서 `OOMKilled=false`를 확인했다.

과부하 탐색에서 제출 1000 RPS는 요청 적체 뒤 web×2가 OOM 종료됐고, scoreboard 2000 RPS는
Windows→Docker 포트 포워딩에서 연결 거부가 발생했다. 두 값은 현재 통과 기준이 아니다.
제출 폭주가 프로세스를 죽이지 않도록 각 웹 노드의 admission은 256건으로 제한된다.
제출 API는 포화 시 HTTP 503과 `Retry-After`를 반환하고, per-user 쿨다운에 걸린 제출은
429와 `Retry-After`를 반환한다. 둘 다 장애가 아니라 backpressure이므로 하네스는 따로 센다.

## 결과와 정리

HTML 보고서는 `gatling/build/reports/gatling/` 아래 생성되며 Git에서 추적하지 않는다.
격리 데이터만 수동 제거하려면 다음 명령을 사용한다.

```powershell
docker compose -p oj-loadtest -f compose.yaml -f compose.loadtest.yaml down -v
```
