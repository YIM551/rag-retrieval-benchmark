# RAG Benchmarking & Timing Guide

## Execution mode
- Configure retrieval execution via the environment variable:
  - `RAG_RETRIEVAL_EXECUTION_MODE=SEQUENTIAL`
  - `RAG_RETRIEVAL_EXECUTION_MODE=PARALLEL`
- Or rely on `src/main/resources/application-rag.yml` where `rag.retrieval.executionMode` defaults to `PARALLEL`.
- Parallel tuning knobs:
  - `rag.retrieval.executorThreads` controls the thread pool size for retrieval work.
  - `rag.retrieval.nestedDenseSparse` toggles inner dense/sparse parallelism inside each expansion (true = nested futures, false = sequential inside each expansion thread).

## Running benchmarks
1. Start the backend in the desired mode (ensure the env var matches).
2. From the project root run:
   - `tools/benchmark_rag_sequential.ps1`
   - `tools/benchmark_rag_parallel.ps1`
3. Each script sends a fixed set of queries to `/api/v1/rag/query` and writes latency rows to `rag_benchmark_sequential.csv` and `rag_benchmark_parallel.csv` respectively.

## Extracting timing metrics
- Ensure the application log contains `RAG_TIMING_METRIC` entries.
- Run: `tools/extract_rag_timing.ps1 -LogPath .\logs\spring.log -OutPath .\rag_timing_metrics.csv`
- The script parses the structured log lines into a CSV with per-stage timings.

## Analyzing results
- Compare sequential vs parallel latency summaries: `python analyze_rag_benchmarks.py`
  - Outputs a console summary, `rag_benchmark_comparison_by_query.csv`, and `rag_benchmark_summary.txt`.
- Analyze per-stage timings and plots: `python analyze_rag_timing_and_plot.py`
  - Saves `rag_timing_metrics_enriched.csv`, plus charts `rag_stage_time_by_mode.png` and `rag_total_time_by_mode.png` for visualization.

### Parallel strategy benchmark (Version A vs B + executor threads)

```powershell
pwsh ./tools/benchmark_rag_parallel_strategies.ps1 `
    -ThreadCounts @(1, 2, 4, 8) `
    -NestedOptions @($true, $false)
```

This runs the RAG pipeline in PARALLEL mode for multiple thread pool sizes and both strategies:

- `nestedDenseSparse = true`: Version A (nested dense+sparse parallelism inside each expansion thread).
- `nestedDenseSparse = false`: Version B (expansion-only parallelism; dense and sparse run sequentially inside each expansion).

For every combination it writes:

- logs: `rag_parallel_t{threads}_nested{true/false}.log`
- timing CSV: `rag_benchmark_parallel_t{threads}_nested{true/false}.csv`
