# G4 — Typed generation, mutation and crossover

**Status: ACCEPTED (November 8, 2026).** The agreed G4 checkpoint is complete. G5 has not started.

## Implemented

One production namespace, `src/niki/optimizer/variation.clj`:

- `generate-program`: seeded finite grammar construction with explicit development caps/distribution.
- `mutate`: same-role/domain subtree construction and full checked replacement.
- `crossover`: actual bidirectional swaps of selected subtrees from the two parents; BOTH resulting whole programs must validate and differ on success.
- Protected program/budget wrappers; role/domain/effect compatibility; whole-program node/depth/count limits.
- At most ten edit attempts; IDs, paths, rejection reasons and explicit unchanged-parent fallbacks. No hidden mutation substituted for crossover.

Generation is development-only, not uniform over legal ASTs. It uses subtree caps at most 30 nodes/depth 6, with discrete constants from -10 through 10; effective envelopes never exceed runtime defaults. Mutation may fall back despite another feasible edit existing. Those policies require external review and eventual freezing, not an efficacy claim.

## Execution checks

- Variation standalone: **8 tests / 76,930 assertions** pass on both Clojure versions and native Windows launchers. Coverage includes independent grammar/effect/size checks, both-child donor equations, real changed offspring, tight envelopes, minimal-envelope termination and seeded replay.
- Shape validation: **6 tests / 2,088 assertions** pass on both versions (rejecting malformed/lazy keys and nested tokens without realizing lazy values).
- G3/G4 synthetic integration: **1 test / 2,039 assertions** pass on both versions.
- Aggregate `:g4-test` runner: **15 tests / 81,057 assertions**, zero failures/errors on each of Clojure 1.12.6 and 1.12.5.
- Combined regression matrix (`:g1-test`, `:g2-test`, `:g3-test`, `:g4-test`, `:legacy:test` on Clojure 1.12.6 and 1.12.5, plus `scripts/validate-protocol.clj`): all green in `docs/evidence/g3-g4-final-regression.txt`.

## Scope boundaries (not implemented in G4)

- No outer GP loop, arm scheduling, assessment-slot ledger, fitness selection, market ingestion, scheduling adapter or optimizer comparison is implemented here.
- Returning two crossover children does not grant two free assessments. G5 must charge each occupied offspring slot and keep the mixed-arm allowance unchanged.

## Reproduce and inspect

From root: `.\clojure.cmd -M:g3-test`, `.\clojure.cmd -M:g4-test`, `.\clojure.cmd -M:g3-test:legacy-version`, and `.\clojure.cmd -M:g4-test:legacy-version`; Git Bash uses `bash ./clojure` with the same aliases.

Evidence: `g4-variation-report.json`, `g4-code-review-01.json`, `g3-g4-shape-fix-report.json`, `g3-g4-fix-code-review-02.json`, `g3-dsl-fix-report.json`, `g4-post-fix-combined.txt`, and `g3-g4-final-regression.txt` under `docs/evidence/`.
