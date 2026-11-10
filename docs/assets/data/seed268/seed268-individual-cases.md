# Seed 268: individual decision traces

[Project experiments](../../../../README.md#experiments) · [Five-configuration records](seed268-experiments.md) · [Reported experiments](../../../reported-experiments.md)

These are purposefully selected examples, not a random sample or estimates of behavioral frequency. All cases use simulation seed 268. The homepage now features Traveler `1005_1` and Joint `20336_4`. The earlier Joint `10010_2` trace and the explanation-inconsistency example remain below as archived evidence. All day numbers in the narrative and tables are simulation days 1–100. The linked source records use zero-based log days: simulation day = log day + 1.

The original 18 date/trip excerpts remain unchanged in the original evidence files. The six-day replacement case has its own [evidence and execution checks](joint-20336-4-evidence.json); it is not included in those earlier validation counts.

## 1. LLM traveler agent 1005_1: route switch at an unchanged departure time

On Day 66, the traveler sees four candidate routes, nine allowed departures from 06:27:47 to 08:27:47, a 09:00 arrival threshold, and personal trips from Days 63–65. Network traffic information is unavailable. The original departure is 07:57:47; 08:12:47 is an allowed choice. The arrival threshold is not a hard feasibility constraint on the choice set.

The selected action is `c1` at 08:12:47. The route is approximately 6.51 km with a 7.56-minute static free-flow estimate. The actual car time is 7.85 minutes; door-to-door time is 28.77 minutes and destination arrival is 08:41:33. Static estimates are not live congestion forecasts.

Recorded rationale:

> I chose route c1 to avoid the inconsistency of c0 while ensuring I meet the 09:00 deadline on time.

| Day | Route | Trip departure | Car time (min) | Destination arrival | Arrival status |
| :--- | :--- | :--- | ---: | :--- | :--- |
| 63 | c0 | 08:12:47 | 28.98 | 09:02:41 | 2 min 41 s late |
| 64 | c0 | 08:12:47 | 14.13 | 08:47:50 | On time |
| 65 | c0 | 08:12:47 | 31.28 | 09:04:59 | 4 min 59 s late |
| 66 | c1 | 08:12:47 | 7.85 | 08:41:33 | On time |
| 67 | c1 | 07:57:47 | 13.12 | 08:31:49 | On time |
| 68 | c1 | 08:12:47 | 8.62 | 08:42:19 | On time |

The unchanged departure on Days 63–66 makes the route change easy to inspect. Day 64 also shows that `c0` sometimes arrives on time. Changing shared network conditions prevent treating the sequence as an isolated route-effect experiment. The rationale is a recorded explanation, not a guarantee of punctuality.

## 2. Joint 20336_4: successive route choices at a fixed departure

Person ID: `20336_4`; target-trip ID: `20336_4/0`. The cohort records four route candidates, nine departure options, an original departure of 07:49:58, and a 09:00 arrival target. The traveler is uninformed about current toll announcements. All six trips below paid zero tolls; Joint's positive tolls start later, on simulation Day 51.

| Day | Route | Car time (min:sec) | Destination arrival | Arrival status |
| :--- | :--- | ---: | :--- | :--- |
| 22 | c0 | 14:17 | 08:16:59 | On time |
| 23 | c0 | 65:35 | 09:08:17 | 8:17 late |
| 24 | c1 | 97:43 | 09:40:25 | 40:25 late |
| 25 | c2 | 37:55 | 08:40:37 | On time |
| 26 | c2 | 87:52 | 09:30:34 | 30:34 late |
| 27 | c0 | 64:00 | 09:06:42 | 6:42 late |

Selected, applied and actual trip departure are 07:49:58 on every day. The executed links match the selected route and all trips complete.

### Recorded rationales

Day 24:

> Route c1 is familiar and slow, but c0 is unreliable and late, so I choose the known safe option despite higher cost risk

Day 25:

> I will try route c2 to find a stable option for my tight budget since c0 is unreliable and c1 is too slow.

Day 27:

> C0 is the only untested route, avoiding the late arrivals my personal history shows for C1 and C2.

Day 27's description of c0 as untested conflicts with its use on Days 22 and 23. This is a discrepancy relative to the full trace; it does not establish which older records were visible to the model at that decision.

Car time includes pre-network waiting: 51 min 33 s on Day 23 and 56 min 36 s on Day 24. Trip departure and destination arrival also include access/egress time, so their difference is not the car-time column. The sequence illustrates route changes following delays and mixed subsequent outcomes. It does not isolate effects of memory, route choice, or pricing.

### Identifier interpretation

The input population contains a person whose full ID is `20336_4`. It also contains distinct persons `20336_1`, `20336_2`, `20336_3`, and `20336_7`, with different attributes and plans but the same home facility `4044_5`. The suffix is inherited from the source population; its precise upstream coding rule has not been verified. It is not this platform's trip index. `MorningCohort` creates trip keys as `personId + "/" + tripIndex`, so `/0` denotes the first trip. The doubled population uses a separate `_new` suffix for copies.

## Archived Joint case: 10010_2, early departure and punctuality


On Day 61, the traveler sees four candidate routes, nine allowed departures from 05:56:43 to 07:56:43, the 09:00 arrival threshold, personal records from Days 58–60, and current pricing announcements. Network traffic conditions remain hidden. The selected `c1` departure is 05:56:43, the earliest allowed choice and 90 minutes before the original 07:26:43 departure.

Recorded rationale:

> Aggressive decision to diversify from unreliable c0 and avoid lateness.

Actual car time is 21.97 minutes, door-to-door time is 36.88 minutes, and destination arrival is 06:33:36. The selected route pays zero tolls even though Joint pricing is active elsewhere in the network.

| Day | Route | Trip departure | Car time (min) | Destination arrival | Arrival status |
| :--- | :--- | :--- | ---: | :--- | :--- |
| 58 | c0 | 05:56:43 | 23.18 | 06:34:49 | On time |
| 59 | c0 | 05:56:43 | 22.65 | 06:34:17 | On time |
| 60 | c0 | 07:41:43 | 74.17 | 09:10:48 | 10 min 48 s late |
| 61 | c1 | 05:56:43 | 21.97 | 06:33:36 | On time |
| 62 | c0 | 05:56:43 | 22.68 | 06:34:19 | On time |
| 63 | c0 | 05:56:43 | 19.40 | 06:31:02 | On time |
| 64 | c0 | 07:26:43 | 19.28 | 08:00:55 | On time |

Day 61 changes both route and departure time relative to Day 60. Day 62 returns to `c0` while retaining the early departure. This trace illustrates schedule changes alongside punctuality; it neither isolates a toll effect nor establishes a welfare improvement from arriving early. Destination arrival is distinct from the start of the destination activity.

## 3. LLM traveler agent 10042_2: a legal action with an inaccurate explanation

On Day 62 the structured action is `c1` at **07:46:19**. The applied plan and actual trip departure use that same allowed time, and the completed route matches `c1`. The generated rationale nevertheless states:

> I choose the familiar route c1 at 07:49:39 to stay conservative and predictable.

The stated 07:49:39 differs from the structured and executed time. Actual car time is **81.43 minutes**, including **36.50 minutes of pre-traffic waiting**, with destination arrival at **09:11:10**: **11 minutes 10 seconds late**.

| Day | Route | Trip departure | Car time (min) | Destination arrival | Arrival status |
| :--- | :--- | :--- | ---: | :--- | :--- |
| 61 | c1 | 07:31:19 | 15.77 | 07:50:30 | On time |
| 62 | c1 | 07:46:19 | 81.43 | 09:11:10 | 11 min 10 s late |
| 63 | c0 | 07:31:19 | 12.52 | 07:47:15 | On time |

This is a checked discrepancy in one rationale. It demonstrates that legal structured output and correct plan execution do not by themselves validate explanatory text or guarantee an on-time arrival. It does not estimate the prevalence of such discrepancies.

## Evidence and extraction

For the original 18 published date/trip records, the model output, applied decision and actual departure agree; selected links match executed links; all trips complete; car time, door time and lateness reconcile with timestamps. Overlapping personal history is cross-checked against the corresponding trip record. The three originally featured outputs each have one recorded model call, no output repair, and finish reason `stop`; these counts do not cover the separate `20336_4` sequence.

- [Evidence excerpts and source hashes](individual-case-evidence.json)
- [Continuous traces as CSV](individual-case-traces.csv)
- [Validation summary](individual-case-validation.json)

The evidence publishes selected context facts, candidates, legal departures, model results, decisions, trips and execution checks. Synthetic profiles, self-written summaries and private service endpoints are omitted; these are not complete model prompts. Source file SHA-256 values and record line numbers support checking the excerpts against the original logs. The recorded extraction used complete newline-terminated records; this handling does not describe the manuscript's final completion state.

The original extraction script and raw run archives are not bundled with these excerpts. Inspect the published JSON/CSV directly; checking source hashes and line references against full logs additionally requires the corresponding original archives.

## Distributional context

Across the manuscript's **100-day** window, LLM Traveler and Joint select departures **34.96** and **39.65 minutes earlier** than their original plans on average. Their overall route-switching rates are **3.08%** and **2.19%**, and departure-adjustment rates are **29.45%** and **28.04%**, over 99 adjacent-day transitions. These population summaries contextualize the cases without establishing habits, isolated policy effects, or welfare gains.

