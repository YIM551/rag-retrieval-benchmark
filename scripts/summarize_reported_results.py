"""Check reported aggregates; does not reproduce the historical benchmark."""
import csv
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "data" / "reported-latency.csv"
with path.open(encoding="utf-8", newline="") as stream:
    rows = list(csv.DictReader(stream))
baseline = next(row for row in rows if row["mode"] == "SEQUENTIAL")
print("Reported aggregate differences; positive values mean reduction, not significance.")
for row in rows:
    print(row["mode"])
    for metric in ("total_mean_ms", "total_p95_ms", "retrieval_mean_ms", "dense_mean_ms"):
        reduction = 100 * (1 - float(row[metric]) / float(baseline[metric]))
        print(f"  {metric}: {reduction:.2f}%")
