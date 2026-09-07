# Contributing

Thank you for improving Agentic MATSim.

1. Open an issue describing the research or software need.
2. Keep changes scoped and avoid committing scenario data without verified redistribution rights.
3. Add or update tests for behavior changes.
4. Run `./mvnw test` (or `.\mvnw.cmd test` on Windows).
5. Run a short Sioux Falls integration check with `AGENTIC_PROVIDER=mock` if you have the scenario input.
6. Submit a pull request describing assumptions, seeds and expected output changes.

Provider integrations must keep API keys in environment variables, validate all returned actions and include a network-free test double. New actions should be represented as bounded structured data rather than free-form code or direct MATSim mutations.
