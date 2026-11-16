# G3 — Bounded runtime and resource ledger

**Status: ACCEPTED (November 8, 2026).** Stop for external review after G4, not a launch of G5 or an experiment.

## Implemented

- `dsl.clj`: closed vector grammar, domain-indexed roles/effects, canonical numeric leaves, protected wrappers, bounded EDN subset, checked subtree replacement, and 1-/2-arity `canonical-edn` / `program-id` honoring explicit limit overrides.
- `runtime.clj`: host-owned proposal/fuel budgets, a single charge-before-construction request path, duplicate/cache accounting, bounded normalized scoring, branches, deterministic traces and measured timing.
- `portfolio_runtime.clj`: bounded objective-free first/random initializers and swap/restart operators, exact-K sampling without rejection loops, separately timed fixed normalization preparation.
- Raw G2 objective fronts retain aligned representative solutions. Method failures preserve diagnostic state but return no primary efficacy score.

## Verified execution

- Aggregate `:g3-test` (`dsl-test` 7 tests / 79 assertions + `runtime-test` 24 tests / 600 assertions): **31 tests / 679 assertions**, zero failures/errors on both Clojure 1.12.6 and 1.12.5 via native Windows launchers (`.\clojure.cmd -M:g3-test` and `.\clojure.cmd -M:g3-test:legacy-version`).
- Aggregate `:g4-test` (variation + malformed-shape regressions + G3/G4 integration): **15 tests / 81,057 assertions**, zero failures/errors on both language versions. The integration checks execute generated/edited programs against the synthetic portfolio fixture, compare retained solutions with the independent same-domain covariance reference, observe real objective calls, replay executions, and check archive monotonicity.
- All earlier review findings (lazy option/limit keys, nested AST tokens, and 2-arity `canonical-edn` / `program-id` limit forwarding) are resolved with RED/GREEN regressions and verified source hashes.

## Boundaries

Fuel bounds interpreter transitions, not arbitrary JVM callback time or heap consumption. Host adapters, canonical solution values and task preparation are trusted. Worker timeouts/memory isolation remain operational gates before expensive runs. Replay requires identical data/core versions, AST, options and seed; measured `:timing` is excluded.

The grammar, variation distribution and operational ceilings are development choices, not frozen confirmatory settings. No market data, outer search, scheduling adapter or confirmatory run was added. Proposal budgets and launch-disabled flags are unchanged.

## Evidence

- `docs/G3_G4_CONTRACT.md`, `docs/G3_G4_PROOFS.md`
- `docs/evidence/g3-runtime-report.json`, `g3-runtime-code-review-01.json`
- `docs/evidence/g3-dsl-code-review-01.json`, `g3-dsl-fix-report.json`
- `docs/evidence/g3-g4-shape-fix-report.json`, `g3-g4-fix-code-review-02.json`
- `docs/evidence/g3-g4-integration-parent.txt`, `g4-post-fix-combined.txt`
- `docs/evidence/g3-g4-baseline-regression.txt`, `g3-g4-final-regression.txt`

Reproduce from root: `.\clojure.cmd -M:g3-test` and `.\clojure.cmd -M:g3-test:legacy-version`.
