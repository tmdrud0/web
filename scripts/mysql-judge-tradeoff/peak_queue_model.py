#!/usr/bin/env python3
"""Theoretical queue model for the contest peak-load profile.

The question it answers: given a judge cluster with a *capacity* k (worker-based capacity divided by the
baseline arrival rate B), how long does a backlog last when the peak profile below is offered, and how
many submissions wait long? The measured runs are compared against this model, so the model shares the
analyzer's definitions exactly (see ``summarize``).

Load profile (Poisson arrivals, rate piecewise constant), total 360s:

    0-90s  1B | 90-150s  5B | 150-180s  10B | 180-240s  5B | 240-360s  1B

Judge time: 95% 50ms, 5% 2000ms (mean 147.5ms), drawn independently per submission.

Three model families:

* ``discrete`` - an event-exact multi-server FIFO queue: c identical servers share one queue, each
  arrival (in arrival order) goes to the server that frees first (min-heap). Nominal k = c / 0.1475 / B.
  Reported as the median over several seeds.
* ``scaled`` - the same discrete queue with every judge time multiplied by (k_nominal / k_effective), so
  an *effective* capacity that is not an integer number of servers can still be simulated with the real
  arrival and service variability. This is how a measured effective k is turned into a prediction with
  percentiles.
* ``fluid`` - a deterministic fluid approximation, dQ/dt = lambda(t) - mu while Q > 0, mu = k B, wait =
  Q / mu, integrated at 0.01s. It has no service variability, so it under-states the stochastic queueing
  near rho ~ 1, but it takes any real k.

A time-varying capacity mode (``fault``) is event-driven: servers belong to nodes, a node can go down at
one instant and come back at another, and the work a dead node was executing is re-queued at the head of
the queue after a redelivery delay (0 for a broker that requeues on connection loss, about the claim
lease for a MySQL claim). This is the model the peak-fault run is compared against.

Backlog duration (primary metric, identical to the analyzer): arrivals are grouped into 10s bins by
arrival time; a bin is a backlog bin when the mean queue wait of its arrivals exceeds 1s; the metric is the
longest run of consecutive backlog bins among bins that start at or after 90s (the end of the first
baseline). The fluid model also reports the *continuous* duration during which the instantaneous wait
exceeds 1s, which is the definition the reference numbers in the task brief were computed with.

Everything is standard library + numpy.
"""

from __future__ import annotations

import argparse
import heapq
import json
import math
import sys
from dataclasses import dataclass, field
from typing import Iterable, Sequence

import numpy as np

FAST_MS = 50.0
SLOW_MS = 2000.0
SLOW_RATIO = 0.05
MEAN_SERVICE_S = (1 - SLOW_RATIO) * FAST_MS / 1000.0 + SLOW_RATIO * SLOW_MS / 1000.0  # 0.1475

DEFAULT_SEGMENTS = ((90.0, 1.0), (60.0, 5.0), (30.0, 10.0), (60.0, 5.0), (120.0, 1.0))
BIN_SECONDS = 10.0
BACKLOG_WAIT_THRESHOLD_S = 1.0
BASELINE_HEAD_SECONDS = 90.0


# ----------------------------------------------------------------------------------------------------
# Shape and arrivals
# ----------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Shape:
    base_rps: float
    segments: tuple = DEFAULT_SEGMENTS  # (seconds, multiplier)

    @property
    def total_seconds(self) -> float:
        return sum(s for s, _ in self.segments)

    def boundaries(self) -> list:
        """[(start, end, rate)] in seconds from the schedule anchor."""
        out, t = [], 0.0
        for seconds, mult in self.segments:
            out.append((t, t + seconds, mult * self.base_rps))
            t += seconds
        return out

    def rate_at(self, t: float) -> float:
        for start, end, rate in self.boundaries():
            if start <= t < end:
                return rate
        return 0.0

    def peak_window(self) -> tuple:
        """First segment whose multiplier is above 1 to the last such segment's end (the 5B..5B span)."""
        b = self.boundaries()
        high = [i for i, (_, m) in enumerate(self.segments) if m > 1.0]
        if not high:
            return (0.0, 0.0)
        return (b[high[0]][0], b[high[-1]][1])

    def expected_arrivals(self) -> float:
        return sum(s * m * self.base_rps for s, m in self.segments)


def parse_segments(text: str) -> tuple:
    """"90:1,60:5,30:10,60:5,120:1" -> ((90,1),(60,5),...)."""
    segs = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        seconds, mult = part.split(":")
        segs.append((float(seconds), float(mult)))
    if not segs:
        raise ValueError("no segments")
    return tuple(segs)


def poisson_arrivals(shape: Shape, rng: np.random.Generator) -> np.ndarray:
    times = []
    for start, end, rate in shape.boundaries():
        if rate <= 0:
            continue
        n = rng.poisson(rate * (end - start))
        # Given the count, Poisson arrival instants in an interval are iid uniform.
        times.append(np.sort(rng.uniform(start, end, size=n)))
    return np.concatenate(times) if times else np.zeros(0)


def service_times(n: int, rng: np.random.Generator, scale: float = 1.0) -> np.ndarray:
    slow = rng.random(n) < SLOW_RATIO
    return np.where(slow, SLOW_MS, FAST_MS) / 1000.0 * scale


def nominal_k(servers: float, base_rps: float) -> float:
    return servers / MEAN_SERVICE_S / base_rps


def servers_for_k(k: float, base_rps: float) -> float:
    return k * MEAN_SERVICE_S * base_rps


# ----------------------------------------------------------------------------------------------------
# Queue simulations
# ----------------------------------------------------------------------------------------------------

def simulate_fifo(arrivals: np.ndarray, services: np.ndarray, servers: int) -> np.ndarray:
    """Start instants for a c-server FIFO queue: each arrival to the server that frees first."""
    if servers < 1:
        raise ValueError("servers must be >= 1")
    free = [0.0] * servers
    heapq.heapify(free)
    starts = np.empty_like(arrivals)
    for i, (a, s) in enumerate(zip(arrivals, services)):
        earliest = heapq.heappop(free)
        start = a if a > earliest else earliest
        starts[i] = start
        heapq.heappush(free, start + s)
    return starts


@dataclass
class NodeOutage:
    node: int
    down_at: float
    up_at: float  # the instant the node takes work again (restart + readiness)
    redelivery_delay: float = 0.0  # when the dead node's in-flight work is back in the queue


def simulate_with_outages(arrivals: np.ndarray, services: np.ndarray, node_servers: Sequence[int],
                          outages: Sequence[NodeOutage]):
    """Event-driven FIFO queue whose servers belong to nodes that can go down and come back.

    Work a dead node was executing is re-queued at the head of the queue after the outage's
    redelivery delay and started from scratch (its full judge time again). Returns
    (final_start, end, redelivered_flag) per arrival.
    """
    n = len(arrivals)
    final_start = np.full(n, np.nan)
    end = np.full(n, np.nan)
    redelivered = np.zeros(n, dtype=bool)

    servers = []  # [node, alive, busy_job, generation]
    for node, count in enumerate(node_servers):
        for _ in range(count):
            servers.append([node, True, -1, 0])

    # Event queue: (time, priority, seq, kind, payload). Lower priority first at equal time:
    # outage transitions, then completions, then requeues, then arrivals.
    events = []
    seq = 0

    def push(t, prio, kind, payload):
        nonlocal seq
        heapq.heappush(events, (t, prio, seq, kind, payload))
        seq += 1

    for i, a in enumerate(arrivals):
        push(float(a), 3, "arrival", i)
    for o in outages:
        push(o.down_at, 0, "down", o)
        push(o.up_at, 0, "up", o)

    from collections import deque
    queue = deque()

    def dispatch(now):
        for idx, srv in enumerate(servers):
            if not queue:
                return
            if srv[1] and srv[2] < 0:
                job = queue.popleft()
                srv[2] = job
                srv[3] += 1
                final_start[job] = now
                push(now + float(services[job]), 1, "done", (idx, srv[3]))

    while events:
        now, _, _, kind, payload = heapq.heappop(events)
        if kind == "arrival":
            queue.append(payload)
        elif kind == "requeue":
            queue.appendleft(payload)
        elif kind == "done":
            idx, gen = payload
            srv = servers[idx]
            if srv[3] != gen or srv[2] < 0 or not srv[1]:
                continue  # completion of work that died with its node
            end[srv[2]] = now
            srv[2] = -1
        elif kind == "down":
            lost = []
            for srv in servers:
                if srv[0] == payload.node:
                    srv[1] = False
                    if srv[2] >= 0:
                        lost.append(srv[2])
                        srv[2] = -1
                    srv[3] += 1
            # Re-queue the oldest first so they come back in arrival order at the head.
            for job in sorted(lost, reverse=True):
                redelivered[job] = True
                push(now + payload.redelivery_delay, 2, "requeue", job)
        elif kind == "up":
            for srv in servers:
                if srv[0] == payload.node:
                    srv[1] = True
        dispatch(now)
    return final_start, end, redelivered


# ----------------------------------------------------------------------------------------------------
# Metrics - shared definitions with the analyzer
# ----------------------------------------------------------------------------------------------------

def longest_backlog_run(bin_starts: Sequence[float], bin_mean_waits: Sequence[float],
                        head_seconds: float = BASELINE_HEAD_SECONDS,
                        threshold: float = BACKLOG_WAIT_THRESHOLD_S,
                        bin_seconds: float = BIN_SECONDS) -> dict:
    """Longest run of consecutive bins (starting at or after head_seconds) with mean wait > threshold.

    A bin with no arrivals (mean wait None/NaN) breaks a run: there was nobody to wait.
    Returns the duration and the first/last backlog bin start of that run.
    """
    best = (0, None, None)
    run, run_start = 0, None
    prev_start = None
    for start, wait in zip(bin_starts, bin_mean_waits):
        if start < head_seconds - 1e-9:
            continue
        is_backlog = wait is not None and not (isinstance(wait, float) and math.isnan(wait)) and wait > threshold
        contiguous = prev_start is not None and abs(start - prev_start - bin_seconds) < 1e-6
        if is_backlog:
            if run > 0 and contiguous:
                run += 1
            else:
                run, run_start = 1, start
            if run > best[0]:
                best = (run, run_start, start)
        else:
            run = 0
        prev_start = start
    return {
        "backlogSeconds": best[0] * bin_seconds,
        "firstBacklogBinStart": best[1],
        "lastBacklogBinStart": best[2],
    }


def bin_series(arrival_s: np.ndarray, wait_s: np.ndarray, latency_s: np.ndarray, total_seconds: float,
               bin_seconds: float = BIN_SECONDS) -> list:
    rows = []
    nbins = int(math.ceil(total_seconds / bin_seconds))
    idx = np.floor(arrival_s / bin_seconds).astype(int)
    for b in range(nbins):
        mask = idx == b
        cnt = int(mask.sum())
        if cnt:
            w = wait_s[mask]
            lat = latency_s[mask]
            rows.append({"binStart": b * bin_seconds, "count": cnt,
                         "meanWait": float(w.mean()), "p50Wait": float(np.percentile(w, 50)),
                         "p99Wait": float(np.percentile(w, 99)),
                         "p50Latency": float(np.percentile(lat, 50)), "p99Latency": float(np.percentile(lat, 99))})
        else:
            rows.append({"binStart": b * bin_seconds, "count": 0, "meanWait": None, "p50Wait": None,
                         "p99Wait": None, "p50Latency": None, "p99Latency": None})
    return rows


def summarize(arrival_s: np.ndarray, start_s: np.ndarray, end_s: np.ndarray, shape: Shape) -> dict:
    wait = start_s - arrival_s
    latency = end_s - arrival_s
    series = bin_series(arrival_s, wait, latency, shape.total_seconds)
    backlog = longest_backlog_run([r["binStart"] for r in series], [r["meanWait"] for r in series])
    lo, hi = shape.peak_window()
    peak = (arrival_s >= lo) & (arrival_s < hi)
    out = {
        "arrivals": int(len(arrival_s)),
        **backlog,
        "over10s": int((latency > 10.0).sum()),
        "over30s": int((latency > 30.0).sum()),
        "maxWait": float(wait.max()) if len(wait) else 0.0,
        "peakP50Latency": float(np.percentile(latency[peak], 50)) if peak.any() else None,
        "peakP99Latency": float(np.percentile(latency[peak], 99)) if peak.any() else None,
        "series": series,
    }
    return out


def _median_of(runs: list, key: str):
    vals = [r[key] for r in runs if r[key] is not None]
    return float(np.median(vals)) if vals else None


def aggregate(runs: list) -> dict:
    keys = ["backlogSeconds", "over10s", "over30s", "maxWait", "peakP50Latency", "peakP99Latency", "arrivals"]
    agg = {k: _median_of(runs, k) for k in keys}
    for k in ["backlogSeconds", "over10s", "maxWait", "peakP99Latency"]:
        vals = [r[k] for r in runs if r[k] is not None]
        agg[k + "Min"] = float(min(vals)) if vals else None
        agg[k + "Max"] = float(max(vals)) if vals else None
    agg["seeds"] = len(runs)
    return agg


# ----------------------------------------------------------------------------------------------------
# Model families
# ----------------------------------------------------------------------------------------------------

def discrete(servers: int, shape: Shape, seeds: Iterable[int], service_scale: float = 1.0) -> dict:
    runs = []
    for seed in seeds:
        rng = np.random.default_rng(seed)
        arr = poisson_arrivals(shape, rng)
        svc = service_times(len(arr), rng, service_scale)
        start = simulate_fifo(arr, svc, servers)
        runs.append(summarize(arr, start, start + svc, shape))
    agg = aggregate(runs)
    agg["model"] = "discrete" if service_scale == 1.0 else "scaled"
    agg["servers"] = servers
    agg["serviceScale"] = service_scale
    agg["k"] = nominal_k(servers, shape.base_rps) / service_scale
    agg["seriesMedian"] = median_series(runs)
    return agg


def median_series(runs: list) -> list:
    out = []
    for i, row in enumerate(runs[0]["series"]):
        vals = {}
        for key in ("meanWait", "p50Latency", "p99Latency"):
            xs = [r["series"][i][key] for r in runs if r["series"][i][key] is not None]
            vals[key] = float(np.median(xs)) if xs else None
        out.append({"binStart": row["binStart"], **vals})
    return out


def scaled(k_effective: float, shape: Shape, seeds: Iterable[int], servers: int | None = None) -> dict:
    """Discrete queue at an effective capacity: integer servers, judge times scaled to hit k_effective."""
    if servers is None:
        servers = max(1, int(round(servers_for_k(k_effective, shape.base_rps))))
    scale = nominal_k(servers, shape.base_rps) / k_effective
    return discrete(servers, shape, seeds, service_scale=scale)


def fluid(k: float, shape: Shape, dt: float = 0.01, mean_service: float = MEAN_SERVICE_S,
          capacity_fn=None) -> dict:
    """Deterministic fluid queue. capacity_fn(t) -> k at t overrides the constant k (fault mode)."""
    total = shape.total_seconds
    steps = int(round(total / dt))
    t = np.arange(steps) * dt
    lam = np.array([shape.rate_at(x) for x in t])
    mu = np.full(steps, k * shape.base_rps) if capacity_fn is None else np.array([capacity_fn(x) * shape.base_rps for x in t])
    q = np.zeros(steps)
    level = 0.0
    for i in range(steps):
        q[i] = level
        level = max(0.0, level + (lam[i] - mu[i]) * dt)
    # A fluid arrival at t waits until the fluid ahead of it drains at the (possibly varying) rate.
    if capacity_fn is None:
        wait = np.where(mu > 0, q / mu, np.inf)
    else:
        cum = np.concatenate([[0.0], np.cumsum(mu * dt)])
        wait = np.empty(steps)
        for i in range(steps):
            target = cum[i] + q[i]
            j = np.searchsorted(cum, target)
            wait[i] = (j - i) * dt if j < len(cum) else np.inf
    weights = lam * dt  # arrivals in each step
    nbins = int(math.ceil(total / BIN_SECONDS))
    bin_idx = np.floor(t / BIN_SECONDS).astype(int)
    starts, means = [], []
    for b in range(nbins):
        m = bin_idx == b
        w = weights[m]
        starts.append(b * BIN_SECONDS)
        means.append(float((wait[m] * w).sum() / w.sum()) if w.sum() > 0 else None)
    backlog = longest_backlog_run(starts, means)
    above = wait > BACKLOG_WAIT_THRESHOLD_S
    # Continuous duration after the first baseline: the longest stretch with instantaneous wait > 1s.
    cont, best = 0.0, 0.0
    for i in range(steps):
        if t[i] >= BASELINE_HEAD_SECONDS and above[i]:
            cont += dt
            best = max(best, cont)
        else:
            cont = 0.0
    lo, hi = shape.peak_window()
    peak = (t >= lo) & (t < hi)
    latency = wait + mean_service

    def weighted_pct(values, wts, p):
        order = np.argsort(values)
        cw = np.cumsum(wts[order])
        if cw[-1] <= 0:
            return None
        return float(values[order][np.searchsorted(cw, p / 100.0 * cw[-1])])

    return {
        "model": "fluid",
        "k": k,
        **backlog,
        "continuousBacklogSeconds": round(best, 2),
        "over10s": float(weights[wait + mean_service > 10.0].sum()),
        "over30s": float(weights[wait + mean_service > 30.0].sum()),
        "maxWait": float(wait.max()),
        "peakP50Latency": weighted_pct(latency[peak], weights[peak], 50),
        "peakP99Latency": weighted_pct(latency[peak], weights[peak], 99),
        "note": "fluid latency = fluid wait + mean judge time; it has no judge-time variability",
        "seriesMedian": [{"binStart": s, "meanWait": m} for s, m in zip(starts, means)],
    }


def fault(node_servers: Sequence[int], shape: Shape, seeds: Iterable[int], down_node: int, down_at: float,
          up_at: float, redelivery_delay: float, service_scale: float = 1.0) -> dict:
    runs, redelivered_counts = [], []
    outage = NodeOutage(node=down_node, down_at=down_at, up_at=up_at, redelivery_delay=redelivery_delay)
    for seed in seeds:
        rng = np.random.default_rng(seed)
        arr = poisson_arrivals(shape, rng)
        svc = service_times(len(arr), rng, service_scale)
        start, end, redelivered = simulate_with_outages(arr, svc, node_servers, [outage])
        s = summarize(arr, start, end, shape)
        s["redelivered"] = int(redelivered.sum())
        runs.append(s)
    agg = aggregate(runs)
    agg["redeliveredMedian"] = _median_of(runs, "redelivered")
    agg.update({"model": "fault", "nodeServers": list(node_servers), "downNode": down_node,
                "downAt": down_at, "upAt": up_at, "redeliveryDelay": redelivery_delay,
                "serviceScale": service_scale,
                "kBefore": nominal_k(sum(node_servers), shape.base_rps) / service_scale,
                "kDuring": nominal_k(sum(node_servers) - node_servers[down_node], shape.base_rps) / service_scale})
    agg["seriesMedian"] = median_series(runs)
    return agg


# ----------------------------------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------------------------------

def _strip_series(d: dict) -> dict:
    return {k: v for k, v in d.items() if k not in ("seriesMedian", "series")}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-rps", type=float, default=5.0)
    ap.add_argument("--segments", default="90:1,60:5,30:10,60:5,120:1")
    ap.add_argument("--seeds", type=int, default=15)
    ap.add_argument("--seed-base", type=int, default=20260925)
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("curve", help="backlog duration vs k: discrete at integer servers, fluid on a grid")
    c.add_argument("--servers", default="3,4,5,6,7,8")
    c.add_argument("--k-grid", default="3:12:0.05", help="start:stop:step for the fluid curve")
    c.add_argument("--out", required=True, help="output JSON")

    p = sub.add_parser("predict", help="predictions at an effective k (fluid + scaled discrete)")
    p.add_argument("--k", type=float, required=True)
    p.add_argument("--servers", type=int, default=None, help="integer servers for the scaled discrete model")
    p.add_argument("--out", default=None)

    f = sub.add_parser("fault", help="one node down for a while, discrete event model")
    f.add_argument("--node-servers", default="3,3")
    f.add_argument("--down-node", type=int, default=0)
    f.add_argument("--down-at", type=float, default=120.0)
    f.add_argument("--up-at", type=float, required=True, help="instant the node takes work again")
    f.add_argument("--redelivery-delay", type=float, default=0.0)
    f.add_argument("--service-scale", type=float, default=1.0)
    f.add_argument("--out", default=None)

    args = ap.parse_args(argv)
    shape = Shape(args.base_rps, parse_segments(args.segments))
    seeds = [args.seed_base + i for i in range(args.seeds)]

    if args.cmd == "curve":
        servers = [int(x) for x in args.servers.split(",") if x.strip()]
        start, stop, step = (float(x) for x in args.k_grid.split(":"))
        grid = np.round(np.arange(start, stop + 1e-9, step), 4)
        doc = {
            "baseRps": args.base_rps, "segments": shape.segments, "seeds": seeds,
            "meanServiceSeconds": MEAN_SERVICE_S,
            "discrete": [discrete(s, shape, seeds) for s in servers],
            "fluid": [_strip_series(fluid(float(k), shape)) for k in grid],
        }
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, indent=1)
        for d in doc["discrete"]:
            print(f"discrete c={d['servers']} k={d['k']:.2f}: backlog {d['backlogSeconds']:.0f}s "
                  f"[{d['backlogSecondsMin']:.0f}-{d['backlogSecondsMax']:.0f}], >10s {d['over10s']:.0f}, "
                  f">30s {d['over30s']:.0f}, maxWait {d['maxWait']:.1f}s, peak p99 {d['peakP99Latency']:.2f}s")
        for k in (5.0, 7.0, 8.0, 9.75):
            fl = fluid(k, shape)
            print(f"fluid k={k}: binned {fl['backlogSeconds']:.0f}s, continuous {fl['continuousBacklogSeconds']:.1f}s")
    elif args.cmd == "predict":
        fl = fluid(args.k, shape)
        sc = scaled(args.k, shape, seeds, args.servers)
        doc = {"k": args.k, "fluid": _strip_series(fl), "scaled": _strip_series(sc)}
        text = json.dumps(doc, indent=1)
        if args.out:
            open(args.out, "w", encoding="utf-8").write(text)
        print(text)
    elif args.cmd == "fault":
        ns = [int(x) for x in args.node_servers.split(",")]
        doc = fault(ns, shape, seeds, args.down_node, args.down_at, args.up_at, args.redelivery_delay,
                    args.service_scale)
        text = json.dumps(_strip_series(doc), indent=1)
        if args.out:
            with open(args.out, "w", encoding="utf-8") as fh:
                json.dump(doc, fh, indent=1)
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
