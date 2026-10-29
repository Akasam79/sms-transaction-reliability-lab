#!/usr/bin/env python3
"""Summarize the committed synthetic request CSV files without external packages."""

import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path


def nearest_rank(values, fraction):
    return sorted(values)[max(0, math.ceil(fraction * len(values)) - 1)]


def summarize(directory):
    metadata = json.loads((directory / "summary.json").read_text())
    rows = list(csv.DictReader((directory / "raw_requests.csv").open()))
    grouped = defaultdict(list)
    for row in rows:
        grouped[row["scenario"]].append(row)
    return {
        "requests_per_scenario": metadata["requests_per_scenario"],
        "trials": metadata["trials"],
        "code_commit": metadata["git_commit"],
        "all_trial_invariants_passed": all(
            item["duplicate_settlements"] == 0
            and item["expired_without_settlement"]
            and item["rollback_preserved_balances"]
            for item in metadata["summaries"]
        ),
        "scenarios": {
            name: {
                "requests": len(group),
                "http_200": sum(row["http_status"] == "200" for row in group),
                "http_500": sum(row["http_status"] == "500" for row in group),
                "latency_median_ms": round(statistics.median(float(row["latency_ms"]) for row in group), 2),
                "latency_p95_ms": round(nearest_rank([float(row["latency_ms"]) for row in group], 0.95), 2),
            }
            for name, group in grouped.items()
        },
    }


if __name__ == "__main__":
    root = Path("experiments")
    report = [summarize(root / name) for name in ("results_10", "results_30")]
    (root / "aggregate.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
