#!/usr/bin/env python3
"""Analyze RAG timing CSVs emitted by extract_rag_timing.ps1."""
from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd

DEFAULT_PATH = Path("rag_timing_metrics.csv")


def load_dataframe(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    df.columns = [c.strip() for c in df.columns]
    normalized = df.copy()
    normalized.columns = [c.lower() for c in normalized.columns]

    numeric_candidates = [c for c in normalized.columns if c.endswith("ms")] + [
        col for col in normalized.columns if col in {"threadpoolsize"}
    ]
    for col in numeric_candidates:
        normalized[col] = pd.to_numeric(normalized[col], errors="coerce")

    return normalized


def summarize_totals(df: pd.DataFrame) -> pd.DataFrame:
    required = {"mode", "totalms"}
    if not required.issubset(df.columns):
        missing = ", ".join(sorted(required - set(df.columns)))
        raise SystemExit(f"Missing required columns for total summary: {missing}")

    summary = df.groupby("mode")["totalms"].agg(
        count="count",
        mean="mean",
        median="median",
        min="min",
        max="max",
        p95=lambda s: s.quantile(0.95),
    )
    return summary


def summarize_stages(df: pd.DataFrame) -> pd.DataFrame | None:
    stage_cols = ["densems", "sparsems", "mergems", "rerankms", "llmms", "guardrailms"]
    present = [c for c in stage_cols if c in df.columns]
    if not present:
        return None

    summary = df.groupby("mode")[present].agg(["mean", "median"])
    summary.columns = [f"{col}_{stat}" for col, stat in summary.columns]
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="?", default=DEFAULT_PATH, type=Path, help="Path to rag_timing_metrics.csv")
    args = parser.parse_args()

    df = load_dataframe(args.csv)

    print("=== Total Latency Summary by Mode (ms) ===")
    totals = summarize_totals(df)
    print(totals)

    stage_summary = summarize_stages(df)
    if stage_summary is not None:
        print("\n=== Stage-wise Latency Summary by Mode (ms) ===")
        print(stage_summary)
    else:
        print("\n(No stage-wise columns found; only total latency summarized.)")


if __name__ == "__main__":
    main()
