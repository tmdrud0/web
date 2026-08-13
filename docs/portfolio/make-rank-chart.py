"""diagrams/rank-latency.svg 를 만든다.

`make-charts.py` 는 `var/loadtest-*/` 의 CSV 에서 그리지만, 랭킹 벤치마크는 부하 런이 아니라
별도 DB(`oj_rank_bench`) 에 대한 쿼리 비교라 원자료가 CSV 로 남아 있지 않다. 그래서 수치를
이 파일에 그대로 적어 두고 출처를 함께 남긴다.

출처: docs/PORTFOLIO_RANK_QUERY_OPTIMIZATION.md · docs/velog/rank-around-me-optimization.md
하네스: src/test/java/my/oj/web/perf/RankAroundBenchmarkLoadTest.java
        (무작위 rank 1,000개로 같은 비교를 돌린다. 아래 표는 고정 3지점 1회 측정이다)

색 규칙은 docs/portfolio/README.md 를 따른다 — 문제 지점 주황, 개선 청록.
"""

from __future__ import annotations

import math
from pathlib import Path

OUT = Path(__file__).resolve().parent / "diagrams" / "rank-latency.svg"

W, H = 900, 430
L, T = 178, 62          # 그림 영역 왼쪽 위
PW, PH = 610, 300       # 그림 영역 크기

NAIVE = "#ea580c"       # OFFSET — 문제 지점
OPT = "#0f766e"         # 개선 후

# (라벨, 지점 설명, naive ms, optimized ms)
ROWS = [
    ("streak 101", "10만 행 snapshot", 161.0, 0.586),
    ("streak 49,601", "", 8_890.0, 0.443),
    ("streak 99,301", "", 14_382.0, 0.113),
    ("solved 101", "800만+ 행 user", 19.1, 24.2),
    ("solved 4,000,501", "", 217_842.0, 369.0),
    ("solved 8,000,901", "", 233_231.0, 47_747.0),
]

LO, HI = 0.05, 400_000.0        # 로그 눈금 양끝 (ms)
TICKS = [0.1, 1, 10, 100, 1_000, 10_000, 100_000]
TICK_LABEL = {
    0.1: "0.1ms", 1: "1ms", 10: "10ms", 100: "100ms",
    1_000: "1s", 10_000: "10s", 100_000: "100s",
}


def x(ms: float) -> float:
    """로그 눈금 위의 x 좌표."""
    lo, hi = math.log10(LO), math.log10(HI)
    return L + (math.log10(max(ms, LO)) - lo) / (hi - lo) * PW


def fmt(ms: float) -> str:
    """본문 표와 같은 자릿수로 적는다. 그림과 표가 다르면 둘 다 못 믿게 된다."""
    if ms >= 1_000:
        return f"{ms / 1000:,.1f}s"
    if ms >= 100:
        return f"{ms:,.0f}ms"
    if ms >= 1:
        return f"{ms:.1f}ms"
    return f"{ms:.3f}ms"


def speedup(naive: float, opt: float) -> str:
    r = naive / opt
    if r < 1:
        return f"{r:.1f}× 손해"
    if r >= 10:
        return f"{r:,.0f}× 빠름"
    return f"{r:.1f}× 빠름"


def build() -> str:
    p: list[str] = []
    p.append(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="100%" '
        f'role="img" aria-label="랭킹 조회 지연 — naive OFFSET과 개선 후 비교">'
    )
    p.append(
        "<style>"
        ".t{font-family:'Malgun Gothic','Segoe UI',sans-serif;fill:#1e293b}"
        ".ttl{font-size:15px;font-weight:700;fill:#0f172a}"
        ".sub{font-size:11.5px;fill:#64748b}"
        ".ax{font-size:10.5px;fill:#94a3b8}"
        ".lb{font-size:11.5px;font-weight:600}"
        ".val{font-size:10.5px;font-weight:700}"
        ".gr{stroke:#e2e8f0;stroke-width:1}"
        ".grp{font-size:11px;font-weight:700;fill:#475569;letter-spacing:.04em}"
        "</style>"
    )
    p.append(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')

    p.append('<text x="24" y="26" class="t ttl">랭킹 조회 지연 — 깊은 페이지일수록 갈린다</text>')
    p.append(
        '<text x="24" y="44" class="t sub">가로축 로그 눈금 · 페이지 100건 · 지점마다 1회 측정'
        ' · 값은 조회 1회에 걸린 시간</text>'
    )

    # 눈금
    for tick in TICKS:
        tx = x(tick)
        p.append(f'<line x1="{tx:.1f}" y1="{T}" x2="{tx:.1f}" y2="{T + PH}" class="gr"/>')
        p.append(
            f'<text x="{tx:.1f}" y="{T + PH + 16}" class="t ax" text-anchor="middle">'
            f"{TICK_LABEL[tick]}</text>"
        )

    band = PH / len(ROWS)
    for i, (label, note, naive, opt) in enumerate(ROWS):
        top = T + i * band
        mid = top + band / 2

        if i == 3:  # streak 그룹과 solved 그룹 경계
            p.append(
                f'<line x1="24" y1="{top:.1f}" x2="{L + PW}" y2="{top:.1f}" '
                f'stroke="#cbd5e1" stroke-width="1"/>'
            )

        p.append(
            f'<text x="{L - 12}" y="{mid - 4:.1f}" class="t lb" text-anchor="end">{label}</text>'
        )
        if note:
            p.append(
                f'<text x="{L - 12}" y="{mid + 10:.1f}" class="t ax" text-anchor="end">{note}</text>'
            )

        bh = 11.0
        for value, color, dy in ((naive, NAIVE, -bh - 1), (opt, OPT, 1)):
            bw = max(x(value) - L, 2.0)
            p.append(
                f'<rect x="{L}" y="{mid + dy:.1f}" width="{bw:.1f}" height="{bh}" '
                f'rx="2" fill="{color}" fill-opacity="0.88"/>'
            )
            p.append(
                f'<text x="{L + bw + 6:.1f}" y="{mid + dy + 9:.1f}" class="t val" '
                f'fill="{color}">{fmt(value)}</text>'
            )

        # 배수는 오른쪽 끝에 모아 둔다
        worse = opt > naive
        p.append(
            f'<text x="{W - 20}" y="{mid + 4:.1f}" class="t val" text-anchor="end" '
            f'fill="{NAIVE if worse else "#0f172a"}">{speedup(naive, opt)}</text>'
        )

    # 그룹 이름
    p.append(f'<text x="24" y="{T + band * 1.5:.1f}" class="t grp">SNAPSHOT</text>')
    p.append(f'<text x="24" y="{T + band * 4.5:.1f}" class="t grp">BUCKET</text>')

    # 범례
    ly = T + PH + 40
    p.append(f'<rect x="{L}" y="{ly - 9}" width="22" height="10" rx="2" fill="{NAIVE}"/>')
    p.append(f'<text x="{L + 30}" y="{ly}" class="t sub">naive — ORDER BY … LIMIT OFFSET</text>')
    p.append(f'<rect x="{L + 258}" y="{ly - 9}" width="22" height="10" rx="2" fill="{OPT}"/>')
    p.append(
        f'<text x="{L + 288}" y="{ly}" class="t sub">개선 후 — snapshot_rank 범위 / bucket 시작점</text>'
    )

    p.append("</svg>")
    return "\n".join(p)


if __name__ == "__main__":
    OUT.write_text(build(), encoding="utf-8")
    print(f"{OUT}  ({OUT.stat().st_size:,} bytes)")
