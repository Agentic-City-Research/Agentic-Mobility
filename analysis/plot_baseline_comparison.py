from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


SERIES_COLORS = {"baseline": "#5B6573", "llm": "#C44E52"}
MODE_COLORS = {"car": "#4C78A8", "pt": "#F28E2B", "walk": "#59A14F"}


def parse_args() -> argparse.Namespace:
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Compare one LLM MATSim run with replicated rule-only baselines."
    )
    parser.add_argument(
        "--llm-dir",
        type=Path,
        default=project / "output" / "llm-101-policy-v2-share-1pct-cache-v2",
        help="MATSim output directory for the LLM experiment.",
    )
    parser.add_argument(
        "--baseline-root",
        type=Path,
        default=project.parent / "matsim-example-project-master" / "output",
        help="Directory containing the baseline run directories.",
    )
    parser.add_argument(
        "--baseline-pattern",
        default="siouxfalls-baseline-policy-v2-*",
        help="Glob used below --baseline-root.",
    )
    parser.add_argument(
        "--figure-dir",
        type=Path,
        default=project / "analysis" / "figures-policy-v2-share-1pct-cache-v2-vs-baseline",
    )
    parser.add_argument(
        "--dpi", type=int, default=220, help="PNG resolution (default: 220)."
    )
    return parser.parse_args()


def read_semicolon_csv(path: Path) -> pd.DataFrame:
    if not path.is_file():
        raise FileNotFoundError(f"Missing MATSim table: {path}")
    frame = pd.read_csv(path, sep=";")
    frame.columns = [str(column).strip() for column in frame.columns]
    return frame


def read_run_tables(run_dir: Path) -> dict[str, pd.DataFrame]:
    score = read_semicolon_csv(run_dir / "scorestats.csv").rename(
        columns={"iteration": "iteration"}
    )
    modes = read_semicolon_csv(run_dir / "modestats.csv")
    distance = read_semicolon_csv(run_dir / "traveldistancestats.csv").rename(
        columns={"ITERATION": "iteration"}
    )
    for frame in (score, modes, distance):
        frame["iteration"] = pd.to_numeric(frame["iteration"], errors="raise").astype(int)
    return {"score": score, "modes": modes, "distance": distance}


def read_seed(config_path: Path) -> int | None:
    if not config_path.is_file():
        return None
    root = ET.parse(config_path).getroot()
    for module in root.findall("./module"):
        if module.get("name") == "global":
            for param in module.findall("./param"):
                if param.get("name") == "randomSeed":
                    return int(param.get("value", ""))
    return None


def config_parameters(config_path: Path) -> dict[str, str]:
    if not config_path.is_file():
        return {}
    root = ET.parse(config_path).getroot()
    result: dict[str, str] = {}
    for module in root.findall("./module"):
        module_name = module.get("name", "")
        for param in module.findall("./param"):
            result[f"{module_name}.{param.get('name', '')}"] = param.get("value", "")
        for index, parameter_set in enumerate(module.findall("./parameterset")):
            values = {
                param.get("name", ""): param.get("value", "")
                for param in parameter_set.findall("./param")
            }
            identity = ":".join(
                part
                for part in (
                    parameter_set.get("type", str(index)),
                    values.get("strategyName", ""),
                    values.get("subpopulation", "default"),
                )
                if part
            )
            for name, value in values.items():
                result[f"{module_name}.parameterset[{identity}].{name}"] = value
    return result


def innovation_disable_iteration(config_path: Path) -> int | None:
    """Return the latest disable iteration among default-population innovative strategies."""
    if not config_path.is_file():
        return None
    root = ET.parse(config_path).getroot()
    values: list[int] = []
    for module in root.findall("./module"):
        if module.get("name") != "replanning":
            continue
        for parameter_set in module.findall("./parameterset"):
            params = {
                param.get("name", ""): param.get("value", "")
                for param in parameter_set.findall("./param")
            }
            if (
                params.get("subpopulation") not in (None, "", "null")
                or params.get("strategyName") == "KeepLastSelected"
            ):
                continue
            raw = params.get("disableAfterIteration")
            if raw is not None:
                values.append(int(raw))
    return max(values) if values else None


def collect_baselines(root: Path, pattern: str) -> list[Path]:
    candidates = sorted(path for path in root.glob(pattern) if path.is_dir())
    valid = [path for path in candidates if (path / "scorestats.csv").is_file()]
    if not valid:
        raise FileNotFoundError(f"No baseline runs matched {root / pattern}")
    return valid


def stack_baselines(
    baseline_dirs: list[Path], tables: dict[str, dict[str, pd.DataFrame]]
) -> dict[str, pd.DataFrame]:
    result: dict[str, pd.DataFrame] = {}
    for table_name in ("score", "modes", "distance"):
        pieces = []
        for run_dir in baseline_dirs:
            piece = tables[str(run_dir)][table_name].copy()
            piece["run"] = run_dir.name
            pieces.append(piece)
        result[table_name] = pd.concat(pieces, ignore_index=True)
    return result


def baseline_envelope(
    stacked: pd.DataFrame, value: str, max_iteration: int
) -> pd.DataFrame:
    subset = stacked.loc[stacked["iteration"] <= max_iteration, ["iteration", value]]
    grouped = subset.groupby("iteration")[value]
    return grouped.agg(mean="mean", minimum="min", maximum="max", std="std").reset_index()


def common_series(
    llm: pd.DataFrame, paired: pd.DataFrame, value: str, max_iteration: int
) -> pd.DataFrame:
    left = llm.loc[llm["iteration"] <= max_iteration, ["iteration", value]].rename(
        columns={value: "llm"}
    )
    right = paired.loc[
        paired["iteration"] <= max_iteration, ["iteration", value]
    ].rename(columns={value: "baseline"})
    merged = left.merge(right, on="iteration", how="inner")
    merged["difference"] = merged["llm"] - merged["baseline"]
    return merged


def style_axis(axis: plt.Axes) -> None:
    axis.grid(axis="y", color="#D7DCE2", linewidth=0.7, alpha=0.75)
    axis.spines[["top", "right"]].set_visible(False)
    axis.tick_params(labelsize=9)


def plot_baseline_band(
    axis: plt.Axes, envelope: pd.DataFrame, label: str = "Rule baseline mean (range)"
) -> None:
    x = envelope["iteration"].to_numpy(dtype=float)
    axis.fill_between(
        x,
        envelope["minimum"].to_numpy(dtype=float),
        envelope["maximum"].to_numpy(dtype=float),
        color=SERIES_COLORS["baseline"],
        alpha=0.16,
        linewidth=0,
    )
    axis.plot(
        x,
        envelope["mean"].to_numpy(dtype=float),
        color=SERIES_COLORS["baseline"],
        linewidth=2,
        label=label,
    )


def save_figure(fig: plt.Figure, directory: Path, stem: str, dpi: int) -> None:
    for extension in ("png", "pdf"):
        fig.savefig(
            directory / f"{stem}.{extension}",
            dpi=dpi if extension == "png" else None,
            bbox_inches="tight",
            facecolor="white",
        )
    plt.close(fig)


def plot_network_overview(
    llm: dict[str, pd.DataFrame],
    baselines: dict[str, pd.DataFrame],
    max_iteration: int,
    out_dir: Path,
    dpi: int,
) -> None:
    metrics = [
        ("score", "avg_executed", "Mean executed score", "Utility points/person"),
        (
            "distance",
            "avg. Average Trip distance",
            "Mean trip distance",
            "Distance units/trip",
        ),
    ]
    fig, axes = plt.subplots(2, 1, figsize=(11.2, 8.2), sharex=True, constrained_layout=True)
    for axis, (table_name, value, title, ylabel) in zip(axes, metrics):
        envelope = baseline_envelope(baselines[table_name], value, max_iteration)
        plot_baseline_band(axis, envelope)
        llm_table = llm[table_name]
        llm_table = llm_table.loc[llm_table["iteration"] <= max_iteration]
        axis.plot(
            llm_table["iteration"],
            llm_table[value],
            color=SERIES_COLORS["llm"],
            linewidth=2.2,
            marker="o",
            markersize=3,
            label="DeepSeek LLM run",
        )
        axis.set_title(title, loc="left", fontweight="bold")
        axis.set_ylabel(ylabel)
        style_axis(axis)
    axes[-1].set_xlabel("MATSim iteration")
    axes[0].legend(frameon=False, ncol=2, loc="lower right")
    fig.suptitle(
        "Sioux Falls: LLM experiment versus five rule-only baseline runs",
        fontsize=14,
        fontweight="bold",
    )
    save_figure(fig, out_dir, "01_network_score_distance", dpi)


def plot_modes(
    llm_modes: pd.DataFrame,
    baseline_modes: pd.DataFrame,
    max_iteration: int,
    out_dir: Path,
    dpi: int,
) -> None:
    modes = [mode for mode in ("car", "pt", "walk") if mode in llm_modes.columns]
    fig, axes = plt.subplots(
        len(modes), 1, figsize=(11.2, 8.7), sharex=True, constrained_layout=True
    )
    axes = np.atleast_1d(axes)
    for axis, mode in zip(axes, modes):
        envelope = baseline_envelope(baseline_modes, mode, max_iteration)
        plot_baseline_band(axis, envelope)
        current = llm_modes.loc[llm_modes["iteration"] <= max_iteration]
        axis.plot(
            current["iteration"],
            current[mode],
            color=MODE_COLORS.get(mode, SERIES_COLORS["llm"]),
            linewidth=2.2,
            marker="o",
            markersize=3,
            label="DeepSeek LLM run",
        )
        axis.set_title(f"{mode.upper()} share", loc="left", fontweight="bold")
        axis.set_ylabel("Trip share")
        axis.set_ylim(bottom=0)
        style_axis(axis)
    axes[-1].set_xlabel("MATSim iteration")
    axes[0].legend(frameon=False, ncol=2, loc="best")
    fig.suptitle(
        "Network mode shares: LLM experiment versus rule-only baseline",
        fontsize=14,
        fontweight="bold",
    )
    save_figure(fig, out_dir, "02_network_mode_shares", dpi)


def plot_paired_differences(
    differences: dict[str, pd.DataFrame],
    paired_name: str,
    out_dir: Path,
    dpi: int,
) -> None:
    warmup_end = 2
    panels = [
        ("score", "Executed-score difference", "LLM - baseline score"),
        ("trip_distance", "Trip-distance difference", "LLM - baseline distance"),
        ("mode", "Mode-share difference", "Percentage points"),
    ]
    fig, axes = plt.subplots(3, 1, figsize=(11.2, 9.5), sharex=True, constrained_layout=True)
    for axis, (key, title, ylabel) in zip(axes, panels):
        axis.axhline(0, color="#30343B", linewidth=1)
        if key != "mode":
            data = differences[key]
            data = data.loc[data["iteration"] > warmup_end]
            axis.plot(
                data["iteration"],
                data["difference"],
                color=SERIES_COLORS["llm"],
                linewidth=2,
                marker="o",
                markersize=3,
            )
        else:
            for mode, data in differences["modes"].items():
                data = data.loc[data["iteration"] > warmup_end]
                axis.plot(
                    data["iteration"],
                    data["difference"] * 100,
                    linewidth=2,
                    label=mode,
                    color=MODE_COLORS.get(mode),
                )
            axis.legend(frameon=False, ncol=3, loc="best")
        axis.set_title(title, loc="left", fontweight="bold")
        axis.set_ylabel(ylabel)
        style_axis(axis)
    axes[-1].set_xlabel("MATSim iteration")
    fig.suptitle(
        f"Post-warm-up paired differences relative to {paired_name}",
        fontsize=14,
        fontweight="bold",
    )
    save_figure(fig, out_dir, "03_paired_seed_differences", dpi)


def build_iteration_table(
    llm: dict[str, pd.DataFrame],
    baselines: dict[str, pd.DataFrame],
    paired: dict[str, pd.DataFrame],
    max_iteration: int,
) -> pd.DataFrame:
    result = pd.DataFrame({"iteration": range(max_iteration + 1)})
    specifications = [
        ("score", "avg_executed", "score"),
        ("distance", "avg. Average Trip distance", "trip_distance"),
        ("modes", "car", "car_share"),
        ("modes", "pt", "pt_share"),
        ("modes", "walk", "walk_share"),
    ]
    for table_name, value, prefix in specifications:
        envelope = baseline_envelope(baselines[table_name], value, max_iteration)
        envelope = envelope.rename(
            columns={
                "mean": f"baseline_{prefix}_mean",
                "minimum": f"baseline_{prefix}_min",
                "maximum": f"baseline_{prefix}_max",
                "std": f"baseline_{prefix}_sd",
            }
        )
        result = result.merge(envelope, on="iteration", how="left")
        llm_values = llm[table_name][["iteration", value]].rename(
            columns={value: f"llm_{prefix}"}
        )
        paired_values = paired[table_name][["iteration", value]].rename(
            columns={value: f"paired_baseline_{prefix}"}
        )
        result = result.merge(llm_values, on="iteration", how="left")
        result = result.merge(paired_values, on="iteration", how="left")
        result[f"paired_difference_{prefix}"] = (
            result[f"llm_{prefix}"] - result[f"paired_baseline_{prefix}"]
        )
    return result


def final_window_summary(iteration_table: pd.DataFrame, window: int = 5) -> pd.DataFrame:
    tail = iteration_table.tail(window)
    rows = []
    for prefix in ("score", "trip_distance", "car_share", "pt_share", "walk_share"):
        llm_value = tail[f"llm_{prefix}"].mean()
        baseline_value = tail[f"baseline_{prefix}_mean"].mean()
        paired_value = tail[f"paired_baseline_{prefix}"].mean()
        rows.append(
            {
                "metric": prefix,
                "window_iterations": f"{int(tail.iteration.min())}-{int(tail.iteration.max())}",
                "llm_mean": llm_value,
                "five_baseline_mean": baseline_value,
                "llm_minus_five_baseline_mean": llm_value - baseline_value,
                "paired_baseline_mean": paired_value,
                "llm_minus_paired_baseline": llm_value - paired_value,
            }
        )
    return pd.DataFrame(rows)


def read_matched_final_outcomes(
    run_dir: Path, person_ids: set[str], prefix: str
) -> tuple[pd.DataFrame, pd.Series]:
    persons = pd.read_csv(
        run_dir / "output_persons.csv.zst",
        sep=";",
        compression="zstd",
        usecols=["person", "executed_score"],
    )
    persons["person"] = persons["person"].astype(str)
    persons = persons.loc[persons["person"].isin(person_ids)].set_index("person")
    persons = persons.rename(columns={"executed_score": f"{prefix}_score"})

    trips = pd.read_csv(
        run_dir / "output_trips.csv.zst",
        sep=";",
        compression="zstd",
        usecols=["person", "trav_time", "traveled_distance", "main_mode"],
    )
    trips["person"] = trips["person"].astype(str)
    trips = trips.loc[trips["person"].isin(person_ids)].copy()
    trips["travel_time_min"] = (
        pd.to_timedelta(trips["trav_time"]).dt.total_seconds() / 60.0
    )
    daily = trips.groupby("person").agg(
        **{
            f"{prefix}_travel_time_min": ("travel_time_min", "sum"),
            f"{prefix}_distance": ("traveled_distance", "sum"),
        }
    )
    mode_counts = trips["main_mode"].value_counts()
    return persons.join(daily, how="inner"), mode_counts


def matched_final_table(
    llm_dir: Path, baseline_dir: Path
) -> tuple[pd.DataFrame, pd.DataFrame]:
    selection = pd.read_csv(llm_dir / "llm-agent-selection.csv")
    person_ids = set(selection["person_id"].astype(str))
    llm, llm_modes = read_matched_final_outcomes(llm_dir, person_ids, "llm")
    baseline, baseline_modes = read_matched_final_outcomes(
        baseline_dir, person_ids, "baseline"
    )
    matched = llm.join(baseline, how="inner")
    matched.index.name = "person_id"
    for metric in ("score", "travel_time_min", "distance"):
        matched[f"{metric}_difference"] = (
            matched[f"llm_{metric}"] - matched[f"baseline_{metric}"]
        )
    modes = sorted(set(llm_modes.index) | set(baseline_modes.index))
    mode_table = pd.DataFrame(
        {
            "mode": modes,
            "llm_trip_count": [int(llm_modes.get(mode, 0)) for mode in modes],
            "baseline_trip_count": [
                int(baseline_modes.get(mode, 0)) for mode in modes
            ],
        }
    )
    return matched.reset_index(), mode_table


def add_identity_line(axis: plt.Axes, x: pd.Series, y: pd.Series) -> None:
    lower = float(min(x.min(), y.min()))
    upper = float(max(x.max(), y.max()))
    padding = max((upper - lower) * 0.07, 0.1)
    bounds = (lower - padding, upper + padding)
    axis.plot(bounds, bounds, color="#555B63", linestyle="--", linewidth=1.2)
    axis.set_xlim(bounds)
    axis.set_ylim(bounds)


def annotate_extremes(
    axis: plt.Axes,
    frame: pd.DataFrame,
    difference_column: str,
    x_column: str,
    y_column: str,
    count: int = 3,
) -> None:
    extremes = frame.loc[frame[difference_column].abs().nlargest(count).index]
    for row in extremes.itertuples(index=False):
        axis.annotate(
            row.person_id,
            (getattr(row, x_column), getattr(row, y_column)),
            xytext=(5, 4),
            textcoords="offset points",
            fontsize=8,
        )


def plot_matched_final_outcomes(
    matched: pd.DataFrame,
    modes: pd.DataFrame,
    out_dir: Path,
    dpi: int,
) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(13, 10), constrained_layout=True)
    sample_size = len(matched)
    marker_size = 42 if sample_size <= 100 else 18
    marker_alpha = 0.85 if sample_size <= 100 else 0.55

    score_better = matched["score_difference"] >= 0
    for mask, label, color in (
        (score_better, "LLM score higher", "#4E9F6D"),
        (~score_better, "LLM score lower", "#C44E52"),
    ):
        axes[0, 0].scatter(
            matched.loc[mask, "baseline_score"],
            matched.loc[mask, "llm_score"],
            s=marker_size,
            color=color,
            alpha=marker_alpha,
            label=label,
        )
    add_identity_line(axes[0, 0], matched["baseline_score"], matched["llm_score"])
    annotate_extremes(
        axes[0, 0], matched, "score_difference", "baseline_score", "llm_score"
    )
    axes[0, 0].set(
        title=f"Final executed score (same {sample_size:,} persons)",
        xlabel="Rule baseline score",
        ylabel="LLM score",
    )
    axes[0, 0].legend(frameon=False, fontsize=9)

    time_better = matched["travel_time_min_difference"] <= 0
    for mask, label, color in (
        (time_better, "LLM time shorter", "#4E9F6D"),
        (~time_better, "LLM time longer", "#C44E52"),
    ):
        axes[0, 1].scatter(
            matched.loc[mask, "baseline_travel_time_min"],
            matched.loc[mask, "llm_travel_time_min"],
            s=marker_size,
            color=color,
            alpha=marker_alpha,
            label=label,
        )
    add_identity_line(
        axes[0, 1],
        matched["baseline_travel_time_min"],
        matched["llm_travel_time_min"],
    )
    annotate_extremes(
        axes[0, 1],
        matched,
        "travel_time_min_difference",
        "baseline_travel_time_min",
        "llm_travel_time_min",
    )
    axes[0, 1].set(
        title=f"Final daily travel time (same {sample_size:,} persons)",
        xlabel="Rule baseline minutes",
        ylabel="LLM minutes",
    )
    axes[0, 1].legend(frameon=False, fontsize=9)

    axes[1, 0].axhline(0, color="#555B63", linewidth=1)
    axes[1, 0].axvline(0, color="#555B63", linewidth=1)
    quadrant_good = (
        (matched["score_difference"] >= 0)
        & (matched["travel_time_min_difference"] <= 0)
    )
    axes[1, 0].scatter(
        matched.loc[~quadrant_good, "score_difference"],
        matched.loc[~quadrant_good, "travel_time_min_difference"],
        color="#C44E52",
        s=42,
        alpha=0.8,
        label="At least one outcome worse",
    )
    axes[1, 0].scatter(
        matched.loc[quadrant_good, "score_difference"],
        matched.loc[quadrant_good, "travel_time_min_difference"],
        color="#4E9F6D",
        s=42,
        alpha=0.85,
        label="Higher score and shorter time",
    )
    axes[1, 0].set(
        title="Paired outcome differences",
        xlabel="LLM - baseline score",
        ylabel="LLM - baseline travel time (min)",
    )
    axes[1, 0].legend(frameon=False, fontsize=9)

    positions = np.arange(len(modes))
    width = 0.36
    axes[1, 1].bar(
        positions - width / 2,
        modes["baseline_trip_count"],
        width,
        color=SERIES_COLORS["baseline"],
        label="Rule baseline",
    )
    axes[1, 1].bar(
        positions + width / 2,
        modes["llm_trip_count"],
        width,
        color=SERIES_COLORS["llm"],
        label="DeepSeek LLM",
    )
    axes[1, 1].set_xticks(positions, modes["mode"].str.upper())
    axes[1, 1].set(
        title="Final trip-mode counts for the matched sample",
        xlabel="Main mode",
        ylabel="Trips",
    )
    axes[1, 1].legend(frameon=False)

    for axis in axes.flat:
        style_axis(axis)
    fig.suptitle(
        "Matched-agent final outcomes: LLM versus rule baseline",
        fontsize=14,
        fontweight="bold",
    )
    save_figure(fig, out_dir, "04_matched_agent_final_outcomes", dpi)


def plot_matched_differences(
    matched: pd.DataFrame, out_dir: Path, dpi: int
) -> None:
    if len(matched) > 120:
        fig, axes = plt.subplots(1, 2, figsize=(13, 5.5), constrained_layout=True)
        specifications = [
            ("score_difference", "Score-difference distribution", "LLM - baseline score", True),
            (
                "travel_time_min_difference",
                "Daily travel-time difference distribution",
                "LLM - baseline minutes",
                False,
            ),
        ]
        for axis, (column, title, xlabel, positive_is_good) in zip(axes, specifications):
            values = matched[column].dropna()
            good = values >= 0 if positive_is_good else values <= 0
            bins = np.histogram_bin_edges(values, bins=40)
            axis.hist(
                values.loc[good], bins=bins, color="#4E9F6D", alpha=0.78,
                label="Better or equal",
            )
            axis.hist(
                values.loc[~good], bins=bins, color="#C44E52", alpha=0.78,
                label="Worse",
            )
            axis.axvline(0, color="#333840", linewidth=1)
            axis.axvline(
                values.mean(), color="#555B63", linewidth=1.4, linestyle="--",
                label=f"Mean = {values.mean():.3f}",
            )
            axis.set(title=title, xlabel=xlabel, ylabel="Persons")
            axis.legend(frameon=False, fontsize=9)
            style_axis(axis)
        fig.suptitle(
            f"Paired differences for {len(matched):,} persons at iteration 100",
            fontsize=14,
            fontweight="bold",
        )
        save_figure(fig, out_dir, "05_matched_agent_differences", dpi)
        return

    ordered = matched.sort_values("score_difference").reset_index(drop=True)
    y = np.arange(len(ordered))
    fig, axes = plt.subplots(1, 2, figsize=(13, 10), constrained_layout=True)
    specifications = [
        ("score_difference", "Score difference", "LLM - baseline score", True),
        (
            "travel_time_min_difference",
            "Daily travel-time difference",
            "LLM - baseline minutes",
            False,
        ),
    ]
    for axis, (column, title, xlabel, positive_is_good) in zip(axes, specifications):
        values = ordered[column]
        good = values >= 0 if positive_is_good else values <= 0
        colors = np.where(good, "#4E9F6D", "#C44E52")
        axis.barh(y, values, color=colors, alpha=0.88)
        axis.axvline(0, color="#333840", linewidth=1)
        axis.set_yticks(y, ordered["person_id"])
        axis.set(title=title, xlabel=xlabel)
        style_axis(axis)
    axes[1].tick_params(labelleft=False)
    fig.suptitle(
        "Person-level paired differences at iteration 100",
        fontsize=14,
        fontweight="bold",
    )
    save_figure(fig, out_dir, "05_matched_agent_differences", dpi)


def plot_agent_trajectory(
    llm_dir: Path,
    matched: pd.DataFrame,
    innovation_end: int | None,
    out_dir: Path,
    dpi: int,
) -> None:
    experiences = pd.read_csv(llm_dir / "llm-experiences.csv")
    # Exclude iterations 0-2 so the large initialization transient does not
    # flatten the differences that matter during the decision process.
    experiences = experiences.loc[experiences["iteration"] >= 3].copy()
    grouped = experiences.groupby("iteration")
    trajectory = grouped.agg(
        score_mean=("score", "mean"),
        score_q25=("score", lambda values: values.quantile(0.25)),
        score_q75=("score", lambda values: values.quantile(0.75)),
        time_mean=("travel_time_min", "mean"),
        time_q25=("travel_time_min", lambda values: values.quantile(0.25)),
        time_q75=("travel_time_min", lambda values: values.quantile(0.75)),
    ).reset_index()
    x = trajectory["iteration"].to_numpy(dtype=float)
    fig, axes = plt.subplots(2, 1, figsize=(11.5, 8.5), sharex=True, constrained_layout=True)
    panels = [
        (
            "score",
            "Mean experienced score of LLM-controlled persons",
            "Utility points/person",
            float(matched["baseline_score"].mean()),
        ),
        (
            "time",
            "Mean experienced daily travel time of LLM-controlled persons",
            "Minutes/person-day",
            float(matched["baseline_travel_time_min"].mean()),
        ),
    ]
    for axis, (prefix, title, ylabel, baseline_value) in zip(axes, panels):
        axis.fill_between(
            x,
            trajectory[f"{prefix}_q25"].to_numpy(dtype=float),
            trajectory[f"{prefix}_q75"].to_numpy(dtype=float),
            color=SERIES_COLORS["llm"],
            alpha=0.14,
            label="LLM interquartile range",
        )
        axis.plot(
            x,
            trajectory[f"{prefix}_mean"],
            color=SERIES_COLORS["llm"],
            linewidth=2,
            label="LLM mean by iteration",
        )
        axis.axhline(
            baseline_value,
            color=SERIES_COLORS["baseline"],
            linestyle="--",
            linewidth=1.8,
            label="Matched baseline final mean",
        )
        if innovation_end is not None:
            axis.axvline(
                innovation_end,
                color="#777D86",
                linestyle=":",
                linewidth=1.2,
                label="Innovation disabled" if prefix == "score" else None,
            )
        axis.set_title(title, loc="left", fontweight="bold")
        axis.set_ylabel(ylabel)
        style_axis(axis)
    axes[0].legend(frameon=False, ncol=2, fontsize=9)
    axes[1].legend(frameon=False, ncol=2, fontsize=9)
    axes[1].set_xlabel("MATSim iteration")
    fig.suptitle(
        "Post-warm-up LLM-agent trajectory with matched baseline endpoint",
        fontsize=14,
        fontweight="bold",
    )
    save_figure(fig, out_dir, "06_llm_agent_trajectory", dpi)


def write_comparison_notes(
    out_dir: Path,
    llm_dir: Path,
    baseline_dirs: list[Path],
    paired_dir: Path,
    max_iteration: int,
    llm_last_iteration: int,
) -> None:
    sample_count = len(pd.read_csv(llm_dir / "llm-agent-selection.csv"))
    llm_config = config_parameters(llm_dir / "output_config.xml")
    paired_config = config_parameters(paired_dir / "output_config.xml")
    llm_innovation_end = innovation_disable_iteration(llm_dir / "output_config.xml")
    paired_innovation_end = innovation_disable_iteration(
        paired_dir / "output_config.xml"
    )
    ignored = {
        "controller.lastIteration",
        "controller.outputDirectory",
        "controller.writeEventsInterval",
        "controller.writePlansInterval",
    }
    differences = {
        key: {"baseline": paired_config.get(key), "llm": llm_config.get(key)}
        for key in sorted(set(llm_config) | set(paired_config))
        if key not in ignored and paired_config.get(key) != llm_config.get(key)
    }
    report = {
        "llm_directory": str(llm_dir.resolve()),
        "baseline_directories": [str(path.resolve()) for path in baseline_dirs],
        "paired_baseline_directory": str(paired_dir.resolve()),
        "llm_seed": read_seed(llm_dir / "output_config.xml"),
        "paired_baseline_seed": read_seed(paired_dir / "output_config.xml"),
        "compared_iterations": [0, max_iteration],
        "llm_available_iterations": [0, llm_last_iteration],
        "llm_innovation_disable_iteration": llm_innovation_end,
        "paired_baseline_innovation_disable_iteration": paired_innovation_end,
        "config_differences_excluding_output_and_iteration_controls": differences,
        "interpretation": [
            "The shaded baseline band is the min-to-max range across baseline seeds.",
            "Paired differences use the baseline with the same MATSim random seed.",
            f"{sample_count:,} persons are LLM-controlled, so network-wide and matched-person effects must be interpreted separately.",
            (
                "Both runs disable ordinary innovation at the same iteration, so their full common iteration range is compared."
                if llm_innovation_end == paired_innovation_end
                else "The comparison stops at the earlier innovation-disable iteration because the replanning schedules differ."
            ),
            f"Final outcomes are matched for the same {sample_count:,} persons; person-level baseline trajectories are unavailable between iterations.",
        ],
    }
    (out_dir / "comparison_metadata.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )


def main() -> int:
    args = parse_args()
    llm_dir = args.llm_dir.resolve()
    baseline_dirs = collect_baselines(args.baseline_root.resolve(), args.baseline_pattern)
    args.figure_dir.mkdir(parents=True, exist_ok=True)

    llm = read_run_tables(llm_dir)
    loaded = {str(path): read_run_tables(path) for path in baseline_dirs}
    baselines = stack_baselines(baseline_dirs, loaded)

    llm_seed = read_seed(llm_dir / "output_config.xml")
    seed_matches = [
        path
        for path in baseline_dirs
        if read_seed(path / "output_config.xml") == llm_seed
    ]
    paired_dir = seed_matches[0] if seed_matches else baseline_dirs[0]
    paired = loaded[str(paired_dir)]
    llm_last_iteration = int(llm["score"]["iteration"].max())
    paired_last_iteration = int(paired["score"]["iteration"].max())
    llm_innovation_end = innovation_disable_iteration(llm_dir / "output_config.xml")
    paired_innovation_end = innovation_disable_iteration(
        paired_dir / "output_config.xml"
    )
    common_last_iteration = min(llm_last_iteration, paired_last_iteration)
    if (
        llm_innovation_end is not None
        and paired_innovation_end is not None
        and llm_innovation_end != paired_innovation_end
    ):
        max_iteration = min(
            common_last_iteration, llm_innovation_end, paired_innovation_end
        )
    else:
        max_iteration = common_last_iteration

    plot_network_overview(llm, baselines, max_iteration, args.figure_dir, args.dpi)
    plot_modes(llm["modes"], baselines["modes"], max_iteration, args.figure_dir, args.dpi)

    differences: dict[str, object] = {
        "score": common_series(
            llm["score"], paired["score"], "avg_executed", max_iteration
        ),
        "trip_distance": common_series(
            llm["distance"],
            paired["distance"],
            "avg. Average Trip distance",
            max_iteration,
        ),
        "modes": {
            mode: common_series(llm["modes"], paired["modes"], mode, max_iteration)
            for mode in ("car", "pt", "walk")
        },
    }
    plot_paired_differences(
        differences, paired_dir.name, args.figure_dir, args.dpi  # type: ignore[arg-type]
    )

    iteration_table = build_iteration_table(
        llm, baselines, paired, max_iteration
    )
    iteration_table.to_csv(args.figure_dir / "comparison_by_iteration.csv", index=False)
    final_window_summary(iteration_table).to_csv(
        args.figure_dir / "comparison_last5_iterations.csv", index=False
    )
    matched, matched_modes = matched_final_table(llm_dir, paired_dir)
    matched.to_csv(args.figure_dir / "matched_agents_final.csv", index=False)
    matched_modes.to_csv(
        args.figure_dir / "matched_agents_mode_counts.csv", index=False
    )
    plot_matched_final_outcomes(matched, matched_modes, args.figure_dir, args.dpi)
    plot_matched_differences(matched, args.figure_dir, args.dpi)
    plot_agent_trajectory(
        llm_dir,
        matched,
        llm_innovation_end,
        args.figure_dir,
        args.dpi,
    )
    write_comparison_notes(
        args.figure_dir,
        llm_dir,
        baseline_dirs,
        paired_dir,
        max_iteration,
        llm_last_iteration,
    )

    print(f"Compared {llm_dir.name} with {len(baseline_dirs)} baseline runs.")
    print(f"Paired seed baseline: {paired_dir.name} (seed={llm_seed})")
    print(f"Fair comparison window: iterations 0-{max_iteration}")
    print(f"Figures and tables: {args.figure_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
