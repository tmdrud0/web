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

| 이름 | 제출 합산 RPS | scoreboard 합산 RPS | 용도 |
|---|---:|---:|---|
| `smoke` | 139 | 200 | 짧은 회귀 확인 |
| `target` | 200 | 300 | 현재 고정 자원에서 통과해야 하는 기준선 |
| `submit-1000` | 1000 | - | 통과 기준이 아닌 과부하/장애 관찰 |
| `scoreboard-2000` | 선행 제출 139 | 2000 | 통과 기준이 아닌 로컬 네트워크 한계 관찰 |

`target`은 제출 부하가 끝난 뒤 judge와 scoreboard 파이프라인이 완전히 drain될 때까지 기다리고,
DB 제출·결과·outbox 완료 건수가 같은지, DB 고유 참가자 수와 Redis scoreboard 참가자 수가
같은지 확인한 후 조회 부하를 실행한다. 따라서 비어 있거나 일부만 채워진 scoreboard를 조회해
빠르게 통과하는 오탐이 없다.

각 Gatling 실행의 기본 assertion은 시나리오별 최소 요청 수, 성공률 99% 이상, p95 10초 이하다.
HTTP 500도 실패로 판정한다. assertion 실패는 프로세스 종료 코드 실패로 전달된다.

## 반복 실행

```powershell
docker compose down

.\gatling\run-loadtest.ps1 -Scenario smoke -KeepStack
.\gatling\run-loadtest.ps1 -Scenario target -RemoveData

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
8. `-KeepStack`이 없으면 격리 스택 종료, `-RemoveData`면 격리 볼륨도 제거

독립 Gatling JVM은 Gradle daemon의 GC가 서버 한계로 오인되는 것을 막는다. 요청별 DEBUG
로그도 끄되 진행 통계와 assertion 결과는 그대로 출력한다.

## 2026-07-23 고정 예산 실측

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
부하용 `/perf` API는 포화 시 HTTP 503과 `Retry-After`를 반환하고, 브라우저 제출 화면은
기존 UX를 유지해 오류 flash message가 있는 302 redirect를 반환한다.

## 결과와 정리

HTML 보고서는 `gatling/build/reports/gatling/` 아래 생성되며 Git에서 추적하지 않는다.
격리 데이터만 수동 제거하려면 다음 명령을 사용한다.

```powershell
docker compose -p oj-loadtest -f compose.yaml -f compose.loadtest.yaml down -v
```
