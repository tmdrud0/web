# 관측 스택

`compose.yaml`의 앱 스택 위에 얹는 오버레이다. 앱 서비스의 자원 상한(합계 7.5 CPU / 9344M)은
바꾸지 않고, 관측 스택은 자체 예산을 쓴다. 모니터링을 켠 실행과 끈 실행의 앱 측 조건을 같게
유지하기 위해서다.

## 1. 실행

```powershell
docker compose -f compose.yaml -f compose.observability.yaml up -d --build
```

| 접속 | 주소 | 비고 |
|---|---|---|
| Grafana | http://localhost:3000 | admin / admin, 익명 조회 허용 |
| Prometheus | http://localhost:9090 | PromQL 직접 확인용 |

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
| mysqld-exporter | 0.10 | 64M |
| redis-exporter | 0.10 | 64M |
| nginx-exporter | 0.10 | 64M |
| **관측 합계** | **2.10** | **1984M** |
| 앱 합계 (`compose.yaml`) | 7.50 | 9344M |
| **총합** | **9.60** | **11328M** |

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
- **아직 없는 지표.** `ContestSubmissionBulkMetrics`의 in-flight/rejected/completion 계열과
  두 outbox의 상태별 count·oldest age는 아직 Micrometer에 올라가 있지 않다.
  `docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md` §10.1과 §10.5의 대부분이 여기에 해당한다.
  MySQL에 영속으로 쌓이는 두 outbox는 §7 표에서 유일하게 무한히 자라는 대기 위치이므로
  다음 작업의 우선순위가 가장 높다.
- **알림 없음.** §9.2의 `estimated_drain_seconds = backlog_count / recent_sustainable_throughput`
  는 backlog 지표가 생긴 뒤에 규칙으로 옮긴다. `prometheus.yml`에 `rule_files` 자리를 비워뒀다.

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
