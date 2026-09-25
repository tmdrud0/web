#!/usr/bin/env python3
"""Replay a peak-fault run through the queue model with the run's own arrivals and job mix.

    python Replay-PeakFault.py <faultRunDir> --implied-ms 185.45 [--redelivery-delay 4] [--up-at 208.7]

The run's own arrival instants and slow/fast sequence are served by the event-driven model
(peak_queue_model.simulate_with_outages): judge-1's workers are down from the recorded kill to the node's
first judgement after restart (or --up-at), their running work is re-queued after --redelivery-delay, and
every judge time is scaled so a job holds a worker for --implied-ms on average - the per-job occupancy
measured in the same mode's fault-free runs at the same worker count. Variants separate what decides the
backlog: the redelivery delay (0 vs the lease), and the outage length (node ready at the restart request).
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util  # noqa: E402

import peak_queue_model as model  # noqa: E402

spec = importlib.util.spec_from_file_location(
    "peak_analyzer", os.path.join(os.path.dirname(os.path.abspath(__file__)), "Analyze-PeakProfileRun.py"))
ana = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ana)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--implied-ms", type=float, required=True)
    ap.add_argument("--redelivery-delay", type=float, default=None)
    ap.add_argument("--up-at", type=float, default=None)
    a = ap.parse_args()
    s = json.load(open(os.path.join(a.run_dir, "peak-summary.json"), encoding="utf-8"))
    params = json.load(open(os.path.join(a.run_dir, "parameters.json"), encoding="utf-8-sig"))
    shape = model.Shape(s["baseRps"], model.parse_segments(s["segments"]))
    anchor, _, _ = ana.read_trace(os.path.join(a.run_dir, "stage-trace.csv"))
    anchor /= 1000.0
    attempts = {r["submissionId"]: r for r in ana.read_csv(os.path.join(a.run_dir, "submission-attempts.csv"))}
    arr, slow = [], []
    for r in ana.read_csv(os.path.join(a.run_dir, "latency.csv")):
        at = attempts.get(r["submissionId"])
        if at is None:
            continue
        arr.append(ana.iso_time(at["startedAt"]) - anchor)
        slow.append(r["latencyClass"] == "slow")
    order = np.argsort(arr)
    arr = np.array(arr)[order]
    nominal = np.where(np.array(slow)[order], model.SLOW_MS, model.FAST_MS) / 1000.0
    scale = (a.implied_ms / 1000.0) / model.MEAN_SERVICE_S
    svc = nominal * scale
    f = s["fault"]
    up = a.up_at if a.up_at is not None else (f.get("firstJudgementAfterRestartAt") or f.get("metricsUpAt"))
    delay = a.redelivery_delay if a.redelivery_delay is not None else (0.0 if s["dispatchMode"] == "rabbit" else ana.claim_timeout_seconds(params))
    nodes = s["nodeWorkers"]

    def run(label, down_at, up_at, d):
        if down_at is None:
            st = model.simulate_fifo(arr, svc, sum(nodes))
            end, red = st + svc, np.zeros(len(arr), dtype=bool)
        else:
            st, end, red = model.simulate_with_outages(arr, svc, nodes, [model.NodeOutage(0, down_at, up_at, d)])
        out = {k: v for k, v in model.summarize(arr, st, end, shape).items() if k != "series"}
        out.update({"variant": label, "redelivered": int(red.sum()), "downAt": down_at, "upAt": up_at, "redeliveryDelay": d})
        return out

    variants = [
        run("as-run", f["faultAt"], up, delay),
        run("no-redelivery-delay", f["faultAt"], up, 0.0),
        run("lease-30s", f["faultAt"], up, 30.0),
        run("ready-at-restart-request", f["faultAt"], f["restartAt"], delay),
        run("no-fault", None, None, 0.0),
    ]
    doc = {"run": s["runId"], "mode": s["dispatchMode"], "impliedMs": a.implied_ms, "serviceScale": round(scale, 4),
           "measured": {k: s[k] for k in ("backlogSeconds", "over10s", "over30s", "maxWaitSeconds", "peakP50LresultSeconds", "peakP99LresultSeconds")},
           "variants": variants}
    with open(os.path.join(a.run_dir, "fault-replay.json"), "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, default=float)
    m = doc["measured"]
    print(f"{s['runId']} measured: backlog {m['backlogSeconds']:.0f} >10 {m['over10s']} >30 {m['over30s']} maxWait {m['maxWaitSeconds']:.1f} "
          f"p50 {m['peakP50LresultSeconds']:.1f} p99 {m['peakP99LresultSeconds']:.1f}")
    for v in variants:
        print(f"  {v['variant']:26s} backlog {v['backlogSeconds']:.0f} >10 {v['over10s']} >30 {v['over30s']} maxWait {v['maxWait']:.1f} "
              f"p50 {v['peakP50Latency']:.1f} p99 {v['peakP99Latency']:.1f} redelivered {v['redelivered']}")


if __name__ == "__main__":
    main()
