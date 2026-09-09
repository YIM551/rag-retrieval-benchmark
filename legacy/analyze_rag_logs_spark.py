#!/usr/bin/env python3
"""Aggregate RAG benchmark CSV logs with PySpark (pandas fallback).

This script loads all CSV files under ./rag_results/** and produces summary
statistics (p50/p90/p95/p99) for scaling, granularity, and mode×concurrency
experiments. PySpark is preferred; pandas is used if Spark is unavailable.
"""
from __future__ import annotations

import glob
import os
import sys
from typing import Iterable, List

try:
    from pyspark.sql import SparkSession
    from pyspark.sql import functions as F
    from pyspark.sql import types as T
except Exception:  # noqa: BLE001 - Spark is optional
    SparkSession = None  # type: ignore


def discover_csv_files() -> List[str]:
    return sorted(glob.glob(os.path.join("rag_results", "**", "*.csv"), recursive=True))


# --------------------------- Spark implementation ---------------------------

def _create_spark_session() -> SparkSession:
    return (
        SparkSession.builder.appName("rag-log-analysis")
        .config("spark.sql.session.timeZone", "UTC")
        .getOrCreate()
    )


def _spark_read_all(spark: SparkSession, files: Iterable[str]):
    dfs = []
    for path in files:
        df = (
            spark.read.option("header", True)
            .option("inferSchema", True)
            .csv(path)
            .withColumn("source", F.lit(path))
        )
        dfs.append(df)
    if not dfs:
        return spark.createDataFrame([], schema=T.StructType([]))
    base_df = dfs[0]
    for df in dfs[1:]:
        base_df = base_df.unionByName(df, allowMissingColumns=True)
    return base_df


def _spark_quantiles(df, group_cols: List[str]):
    latency_col = "latency_ms"
    quantiles = [0.5, 0.9, 0.95, 0.99]
    agg = df.groupBy(*group_cols).agg(
        F.expr(f"percentile_approx({latency_col}, 0.5) as p50"),
        F.expr(f"percentile_approx({latency_col}, 0.9) as p90"),
        F.expr(f"percentile_approx({latency_col}, 0.95) as p95"),
        F.expr(f"percentile_approx({latency_col}, 0.99) as p99"),
        F.count(F.col(latency_col)).alias("samples"),
    )
    return agg.orderBy(*group_cols)


def _spark_write(df, path: str):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    df.coalesce(1).write.mode("overwrite").option("header", True).csv(path)


# --------------------------- pandas implementation -------------------------

def _pandas_read_all(files: Iterable[str]):
    import pandas as pd

    frames = []
    for path in files:
        frames.append(pd.read_csv(path).assign(source=path))
    if not frames:
        return pd.DataFrame()
    return pd.concat(frames, ignore_index=True, sort=False)


def _pandas_quantiles(df, group_cols: List[str]):
    import pandas as pd

    if df.empty:
        return pd.DataFrame(columns=group_cols + ["p50", "p90", "p95", "p99", "samples"])

    def agg(group: pd.DataFrame):
        latency = group["latency_ms"].dropna()
        return pd.Series(
            {
                "p50": latency.quantile(0.5),
                "p90": latency.quantile(0.9),
                "p95": latency.quantile(0.95),
                "p99": latency.quantile(0.99),
                "samples": len(latency),
            }
        )

    return df.groupby(group_cols).apply(agg).reset_index().sort_values(group_cols)


def _pandas_write(df, path: str):
    import pandas as pd

    os.makedirs(os.path.dirname(path), exist_ok=True)
    df.to_csv(path, index=False)


# --------------------------- orchestration ---------------------------------

def run_with_spark(files: List[str]):
    spark = _create_spark_session()
    df = _spark_read_all(spark, files)
    if df.columns:
        df = df.withColumn("latency_ms", F.col("latency_ms").cast("double"))
    outputs = {
        "rag_analysis/summary_scaling.csv": ["mode", "threads", "concurrency"],
        "rag_analysis/summary_granularity.csv": ["mode", "granularity", "concurrency", "threads"],
        "rag_analysis/summary_modes_concurrency.csv": ["mode", "concurrency", "granularity", "threads"],
    }
    for path, group_cols in outputs.items():
        summary = _spark_quantiles(df, group_cols)
        _spark_write(summary, path)
    spark.stop()


def run_with_pandas(files: List[str]):
    import pandas as pd

    df = _pandas_read_all(files)
    if not df.empty and "latency_ms" in df.columns:
        df["latency_ms"] = pd.to_numeric(df["latency_ms"], errors="coerce")
    outputs = {
        "rag_analysis/summary_scaling.csv": ["mode", "threads", "concurrency"],
        "rag_analysis/summary_granularity.csv": ["mode", "granularity", "concurrency", "threads"],
        "rag_analysis/summary_modes_concurrency.csv": ["mode", "concurrency", "granularity", "threads"],
    }
    for path, group_cols in outputs.items():
        summary = _pandas_quantiles(df, group_cols)
        _pandas_write(summary, path)


def main():
    files = discover_csv_files()
    if not files:
        print("No benchmark CSV files found under ./rag_results.")
        return 1

    if SparkSession is not None:
        print("Using PySpark for aggregation.")
        run_with_spark(files)
    else:
        print("PySpark not available; using pandas fallback.")
        run_with_pandas(files)
    print("Aggregation complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
