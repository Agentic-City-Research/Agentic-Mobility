from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarize DeepSeek token usage recorded by the MATSim run."
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("output/llm-101-policy-v2-share-1pct-cache-v2"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    decisions = pd.read_csv(args.output_dir / "llm-decisions.csv", low_memory=False)
    required = {
        "prompt_tokens",
        "prompt_cache_hit_tokens",
        "prompt_cache_miss_tokens",
        "completion_tokens",
        "total_tokens",
    }
    missing = sorted(required - set(decisions.columns))
    if missing:
        raise ValueError("Run predates token auditing; missing columns: " + ", ".join(missing))

    usage = decisions.loc[decisions["total_tokens"] > 0].copy()
    if usage.empty:
        print("No billed token usage found (the run may be a dry-run).")
        return 0

    hit = int(usage["prompt_cache_hit_tokens"].sum())
    miss = int(usage["prompt_cache_miss_tokens"].sum())
    completion = int(usage["completion_tokens"].sum())
    total = int(usage["total_tokens"].sum())
    input_total = hit + miss
    hit_rate = hit / input_total if input_total else 0.0
    estimated_cny = hit * 0.00000002 + miss * 0.000001 + completion * 0.000002

    print(f"Successful metered responses: {len(usage):,}")
    print(f"Input cache hit tokens:     {hit:,}")
    print(f"Input cache miss tokens:    {miss:,}")
    print(f"Completion tokens:          {completion:,}")
    print(f"Total tokens:               {total:,}")
    print(f"Input cache hit rate:       {hit_rate:.2%}")
    print(f"Estimated DeepSeek cost:    CNY {estimated_cny:,.4f}")
    print("Cost estimate uses the 2026-08 deepseek-v4-flash CNY rates.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
