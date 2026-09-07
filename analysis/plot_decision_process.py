from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


COLORS = {"car": "#4472C4", "pt": "#ED7D31", "walk": "#70AD47"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot iterative LLM-agent audit tables.")
    parser.add_argument(
        "--output-dir", type=Path,
        default=Path("output/llm-101-policy-v2-share-1pct-cache-v2")
    )
    parser.add_argument(
        "--figure-dir", type=Path,
        default=Path("analysis/figures-policy-v2-share-1pct-cache-v2")
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    decisions = pd.read_csv(args.output_dir / "llm-decisions.csv")
    experiences = pd.read_csv(args.output_dir / "llm-experiences.csv")
    args.figure_dir.mkdir(parents=True, exist_ok=True)

    fig, axes = plt.subplots(2, 2, figsize=(13, 9), constrained_layout=True)

    mode_share = (
        decisions.groupby(["target_iteration", "chosen_mode"]).size()
        .unstack(fill_value=0)
        .pipe(lambda frame: frame.div(frame.sum(axis=1), axis=0))
    )
    for mode in ("car", "pt", "walk"):
        if mode in mode_share:
            axes[0, 0].plot(
                mode_share.index, mode_share[mode], marker="o", markersize=3,
                label=mode, color=COLORS[mode]
            )
    axes[0, 0].set(title="LLM chosen-mode share", xlabel="Target iteration", ylabel="Share")
    axes[0, 0].set_ylim(0, 1)
    axes[0, 0].legend(frameon=False)

    mean_time = experiences.groupby("iteration")["travel_time_min"].mean()
    axes[0, 1].plot(mean_time.index, mean_time, color="#2F5597", marker="o", markersize=3)
    axes[0, 1].set(
        title="Mean experienced travel time", xlabel="Iteration",
        ylabel="Minutes per LLM agent-day"
    )

    ordered = decisions.sort_values(["person_id", "target_iteration"]).copy()
    ordered["changed"] = ordered.groupby("person_id")["chosen_mode"].transform(
        lambda values: values.ne(values.shift())
    )
    ordered.loc[ordered["target_iteration"] == 0, "changed"] = False
    changes = ordered.groupby("target_iteration")["changed"].mean()
    axes[1, 0].bar(changes.index, changes, color="#A5A5A5")
    axes[1, 0].set(
        title="Mode-change decision rate", xlabel="Target iteration",
        ylabel="Share changing mode"
    )
    axes[1, 0].set_ylim(0, 1)

    api = decisions.groupby("target_iteration").agg(
        mean_latency_ms=("latency_ms", "mean"), fallback_rate=("fallback", "mean")
    )
    axes[1, 1].plot(
        api.index, api["mean_latency_ms"], color="#7030A0", label="Mean latency"
    )
    axes[1, 1].set(
        title="DeepSeek reliability and latency", xlabel="Target iteration",
        ylabel="Latency (ms)"
    )
    fallback_axis = axes[1, 1].twinx()
    fallback_axis.plot(
        api.index, api["fallback_rate"], color="#C00000", linestyle="--",
        label="Fallback rate"
    )
    fallback_axis.set_ylabel("Fallback rate")
    fallback_axis.set_ylim(0, 1)

    fig.suptitle("Sioux Falls iterative LLM decision-process experiment")
    for extension in ("png", "pdf"):
        fig.savefig(
            args.figure_dir / f"llm_decision_process_summary.{extension}",
            dpi=300 if extension == "png" else None,
        )
    plt.close(fig)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
