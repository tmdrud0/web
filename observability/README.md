# 관측 스택

`compose.yaml`의 앱 스택 위에 얹는 오버레이다. 앱 서비스의 자원 상한(합계 7.5 CPU / 9344M)은
바꾸지 않고, 관측 스택은 자체 예산을 쓴다. 모니터링을 켠 실행과 끈 실행의 앱 측 조건을 같게
유지하기 위해서다.

## 1. 실행

```powershell
.\gradlew.bat bootJar
docker compose -f compose.yaml -f compose.observability.yaml up -d --build
```

`bootJar`를 먼저 돌려야 한다. `Dockerfile`은 `build/libs/`의 jar를 복사할 뿐이므로
`--build`만으로는 소스 변경이 반영되지 않는다. 이 경우 이미지는 새로 만들어지고 컨테이너는
`healthy`가 되며 스크레이프도 200을 주는데, 새 지표만 조용히 없다. `docs/ENVIRONMENT.md` §8의
순서와 같다.

| 접속 | 주소 | 비고 |
|---|---|---|
| Grafana | http://localhost:3000 | admin / admin, 익명 조회 허용 |
| Prometheus | http://localhost:9090 | PromQL 직접 확인용, `Status > Rules`에서 규칙 상태 |
| Alertmanager | http://localhost:9093 | 발화 중인 알림, 그룹핑과 silence |

앱 스택만 띄우려면 `-f compose.observability.yaml`을 빼면 된다. 관측 스택은 앱 스택에
의존하지만 그 반대는 아니다.

격리된 부하 스택(`docs/ENVIRONMENT.md` §10)에 붙일 때는 같은 프로젝트로 함께 올린다.

```powershell
docker compose -p oj-loadtest -f compose.yaml -f compose.loadtest.yaml -f compose.observability.yaml up -d --build
```

`OJ_ROLE`은 오버레이 병합 후에도 유지되므로 두 스택 모두 같은 태그로 조회된다. 다만 관측
컨테이너 이름과 호스트 포트(3000, 9090)는 고정이므로 두 스택을 동시에 띄울 수는 없다.
원래도 8-vCPU 예산 때문에 동시 기동은 금지돼 있다.

## 2. 자원 예산

| 서비스 | CPU | 메모리 |
|---|---:|---:|
| prometheus | 1.00 | 1024M |
| grafana | 0.50 | 512M |
| cadvisor | 0.30 | 256M |
| alertmanager | 0.10 | 128M |
| mysqld-exporter | 0.10 | 64M |
| redis-exporter | 0.10 | 64M |
| nginx-exporter | 0.10 | 64M |
| **관측 합계** | **2.20** | **2112M** |
| 앱 합계 (`compose.yaml`) | 7.50 | 9344M |
| **총합** | **9.70** | **11456M** |

컨테이너 상한은 예약이 아니라 상한이므로, WSL VM 예산이 총합을 덮으면 관측 스택이 앱의
CPU/메모리를 빼앗지 않는다. `docs/ENVIRONMENT.md` §2의 기존 예산(8 CPU / 10GB)은 총합보다
작으므로 관측 스택을 함께 띄우려면 예산을 올려야 한다.

`C:\Users\<사용자>\.wslconfig`:

```ini
[wsl2]
processors=12
memory=14GB
swap=0
localhostForwarding=true
```

적용 순서는 `docs/ENVIRONMENT.md` §2와 같다. Docker 종료 → `wsl --shutdown` → Docker Desktop 재시작.

### 예산으로 격리되지 않는 비용

컨테이너 상한을 분리해도 다음 비용은 앱 예산 안에서 발생한다. 측정값을 읽을 때 감안한다.

- 각 JVM이 스크레이프 요청마다 지표를 직렬화하는 비용
- mysqld/redis/nginx exporter가 관측 대상에게 실제로 던지는 질의
- RabbitMQ가 `/metrics/detailed`의 큐 객체 지표를 직렬화하는 비용
- Prometheus TSDB 쓰기와 MySQL commit이 공유하는 디스크 I/O

앞의 둘은 스크레이프 주기로 조절한다. exporter 계열을 10s로 둔 이유가 이것이다.
RabbitMQ detailed scrape는 새 컨테이너가 아니라 기존 Prometheus의 두 번째 target이므로 표의
관측 상한은 **2.20 CPU / 2112M**, 총합은 **9.70 CPU / 11456M**으로 그대로다. broker 쪽 비용은
RabbitMQ의 앱 예산 안에서 발생하며 §12의 family·sample 제한으로 묶는다.

## 3. 스크레이프 대상

| job | 대상 | 경로 | 주기 |
|---|---|---|---:|
| `oj-app` | web-1, web-2, batch-1, judge-1, judge-2 (`:9000`) | `/actuator/prometheus` | 5s |
| `rabbitmq` | rabbitmq:15692 | `/metrics` | 5s |
| `rabbitmq-per-queue` | rabbitmq:15692, vhost `/` | `/metrics/detailed` | 5s |
| `mysql` | mysqld-exporter:9104 | `/metrics` | 10s |
| `redis` | redis-exporter:9121 | `/metrics` | 10s |
| `nginx` | nginx-exporter:9113 | `/metrics` | 10s |
| `cadvisor` | cadvisor:8080 | `/metrics` | 10s |
| `prometheus` | localhost:9090 (자기 자신) | `/metrics` | 15s |

job은 8개, 스크레이프 대상은 `oj-app`의 5개를 포함해 모두 12개다.

Alertmanager는 이 표에 없다. Prometheus가 알림을 보내는 대상이지 긁어오는 대상이 아니다.
받는 쪽(receiver)이 설정되지 않은 지금은 실패할 알림 전송 자체가 없으므로 job을 만들지 않았다.
Prometheus가 Alertmanager를 찾았는지는 이미 긁고 있는 `prometheus` job의
`prometheus_notifications_alertmanagers_discovered`로 본다.

### 관리 포트 9000

Actuator는 업무 포트가 아니라 별도 포트에서 듣는다(`application.properties`, `MANAGEMENT_PORT`로
덮어쓸 수 있다). nginx는 8080만 프록시하므로 `/actuator`는 compose 네트워크 안에서만 닿는다.

이 값이 프로필이 아니라 전역 기본값에 있는 이유는 `server.port=-1`과 반드시 짝이 되어야 하기
때문이다. `compose.yaml`의 `BATCH1_PROFILES` 등은 프로필 조합을 열어두므로 `multi-server` 없이
`batch-role`만 켜는 조합이 가능한데, 그러면 관리 포트가 없어 Spring Boot의
`ManagementPortType.get()`이 `SAME`을 반환하고 그 SAME 포트가 -1이라 HTTP가 하나도 열리지 않는다.
예외도 경고도 없이 기동하고, 헬스체크는 프로세스 존재만 보므로 `healthy`로 남고, Prometheus
타깃만 조용히 down이 된다. 두 파일에 값을 나눠 두면 이 실패는 조용하다.

부수효과로 프로필 없이 로컬 단독 실행할 때도 `/actuator`가 업무 포트 8080에서 9000으로
분리된다. 이 프로젝트에는 Spring Security 의존성이 없어 지금까지 `/actuator`가 업무 포트에
그대로 노출돼 있었으므로 이쪽이 개선이다. 테스트는 `application-test.properties`의
`management.server.port=-1`이 프로필 우선순위로 이 기본값을 이기므로 영향받지 않는다.

`batch-1`, `judge-1`, `judge-2`는 원래 `spring.main.web-application-type=none`이었다. 이
설정은 서블릿 컨테이너를 아예 띄우지 않으므로 Actuator HTTP 엔드포인트도 함께 사라진다.
그런데 outbox relay와 scoreboard worker가 바로 이 역할에 있어서, 가장 보고 싶은 backlog
지표가 가장 안 보이는 프로세스에 있었다.

`server.port=-1`로 바꾸면 서블릿 컨텍스트는 유지되어 관리 자식 컨텍스트가 기동하고, 업무
커넥터는 바인딩되지 않는다. 실측으로 확인한 동작은 다음과 같다.

- 프로세스가 여는 리스닝 포트는 관리 포트 하나뿐이다.
- `/actuator/health`, `/actuator/prometheus`가 정상 응답한다.
- 업무 포트로는 연결 자체가 되지 않는다.

역할 계약(업무 HTTP를 제공하지 않는다)은 그대로다.

## 4. 지표 태그

| 라벨 | 출처 | 값 |
|---|---|---|
| `application` | Micrometer 공통 태그 | `web` (`spring.application.name`) |
| `role` | Micrometer 공통 태그 (`OJ_ROLE` 환경변수) | `web` / `batch` / `judge` |
| `instance` | Prometheus 스크레이프 대상 | `web-1:9000` 등 |
| `node` | Prometheus 정적 라벨 | `web-1`, `batch-1` 등 |

`instance`는 Prometheus가 붙이는 예약 라벨이므로 Micrometer 공통 태그로 같은 이름을 쓰지
않는다. 충돌하면 `exported_instance`로 밀려 대시보드 질의가 어긋난다.

## 5. 히스토그램

`http.server.requests`에 percentile histogram을 켰다. 클라이언트측 percentile이 아니라
버킷 카운트를 내보내는 이유는, 버킷만이 인스턴스 간 합산에서 올바른 percentile을 주기
때문이다. 평균과 인스턴스별 max는 합산되지 않는다. 기존 `/perf/contest/submission-bulk-stats`
스냅샷이 web-1과 web-2의 p99를 낼 수 없었던 이유가 이것이다.

버킷 범위는 5ms~10s로 제한해 시계열 수를 묶어두었다.

파이프라인 내부 지표(§9)의 Timer 3개도 같은 이유로 버킷을 낸다. 다만 이쪽 버킷 경계는
프로필이 아니라 meter 자체에 속하는 값이므로 `application.properties`가 아니라
`ContestSubmissionBulkMetrics` 안에 있다. 프로필 조합에 따라 사라질 수 있는 자리에 두지
않는다.

**Micrometer Timer는 요청하지 않아도 `_max` 시계열을 함께 낸다.** time-decaying 창의
인스턴스별 최댓값이므로 §5의 규칙에 걸리는 값이다. `http.server.requests`도 마찬가지다.
percentile은 항상 `histogram_quantile(..., sum by (le) (rate(..._bucket[...])))`으로 구하고,
`_max`는 단일 인스턴스를 들여다볼 때만 쓴다. 절대 인스턴스 간에 더하거나 평균내지 않는다.

## 6. 검증

```powershell
docker compose -f compose.yaml -f compose.observability.yaml ps
```

모든 컨테이너가 `healthy`인 것을 확인한 뒤:

1. Prometheus `Status > Target health`에서 위 8개 job, 대상 12개가 모두 `UP`인지 본다.
2. `up{job="rabbitmq-per-queue"} == 1`이고
   `count by (queue) (rabbitmq_detailed_queue_messages_ready)`가 `contest.judge.live`와
   `contest.judge.dead`를 각각 한 번 반환하는지 본다. target이 0이면 §12의 100-sample 상한을
   넘겼는지도 함께 확인한다.
3. `up{job="oj-app"}` 이 5개 시계열을 반환하는지 본다. 5개가 아니면 해당 역할의 관리 포트가
   열리지 않은 것이다.
4. `cgroup_cpu_limit_cores` 가 compose 상한과 일치하는지 본다. 이 값이 어긋나면 컨테이너가
   의도한 자원 상한으로 뜨지 않은 것이므로 그 실행의 측정값은 버린다.

   | node | 기대값 |
   |---|---:|
   | web-1, web-2 | 1 |
   | judge-1, judge-2 | 0.75 |
   | batch-1 | 0.5 |

5. `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{role="web"}[5m])))`
   가 값을 반환하는지 본다. 비어 있으면 히스토그램 버킷이 꺼진 것이다.
6. Grafana의 `OJ` 폴더에 `OJ - Bottleneck Overview` 대시보드가 있는지 본다.
7. `contest_scoreboard_pending_events`가 `batch-1`의 **한 시계열**만 반환하는지 본다. 진단용
   `contest_outbox_backlog_rows`는 judge 2개(PENDING, PUBLISHING)와 scoreboard 3개(PENDING,
   PROCESSING, FAILED), 합계 5개이며 `count by (node) (...)`는 `batch-1` 하나만 내야 한다.
   다른 역할에서도 gauge 폴러가 켜지면 `sum()`이 backlog를 인스턴스 수만큼 부풀린다.
8. Prometheus `Status > Rule Health`에서 규칙 그룹 4개가 모두 `OK`인지 본다.
   `oj:contest_scoreboard_estimated_drain:seconds`가 비어 있으면 pending/apply 지표가 올라오지 않은
   것이다(7번). 값이 없는 것과 0인 것은 다르다 — 빈 outbox는 0/0이라 NaN이고, 시계열 자체는
   존재한다.
9. Prometheus `Status > Runtime & Build Information`의 Alertmanagers에 `alertmanager:9093`이
   보이는지 본다. 비어 있으면 규칙은 평가되지만 발화가 아무 데도 가지 않는다.
10. 제출 파이프라인 상한이 실제 상한과 맞는지 본다. 이 지표들은 다섯 역할 전부에서 나오므로
   (§9.4) 역할을 지정하지 않으면 2.5배가 된다.

   | 질의 | 기대값 |
   |---|---:|
   | `sum(contest_submission_in_flight_limit{role="web"})` | 1600 |
   | `sum(contest_submission_bulk_workers_limit{role="web"})` | 8 |
   | `sum(contest_submission_completion_queue_capacity{role="web"})` | 128 |
   | `sum(contest_submission_completion_threads{role="web"})` | 8 |

   `{role="web"}`을 빼면 각각 4000·20·320·20이 나온다. 이 차이가 보이면 정상이다.

## 7. 알려진 제약

- **cAdvisor는 이 환경에서 컨테이너 이름을 붙이지 못한다.** 실측으로 확인한 내용이다.
  cAdvisor는 컨테이너를 등록할 때 read-write 레이어를 `/var/lib/docker/image/<driver>/layerdb/mounts/<id>/mount-id`
  에서 찾는데, Docker Desktop이 쓰는 containerd 이미지 저장소는 이 경로를 만들지 않는다.
  결과적으로 **모든 컨테이너 등록이 실패**하고 루트 cgroup 하나만 남으며 throttling 지표가
  전혀 나오지 않는다. Docker 소켓 마운트를 빼면 raw cgroup 핸들러로 넘어가 throttling은
  나오지만 `name` 라벨 없이 `id="/docker/<hash>"`만 남는다. 현재 구성이 이 상태다.

  그래서 애플리케이션 5개 인스턴스의 자원 상한 지표는 cAdvisor가 아니라 JVM이 직접
  보고한다. `CgroupResourceMetrics`가 컨테이너 안에서 cgroup v2의 `cpu.stat`, `cpu.max`,
  `memory.current`, `memory.stat`, `memory.events`, `memory.max`를 읽어 `cgroup_*` 지표로
  내보낸다. 호스트 접근이 필요 없고 다른 지표와 같은 `role`·`node` 라벨을 갖는다. cAdvisor는
  JVM이 없는 MySQL·Redis·RabbitMQ·nginx용으로만 남겨두었고, 해시 id는
  `docker inspect <id 앞부분>`으로 확인한다.
- **`memory.current`는 여유 지표가 아니다.** 그래서 상한과 비교하는 값은 `memory.current`가
  아니라 working set(`memory.current - inactive_file`)이다. `memory.current`에는 회수 가능한 page
  cache가 들어 있고 커널은 상한에 닿으면 이 캐시부터 버리므로, 파일 I/O를 하는 컨테이너는
  부하와 무관하게 `memory.current / memory.max`가 1.0에 붙고 한 번 오르면 내려오지 않는다.
  거짓 경보를 내면서 진짜 위험 신호를 가린다. cAdvisor·docker stats·쿠버네티스가 모두 쓰는
  정의가 working set이다. `cgroup_memory_usage_bytes`는 원본 값으로 남겨두었으니 둘의 차이가
  곧 회수 가능분이다.
- **`cgroup_memory_oom_kills_total`이 잡는 범위는 좁다.** OOM 여부는 `memory.events`의
  `oom_kill`을 세는 이 지표로 보고, 값은 커널이 직접 센 것이라 추정이 아니다. 다만 커널이
  죽인 대상이 JVM 자신(컨테이너의 PID 1)이면 컨테이너가 함께 끝난다. 그러면 oom_kill 증분은
  마지막 스크레이프(최대 5초 전) 뒤에 일어나 영영 수집되지 않고, 재시작된 컨테이너는 새
  cgroup이라 카운터가 0부터 다시 센다. **이 지표가 실제로 잡는 것은 cgroup 안의 다른
  프로세스가 죽는 경우뿐이다.** `docs/ENVIRONMENT.md` §8.1의 `batch-1` OOM이 정확히 JVM이
  죽은 경우이므로, 그때 이 지표가 있었어도 0이었다. 따라서 **값이 0인 것을 "OOM이 없었다"로
  읽으면 안 된다.** JVM이 죽는 쪽은 재시작으로 본다. 대시보드의
  `JVM restarts over the selected range (app tier)` 패널
  (`changes(process_start_time_seconds{job="oj-app"})`)이 그 신호이고, 이 환경에서 JVM OOM을
  실제로 잡는 것은 이쪽이다.
- **스크레이프 주기와 부하 실행 길이.** 5s 주기에서 10초 hold 실행은 표본이 두어 개뿐이라
  곡선이 되지 않는다. 이 대시보드로 판단하려면 hold를 60초 이상으로 늘려야 한다.
- **backlog 게이지는 값이 오래됐을 수 있다.** 이 문서 §10의 폴러는 5초마다 질의하고 게이지는 그
  결과를 읽는다. 스크레이프 경로에서 DB를 건드리지 않기 위한 구조이므로, 게이지는 최대
  폴링 주기만큼 과거다. 질의가 실패하면 게이지는 **0으로 떨어지지 않고 직전 값을 유지한다.**
  0은 "backlog가 없다"는 뜻이라 실패 상황에서 정확히 반대로 읽히기 때문이다. 그래서 "backlog가
  평평하다"와 "보는 것을 멈췄다"는 게이지만으로 구분되지 않는다. 구분해 주는 것은
  공통 계층에서는 `contest_scoreboard_observation_failures_total`이고
  `ContestOutboxBacklogUnobserved` 알림이 이 값을 본다. 기존
  `contest_outbox_backlog_poll_failures_total`도 구현 진단용으로 유지한다.
- **backlog 개수는 상한에서 잘린다.** 질의는 파생 테이블의 `LIMIT`로 스캔을 묶는다
  (`contest.outbox.metrics.max-counted-rows`, 기본 100000). backlog가 상한을 넘으면 게이지는
  상한값을 보고하므로 **실제보다 작은 값**이다. 모든 알림 임계값은 상한보다 한참 아래이므로
  잘린 값이 알림을 가리지는 못한다. 측정값은 이 문서 §10에 있다.
- **head lag는 두 outbox 모두 PENDING만 본다.** 의도한 설계다(§10). `PUBLISHING`에서 멈춘
  행, backoff 중인 `FAILED` 행, lease 안의 `PROCESSING` 행은 **개수에는 잡히지만 나이에는
  잡히지 않는다.** 이 상태들의 나이는 "처리량 부족"이 아니라 "재시도 중"을 뜻하므로 같은 값에
  섞으면 두 신호가 구분되지 않는다. 나이가 0인데 `FAILED`나 `PUBLISHING` count가 올라가 있으면
  막힌 행이 있는 것이다. 두 신호는 §11에서 서로 다른 알림이 된다.
- **batch-1이 멈추면 backlog 신호도 멈춘다.** 게이지를 앱 안에 두고 소유 역할을 하나로 고정한
  구조(§10의 (a))가 치르는 대가다. drain이 멈춘 바로 그 순간에 관측도 멈춘다. Prometheus는
  마지막 값을 5분 유지한 뒤 시계열을 버리므로, 이 경우 알림은 `ContestOutboxDrainTimeTooLong`이
  아니라 `OjAppInstanceDown`(node=batch-1)으로 온다. **batch-1의 down을 outbox 알림보다 먼저
  읽어야 한다** — backlog 게이지가 평평한 것이 근거가 되지 못한다.
- **아직 없는 지표.** 이 문서 §9·§10으로
  `docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md` §10.1의 제출 파이프라인 항목과 §10.3의 outbox
  항목("각 outbox의 상태별 count와 oldest age")은 채워졌다. 두 outbox 모두 활성 상태별 count와
  PENDING oldest age가 Prometheus에서 조회되고, 부하로 쌓았다 빼는 곡선까지 실측했다(§10).
  남은 것은 같은 문서 §10.1의 async in-flight·Tomcat accept queue·중복 응답 수와
  `ContestSubmissionBatchConsistencyException` 카운트, §10.5의 judge API latency histogram,
  listener retry/DLQ 이동 수, result writer queue depth, Redis pipeline latency, Lua 오류와
  `w:*` 필드 총량이다. 이들은 judge 역할과 Redis 경로에 있어 이번 작업 범위 밖이다.
- **알림 임계값은 실측값이 아니다.** 규칙은 `observability/prometheus/rules/oj-pipeline.yml`에
  있고 숫자는 전부 출발점이다. 임계값을 정할 근거가 되는 실행 — 곡선이 나올 만큼 긴 hold,
  위의 "스크레이프 주기와 부하 실행 길이" 항목 — 이 아직 없다. 각 임계값이 무엇의 대리값인지는
  규칙 파일에 적어두었으니 측정이 생기면 그 자리를 바꾼다.
- **알림을 받을 곳이 없다.** Alertmanager는 떠 있지만 receiver가 비어 있다. Slack이나
  webhook 주소는 배포에 속하는 값이라 리포에 두면 "보내는 것처럼 보이는데 아무 데도 안 가는"
  설정이 된다. 지금 얻는 것은 그룹핑·중복 제거·inhibition·silence와 http://localhost:9093의
  목록까지다. 실제 발송은 receiver를 붙이는 시점의 작업이다.

## 8. 기동 직후 관측된 값

첫 기동 검증에서 CFS throttling이 상시 발생했다. 부하를 걸지 않은 상태에서 스케줄링 주기의
61~78%가 throttle됐다.

| node | throttle된 주기 비율 | 메모리 사용/상한 (주) |
|---|---:|---:|
| web-1 | 61.4% | 46.2% |
| web-2 | 62.0% | 61.3% |
| judge-1 | 73.3% | 79.8% |
| judge-2 | 77.8% | 73.7% |

`batch-1`은 이 표에 없다. 첫 기동 검증에서 기록되지 않았고, 이 표는 기동 직후 값만 담으므로
이미 떠 있는 프로세스에서 지금 재서 채울 수 없다. 아래 재측정 때 함께 채운다.

**(주) 메모리 열은 재측정 대기 상태다.** 이 수치는 `memory.current / memory.max`로 잰 값이고,
`memory.current`에는 회수 가능한 page cache가 포함된다(§7). 대시보드는 working set
(`memory.current - inactive_file`) 기준으로 바뀌었으므로 지금 대시보드에서 읽히는 값과 이 표는
정의가 다르다. 이 표의 값을 새 패널의 기준선으로 그대로 옮겨 쓰면 안 된다.

throttle된 주기 비율은 대시보드의 `Throttled period ratio (app tier)` 패널에서 바로 볼 수
있다(`rate(cgroup_cpu_throttled_periods_total) / rate(cgroup_cpu_periods_total)`).

이 값 자체를 결론으로 삼으면 안 된다. JVM 기동은 원래 CPU를 몰아 쓰므로 기동 구간의
throttling은 정상이다. 다만 **부하 실행 중에도 이 비율이 유지된다면 그 실행의 지연 수치는
애플리케이션 병목이 아니라 자원 상한을 측정한 것**이다. 다음 부하 측정에서 가장 먼저
확인해야 할 값이다.

## 9. 제출 파이프라인 지표

> **측정 맥락이 바뀌었다.** 이 절의 staleness 수치는 여전히 제출 경로 기준이라 유효하지만,
> 그때의 제출은 `POST /perf/contest/submit`이었다. 그 엔드포인트는 body의 userId를 그대로 믿어
> 세션 조회가 없었고 per-user 쿨다운도 꺼져 있었다. 지금 제출은
> `POST /api/problems/{id}/submissions`이고 세션 인증과 쿨다운 Redis 왕복이 측정 안에 있다.
> 파이프라인 뒤쪽(bulk writer → outbox → judge → scoreboard)은 같은 코드이므로 분포의 모양은
> 비교 가능하지만, **절대값을 그대로 인용하지 말고 API 전환 이후 실행에서 다시 재라.**
> 이 커밋 시점 기준으로 그 실행은 아직 없다.

`ContestSubmissionBulkMetrics`는 이제 두 곳에 기록한다. `snapshot()`은 `/perf`의 bulk-stats
엔드포인트가 읽는 기존 경로 그대로고, `bindTo()`가 Prometheus용 meter를 등록한다. 두 독자의
요구가 다르기 때문에 형태도 다르다.

| | perf 스냅샷 | Prometheus |
|---|---|---|
| 읽는 주기 | 실행 1회당 1번 | 5초마다 계속 |
| 내보내는 범위 | JVM 하나 | **다섯 역할 전부** (§9.4) |
| 읽는 범위 | JVM 하나 | web-1 + web-2 (`{role="web"}`) |
| 형태 | 평균, 프로세스별 max | Timer 히스토그램, 살아 있는 객체를 읽는 게이지 |
| `reset()` | 실행 사이에 초기화 | **건드리지 않는다** |

`reset()`이 meter를 건드리지 않는 이유는 카운터가 0으로 떨어지면 Prometheus가 프로세스
재시작으로 읽고 `rate()`를 끊기 때문이다. f94de98의 carry-forward와 같은 이유다.

### 9.1 이벤트 계열

| meter | 종류 | 노출 이름 |
|---|---|---|
| `contest.submission.bulk.chunk` | Timer + 버킷 | `contest_submission_bulk_chunk_seconds_*` |
| `contest.submission.bulk.submissions` | Counter (`outcome`) | `contest_submission_bulk_submissions_total` |
| `contest.submission.rejected` | Counter | `contest_submission_rejected_total` |
| `contest.submission.completion.queue.delay` | Timer + 버킷 | `contest_submission_completion_queue_delay_seconds_*` |
| `contest.submission.completion.task` | Timer + 버킷 | `contest_submission_completion_task_seconds_*` |
| `contest.submission.completion.failures` | Counter | `contest_submission_completion_failures_total` |
| `contest.submission.completion.caller_runs` | Counter | `contest_submission_completion_caller_runs_total` |

### 9.2 현재 상태 게이지

살아 있는 객체를 **스크레이프 시점에** 읽는다. 어디선가 기록해 둔 값을 내보내지 않는다.

| meter | 읽는 대상 | 노출 이름 |
|---|---|---|
| `contest.submission.in_flight` | `maxInFlight - inFlightPermits.availablePermits()` | `contest_submission_in_flight` |
| `contest.submission.in_flight.limit` | `max-in-flight` 설정값 | `contest_submission_in_flight_limit` |
| `contest.submission.bulk.queue.depth` | writer의 `pendingCount` | `contest_submission_bulk_queue_depth` |
| `contest.submission.bulk.active.workers` | writer의 `activeWorkers` | `contest_submission_bulk_active_workers` |
| `contest.submission.bulk.workers.limit` | `worker-count` 설정값 | `contest_submission_bulk_workers_limit` |
| `contest.submission.completion.queue.depth` | `executor.getQueue().size()` | `contest_submission_completion_queue_depth` |
| `contest.submission.completion.queue.capacity` | `completion.queue-capacity` 설정값 | `contest_submission_completion_queue_capacity` |
| `contest.submission.completion.active` | `executor.getActiveCount()` | `contest_submission_completion_active` |
| `contest.submission.completion.threads` | `completion.thread-count` 설정값 | `contest_submission_completion_threads` |

**깊이 게이지에는 항상 설정 상한을 함께 낸다.** 그래야 패널이 비율이 되고, 대시보드가
`application.properties`의 값을 하드코딩하지 않는다. 상한은 배포 사이에 변하지 않는 상수지만,
게이지로 내보내는 것이 설정 변경을 눈에 보이게 만드는 유일한 방법이다.

`in_flight`가 `in_flight.limit`에 붙으면 그 순간 503이 나가고 있다는 뜻이다. 남은 여유분은
두 값의 차이다.

### 9.3 왜 기록 시점 값이 아니라 살아 있는 객체를 읽는가

writer의 `pendingCount`·`activeWorkers`·`inFlightPermits`와 completion executor는 이미
폴링 가능한 상태다. 기록 시점 값을 필드에 넣어두고 내보내면, chunk가 하나도 끝나지 않는
동안 — 정확히 보고 싶은 상황에서 — 게이지가 낮은 값에 얼어붙는다.

스냅샷의 max* 누산기 8개(`maxPendingBefore`, `maxPendingAfter`, `maxActiveWorkers`,
`maxCompletionQueueDepth`, `maxActiveCompletionWorkers`, `maxInFlight` 등)는 **실행 1회당
한 번 읽는 스냅샷이 시간에 걸친 최댓값을 관측할 방법이 그것뿐이라서** 존재한다. 5초 주기
스크레이프가 생기면 그 이유가 사라진다. `max_over_time(...[5m])`이 임의 구간의 최댓값을
주고, 인스턴스별로도 합산으로도 준다. 그래서 하나도 내보내지 않았다.

**트레이드오프: 5초보다 짧은 스파이크는 게이지가 놓치고 누산기는 잡는다.** 이걸 감수하는
이유는 합산 불가능한 JVM별 max보다 합산 가능한 시계열이 운영에서 낫기 때문이다. 정말
sub-scrape 피크가 필요해지면 기록 시점 값을 `DistributionSummary`로 올려 합산 가능한 형태로
되찾을 수 있다. 지금은 필요한 곳이 없다.

max* 누산기는 아직 지우지 않았다. perf 엔드포인트가 그대로 쓰고 있고
`/perf/contest/submission-bulk-stats` 응답은 바뀌지 않았다.

### 9.4 왜 다섯 역할 전부에서 나오는가, 그리고 읽을 때 `{role="web"}`이 필요한 이유

§9.1과 §9.2의 meter는 **다섯 역할 전부에서 등록된다.** `ContestSubmissionBulkWriter`와
`ContestSubmissionCompletionDispatcher`가 조건 없는 `@Component`이고, 어떤 역할이든
컨텍스트가 뜨려면 이 빈들이 있어야 하기 때문이다. batch-1과 judge 인스턴스에서 실측한 값이다.

| 지표 | 전체 sum() | `{role="web"}` |
|---|---:|---:|
| `contest_submission_in_flight_limit` | 4000 | **1600** |
| `contest_submission_bulk_workers_limit` | 20 | **8** |
| `contest_submission_completion_queue_capacity` | 320 | **128** |
| `contest_submission_completion_threads` | 20 | **8** |

깊이 게이지는 그 역할들에서 0이라 무해하다. **틀리는 것은 설정 상한 4개다.** 분자는 맞고
분모만 2.5배가 되므로, web-1과 web-2가 각각 800에 붙어 503을 흘리는 완전 포화 순간에
Admission 패널은 1600 대 4000, 여유 60%로 읽힌다. 유휴 상태에서는 0/4000이라 멀쩡해 보이고
부하가 걸려야 어긋나는데, 하필 `ContestSubmissionShedding` 알림이 운영자를 그 패널로 보낸다.

그래서 **대시보드와 규칙의 `contest_submission_*` 질의 전부에 `{role="web"}`을 붙였다.**
상한 4개만이 아니라 게이지 9개와 Timer/Counter까지 전부다 — 지금 나머지 숫자가 맞는 것은
그 역할들이 우연히 0이기 때문이지 질의가 대상을 지정해서가 아니다.

**빈 등록을 조건부로 바꾸지 않았다.** 검토하고 기각한 이유는 다음과 같다.

- writer와 dispatcher는 어떤 역할이든 컨텍스트가 뜨려면 존재해야 한다.
- `compose.yaml`은 프로필 조합을 열어두므로(§3) web-role이 제출을 받는 유일한 역할이라는
  보장이 없다. 등록을 역할에 묶으면 조합에 따라 지표가 조용히 사라진다. 이 리포는 이미 같은
  모양의 침묵 실패(관리 포트)를 f94de98에서 겪었다.
- 깊이 게이지는 §10의 outbox backlog와 달리 **진짜 인스턴스별 상태**다. 게시 자체는 옳고
  틀린 것은 읽는 쪽이다.

`application-web-role.properties`에 outbox backlog에 대해 적어둔 "다섯 인스턴스가 같은
시계열을 내면 합산이 다섯 배가 된다"와 같은 함정의 다른 형태다. outbox는 테이블의 성질을
프로세스마다 중복 게시하는 문제라 **게시를 한 곳으로 묶어** 해결했고, 이쪽은 인스턴스별 상태를
올바르게 게시하는데 읽는 쪽이 대상을 좁히지 않는 문제라 **질의에서 좁혀** 해결했다. 진단이
같아 보여도 처방이 반대인 이유가 이것이다.

`contest_outbox_*`에는 `{role="web"}`을 붙이면 안 된다. batch-1이 유일한 게시자이므로
아무것도 선택되지 않는다. 두 방향 모두 `PipelineMetricNamesTests`가 고정한다.

### 9.5 staleness 꼬리를 읽기 전에 재시도부터 확인한다

`redis_seq`는 Redis의 sequence 카운터가 발급하고 DB outbox에 저장된다. 그래서 **Redis만 비우고
DB outbox 행을 남기면** 다음 실행이 같은 번호를 다시 발급하고, 그것이
`docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md` §5.2가 정의한 **Redis 롤백 신호와 구별되지 않는다.**
복구 워커는 정상 동작한다 — 없는 장애를 만들어 준 쪽이 잘못이다.

이때 나타나는 모양은 다음과 같다. 실측이다.

| | 오염된 실행 | 정상 실행 |
|---|---:|---:|
| 겹친 `redis_seq` 값 | 946 | 0 |
| `attempts > 1` 행 | 924 | **0** |
| `scoreboard applied` p99 | 31.4s | **4.9s** |
| `scoreboard applied` max | 178s | **13.3s** |

`attempts = 1`인 행만 보면 오염된 실행에서도 최대 4.5초였다. **꼬리 전체가 재시도였다.**

부수 증상이 두 가지 더 있다. `Wait-PipelineDrain`이 0을 보고 끝난 뒤 복구 워커가 `COMPLETED`
행을 다시 큐에 넣으므로 **`processed_at`이 드레인 완료 후에 갱신되고**, 샘플러는 이미 멈춘
뒤라 head-lag 교차 검증이 서로 다른 시간 구간을 비교해 `MISMATCH`를 낸다. 실측 실행에서
샘플링은 07:46:18에 끝났는데 처리는 07:49:23까지 이어졌다. 그리고 이 되돌림은 수렴하지 않아
**부하가 끝난 지 5분이 지나도 requeue 로그가 계속 찍혔다.**

`gatling/run-loadtest.ps1`의 `Reset-LoadRedis`가 `FLUSHDB`와 함께 두 outbox 테이블을 비우는
이유가 이것이다. Redis와 그 두 테이블은 한 덩어리 상태이고, 절반만 비우면 안 된다.

## 10. scoreboard pending과 outbox 진단 지표

운영 판단의 질문은 저장 구조가 아니라 **"채점은 끝났지만 아직 scoreboard에 반영되지 않은
결과가 얼마나 많고, 그중 바로 처리할 수 있는 가장 오래된 것은 얼마나 기다렸는가"**다. 현재
구현에서는 답이 `contest_submission_outbox`에 있지만 RabbitMQ Stream 구현에서는 consumer lag에
있다. 구현 이름을 직접 읽으면 before/after 공통 축이 사라지므로 지표를 두 층으로 나눈다.

### 왜 두 층인가

| 층 | 목적 | 안정성 |
|---|---|---|
| scoreboard 공통 계층 | 구현 비교, recording rule, 알림, 주 대시보드 | outbox/stream 모두 같은 이름과 의미 |
| 구현 진단 계층 | PENDING·PROCESSING·FAILED·PUBLISHING, retry 등 원인 분해 | 구현과 함께 바뀔 수 있음 |

공통 `pending`은 아직 적용되지 않은 모든 이벤트를 센다. 현재 outbox에서는
`PENDING + PROCESSING + FAILED`이고, Stream에서는 같은 의미의 consumer lag가 된다. 반면 나이는
`oldest_unapplied`가 아니라 **`oldest_ready`**라고 부른다. 아래 기존 판단대로 backoff 중인
FAILED와 lease 안의 PROCESSING은 의도적으로 제외하며, 지금 당장 소비할 수 있는 PENDING만 보기
때문이다. `unapplied`라고 부르면 한 시간 된 FAILED도 포함한다고 오해하게 된다.

공통 계층은 기존 outbox 폴러와 완료 기록이 가진 값을 그대로 공유한다. 새 DB 질의나 별도 갱신
주기가 없어서 같은 scrape에서 아래 대응은 정확히 같다.

| 공통 meter | 노출 이름 | 현재 outbox 구현의 원천 | 게시 주체 |
|---|---|---|---|
| `contest.scoreboard.pending` | `contest_scoreboard_pending_events` | scoreboard non-terminal 상태 합 | batch-1 |
| `contest.scoreboard.oldest.ready` | `contest_scoreboard_oldest_ready_seconds` | scoreboard PENDING head lag | batch-1 |
| `contest.scoreboard.applied` | `contest_scoreboard_applied_total` | scoreboard COMPLETED 적용 수 | 5개 역할 등록, batch-1만 증가 |
| `contest.scoreboard.observation.failures` | `contest_scoreboard_observation_failures_total` | scoreboard gauge 갱신 실패 | batch-1 |

기존 outbox 전용 지표는 삭제하지 않는다. 상태별 count와 retry는 공통 지표가 나빠진 이유를
구분하는 데 필요하다.

| meter | 종류 | 노출 이름 | 게시 주체 |
|---|---|---|---|
| `contest.outbox.backlog` | Gauge (`outbox`, `status`) | `contest_outbox_backlog_rows` | batch-1 |
| `contest.outbox.head.lag` | Gauge (`outbox`) | `contest_outbox_head_lag_seconds` | batch-1 |
| `contest.outbox.backlog.poll.failures` | Counter | `contest_outbox_backlog_poll_failures_total` | batch-1 |
| `contest.outbox.drained` | Counter (`outbox`) | `contest_outbox_drained_total` | 5개 역할 전부 등록, batch-1만 증가 |
| `contest.outbox.retries` | Counter (`outbox`) | `contest_outbox_retries_total` | 5개 역할 전부 등록, batch-1만 증가 |

applied/drained/retries 세 카운터는 §9.4의 제출 파이프라인 지표처럼 다섯 역할 전부에서 등록된다.
`ContestOutboxDrainMetrics`가 조건 없는 `@Component`여야 relay와 scheduler가 주입받을 수 있기
때문이다. 실측으로 web-1·web-2·judge-1에도 시계열이 있는 것을 확인했다.

**공통/진단 계층 모두 `{role=...}` 필터를 붙이지 않았다.** applied와 기존 drained/retries를
증가시키는 코드는 batch-1에만 존재하므로 나머지 네 인스턴스의 값은 영원히 정확히 0이다. 0인
시계열을 더해도 합은 변하지 않으므로 `sum()`이 정확하다. 공통 gauge와 outbox gauge는
batch-1에서만 등록된다. §9.4에서 필터가 필요했던 것은 상한 gauge가 **0이 아닌 상수**를 다섯
번 보고해서 분모가 2.5배가 됐기 때문이다.

즉 판단 기준은 "몇 개 인스턴스가 등록하는가"가 아니라 **"등록만 하는 인스턴스가 0을 보고하는가
아닌가"**다. 0이면 합산이 정확하고, 상수면 배수가 된다. 이 양방향은
`PipelineMetricNamesTests`가 고정한다. `contest_submission_*` 질의에는 role이 필요하고,
`contest_scoreboard_*`와 `contest_outbox_*` 질의에는 web-role 필터가 없어야 한다.

### 앱 게이지인가 별도 exporter인가

이 지표는 스택의 다른 모든 지표와 모양이 다르다. 나머지는 전부 인스턴스별 상태(이 JVM의 heap,
이 컨테이너의 cgroup)인데 outbox backlog는 **전역 공유 DB 상태**다. 그래서 두 가지를 놓고
비교했다.

| | (a) 앱 게이지 + batch 역할 고정 | (b) sql_exporter 계열 별도 컨테이너 |
|---|---|---|
| 중복 시계열 | 소유 역할을 지정해서 막는다 | 구조적으로 1회 실행 |
| status enum 결합 | 컴파일 타임에 묶인다 | SQL이 앱 밖에 살아 결합이 끊긴다 |
| 스크레이프 경로 | 폴러를 따로 두면 DB 질의가 안 들어간다 | 구조적으로 안 들어간다 |
| 예산 | 0 | 컨테이너 1개 추가 |
| 소유 프로세스 정지 | **backlog 신호를 잃는다** | 신호가 유지된다 |

**(a)를 골랐다.** 결정적인 근거는 두 가지다.

첫째, (b)의 최대 장점인 "스크레이프 경로에 DB 질의가 안 들어간다"는 (a)에서도 얻을 수 있다.
폴러를 스케줄러에 두고 게이지는 남겨진 값만 읽으면 된다(아래 항목). 이 구조를 택하는 순간
(b)의 우위는 "소유 프로세스가 죽어도 신호가 유지된다" 하나로 줄어든다.

둘째, status 문자열은 `ContestScoreboardOutboxStatus` enum과 claim/complete SQL에 이미
박혀 있다. exporter로 빼면 같은 문자열이 리포의 두 곳에 살면서 컴파일러가 둘의 일치를 검사하지
않는다. 상태 하나가 추가되거나 이름이 바뀌면 지표가 조용히 그 상태를 세지 않게 된다 — 이
리포가 f94de98에서 이미 겪은 침묵 실패와 같은 모양이다.

예산은 결정적이지 않았다. Alertmanager를 넣고도 관측 합계가 2.2 CPU / 2112M이라 12 CPU /
14GB 안에 여유가 있다.

**대신 (a)의 약점은 그대로 남는다.** batch-1이 죽으면 backlog 게이지가 멈춘다 — drain이
멈춘 바로 그 순간이다. Prometheus는 마지막 값을 5분간 유지한 뒤 시계열을 버리므로, 알림은
`ContestOutboxDrainTimeTooLong`이 아니라 `OjAppInstanceDown`(batch-1)으로 온다. §7에 제약으로
적어두었다. 이 약점이 실제로 문제가 되면 그때 (b)로 옮기는 것이 맞고, 그 경우 위 표의 두 번째
행(enum 결합)을 어떻게 지킬지가 그 작업의 핵심이 된다.

### 왜 batch-1 한 곳에서만 게시하는가

테이블의 backlog는 그 테이블의 성질이지 읽는 프로세스의 성질이 아니다. 5개 인스턴스가 모두
폴링하면 같은 질의를 5번 하고 같은 시계열을 5개 만든다. 그리고 다른 모든 애플리케이션
지표에서 옳은 연산인 `sum()`이 여기서는 backlog를 5배로 보고한다. batch-1이 두 drain을 모두
소유하므로 읽는 것도 batch-1이 한다. `contest.outbox.metrics.enabled=false`가 web-role과
judge-role에 있다.

실측으로 `count(contest_scoreboard_pending_events)`가 1개,
`count(contest_outbox_backlog_rows)`가 5개(judge 2개 + scoreboard 3개)이며 둘 다
`count by (node) (...)`가 `batch-1` 하나만 반환하는 것을 확인한다.

### 왜 스크레이프 경로에서 질의하지 않는가

스크레이프 주기는 5s, 타임아웃은 4s다. 스크레이프가 이를 넘기면 그 인스턴스의 **모든** 지표가
사라진다. backlog가 커진 순간이 곧 질의가 느려지는 순간이므로, 스크레이프 경로에 DB 왕복을
두면 가장 보고 싶을 때 계기판 전체가 꺼진다. 폴러가 스케줄러에서 돌고 게이지는 남겨진 값만
읽는다.

### 왜 질의에 상한을 두는가

두 outbox 모두 terminal 행(`PUBLISHED`, `COMPLETED`)의 purge 정책이 없다
(파이프라인 히스토리 §9.4). 그래서
전체를 세는 질의는 backlog가 아니라 테이블 크기에 비례한다. 질의는 non-terminal 상태만
보고, 파생 테이블 `LIMIT`로 스캔 자체를 묶는다.

MySQL 8.0에서 실측한 계획과 시간이다. 두 테이블 각각 40만 행이고, `EXPLAIN ANALYZE`로 쟀다.

| 질의 | 계획 | backlog 2만 | backlog 40만 |
|---|---|---:|---:|
| judge 개수 (상한 100000) | `idx_contest_judge_outbox_claim` covering range scan | 15.2 ms | — |
| judge head lag | 같은 인덱스, 1행 | 0.08 ms | — |
| scoreboard 개수 (상한 100000) | `idx_cs_outbox_status_created` covering range scan | 13.3 ms | 69.2 ms |
| scoreboard head lag | `idx_cs_outbox_due` covering range scan, 1행 | 0.01 ms | 0.02 ms |
| (비교) 상한 없는 같은 개수 질의 | 같은 인덱스, 전량 | — | **1189 ms** |
| (비교) `GROUP BY status` 전체 | covering index scan 40만 항목 | 62.7 ms | — |

네 질의 모두 covering index다. 행 조회가 없다. 상한은 실제로 적용된다 — 계획에
`Limit: 100000 row(s)`가 남아 있고 읽은 항목 수가 정확히 상한에서 멈춘다. 파생 테이블의
`LIMIT`가 바깥 질의로 병합돼 사라지는 경우가 있어 통합 테스트로 이 동작을 고정해 두었다.

40만 backlog에서 상한이 1189 ms를 69 ms로 바꾼다. 그리고 상한 없는 쪽은 backlog와 함께
계속 자란다. 상한에 닿은 게이지는 실제보다 작은 값이지만, 그 시점은 이미 모든 임계값을 한참
넘긴 상태다.

### head lag가 PENDING만 보는 이유

**활성 상태 3개는 서로 다른 신호다.** backoff 중인 FAILED 행은 정당하게 오래됐고, lease 안에
있는 PROCESSING 행도 정당하게 오래됐다. 둘 다 "처리량이 부족하다"는 뜻이 아니다. 세 상태를
한 값으로 뭉개면 "처리량이 모자란다"와 "뭔가 한 시간째 재시도 중이다"가 구분되지 않는다.

그래서 두 outbox 모두 head lag는 **PENDING 행의 나이**만 잰다. stuck 신호는 FAILED count와
PUBLISHING count가 따로 나른다. `ContestOutboxBacklogMetricsMySqlIntegrationTests`가 한
시간 지난 FAILED·PROCESSING 행을 넣고 head lag가 0인 것을, 거기에 PENDING 행 하나를 더하면
그 나이가 나오는 것을 고정한다.

PENDING에 "그리고 due가 지났다"는 조건은 필요 없다. `due_at`은 insert 시점에 찍히므로 PENDING
행은 생긴 순간부터 claim 가능하다.

### head lag의 시계와 인덱스

뺄셈은 MySQL 안에서 한다(`TIMESTAMPDIFF(..., CURRENT_TIMESTAMP(6))`). JVM 시계와 DB 시계를
섞으면 스큐가 상수 편향으로 들어오고 음수가 시계 문제인지 지표 버그인지 구분되지 않는다.
scoreboard 쪽 값이 `created_at`이 아니라 `due_at`인 이유도 이것이다 — `created_at`은 JVM이
써 넣지만 `due_at`은 `CURRENT_TIMESTAMP(6)`로 찍힌다.

두 질의 모두 `status='PENDING'`으로 좁힌 뒤 인덱스 순서의 첫 행 하나만 읽는다. PENDING이면
`claimed_at`이 NULL이라는 불변식(claim이 둘을 함께 쓰고 완료 경로가 둘을 함께 지운다) 덕분에
인덱스 범위 안의 첫 항목이 곧 가장 오래된 행이다. 40만 행 fixture에서 잰 값이다.

| 질의 | 계획 | 시간 |
|---|---|---:|
| scoreboard head lag (PENDING only) | `idx_cs_outbox_claim` index lookup, 1행 | 0.18 ms |
| judge head lag (PENDING only) | `idx_contest_judge_outbox_claim` index lookup, 1행 | 0.03 ms |
| (기각) `MIN(created_at) WHERE status='PENDING'` | 같은 인덱스, **PENDING 19000행 전부** | 10.3 ms |
| (기각) `due_at` 인덱스 + status 필터 | due_at 순서로 훑으며 비-PENDING을 버림 | 0.04 ms |

`MIN(created_at)` 형태는 PENDING 행을 전부 읽는다 — backlog가 깊을수록 비싸지므로 **지표가
필요한 바로 그 순간에 비싸진다.** `ORDER BY ... LIMIT 1`은 항상 1행이다.
`contest_judge_outbox`의 인덱스에는 `created_at`이 없지만, `LIMIT 1` 덕분에 행 조회가 한 번뿐이라
인덱스에 컬럼을 추가할 필요가 없었다.

마지막 기각안은 이 fixture에서 0.04 ms로 싸 보이지만 계획이 `Filter: status='PENDING'`을
인덱스 스캔 **뒤에** 둔다. due_at 순서 앞쪽에 비-PENDING 행이 길게 늘어서면 그만큼 훑는다.
싸 보이는 것이 데이터 배치에 달려 있는 계획이라 쓰지 않았다.

### 실측: backlog가 쌓였다가 빠지는 곡선

격리 부하 스택(`oj-loadtest`)에 nginx를 통해 제출 2500건을 넣고 2초 간격으로 게이지를 읽었다.
이 하드웨어에서는 relay가 5000행/s, scoreboard worker가 2500행/s를 빼므로 제출 부하만으로는
backlog가 쌓이지 않는다. 그래서 **scoreboard worker만** `batch-size=1`,
`poll-interval=3000ms`(약 0.33행/s)로 굶겼다. 행·insert·drain은 전부 실제 코드 경로이고
drain 속도만 제한한 것이다.

| 시각 | judge PENDING | scoreboard PENDING | judge lag | scoreboard lag |
|---|---:|---:|---:|---:|
| 17:56:51 | 0 | 0 | 0.0s | 0.0s |
| 17:56:56 | 67 | 331 | 1.5s | 2.9s |
| 17:57:11 | 5 | 1088 | 0.1s | 17.9s |
| 17:57:40 | 0 | 2485 | 0.0s | 47.7s |
| 17:58:09 | 0 | 2478 | 0.0s | **72.6s** |
| 17:58:13 | 0 | 0 | 0.0s | 0.0s |

읽을 것이 세 가지다.

1. **judge outbox는 따라잡는다.** 같은 부하에서 PENDING이 67까지 튀었다가 곧 0으로 돌아오고
   lag가 1.5초를 넘지 않는다. 굶긴 쪽과 정상인 쪽이 나란히 보인다.
2. **17:57:40 → 17:58:09 구간이 count와 age를 같이 봐야 하는 이유다.** count는 2485에서
   2478로 사실상 평평한데(-0.3%) age는 47.7초에서 72.6초로 계속 오른다. count만 보면 "아무
   일도 없다", age를 같이 보면 "맨 앞 행이 늙고 있다"가 된다. 대시보드가 두 패널을 나란히
   두는 근거가 이 구간이다.
3. **17:58:09 → 17:58:13.** worker를 정상 설정으로 되돌리자 2478행이 한 샘플 안에 빠졌다.
   500행/200ms면 약 1초 분량이므로 계산과 맞는다.

### 구현 중립/진단 곡선 등가성 실측

2026-08-08 `submit-100`의 새 contest 22에서 batch-1을 잠깐 pause해 PENDING 행을 만든 뒤,
MySQL locking read로 그 행들을 잡았다. batch를 다시 살리면 worker는 `SKIP LOCKED`로 건너뛰지만
metric 폴러와 Prometheus scrape는 계속 동작하므로 backlog와 alert의 상승·회복을 실제 코드로
검증할 수 있다. 이는 고의 장애 실행이라 하네스의 paused-container 안전검사가 실패했으며 구현
성능 비교 표본으로 쓰지 않는다.

Prometheus의 같은 시각 range sample에서 새 값과 기존 outbox 값의 절댓값 차이를 계산했다.

| 비교 | sample 수 | 최대 차이 | 0이 아닌 sample |
|---|---:|---:|---:|
| `pending_events` vs scoreboard 상태 합 | 105 | 0 | 0 |
| `oldest_ready` vs scoreboard head lag | 105 | 0s | 0 |
| `applied_total` vs scoreboard drained | 126 | 0 | 0 |
| 새/기존 drain `> 120s` predicate | 109 | 0 | 0 |
| 새/기존 oldest-ready `> 60s` predicate | 105 | 0 | 0 |

잠금 중 관측한 대표 동시 sample은 pending `2052 = 2052`, oldest-ready
`326.333741s = 326.333741s`, 5분 applied rate `0 = 0`, estimated drain `+Inf`였다.
`ContestOutboxHeadOfLineStalled`는 14:07:57~14:12:57, `ContestOutboxDrainTimeTooLong`은
14:12:12~14:12:57에 실제 `firing`이었다. alert ID는 기존 라우팅·대시보드 이력 호환성을 위해
유지했지만 두 predicate가 모든 sample에서 같으므로 기존 outbox 식도 같은 평가 시점에 같은
상태가 된다.

정상 부하에서 공통/기존 observation failure counter는 둘 다 0이었다. 단위 테스트는 scoreboard
질의를 실패시켜 두 counter가 같은 catch 경로에서 함께 1 증가하고 두 공통 gauge가 직전 값을
유지하는 것도 고정한다. 잠금을 풀자 judge backlog는 0, scoreboard는 19,237건 전부 COMPLETED로
회복했고 두 alert도 inactive로 돌아왔다.

### 제출 단위 end-to-end staleness

`gatling/run-loadtest.ps1`은 drain과 정합성 검사가 끝난 뒤, 같은 제출의 시작과 종점을 직접
빼서 다음 두 분포를 출력한다. 분위수는 MySQL 8의 `ROW_NUMBER()`를 사용한 nearest-rank
p50/p95/p99이며 `max`와 표본 수 `n`을 항상 함께 낸다. 단계별 분위수를 더한 값이 아니다.

| 출력 이름 | 시작 | 종점 | SQL 의미 |
|---|---|---|---|
| `result queryable` | `contest_submission.submitted_time` | `contest_submission_result.result_saved_at` | `TIMESTAMPDIFF(MICROSECOND, submitted_time, result_saved_at)` |
| `scoreboard applied` | `contest_submission.submitted_time` | `contest_submission_result.scoreboard_applied_at` | `TIMESTAMPDIFF(MICROSECOND, submitted_time, scoreboard_applied_at)` |

V17 전환 검증 중에는 아래 legacy 정의도 같은 CSV에 나란히 남긴다. 주 지표는 위 두 행이며,
legacy 행은 과거 결과와의 연결만을 위한 임시 출력이다.

| CSV 출력 이름 | legacy 종점 |
|---|---|
| `legacy-result-queryable` | `contest_submission_outbox.created_at` |
| `legacy-scoreboard-applied` | `contest_submission_outbox.processed_at` |

시작은 **(가) 요청 처리 중 `SubmissionService`가 `LocalDateTime.now()`로 찍은 서버 시각**이다.
200 응답 시각이 아니므로 admission semaphore 대기, bulk queue 대기, 제출 DB transaction과
completion executor를 포함한다. 따라서 이 문서와 리포트에서 end-to-end staleness는
"제출 처리를 시작한 뒤 결과/scoreboard가 보일 때까지"를 뜻한다. HTTP 200 이후만을 뜻하는
값으로 읽으면 안 된다.

200 반환 시각은 제출 행에 저장하지 않는다. `http_server_requests`는 요청 앞단 분포를 별도로
보여 주므로 두 분포를 나란히 비교할 수는 있지만, 서로 다른 표본의 p99를 빼서 "200 이후
p99"를 만들지는 않는다. 그 값도 어떤 실제 제출의 p99가 아니기 때문이다. (나)가 제출별로
필요해지면 응답 완료 시각 또는 상관 가능한 요청 식별자를 별도로 저장해야 한다.

`result queryable`의 종점으로 `provisional_judged_at`을 쓰지 않는다. 그 값은 judge가 result
writer queue에 넣기 전에 찍혀 queue 대기와 결과 DB 쓰기를 누락한다. 대신 결과 batch INSERT
statement가 `result_saved_at = CURRENT_TIMESTAMP(6)`을 함께 쓴다. 결과 행은 transaction이
commit된 뒤에만 조회 가능하므로 조회 가능 시점에 가까운 저장 시각이면서 outbox 스키마에
의존하지 않는다. 다만 MySQL의 실제 MVCC commit timestamp는 아니어서, result INSERT 뒤
outbox INSERT와 commit에 걸린 짧은 구간은 포함하지 않는다는 한계가 있다.

`scoreboard applied`의 `scoreboard_applied_at`은 Redis Lua/pipeline 적용이 성공한 뒤 outbox의
`processed_at`과 같은 MySQL statement에서 `CURRENT_TIMESTAMP(6)`로 기록된다. 따라서 두 번째
분포는 judge와 result writer를 거쳐 Redis 반영까지의 전체 경로를 포함한다. 두 쿼리 모두
`contest_submission_result`까지만 읽으며, 하네스가 먼저
`submissions == results == result timestamps == scoreboard timestamps == outbox == completed`인지
검사해 표본 완전성을 보장한다.

V16부터 기존 관련 시각을 `DATETIME(6)`으로 저장하고 V17부터 결과 행의 두 종점도
`DATETIME(6)`으로 저장한다. V16 이전 행은 기존 시각의 소수부가 `.000000`이고 V17 이전 행은
새 종점이 `NULL`이므로, **마이그레이션 이전 행이 섞인 분포는 신뢰할 수 없다.** 부하 하네스는
초기화 뒤 매 실행 새 contest를 seed하며, materialization 검사에서 새 종점의 non-null 행 수도
확인하므로 실행끼리 또는 migration 전후 표본이 섞이지 않는다.

대표값은 조회와 제출이 동시에 Redis를 쓰는 `mixed`에서 잰다. `smoke`에도 같은 SQL을 적용해
조용한 scoreboard 조건과 비교한다. 실행별 원시 마이크로초 값은
`var/loadtest-*/end-to-end-staleness.csv`에 남는다.

아래 첫 실측값은 V17 이전 legacy/outbox 정의로 측정한 역사적 기준선이다.
`mixed-target`(제출 139→200 RPS, 조회 300 RPS, 사용자 10,000명), web CPU가
상한의 35~38%에 머문 조건이므로 자원 상한이 아니라 파이프라인을 잰 값이다.

```text
-- end-to-end staleness --
result queryable    p50 0.352s  p95 3.498s  p99 4.347s  max 9.744s    n=35865
scoreboard applied  p50 0.607s  p95 4.046s  p99 4.921s  max 13.270s   n=35865
```

두 분포의 차이가 scoreboard outbox 단계의 비용이다. p50에서 0.26초, p99에서 0.57초로 작고,
지연의 대부분은 judge 경로에 있다.

**같은 실행에서 재시도(`attempts > 1`)는 0건이었다.** 이 값이 0이 아니면 아래 §9.5의 오염을
먼저 의심한다. 재시도가 섞인 실행에서는 같은 조건인데도 p99가 31초, max가 178초까지 벌어졌다.

하네스는 scoreboard p99와 같은 제출+drain 구간에서 샘플링한
`contest_scoreboard_oldest_ready_seconds`와 같은 정의로 샘플링한 최댓값의 비율도 계산한다. 두 양수 값의
비율이 10배 미만이면 `PASS`, 아니면 `MISMATCH`다. `mixed`에서 샘플이 없거나 `MISMATCH`이면
실행을 실패시킨다. 이는 두 값이 같은 통계라는 뜻이 아니라, end-to-end SQL이 scoreboard
backlog와 무관한 시각을 잘못 빼는 오류를 잡는 자릿수 교차 검증이다.

#### V17 종점 전환 검증과 쓰기 비용

`var/loadtest-20260808-132805`의 `submit-100` 한 실행에서 V17이 적용된 새 contest 21만
측정했다. Redis와 두 outbox는 실행 전에 모두 0이었고, 재시도·JVM 재시작은 0, 관측 JVM은
5/5였다. 19,369개 제출 모두 result/outbox 완료 행과 새 시각 두 개를 가졌다.

| 정의 | p50 | p95 | p99 | max | n |
|---|---:|---:|---:|---:|---:|
| 새 `result_saved_at` | 0.243134s | 0.627074s | 1.404445s | 5.322052s | 19,369 |
| legacy `outbox.created_at` | 0.244120s | 0.631241s | 1.406942s | 5.322821s | 19,369 |
| 새 `scoreboard_applied_at` | 0.436580s | 1.101685s | 7.583841s | 13.363715s | 19,369 |
| legacy `outbox.processed_at` | 0.436580s | 1.101685s | 7.583841s | 13.363715s | 19,369 |

scoreboard의 새/legacy 시각을 submission별로 직접 비교한 불일치 행은 0이다. 두 컬럼을 같은
완료 statement의 DB 시계로 찍기 때문이다. 결과 쪽은 legacy가 새 정의보다 submission별
0.165~96.452ms 늦었고 paired delta(`created_at - result_saved_at`)는 p50 0.473ms,
p95 1.866ms, p99 16.629ms, 평균 1.045ms였다. 새 시각은 result INSERT statement에서 찍고,
legacy `created_at`은 그 배치가 끝난 뒤 outbox INSERT를 준비하며 JVM에서 찍으므로 새 정의는
그 사이 구간을 포함하지 않는다. 다만 이 paired delta에는 DB 시계와 legacy JVM 시계의 상수
스큐도 섞이므로 1.045ms 전부를 코드 구간 비용으로 해석할 수 없다. 이것이 새 종점 두 개를 모두
DB 시계로 고정한 이유다. 위 분포의 p95끼리 뺀 4.167ms도 서로 다른 순위의 표본 차이일 수 있어
paired p95로 해석하지 않는다.

MySQL `performance_schema`의 statement server time으로 추가 쓰기 비용도 같은 환경에서
측정했다. 기존 완료 UPDATE는 누적 457,672건에서 평균 0.219ms/제출이었고, 결과 행까지 함께
쓰는 V17 완료 UPDATE는 19,369건에서 평균 0.341ms/제출이었다. 즉 결과 행 한 개를 더 갱신하는
직접 비용은 **약 0.122ms/제출(+55.7%)**이며 affected row는 1개에서 2개가 된다. result INSERT의
행당 시간은 legacy 0.122ms, V17 0.108ms로 이 실행에서는 추가 비용이 관측되지 않았다. 두
statement를 합친 DB server time은 약 0.341ms에서 0.449ms로 **0.108ms/제출(+31.6%)** 늘었다.
이는 DB statement 비용 측정이지 HTTP latency 인과 추정은 아니다.

실제로 이 실행의 HTTP p95와 staleness p95는 아래 5회 기준선 밖이므로 구현 성능 비교에는
사용하지 않는다. 목적은 같은 행에서 종점 정의를 교차 검증하고 추가 SQL 쓰기 비용을 재는
것이며, 구현 비교는 아래 최소 5회 합격 기준을 그대로 적용한다.

### submit-100 반복 편차와 비교 가능한 실행의 조건

아래 수치는 V17 이전 legacy/outbox 종점 정의다. 2026-08-08에 애플리케이션 코드
`1c10173`으로 `submit-100`을 5회 반복했다. 모두 기본 설정
(사용자 10,000명, 문제 5개, 30초 ramp, 180초 hold, 100 RPS), 같은 load-test/observability
스택과 같은 Compose 상한을 사용했다. 실행 전에 일반 OJ 스택이 꺼져 있는지 확인했고, 매번
Redis `DBSIZE`와 `contest_submission_outbox`, `contest_judge_outbox`가 모두 0인지 검증했다.

하네스는 실행별로 다음 파일을 추가로 남긴다.

- `state-reset.csv`: Redis와 두 outbox의 초기화 직후 행 수
- `jvm-metrics.csv`: `PipelineIntervalSeconds`(기본 5초) 간격으로 수집을 시도한 노드별 CFS throttle 비율
  (`rate(cgroup_cpu_throttled_periods_total[30s]) / rate(cgroup_cpu_periods_total[30s])`),
  `process_start_time_seconds`, GC pause 누적 시간, heap 사용량
- `jvm-summary.csv`: throttle 중앙값/p95, `process_start_time_seconds` 변화 수, GC 시간 증분,
  heap 중앙값/p95
- `run-diagnostics.csv`: `attempts > 1`인 scoreboard outbox 행 수와 전체 JVM 재시작 수,
  관측된 JVM 노드 수

| 실행 (`var/loadtest-*`) | result p95 | scoreboard p95 | 최대 노드 throttle 중앙값 / p95 | JVM 재시작 | `attempts > 1` | GC 합계 | 최대 heap p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `20260808-124919` | 0.364s | 0.585s | 0% / 4.03% | 0 | 0 | 0.245s | 427MiB |
| `20260808-125358` | 0.354s | 0.576s | 0% / 3.20% | 0 | 0 | 0.212s | 421MiB |
| `20260808-125810` | 0.356s | 0.575s | 0% / 2.40% | 0 | 0 | 0.321s | 428MiB |
| `20260808-130225` | 0.358s | 0.583s | 0% / 3.60% | 0 | 0 | 0.272s | 411MiB |
| `20260808-130641` | 0.357s | 0.578s | 0.40% / 1.20% | 0 | 0 | 0.215s | 409MiB |

모든 실행은 HTTP 성공률 100%, JVM 5개 노드 관측, 재시작·재시도·OOM 0을 만족했다. 표본 수는
19,480~19,517이고 각 실행에서 submissions = results = outbox = completed가 성립했다. §8의
기동 직후 61~78% throttling은 부하 중에 유지되지 않았다. 아래 사분위수는 정렬한 5개 값에서
위치 `(n - 1) p`를 선형 보간하는 방식으로 계산했다.

예비 실행 `20260808-124359`은 최종 `run-diagnostics.csv`와 staleness CSV가 없어서 위 5회와
통계에서 제외했다. 부하가 끝났다는 사실만으로 표본 완전성이 증명되지는 않는다는 기준을 그대로
적용한 것이다.

| 분포 | 실행별 값 | 중앙값 | Q1 | Q3 | IQR | 실측 min~max |
|---|---|---:|---:|---:|---:|---:|
| result queryable | p50 | 0.219s | 0.219s | 0.220s | 0.001s | 0.217~0.223s |
| result queryable | p95 | **0.357s** | 0.356s | 0.358s | **0.003s (0.77%)** | 0.354~0.364s |
| result queryable | p99 | 0.412s | 0.411s | 0.413s | 0.002s | 0.407~0.424s |
| result queryable | max | 0.626s | 0.573s | 0.657s | 0.084s | 0.564~0.815s |
| result queryable | n | 19,493 | 19,492 | 19,502 | 10 | 19,480~19,517 |
| scoreboard applied | p50 | 0.394s | 0.394s | 0.397s | 0.003s | 0.392~0.398s |
| scoreboard applied | p95 | **0.578s** | 0.576s | 0.583s | **0.007s (1.27%)** | 0.575~0.585s |
| scoreboard applied | p99 | 0.648s | 0.641s | 0.656s | 0.016s | 0.639~0.671s |
| scoreboard applied | max | 0.844s | 0.806s | 0.953s | 0.147s | 0.777~1.102s |
| scoreboard applied | n | 19,493 | 19,492 | 19,502 | 10 | 19,480~19,517 |

따라서 같은 조건의 p95는 result에서 중앙값의 0.992~1.022배, scoreboard에서
0.995~1.013배 안에 실측으로 모였다. max는 상대 IQR이 각각 13.4%, 17.5%라 실행 합격 기준이
아니라 꼬리 진단값으로만 쓴다.

#### 0.56초 대 6.86초는 정상 분산이 아니다

문제가 된 두 실행의 보존된 Prometheus 시계열을 같은 부하 시간창으로 다시 읽었다.

| 신호 | `20260808-114654` (result p95 0.556s) | `20260808-115115` (result p95 6.864s) |
|---|---:|---:|
| JVM restart | 0 | **judge-1 1회** |
| `up{node="judge-1"}` | 계속 1 | **11:52:55~11:53:50 0** |
| 최대 노드 throttle 중앙값 / p95 | 0.80% / 4.80% | 0.81% / 32.26% (judge-1 재기동 구간) |
| scoreboard retry counter 증분 | 0 | 0 |
| GC pause 합계 | 0.192s | 0.211s |
| 최대 heap p95 | 409MiB | 413MiB |
| HTTP 성공률 / p95 | 100% / 184ms | 100% / 186ms |

느린 실행에서만 `process_start_time_seconds`가 바뀌었고 judge-1이 약 55초 동안 스크레이프되지
않았다. throttle 중앙값은 두 실행 모두 1% 미만이라 §8의 자원 상한 상태가 아니며, 느린 실행의
p95 throttle만 재기동과 함께 순간적으로 올랐다. GC와 heap도 반복 5회의 범위 안이다. 그러므로
12배 차이는 정상 분산이나 CPU 상한이 아니라 **`20260808-115115` 실행 중 JVM 중단 사고**와
상관된다. OOM 지표는 0이지만 §7에 적은 이유로 이것만으로 OOM을 배제할 수 없고, 보존된 지표는
재시작의 원인까지 구분하지 못한다.

`20260808-114654`의 0.556초도 위 5회 result p95 범위(0.354~0.364초)와 아래 산포 기준 밖이다.
그 실행에서는 지정한 사고 신호가 발견되지 않았으므로 원인을 추정해 살려 쓰지 않는다. 기존
두 번만으로 만든 비교 자체를 폐기하고, 위 5회를 현재 기준선으로 사용한다.

#### 이후 구현 비교의 명시적 합격 기준

다음 조건을 모두 만족한 실행만 구현 비교에 사용한다.

1. 비교하려는 구현 외에는 같은 호스트/WSL 예산, Compose 파일과 자원 상한, observability
   overlay, 시나리오 파라미터와 환경 변수를 사용한다. 일반 OJ 스택이나 다른 부하 작업을 함께
   실행하지 않는다.
2. 실행마다 `state-reset.csv`의 Redis·두 outbox가 모두 0이어야 한다. 하나라도 0이 아니면
   즉시 무효다.
3. Gatling assertion과 materialization 검사를 통과하고 OOMKilled=false여야 한다.
   `run-diagnostics.csv`는 `attempts > 1 = 0`, JVM restart=0, observed JVM nodes=5여야 한다.
4. 부하 구간의 앱 노드별 throttle 중앙값이 모두 **10% 이하**여야 한다. 이번 정상값 0~0.4%에
   충분한 여유를 주면서 §8의 61~78%가 지속되는 자원 상한 실행을 배제하는 경계다. GC와 heap은
   함께 보고하되, 구현 자체가 바꾸려는 값일 수 있으므로 기준선과 다르다는 이유만으로 버리지
   않는다.
5. 구현 후보마다 위 조건을 통과한 실행을 **최소 5회** 확보하고 p50/p95/p99/max/n의 중앙값,
   Q1, Q3, IQR을 보고한다. 대표값 하나나 가장 좋은 실행을 고르지 않는다.
6. 각 후보의 result와 scoreboard p95가 모두 `IQR / 중앙값 <= 5%`이고, 모든 개별 p95가
   `[Q1 - 3 IQR, Q3 + 3 IQR]` 안에 있어야 반복 집합이 안정적이다. 이번 result 허용 구간은
   0.347~0.367초, scoreboard는 0.554~0.605초이고 5회가 모두 들어왔다.

**이 조건을 만족하지 않는 실행은 구현 비교에 쓰지 않는다.** 재시작·재시도처럼 원인이 있는
개별 실행만 무효 처리하고 다시 실행한다. 사고 신호 없이 5% 상대 IQR이나 3 IQR 경계를
넘는다면 편한 실행만 제거하지 않고 환경이 불안정한 것으로 보고 그 후보의 반복 집합 전체를
다시 측정한다.

## 11. 알림

규칙은 `observability/prometheus/rules/oj-pipeline.yml`에 있고 Prometheus가 기동 시 문법을
검사한다. 잘못된 규칙 파일은 그 안의 알림만 조용히 꺼지는 게 아니라 Prometheus를 멈춘다.
아래 세 `ContestOutbox*` alert ID는 기존 Alertmanager route와 이력을 끊지 않기 위해 유지하지만,
표현식과 annotation은 `contest_scoreboard_*` 공통 계층만 읽는다.

| 알림 | 조건 | for | 등급 |
|---|---|---:|---|
| `ContestOutboxDrainTimeTooLong` | scoreboard 예상 drain 시간 > 120s | 2m | warning |
| `ContestOutboxHeadOfLineStalled` | scoreboard oldest-ready > 60s | 2m | warning |
| `ContestOutboxBacklogUnobserved` | scoreboard 공통 gauge 갱신 실패 | 5m | warning |
| `ContestSubmissionShedding` | 503 shed rate > 0 | 2m | warning |
| `ContestSubmissionCompletionSaturated` | CallerRuns rate > 0 | 5m | warning |
| `OjAppInstanceDown` | `up{job="oj-app"} == 0` | 1m | critical |
| `OjAppInstanceRestarted` | 10분 내 `process_start_time_seconds` 변화 | — | critical |

`estimated_drain_seconds = scoreboard_pending / recent_scoreboard_apply_rate`(파이프라인 히스토리
§9.2)는 recording
rule 3개로 들어갔다. 대시보드 패널과 알림이 같은 규칙을 읽으므로 정의가 하나다.

drain rate가 0이면 결과는 `+Inf`다. 나누기를 방어하지 않은 것은 의도다. 움직이지 않는
pending work에는 유한한 drain 시간이 없고, 그 상태가 가장 알림이 필요한 상태다. 방어하면
그래프에 구멍이 생긴다. 빈 scoreboard pipeline은 0/0이라 `NaN`이고 모든 비교에서 false이므로
놀고 있는 스택은
조용하다.

oldest-ready를 따로 두는 이유는 drain rate가 볼 수 없는 절반이 있기 때문이다. 뒤쪽 backlog가
정상적으로 빠지는 동안 맨 앞 행 하나가 막혀 있으면 예상 drain 시간은 건강하게 나온다.

**CPU throttling 알림은 일부러 만들지 않았다.** §8이 부하 없이도 모든 인스턴스에서 61~78%를
기록했으므로, 의미가 있을 만큼 낮은 임계값은 항상 발화한다. throttling은 실행을 해석할 때
대시보드에서 읽는 값이지 호출을 받을 값이 아니다.

`OjAppInstanceDown`은 다른 어디에도 신호가 없는 실패를 위해 있다. 관리 포트 없이 뜬 역할은
커넥터를 하나도 바인딩하지 않고, 예외 없이 기동하고, 프로세스 존재만 보는 헬스체크를 계속
통과한다(§3). 스크레이프 대상이 down이 되는 것이 유일한 신호다. `OjAppInstanceRestarted`는
§7의 `cgroup_memory_oom_kills`가 닿지 못하는 절반 — JVM 자신이 OOM으로 죽는 경우 — 을 잡는다.

Alertmanager의 inhibition은 critical이 warning을 누르도록 해 두었다. 인스턴스가 죽었거나 방금
재시작했다면 그 인스턴스가 만든 숫자는 전부 무효다. backlog 게이지는 batch-1에서만 나오므로
batch-1이 내려가면 outbox warning들은 독립된 발견이 아니라 critical의 결과다.

## 12. RabbitMQ 큐별 지표와 judge queue baseline

### detailed endpoint와 시계열 상한

기본 `rabbitmq` job은 노드 전체 상태를 보는 저비용 합산 `/metrics`를 그대로 유지한다. 큐를
구분하는 `rabbitmq-per-queue` job만 `/metrics/detailed`를 추가로 긁는다. 전체 per-object 모드를
켜지 않은 이유는 연결·채널 수가 늘 때 시계열도 함께 늘기 때문이다. RabbitMQ 4.1은 detailed
endpoint에서 family를 고를 수 있으므로 다음 네 개만 요청한다
([공식 Prometheus 지표 목록](https://www.rabbitmq.com/docs/4.1/prometheus)).

| family | 이 리포에서 쓰는 값 |
|---|---|
| `queue_coarse_metrics` | ready, unacked |
| `queue_consumer_count` | consumer 수 |
| `queue_delivery_metrics` | 큐별 manual/auto-ack deliver counter |
| `queue_exchange_metrics` | exchange를 거쳐 큐에 들어온 publish counter |

그 응답에서도 dashboard와 부하 하네스가 읽는 6개 metric 이름만 metric relabeling으로 남긴다.
채널 ID가 붙는 `channel_queue_metrics`와 `channel_queue_exchange_metrics`는 사용하지 않는다.
현재 32개 consumer channel에서 `channel_queue_metrics`만 직접 요청해도 230 samples였지만, 선택한
네 family의 원 응답은 26 samples이고 relabel 뒤 RabbitMQ payload는 **9 series**다. 마지막으로
`sample_limit: 100`을 둔다. 새 queue/stream을 잘못 무제한 노출하면 target 전체가 `up=0`이 되어
조용한 TSDB 증가가 아니라 명시적인 관측 실패가 된다.

2026-08-08 같은 실행 중 reload 전후 실측은 다음과 같다.

| 항목 | 전 | 후 | 증가 |
|---|---:|---:|---:|
| detailed RabbitMQ payload | 0 | 9 | +9 |
| target 자체 `up`/scrape 상태 series | 0 | 5 | +5 |
| `rabbitmq-per-queue` job 합계 | 0 | **14** | **+14** |
| 기존 합산 `rabbitmq` job | 1,677 | 1,683 | +6 |
| Prometheus head series | 12,177 | 12,197 | +20 |

기존 job 증가 6개 중 4개는 detailed endpoint 호출 뒤 생긴
`telemetry_scrape_encoded_size_bytes_{count,sum}{registry="detailed"}`(gzip/identity)였고 나머지 2개는
같은 시간창의 aggregate drift다. 새 job에서 직접 센 값은 14지만 원인을 낙관적으로 빼지 않고
**관측된 head 증가 전체 20 series(12,177 대비 0.16%)를 이번 변경의 시계열 예산으로 잡는다.**
새 서비스나 컨테이너 상한은 없으므로 §2 자원 예산 표는 바뀌지 않는다.

### `submit-100` baseline

실행은 `var/loadtest-20260808-142701`이다. 이전 실패 실행이 남긴 격리 load-test DLQ 10건은
개수를 확인한 뒤 실행 전에 purge했고, 첫 sample에서 live와 DLQ가 모두 ready=0/unacked=0임을
확인했다. 새 contest 23을 seed했고 `state-reset.csv`의 Redis·두 outbox는 모두 0이었다. 결과는
submissions = results = scoreboard completed = 19,495, HTTP 성공률 100%, outbox retry 0, JVM restart
0, OOMKilled=false였다.

`rabbitmq-metrics.csv`의 35개 queue별 sample과 `rabbitmq-summary.csv`의 결과는 다음과 같다.

| queue | ready max | unacked max | consumers min~max | publish 평균 / 5s구간 최대 | deliver 평균 / 5s구간 최대 | counter delta |
|---|---:|---:|---:|---:|---:|---:|
| `contest.judge.live` | 0 | 19 | 32~32 | 88.97/s / 165.54/s | 88.97/s / 169.32/s | publish 19,495 / deliver 19,495 |
| `contest.judge.dead` | 0 | 0 | 0~0 | 0 / 0 | 0 / 0 | publish 0 / deliver 0 |

Prometheus에서 같은 시간창을 30초 rate로 읽으면 live publish와 deliver 최대가 모두 102.84/s였다.
ready가 0인 것은 누락이 아니라 `submit-100`이 judge drain rate 아래에 있다는 시나리오 계약과
일치한다. broker에 머문 일은 unacked 최대 23으로 보이고, 32 consumers는 전 구간 유지됐다.

`contest_outbox_backlog_rows{outbox="judge"}`는 같은 Prometheus 시간창에서 0~37, 하네스의 DB
직접 sample은 0~26이었다. §7의 5초 gauge poll과 scrape 위상 차이 때문에 최대값은 같을 필요가
없다. 더 중요한 보존 관계는 정확히 맞았다. `pipeline.csv`에서 judge outbox가 PUBLISHED로 바뀐
증분 19,495건과 RabbitMQ live queue publish counter 증분 19,495건의 차이가 **0**이고, live
publish/deliver delta도 둘 다 19,495였다. 즉 outbox에 잠깐 쌓인 work가 broker로 이동한 뒤 ready
backlog를 만들지 않고 즉시 consumer에게 전달됐으며, DLQ 유입이나 유실은 없었다. 대시보드에서
두 depth가 같은 모양이어야 하는 것이 아니라 이 연속 단계와 보존 관계가 성립해야 한다.
