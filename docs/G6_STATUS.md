# G6 — Second domain: single-machine scheduling reuse and exact oracle

**Status: ACCEPTED (November 8, 2026).** Second domain adapter and exact 5,040-permutation oracle implemented and verified with **zero modifications** to the G1–G4 shared core (Hypothesis H5).

## Implemented

- `src/niki/optimizer/scheduling.clj`:
  - Single-machine non-preemptive scheduling of $J$ jobs available at time 0 (permutation representation).
  - Strict shape and numeric validation (`validate-instance`, `prepare-instance`, `validate-schedule`) rejecting lazy/non-vector shapes, mutable numbers, duplicate IDs, and invalid permutations before evaluation.
  - Minimized objectives: mean completion time $f_1 = \frac{1}{J} \sum_j C_j$ and total tardiness $f_2 = \sum_j \max(0, C_j - d_j)$.
  - Fixed analytical G2 normalization bounds (`scheduling-normalization`): $[0, P]$ for mean completion time and $[0, \sum_j \max(0, P - d_j)]$ for total tardiness, handling zero-width tardiness bounds on loose-deadline instances via `:constant-coordinates`.
  - Stratified instance and suite generator (`generate-instance`, `generate-benchmark-suite`) across the 6 $(\tau, R) \in \{0.2, 0.5, 0.8\} \times \{0.2, 0.6\}$ strata with disjoint seeds (6 train, 6 validation, 24 test instances).
- `src/niki/optimizer/scheduling_reference.clj`:
  - Independent exact reference oracle sharing zero helpers with `scheduling.clj`; enumerates all $J!$ permutations lexicographically and computes exact `BigInt`/`Ratio` completion times and tardiness before binary64 conversion.
- `src/niki/optimizer/scheduling_runtime.clj`:
  - Bounded runtime adapter (`make-adapter`) for the unchanged `niki.optimizer.runtime` supporting `:first` and `:random` (Fisher-Yates) initializers and `:swap`, `:restart`, `:insert`, and `:reverse` permutation operators, plus deterministic Shortest Processing Time (`spt-schedule`) and Earliest Due Date (`edd-schedule`) heuristics.
- `resources/fixtures/g6-scheduling.edn` & `scripts/verify-g6.clj`:
  - Deterministic 7-job synthetic fixture (`5,040` permutations).

## Verified execution

- `scripts/verify-g6.clj`: all **5,040** permutations checked against `scheduling-reference`; 100% exact agreement, 4 nondominated Pareto archive points, normalized hypervolume `0.4757567726027838`, Smith's rule verified (`spt-schedule` achieves global minimum mean completion time `61.857142857142854` across all 5,040 schedules). Evidence written to `docs/evidence/g6-verification.edn` and `docs/evidence/g6-front.csv`.
- `:g6-test` (`test/niki/optimizer/scheduling_test.clj`): **6 tests / 25,843 assertions**, zero failures/errors on both Clojure 1.12.6 and 1.12.5 (`.\clojure.cmd -M:g6-test` and `.\clojure.cmd -M:g6-test:legacy-version`).
- Cryptographic SHA-256 verification confirms `pareto.clj`, `dsl.clj`, `runtime.clj`, and `variation.clj` were byte-for-byte unchanged.
