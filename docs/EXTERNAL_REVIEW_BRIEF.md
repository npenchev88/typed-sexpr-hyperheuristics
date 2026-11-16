# Independent review brief — Niki · Typed Optimizer Lab

**Prepared for:** Niki's separate review with Gemini 4 Arnon or another independent model.  
**As of:** November 7, 2026.  
**Project root:** `C:\Users\meggy\Niki\typed-optimizer-lab`  
**Stop boundary:** G1 is complete. **Do not implement or continue G2**, change the experiment, access a holdout, or launch any optimization runs. The request is for a separate check and opinion.

## 1. What we are trying to establish

**Central question:** can evolving small, typed, executable optimizer programs produce useful search behavior beyond random program sampling, evolved scoring formulas in a fixed scaffold, supplied heuristics, and configured conventional algorithms?

We are evolving the **optimizer**, not merely a portfolio, a schedule, or a list of numerical hyperparameters. An outer individual is a restricted program tree. Executing that program creates an inner search over candidate solutions under explicit proposal and execution budgets. The proposed quality measure is held-out normalized hypervolume, with program complexity and computation reported separately. This is a methodology-first study, not a profitability study or a claim of unrestricted algorithm invention.

Conceptual pipeline:

```text
Approved typed language + fixed primitives + domain task
                     |
Outer search: generate / evolve / configure optimizer candidates
                     |
Inner execution: candidate optimizer proposes and evaluates solutions
                     |
Training assessment across tasks and seeds
                     |
Frozen automated validation selection, then freeze program/configuration
                     |
Locked held-out evaluation against strong controls
                     |
Evidence about quality, behavior, complexity, cost and failure
```

**Not yet demonstrated:** a new multiobjective optimizer, beneficial crossover, improvement over strong controls, cross-domain reuse, or future investment returns. The only new implemented milestone is G1's portfolio evaluator and numerical reference.

## 2. Where the motivation came from

### 2.1 Niki's research progression

The supplied October 31 Bulgarian planning documents describe a progression from comparing existing optimization algorithms, through multiobjective portfolio models, to constructing the search algorithm itself. They are the source of the earlier-work summaries below.[21][22]

**Provenance limitation:** the two original research-article PDFs discussed in those plans are **not present in this workspace**. Their models, findings and bibliographic titles below are therefore summarized from the supplied planning documents, not from a new independent inspection of the original articles. Publication years, venues and peer-review status are not inferred. The other public repositories listed in those documents were not newly downloaded or audited for this handoff. The preserved Clojure repository is available directly.

| Previous work or project | What the supplied materials describe | Continuity and caution for this experiment |
|---|---|---|
| **Penchev, N.; Marchev, A. jr. — Testing the Case of Knapsack-like Portfolio Optimization Using Various Evolutionary Algorithms** | Synthetic knapsack-like portfolio comparisons involving GA, ES, DE, memetic approaches, dynamic programming and random controls | Experience with strong baselines, exact references, scaling, seeds and quality/resource trade-offs. The earlier additive individual-asset “total Sharpe” score is not the Sharpe ratio of a jointly correlated portfolio.[21][22] |
| **Penchev, N.; Marchev, A. jr. — Multi-Objective Knapsack-like Portfolio Optimization using Evolutionary Algorithm** | Real NASDAQ data; binary selection with budget/class constraints; NSGA-II and other controls; Pareto metrics | Prior multiobjective/data-pipeline experience. The earlier risk is described as a sum of individual variances, not covariance-aware joint portfolio volatility. The planning documents report that more nondominated NSGA-II points did not imply higher hypervolume than the Lagrange procedure; this needs original-table verification before reuse as a paper claim.[21][22] |
| **evolving-portfolio-dsl** | Executable typed Clojure optimizer programs; mutation-only outer evolution; restricted interpreter; synthetic knapsack task; exact dynamic-programming optimum | Direct code foundation. It changes the scientific object from a solution to an executable search procedure, but the supplied greedy initializer explains almost all reported quality.[8] |
| **summer-school-2025-crypto-forecasting** | A GA over portfolio weights with covariance-based risk and Sharpe evaluation | Covariance is already part of the prior-work context; merely adding covariance is not the new contribution. This notebook summary is second-hand from the supplied plan.[21][22] |
| **Multi-objective-optimization**, **evolutionary-algorithms** | Multiobjective stock-data/model code and conventional knapsack optimizers | Potential controls and reproducibility material, not automatic new methodological contributions.[21][22] |
| **Sokoban-SS2024**, **graph-algorithms-101**, **recipe-for-dynamic-programming**, **neural-networks** | RL/NEAT, graph search, DP/knapsack, and regression work | Background experience only. These do not add mandatory domains to the proposed study.[21][22] |

The supplied plans also flag a provenance mismatch between earlier reported NASDAQ asset counts and available CSV snapshots. We have **not resolved or independently rechecked those historical datasets**. Do not silently reuse them or call them untouched holdouts.[21][22]

### 2.2 The crucial lesson from the preserved Clojure prototype

Pinned repository: `npenchev88/evolving-portfolio-dsl`, commit:

```text
29252c5b86d9f8a15099d85893e5e97861cb705d
```

This is a **single-objective synthetic 0–1 knapsack-like problem**, not the proposed mean/volatility portfolio model. It evolves initialization, repair and search-body compositions through typed S-expressions. Outer evolution is mutation-only; solution-level crossover elsewhere is not optimizer-program crossover.[8]

Its README reports, at 100 assets:

- Greedy-only normalized quality: **0.998205**.
- Evolved DSL quality: **0.998217**.
- Greedy-only uses **one** objective evaluation; stochastic deployed methods have a budget of **150**.
- Replacing the evolved body with random injection changes quality very little; removing greedy initialization causes a large drop.[8]

These are **historical repository-reported results**, not freshly rerun performance experiments. We have rerun its unit/integration tests and a bounded demo, not its full 30-seed experiments. The README and preserved result files are included for independent inspection.

**Design implication:** high benchmark quality can mostly reflect a heuristic supplied by the designer. The next study must establish headroom and attribute any improvement specifically to evolved search control, rather than merely selecting a powerful primitive or compressing its surrounding program.

### 2.3 McCarthy and symbolic program search

Niki supplied three McCarthy sources:

- **History of Lisp**: the supplied page is a 1979 retrospective. Programs represented as manipulable list structures and the interpreter motivate a practical program-as-data representation.[1][4]
- **Recursive Functions of Symbolic Expressions and Their Computation by Machine, Part I** (1960): symbolic program representation separated from execution motivates our AST/interpreter design.[2][18]
- **Programs with Common Sense**, introduction: formal representation of procedures/heuristics and the difficulty of finding useful behaviors motivate studying the space and effects of program edits.[3]

These are conceptual motivations, **not evidence that GP succeeds**. We are not implementing the Advice Taker, unrestricted host-code evaluation, self-modification of the evaluator, or an AGI claim. Symbolic regression supplies ideas about structured expression search, variation and complexity, but formula evolution alone is a narrower control condition here.[21][22]

### 2.4 Related work limits the novelty claim

The reviewed plan identifies strong prior art in strongly typed GP, executable optimizer evolution, GP-generated scheduling policies, and automatic multiobjective algorithm/component design. Clojure, types, S-expressions or crossover alone cannot establish novelty.[14][15][23]

The comparison should also distinguish this proposal from prior combinatorial multiobjective algorithm design and scheduling hyper-heuristics. This is a focused related-work map, not an exhaustive novelty clearance; the independent reviewer should challenge it.[24][25]

## 3. Proposed study — not an approved run

### Portfolio domain

Choose exactly K of N assets with equal weights. Provisionally N=50, K=5, with 365 historical return observations, subject to a point-in-time asset/data audit. For selected assets:

```text
r_p[t] = sum_i selected_i * r_i[t] / K
mean   = average_t r_p[t]
risk   = sqrt(sum_t (r_p[t]-mean)^2 / (T-1))
f      = [-mean, risk]          # both minimized
```

Risk includes joint asset movement; it is not a sum of individual asset risks. Standard deviation, not variance, defines the planned hypervolume geometry. Weights are analytically fixed each observation, not drifting buy-and-hold quantities. The earlier illustrative 10,000 USDT is not an extra optimization constraint under fractional, costless holdings.[21][22]

The original proposal names Binance Spot as a candidate starting data source. **No market snapshot, eligible universe or temporal holdout is currently locked.** G1 uses synthetic returns only.

### Scheduling domain

Proposed second domain: permutations of 30 non-preemptive jobs, all ready at time zero on one machine. Minimize mean completion time and total tardiness. Tiny correctness reference: 7 jobs / 5,040 permutations. Scheduling is not implemented. Makespan is deliberately not an objective because it is constant for these assumptions.[22]

Using the same frozen engine with separate per-domain training would demonstrate **framework reuse**, not unchanged-program cross-domain transfer.

### Controls and evaluation

Five proposed search arms:

1. Full GP with mutation and typed crossover.
2. Mutation-only GP.
3. Random DSL search.
4. Formula/scoring-only evolution in a fixed control-flow scaffold.
5. Configured conventional algorithm families.

Additional direct controls include supplied-initializer-only behavior, hand-designed heuristics and body/branch ablations. Conventional solvers must not be arbitrary weak defaults. The typing-efficiency comparison must keep the accepted language fixed and charge rejection/validation overhead.

The canonical proposal currently specifies 30 independent outer runs, 256 assessment slots per run, six training tasks and two inner seeds, eight maximum validation finalists, six validation tasks, 24 test tasks and ten fresh inner test seeds. Duplicates and failed slots consume allowance; these are not 256 guaranteed distinct programs. All values remain subject to a development resource/precision pilot, not post-test tuning.

Selection-validation is reserved for a frozen automated choice. Retain every outer run, account for failures and offline design cost, and do not equate overlapping windows or inner seeds with independent markets. The model-selection risk is explicitly part of the protocol.[26]

Configuration flags remain:

```clojure
:status :proposal
:confirmatory-ready? false
:launch-authorized? false
:holdout-status :not-locked
```

## 4. What is actually implemented and verified

**G1 is complete:** exact-K portfolio evaluator, aligned/finite input validation, independent sample-covariance oracle, exhaustive subset enumeration, synthetic fixture and verification outputs. **G2 is paused and not implemented.**

| Check | Recorded result |
|---|---|
| New G1 suite on Clojure 1.12.6 | 13 tests / 12,156 assertions; zero failures/errors |
| Same G1 suite on Clojure 1.12.5 | Same counts; zero failures/errors |
| Preserved upstream suite, each language version | 62 tests / 191 assertions; zero failures/errors |
| Proposal-data checks | 4 tests / 23 assertions; zero failures/errors |
| Exhaustive fixture, N=15 / K=5 / 12 observations | All 3,003 unique portfolios checked; zero failures |
| Additional numerical differential probe | 200 panels / 400 objective comparisons passed |
| Final independent AI static code review | Passed after recorded corrections; not publication peer review or a proof |

Primary evidence: `docs/G1_STATUS.md`, `docs/evidence/g1-regression.txt`, `g1-verification.edn`, `g1-objectives.csv`, `g1-code-review-03.json`, and `g1-deliverables.json`. A separate Python Fraction/80-digit Decimal check validated all exported rows and exact reference variances.

Maximum absolute discrepancy on the fixed fixture: **8.673617379884035e-19 for mean**, **3.469446951953614e-18 for risk**. Tolerance is `1e-12 + 1e-10*abs(reference)`; tiny-magnitude regressions have separate relative checks. These are numerical correctness results, not optimizer superiority results.

### Why the final arithmetic deserves separate scrutiny

Review caught (a) subtraction of a rounded large mean, (b) residual loss during first-observation shifts followed by cross-asset cancellation, and (c) Long overflow in the exact covariance reference. The RED/GREEN logs and unsuccessful reviews are retained rather than hidden.

The final direct path coerces inputs to binary64, then uses exact `BigDecimal(double)` accumulation:

```text
S[t] = exact sum of selected binary64 returns at t
Q    = sum_t S[t]
d[t] = T*S[t] - Q
mean = Q / (K*T)
var  = sum_t d[t]^2 / (K^2*T^2*(T-1))
risk = sqrt(var)
```

Only final divisions/root use DECIMAL128 and output conversion. The reference instead computes original-input rational asset means and sample covariance with promoting arithmetic. They share validation, not numerical implementations. Original-input versus normalized-binary64 comparisons must not be conflated. Rounded displayed period returns can be flat while correctly accumulated risk is nonzero. Exact intermediates add cost that still needs a representative runtime pilot.

## 5. What remains open

- G2: Pareto archive, normalization and hypervolume — **paused at Niki's request**.
- Bounded interpreter/resource ledger, typed variation/crossover, five search arms and controls.
- Scheduling adapter and exact oracle.
- Point-in-time universe, delisting/adjustment policies, prior-data-exposure disclosure and locked splits.
- Pilot costs, precision/sample-size analysis, exact inferential implementation and multiplicity handling.
- Approved compute budget and preregistration before confirmation.
- Redistribution/license review; pinned upstream has no tracked LICENSE/COPYING file.

Environment: portable Java 25.0.5+8, Clojure 1.12.6 with 1.12.5 alias, deps.edn workflow; no demonstrated need for Leiningen. Upstream requested Java 21, so current passing tests are compatibility evidence, not exact historical-environment replication. The review ZIP omits toolchain binaries/caches; rebuilding an environment is separate from inspecting the included code.

## 6. Requested independent opinion

Please be skeptical rather than endorsing the previous model. Do not implement anything or continue G2.

1. Is the research question meaningful and potentially publishable, and which contribution survives the closest prior art?
2. Does the design genuinely isolate evolved control flow from heuristic selection, scoring formulas and conventional configuration?
3. Are domains, controls, resource accounting and statistical units fair? What would make the study invalid or uninformative?
4. Audit G1 formulas, exact arithmetic, validation, coercion semantics, oracle independence and tests. Give a concrete counterexample for a suspected defect; distinguish executed results from static reasoning.
5. Is exact decimal accumulation a defensible correctness-first implementation, and how should its cost be treated in later resource comparisons?
6. What are the smallest changes needed **before resuming**, and which proposed components should be removed or deferred?
7. What source documents are still needed to independently verify the earlier-paper summaries and data provenance?

Return: **overall verdict; blockers; major/minor issues with file/line or source support; strengths; missing evidence; and prioritized recommendations.** Label what you actually executed. Do not promise positive performance, financial profit, publication acceptance or unrestricted generalization.

## 7. File precedence and reading order

1. This brief, then `docs/G1_STATUS.md` for current implementation state.
2. `docs/FINAL_EXPERIMENT_PLAN.md` and `configs/flagship-proposal.edn` for the proposed design.
3. `docs/DECISIONS.md` and `docs/REVIEW_RESOLUTION.md` for choices and unresolved gates.
4. New code/tests/fixture and G1 evidence.
5. Original Bulgarian PDFs, then pinned upstream README/source/results for research continuity.

**Known documentation caveat:** the November 2 plan's opening progress paragraph still contains pre-G1 “not done” wording, while its milestone table has a November 7 G1 completion annotation. The PDF/HTML/dossier are earlier planning snapshots. Use `G1_STATUS.md`, current code and timestamped evidence for implementation status. This inconsistency is disclosed, not silently treated as an unimplemented evaluator.

`docs/EXPERIMENT_OVERVIEW_CONTINUE.md` was a mistaken generated continuation with unapproved hypotheses and unverified placeholder references. It is marked withdrawn and deliberately excluded from the review package. It is not a competing protocol.

The numbered entries below are rendered from the existing project ledger. Earlier-paper summaries cite the supplied plans because the original articles are absent. Web literature is inherited from the reviewed source ledger, not newly exhaustively revalidated for this handoff.

## Sources

[1] https://www-formal.stanford.edu/jmc/lisp/lisp.html?utm_source=chatgpt.com — McCarthy (1979), History of Lisp
[2] https://www-formal.stanford.edu/jmc/recursive.pdf?utm_source=chatgpt.com — McCarthy (1960), Recursive Functions of Symbolic Expressions and Their Computation by Machine, Part I
[3] https://www-formal.stanford.edu/jmc/mcc59/node1.html?utm_source=chatgpt.com — McCarthy, Programs with Common Sense: Introduction
[4] https://www-formal.stanford.edu/jmc/lisp/node3.html — McCarthy, History of Lisp: The implementation of LISP
[8] https://github.com/npenchev88/evolving-portfolio-dsl — npenchev88/evolving-portfolio-dsl, pinned commit 29252c5b86d9f8a15099d85893e5e97861cb705d
[14] https://research.manchester.ac.uk/en/publications/automatically-designing-state-of-the-art-multi-and-many-objective — Bezerra, Lopez-Ibanez and Stutzle (2020), Automatically Designing State-of-the-Art Multi- and Many-Objective Evolutionary Algorithms
[15] https://davidmontana.net/papers/stgp.pdf — Montana (1995), Strongly Typed Genetic Programming
[18] https://www-formal.stanford.edu/jmc/recursive/recursive.html — McCarthy (1960), Recursive Functions: HTML edition
[21] file:///C:/Users/meggy/Niki/typed-optimizer-lab/docs/input/typed_clojure_portfolio_plan_bg.pdf — Niki supplied Clojure portfolio plan, 2026-10-31
[22] file:///C:/Users/meggy/Niki/typed-optimizer-lab/docs/input/optimizer_methodology_extension_bg.pdf — Niki supplied methodology extension, 2026-10-31
[23] https://arxiv.org/abs/2103.11746 — Lones (2021), Evolving continuous optimisers from scratch
[24] https://doi.org/10.1007/978-3-319-10762-2_50 — Bezerra et al. (2014), Automatic Design of Evolutionary Algorithms for Multi-Objective Combinatorial Optimization
[25] https://doi.org/10.1016/j.procir.2019.02.118 — Zhou and Yang (2019), Automatic design of scheduling policies for dynamic flexible job shop scheduling by multi-objective genetic programming based hyper-heuristic
[26] https://www.jmlr.org/papers/v11/cawley10a.html — Cawley and Talbot (2010), On Over-fitting in Model Selection and Subsequent Selection Bias in Performance Evaluation
