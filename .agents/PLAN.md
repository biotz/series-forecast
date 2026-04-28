## Active Plan - 2026-04-28

- Active hypothesis: the immediate errors come from import-time DuckDB side effects and a missing `greet` API expected by the generated tests, README, and build aliases.
- Current approach: keep forecasting helpers explicit and callable, but remove database mutation/printing from namespace load; restore the greeting API and CLI behavior.
- Validation path: run `clojure -M:test -m cognitect.test-runner`, `clojure -T:build test`, and a direct `clojure -M:run-m` smoke check.
- Next checkpoint: tests pass without forcing DuckDB vocabulary creation during namespace require.
- Negative-memory constraints: do not reintroduce top-level `def` forms that open DuckDB connections, create tables, or print vocabulary data at namespace load.
- Agent assignments: Codex main session owns integration; Serena/Context7/Ruflo/memory MCP tools were unavailable in this environment.
