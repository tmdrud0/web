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
- Prometheus TSDB 쓰기와 MySQL commit이 공유하는 디스크 I/O

앞의 둘은 스크레이프 주기로 조절한다. exporter 계열을 10s로 둔 이유가 이것이다.

## 3. 스크레이프 대상

| job | 대상 | 경로 | 주기 |
|---|---|---|---:|
| `oj-app` | web-1, web-2, batch-1, judge-1, judge-2 (`:9000`) | `/actuator/prometheus` | 5s |
| `rabbitmq` | rabbitmq:15692 | `/metrics` | 5s |
| `mysql` | mysqld-exporter:9104 | `/metrics` | 10s |
| `redis` | redis-exporter:9121 | `/metrics` | 10s |
| `nginx` | nginx-exporter:9113 | `/metrics` | 10s |
| `cadvisor` | cadvisor:8080 | `/metrics` | 10s |
| `prometheus` | localhost:9090 (자기 자신) | `/metrics` | 15s |

job은 7개, 스크레이프 대상은 `oj-app`의 5개를 포함해 모두 11개다.

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

1. Prometheus `Status > Target health`에서 위 7개 job, 대상 11개가 모두 `UP`인지 본다.
2. `up{job="oj-app"}` 이 5개 시계열을 반환하는지 본다. 5개가 아니면 해당 역할의 관리 포트가
   열리지 않은 것이다.
3. `cgroup_cpu_limit_cores` 가 compose 상한과 일치하는지 본다. 이 값이 어긋나면 컨테이너가
   의도한 자원 상한으로 뜨지 않은 것이므로 그 실행의 측정값은 버린다.

   | node | 기대값 |
   |---|---:|
   | web-1, web-2 | 1 |
   | judge-1, judge-2 | 0.75 |
   | batch-1 | 0.5 |

4. `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{role="web"}[5m])))`
   가 값을 반환하는지 본다. 비어 있으면 히스토그램 버킷이 꺼진 것이다.
5. Grafana의 `OJ` 폴더에 `OJ - Bottleneck Overview` 대시보드가 있는지 본다.
6. `contest_outbox_backlog_rows`가 **5개 시계열**을 반환하는지 본다. judge 2개(PENDING,
   PUBLISHING)와 scoreboard 3개(PENDING, PROCESSING, FAILED)다. 이보다 많으면 batch-1 외의
   역할에서도 폴러가 켜진 것이고, 그러면 `sum()`이 backlog를 인스턴스 수만큼 부풀린다.
   `count by (node) (contest_outbox_backlog_rows)`가 `batch-1` 하나만 내야 한다.
7. Prometheus `Status > Rule Health`에서 규칙 그룹 4개가 모두 `OK`인지 본다.
   `oj:contest_outbox_estimated_drain:seconds`가 비어 있으면 backlog 지표가 올라오지 않은
   것이다(6번). 값이 없는 것과 0인 것은 다르다 — 빈 outbox는 0/0이라 NaN이고, 시계열 자체는
   존재한다.
8. Prometheus `Status > Runtime & Build Information`의 Alertmanagers에 `alertmanager:9093`이
   보이는지 본다. 비어 있으면 규칙은 평가되지만 발화가 아무 데도 가지 않는다.
9. 제출 파이프라인 상한이 실제 상한과 맞는지 본다. 이 지표들은 다섯 역할 전부에서 나오므로
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
- **RabbitMQ 큐별 지표.** 기본 `/metrics`는 큐를 합산한 값만 준다. 큐별로 보려면
  `/metrics/detailed` 또는 `prometheus.return_per_object_metrics` 설정이 필요하다. DLQ와
  live queue를 분리해서 보려면 이 작업이 선행돼야 한다.
- **스크레이프 주기와 부하 실행 길이.** 5s 주기에서 10초 hold 실행은 표본이 두어 개뿐이라
  곡선이 되지 않는다. 이 대시보드로 판단하려면 hold를 60초 이상으로 늘려야 한다.
- **backlog 게이지는 값이 오래됐을 수 있다.** 이 문서 §10의 폴러는 5초마다 질의하고 게이지는 그
  결과를 읽는다. 스크레이프 경로에서 DB를 건드리지 않기 위한 구조이므로, 게이지는 최대
  폴링 주기만큼 과거다. 질의가 실패하면 게이지는 **0으로 떨어지지 않고 직전 값을 유지한다.**
  0은 "backlog가 없다"는 뜻이라 실패 상황에서 정확히 반대로 읽히기 때문이다. 그래서 "backlog가
  평평하다"와 "보는 것을 멈췄다"는 게이지만으로 구분되지 않는다. 구분해 주는 것은
  `contest_outbox_backlog_poll_failures_total`이고 `ContestOutboxBacklogUnobserved` 알림이
  이 값을 본다.
- **backlog 개수는 상한에서 잘린다.** 질의는 파생 테이블의 `LIMIT`로 스캔을 묶는다
  (`contest.outbox.metrics.max-counted-rows`, 기본 100000). backlog가 상한을 넘으면 게이지는
  상한값을 보고하므로 **실제보다 작은 값**이다. 모든 알림 임계값은 상한보다 한참 아래이므로
  잘린 값이 알림을 가리지는 못한다. 측정값은 이 문서 §10에 있다.
- **judge outbox의 head lag는 PENDING만 본다.** `PUBLISHING`에서 멈춘 행은 개수에는 잡히지만
  나이에는 잡히지 않는다. relay가 lease 만료 후 그 행을 다시 claim하면서 상태를 계속
  `PUBLISHING`으로 두기 때문이다. scoreboard outbox는 `due_at` 하나로 세 상태를 모두 덮으므로
  이 구멍이 없다.
- **아직 없는 지표.** 이 문서 §9·§10으로
  `docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md` §10.1의 제출 파이프라인 항목과 §10.3의 outbox
  항목은 채워졌다. 남은 것은 같은 문서 §10.1의 async in-flight·Tomcat accept queue·중복 응답 수와
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

`ContestSubmissionBulkMetrics`는 이제 두 곳에 기록한다. `snapshot()`은 perf 엔드포인트가
읽는 기존 경로 그대로고, `bindTo()`가 Prometheus용 meter를 등록한다. 두 독자의 요구가
다르기 때문에 형태도 다르다.

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

## 10. outbox backlog 지표

`docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md` §7 표에서 상한 없이 자라는 대기 위치는 두
outbox뿐이다. 나머지는 semaphore, 고정 크기 executor 큐, broker가 각각 묶고 있다. 그래서
용량을 넘긴 실행은 결국 여기에 앉는다.

| meter | 종류 | 노출 이름 | 게시 주체 |
|---|---|---|---|
| `contest.outbox.backlog` | Gauge (`outbox`, `status`) | `contest_outbox_backlog_rows` | batch-1 |
| `contest.outbox.head.lag` | Gauge (`outbox`) | `contest_outbox_head_lag_seconds` | batch-1 |
| `contest.outbox.backlog.poll.failures` | Counter | `contest_outbox_backlog_poll_failures_total` | batch-1 |
| `contest.outbox.drained` | Counter (`outbox`) | `contest_outbox_drained_total` | relay / worker |
| `contest.outbox.retries` | Counter (`outbox`) | `contest_outbox_retries_total` | relay / worker |

### 왜 batch-1 한 곳에서만 게시하는가

테이블의 backlog는 그 테이블의 성질이지 읽는 프로세스의 성질이 아니다. 5개 인스턴스가 모두
폴링하면 같은 질의를 5번 하고 같은 시계열을 5개 만든다. 그리고 다른 모든 애플리케이션
지표에서 옳은 연산인 `sum()`이 여기서는 backlog를 5배로 보고한다. batch-1이 두 drain을 모두
소유하므로 읽는 것도 batch-1이 한다. `contest.outbox.metrics.enabled=false`가 web-role과
judge-role에 있다.

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

head lag는 뺄셈을 MySQL 안에서 한다(`TIMESTAMPDIFF(..., CURRENT_TIMESTAMP(6))`). JVM 시계와
DB 시계를 섞지 않기 위해서다. scoreboard 쪽은 worker가 claim할 때 쓰는 바로 그 `due_at`
컬럼을 읽으므로, worker가 다음에 실제로 보게 될 지연을 잰다. 아직 due하지 않은 행(backoff
중인 FAILED, lease 안에 있는 PROCESSING)은 음수로 나오고 0으로 자른다.

## 11. 알림

규칙은 `observability/prometheus/rules/oj-pipeline.yml`에 있고 Prometheus가 기동 시 문법을
검사한다. 잘못된 규칙 파일은 그 안의 알림만 조용히 꺼지는 게 아니라 Prometheus를 멈춘다.

| 알림 | 조건 | for | 등급 |
|---|---|---:|---|
| `ContestOutboxDrainTimeTooLong` | 예상 drain 시간 > 120s | 2m | warning |
| `ContestOutboxHeadOfLineStalled` | head lag > 60s | 2m | warning |
| `ContestOutboxBacklogUnobserved` | backlog 질의 실패 발생 | 5m | warning |
| `ContestSubmissionShedding` | 503 shed rate > 0 | 2m | warning |
| `ContestSubmissionCompletionSaturated` | CallerRuns rate > 0 | 5m | warning |
| `OjAppInstanceDown` | `up{job="oj-app"} == 0` | 1m | critical |
| `OjAppInstanceRestarted` | 10분 내 `process_start_time_seconds` 변화 | — | critical |

`estimated_drain_seconds = backlog_count / recent_sustainable_throughput`(파이프라인 히스토리
§9.2)는 recording
rule 3개로 들어갔다. 대시보드 패널과 알림이 같은 규칙을 읽으므로 정의가 하나다.

drain rate가 0이면 결과는 `+Inf`다. 나누기를 방어하지 않은 것은 의도다. 움직이지 않는
backlog에는 유한한 drain 시간이 없고, 그 상태가 가장 알림이 필요한 상태다. 방어하면 그래프에
구멍이 생긴다. 빈 outbox는 0/0이라 `NaN`이고 모든 비교에서 false이므로 놀고 있는 스택은
조용하다.

head lag를 따로 두는 이유는 drain rate가 볼 수 없는 절반이 있기 때문이다. 뒤쪽 backlog가
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
