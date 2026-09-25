#!/usr/bin/env python3
"""Compare peak-profile runs and draw backlog duration against capacity k.

    python Compare-PeakProfileRuns.py --out <dir> [--svg docs/x.svg] <runDir> [<runDir> ...]

Reads each run's peak-summary.json (Analyze-PeakProfileRun.py) and writes <dir>/comparison.json and
<dir>/comparison.md: one row per run, then per (mode, workers) medians and ranges over the valid runs.
With --svg it draws the theory (fluid line over k, discrete medians with their seed range at integer
worker counts) and every valid measured run twice - at its nominal k (hollow) and at its effective k
(filled) - so "is the difference just effective capacity?" can be read off one picture.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import peak_queue_model as model  # noqa: E402

COLORS = {"rabbit": "#2a78d6", "mysql": "#eb6834"}
THEORY = "#52514e"


def load(run_dirs):
    runs = []
    for d in run_dirs:
        path = os.path.join(d, "peak-summary.json")
        if not os.path.exists(path):
            print(f"skip {d}: no peak-summary.json", file=sys.stderr)
            continue
        with open(path, encoding="utf-8") as fh:
            s = json.load(fh)
        s["_dir"] = d
        runs.append(s)
    return runs


def g(d, *keys):
    for k in keys:
        if d is None:
            return None
        d = d.get(k)
    return d


def row(s):
    eff = s["effective"]
    cpu = s.get("cpu") or {}
    cont = cpu.get("containers") or {}
    pr = s.get("predictions") or {}
    return {
        "run": s["runId"], "mode": s["dispatchMode"], "workers": "+".join(map(str, s["nodeWorkers"])),
        "kNominal": s["kNominal"], "valid": s["valid"], "invalid": "; ".join(s["invalidReasons"]),
        "backlog": s["backlogSeconds"], "backlogFluidKeff": g(pr, "fluidAtKEffective", "backlogSeconds"),
        "backlogFluidKeffContinuous": g(pr, "fluidAtKEffective", "continuousBacklogSeconds"),
        "backlogScaledKeff": g(pr, "scaledAtKEffective", "backlogSeconds"),
        "backlogNominalModel": g(pr, "nominal", "backlogSeconds"),
        "over10": s["over10s"], "over10ScaledKeff": g(pr, "scaledAtKEffective", "over10s"),
        "over30": s["over30s"], "maxWait": s["maxWaitSeconds"],
        "peakP50": s["peakP50LresultSeconds"], "peakP99": s["peakP99LresultSeconds"],
        "peakP99ScaledKeff": g(pr, "scaledAtKEffective", "peakP99Latency"),
        "kEff": eff.get("kEffective"), "efficiency": eff.get("efficiency"), "effSpan": eff.get("spanSeconds"),
        "cpuStackLoad": g(cpu, "stackTotal", "cpuSecondsLoad"), "cpuStackLoadDrain": g(cpu, "stackTotal", "cpuSecondsLoadAndDrain"),
        "cpuMysql": g(cont, "mysql", "cpuSecondsLoad"), "cpuBroker": g(cont, "rabbitmq", "cpuSecondsLoad"),
        "cpuJudges": (g(cont, "judge-1", "cpuSecondsLoad") or 0) + (g(cont, "judge-2", "cpuSecondsLoad") or 0),
        "cpuBatch": g(cont, "batch-1", "cpuSecondsLoad"),
        "brokerTopPeakCores": g(cont, "rabbitmq", "meanCoresTopPeak"), "brokerThrottledMs": g(cont, "rabbitmq", "throttledMsLoad"),
        "batchThrottledMs": g(cont, "batch-1", "throttledMsLoad"),
        "rowLockWaitsPerS": g(s, "db", "rowLockWaitsPerSecond"), "questionsPerS": g(s, "db", "questionsPerSecond"),
        "hostMeanPeak": g(s, "host", "meanBusyPercentPeak"), "hostSustained90": g(s, "host", "sustainedAbove90"),
        "postJudgeSaveP50": g(s, "postJudge", "judgeEndToResultSaved", "p50Ms"),
        "saveToPublishP50": g(s, "postJudge", "resultSavedToOutboxPublished", "p50Ms"),
        "judgeTimeMean": g(s, "postJudge", "judgeTime", "meanMs"),
        "flags": "; ".join(s.get("flags") or []),
        "fault": s.get("fault"),
    }


def fmt(v, nd=1):
    if v is None:
        return "-"
    if isinstance(v, bool):
        return "yes" if v else "no"
    if isinstance(v, (int,)) and not isinstance(v, bool):
        return str(v)
    if isinstance(v, float):
        return f"{v:.{nd}f}"
    return str(v)


def summarize_groups(rows):
    groups = defaultdict(list)
    for r in rows:
        if r["valid"] and not r["fault"]:
            groups[(r["mode"], r["workers"])].append(r)
    out = []
    for (mode, workers), rs in sorted(groups.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        def stat(key):
            v = [r[key] for r in rs if r[key] is not None]
            if not v:
                return None
            return {"median": float(np.median(v)), "min": float(min(v)), "max": float(max(v)), "n": len(v)}
        out.append({"mode": mode, "workers": workers, "kNominal": rs[0]["kNominal"], "n": len(rs),
                    **{k: stat(k) for k in ("backlog", "backlogFluidKeff", "backlogScaledKeff", "over10", "over30",
                                            "maxWait", "peakP50", "peakP99", "kEff", "efficiency", "cpuStackLoad",
                                            "cpuStackLoadDrain", "cpuMysql", "cpuBroker", "rowLockWaitsPerS")}})
    return out


def write_md(path, rows, groups):
    L = ["# Peak profile comparison", "", "## Runs", "",
         "| run | mode | workers | k nom | valid | backlog s (meas / fluid@keff / scaled@keff / nominal model) | >10s | >30s | max wait s | peak p50/p99 s | k_eff | eff | CPU stack core-s (load / +drain) | MySQL | broker | row-lock waits/s |",
         "|---|---|---|---:|---|---|---:|---:|---:|---|---:|---:|---|---:|---:|---:|"]
    for r in rows:
        L.append(f"| {r['run']} | {r['mode']} | {r['workers']} | {fmt(r['kNominal'],2)} | {fmt(r['valid'])} | "
                 f"{fmt(r['backlog'],0)} / {fmt(r['backlogFluidKeff'],0)} / {fmt(r['backlogScaledKeff'],0)} / {fmt(r['backlogNominalModel'],0)} | "
                 f"{r['over10']} | {r['over30']} | {fmt(r['maxWait'],1)} | {fmt(r['peakP50'],2)}/{fmt(r['peakP99'],2)} | "
                 f"{fmt(r['kEff'],2)} | {fmt(r['efficiency'],3)} | {fmt(r['cpuStackLoad'],0)} / {fmt(r['cpuStackLoadDrain'],0)} | "
                 f"{fmt(r['cpuMysql'],0)} | {fmt(r['cpuBroker'],0)} | {fmt(r['rowLockWaitsPerS'],2)} |")
    L += ["", "## Groups (valid, no fault; median [min-max])", "",
          "| mode | workers | k nom | n | backlog s | scaled@keff | >10s | peak p99 s | k_eff | eff |", "|---|---|---:|---:|---|---|---|---|---|---|"]

    def st(x, nd=1):
        if not x:
            return "-"
        return f"{x['median']:.{nd}f} [{x['min']:.{nd}f}-{x['max']:.{nd}f}]"
    for gph in groups:
        L.append(f"| {gph['mode']} | {gph['workers']} | {gph['kNominal']:.2f} | {gph['n']} | {st(gph['backlog'],0)} | "
                 f"{st(gph['backlogScaledKeff'],0)} | {st(gph['over10'],0)} | {st(gph['peakP99'],2)} | {st(gph['kEff'],2)} | {st(gph['efficiency'],3)} |")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")


def draw(svg_path, rows, base_rps, seeds):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    shape = model.Shape(base_rps)
    ks = np.round(np.arange(3.0, 12.01, 0.05), 3)
    fl = [model.fluid(float(k), shape)["backlogSeconds"] for k in ks]
    seed_list = list(range(20260925, 20260925 + seeds))
    disc = [model.discrete(c, shape, seed_list) for c in range(3, 9)]

    plt.rcParams.update({"font.size": 10, "font.family": "sans-serif", "axes.edgecolor": "#b5b4ad",
                         "axes.labelcolor": "#0b0b0b", "xtick.color": "#52514e", "ytick.color": "#52514e"})
    fig, ax = plt.subplots(figsize=(8.2, 4.8), dpi=100)
    fig.patch.set_facecolor("#fcfcfb")
    ax.set_facecolor("#fcfcfb")
    ax.grid(True, color="#e6e5df", linewidth=0.8)
    ax.set_axisbelow(True)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.plot(ks, fl, color=THEORY, linewidth=2, label="model: fluid (any k)")
    dk = [d["k"] for d in disc]
    ax.errorbar(dk, [d["backlogSeconds"] for d in disc],
                yerr=[[d["backlogSeconds"] - d["backlogSecondsMin"] for d in disc],
                      [d["backlogSecondsMax"] - d["backlogSeconds"] for d in disc]],
                fmt="s", color=THEORY, markerfacecolor="#fcfcfb", markersize=8, capsize=3, linewidth=1.2,
                label=f"model: discrete FIFO, median and range of {seeds} seeds")
    for mode, label in (("rabbit", "RabbitMQ"), ("mysql", "MySQL claim")):
        rs = [r for r in rows if r["mode"] == mode and r["valid"] and not r["fault"]]
        if not rs:
            continue
        c = COLORS[mode]
        ax.scatter([r["kNominal"] for r in rs], [r["backlog"] for r in rs], s=70, facecolors="none", edgecolors=c,
                   linewidths=2, zorder=3, label=f"{label}: measured at nominal k")
        kr = [r for r in rs if r["kEff"] is not None]
        ax.scatter([r["kEff"] for r in kr], [r["backlog"] for r in kr], s=70, color=c, edgecolors="#fcfcfb",
                   linewidths=2, zorder=4, label=f"{label}: measured at effective k")
        for r in kr:
            ax.plot([r["kNominal"], r["kEff"]], [r["backlog"], r["backlog"]], color=c, linewidth=1, alpha=0.5, zorder=2)
    ax.set_xlabel("capacity k = service rate / baseline arrival rate B")
    ax.set_ylabel("backlog duration (s)")
    ax.set_title(f"Backlog duration vs capacity, peak profile at B={base_rps:g}", loc="left", color="#0b0b0b", fontsize=11)
    ax.set_xlim(3, 12)
    ax.set_ylim(bottom=0)
    ax.legend(frameon=False, fontsize=8.5, loc="upper right")
    fig.tight_layout()
    fig.savefig(svg_path, format="svg", facecolor=fig.get_facecolor())
    plt.close(fig)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dirs", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--svg", default=None)
    ap.add_argument("--seeds", type=int, default=15)
    a = ap.parse_args(argv)
    runs = load(a.run_dirs)
    rows = [row(s) for s in runs]
    groups = summarize_groups(rows)
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "comparison.json"), "w", encoding="utf-8") as fh:
        json.dump({"runs": rows, "groups": groups}, fh, indent=1, default=float)
    write_md(os.path.join(a.out, "comparison.md"), rows, groups)
    if a.svg:
        draw(a.svg, rows, runs[0]["baseRps"] if runs else 5.0, a.seeds)
    print(open(os.path.join(a.out, "comparison.md"), encoding="utf-8").read())
    return 0


if __name__ == "__main__":
    sys.exit(main())
