"""Replot the README traveler comparison from the committed aggregate CSV.

Run from the repository root: python scripts/plot_readme_traveler_results.py
Requires matplotlib. No simulation, model service, or individual trip logs are used.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D


def draw(data_path: Path, output_dir: Path) -> None:
    with data_path.open(newline="", encoding="utf-8-sig") as stream:
        rows = list(csv.DictReader(stream))

    def series(condition: str, seed: int, key: str, scale: float = 1):
        selected = sorted(
            (row for row in rows if row["condition"] == condition and int(row["seed"]) == seed),
            key=lambda row: int(row["day"]),
        )
        days = [int(row["day"]) for row in selected]
        if days != list(range(61)):
            raise ValueError(f"Expected Days 0–60 for {condition}, seed {seed}")
        return days, [float(row[key]) * scale for row in selected]

    plt.rcParams.update({
        "font.family": ["Arial", "DejaVu Sans"], "font.size": 10,
        "axes.titlesize": 12, "axes.labelsize": 10,
        "xtick.labelsize": 9, "ytick.labelsize": 9,
        "axes.linewidth": 0.8, "axes.edgecolor": "#61676D",
        "xtick.color": "#3C4248", "ytick.color": "#3C4248",
        "pdf.fonttype": 42, "svg.fonttype": "none",
        "figure.constrained_layout.use": False, "figure.autolayout": False,
    })
    blue, orange, gray = "#0072B2", "#D55E00", "#8C9298"
    legend = [
        Line2D([], [], color=blue, lw=2, ls="--", label="Native MATSim (seed 9261)"),
        Line2D([], [], color=orange, lw=2, label="LLM travelers (seed 9261)"),
        Line2D([], [], color=gray, lw=0.9, label="Native MATSim (seeds 268, 4711)"),
    ]
    fig, axes = plt.subplots(2, 2, figsize=(11.8, 8.3))
    fig.subplots_adjust(left=0.09, right=0.97, bottom=0.13, top=0.80, hspace=0.42, wspace=0.29)
    fig.suptitle("LLM and native MATSim over the same 61 days", x=0.51, y=0.965,
                 fontsize=17, weight="bold")
    fig.text(0.51, 0.921, "Day 0-60 | Same population, network and capacity | Daily observations",
             ha="center", fontsize=10, color="#4E5963")
    fig.legend(handles=legend, loc="upper center", bbox_to_anchor=(0.5, 0.897),
               ncol=2, frameon=False, fontsize=10)
    panels = [
        ("all_car_person_hours", "A  Full-day network travel", "Car travel time (thousand person-hours/day)", 0.001),
        ("mean_car_min", "B  Mean target-trip car time", "Mean car travel time (min)", 1),
        ("p95_car_min", "C  Target-trip upper tail", "Daily P95 car travel time (min)", 1),
        ("work_late_pct", "D  Work arrival punctuality", "Late work arrivals (%)", 1),
    ]
    for ax, (key, title, ylabel, scale) in zip(axes.flat, panels):
        for seed in [268, 4711]:
            ax.plot(*series("Native", seed, key, scale), color=gray, lw=0.9, alpha=0.75, zorder=1)
        ax.plot(*series("Native", 9261, key, scale), color=blue, lw=1.9, ls="--", zorder=2)
        ax.plot(*series("LLM", 9261, key, scale), color=orange, lw=2, zorder=3)
        ax.axvspan(56, 60, color="#3C4248", alpha=0.06, lw=0)
        ax.set(title=title, xlabel="Simulation day", ylabel=ylabel, xlim=(0, 60), ylim=(0, None))
        ax.set_xticks(range(0, 61, 10))
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(axis="y", color="#DDE1E5", linewidth=0.55, alpha=0.9)
        ax.set_axisbelow(True)
    fig.text(0.09, 0.057,
             "Target cohort: 11,793 trips/day; work punctuality: 11,332 trips/day. Full-day traffic: 33,644 car legs/day.",
             fontsize=9, color="#4E5963")
    fig.text(0.09, 0.032,
             "Gray lines are two additional native runs, not confidence bounds. Shading marks Days 56-60; LLM is active on Day 0.",
             fontsize=9, color="#4E5963")

    fig.canvas.draw()
    renderer = fig.canvas.get_renderer()
    bounds = fig.get_tightbbox(renderer)
    width, height = fig.get_size_inches()
    if bounds.x0 < 0 or bounds.y0 < 0 or bounds.x1 > width or bounds.y1 > height:
        raise ValueError("Figure content extends beyond the canvas")
    output_dir.mkdir(parents=True, exist_ok=True)
    for extension in ["png", "svg"]:
        fig.savefig(output_dir / f"llm-vs-native-61days.{extension}", dpi=300, facecolor="white")
    svg_path = output_dir / "llm-vs-native-61days.svg"
    svg_path.write_text("\n".join(line.rstrip() for line in svg_path.read_text(encoding="utf-8").splitlines()) + "\n",
                        encoding="utf-8", newline="\n")
    plt.close(fig)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=root / "docs/data/llm-traveler-daily-comparison.csv")
    parser.add_argument("--output-dir", type=Path, default=root / "docs/assets")
    args = parser.parse_args()
    draw(args.data, args.output_dir)
