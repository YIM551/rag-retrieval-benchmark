import pandas as pd

# 1) CSV 로드
seq_path = "rag_benchmark_sequential.csv"
par_path = "rag_benchmark_parallel.csv"

seq = pd.read_csv(seq_path)
par = pd.read_csv(par_path)

# LatencyMs가 문자열이면 숫자로 캐스팅
seq["LatencyMs"] = pd.to_numeric(seq["LatencyMs"], errors="coerce")
par["LatencyMs"] = pd.to_numeric(par["LatencyMs"], errors="coerce")

# 2) StatusCode 200만 사용 (에러 응답 제거)
seq_ok = seq[seq["StatusCode"] == 200].copy()
par_ok = par[par["StatusCode"] == 200].copy()

print("===== ROW COUNT =====")
print(f"SEQUENTIAL rows (ok): {len(seq_ok)}")
print(f"PARALLEL  rows (ok): {len(par_ok)}")
print()

# 3) 전체 통계 (엔드투엔드 평균 / p50 / p95 비교)
def summarize(df, label):
    desc = df["LatencyMs"].describe()
    p95 = df["LatencyMs"].quantile(0.95)
    print(f"=== {label} ===")
    print(desc)
    print(f"p95: {p95:.1f} ms")
    print()

summarize(seq_ok, "SEQUENTIAL")
summarize(par_ok, "PARALLEL")

overall_seq_mean = seq_ok["LatencyMs"].mean()
overall_par_mean = par_ok["LatencyMs"].mean()

speedup = overall_seq_mean / overall_par_mean
improvement_pct = (overall_seq_mean - overall_par_mean) / overall_seq_mean * 100

print("===== OVERALL SPEEDUP =====")
print(f"Mean SEQ latency : {overall_seq_mean:.1f} ms")
print(f"Mean PAR latency : {overall_par_mean:.1f} ms")
print(f"Speedup          : x{speedup:.3f}")
print(f"Improvement      : {improvement_pct:.1f}% faster")
print()

# 4) Query별 통계
seq_by_q = (
    seq_ok
    .groupby("Query")["LatencyMs"]
    .agg(["count", "mean", "std", "min", "max"])
    .add_prefix("seq_")
)

par_by_q = (
    par_ok
    .groupby("Query")["LatencyMs"]
    .agg(["count", "mean", "std", "min", "max"])
    .add_prefix("par_")
)

comparison = seq_by_q.join(par_by_q, how="outer")

# speedup / 차이(ms) 계산
comparison["speedup_mean"] = comparison["seq_mean"] / comparison["par_mean"]
comparison["delta_mean_ms"] = comparison["seq_mean"] - comparison["par_mean"]

# 보기 좋게 반올림
comparison_round = comparison.round(1)

print("===== PER QUERY SUMMARY (head) =====")
print(comparison_round.head())
print()

# 5) CSV로 저장 (보고서 / 노션에 첨부용)
comparison_round.to_csv("rag_benchmark_comparison_by_query.csv", encoding="utf-8-sig")

# 6) 전체 결과 한 줄 요약용 텍스트 파일 (발표/보고서에 그대로 붙이기)
summary_lines = [
    f"SEQUENTIAL mean latency: {overall_seq_mean:.1f} ms",
    f"PARALLEL   mean latency: {overall_par_mean:.1f} ms",
    f"Speedup: x{speedup:.3f}",
    f"Improvement: {improvement_pct:.1f}% faster",
]
with open("rag_benchmark_summary.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(summary_lines))
