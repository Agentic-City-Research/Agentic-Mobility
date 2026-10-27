# Traveler v9: continuous private memory and strict output

Templates: `morning-information-v9-continuous-memory` and `morning-reflection-v9-continuous-memory`. Manifest schema version: 23. Entry point: `org.matsim.agentic.MorningExperiment`.

This page describes the current traveler protocol. The [reported manuscript demonstration](reported-experiments.md) contains five seed-268 configurations over log Days0–99; traveler semantic review was **disabled**. The manuscript reports 1,179,300 finalized Joint target decisions and continuous 100-day personal-memory chains. These checks concern accepted actions and bounded state updates, not systematic semantic validation of generated memories or explanations. Preserve the templates and service settings of the actual archived build when comparing revisions.

## Daily memory cycle

| Phase | Visible private state |
| --- | --- |
| Day D choice | Fixed personal information, legal route/time options, previous summary, executed facts from D-3 to D-1 by default |
| Day D execution | Record the selected and actual route/timing, travel time, arrival, lateness, unfinished/stuck status, and actual payments |
| Day D reflection | Previous summary plus executed facts from D-2 to D, including today's result |
| Day D+1 | Feed the accepted replacement summary into the next choice and reflection |

Initial reflection uses empty memory. Every subsequent live reflection requires the preceding day's summary. Recent factual windows are calendar-day windows; initial days supply available records. `--history-window` accepts 1..3 for pre-choice facts, default 3; reflection uses three days including today.

The previous summary can retain selected older experiences, interpretations, and uncertainty within its text limit. The prompt asks the model to retain, revise and integrate it instead of recreating memory from zero or retelling only recent facts. Older experience is not automatically rejected because it cannot be checked within the window. The program archives complete execution records and does not retrieve them to restore forgotten private knowledge. Summary continuity alone does not establish learned habits or the factual accuracy of retained content.

Fixed facts are written once, candidate estimates are labelled predictions with units, experiences are actual executions, and memory is a personal summary. Information groups still refer to manager announcements. No live network traffic, other agents' experience or hidden current tariffs are added. The departure grid, lateness treatment and native behavior mechanisms retain their existing semantics.

## Output constraints

| Output | Text requirement | Default output tokens |
| --- | --- | --- |
| Choice reason | One short sentence, at most 120 Unicode characters | 1024 |
| Private memory | Target 240, at most 500 Unicode characters | 1024 |
| Optional review | One compact status/evidence/output object | 2048 |

Spaces and punctuation count toward character limits. Unicode code points are counted; token limits also accommodate JSON keys, quotes and numbers. The target memory length is soft; 241–500 characters are legal. Empty memory is illegal after a successful reflection proposal.

Recommended experiment arguments, replacing any existing duplicate arguments:

```bash
--history-window 3 --max-output-tokens 1024 --reason-max-chars 120 \
--memory-max-output-tokens 1024 --memory-max-chars 500 \
--response-format json_schema --repair-attempts 3
```

Use `json_schema` only if the inference service supports it; `omit` is available otherwise. MorningExperiment defaults to 1024 decision tokens / 120 reason characters when the corresponding environment variables are absent. Explicit environment or CLI settings can override them. PowerShell and Slurm templates now explicitly use the v9 limits.

Three output attempts mean one initial call and at most two retries. Local validation rejects truncation (`length`/`max_tokens`), empty/whitespace-only replies, malformed or trailing JSON, duplicate/extra fields, wrong field types, excessive text, unknown routes and illegal departure options. Invalid output is retried with the specific error. Exhaustion fails the experiment; JSON is not salvaged, memory is not clipped and actions are not supplied by the program. Actual lateness is never an output error.

## Optional one-shot semantic review

`--traveler-semantic-review` is off by default. With it enabled, each structurally legal choice or summary may dispatch one review call, outside the structural repair loop. `--semantic-review-max-output-tokens` defaults to 2048. PowerShell uses `-TravelerSemanticReview`; Slurm uses `TRAVELER_SEMANTIC_REVIEW=1`.

Enabling this option changes the configuration from the reported demonstration and requires a separately recorded comparison and request budget.

A single model call checks for clear contradictions and, if found, proposes its own revised reason or memory. The review receives only the same traveler's visible fixed facts, recent executed facts, previous subjective memory and proposed output. Fixed-fact/actual-record evidence is separate from the old subjective summary; the latter is not accepted as verified evidence. Predictions, preferences, uncertainty, future plans and unverified old experience are not factual errors. Late arrival does not trigger a mandatory route or departure change.

Conflicts must quote a claim present in the original text and evidence present in the visible factual input. At most three conflicts are returned. The selected route and numeric departure time are locked; the program validates the final text and complete output structure again. The review does not introduce new action choices or request private archive data.

| Review result | Execution |
| --- | --- |
| `no_conflict` | Keep the original text and actions |
| `corrected` | Accept the model's revised text if evidence and output checks pass; keep actions locked |
| `unresolved` | Accept the structurally legal output and mark the unresolved judgment |
| `review_failed` | Retain the original legal output, mark review failure, and do not retry the review |

Quotes establish provenance, not semantic truth. A review is an LLM judgment and can itself be wrong. The program never rewrites memory text on its own. Malformed/truncated review, ungrounded quotes, illegal action changes, API error or insufficient review budget retain the original valid result and are audited. Review remains limited to one call; no automatic second semantic round is made.

Enabling reviews approximately doubles the base traveler client calls (choice + reflection + their reviews), before structural repairs. Manager calls are unchanged. Preflight budgets include review calls; `api-usage.json` reports `attemptedTravelerSemanticReviewCalls` separately.

## Audit and whitespace diagnostics

- `attempts.jsonl.gz` and `memory-attempts.jsonl.gz` retain exact base responses, schema validation, finish reason, tokens/cache counters and latency.
- New response fields: `outputCharacters`, `outputWhitespaceCharacters`, `outputWhitespaceRatio`, `blankOutput`. These diagnose whitespace generation without altering any valid text or stripping spaces from memory.
- `semantic-reviews.jsonl.gz` retains original response/output, visible context, conflict quotes, review response/output, final output, applied revision, unresolved status, hashes, settings and usage.
- `llm-results.jsonl.gz` and `memory-updates.jsonl.gz` retain the final review status. Manifest records the templates, window sizes, limits, review switch, locked-action behavior and exact maximum output attempts.

Client prompts and token limits cannot guarantee that a model server stops producing unnecessary whitespace. Increasing output tokens alone can extend that behavior. vLLM documents `disable_any_whitespace` for its xgrammar and guidance structured-output backends; verify the installed service version and test failed examples before changing server startup settings. See [vLLM StructuredOutputsConfig](https://docs.vllm.ai/en/stable/api/vllm/config/structured_outputs/#vllm.config.structured_outputs.StructuredOutputsConfig.disable_any_whitespace). This repository change does not reconfigure or restart the server.

Manager daily memory, pricing cadence and all-day toll rules use their existing implementation. Traveler structural failures still fail the experiment; Manager reflection retains its independent failure policy.
