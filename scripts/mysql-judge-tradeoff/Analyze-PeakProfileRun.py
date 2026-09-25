#!/usr/bin/env python3
"""Analyze one peak-profile run (Run-TradeoffExperiment.ps1 -PeakProfile).

    python Analyze-PeakProfileRun.py <runDirectory> [--seeds 15]

Reads the run's own files and writes, into the run directory:

    peak-summary.json   every number below, plus validity and model predictions
    peak-bins.csv       10s arrival bins: count, mean/p50/p99 queue wait, p50/p99 L_result
    peak-queue-1s.csv   1s series: arrivals, worker pickups, judge ends, waiting queue, container CPU
    peak-report.md      a short human-readable account

Definitions (shared with peak_queue_model.py so the model and the measurement are the same numbers):

* Time axis: seconds after the schedule anchor (the marker user's clock reading, stage-trace.csv). A
  submission's arrival is the load generator's own dispatch instant (submission-attempts.csv, joined on
  submissionId); every later instant is placed by adding a duration measured on the Docker VM's clock
  (judge_started_at - submitted_time, ...) so the host/VM clock offset never enters a duration.
* Queue wait = judge_started_at - submitted_time (worker pickup minus acceptance).
* Backlog duration (primary): arrivals grouped into 10s bins; a bin whose mean queue wait exceeds 1s is a
  backlog bin; the longest run of consecutive backlog bins starting at or after 90s.
* L_result = result_saved_at - submitted_time. Over-10s / over-30s counts use it.
* Peak p50/p99: L_result of arrivals in the first high segment's start to the last high segment's end.
* Effective k: while at least W submissions (W = total workers) are waiting, every worker has work
  available, so worker pickups per second in that span are the cluster's service rate mu. k_eff = mu / B.
  The longest such span is used, trimmed by 1s at each end; under 15s it is reported as insufficient.
* Model predictions at k_eff: fluid (binned and continuous) and the service-scaled discrete queue.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
from datetime import datetime, timezone

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import peak_queue_model as model  # noqa: E402

SATURATION_MIN_SECONDS = 15.0


def read_json(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8-sig") as fh:
        return json.load(fh)


def read_csv(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8-sig", newline="") as fh:
        return list(csv.DictReader(fh))


def db_time(text):
    """'2026-09-25 04:05:35.812964' (UTC, no zone) -> epoch seconds."""
    if not text or text in ("NULL", "unavailable"):
        return None
    text = text.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(text, fmt).replace(tzinfo=timezone.utc).timestamp()
        except ValueError:
            pass
    return None


def iso_time(text):
    if not text or text == "unavailable":
        return None
    t = text.strip().replace("Z", "+00:00")
    # Trim fractional seconds beyond microseconds (.NET "o" format has 7 digits).
    if "." in t:
        head, rest = t.split(".", 1)
        digits = ""
        for ch in rest:
            if not ch.isdigit():
                break
            digits += ch
        tz = rest[len(digits):]
        t = f"{head}.{digits[:6]}{tz}"
    return datetime.fromisoformat(t).timestamp()


def pct(values, p):
    v = [x for x in values if x is not None and not math.isnan(x)]
    return float(np.percentile(v, p)) if v else None


def read_trace(path):
    anchor, segments, plan_end = None, [], None
    open_seg = None
    with open(path, encoding="utf-8-sig") as fh:
        for row in csv.reader(fh):
            if len(row) < 8:
                continue
            ms, event = int(row[0]), row[1]
            if event == "anchor":
                anchor = ms
            elif event == "segmentStart":
                open_seg = {"index": int(row[2]), "startMs": ms, "rps": float(row[7])}
            elif event == "segmentEnd" and open_seg is not None:
                open_seg["endMs"] = ms
                segments.append(open_seg)
                open_seg = None
            elif event == "planDone":
                plan_end = ms
    return anchor, segments, plan_end


def analyze(run_dir, seeds=15):
    params = read_json(os.path.join(run_dir, "parameters.json")) or {}
    events = read_json(os.path.join(run_dir, "events.json")) or {}
    verification = read_json(os.path.join(run_dir, "db-verification.json")) or {}
    supply = read_json(os.path.join(run_dir, "supply.json")) or {}
    peak = params.get("peakProfile") or {}
    base_rps = float(peak.get("baseRps") or params.get("targetRps"))
    segments_text = peak.get("segments", "90:1,60:5,30:10,60:5,120:1")
    shape = model.Shape(base_rps, model.parse_segments(segments_text))
    mode = params.get("dispatchMode")
    total_workers = int(params.get("totalWorkers") or 2 * int(params.get("workerCountPerNode", 16)))
    node_workers = [int(params["judgeNodes"]["judge-1"]["workers"]), int(params["judgeNodes"]["judge-2"]["workers"])] \
        if params.get("judgeNodes") else [total_workers // 2, total_workers - total_workers // 2]
    k_nominal = model.nominal_k(total_workers, base_rps)

    anchor_ms, trace_segments, plan_end_ms = read_trace(os.path.join(run_dir, "stage-trace.csv"))
    anchor = anchor_ms / 1000.0

    # ---- per-submission timeline -------------------------------------------------------------------
    attempts = {r["submissionId"]: r for r in read_csv(os.path.join(run_dir, "submission-attempts.csv"))
                if r.get("submissionId") not in (None, "", "unavailable")}
    lat_rows = read_csv(os.path.join(run_dir, "latency.csv"))
    subs = []
    offsets = []
    for r in lat_rows:
        submitted = db_time(r.get("submittedAt"))
        if submitted is None:
            continue
        a = attempts.get(r["submissionId"])
        host_start = iso_time(a["startedAt"]) if a else None
        if host_start is not None:
            offsets.append(submitted - host_start)
        subs.append({
            "id": r["submissionId"], "submitted": submitted, "hostStart": host_start,
            "judgeStarted": db_time(r.get("judgeStartedAt")), "judged": db_time(r.get("judgedAt")),
            "saved": db_time(r.get("resultSavedAt")), "published": db_time(r.get("outboxPublishedAt")),
            "attempts": int(r["attempts"]) if (r.get("attempts") or "").isdigit() else None,
            "slow": r.get("latencyClass") == "slow",
        })
    # VM clock minus host clock, including the request's own trip to the web node. Only used to place a
    # submission the client log could not be joined for.
    clock_offset = float(np.median(offsets)) if offsets else 0.0
    for s in subs:
        s["t"] = (s["hostStart"] if s["hostStart"] is not None else s["submitted"] - clock_offset) - anchor
        s["wait"] = (s["judgeStarted"] - s["submitted"]) if s["judgeStarted"] is not None else None
        s["lresult"] = (s["saved"] - s["submitted"]) if s["saved"] is not None else None
        s["judgeTime"] = (s["judged"] - s["judgeStarted"]) if (s["judged"] and s["judgeStarted"]) else None
        s["postJudgeSave"] = (s["saved"] - s["judged"]) if (s["saved"] and s["judged"]) else None
        s["saveToPublish"] = (s["published"] - s["saved"]) if (s["published"] and s["saved"]) else None
        s["startT"] = s["t"] + s["wait"] if s["wait"] is not None else None
        s["judgedT"] = s["t"] + (s["judged"] - s["submitted"]) if s["judged"] else None
        s["savedT"] = s["t"] + s["lresult"] if s["lresult"] is not None else None
    subs.sort(key=lambda s: s["t"])

    t = np.array([s["t"] for s in subs])
    wait = np.array([np.nan if s["wait"] is None else s["wait"] for s in subs])
    lres = np.array([np.nan if s["lresult"] is None else s["lresult"] for s in subs])

    # ---- 10s bins, backlog -------------------------------------------------------------------------
    nbins = int(math.ceil(shape.total_seconds / model.BIN_SECONDS))
    bins = []
    for b in range(nbins):
        lo, hi = b * model.BIN_SECONDS, (b + 1) * model.BIN_SECONDS
        m = (t >= lo) & (t < hi)
        w = wait[m & ~np.isnan(wait)]
        lr = lres[m & ~np.isnan(lres)]
        bins.append({
            "binStart": lo, "count": int(m.sum()),
            "meanWait": float(w.mean()) if len(w) else None,
            "p50Wait": pct(w, 50), "p99Wait": pct(w, 99),
            "p50Lresult": pct(lr, 50), "p99Lresult": pct(lr, 99),
            "segmentRps": shape.rate_at(lo),
        })
    backlog = model.longest_backlog_run([b["binStart"] for b in bins], [b["meanWait"] for b in bins],
                                        head_seconds=shape.head_seconds)
    all_backlog_bins = [b["binStart"] for b in bins if b["binStart"] >= shape.head_seconds
                        and b["meanWait"] is not None and b["meanWait"] > model.BACKLOG_WAIT_THRESHOLD_S]

    lo_peak, hi_peak = shape.peak_window()
    in_peak = (t >= lo_peak) & (t < hi_peak)
    valid_l = ~np.isnan(lres)

    # ---- queue series and effective k --------------------------------------------------------------
    starts = np.sort(np.array([s["startT"] for s in subs if s["startT"] is not None]))
    judged_t = np.sort(np.array([s["judgedT"] for s in subs if s["judgedT"] is not None]))
    end_t = max([shape.total_seconds + 1] + [s["savedT"] for s in subs if s["savedT"] is not None])
    grid = np.arange(0.0, end_t + 0.1, 0.1)
    arrived = np.searchsorted(t, grid, side="right")
    picked = np.searchsorted(starts, grid, side="right")
    waiting = arrived - picked
    sat = waiting >= total_workers
    best = (0, 0)
    i = 0
    while i < len(grid):
        if sat[i]:
            j = i
            while j + 1 < len(grid) and sat[j + 1]:
                j += 1
            if j - i > best[1] - best[0]:
                best = (i, j)
            i = j + 1
        else:
            i += 1
    eff = {"basis": f"worker pickups per second while >= {total_workers} submissions (the total worker count) were waiting; longest such span, trimmed 1s at each end"}
    if best[1] > best[0]:
        span_lo, span_hi = grid[best[0]] + 1.0, grid[best[1]] - 1.0
    else:
        span_lo = span_hi = 0.0
    span = span_hi - span_lo
    eff.update({"spanStart": round(span_lo, 1), "spanEnd": round(span_hi, 1), "spanSeconds": round(max(span, 0.0), 1)})
    if span >= SATURATION_MIN_SECONDS:
        n_starts = int(((starts >= span_lo) & (starts < span_hi)).sum())
        n_judged = int(((judged_t >= span_lo) & (judged_t < span_hi)).sum())
        mu = n_starts / span
        eff.update({"pickupsInSpan": n_starts, "judgementsEndedInSpan": n_judged,
                    "serviceRatePerSecond": round(mu, 3), "kEffective": round(mu / base_rps, 3),
                    "kEffectiveFromJudgementEnds": round(n_judged / span / base_rps, 3),
                    "efficiency": round(mu / base_rps / k_nominal, 3), "sufficient": True})
        # The judge-time mix inside the span, so a span that happened to hold more slow jobs than 5%
        # is visible rather than read as lower efficiency.
        in_span = [s for s in subs if s["startT"] is not None and span_lo <= s["startT"] < span_hi]
        eff["slowShareInSpan"] = round(sum(1 for s in in_span if s["slow"]) / len(in_span), 4) if in_span else None
        eff["meanServiceSecondsImplied"] = round(total_workers / mu, 4) if mu > 0 else None
    else:
        eff.update({"sufficient": False, "kEffective": None, "efficiency": None,
                    "reason": f"no span of {SATURATION_MIN_SECONDS:.0f}s or more with >= {total_workers} waiting"})

    # ---- post-judge work ---------------------------------------------------------------------------
    def dist(key, scale=1000.0):
        v = [s[key] * scale for s in subs if s[key] is not None]
        if not v:
            return None
        return {"n": len(v), "meanMs": round(float(np.mean(v)), 2), "p50Ms": round(pct(v, 50), 2),
                "p90Ms": round(pct(v, 90), 2), "p99Ms": round(pct(v, 99), 2)}

    post = {
        "judgeTime": dist("judgeTime"),
        "judgeEndToResultSaved": dist("postJudgeSave"),
        "resultSavedToOutboxPublished": dist("saveToPublish") if mode == "mysql" else None,
        "note": ("judge time = provisional_judged_at - judge_started_at (judge JVM clock); judge end -> result "
                 "saved crosses to the DB clock (same Docker VM). Under mysql the outbox row turns PUBLISHED when "
                 "the worker completes it, so saved -> published is the dispatcher's completeAll. Under rabbit the "
                 "outbox row is published by the relay before judging, so that step does not exist."),
    }
    fast_post = [s["postJudgeSave"] * 1000 for s in subs if s["postJudgeSave"] is not None and not s["slow"]]
    post["judgeEndToResultSavedFastOnlyP50Ms"] = round(pct(fast_post, 50), 2) if fast_post else None
    # Worker occupancy by Little's law over the whole run from the processing timer, when scraped.
    post["prometheus"] = prom_processing(run_dir)

    # ---- cost: CPU, DB ----------------------------------------------------------------------------
    drain_end = iso_time(events.get("drainEndedAt")) if events.get("drainEndedAt") else None
    window_end = (drain_end - anchor) if drain_end else shape.total_seconds
    cpu = cpu_summary(run_dir, anchor, shape.total_seconds, window_end, shape.top_window())
    host = host_summary(run_dir, anchor, shape.total_seconds)
    db = db_summary(run_dir, anchor, window_end)

    # ---- fault -------------------------------------------------------------------------------------
    fault = None
    if events.get("faultInjectedAt"):
        fault_t = iso_time(events["faultInjectedAt"]) - anchor
        restart_t = iso_time(events["nodeRestartedAt"]) - anchor if events.get("nodeRestartedAt") else None
        ready_t = iso_time(events["nodeReadyAt"]) - anchor if events.get("nodeReadyAt") else None
        judging_t = iso_time(events["nodeFirstJudgementAfterRestartAt"]) - anchor if events.get("nodeFirstJudgementAfterRestartAt") else None
        if mode == "mysql":
            cohort_ids = {s["id"] for s in subs if (s["attempts"] or 0) > 1}
            cohort_basis = "outbox attempts > 1: re-claimed after the 4s lease expired"
        else:
            red = read_csv(os.path.join(run_dir, "redelivered-submissions.csv"))
            cohort_ids = {r["submissionId"] for r in red if r.get("submissionId")}
            cohort_basis = "the surviving node logged the delivery with the AMQP redelivered flag set"
        cohort = [s for s in subs if s["id"] in cohort_ids]
        fault = {
            "faultAt": round(fault_t, 2), "restartAt": None if restart_t is None else round(restart_t, 2),
            "metricsUpAt": None if ready_t is None else round(ready_t, 2),
            "firstJudgementAfterRestartAt": None if judging_t is None else round(judging_t, 2),
            "redeliveredOrReclaimed": {
                "basis": cohort_basis, "count": len(cohort),
                "maxLresultSeconds": round(max([s["lresult"] for s in cohort if s["lresult"] is not None], default=float("nan")), 2) if cohort else None,
                "p50LresultSeconds": round(pct([s["lresult"] for s in cohort], 50), 2) if cohort else None,
                "maxWaitSeconds": round(max([s["wait"] for s in cohort if s["wait"] is not None], default=float("nan")), 2) if cohort else None,
                "note": "small sample: read as a maximum, not a distribution",
            },
        }
        up_at = judging_t if judging_t is not None else (ready_t if ready_t is not None else restart_t)
        if up_at is not None:
            delay = 0.0 if mode == "rabbit" else claim_timeout_seconds(params)
            nominal_fault = model.fault(node_workers, shape, range(20260925, 20260925 + seeds), 0, fault_t, up_at, delay)
            fault["modelNominal"] = {k: v for k, v in nominal_fault.items() if k != "seriesMedian"}

    # ---- model predictions -------------------------------------------------------------------------
    seed_list = list(range(20260925, 20260925 + seeds))
    nominal_pred = model.discrete(total_workers, shape, seed_list)
    predictions = {"nominal": strip(nominal_pred)}
    if eff.get("sufficient"):
        predictions["fluidAtKEffective"] = strip(model.fluid(eff["kEffective"], shape))
        predictions["scaledAtKEffective"] = strip(model.scaled(eff["kEffective"], shape, seed_list, total_workers))
        # Replay: this run's own arrival instants and its own slow/fast sequence, served FIFO by the run's
        # workers with every judge time scaled so the mean service equals total workers / measured rate.
        # If the measured backlog equals this, the run is fully described by one number - its service rate.
        order = [s_ for s_ in subs]
        arr = np.array([s_["t"] for s_ in order])
        nominal_ms = np.array([model.SLOW_MS if s_["slow"] else model.FAST_MS for s_ in order]) / 1000.0
        scale = (total_workers / eff["serviceRatePerSecond"]) / float(nominal_ms.mean())
        svc = nominal_ms * scale
        st = model.simulate_fifo(arr, svc, total_workers)
        rep = strip(model.summarize(arr, st, st + svc, shape))
        rep.update({"model": "replay", "serviceScale": round(scale, 4), "servers": total_workers})
        predictions["replayAtKEffective"] = rep

    # ---- validity ----------------------------------------------------------------------------------
    invalid = []
    if supply and not supply.get("supplySucceeded", False):
        invalid.extend(supply.get("failed") or ["supply verdict failed"])
    integrity = (verification.get("integrity") or {})
    if not integrity.get("passed"):
        invalid.append(f"integrity not passed: {verification.get('counts')}")
    if len(subs) == 0:
        invalid.append("no submissions in latency.csv")
    missing_start = sum(1 for s in subs if s["judgeStarted"] is None)
    if missing_start:
        invalid.append(f"{missing_start} results have no judge_started_at")
    flags = []
    if host.get("sustainedAbove90"):
        flags.append("host CPU above 90% for 30s or more inside the load")
    rabbit_cpu = (cpu.get("containers") or {}).get("rabbitmq") or {}
    if rabbit_cpu.get("limitCores") and (rabbit_cpu.get("meanCoresTopPeak") or 0) >= 0.9 * rabbit_cpu["limitCores"]:
        flags.append("broker at >= 90% of its CPU limit during the top peak (configuration limit, not the dispatch method)")

    summary = {
        "runId": params.get("runId"), "dispatchMode": mode, "baseRps": base_rps, "segments": segments_text,
        "nodeWorkers": node_workers, "totalWorkers": total_workers, "kNominal": round(k_nominal, 3),
        "mysqlMaxInFlight": [params["judgeNodes"]["judge-1"]["mysqlMaxInFlight"], params["judgeNodes"]["judge-2"]["mysqlMaxInFlight"]] if params.get("judgeNodes") else None,
        "mysqlClaimTimeout": params.get("mysqlClaimTimeout"), "mysqlPollInterval": params.get("mysqlPollInterval"),
        "rabbitPrefetch": params.get("rabbitPrefetch"),
        "valid": not invalid, "invalidReasons": invalid, "flags": flags,
        "statusCounts": supply.get("statusCounts"),
        "counts": verification.get("counts"),
        "arrivals": len(subs), "clockOffsetSeconds": round(clock_offset, 4),
        "joinedToClientStart": sum(1 for s in subs if s["hostStart"] is not None),
        "backlogSeconds": backlog["backlogSeconds"], "backlogFirstBin": backlog["firstBacklogBinStart"],
        "backlogLastBin": backlog["lastBacklogBinStart"], "backlogBinsAll": all_backlog_bins,
        "over10s": int((lres[valid_l] > 10.0).sum()), "over30s": int((lres[valid_l] > 30.0).sum()),
        "maxWaitSeconds": round(float(np.nanmax(wait)), 3) if len(wait) else None,
        "peakP50LresultSeconds": round(pct(lres[in_peak & valid_l], 50), 3),
        "peakP99LresultSeconds": round(pct(lres[in_peak & valid_l], 99), 3),
        "baselineHeadP50WaitSeconds": round(pct(wait[(t < shape.head_seconds) & ~np.isnan(wait)], 50), 4),
        "baselineHeadP50LresultSeconds": round(pct(lres[(t < shape.head_seconds) & valid_l], 50), 4),
        "effective": eff, "postJudge": post, "cpu": cpu, "host": host, "db": db,
        "fault": fault, "predictions": predictions,
        "drainSeconds": events.get("drainSeconds"),
    }
    with open(os.path.join(run_dir, "peak-summary.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=1, default=float)
    with open(os.path.join(run_dir, "peak-bins.csv"), "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(bins[0].keys()))
        w.writeheader()
        w.writerows(bins)
    write_queue_series(run_dir, grid, arrived, picked, judged_t, cpu.get("_series"))
    summary["cpu"].pop("_series", None)
    with open(os.path.join(run_dir, "peak-summary.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=1, default=float)
    write_report(run_dir, summary)
    return summary


def strip(d):
    return {k: v for k, v in d.items() if k not in ("seriesMedian", "series")}


def claim_timeout_seconds(params):
    text = str(params.get("mysqlClaimTimeoutProperty") or params.get("mysqlClaimTimeout") or "4s")
    if text.endswith("ms"):
        return float(text[:-2]) / 1000.0
    if text.endswith("s"):
        return float(text[:-1])
    return 4.0


def prom_value(path, metric):
    total = 0.0
    found = False
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8-sig") as fh:
        for line in fh:
            if line.startswith(metric + "{") or line.startswith(metric + " "):
                try:
                    total += float(line.rsplit(" ", 1)[1])
                    found = True
                except ValueError:
                    pass
    return total if found else None


def prom_processing(run_dir):
    out = {}
    for node in ("judge-1", "judge-2"):
        s = os.path.join(run_dir, "metrics", f"start-{node}.prom")
        e = os.path.join(run_dir, "metrics", f"end-{node}.prom")
        vals = {}
        for name in ("contest_judge_processing_seconds_sum", "contest_judge_processing_seconds_count",
                     "contest_judge_duration_seconds_sum", "contest_judge_duration_seconds_count"):
            a, b = prom_value(s, name), prom_value(e, name)
            vals[name] = None if a is None or b is None or b < a else b - a
        cnt = vals["contest_judge_processing_seconds_count"]
        if cnt:
            out[node] = {
                "invocations": cnt,
                "meanProcessingMs": round(1000 * vals["contest_judge_processing_seconds_sum"] / cnt, 2),
                "meanJudgementMs": round(1000 * vals["contest_judge_duration_seconds_sum"] / vals["contest_judge_duration_seconds_count"], 2)
                if vals["contest_judge_duration_seconds_count"] else None,
            }
            if out[node]["meanJudgementMs"] is not None:
                out[node]["meanPostJudgeInProcessorMs"] = round(out[node]["meanProcessingMs"] - out[node]["meanJudgementMs"], 2)
        else:
            out[node] = {"unavailable": "counter missing or reset (a killed node loses its counters)"}
    return out


def cpu_summary(run_dir, anchor, load_seconds, window_end, top=(150.0, 240.0)):
    rows = read_csv(os.path.join(run_dir, "container-cpu-1s.csv"))
    if not rows:
        return {"unavailable": "container-cpu-1s.csv missing"}
    per = {}
    series = {}
    for r in rows:
        tm = int(r["epochMillis"]) / 1000.0 - anchor
        if r.get("cpuCores") in (None, ""):
            continue
        cores = float(r["cpuCores"])
        interval = float(r["intervalMs"]) / 1000.0
        name = r["container"].replace("oj-loadtest-", "")
        d = per.setdefault(name, {"loadCpuSeconds": 0.0, "eventCpuSeconds": 0.0, "throttledMsLoad": 0.0,
                                  "limit": float(r["cpuLimitCores"]) if r.get("cpuLimitCores") else None,
                                  "loadSeconds": 0.0, "peakSeconds": 0.0, "peakCpuSeconds": 0.0, "throttledTicksLoad": 0})
        if 0 <= tm <= load_seconds:
            d["loadCpuSeconds"] += cores * interval
            d["loadSeconds"] += interval
            d["throttledMsLoad"] += float(r["throttledMs"] or 0)
            if float(r["throttledMs"] or 0) > 0:
                d["throttledTicksLoad"] += 1
            if top[0] <= tm <= top[1]:
                d["peakCpuSeconds"] += cores * interval
                d["peakSeconds"] += interval
        if 0 <= tm <= window_end:
            d["eventCpuSeconds"] += cores * interval
        series.setdefault(int(math.floor(tm)), {})[name] = cores
    out = {}
    for name, d in per.items():
        out[name] = {
            "cpuSecondsLoad": round(d["loadCpuSeconds"], 2),
            "cpuSecondsLoadAndDrain": round(d["eventCpuSeconds"], 2),
            "meanCoresLoad": round(d["loadCpuSeconds"] / d["loadSeconds"], 4) if d["loadSeconds"] else None,
            "meanCoresTopPeak": round(d["peakCpuSeconds"] / d["peakSeconds"], 4) if d["peakSeconds"] else None,
            "limitCores": d["limit"],
            "throttledMsLoad": round(d["throttledMsLoad"], 1),
            "throttledTicksLoad": d["throttledTicksLoad"],
        }
    stack = [n for n in out if n != "cpu-sampler"]
    total = {
        "cpuSecondsLoad": round(sum(out[n]["cpuSecondsLoad"] for n in stack), 2),
        "cpuSecondsLoadAndDrain": round(sum(out[n]["cpuSecondsLoadAndDrain"] for n in stack), 2),
        "meanCoresLoad": round(sum(out[n]["meanCoresLoad"] or 0 for n in stack), 3),
        "meanCoresTopPeak": round(sum(out[n]["meanCoresTopPeak"] or 0 for n in stack), 3),
    }
    return {"containers": out, "stackTotal": total,
            "window": f"load = the whole schedule; loadAndDrain = anchor to drain end; topPeak = {top[0]:g}-{top[1]:g}s (top segment and the one after it)",
            "_series": series}


def host_summary(run_dir, anchor, load_seconds):
    rows = read_csv(os.path.join(run_dir, "host-cpu-1s.csv"))
    if not rows:
        return {"unavailable": "host-cpu-1s.csv missing"}
    vals = [(int(r["epochMillis"]) / 1000.0 - anchor, float(r["hostBusyPercent"])) for r in rows]
    load = [v for tm, v in vals if 0 <= tm <= load_seconds]
    peak = [v for tm, v in vals if 90 <= tm <= 240]
    longest, run = 0, 0
    for tm, v in vals:
        if 0 <= tm <= load_seconds and v > 90:
            run += 1
            longest = max(longest, run)
        elif 0 <= tm <= load_seconds:
            run = 0
    return {"meanBusyPercentLoad": round(float(np.mean(load)), 2) if load else None,
            "meanBusyPercentPeak": round(float(np.mean(peak)), 2) if peak else None,
            "maxBusyPercent": round(max(load), 2) if load else None,
            "secondsAbove90": sum(1 for v in load if v > 90),
            "longestRunAbove90Seconds": longest,
            "sustainedAbove90": longest >= 30,
            "logicalProcessors": int(rows[0]["logicalProcessors"]),
            "basis": "Win32_PerfRawData_PerfOS_Processor(_Total), differentiated per second; includes the Docker VM and the load generator"}


def db_summary(run_dir, anchor, window_end):
    rows = read_csv(os.path.join(run_dir, "timeseries.csv"))
    pts = []
    for r in rows:
        try:
            tm = int(r["epochMillis"]) / 1000.0 - anchor
            pts.append((tm, float(r["innodbRowLockWaits"]), float(r["questions"])))
        except (ValueError, KeyError, TypeError):
            continue
    pts = [p for p in pts if 0 <= p[0] <= window_end]
    if len(pts) < 2:
        return {"unavailable": "timeseries.csv has fewer than two ticks inside the window"}
    span = pts[-1][0] - pts[0][0]
    return {"rowLockWaitsPerSecond": round((pts[-1][1] - pts[0][1]) / span, 3),
            "questionsPerSecond": round((pts[-1][2] - pts[0][2]) / span, 1),
            "windowSeconds": round(span, 1),
            "basis": "Innodb_row_lock_waits and Questions deltas between the first and last sampler tick from the anchor to drain end"}


def write_queue_series(run_dir, grid, arrived, picked, judged_t, cpu_series):
    path = os.path.join(run_dir, "peak-queue-1s.csv")
    names = sorted({n for d in (cpu_series or {}).values() for n in d})
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["second", "arrivedTotal", "pickedUpTotal", "waiting", "judgementsEndedTotal"] + [f"cpu_{n}" for n in names])
        for sec in range(0, int(grid[-1]) + 1):
            i = min(int(round(sec / 0.1)), len(grid) - 1)
            row = [sec, int(arrived[i]), int(picked[i]), int(arrived[i] - picked[i]),
                   int(np.searchsorted(judged_t, sec, side="right"))]
            cs = (cpu_series or {}).get(sec, {})
            row += [cs.get(n, "") for n in names]
            w.writerow(row)


def fmt(v, nd=1):
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:.{nd}f}"
    return str(v)


def write_report(run_dir, s):
    eff = s["effective"]
    pr = s["predictions"]
    lines = [f"# Peak profile run {s['runId']}", "",
             f"- dispatch: **{s['dispatchMode']}**, workers {s['nodeWorkers']} (total {s['totalWorkers']}), B={s['baseRps']}, nominal k={s['kNominal']}",
             f"- valid: **{s['valid']}** {'' if s['valid'] else '- ' + '; '.join(s['invalidReasons'])}",
             f"- statuses: {s['statusCounts']}; counts: {s['counts']}",
             "", "| metric | measured | nominal model | fluid @k_eff | scaled @k_eff | replay @k_eff |", "|---|---:|---:|---:|---:|---:|"]
    fl = pr.get("fluidAtKEffective") or {}
    rp = pr.get("replayAtKEffective") or {}
    sc = pr.get("scaledAtKEffective") or {}
    no = pr["nominal"]
    lines.append(f"| backlog s | {s['backlogSeconds']:.0f} | {fmt(no.get('backlogSeconds'),0)} | {fmt(fl.get('backlogSeconds'),0)} (cont. {fmt(fl.get('continuousBacklogSeconds'))}) | {fmt(sc.get('backlogSeconds'),0)} | {fmt(rp.get('backlogSeconds'),0)} |")
    lines.append(f"| L_result > 10s | {s['over10s']} | {fmt(no.get('over10s'),0)} | {fmt(fl.get('over10s'),0)} | {fmt(sc.get('over10s'),0)} | {fmt(rp.get('over10s'),0)} |")
    lines.append(f"| L_result > 30s | {s['over30s']} | {fmt(no.get('over30s'),0)} | {fmt(fl.get('over30s'),0)} | {fmt(sc.get('over30s'),0)} | {fmt(rp.get('over30s'),0)} |")
    lines.append(f"| max wait s | {fmt(s['maxWaitSeconds'],2)} | {fmt(no.get('maxWait'),2)} | {fmt(fl.get('maxWait'),2)} | {fmt(sc.get('maxWait'),2)} | {fmt(rp.get('maxWait'),2)} |")
    lines.append(f"| peak p50 s | {fmt(s['peakP50LresultSeconds'],2)} | {fmt(no.get('peakP50Latency'),2)} | {fmt(fl.get('peakP50Latency'),2)} | {fmt(sc.get('peakP50Latency'),2)} | {fmt(rp.get('peakP50Latency'),2)} |")
    lines.append(f"| peak p99 s | {fmt(s['peakP99LresultSeconds'],2)} | {fmt(no.get('peakP99Latency'),2)} | {fmt(fl.get('peakP99Latency'),2)} | {fmt(sc.get('peakP99Latency'),2)} | {fmt(rp.get('peakP99Latency'),2)} |")
    lines += ["", f"- effective k: {fmt(eff.get('kEffective'),3)} (efficiency {fmt(eff.get('efficiency'),3)}), span {eff.get('spanStart')}-{eff.get('spanEnd')}s ({eff.get('spanSeconds')}s)"
              + ("" if eff.get("sufficient") else f" - INSUFFICIENT: {eff.get('reason')}"),
              f"- CPU (load window, core-seconds): stack {s['cpu'].get('stackTotal')}",
              f"- host: {s['host']}", f"- db: {s['db']}", f"- post-judge: {s['postJudge']}"]
    if s.get("fault"):
        lines.append(f"- fault: {s['fault']}")
    with open(os.path.join(run_dir, "peak-report.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--seeds", type=int, default=15)
    a = ap.parse_args(argv)
    s = analyze(a.run_dir, a.seeds)
    print(open(os.path.join(a.run_dir, "peak-report.md"), encoding="utf-8").read())
    return 0


if __name__ == "__main__":
    sys.exit(main())
