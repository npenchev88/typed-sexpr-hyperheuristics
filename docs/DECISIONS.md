# Decision record

## Fixed for the implementation plan

- Working title: **Typed Evolution of Executable Optimizers**.
- Paper direction: methodology-first is the recommendation; portfolios are the first domain.
- Recommended flagship: portfolio subset selection plus single-machine scheduling permutations, with separate evolution using a frozen shared core.
- Do not claim frozen-program cross-domain transfer from framework reuse.
- Primary portfolio risk: sample standard deviation of joint portfolio returns; CVaR is deferred.
- Keep Clojure and a restricted interpreter. Use `deps.edn`; do not add Leiningen without a concrete dependency on it.
- Preserve the previous knapsack repository and its original reported results separately. Do not silently redefine its objective.
- G1 completed November 7, 2026: exact N=15, K=5 portfolio evaluator, independent reference, all 3,003 selections checked and independent code review passed; see `G1_STATUS.md`. Confirmed G1 contract gaps are corrected; G2 Pareto/HV checks and independent review are complete; see `G2_STATUS.md`. G3 bounded DSL/runtime/ledger and G4 typed generation/mutation/crossover are complete and verified; see `G3_STATUS.md` and `G4_STATUS.md`. G5 (point-in-time/footprint audits, five search arms, conventional NSGA-II/PLS/restart families, and direct controls), G6 (single-machine scheduling domain, 5,040-permutation exact oracle, and runtime adapter using the unchanged G1–G4 core), and G7 technical pilot & statistical analysis pipeline (`stats.clj` paired bootstrap + Holm-Bonferroni + smoke profiler) are complete and verified (November 8, 2026); see `G5_STATUS.md`, `G6_STATUS.md`, and `G7_STATUS.md`. Confirmatory G8 launch remains gated on Niki's owner decisions.
- Five search arms: mixed GP, mutation-only, Random DSL, formula-only, configured conventional families.
- A source-code test pass is not an experiment result. An experiment report includes failed and timed-out runs.
- The existing files under `configs/` are **proposed protocol data**, not an implemented experiment runner.

## Proposed values to freeze after a development pilot

- Portfolio N=50, K=5; scheduling 30 jobs.
- Six training, six validation and 24 test instances per domain.
- 256 outer candidate-assessment slots; six training tasks × two seeds × 256 inner proposals per assessment.
- 30 independent outer runs per arm/domain, subject to development-only precision/power analysis.
- At most eight validation finalists per outer run; one frozen selected representative; ten fresh inner test seeds.
- 50:50 mutation/crossover mixture; structural limits and numerical tolerances still require implementation/pilot validation.
- Normalized-HV reference (1.05,1.05), unit-rectangle divisor 1.1025; proposed practical margin 0.01 absolute score units. Proposal v1.2 uses exact-K feasible mean extrema for portfolio return bounds; risk and scheduling bounds remain conservative. G2 endpoint tolerance is 1e-12 normalized units, to freeze before confirmation.
- Primary program selection: maximum validation mean score, size only as a frozen tie-breaker. Smallest-within-1% is not the primary selector.
- Four primary Full-GP contrasts in each domain; preregister the two-domain family before testing.

## Unresolved gates

| Decision | Why it matters | Can early implementation proceed? |
|---|---|---|
| Previously inspected data/periods | Determines whether any historical set can honestly be called untouched | Yes, using synthetic fixtures/development data |
| Historical eligible-asset availability | Determines whether N=50 is feasible at the proposed training dates | Yes |
| Point-in-time and delisting policy | Determines the scope of market claims | Yes |
| Publication emphasis | Chooses methodology vs financial-application priorities | Yes; methodology is the current recommendation |
| CPU-hour allowance | Controls final replication count and the two-domain launch | Yes, bounded smoke tests only |
| Exact structural/fuel/timeout bounds | Must be empirically safe and uniformly applied | Yes, but no confirmatory launch |
| Statistical resampling/block protocol | Prevents pseudoreplication and post-hoc analysis choices | Yes |
| Strong-baseline implementations and parameter spaces | Prevents a comparison against arbitrarily weak defaults | Yes |
| Data/solver redistribution and licensing | Determines what the published artifact may include | Yes |
| Upstream code licensing | No tracked LICENSE/COPYING file was found in the pinned checkout; public availability alone does not establish redistribution rights | Yes, local evaluation only |

The proposed retrospective calendar is an illustration. The test set is **not locked**. There is no basis to label 2024–2025 unseen without Niki's disclosure of prior use.

## Deliberately deferred

Real orders; profitability claims; CVaR; forecasting; LLM-generated optimizer proposals; islands; recursive subroutine invention; automatic tuning of the evolution mechanism; arbitrary cross-domain crossover; additional domains; a visual laboratory; external compute; and scheduled long-running experiments.

## How to revise

Before test access, revise the protocol with a recorded rationale and version. After test access, retain the original confirmatory analysis, label new analyses exploratory, and reserve a new untouched holdout for a revised claim. Do not overwrite provenance or silently reuse the same test set as independent evidence.
