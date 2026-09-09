"""Validate a NEW timing JSONL contract; not a recovered historical log parser."""
import argparse
import json
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path

MODES = {"SEQUENTIAL", "PARALLEL_OLD", "PARALLEL_SYNC"}
COHORT = ("actual_mode", "corpus_id", "config_id", "cache_state", "timing_scope")
REQUIRED = {"schema_version", "run_id", "request_id", "query_id", "phase",
            "outcome", "duration_ms", *COHORT}
STAGES = {"dense", "sparse", "retrieval", "rerank", "llm"}


def number(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return False
    try:
        return math.isfinite(value) and value >= 0
    except OverflowError:
        return False


def validate(record):
    if not isinstance(record, dict):
        raise ValueError("record must be an object")
    if REQUIRED - record.keys() or record.keys() - REQUIRED - {"stage_ms"}:
        raise ValueError("missing or unknown fields")
    if type(record["schema_version"]) is not int or record["schema_version"] != 1:
        raise ValueError("schema_version must be 1")
    for field in ("run_id", "request_id", "query_id", "corpus_id", "config_id"):
        if not isinstance(record[field], str) or not record[field].strip():
            raise ValueError(f"{field} must be a nonempty identifier")
    choices = {"actual_mode": MODES, "phase": {"warmup", "measurement"},
               "outcome": {"success", "error", "timeout"},
               "cache_state": {"cold", "warm", "disabled", "unknown"},
               "timing_scope": {"server_total", "client_e2e"}}
    for field, allowed in choices.items():
        if not isinstance(record[field], str) or record[field] not in allowed:
            raise ValueError(f"invalid {field}")
    if not number(record["duration_ms"]):
        raise ValueError("duration_ms must be finite and nonnegative")
    stages = record.get("stage_ms", {})
    if not isinstance(stages, dict) or stages.keys() - STAGES:
        raise ValueError("invalid stage names")
    if stages and record["timing_scope"] != "server_total":
        raise ValueError("server stages require server_total scope")
    for stage, value in stages.items():
        if not number(value) or value > record["duration_ms"]:
            raise ValueError(f"invalid {stage} duration")


def load_records(path):
    records = []
    with Path(path).open(encoding="utf-8") as stream:
        for line_no, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                validate(record)
            except (ValueError, TypeError) as exc:
                # Do not echo raw logs: these might contain personal information.
                raise ValueError(f"line {line_no}: {exc}") from None
            records.append(record)
    if not records:
        raise ValueError("no timing records")
    return records


def summary(values):
    if not values:
        return {"n": 0, "mean_ms": None, "p95_ms": None}
    ordered = sorted(values)
    return {"n": len(values), "mean_ms": statistics.mean(values),
            "p95_ms": ordered[math.ceil(0.95 * len(values)) - 1]}


def analyze(records, expected_mode=None):
    if not records:
        raise ValueError("no timing records")
    groups = defaultdict(list)
    seen = set()
    for record in records:
        validate(record)
        key = (record["run_id"], record["request_id"])
        if key in seen:
            raise ValueError("duplicate run_id/request_id")
        seen.add(key)
        if expected_mode is not None and record["actual_mode"] != expected_mode:
            raise ValueError("actual_mode differs from expected mode")
        groups[record["run_id"]].append(record)
    result = []
    for run_id, group in sorted(groups.items()):
        if len({tuple(row[field] for field in COHORT) for row in group}) != 1:
            raise ValueError(f"run {run_id}: mixed mode/config/corpus/cache/scope")
        measured = [r for r in group if r["phase"] == "measurement"]
        counts = Counter(r["outcome"] for r in measured)
        successful = [r for r in measured if r["outcome"] == "success"]
        stage_stats = {}
        for stage in sorted(STAGES):
            values = [r["stage_ms"][stage] for r in successful
                      if stage in r.get("stage_ms", {})]
            if values:
                stage_stats[stage] = summary(values)
        warnings = []
        if group[0]["cache_state"] == "unknown":
            warnings.append("cache state unknown; controlled comparison unavailable")
        if counts["error"] + counts["timeout"]:
            warnings.append("success latency excludes failures; read failure rate together")
        result.append({"run_id": run_id, **{k: group[0][k] for k in COHORT},
                       "warmup_excluded": len(group) - len(measured),
                       "attempts": len(measured), "success": counts["success"],
                       "errors": counts["error"], "timeouts": counts["timeout"],
                       "failure_rate": ((counts["error"] + counts["timeout"]) / len(measured)
                       if measured else None),
                       "unique_queries": len({r["query_id"] for r in measured}),
                       "success_latency": summary([r["duration_ms"] for r in successful]),
                       "success_stage_latency": stage_stats, "warnings": warnings})
    return {"contract_version": 1, "percentile_method": "nearest_rank: ceil(0.95*n)",
            "throughput_rps": None,
            "throughput_note": "Not measured: observation window and workload schedule absent.",
            "runs": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--expected-mode", choices=sorted(MODES))
    args = parser.parse_args()
    try:
        result = analyze(load_records(args.input), args.expected_mode)
    except (ValueError, OSError) as exc:
        parser.exit(2, f"Invalid timing input: {exc}\n")
    print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))


if __name__ == "__main__":
    main()
