#!/usr/bin/env python3
"""Generate CSV fixtures for large-scale performance testing.

The script produces CSV files for contests, problems, users, submissions,
accepted submissions, and user/problem guard rows so they can be ingested via
MySQL's `LOAD DATA` for faster bulk loading than going through HTTP APIs.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import random
from contextlib import nullcontext
from datetime import datetime, timedelta
from pathlib import Path


SOLVED_BUCKETS_YEAR1_100 = [
    (0, 0, 0.20),
    (1, 5, 0.28),
    (6, 15, 0.24),
    (16, 30, 0.15),
    (31, 50, 0.08),
    (51, 80, 0.04),
    (81, 100, 0.01),
]

CURRENT_ACTIVE_BUCKETS_YEAR1_100 = [
    (1, 1, 0.42),
    (2, 3, 0.28),
    (4, 7, 0.18),
    (8, 14, 0.08),
    (15, 30, 0.03),
    (31, 100, 0.01),
]

LONGEST_STREAK_BUCKETS_YEAR1_100 = [
    (0, 0, 0.08),
    (1, 3, 0.30),
    (4, 7, 0.22),
    (8, 14, 0.17),
    (15, 30, 0.13),
    (31, 60, 0.07),
    (61, 100, 0.03),
]

SOLVED_SUMMARY_BUCKETS = [
    ("0", 0, 0),
    ("1-5", 1, 5),
    ("6-15", 6, 15),
    ("16-30", 16, 30),
    ("31-50", 31, 50),
    ("51-80", 51, 80),
    ("81-100", 81, 100),
]

CURRENT_SUMMARY_BUCKETS = [
    ("0", 0, 0),
    ("1", 1, 1),
    ("2-3", 2, 3),
    ("4-7", 4, 7),
    ("8-14", 8, 14),
    ("15-30", 15, 30),
    ("31-100", 31, 100),
]

LONGEST_SUMMARY_BUCKETS = [
    ("0", 0, 0),
    ("1-3", 1, 3),
    ("4-7", 4, 7),
    ("8-14", 8, 14),
    ("15-30", 15, 30),
    ("31-100", 31, 100),
]

CURRENT_NON_ZERO_RATIO_YEAR1_100 = 0.01
SOLVED_ZERO_SHARE_YEAR1_100 = SOLVED_BUCKETS_YEAR1_100[0][2]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate CSV files for perf dataset seeding",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    parser.add_argument("output", type=Path, help="Directory to write CSV files")
    parser.add_argument(
        "--user-model",
        choices=["baseline", "oj-year1-100"],
        default="baseline",
        help="How to generate solved/streak distribution for users",
    )
    parser.add_argument(
        "--dataset",
        choices=["full", "rank-only"],
        default="full",
        help="Whether to generate the full schema fixture set or only rank-related user data",
    )
    parser.add_argument("--total-users", type=int, default=10_000_000)
    parser.add_argument("--inactive-users", type=int, default=None,
                        help="Users without any submissions")
    parser.add_argument("--total-submissions", type=int, default=20_000_000)
    parser.add_argument("--problem-count", type=int, default=10)
    parser.add_argument("--contest-count", type=int, default=2)
    parser.add_argument("--accepted-ratio", type=float, default=0.45)
    parser.add_argument("--submission-stddev", type=float, default=2.0,
                        help="Std dev multiplier for submissions per active user")
    parser.add_argument("--random-seed", type=int, default=4_202_409)
    parser.add_argument("--batch-hours", type=int, default=72,
                        help="How many hours after contests submissions occur")

    args = parser.parse_args()

    if args.user_model == "baseline" and args.inactive_users is None:
        args.inactive_users = min(5_000_000, args.total_users)
    if args.user_model == "oj-year1-100" and args.problem_count == parser.get_default("problem_count"):
        args.problem_count = 100
    if args.user_model == "oj-year1-100" and args.inactive_users is None:
        args.inactive_users = 0

    if args.inactive_users > args.total_users:
        parser.error("inactive_users cannot exceed total_users")
    if args.dataset == "full" and args.user_model == "baseline" and args.total_submissions < max(0, args.total_users - args.inactive_users):
        parser.error("total_submissions must be >= active user count")
    if args.problem_count <= 0:
        parser.error("problem_count must be positive")

    return args


def emit_contests(output_dir: Path, contest_count: int, base_time: datetime,
                  rng: random.Random) -> list[dict]:
    contests = []
    path = output_dir / "contest.csv"
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        for cid in range(1, contest_count + 1):
            end = base_time - timedelta(days=cid * 3)
            start = end - timedelta(days=2)
            writer.writerow([
                cid,
                f"perf_contest_{cid}",
                start.strftime("%Y-%m-%d %H:%M:%S"),
                end.strftime("%Y-%m-%d %H:%M:%S"),
            ])
            contests.append({"id": cid, "start": start, "end": end})
    return contests


def emit_problems(output_dir: Path, contests: list[dict], problem_count: int,
                  rng: random.Random) -> None:
    if not contests:
        return

    total = problem_count
    path = output_dir / "problem.csv"
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        for pid in range(1, total + 1):
            contest = contests[(pid - 1) % len(contests)]
            contest_num = (pid - 1) % 50 + 1
            writer.writerow([
                pid,
                f"perf_problem_{pid}",
                contest["id"],
                contest_num,
            ])


def hash_code(code: str) -> str:
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


def gaussian_int(rng: random.Random, mean: float, stddev: float,
                 minimum: int, maximum: int) -> int:
    if maximum <= minimum:
        return minimum
    value = int(round(rng.gauss(mean, stddev)))
    return max(minimum, min(maximum, value))


def triangular_int(rng: random.Random, minimum: int, maximum: int, mode: int) -> int:
    if maximum <= minimum:
        return minimum
    value = rng.triangular(minimum, maximum + 0.999999, mode)
    return max(minimum, min(maximum, int(value)))


def choose_weighted_bucket(rng: random.Random, buckets: list[tuple[int, int, float]],
                           cap: int) -> tuple[int, int, int]:
    eligible = []
    for index, (low, high, weight) in enumerate(buckets):
        if low > cap:
            continue
        eligible.append((index, low, min(high, cap), weight))

    total_weight = sum(weight for _, _, _, weight in eligible)
    pick = rng.random() * total_weight
    upto = 0.0
    for index, low, high, weight in eligible:
        upto += weight
        if pick <= upto:
            return index, low, high
    return eligible[-1][:3]


def sample_bucket_value(rng: random.Random, buckets: list[tuple[int, int, float]],
                        cap: int) -> int:
    _, low, high = choose_weighted_bucket(rng, buckets, cap)
    if low == high:
        return low
    return triangular_int(rng, low, high, low)


def sample_current_streak_year1_100(rng: random.Random, solved_count: int, streak_cap: int) -> int:
    if solved_count <= 0:
        return 0

    active_probability = min(1.0, CURRENT_NON_ZERO_RATIO_YEAR1_100 / max(0.000001, 1.0 - SOLVED_ZERO_SHARE_YEAR1_100))
    if rng.random() >= active_probability:
        return 0

    solved_ratio = solved_count / max(1, streak_cap)
    adjusted = []
    for index, (low, high, weight) in enumerate(CURRENT_ACTIVE_BUCKETS_YEAR1_100):
        if low > solved_count:
            continue
        adjusted_weight = weight * (1.0 + solved_ratio * (0.15 * index))
        adjusted.append((low, min(high, solved_count), adjusted_weight))
    return sample_bucket_value(rng, adjusted, solved_count)


def sample_longest_streak_year1_100(rng: random.Random, solved_count: int,
                                    current_streak: int, streak_cap: int) -> int:
    if solved_count <= 0:
        return 0

    solved_ratio = solved_count / max(1, streak_cap)
    adjusted = []
    for index, (low, high, weight) in enumerate(LONGEST_STREAK_BUCKETS_YEAR1_100):
        if low > solved_count:
            continue
        if low == 0 and high == 0:
            adjusted_weight = weight * max(0.20, 1.00 - solved_ratio * 0.80)
        else:
            adjusted_weight = weight * (1.0 + solved_ratio * (0.15 * index))
        adjusted.append((low, min(high, solved_count), adjusted_weight))

    sampled = sample_bucket_value(rng, adjusted, solved_count)
    if sampled >= current_streak:
        return sampled
    if current_streak >= solved_count:
        return solved_count
    return triangular_int(rng, current_streak, solved_count, current_streak)


def sample_user_profile_year1_100(rng: random.Random, problem_count: int) -> tuple[int, int, int, int]:
    solved_cap = min(problem_count, 100)
    solved_count = sample_bucket_value(rng, SOLVED_BUCKETS_YEAR1_100, solved_cap)

    current_streak = sample_current_streak_year1_100(rng, solved_count, 100)
    longest_streak = sample_longest_streak_year1_100(rng, solved_count, current_streak, 100)

    if longest_streak < current_streak:
        longest_streak = current_streak

    solved_count = min(solved_count, solved_cap)
    current_streak = min(current_streak, solved_count, 100)
    longest_streak = min(longest_streak, solved_count, 100)
    return solved_count, current_streak, longest_streak, solved_cap


def sample_submission_count_for_profile(rng: random.Random, solved_count: int,
                                        problem_count: int) -> int:
    if solved_count <= 0:
        if rng.random() < 0.03:
            return 1
        return 0

    # Fewer solved problems usually means only a handful of attempts, while
    # higher solved counts get a longer but still low-skew failure tail.
    retry_budget = max(1, int(round(solved_count * rng.uniform(0.10, 0.65))))
    retries = triangular_int(rng, 0, retry_budget, 0)
    upper_bound = max(solved_count, min(problem_count * 3, solved_count + retry_budget))
    return min(upper_bound, solved_count + retries)


def summarize_bucket(value: int, buckets: list[tuple[str, int, int]],
                     counters: dict[str, int]) -> None:
    for label, low, high in buckets:
        if low <= value <= high:
            counters[label] += 1
            return
    counters["overflow"] = counters.get("overflow", 0) + 1


def format_summary(title: str, counters: dict[str, int], total: int) -> str:
    parts = []
    for label, count in counters.items():
        share = 0.0 if total == 0 else (count / total) * 100.0
        parts.append(f"{label}={share:.1f}%")
    return f"{title}: " + ", ".join(parts)


def emit_users_and_submissions(output_dir: Path, args: argparse.Namespace,
                               contests: list[dict], rng: random.Random) -> dict[str, object]:
    user_path = output_dir / "user.csv"
    sub_path = output_dir / "submission.csv"
    acc_path = output_dir / "accepted_submission.csv"
    guard_path = output_dir / "user_problem_guard.csv"
    full_dataset = args.dataset == "full"

    base_submission_time = (contests[0]["end"] if contests else datetime.now()) + timedelta(hours=args.batch_hours)

    solved_summary = {label: 0 for label, _, _ in SOLVED_SUMMARY_BUCKETS}
    current_summary = {label: 0 for label, _, _ in CURRENT_SUMMARY_BUCKETS}
    longest_summary = {label: 0 for label, _, _ in LONGEST_SUMMARY_BUCKETS}
    generated_submissions = 0
    total_solved = 0
    total_current = 0
    total_longest = 0

    with user_path.open("w", newline="", encoding="utf-8") as user_fh, \
            (sub_path.open("w", newline="", encoding="utf-8") if full_dataset else nullcontext()) as sub_fh, \
            (acc_path.open("w", newline="", encoding="utf-8") if full_dataset else nullcontext()) as acc_fh, \
            (guard_path.open("w", newline="", encoding="utf-8") if full_dataset else nullcontext()) as guard_fh:

        user_writer = csv.writer(user_fh)
        sub_writer = csv.writer(sub_fh) if full_dataset else None
        acc_writer = csv.writer(acc_fh) if full_dataset else None
        guard_writer = csv.writer(guard_fh) if full_dataset else None

        active_users = args.total_users - args.inactive_users
        if args.user_model == "baseline":
            if active_users > 0:
                mean_submissions = args.total_submissions / active_users
            else:
                mean_submissions = 0.0
            remaining_users = active_users
            remaining_submissions = args.total_submissions
        else:
            mean_submissions = 0.0
            remaining_users = 0
            remaining_submissions = 0

        for uid in range(1, args.total_users + 1):
            if args.user_model == "baseline":
                is_active = uid > args.inactive_users
                if not is_active:
                    solved_count = 0
                    current_streak = 0
                    longest_streak = 0
                    submissions_for_user = 0
                else:
                    remaining_users -= 1
                    min_required = 1 if remaining_submissions > remaining_users else 0
                    max_allowed = remaining_submissions - remaining_users
                    submissions_for_user = gaussian_int(
                        rng,
                        mean_submissions,
                        max(0.1, args.submission_stddev),
                        min_required,
                        max_allowed,
                    )
                    remaining_submissions -= submissions_for_user

                    accepted_target = int(round(submissions_for_user * args.accepted_ratio))
                    accepted_target = min(accepted_target, submissions_for_user, args.problem_count)
                    solved_count = accepted_target

                    if solved_count > 0:
                        current_streak = min(90, solved_count)
                        longest_streak = min(180, solved_count + rng.randint(0, max(0, solved_count)))
                    else:
                        current_streak = 0
                        longest_streak = 0
            else:
                solved_count, current_streak, longest_streak, _ = sample_user_profile_year1_100(
                    rng,
                    args.problem_count,
                )
                submissions_for_user = sample_submission_count_for_profile(
                    rng,
                    solved_count,
                    args.problem_count,
                )

            if solved_count > 0:
                streak_last = base_submission_time.strftime("%Y-%m-%d %H:%M:%S")
            else:
                streak_last = "\\N"

            user_writer.writerow([
                f"perf_user_{uid}",
                "perf-pass",
                solved_count,
                streak_last,
                current_streak,
                longest_streak,
            ])

            summarize_bucket(solved_count, SOLVED_SUMMARY_BUCKETS, solved_summary)
            summarize_bucket(current_streak, CURRENT_SUMMARY_BUCKETS, current_summary)
            summarize_bucket(longest_streak, LONGEST_SUMMARY_BUCKETS, longest_summary)
            total_solved += solved_count
            total_current += current_streak
            total_longest += longest_streak

            if not full_dataset or submissions_for_user == 0:
                continue

            generated_submissions += submissions_for_user
            accepted_problems = (
                sorted(rng.sample(range(1, args.problem_count + 1), k=solved_count))
                if solved_count > 0 else []
            )

            for attempt in range(submissions_for_user):
                if attempt < solved_count:
                    problem_id = accepted_problems[attempt]
                    result = "ACCEPTED"
                else:
                    problem_id = rng.randint(1, args.problem_count)
                    result = rng.choice([
                        "WRONG_ANSWER",
                        "TIME_LIMIT",
                        "MEMORY_LIMIT",
                        "RUNTIME_ERROR",
                        "COMPILATION_ERROR",
                    ])

                submitted_time = base_submission_time - timedelta(seconds=rng.randint(0, args.batch_hours * 3600))
                code = f"// user={uid} problem={problem_id} attempt={attempt}\npublic class Main {{ public static void main(String[] args) {{ System.out.println(\"{uid}-{attempt}\"); }} }}"

                sub_writer.writerow([
                    uid,
                    submitted_time.strftime("%Y-%m-%d %H:%M:%S"),
                    problem_id,
                    code,
                    hash_code(code),
                    result,
                ])

                if result == "ACCEPTED":
                    acc_writer.writerow([
                        uid,
                        problem_id,
                        submitted_time.strftime("%Y-%m-%d %H:%M:%S"),
                    ])
                    guard_writer.writerow([
                        uid,
                        problem_id,
                    ])

    return {
        "generated_submissions": generated_submissions,
        "avg_solved": 0.0 if args.total_users == 0 else total_solved / args.total_users,
        "avg_current": 0.0 if args.total_users == 0 else total_current / args.total_users,
        "avg_longest": 0.0 if args.total_users == 0 else total_longest / args.total_users,
        "solved_summary": solved_summary,
        "current_summary": current_summary,
        "longest_summary": longest_summary,
    }


def main() -> None:
    args = parse_args()
    rng = random.Random(args.random_seed)

    output_dir: Path = args.output.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    base_time = datetime.now()
    contests = emit_contests(output_dir, args.contest_count, base_time, rng) if args.dataset == "full" else []
    if args.dataset == "full":
        emit_problems(output_dir, contests, args.problem_count, rng)
    summary = emit_users_and_submissions(output_dir, args, contests, rng)

    print(f"CSV generation complete. Files written to {output_dir}")
    print(
        f"user_model={args.user_model}, dataset={args.dataset}, "
        f"users={args.total_users}, problems={args.problem_count}"
    )
    print(
        "generated submissions="
        f"{summary['generated_submissions']:,}, "
        f"avg solved={summary['avg_solved']:.2f}, "
        f"avg current={summary['avg_current']:.2f}, "
        f"avg longest={summary['avg_longest']:.2f}"
    )
    print(format_summary("solved_count", summary["solved_summary"], args.total_users))
    print(format_summary("current_streak", summary["current_summary"], args.total_users))
    print(format_summary("longest_streak", summary["longest_summary"], args.total_users))


if __name__ == "__main__":
    main()
