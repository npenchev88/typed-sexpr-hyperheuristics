# G7 — Development pilot, statistical pipeline and preregistration gate

**Status: Technical pilot & statistical pipeline ACCEPTED (November 8, 2026); Confirmatory G8 launch gated on Niki's 3 owner decisions.**

## Implemented

- `src/niki/optimizer/stats.clj`:
  - `analyze-paired-runs`: seeded paired bootstrap resampling (`10,000` resamples default) of whole outer-run outcome vectors across the four primary Full-GP contrasts (`H1` vs `:random-dsl`, `H2` vs `:formula-only`, `H3` vs `:gp-mutation-only`, `H4` vs `:configured-conventional`).
  - `holm-bonferroni`: step-down Holm-Bonferroni multiplicity adjustment and simultaneous family-wise confidence intervals.
  - `classify-interval`: practical threshold classification (`:practically-superior`, `:practically-inferior`, `:practically-equivalent`, `:inconclusive`) against the $\pm 0.01$ normalized-HV practical margin.
  - `temporal-block-bootstrap`: crossed outer-run $\times$ consecutive-task-block bootstrap for task/period generalization uncertainty.
- `scripts/run-smoke.clj` & `test/niki/optimizer/g7_test.clj`:
  - Executes the `configs/smoke-proposal.edn` development smoke protocol (`32` assessment slots = `8` initial + `3 x 8` offspring, `2` tasks per domain, `1` inner seed, `64` proposals per execution, `4,096` proposal ceiling) across all 5 search arms and both domains (`:portfolio` and `:scheduling`).
  - Verifies frontier headroom on both exact fixtures (`g1-portfolio.edn` and `g6-scheduling.edn`) and all 7 smoke pass-gates (`:objective-crosscheck`, `:feasibility`, `:budget-conservation`, `:deterministic-replay`, `:finite-values`, `:archive-validity`, `:failure-recording`).
  - Saves empirical timing and heap profiling to `docs/evidence/g7-smoke-pilot.edn`.

## Verified execution

- `:g7-test` (`test/niki/optimizer/g7_test.clj`): **3 tests / 72 assertions**, zero failures/errors on both Clojure 1.12.6 and 1.12.5 (`.\clojure.cmd -M:g7-test` and `.\clojure.cmd -M:g7-test:legacy-version`).
- `.\clojure.cmd -M scripts/run-smoke.clj`: all 10 domain $\times$ arm smoke runs (`32` slots $\times$ `2` tasks $\times$ `1` seed $\times$ `64` proposals = `4,096` proposal ceiling per arm) completed in **`6.997` seconds total** (`1.54` s for all 5 portfolio arms; `5.46` s for all 5 30-job scheduling arms) with peak observed heap `< 445 MiB`, saved in `docs/evidence/g7-smoke-pilot.edn`.
- **Empirical Flagship Compute Projection:** Extrapolating the measured smoke throughput ($192\times$ proposal scaling from smoke to flagship training) projects **~1.1 hours on 1 worker (~17–20 minutes on 4 workers)** on this Ryzen 7 9700X for the full 30-replication two-domain flagship study (`261,734,400` proposal ceiling).

## Remaining owner decisions required before G8 confirmatory lock

Per `FINAL_EXPERIMENT_PLAN.md` §16, Niki's explicit confirmation is required on three items before freezing the confirmatory protocol and launching G8:

1. **Prior data exposure disclosure:** Which historical crypto/portfolio periods, symbols, or results have already been inspected in earlier work (or should we use a newly sequestered/synthetic-plus-frozen benchmark snapshot)?
2. **Primary paper framing:** Confirm the recommended **methodology-first, two-domain study** (`:portfolio` + `:scheduling`).
3. **Compute budget approval:** Confirm the local replication count (e.g. `30` outer runs per arm/domain, or a scaled local budget based on the measured smoke timings in `docs/evidence/g7-smoke-pilot.edn`).
