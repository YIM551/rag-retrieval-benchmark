"""Reaggregate recovered 2025 RAG_TIMING records without calling a service.

Added during the 2026 portfolio audit. Preserves the original pandas linear
percentile definition. These records have no status/warm-up/request identifiers.
"""
import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

STRATEGIES = {"SEQUENTIAL": "SEQUENTIAL", "PARALLEL_OLD": "V1_OLD", "PARALLEL_SYNC": "ASYNC"}
TIMES = ("totalMs", "queryExpansionMs", "retrievalMs", "denseMs", "sparseMs", "mergeMs",
         "rerankMs", "contextMs", "llmMs", "guardrailMs")
CONFIG = ("strategy", "threadPoolSize", "parallelGranularity", "nestedDenseSparse")
FIELDS = {"mode", *CONFIG, *TIMES}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


def validate(record):
    if not isinstance(record, dict) or set(record) != FIELDS:
        raise ValueError("unexpected timing schema")
    mode = record["mode"]
    if not isinstance(mode, str) or mode not in STRATEGIES or record["strategy"] != STRATEGIES[mode]:
        raise ValueError("mode and strategy are inconsistent")
    if type(record["threadPoolSize"]) is not int or record["threadPoolSize"] < 1:
        raise ValueError("invalid thread pool size")
    if record["parallelGranularity"] not in ("FINE", "COARSE") or type(record["nestedDenseSparse"]) is not bool:
        raise ValueError("invalid retrieval configuration")
    for key in TIMES:
        value = record[key]
        try:
            valid = type(value) in (int, float) and math.isfinite(value) and value >= 0
        except OverflowError:
            valid = False
        if not valid:
            raise ValueError("timings must be finite nonnegative numbers")
    # Dense/sparse/merge are accumulated task times, possibly overlapping.
    for key in ("queryExpansionMs", "retrievalMs", "contextMs", "llmMs", "guardrailMs"):
        if record[key] > record["totalMs"]:
            raise ValueError("wall-clock stage exceeds server total")


def read_records(path):
    records = []
    with Path(path).open(encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            if "[RAG_TIMING]" in line:
                line = line.split("[RAG_TIMING]", 1)[1]
            elif not line.lstrip().startswith("{"):
                continue
            try:
                record = json.loads(line, object_pairs_hook=unique_object)
                validate(record)
            except (ValueError, TypeError, OverflowError):
                raise ValueError(f"invalid timing record at line {line_number}") from None
            records.append(record)
    if not records:
        raise ValueError("no timing records")
    return records


def percentile_linear(values, q=.95):
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summarize(records):
    if not records:
        raise ValueError("no timing records")
    groups = defaultdict(list)
    configs = {}
    for record in records:
        validate(record)
        mode = record["mode"]
        config = tuple(record[key] for key in CONFIG)
        if mode in configs and config != configs[mode]:
            raise ValueError("mixed configurations within one mode")
        configs[mode] = config
        groups[mode].append(record)
    modes = {}
    for mode, rows in sorted(groups.items()):
        modes[mode] = {
            "n": len(rows), "configuration": dict(zip(CONFIG, configs[mode])),
            "timings": {key: {"mean_ms": statistics.mean(row[key] for row in rows),
                              "p95_ms": percentile_linear([row[key] for row in rows])}
                        for key in TIMES},
        }
    return {
        "scope": "reaggregation_of_historical_server_timings",
        "percentile_method": "linear_interpolation_at_(n-1)*q",
        "records": len(records), "modes": modes,
        "error_rate": None, "throughput_rps": None, "concurrent_users": None,
        "warmup_excluded": None,
        "limitations": ["No outcome/request identifiers in source timing records",
                        "Warm-up and cache state cannot be recovered from these fields",
                        "Task durations can overlap; use retrievalMs as measured wall-clock span"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--csv", type=Path)
    args = parser.parse_args()
    try:
        records = read_records(args.input)
        summary = summarize(records)
        if args.summary:
            args.summary.parent.mkdir(parents=True, exist_ok=True)
            args.summary.write_text(json.dumps(summary, indent=2, allow_nan=False)+"\n", encoding="utf-8")
        if args.csv:
            args.csv.parent.mkdir(parents=True, exist_ok=True)
            with args.csv.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=["mode", *CONFIG, *TIMES])
                writer.writeheader()
                writer.writerows(records)
        print(json.dumps(summary, indent=2, allow_nan=False))
    except (ValueError, OSError) as exc:
        parser.exit(2, f"Cannot reaggregate timings: {exc}\n")


if __name__ == "__main__":
    main()
