# Seed 268: individual decision traces

[Project experiments](../../../../README.md#experiments) · [Five-configuration records](seed268-experiments.md) · [Reported experiments](../../../reported-experiments.md)

These are purposeful examples from nine multi-route work trips with sampled decision contexts, selected to illustrate route choice, schedule changes, and an explanation inconsistency. They are not a random sample and do not estimate the frequency of these behaviors. All cases use simulation seed 268. The published excerpts contain 18 date/trip records, while the current manuscript's five-configuration comparison covers **Days0–99**. The same `qwen3.5-9b` model ID is recorded for the three featured outputs; this identifies those records, not the service settings of every run.

The manuscript's execution-validation section, whose source is not included in this repository, uses case 2 (`10010_2`, Day60) for the interaction trace and case 3 (`10042_2`, Day61) for the explanation inconsistency. Case 1 is an additional archived example. Completing the later run does not change these recorded case facts; the 18 published date/trip records are a selected subset, not the full 100-day audit.

## 1. LLM traveler agent 1005_1: route switch at an unchanged departure time

On Day 65, the traveler sees four candidate routes, nine allowed departures from 06:27:47 to 08:27:47, a 09:00 arrival threshold, and personal trips from Days 62–64. Network traffic information is unavailable. The original departure is 07:57:47; 08:12:47 is an allowed choice. The arrival threshold is not a hard feasibility constraint on the choice set.

The selected action is `c1` at 08:12:47. The route is approximately 6.51 km with a 7.56-minute static free-flow estimate. The actual car time is 7.85 minutes; door-to-door time is 28.77 minutes and destination arrival is 08:41:33. Static estimates are not live congestion forecasts.

Recorded rationale:

> I chose route c1 to avoid the inconsistency of c0 while ensuring I meet the 09:00 deadline on time.

| Day | Route | Trip departure | Car time (min) | Destination arrival | Arrival status |
| :--- | :--- | :--- | ---: | :--- | :--- |
| 62 | c0 | 08:12:47 | 28.98 | 09:02:41 | 2 min 41 s late |
| 63 | c0 | 08:12:47 | 14.13 | 08:47:50 | On time |
| 64 | c0 | 08:12:47 | 31.28 | 09:04:59 | 4 min 59 s late |
| 65 | c1 | 08:12:47 | 7.85 | 08:41:33 | On time |
| 66 | c1 | 07:57:47 | 13.12 | 08:31:49 | On time |
| 67 | c1 | 08:12:47 | 8.62 | 08:42:19 | On time |

The unchanged departure on Days 62–65 makes the route change easy to inspect. Day 63 also shows that `c0` sometimes arrives on time. Changing shared network conditions prevent treating the sequence as an isolated route-effect experiment. The rationale is a recorded explanation, not a guarantee of punctuality.

## 2. Joint 10010_2: early departure and punctuality

On Day 60, the traveler sees four candidate routes, nine allowed departures from 05:56:43 to 07:56:43, the 09:00 arrival threshold, personal records from Days 57–59, and current pricing announcements. Network traffic conditions remain hidden. The selected `c1` departure is 05:56:43, the earliest allowed choice and 90 minutes before the original 07:26:43 departure.

Recorded rationale:

> Aggressive decision to diversify from unreliable c0 and avoid lateness.

Actual car time is 21.97 minutes, door-to-door time is 36.88 minutes, and destination arrival is 06:33:36. The selected route pays zero tolls even though Joint pricing is active elsewhere in the network.

| Day | Route | Trip departure | Car time (min) | Destination arrival | Arrival status |
| :--- | :--- | :--- | ---: | :--- | :--- |
| 57 | c0 | 05:56:43 | 23.18 | 06:34:49 | On time |
| 58 | c0 | 05:56:43 | 22.65 | 06:34:17 | On time |
| 59 | c0 | 07:41:43 | 74.17 | 09:10:48 | 10 min 48 s late |
| 60 | c1 | 05:56:43 | 21.97 | 06:33:36 | On time |
| 61 | c0 | 05:56:43 | 22.68 | 06:34:19 | On time |
| 62 | c0 | 05:56:43 | 19.40 | 06:31:02 | On time |
| 63 | c0 | 07:26:43 | 19.28 | 08:00:55 | On time |

Day 60 changes both route and departure time relative to Day 59. Day 61 returns to `c0` while retaining the early departure. This trace illustrates schedule changes alongside punctuality; it neither isolates a toll effect nor establishes a welfare improvement from arriving early. Destination arrival is distinct from the start of the destination activity.

## 3. LLM traveler agent 10042_2: a legal action with an inaccurate explanation

On Day 61 the structured action is `c1` at **07:46:19**. The applied plan and actual trip departure use that same allowed time, and the completed route matches `c1`. The generated rationale nevertheless states:

> I choose the familiar route c1 at 07:49:39 to stay conservative and predictable.

The stated 07:49:39 differs from the structured and executed time. Actual car time is **81.43 minutes**, including **36.50 minutes of pre-traffic waiting**, with destination arrival at **09:11:10**: **11 minutes 10 seconds late**.

| Day | Route | Trip departure | Car time (min) | Destination arrival | Arrival status |
| :--- | :--- | :--- | ---: | :--- | :--- |
| 60 | c1 | 07:31:19 | 15.77 | 07:50:30 | On time |
| 61 | c1 | 07:46:19 | 81.43 | 09:11:10 | 11 min 10 s late |
| 62 | c0 | 07:31:19 | 12.52 | 07:47:15 | On time |

This is a checked discrepancy in one rationale. It demonstrates that legal structured output and correct plan execution do not by themselves validate explanatory text or guarantee an on-time arrival. It does not estimate the prevalence of such discrepancies.

## Evidence and extraction

For the 18 published date/trip records, the model output, applied decision and actual departure agree; selected links match executed links; all trips complete; car time, door time and lateness reconcile with timestamps. Overlapping personal history is cross-checked against the corresponding trip record. The three featured outputs each have one recorded model call, no output repair, and finish reason `stop`.

- [Evidence excerpts and source hashes](individual-case-evidence.json)
- [Continuous traces as CSV](individual-case-traces.csv)
- [Validation summary](individual-case-validation.json)

The evidence publishes selected context facts, candidates, legal departures, model results, decisions, trips and execution checks. Synthetic profiles, self-written summaries and private service endpoints are omitted; these are not complete model prompts. Source file SHA-256 values and record line numbers support checking the excerpts against the original logs. The recorded extraction used complete newline-terminated records; this handling does not describe the manuscript's final completion state.

The original extraction script and raw run archives are not bundled with these excerpts. Inspect the published JSON/CSV directly; checking source hashes and line references against full logs additionally requires the corresponding original archives.

## Distributional context

Across the manuscript's **100-day** window, LLM Traveler and Joint select departures **34.96** and **39.65 minutes earlier** than their original plans on average. Their overall route-switching rates are **3.08%** and **2.19%**, and departure-adjustment rates are **29.45%** and **28.04%**, over 99 adjacent-day transitions. These population summaries contextualize the cases without establishing habits, isolated policy effects, or welfare gains.

