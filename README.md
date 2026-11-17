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

## Prerequisites and Dependencies

No Python, R, C/C++ toolchain, GPU, or Leiningen installation is required to run the framework, test suites, data fetcher, or 30-seed experiments.

### Software & Language Dependencies

| Component | Requirement | Pinned / Verified Version | Notes |
| :--- | :--- | :--- | :--- |
| **Java JDK** | JDK 21+ (`JAVA_HOME` configured) | Eclipse Temurin `25.0.5+8` | `java.net.http.HttpClient` (used by `fetch-nasdaq50.clj`) and exact `DECIMAL128` math are built into the JDK |
| **Clojure CLI** | `clojure` CLI (`tools.deps`) | `1.12.6.1673` | On Windows 11 provided by `clj-msi` / `deps.exe` |
| **Clojure Core** | Auto-fetched via `deps.edn` | `org.clojure/clojure` `1.12.6` | Cached locally in `.cache/m2` on first run (no user `deps.edn` needed with `-Srepro`) |
| **Core Transitively Resolved Jars** | Auto-fetched via `deps.edn` | `spec.alpha` `0.5.238`, `core.specs.alpha` `0.4.74` | Verified SHA-256 checksums recorded in `environment.lock.json` |
| **Optional (`:legacy` alias only)** | Upstream comparison | Clojure `1.12.5`, `commons-math3` `3.6.1` | Only needed if restoring `vendor/evolving-portfolio-dsl` at its pinned commit |
| **Git** | Any modern Git (`>= 2.x`) | `2.56.1` | Used to clone the repository |

> **Important note on local wrappers (`clojure` / `clojure.cmd`):** The convenience wrapper scripts in the root directory point to `.tools/jdk` and `.tools/cli/deps.exe` (excluded from Git via `.gitignore`). On any fresh clone on Windows 11, macOS, or Linux, run **`clojure -Srepro ...`** directly once Java and the Clojure CLI are installed (see OS guides below).

### OS-Specific Installation

#### Windows 11 (PowerShell)
1. Install Git and Eclipse Temurin JDK 25 via `winget`:
```powershell
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.25.JDK -e
```
2. Install the native Windows Clojure CLI via the [`clj-msi` installer](https://github.com/casselc/clj-msi/releases/latest) (which bundles [`deps.clj`](https://github.com/borkdude/deps.clj) and Clojure tools for Windows; see `clojure.org/guides/install_clojure`).
   *(Alternatively, you can run inside WSL2 using the Linux steps below, or reconstruct the project-local `.tools/` layout recorded in `environment.lock.json` and `docs/ENVIRONMENT.md` and run `.\clojure.cmd`.)*
3. Open a new PowerShell window and verify:
```powershell
java -version
clojure -Srepro -M -e "(println (clojure-version))"
```

#### macOS (Apple Silicon / Intel via Homebrew)
```bash
brew trust clojure/tools
brew install git openjdk@25 clojure/tools/clojure
# Follow Homebrew caveats if needed to symlink openjdk@25 into /Library/Java/JavaVirtualMachines
java -version
clojure -Srepro -M -e "(println (clojure-version))"
```

#### Linux (Ubuntu / Debian & General Distributions)
Install Java 21+ (or Temurin 25 via Adoptium apt/tarball) and the official Clojure CLI installer:

```bash
# 1. Install Git, curl, bash, rlwrap, and OpenJDK 21+ (e.g. Ubuntu 24.04+)
sudo apt update && sudo apt install -y git curl bash rlwrap openjdk-21-jdk

# 2. Install the official Clojure CLI tools
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
rm linux-install.sh

# 3. Verify installation
java -version
clojure -Srepro -M -e "(println (clojure-version))"
```

### Hardware & Network Notes
- **First run:** Requires outbound HTTPS access to `repo.maven.apache.org` once to download Clojure `1.12.6` into `.cache/m2`, and to `query1.finance.yahoo.com` once when running `scripts/fetch-nasdaq50.clj`.
- **Confirmatory 30-seed runs:** Test suites run in seconds on any modern laptop; full 30-seed meta-evolution (`run-thirty-replications-g8.clj`) benefits from multi-core CPUs and 8–16 GB RAM (reference runs were executed on an 8-core AMD Ryzen 7 9700X with 32 GB RAM).

---

## Benchmark Data Setup

The public OR-Library `wt40` scheduling file (`data/orlib_wt40.txt`) is included directly in the repository.

To comply with market-data redistribution terms, historical NASDAQ-50 price returns (`data/nasdaq50_2012_2026.edn`) are **not** committed to GitHub. Before running portfolio experiments or `:g5-test`/`:g7-test` suites that read the NASDAQ-50 panel, reconstruct `data/nasdaq50_2012_2026.edn` directly from Yahoo Finance using the zero-dependency Clojure script:

```bash
git clone https://github.com/npenchev88/typed-sexpr-hyperheuristics.git
cd typed-sexpr-hyperheuristics
clojure -Srepro -M scripts/fetch-nasdaq50.clj
```

This downloads daily closing prices (`2012-01-03` to `2026-02-27`, `3,559` aligned trading dates yielding `3,558` daily simple returns) for all 50 NASDAQ tickers and writes `data/nasdaq50_2012_2026.edn`.

---

## Running Tests and Reproducing Experiments

Run all commands below from the repository root (`.\clojure.cmd ...` may be used interchangeably with `clojure -Srepro ...` if you use the Windows `.tools/` setup):

```bash
# Core domain, Pareto archive, DSL, variation, search, scheduling, and stats suites
clojure -Srepro -M:g1-test
clojure -Srepro -M:g2-test
clojure -Srepro -M:g3-test
clojure -Srepro -M:g4-test
clojure -Srepro -M:g5-test
clojure -Srepro -M:g6-test
clojure -Srepro -M:g7-test

# Reproducibility verifications and smoke pilot
clojure -Srepro -M scripts/verify-g1.clj
clojure -Srepro -M scripts/verify-g2.clj
clojure -Srepro -M scripts/verify-g6.clj
clojure -Srepro -M scripts/validate-protocol.clj
clojure -Srepro -M scripts/run-smoke.clj
```

To reproduce the confirmatory 30-seed run, mechanism ablations, and manuscript SVG figures (which update `docs/evidence/*.edn` and `docs/figures/springer/*.svg`):

```bash
clojure -Srepro -M scripts/run-thirty-replications-g8.clj
clojure -Srepro -M scripts/summarize-g8-thirty-runs.clj
clojure -Srepro -M scripts/run-budget-scaling-ablation.clj
clojure -Srepro -M scripts/run-typed-vs-untyped-ablation.clj
clojure -Srepro -M scripts/run-ast-knockout-ablation.clj
clojure -Srepro -M scripts/run-cache-ablation.clj
clojure -Srepro -M scripts/generate-springer-svgs.clj
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
