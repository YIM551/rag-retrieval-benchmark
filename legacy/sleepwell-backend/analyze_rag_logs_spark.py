"""PySpark analyzer for RAG benchmark logs/CSVs."""

import argparse
import time
from pathlib import Path

from pyspark.sql import SparkSession, functions as F


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze RAG benchmarks with PySpark (local mode).")
    parser.add_argument("--input", type=Path, required=True, help="Input CSV with latency measurements")
    parser.add_argument("--out", type=Path, help="Optional output CSV path")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    spark = (
        SparkSession.builder.appName("rag-log-analysis")
        .master("local[*]")
        .getOrCreate()
    )

    start = time.time()
    df = spark.read.csv(str(args.input), header=True, inferSchema=True)

    # Normalize column names to lower snake-like
    for column in df.columns:
        df = df.withColumnRenamed(column, column.lower())

    latency_col = None
    for cand in ["latency_ms", "latencym", "latency"]:
        if cand in df.columns:
            latency_col = cand
            break
    if latency_col is None:
        raise SystemExit("Cannot find latency column")

    df = df.withColumn("latency_ms", df[latency_col].cast("double"))

    group_cols = [c for c in ["mode", "strategy", "concurrency", "threads", "granularity"] if c in df.columns]
    if not group_cols:
        group_cols = ["mode"]

    summary = (
        df.groupBy(*group_cols)
        .agg(
            F.avg("latency_ms").alias("mean_total_ms"),
            F.expr("percentile_approx(latency_ms, 0.95)").alias("p95_total_ms"),
            F.count("*").alias("count"),
        )
        .orderBy(group_cols)
    )

    elapsed = time.time() - start
    print(f"Processed {summary.count()} groups from {args.input} in {elapsed:.3f}s")
    summary.show(truncate=False)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        summary.coalesce(1).write.mode("overwrite").option("header", True).csv(str(args.out))
        print(f"Saved Spark summary to {args.out}")

    spark.stop()


if __name__ == "__main__":
    main()
