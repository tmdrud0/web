#!/usr/bin/env python3
"""Where a worker's time goes, per peak-profile run: why effective k is below nominal k.

    python Decompose-PeakOccupancy.py <runDir> [<runDir> ...]

For every submission judged inside the run's saturated span (see Analyze-PeakProfileRun.py) it splits the
worker's mean occupancy per job into:

    nominal     the synthetic judge sleep the job was assigned (50ms or 2000ms by its latency class)
    preJudge    judge_started_at -> provisional_judged_at minus nominal: the stored-result lookup, the
                projection read and the sleep's own overshoot
    resultSave  provisional_judged_at -> result_saved_at: waiting on the result batch writer's insert
    afterSave   processor return minus result_saved_at, from the contest_judge_processing timer
                (mean processing - mean (saved - started)): stream publish and completion hand-back
    outside     implied occupancy (workers / pickups per second) minus mean processing: what the worker
                spends outside the processor call - under mysql the outbox completeAll, the local handoff
                and poll gaps; under rabbit the ack and the next delivery (prefetch 1)

implied = total workers / measured service rate, i.e. the mean time one job holds one worker when every
worker always has work. efficiency = nominal / implied, the same number as k_eff / k_nominal up to the
job mix of the span.
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util  # noqa: E402

spec = importlib.util.spec_from_file_location(
    "peak_analyzer", os.path.join(os.path.dirname(os.path.abspath(__file__)), "Analyze-PeakProfileRun.py"))
ana = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ana)


def decompose(run):
    s = json.load(open(os.path.join(run, "peak-summary.json"), encoding="utf-8"))
    eff = s["effective"]
    if not eff.get("sufficient"):
        return None
    anchor, _, _ = ana.read_trace(os.path.join(run, "stage-trace.csv"))
    anchor /= 1000.0
    attempts = {r["submissionId"]: r for r in ana.read_csv(os.path.join(run, "submission-attempts.csv"))}
    jobs = []
    for r in ana.read_csv(os.path.join(run, "latency.csv")):
        a = attempts.get(r["submissionId"])
        sub, st, jd, sv, pub = (ana.db_time(r.get(k)) for k in ("submittedAt", "judgeStartedAt", "judgedAt", "resultSavedAt", "outboxPublishedAt"))
        if a is None or st is None or jd is None or sv is None:
            continue
        start_t = ana.iso_time(a["startedAt"]) - anchor + (st - sub)
        if not (eff["spanStart"] <= start_t < eff["spanEnd"]):
            continue
        nominal = 2.0 if r["latencyClass"] == "slow" else 0.05
        jobs.append((nominal, jd - st, sv - jd, sv - st, (pub - sv) if pub else None))
    arr = np.array([j[:4] for j in jobs])
    nominal, judge, save, to_save = arr.mean(axis=0)
    prom = s["postJudge"]["prometheus"]
    inv = sum(v.get("invocations", 0) for v in prom.values() if "invocations" in v)
    processing = sum(v["invocations"] * v["meanProcessingMs"] for v in prom.values() if "invocations" in v) / inv / 1000.0
    implied = eff["meanServiceSecondsImplied"]
    pubs = [j[4] for j in jobs if j[4] is not None]
    ms = lambda x: round(1000 * x, 1)  # noqa: E731
    return {
        "run": s["runId"], "mode": s["dispatchMode"], "workers": s["totalWorkers"], "jobsInSpan": len(jobs),
        "slowShare": round(float(np.mean([j[0] > 1 for j in jobs])), 4),
        "nominalMs": ms(nominal), "preJudgeMs": ms(judge - nominal), "resultSaveMs": ms(save),
        "afterSaveMs": ms(processing - to_save), "processingMs": ms(processing),
        "outsideMs": ms(implied - processing), "impliedMs": ms(implied),
        "savedToOutboxPublishedMs": ms(float(np.mean(pubs))) if s["dispatchMode"] == "mysql" and pubs else None,
        "efficiency": round(nominal / implied, 3), "kEffOverKNominal": eff["efficiency"],
        "processingBasis": "contest_judge_processing timer, whole-run mean over both nodes (not span-only)",
    }


def main():
    rows = [d for d in (decompose(r) for r in sys.argv[1:]) if d]
    print("| run | mode | W | slow% | nominal | pre-judge | result save | after save | outside processor | implied | efficiency (nominal/implied) |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for d in rows:
        print(f"| {d['run']} | {d['mode']} | {d['workers']} | {100*d['slowShare']:.1f} | {d['nominalMs']} | {d['preJudgeMs']} | "
              f"{d['resultSaveMs']} | {d['afterSaveMs']} | {d['outsideMs']} | {d['impliedMs']} | {d['efficiency']} |")
    out = os.environ.get("DECOMPOSE_OUT")
    if out:
        json.dump(rows, open(out, "w", encoding="utf-8"), indent=1)


if __name__ == "__main__":
    main()
