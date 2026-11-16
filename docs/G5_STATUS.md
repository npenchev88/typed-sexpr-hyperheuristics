# G5 — Pilot data audit, five search arms and strong controls

**Status: ACCEPTED (November 8, 2026).** Development data audits, all five outer search arms, and direct deployment controls are implemented and verified.

## Implemented

- `src/niki/optimizer/portfolio_data.clj`:
  - `audit-task-window`: point-in-time date window validation (`start-date < end-date <= decision-date`), rejecting future-information leakage, window underflow/overflow, and non-monotonic dates.
  - `audit-split-footprints`: cross-split footprint verification ensuring strictly disjoint training, validation, and test date footprints (`latest(train) < earliest(validation) < earliest(test)`).
  - `generate-synthetic-panel` / `generate-synthetic-suite`: deterministic multi-factor synthetic portfolio benchmark generator (6 train, 6 validation, 24 test tasks) with binary64-safe returns for development and smoke verification without touching unapproved market holdouts.
- `src/niki/optimizer/baselines.clj`:
  - Published configuration parameter space (`parameter-space`) and seeded configuration sampler (`sample-conventional-config`) for Arm A5 (`:configured-conventional`).
  - Three conventional multiobjective solver families (`:nsga2` with fast nondominated sorting and 2-D crowding distance, `:pareto-local-search` with archive neighborhood exploration, and `:multi-start-restart` with scalarized weight schedules).
  - Three direct deployment controls (`:initializer-only`, `:random-search`, `:heuristic-local-search`).
  - Every conventional solver and direct control executes under the identical charge-before-construction proposal ledger (`:proposal-attempts <= solution-budget`), canonical validation, duplicate cache accounting, and uncapped G2 Pareto archive.
- `src/niki/optimizer/search.clj`:
  - Equal-slot outer assessment runner (`run-arm`) implementing all five search arms:
    - `A1 :gp-mixed`: Full GP (`variation/generate-program`, binary tournament selection on `[-mean-normalized-hv, ast-node-count, candidate-id]`, 50% `crossover` / 50% `mutate`, where crossover replaces mutation in its single slot and never grants free extra assessments).
    - `A2 :gp-mutation-only`: Identical initial population, parent selection, limits, and slot schedule as `:gp-mixed`, using 100% `variation/mutate`.
    - `A3 :random-dsl`: Independent `variation/generate-program` across all assessment slots, sharing the initial population seed schedule with `:gp-mixed` and `:gp-mutation-only`.
    - `A4 :formula-only`: Evolves only the `:score` subtree at path `[2 1 2 2 3]` inside the fixed canonical optimizer scaffold (`formula-scaffold`).
    - `A5 :configured-conventional`: Samples and assesses conventional solver configurations under the identical assessment-slot and inner-proposal allowances.
  - Enforces `:duplicate-consumes-slot? true` and `:failed-slot-consumes-slot? true`, deterministic finalist selection (`:max-finalists`), validation tie-breaking (`[:negative-mean-normalized-hv :ast-node-count :candidate-id]`), and held-out test evaluation without cross-seed archive pooling.

## Verified execution

- `:g5-test` (`test/niki/optimizer/g5_test.clj`): **4 tests / 771 assertions**, zero failures/errors on both Clojure 1.12.6 and 1.12.5 (`.\clojure.cmd -M:g5-test` and `.\clojure.cmd -M:g5-test:legacy-version`).

## Boundaries

- Synthetic suites are for development/smoke verification only; historical market data ingestion remains gated on Niki's prior-data-exposure disclosure in G7.
