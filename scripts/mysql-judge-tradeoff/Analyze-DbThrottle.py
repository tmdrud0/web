"""Break MySQL container CPU and throttling down by peak-profile segment.

For each run directory it reads container-cpu-1s.csv (MySQL rows), stage-trace.csv (anchor) and
latency.csv, and reports per segment: mean/max cores, CFS throttled time and the number of seconds
with any throttling, and the judge-end -> result-saved and result-saved -> outbox-published
(MySQL dispatch only) latencies of submissions judged in that segment.

Usage: python Analyze-DbThrottle.py [--json OUT] RUN_DIR [RUN_DIR ...]
"""
import argparse
import csv
import datetime
import json
import os
import statistics

SEGMENTS = [("base-1", 0, 90), ("5B-a", 90, 150), ("10B", 150, 180),
            ("5B-b", 180, 240), ("base-2", 240, 360), ("drain", 360, 420)]


def parse_ts(value):
    return datetime.datetime.strptime(value[:26], "%Y-%m-%d %H:%M:%S.%f") \
        .replace(tzinfo=datetime.timezone.utc).timestamp()


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(p * len(values)))], 1)


def analyze(run_dir):
    anchor = None
    with open(os.path.join(run_dir, "stage-trace.csv"), encoding="utf-8") as f:
        for row in csv.reader(f):
            if row[1] == "anchor":
                anchor = int(row[0]) / 1000
                break
    with open(os.path.join(run_dir, "container-cpu-1s.csv"), encoding="utf-8") as f:
        cpu = [r for r in csv.DictReader(f) if r["container"].endswith("mysql")]
    with open(os.path.join(run_dir, "latency.csv"), encoding="utf-8-sig") as f:
        latency = list(csv.DictReader(f))
    mysql_dispatch = "-mysql-" in os.path.basename(run_dir)

    result = {"run": os.path.basename(run_dir), "limitCores": None, "segments": {}}
    for name, start, end in SEGMENTS:
        rows = [r for r in cpu if start <= int(r["epochMillis"]) / 1000 - anchor < end]
        cores = [float(r["cpuCores"]) for r in rows]
        if rows:
            result["limitCores"] = float(rows[0]["cpuLimitCores"])
        save, publish = [], []
        for r in latency:
            if not r["judgedAt"] or not r["resultSavedAt"]:
                continue
            judged = parse_ts(r["judgedAt"])
            if not start <= judged - anchor < end:
                continue
            saved = parse_ts(r["resultSavedAt"])
            save.append((saved - judged) * 1000)
            if mysql_dispatch and r["outboxPublishedAt"]:
                publish.append((parse_ts(r["outboxPublishedAt"]) - saved) * 1000)
        result["segments"][name] = {
            "seconds": len(rows),
            "meanCores": round(statistics.mean(cores), 2) if cores else None,
            "maxCores": round(max(cores), 2) if cores else None,
            "throttledMs": round(sum(float(r["throttledMs"]) for r in rows)),
            "throttledSeconds": sum(1 for r in rows if float(r["throttledMs"]) > 0),
            "judgeEndToSavedP50Ms": percentile(save, 0.5),
            "judgeEndToSavedP90Ms": percentile(save, 0.9),
            "savedToOutboxPublishedP50Ms": percentile(publish, 0.5),
            "savedToOutboxPublishedP90Ms": percentile(publish, 0.9),
        }
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json")
    parser.add_argument("runs", nargs="+")
    args = parser.parse_args()
    results = [analyze(run) for run in args.runs]
    for r in results:
        print(f"{r['run']} (limit {r['limitCores']} cores)")
        for name, s in r["segments"].items():
            print(f"  {name:7} mean {s['meanCores']} max {s['maxCores']} "
                  f"throttled {s['throttledMs']}ms in {s['throttledSeconds']}/{s['seconds']}s "
                  f"save p50/p90 {s['judgeEndToSavedP50Ms']}/{s['judgeEndToSavedP90Ms']} "
                  f"outbox p50/p90 {s['savedToOutboxPublishedP50Ms']}/{s['savedToOutboxPublishedP90Ms']}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
