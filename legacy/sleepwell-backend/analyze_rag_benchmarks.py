"""
Analyze RAG benchmark CSV files containing Mode, Query, and LatencyMs columns.
Computes per-mode statistics, overall speedup, and per-query comparisons.
"""

import argparse
import time
from pathlib import Path
from typing import Iterable, List

import pandas as pd

DEFAULT_PATHS = [
    Path("rag_benchmark_sequential.csv"),
    Path("rag_benchmark_parallel.csv"),
]

LAT_COL_CANDIDATES = ["latency_ms", "LatencyMs", "latency"]
COL_RENAMES = {
    "Mode": "mode",
    "Strategy": "strategy",
    "Threads": "threads",
    "MaxConcurrency": "concurrency",
    "Concurrency": "concurrency",
    "Granularity": "granularity",
    "LatencyMs": "latency_ms",
    "Latency": "latency_ms",
    "Query": "query",
}


def load_data(paths: Iterable[Path]) -> pd.DataFrame:
    """Load and concatenate benchmark CSV files."""
    frames: List[pd.DataFrame] = []
    for path in paths:
        if not Path(path).exists():
            raise SystemExit(f"File not found: {path}")
        frames.append(pd.read_csv(path))
    if not frames:
        raise SystemExit("No input files provided")
    df = pd.concat(frames, ignore_index=True)
    return normalize_columns(df)


def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Normalize column names to snake_case for downstream grouping."""
    renamed = df.rename(columns=COL_RENAMES)
    latency_col = next((col for col in LAT_COL_CANDIDATES if col in renamed.columns), None)
    if latency_col is None:
        raise SystemExit("Could not find latency column in data")
    renamed["latency_ms"] = pd.to_numeric(renamed[latency_col], errors="coerce")
    return renamed


def aggregate_latency(df: pd.DataFrame) -> pd.DataFrame:
    """Group by mode/strategy/concurrency/threads/granularity and compute mean+p95."""
    group_cols = [col for col in ["mode", "strategy", "concurrency", "threads", "granularity"] if col in df.columns]
    if not group_cols:
        group_cols = ["mode"]
    filtered = df[df.get("StatusCode", df.get("status", pd.Series(200))).fillna(200) == 200]
    grouped = filtered.groupby(group_cols)["latency_ms"].agg([
        ("mean_total_ms", "mean"),
        ("p95_total_ms", lambda s: s.quantile(0.95)),
        ("count", "count"),
    ])
    return grouped.reset_index()


def describe_mode(df: pd.DataFrame, mode: str) -> pd.Series:
    """Return descriptive stats for a given mode including p95."""
    subset = df[df["mode"] == mode]
    subset = subset[subset.get("StatusCode", subset.get("status", pd.Series(200))).fillna(200) == 200]
    if subset.empty:
        raise ValueError(f"No rows available for mode: {mode}")
    stats = subset["latency_ms"].describe(percentiles=[0.25, 0.5, 0.75])
    stats["p95"] = subset["latency_ms"].quantile(0.95)
    return stats


def analyze_by_mode(df: pd.DataFrame) -> None:
    """Print descriptive statistics per mode."""
    modes = df["mode"].unique()
    print("=== Summary by mode ===")
    for mode in modes:
        stats = describe_mode(df, mode)
        print(f"\nMode: {mode}")
        print(stats.to_string())


def analyze_overall(df: pd.DataFrame) -> None:
    """Compute and print overall speedup between SEQUENTIAL and PARALLEL modes."""
    seq = df[(df["mode"].str.upper() == "SEQUENTIAL") & (df.get("StatusCode", df.get("status", pd.Series(200))).fillna(200) == 200)]
    par = df[(df["mode"].str.upper() == "PARALLEL") & (df.get("StatusCode", df.get("status", pd.Series(200))).fillna(200) == 200)]

    if seq.empty or par.empty:
        print("Skipping overall comparison because SEQUENTIAL or PARALLEL rows are missing.")
        return

    mean_seq = seq["latency_ms"].mean()
    mean_par = par["latency_ms"].mean()
    speedup = mean_seq / mean_par
    improvement_pct = (mean_seq - mean_par) / mean_seq * 100

    print("\n=== Overall comparison ===")
    print(f"Mean SEQ latency: {mean_seq:.2f} ms")
    print(f"Mean PAR latency: {mean_par:.2f} ms")
    print(f"Speedup (SEQ/PAR): x{speedup:.3f}")
    if improvement_pct >= 0:
        print(f"PARALLEL is {improvement_pct:.1f}% faster than SEQUENTIAL")
    else:
        print(f"PARALLEL is {abs(improvement_pct):.1f}% slower than SEQUENTIAL")


def analyze_per_query(df: pd.DataFrame) -> pd.DataFrame:
    """Return pivoted per-query stats with speedup columns."""
    ok = df[df.get("StatusCode", df.get("status", pd.Series(200))).fillna(200) == 200]
    if "query" not in ok.columns:
        return pd.DataFrame()
    grouped = ok.groupby(["query", "mode"]).agg(
        count=("latency_ms", "count"),
        mean=("latency_ms", "mean"),
        std=("latency_ms", "std"),
        min=("latency_ms", "min"),
        max=("latency_ms", "max"),
    )

    pivot = grouped.unstack("mode")
    pivot.columns = [f"{stat}_{mode.lower()}" for stat, mode in pivot.columns]

    if "mean_sequential" in pivot.columns and "mean_parallel" in pivot.columns:
        pivot["speedup_mean"] = pivot["mean_sequential"] / pivot["mean_parallel"]
        pivot["delta_mean_ms"] = pivot["mean_sequential"] - pivot["mean_parallel"]

    sort_by = "speedup_mean" if "speedup_mean" in pivot.columns else pivot.columns[-1]
    pivot_sorted = pivot.sort_values(by=sort_by, ascending=False).round(2)
    return pivot_sorted


def print_per_query(pivot: pd.DataFrame) -> None:
    """Pretty print the per-query comparison table."""
    if pivot.empty:
        print("\n(No per-query data available)")
        return
    print("\n=== Per-query comparison (top rows) ===")
    display_cols = [col for col in pivot.columns if col.startswith("mean_") or col in {"speedup_mean", "delta_mean_ms"}]
    preview = pivot[display_cols].head(10)
    with pd.option_context("display.max_rows", None, "display.max_columns", None):
        print(preview)


def run_analysis(inputs: Iterable[Path], out: Path | None = None) -> pd.DataFrame:
    start = time.perf_counter()
    df = load_data(inputs)
    summary = aggregate_latency(df)
    elapsed = time.perf_counter() - start
    print(f"Processed {len(df)} rows in {elapsed:.3f}s")
    print(summary)
    if out:
        Path(out).parent.mkdir(parents=True, exist_ok=True)
        summary.to_csv(out, index=False)
        print(f"Saved summary to {out}")
    return df


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze RAG benchmark CSVs and compute latency summaries.")
    parser.add_argument("--input", nargs="*", type=Path, help="Input CSV files", default=None)
    parser.add_argument("--out", type=Path, help="Optional output CSV for summary", default=None)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    inputs = args.input or DEFAULT_PATHS
    df = run_analysis(inputs, args.out)
    analyze_by_mode(df)
    analyze_overall(df)
    per_query = analyze_per_query(df)
    print_per_query(per_query)
    if not per_query.empty:
        per_query.to_csv("rag_benchmark_comparison_by_query.csv", encoding="utf-8")


if __name__ == "__main__":
    main()
