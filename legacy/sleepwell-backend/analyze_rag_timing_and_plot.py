import pandas as pd
import matplotlib.pyplot as plt

TIMING_PATH = "rag_timing_metrics.csv"


def load_timing() -> pd.DataFrame:
    try:
        df = pd.read_csv(TIMING_PATH)
    except FileNotFoundError:
        raise SystemExit(f"File not found: {TIMING_PATH}")
    numeric_cols = [
        "TotalMs",
        "RewriteMs",
        "DenseMs",
        "SparseMs",
        "MergeMs",
        "RerankMs",
        "LlmMs",
    ]
    for col in numeric_cols:
        df[col] = pd.to_numeric(df[col], errors="coerce")
    df["RetrievalMs"] = df[["DenseMs", "SparseMs", "MergeMs", "RerankMs"]].sum(axis=1)
    df["RewriteRatio"] = df["RewriteMs"] / df["TotalMs"]
    df["RetrievalRatio"] = df["RetrievalMs"] / df["TotalMs"]
    df["LlmRatio"] = df["LlmMs"] / df["TotalMs"]
    return df


def mode_summary(df: pd.DataFrame) -> pd.DataFrame:
    summary = df.groupby("Mode")[
        ["TotalMs", "RewriteMs", "RetrievalMs", "LlmMs", "RewriteRatio", "RetrievalRatio", "LlmRatio"]
    ].mean()
    return summary


def plot_stage_time(summary: pd.DataFrame) -> None:
    stages = ["RewriteMs", "RetrievalMs", "LlmMs"]
    modes = summary.index.tolist()
    x = range(len(stages))
    width = 0.35

    fig, ax = plt.subplots()
    for idx, mode in enumerate(modes):
        offsets = [i + (idx * width) - (width / 2) for i in x]
        ax.bar(offsets, summary.loc[mode, stages], width=width, label=mode)

    ax.set_xticks(list(x))
    ax.set_xticklabels(["Rewrite", "Retrieval", "LLM"])
    ax.set_ylabel("Time (ms)")
    ax.set_title("Average stage time by mode")
    ax.legend()
    plt.tight_layout()
    plt.savefig("rag_stage_time_by_mode.png", dpi=200)


def plot_total_time(summary: pd.DataFrame) -> None:
    fig, ax = plt.subplots()
    ax.bar(summary.index, summary["TotalMs"], width=0.5)
    ax.set_ylabel("Time (ms)")
    ax.set_title("Average total time by mode")
    plt.tight_layout()
    plt.savefig("rag_total_time_by_mode.png", dpi=200)


def main() -> None:
    df = load_timing()
    df.to_csv("rag_timing_metrics_enriched.csv", index=False, encoding="utf-8")
    summary = mode_summary(df)
    print("=== Per-mode averages ===")
    print(summary[["TotalMs", "RewriteMs", "RetrievalMs", "LlmMs"]])
    print("\n=== Per-mode ratios ===")
    print(summary[["RewriteRatio", "RetrievalRatio", "LlmRatio"]])

    plot_stage_time(summary)
    plot_total_time(summary)


if __name__ == "__main__":
    main()
