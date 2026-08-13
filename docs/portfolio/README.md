# 포트폴리오

한국 서비스 기업 백엔드 신입 지원용. Markdown으로 쓰고 PDF로 출력한다.

## 구성

**제출본은 아래 두 개다.**

| 파일 | 내용 |
|---|---|
| [SUBMISSION.md](SUBMISSION.md) | **본문** — 프로필 · 대회 제출 파이프라인 · 스코어보드 전달·복구 · 남은 한계 |
| [MEASUREMENTS.md](MEASUREMENTS.md) | **근거** — 측정 환경, 런 목록, 적체·복구 그래프, 재현 절차 |

| | |
|---|---|
| `diagrams/` | SVG 11종. 설계 그림 7 + 측정 차트 4. 이 중 `insert-ignore`·`deadlock-lock-order`는 본문에서 빠져 지금은 쓰이지 않는다 |
| [`make-charts.py`](make-charts.py) | `var/loadtest-*/`의 CSV에서 측정 차트 3종을 생성 |
| [`make-rank-chart.py`](make-rank-chart.py) | 랭킹 벤치마크 차트. 원자료가 CSV로 없어 수치를 스크립트에 적어 둔다 |
| `archive/` | 이전 4분할 구성(`00~03-*.md`)과 본문 v1. 참고용이며 제출하지 않는다 |

본문은 주장, `MEASUREMENTS.md`는 그 주장의 출처다. **본문에 실린 모든 수치는 근거 문서에서
런 이름과 함께 추적된다.**

## 빌드

```bash
python docs/portfolio/make-charts.py
```

```bash
python scripts/build_portfolio.py
```

```bash
powershell -ExecutionPolicy Bypass -File scripts/export_portfolio.ps1
```

차트를 먼저 만들고 HTML을 합친 뒤 PDF로 내보낸다. 결과물은 `docs/pdf/dist/portfolio.pdf`.
빌드 스크립트가 `diagrams/*.svg`를 HTML에 인라인으로 삽입하므로 별도 이미지 변환 단계는 없다.

---

## 작성 규칙

### 1. 수치에는 조건을 함께 적는다

- **근거로 쓸 수 없는 수치는 "쓰지 않는다"고 명시한다.** 부하 발생기와 서버가 같은 호스트를
  나눠 쓰므로 절대 처리량은 용량 수치가 아니다.
- 모든 비교는 **같은 환경의 짝실험**으로 설계하고, 순서를 뒤집어 재현해 워밍업 교란을 배제한다.
- 실행 조건이 다르면 같은 변경도 다르게 보인다. `prefetch` 실험이 포화에서 +25%, 비포화에서
  +334%로 갈린 것이 그 예다.

### 2. 문제 해결 사례는 8단계로 쓴다

| 단계 | 내용 |
|---|---|
| 1 | 배경 / 만족해야 했던 요구사항 |
| 2 | 초기 구조와 그 한계 (**before 그림**) |
| 3 | 문제를 어떻게 정의했나 — 측정으로 증명 |
| 4 | 후보 대안과 트레이드오프 (**비교 표**) |
| 5 | 선택과 근거 |
| 6 | 구현 핵심 — 순서 계약, 불변식 정도만 |
| 7 | 검증 결과 (**after 그림 + 수치**) |
| 8 | 남은 한계 / 다음 스텝 |

**8단계를 빼지 않는다.** 면접에서 파고들 때 한계를 먼저 적어둔 쪽이 유리하다.

### 3. 다이어그램은 3계층 · 색 규칙 고정

| 계층 | 예 |
|---|---|
| 전체 아키텍처 | `architecture.svg` |
| 주제별 확대 | `judge-evolution.svg`, `scoreboard-paths.svg`, `backpressure.svg` |
| 구간 상세 | `insert-ignore.svg`, `recovery-timeline.svg` |
| 측정 차트 | `chart-backlog.svg`, `chart-nodekill.svg`, `chart-prefetch.svg` |

확대해도 어디인지 알아볼 수 있도록 **모든 그림이 같은 색을 쓴다.**

| 대상 | fill | stroke |
|---|---|---|
| MySQL — 원본 | `#eff6ff` | `#2563eb` |
| Redis — 파생 상태 | `#fef2f2` | `#dc2626` |
| RabbitMQ — 전달 | `#f5f3ff` | `#7c3aed` |
| Spring 애플리케이션 | `#f1f5f9` | `#64748b` |
| 비영속 · 문제 지점 | `#fff7ed` | `#ea580c` (점선) |
| 현재 구조 · 개선 | `#f0fdfa` | `#0f766e` |

`make-charts.py`도 이 표를 그대로 따르므로 측정 차트와 설계 그림이 한 벌로 읽힌다.

---

## 근거 기록

`MEASUREMENTS.md`가 참조하는 원본이다. 제출하지 않고 면접 대비용으로 쓴다.

- `docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md` — 설계 이력과 측정값 전체
- `docs/TROUBLESHOOTING.md` — 요청 처리 순서로 정리한 트러블슈팅
- `docs/ARCHITECTURE.md` — 현재 코드 구조
- `docs/ENVIRONMENT.md` — 측정 환경 기준선
- `observability/README.md` — 지표 정의와 알림 규칙
- `var/loadtest-*/`, `var/c-stage-*/` — 런별 원자료
