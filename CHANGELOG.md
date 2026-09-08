# Changelog

## 0.2.0 - Unreleased

- Added optional single congestion-pricing Manager with off/shadow/active modes.
- Added fixed, threshold-rule and OpenAI-compatible LLM policy providers.
- Restricted policies to researcher-confirmed candidate links, time bins and price levels; fail-closed validation and KEEP fallback.
- Integrated car toll disutility and actual PersonMoneyEvent payments; excluded transit and origin-link starts.
- Added objective observation windows, policy/payment audit, bounded Manager history and traveler policy-regime memory resets.
- Added baseline link/time delay-ranking helper (does not choose candidates), configuration template and Chinese usage guide.
- Added synthetic MATSim integration tests without external scenario data or paid API calls.

## 0.1.0 - Unreleased

- Extracted a public decision-provider API from the validated Sioux Falls prototype.
- Added deterministic mock and generic OpenAI-compatible providers.
- Externalized the travel-decision prompt.
- Added Sioux Falls as the default scenario and a reusable city-adapter API.
- Added run manifests, tests, CI and open-source project documentation.
