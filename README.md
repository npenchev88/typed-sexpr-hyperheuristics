# Typed S-Expression Hyper-Heuristics in Clojure

Research codebase accompanying the paper *"Evolution of Multi-Objective Optimization Hyper-Heuristics via Genetic Programming with Typed S-Expressions in Clojure"* by Nikolay Penchev and Angel Marchev, Jr.

This repository implements a generative multi-objective optimization framework in Clojure that evolves complete, executable search algorithms encoded as homoiconic, statically typed symbolic expressions (S-expressions). Rather than tuning numerical parameters inside a fixed metaheuristic template, Genetic Programming evolves quoted algorithm trees `'(:program ...)` validated across six disjoint syntactic roles and static effect rules, then executed by a resource-bounded interpreter under strict proposal and runtime accounting.

---

## Key Features

- **Six-Role Typed Clojure DSL (`src/niki/optimizer/dsl.clj`):** Enforces role compatibility across `:operation`, `:count`, `:initializer`, `:operator`, `:predicate`, and `:score`, alongside static effect checks (`:pure`, `:control`, `:state`) and structural limits (max 128 nodes, depth 12, count 256).
- **Type-Directed Variation (`src/niki/optimizer/variation.clj`):** Supports `budget-aware-v2` and `development-only-v1` typed program generators, role-preserving subtree mutation, two-parent subtree crossover, and an `:untyped-validated` control mode (`0` rejections under typed `v2` vs. ~91% rejections without role typing).
- **Bounded Interpreter & Fair Cache Accounting (`src/niki/optimizer/runtime.clj`):** Caps per-instance search at $B = 256$ candidate proposals and enforces step limits. When evaluation caching (`cache? = true`) is active, revisited solutions reuse cached objectives to save wall-clock time while still counting against the 256-proposal budget.
- **Two Multi-Objective Combinatorial Benchmarks:**
  - **NASDAQ-50 Exact-*K* Portfolio Selection (`portfolio.clj`, `portfolio_data.clj`):** Equal-weight $K=5$ subsets from $N=50$ NASDAQ equities across 36 strictly non-overlapping 90-day windows (`2012-01-03` to `2026-02-27`, 8-day embargo gaps: 6 train, 6 validation, 24 held-out test quarters), using exact `DECIMAL128` arithmetic.
  - **OR-Library `wt40` Single-Machine Scheduling (`scheduling.clj`):** 36 stratified 40-job instances ($J=40$) minimizing mean completion time and total tardiness across `RDD, TF ∈ {0.2, 0.4, 0.6, 0.8, 1.0}`.
- **Five Experimental Search Arms (`search.clj`, `baselines.clj`) & Paired Bootstrap Statistics (`stats.clj`):** Compares `:gp-mixed`, `:gp-mutation-only`, equal-budget `:random-dsl`, fixed-template `:formula-only`, and validation-tuned `:configured-conventional` (NSGA-II, Pareto Local Search, Multi-Start Restart) across 30 independent outer seeds with 10,000-resample paired bootstrap confidence intervals, Holm–Bonferroni correction, and a $\pm 0.01$ equivalence margin.

---

## Summary of 30-Seed Confirmatory Results (`budget-aware-v2` Prior)

| Domain | Method | Test Norm. HV (Mean ± SD) | Runtime / Instance | Key Comparison vs. `:configured-conventional` |
| :--- | :--- | :---: | :---: | :--- |
| **NASDAQ-50 Portfolio** | `:gp-mixed` (`A1`) | `0.72975 ± 0.00768` | `127.7 ms` | **22% faster** (`127.7` vs. `164.6 ms`); higher return/risk (`4.33` vs. `4.24`); practically equivalent HV (`Δ = -0.00465`, wins 12/30 seeds) |
| **NASDAQ-50 Portfolio** | `:configured-conventional` (`A5`) | `0.73440 ± 0.00241` | `164.6 ms` | Validation-selected 18 NSGA-II / 12 PLS |
| **OR-Library `wt40`** | `:gp-mixed` (`A1`) | **`0.53974 ± 0.00438`** | `597.5 ms` | **`+0.01222` HV** (*p* < 0.0001, **wins 28/30 seeds**); strictly dominates knee schedule (`781.9 / 9,928.8` vs. `828.7 / 10,410.1`) |
| **OR-Library `wt40`** | `:configured-conventional` (`A5`) | `0.52751 ± 0.00212` | `632.8 ms` | Validation-selected 19 NSGA-II / 11 PLS |

Upgrading initialization from `development-only-v1` to `budget-aware-v2` yields `+0.02110` HV on NASDAQ-50 and `+0.02316` HV on `wt40` (*p* < 0.0001). All 7 publication figures in `docs/figures/springer/` (SVG) and `docs/figures/png300/` (360 DPI PNG) are generated directly from the experimental logs.

---

## Benchmark Data Setup

The public OR-Library `wt40` scheduling file (`data/orlib_wt40.txt`) is included directly in the repository.

To comply with market-data redistribution terms, historical NASDAQ-50 price returns (`data/nasdaq50_2012_2026.edn`) are **not** committed to GitHub. Before running portfolio experiments or `:g5-test`/`:g7-test` scripts that read the NASDAQ-50 panel, reconstruct `data/nasdaq50_2012_2026.edn` directly from Yahoo Finance using the bundled zero-dependency Clojure script:

**Windows PowerShell:**
```powershell
.\clojure.cmd -M scripts/fetch-nasdaq50.clj
```

**Linux / macOS / Git Bash:**
```bash
./clojure -M scripts/fetch-nasdaq50.clj
```

This downloads daily closing prices (`2012-01-03` to `2026-02-27`, `3,559` aligned trading dates yielding `3,558` daily simple returns) for all 50 NASDAQ tickers and writes `data/nasdaq50_2012_2026.edn`.

*(Note: `.\clojure.cmd` / `./clojure` expects JDK and Clojure CLI tools configured locally — see `docs/ENVIRONMENT.md` and `environment.lock.json`, or run with your system `clojure` CLI on Java 17+ using `-Srepro`.)*

---

## Running Tests and Reproducing Experiments

Run all verification test suites from the repository root:

```powershell
# Core domain, Pareto archive, DSL, variation, search, scheduling, and stats suites
.\clojure.cmd -M:g1-test
.\clojure.cmd -M:g2-test
.\clojure.cmd -M:g3-test
.\clojure.cmd -M:g4-test
.\clojure.cmd -M:g5-test
.\clojure.cmd -M:g6-test
.\clojure.cmd -M:g7-test

# Reproducibility verifications and smoke pilot
.\clojure.cmd -M scripts/verify-g1.clj
.\clojure.cmd -M scripts/verify-g2.clj
.\clojure.cmd -M scripts/verify-g6.clj
.\clojure.cmd -M scripts/validate-protocol.clj
.\clojure.cmd -M scripts/run-smoke.clj
```

To reproduce the confirmatory 30-seed run, mechanism ablations, and manuscript SVG figures (which update `docs/evidence/*.edn` and `docs/figures/springer/*.svg`):

```powershell
.\clojure.cmd -M scripts/run-thirty-replications-g8.clj
.\clojure.cmd -M scripts/summarize-g8-thirty-runs.clj
.\clojure.cmd -M scripts/run-budget-scaling-ablation.clj
.\clojure.cmd -M scripts/run-typed-vs-untyped-ablation.clj
.\clojure.cmd -M scripts/run-ast-knockout-ablation.clj
.\clojure.cmd -M scripts/run-cache-ablation.clj
.\clojure.cmd -M scripts/generate-springer-svgs.clj
```

---

## Repository Structure

```text
.
├── deps.edn                           # Clojure 1.12.6 dependencies and test aliases (:g1-test..:g7-test)
├── clojure / clojure.cmd              # Project-local reproducible Clojure launcher scripts
├── src/niki/optimizer/                # Core framework modules
│   ├── dsl.clj                        # Six-role typed S-expression DSL & static validator
│   ├── variation.clj                  # v1/v2 typed & untyped generators, subtree mutation & crossover
│   ├── runtime.clj                    # Resource-bounded AST interpreter & proposal ledger
│   ├── pareto.clj                     # Exact DECIMAL128 normalization & 2D hypervolume
│   ├── portfolio.clj / portfolio_data.clj / portfolio_runtime.clj
│   ├── scheduling.clj / scheduling_reference.clj / scheduling_runtime.clj
│   ├── baselines.clj                  # NSGA-II, Pareto Local Search, Multi-Start Restart
│   ├── search.clj                     # 5-arm outer meta-evolution & equal-budget runner
│   ├── stats.clj                      # Paired bootstrap CIs & Holm-Bonferroni correction
│   └── springer_figures.clj           # Matplotlib-style clean vector SVG plot generator
├── test/niki/optimizer/               # Automated unit & property test suites (G1–G7)
├── scripts/                           # Data fetcher (fetch-nasdaq50.clj), G8 runners, ablations & SVGs
├── data/
│   └── orlib_wt40.txt                 # Beasley OR-Library 40-job scheduling benchmark
├── configs/                           # Experiment protocol configuration files (.edn)
├── docs/
│   ├── PAPER_DRAFT_SPRINGER.md        # Extended Springer manuscript draft
│   ├── PAPER_DRAFT_ASTI_UNWE.md       # Condensed ASTI / UNWE template manuscript (<= 5,000 words)
│   ├── evidence/                      # Logged confirmatory & ablation EDN results
│   └── figures/                       # Clean publication SVGs and 360 DPI PNGs
└── PROJECT_EXPLANATION_BG.txt         # Full explanatory summary in Bulgarian
```

---

## Acknowledgements

This work was financially supported by the **UNWE Research Project NID NI-11/2026/A** (University of National and World Economy, Sofia, Bulgaria).
