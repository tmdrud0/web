"""var/loadtest-*/ 의 CSV에서 보조자료용 SVG 차트를 만든다.

포트폴리오 본문 그림과 같은 색 규칙을 쓴다(docs/portfolio/README.md).
  MySQL #2563eb / Redis #dc2626 / RabbitMQ #7c3aed / 애플리케이션 #64748b / 문제 지점 #ea580c

실행:  python docs/portfolio/make-charts.py
출력:  docs/portfolio/diagrams/chart-*.svg
"""
import csv
import datetime
import os

VAR = os.path.join(os.path.dirname(__file__), "..", "..", "var")
OUT = os.path.join(os.path.dirname(__file__), "diagrams")

MYSQL, REDIS, RABBIT, APP, WARN = "#2563eb", "#dc2626", "#7c3aed", "#64748b", "#ea580c"

W, H = 760, 340
L, R, T, B = 74, 152, 46, 48          # 좌/우/상/하 여백 (우측은 범례 자리)
PW, PH = W - L - R, H - T - B


def load(run):
    path = os.path.join(VAR, run, "pipeline.csv")
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig")))
    t0 = datetime.datetime.fromisoformat(rows[0]["timestamp"])
    for r in rows:
        r["_t"] = (datetime.datetime.fromisoformat(r["timestamp"]) - t0).total_seconds()
    return rows


def load_rabbit(run, queue="live"):
    path = os.path.join(VAR, run, "rabbitmq-metrics.csv")
    rows = [r for r in csv.DictReader(open(path, encoding="utf-8-sig")) if queue in r["queue"]]
    t0 = datetime.datetime.fromisoformat(rows[0]["timestamp"])
    for r in rows:
        r["_t"] = (datetime.datetime.fromisoformat(r["timestamp"]) - t0).total_seconds()
    return rows


def nice(v):
    """축 최대값을 읽기 좋은 수로 올린다."""
    if v <= 0:
        return 1
    import math
    e = 10 ** math.floor(math.log10(v))
    for m in (1, 2, 2.5, 5, 10):
        if v <= m * e:
            return int(m * e)
    return int(10 * e)


def fmt(v):
    return f"{v:,.0f}" if v >= 1000 else f"{v:g}"


class Chart:
    def __init__(self, title, subtitle, xmax, ymax, ylabel):
        self.p = []
        self.xmax, self.ymax = xmax, ymax
        self.p.append(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" '
            f'width="{W}" height="{H}" font-family="system-ui,-apple-system,Segoe UI,sans-serif">'
        )
        self.p.append(
            "<style>"
            ".t{font-size:12px;fill:#334155}.ttl{font-size:15px;font-weight:700;fill:#0f172a}"
            ".sub{font-size:11.5px;fill:#64748b}.ax{font-size:11px;fill:#64748b}"
            ".gr{stroke:#e2e8f0;stroke-width:1}.mk{font-size:11px;font-weight:600}"
            "</style>"
        )
        self.p.append(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
        self.p.append(f'<text x="{L}" y="22" class="ttl">{title}</text>')
        self.p.append(f'<text x="{L}" y="38" class="sub">{subtitle}</text>')
        self.p.append(f'<text x="16" y="{T - 8}" class="ax">{ylabel}</text>')
        self._axes()
        self.legend = []

    def X(self, t):
        return L + PW * t / self.xmax

    def Y(self, v):
        return T + PH - PH * min(v, self.ymax) / self.ymax

    def _axes(self):
        for i in range(5):
            v = self.ymax * i / 4
            y = self.Y(v)
            self.p.append(f'<line x1="{L}" y1="{y:.1f}" x2="{L + PW}" y2="{y:.1f}" class="gr"/>')
            self.p.append(f'<text x="{L - 8}" y="{y + 4:.1f}" class="ax" text-anchor="end">{fmt(v)}</text>')
        for i in range(6):
            t = self.xmax * i / 5
            x = self.X(t)
            self.p.append(f'<text x="{x:.1f}" y="{T + PH + 18}" class="ax" text-anchor="middle">{t:.0f}s</text>')
        self.p.append(f'<line x1="{L}" y1="{T + PH}" x2="{L + PW}" y2="{T + PH}" stroke="#94a3b8"/>')
        self.p.append(f'<text x="{L + PW / 2}" y="{H - 12}" class="ax" text-anchor="middle">부하 시작 이후 경과 시간</text>')

    def series(self, pts, color, label, dash=None, width=2):
        d = " ".join(f"{'M' if i == 0 else 'L'}{self.X(t):.1f},{self.Y(v):.1f}" for i, (t, v) in enumerate(pts))
        da = f' stroke-dasharray="{dash}"' if dash else ""
        self.p.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}"{da} stroke-linejoin="round"/>')
        self.legend.append((color, label, dash))

    def vline(self, t, text, color=WARN, up=0):
        x = self.X(t)
        self.p.append(f'<line x1="{x:.1f}" y1="{T}" x2="{x:.1f}" y2="{T + PH}" stroke="{color}" stroke-width="1.4" stroke-dasharray="4 3"/>')
        self.p.append(f'<text x="{x + 5:.1f}" y="{T + 14 + up}" class="mk" fill="{color}">{text}</text>')

    def point(self, t, v, text, color):
        x, y = self.X(t), self.Y(v)
        self.p.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{color}"/>')
        anchor = "end" if x > L + PW * 0.6 else "start"
        dx = -8 if anchor == "end" else 8
        self.p.append(f'<text x="{x + dx:.1f}" y="{y - 9:.1f}" class="mk" fill="{color}" text-anchor="{anchor}">{text}</text>')

    def save(self, name):
        y = T + 6
        for color, label, dash in self.legend:
            da = f' stroke-dasharray="{dash}"' if dash else ""
            self.p.append(f'<line x1="{L + PW + 14}" y1="{y}" x2="{L + PW + 38}" y2="{y}" stroke="{color}" stroke-width="2.5"{da}/>')
            self.p.append(f'<text x="{L + PW + 44}" y="{y + 4}" class="t">{label}</text>')
            y += 20
        self.p.append("</svg>")
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, name)
        open(path, "w", encoding="utf-8").write("\n".join(self.p))
        print("wrote", os.path.relpath(path))


# ── 차트 1 : 단계별 적체와 해소 (submit-1000) ────────────────────────────────
def chart_backlog():
    rows = load("loadtest-20260808-104446")
    tmax = rows[-1]["_t"]
    ready = [(r["_t"], int(r["rabbitReady"])) for r in rows]
    outbox = [(r["_t"], int(r["judgeOutboxPending"])) for r in rows]
    sb = [(r["_t"], int(r["scoreboardPending"])) for r in rows]
    peak = max(ready, key=lambda p: p[1])

    c = Chart(
        "단계별 적체와 해소 — 제출 1,000/s를 150초",
        "var/loadtest-20260808-104446/pipeline.csv · 접수 132,510건 · 중복 제외 127,687건 채점 · 유실 0",
        tmax, nice(peak[1]), "건",
    )
    c.series(ready, RABBIT, "RabbitMQ ready")
    c.series(outbox, MYSQL, "judge outbox (MySQL)")
    c.series(sb, REDIS, "scoreboard 반영 대기", dash="5 3")
    c.vline(150, "부하 종료")
    c.point(peak[0], peak[1], f"피크 {peak[1]:,}", RABBIT)
    c.point(max(outbox, key=lambda p: p[1])[0], max(outbox, key=lambda p: p[1])[1],
            f"{max(o[1] for o in outbox):,}", MYSQL)
    c.save("chart-backlog.svg")


# ── 차트 2 : 노드 사망과 회수 (짝실험) ──────────────────────────────────────
def chart_nodekill():
    kill = load("loadtest-20260808-115115")     # judge-1 SIGKILL
    base = load("loadtest-20260808-114654")     # 무주입 짝
    tmax = max(kill[-1]["_t"], base[-1]["_t"])
    ready_k = [(r["_t"], int(r["rabbitReady"])) for r in kill]
    ready_b = [(r["_t"], int(r["rabbitReady"])) for r in base]
    unack = [(r["_t"], int(r["rabbitUnacked"])) for r in kill]
    peak = max(ready_k, key=lambda p: p[1])
    ymax = nice(peak[1])

    c = Chart(
        "judge 노드 하나를 SIGKILL — 회수와 복구",
        "var/loadtest-20260808-115115(주입) vs -114654(무주입) · 도착 100/s · prefetch=1이라 unacked 수 = 살아 있는 consumer 수",
        tmax, ymax, "건",
    )
    # unacked(0–32)를 같은 축에 스케일해 겹친다. 32→16→32가 노드 생사 그 자체다.
    c.series([(t, v / 32 * ymax) for t, v in unack], APP, "unacked (0–32 스케일)", dash="6 3", width=1.8)
    c.series(ready_b, "#0f766e", "ready — 무주입 짝", dash="3 3", width=1.6)
    c.series(ready_k, RABBIT, "ready — SIGKILL")
    # unacked == 16 이 가장 길게 이어진 구간이 노드가 죽어 있던 시간이다.
    # 런 후반에도 순간적으로 16 이하가 되므로 최장 연속 구간만 잡는다.
    best = cur = None
    for i, (t_, v) in enumerate(unack):
        if v == 16:
            cur = (cur or i)
        else:
            if cur is not None and (best is None or i - cur > best[1] - best[0]):
                best = (cur, i)
            cur = None
    if best:
        s, e = unack[best[0]][0], unack[best[1]][0]
        c.p.insert(4, f'<rect x="{c.X(s):.1f}" y="{T}" width="{c.X(e) - c.X(s):.1f}" '
                      f'height="{PH}" fill="{WARN}" opacity="0.07"/>')
        c.vline(s, "SIGKILL · unacked 32→16", up=0)
        c.vline(e, f"{e - s:.0f}초 뒤 32 복귀", color="#0f766e", up=18)
    c.point(peak[0], peak[1], f"피크 {peak[1]:,}", RABBIT)
    c.save("chart-nodekill.svg")


# ── 차트 3 : prefetch 별 consumer 로컬 버퍼 ──────────────────────────────────
def chart_prefetch():
    runs = [("loadtest-20260808-092647", "prefetch=1", "#0f766e", None),
            ("loadtest-20260808-094634", "prefetch=4", RABBIT, None),
            ("loadtest-20260808-093145", "prefetch=64", WARN, None)]
    data, tmax, ymax = [], 0, 0
    for run, label, color, dash in runs:
        rows = load(run)
        pts = [(r["_t"], int(r["rabbitUnacked"])) for r in rows]
        data.append((pts, color, label, dash))
        tmax = max(tmax, rows[-1]["_t"])
        ymax = max(ymax, max(p[1] for p in pts))

    c = Chart(
        "prefetch 별 consumer 로컬 버퍼 (unacked)",
        "도착·서비스 분포·consumer 수 고정, 디스패치 정책만 변경 · 상한은 32 / 128 / 2,048",
        tmax, nice(ymax), "unacked (건)",
    )
    for pts, color, label, dash in data:
        c.series(pts, color, label, dash=dash)
    c.save("chart-prefetch.svg")


if __name__ == "__main__":
    chart_backlog()
    chart_nodekill()
    chart_prefetch()
