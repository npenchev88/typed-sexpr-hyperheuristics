# Typed Evolution of Executable Optimizers
## Niki · Final architectural plan and proposed confirmatory protocol v1.1

**Prepared:** November 1, 2026. **Reviewed and finalized for implementation:** November 2, 2026. **Status:** ready to guide implementation, not yet preregistered or empirically validated. The final dataset, holdout provenance and computational allowance remain release gates. Final means a consolidated implementation plan, not a completed experiment or a sealed preregistration.

> **Recommendation:** a methodology-first, two-domain study. Evolve complete, bounded optimizer programs; establish correctness on exact tiny cases; compare against strong, equally configured alternatives; then replicate with an unchanged core on scheduling. Do not make profitability, universal optimization, or beneficial crossover prerequisites for an honest result.

## 1. What has actually been done

The desktop project **Niki** is anchored at `C:\Users\meggy\Niki\typed-optimizer-lab`. Both supplied PDFs have been copied without modification and hashed. The upstream repository is preserved at commit `29252c5b86d9f8a15099d85893e5e97861cb705d`.

A portable Temurin **25.0.5+8** JDK and native **deps.clj 1.12.6.1673** launcher are installed inside this workspace. The default Clojure language is **1.12.6**; an alias retains the upstream language version **1.12.5**. The upstream suite passed **62 tests / 191 assertions / zero failures / zero errors** with each language version. A legacy DSL demonstration also ran. Both test runs used the new Java 25 installation; they do not reproduce the upstream Java 21 environment or its complete published experiments. Logs are in `docs/evidence/`.

**Not done:** the new portfolio evaluator, multiobjective runtime, typed crossover, market-data snapshot, scheduling adapter or confirmatory runs. This document contains proposals, not invented experimental results.

The two EDN proposal configurations now exist. Their consistency checks passed **4 tests / 23 assertions / zero failures / zero errors**. They explicitly mark the confirmatory launch as unapproved and the new experiment runner as unimplemented. These are configuration checks, not tests of the proposed optimizer.

The supplied documents provide the research continuity and much of the initial specification. This plan makes their warnings operational, adds a configured-conventional-solver comparator, tightens temporal separation, and bounds the flagship workload.[21][22]

## 2. Scientific positioning

### Central question

**Can search over small, typed, executable programs discover compact optimization procedures that improve held-out Pareto approximation under an explicit resource contract, beyond random program search, formula-only evolution and configured conventional algorithms?**

The individual in the outer search is an **optimizer program**. The inner search produces **candidate solutions and a nondominated archive**. These are different populations, objectives and budgets.

The intended contribution is a combination of:

1. A small language with precise domain, type, effect and resource semantics.
2. An auditable implementation of program generation and bounded execution.
3. A causal comparison of search, evolvable control flow, and variation policy.
4. A reusable domain interface demonstrated on two different representations.
5. An artifact that exposes failures, compute costs and the actual discovered programs.

Automatic algorithm design is already established: Bezerra, López-Ibáñez and Stützle describe automatically configured multiobjective algorithms. Therefore, using Clojure, types, or recombination is not by itself a novelty claim. A focused related-work comparison must precede a paper's final novelty statement.[14]

### Closest precedents and the contribution we must actually demonstrate

| Prior work | What is already established | Our proposed discriminating test |
|---|---|---|
| Montana, *Strongly Typed Genetic Programming* (1995) | Type-constrained genetic programming is prior art.[15] | Type-directed vs generate-and-validate proposals in the same legal language; useful quality per resource, not just fewer syntax errors |
| Lones, *Evolving continuous optimisers from scratch* (2021) | Stateful executable optimizer evolution using Push is prior art.[23] | Whether evolvable orchestration of discrete, multiobjective search adds value beyond a fixed scaffold; disclose everything our framework keeps fixed |
| Zhou and Yang, scheduling policies through multiobjective GP (2019) | GP-generated scheduling policies are prior art; their dynamic flexible-job-shop formulation differs from ours.[25] | Full control flow versus scoring-rule evolution and competent scheduling controls; a changed scheduling problem is not itself a new methodology |
| Bezerra et al., automatic MOEA design for combinatorial optimization (2014) | Automatic component configuration has already been tested on permutation-flowshop variants.[24] | Compare our DSL search to a configured component/template space, not only default solvers |
| Bezerra et al., automatic multi-/many-objective algorithm design (2020) | Automatically designing MOEAs and using multiple design metrics are established.[14] | Controlled evidence about quality, complexity, cost and behavior across the specified two domains |

**Candidate contribution:** identify when search over bounded, executable control structures adds useful behavior beyond evolved scoring, configured components and supplied heuristics. This focused review is not an exhaustive novelty clearance. The study must show actual behavioral changes and attribution, not only valid or shorter trees.

“Whole-program evolution” means the entire permitted optimizer AST. The grammar, primitive implementations, evaluator, safety envelope, archive semantics and outer-search machinery remain fixed. It does not mean unrestricted invention of an optimizer or self-modification of its judge.

### What McCarthy contributes to the argument

- **History of Lisp:** the supplied page is a 1979 retrospective, not the original 1960 paper. Its account of programs represented as lists and the emergence of an interpreter motivates manipulable program representations.[1][4]
- **Recursive Functions of Symbolic Expressions (1960):** functions can themselves be represented as symbolic expressions, with an interpreter defining execution. Our design adopts this representation/interpreter separation, not unrestricted execution of generated host-language code.[18][19][20]
- **Programs with Common Sense (1959):** the introduction identifies a difficulty with searching arbitrary behavior representations: useful behaviors are sparse and meaningful behavioral changes may be hard to express. Our proposed empirical connection is to measure whether typed, domain-appropriate edits produce useful behavioral changes.[3]

These works are intellectual motivation, **not evidence that GP will succeed**. We are not reproducing the Advice Taker, claiming AGI, or allowing the evolution mechanism to rewrite its own evaluator. The supplied 1960 PDF is archived locally.[2] Relevant sections were read through Stanford's HTML edition; formula images missing from HTML text were not treated as parsed equations. This is a focused reading, not a claim to have verified every displayed formula.

## 3. Three scope scenarios

| Scenario | Deliverable | Defensible claim | Decision |
|---|---|---|---|
| **A. Focused portfolio study** | Exact oracle, portfolio runtime, five search arms, strong direct solvers, locked evaluation | Evidence about executable optimizer search on the defined portfolio tasks | Fallback if resources or historical eligibility block the second domain |
| **B. Two-domain methodology study** | A plus a scheduling adapter, the same frozen core, separate evolution in each domain | Tested methodology reuse across subset and permutation problems | **Recommended flagship** |
| **C. Frozen-program transfer / richer language** | A/B plus a common abstract operator interface, genuinely unchanged transferred programs, or learned subroutines | Only the specific transfer or abstraction tested | Later study; not a prerequisite for B |

A financial-application-first paper is a separate scope choice: it needs execution assumptions, costs, selection rules and market realism beyond this optimization study. Do not enlarge B by quietly adding trading, CVaR, forecasting, LLM proposals and extra domains.

## 4. Confirmatory hypotheses and failure conditions

All effects below concern **held-out normalized hypervolume**, not the number of nondominated points or future financial returns.

| ID | Contrast | Question | What would not support it? |
|---|---|---|---|
| H1 | Full GP vs Random DSL Search | Does evolutionary search add value beyond sampling the same program space? | No practically meaningful held-out improvement |
| H2 | Full GP vs evolved scoring in a fixed scaffold | Does evolvable control flow add value? | Formula-only is as good or better under the frozen comparison |
| H3 | Mutation+crossover vs mutation-only | Does the specified recombination policy help? | Improvement absent, harmful, or too uncertain |
| H4 | Full GP vs configured conventional families | Is there useful advantage over a stronger practical alternative? | Gains vanish after fair tuning or matched-time comparison |
| H5 | Portfolio and scheduling with unchanged core | Can the framework be reused without domain-specific changes to its engine? | Runtime, type system or search requires task-specific rewrites |

H5 is a software/experimental-reuse claim, not proof that a frozen portfolio optimizer transfers to schedules. Separate scheduling evolution is allowed; any changes to the core after its freeze must be logged and trigger reruns under the new version.

**Proposed practical threshold:** an absolute difference of **0.01 normalized-HV units**. This is a design choice, not an established domain standard. Validate its relevance and achievable precision during development, then freeze it. Do not replace it after inspecting confirmation results. Confidence intervals crossing meaningful benefit and harm are inconclusive, not evidence of equivalence.

## 5. Domain contracts

### 5.1 Portfolio: exactly K-of-N, equal weights

Primary deployment size is provisionally **N=50, K=5**, subject to a historical-data availability audit. The exact correctness case is **N=15, K=5: 3,003 feasible portfolios**, calculated programmatically.

For aligned simple daily returns `r[i,t]`, select binary `x[i]` with `sum(x)=K`, then `w[i]=x[i]/K`:

```text
r_p[t]   = sum_i w[i] * r[i,t]
mean     = sum_t r_p[t] / T
variance = sum_t (r_p[t] - mean)^2 / (T - 1)
risk     = sqrt(variance)
f        = [-mean, risk]                    ; both coordinates minimized
```

Require `T >= 2`, finite values, valid price denominators and identical dates. Independently test direct-series calculations against `mu^T w` and `w^T Sigma w` with sample covariance. Include zero volatility, identical assets, anticorrelated assets, ties and singular covariance matrices. Do not replace portfolio risk with a sum of individual risks (main input §8; extension §§3–4).[21][22]

The optimization scenario uses fixed weights analytically. A later buy-and-hold simulation must keep initial quantities and allow weights to drift. Simulated 10,000 USDT is an illustration, not an extra constraint when quantities are fractional and costs are absent. USDT is not assumed identical to USD (main input §§7–9).[21]

Use return and **standard deviation**, not variance, for reported HV. A monotone square root preserves dominance, but not HV geometry (main input §§8,12).[21]

### 5.2 Scheduling: a genuinely different representation

Primary scheduling instances contain **30 jobs**, all available at time zero, non-preemptive, on one machine. A solution is a permutation. For completion times `C[j]` and due dates `d[j]`, minimize:

```text
f1 = mean_j C[j]
f2 = sum_j max(0, C[j] - d[j])
```

The exact correctness case contains **7 jobs: 5,040 permutations**, calculated programmatically. Makespan is deliberately not an objective here: without idle time it is the same sum of processing times for every order (extension §5).[22]

Proposed generator: integer durations from 1 through 100; total duration `P`; deadlines `max(1, round(P*u))`, with `u` uniform on `[1-tau-R/2, 1-tau+R/2]`. Use six strata from `tau in {0.2,0.5,0.8}` and `R in {0.2,0.6}`. Assign one independent training instance and one validation instance per stratum, then four test instances per stratum. Seed lists are disjoint.

Audit the generator on development instances. Very loose or uniformly late deadlines can make the objectives nearly aligned; retain such cases as labeled sanity checks rather than allowing them to dominate the flagship. Any revision to generator strata happens before freezing test seeds and without selection on method performance.

Operators are swap, insertion and bounded segment reversal. Exact permutation validity is checked after every operation. New size and deadline-regime generalization are secondary extensions, not extra primary endpoints.

## 6. Language, architecture and proof obligations

### Layers

| Layer | Responsibility | May depend on the domain? |
|---|---|---|
| Program AST and grammar | Serialization, node paths, bounded forms | Via an explicit domain parameter |
| Type/effect checker | Arity, roles, domain identity, allowed effects | Operator signatures only |
| Variation | Typed generation, subtree mutation, compatible crossover | Through the same signatures |
| Interpreter and resource ledger | Execute allowed instructions, charge costs, terminate and diagnose | No hidden portfolio special cases |
| Domain adapter | Solution validity, proposals, objective vector, static features | Yes |
| Pareto/HV core | Dominance, archive, indicators | Objective dimension and frozen normalization |
| Outer search | Assessment, selection, complexity control, lineage | No |
| Experiment runner | Data manifests, split protection, seeds, metrics, failures | Declarative domain/configuration |

Suggested new namespaces are under `src/niki/optimizer/`, separate from the preserved upstream checkout. Namespace names are an implementation proposal, not a list of already existing modules.

The runtime contract is:

```text
run(program, permitted-instance, solution-budget, step-budget, seed)
    -> {archive, charged-costs, status, trace, diagnostics}
```

Use `Program[Domain]`, `Predicate[Domain]`, `Operation[Domain]`, `Initializer[Domain]`, `Score`, `Count`, and bounded numeric roles. A portfolio operation must not inhabit a schedule-program slot. Static types are a property of the DSL; they do not make the host Clojure language statically typed (main input §§2,11; extension §2).[21][22]

Start with sequencing, a bounded repeat wrapper, predicates, restart/perturbation, local search and small pure scoring expressions. No local binders, unrestricted recursion, host interop, filesystem/network operations, or arbitrary Clojure `eval`. Parse an allowed EDN subset with tagged forms disabled/rejected, validate node limits before execution, and whitelist every operation.

### Three propositions to establish, not assume

**P1 — replacement preserves well-typedness under explicit conditions.** If a child subtree has the same domain-indexed type and permitted effects as the replaced subtree, and the enclosing context is unchanged, substitution preserves the typing judgment for the binder-free language. Arity and depth/node limits are separate checks. Deliver a grammar, inference rules, a structural-induction proof sketch and generated counterexample tests. Adding lexical bindings would require scope-aware rules.

**P2 — the interpreter respects resource bounds.** If every solution request is charged before evaluation, every interpreter transition consumes fuel, and every primitive has a bounded implementation, no completed run exceeds the requested proposal or fuel budgets. Validate that the system fails closed on exhausted budgets. Fuel alone does not bound an unbounded host call: worker wall-time and memory limits remain separate operational guards.

**P3 — an uncapped exact-dominance archive has nondecreasing HV.** Under fixed objectives, normalization and reference point, adding a feasible finite candidate and removing only dominated/duplicate objective points cannot reduce the archive's dominated volume. This obligation does not hold automatically for archive truncation or changing normalization. Use a fixed exact dominance rule over computed values; do not introduce a nontransitive approximate-dominance comparator.

Property tests support implementation confidence; they do not replace a proof. A small reference implementation and exhaustive fixtures are stronger than merely obtaining a green test count.

### Recombination details

Protect the root and budget wrapper. Select compatible subtree sites, enforce type/domain/effect compatibility, then validate both whole children and structural limits. Use at most ten proposal attempts per assessment slot. A failed crossover has a predetermined fallback and a recorded reason. In the mixed arm, crossover **replaces** mutation in its offspring slot; it must not receive an extra child or fitness assessment.

Initial proposed mixture: 50% crossover, 50% mutation, with shared initial populations between the two GP arms. This is a frozen policy to test, not a claim that 50% is optimal. A random-donor, size-matched diagnostic can later distinguish useful inheritance from simply making larger edits.

## 7. Five search arms and additional deployment controls

| Arm | Search object | Required control |
|---|---|---|
| A1 Full GP | Program control flow and scoring | Mutation+crossover |
| A2 Mutation-only | Same program space | Same initialization, selection, limits and budget |
| A3 Random DSL | Independent typed programs | Same grammar, initial pool and training-fixed size/depth prior |
| A4 Formula-only | Scoring expression in a fixed optimizer scaffold | Same primitive access and scoring terminals |
| A5 Configured conventional algorithms | Parameters and family choice | Same training-assessment and validation allowances |

A5 should contain NSGA-II, multi-start Pareto local search and simple fixed restart schedules. The scheduling versions include shortest-processing-time and earliest-due-date initializers with swap/insertion search. Configuration can initially use bounded random configuration search over a published, preregistered parameter space; do not compare carefully tuned GP against default-only algorithms. Audit these implementations and cross-check at least a small set with an independent implementation.

A5 is one family-selection/configuration procedure with the same total assessment allowance as each GP arm, not a separate full allowance for every family followed by an uncharged winner selection. Publish its component space and search distribution. For a stronger follow-up, add a dedicated algorithm configurator under its own explicit allocation. A restricted random configurator cannot support a claim of beating every automatic-design method.

Separately report feasible random solution search, initializer-only behavior and straightforward hand-designed heuristics. The same knowledgeable initializer or local-search primitive must be available to the relevant controls. The supplied baseline's own README reports initializer-dominated performance, which makes this particularly important (main input §1).[8][21]

Use exact enumeration for small tasks. For larger portfolios, an epsilon-constrained mixed-integer quadratic formulation is a separately timed reference option: minimize variance subject to a return threshold and cardinality constraints. A finite threshold grid is an approximate front even when individual subproblems are solved optimally. Log solver version, licenses, gaps and timeouts. OR-Library's supplied fronts are unconstrained and must not be relabeled as exact fronts for our equal-weight cardinality model (main input §12).[9][21]

## 8. Data, holdout and leakage controls

### Dataset provenance

For portfolios, start from the official Binance Spot archive, not a live account. It documents ZIP checksums and a change to microsecond spot timestamps from January 1, 2025. Archived files can subsequently change; a dataset must therefore pin actual file hashes, not just filenames.[10]

The ingestion manifest must include: source URL, retrieval time, SHA-256, schema, timestamp unit, UTC boundaries, symbols, quote currency, observed listing history, missing-data decisions, transformation version and processed-data hash. Verify the publisher checksum when provided. Never silently replace a frozen snapshot.

Eligibility at date `d` may use only observations available then. A provisional universe is the top 50 eligible USDT pairs by trailing quote volume, with a preregistered stablecoin/leveraged-token policy. The history and eligibility audit must precede committing to N=50. Do not fill prelisting data, backfill from future prices or use today's survivors as a historical universe. If historical membership cannot be reconstructed, narrow the claim to the explicit fixed-universe benchmark and disclose survivor selection (main input §§7,9).[21]

### Two different kinds of leakage

1. **Future-information leakage:** a task sees data after its allowed decision date, or eligibility depends on what happened later. This is prohibited.
2. **Cross-split information reuse:** training and test tasks have different endpoint dates but share observations in their 365-day histories. This can be a realistic deployment setting, but it is not a clean test of unseen market information.

For the stronger flagship claim, separate the full data footprints, including closing prices needed to construct returns, preprocessing and universe selection. Within-split rolling windows will still be dependent. Point-in-time metadata and forward outcomes, if later added, must be included in the audit.

### Conditional retrospective calendar

This is an **illustration, not an approved holdout**:

| Split | Task endpoints | Required daily-close footprint for 365 returns |
|---|---|---|
| Train: six instances | 2019-03-31, 06-30, 09-30, 12-31; 2020-06-30, 12-31 | Earliest 2018-03-31; latest 2020-12-31 |
| Validation: six | Month-ends July–December 2022 | Earliest 2021-07-31; latest 2022-12-31 |
| Test: 24 | Month-ends January 2024–December 2025 | Earliest 2023-01-31; latest 2025-12-31 |

The calendar arithmetic is saved in `docs/evidence/proposed-calendar.json`. It is conditional on asset availability and prior inspection. On **November 1, 2026**, these are all historical dates. They are not automatically unseen simply because a new program has not yet been run on them.

Before preregistration, Niki must disclose which periods, symbols and results have already influenced design. Previously inspected periods remain development/retrospective evaluation data. If an honest untouched historical holdout cannot be established, use a newly sequestered dataset or a prospective period fixed after preregistration. New random seeds do not undo exposure to a market period.

Test data must be inaccessible to program search and validation selection. The instance handed to the runtime contains only permitted features; raw future-price files belong outside that object and process. A hash is provenance, not an access-control system: the experiment runner must enforce the split.

## 9. Metrics and selection

### Fixed normalization

Use minimized objectives. For portfolio return, derive bounds from allowed instance data: `[-max(mu_i), -min(mu_i)]`. For risk, use `[0, max(sigma_i)]` under nonnegative weights summing to one (main input §12).[21] For scheduling, our proposed safe bounds are `[0,P]` for mean completion and `[0, sum_j max(0,P-d_j)]` for total tardiness. Handle a zero-width range by assigning zero to that constant normalized coordinate, and flag the degenerate instance.

Map each coordinate to `[0,1]`, then use reference point `(1.05,1.05)`. Define:

```text
normalized-HV = HV(normalized archive; reference) / 1.1025
```

The divisor is computed from the reference rectangle and keeps the proposed score on a unit scale for valid normalized points. Never derive bounds from competitors' test outcomes. Reject or diagnose values materially outside analytical bounds; do not silently clip them into a better score. Tiny floating-point tolerances are frozen in advance.

### Primary and secondary endpoints

- **Primary:** mean per-execution normalized HV at **256 charged solution proposals**, first averaging ten fresh inner seeds per test instance, then the predetermined instance weights.
- Do not pool the archives from ten executions and present their union as one 256-proposal run.
- **Secondary:** HV traces at intermediate budgets, elapsed and CPU time, unique candidates, full objective calculations, failures, node/depth counts, behavioral diversity and operator usage.
- Use IGD+ only where a separate reference front is available; label approximate references clearly. Nondominated-point count is descriptive, not a victory criterion (main input §12).[21]

Outer search can use `(-training mean normalized-HV, program size)` with a training hall of fame. Select at most eight finalists by a deterministic training-only diversity/quality rule. On validation, select the highest mean normalized-HV; use size and canonical identifier only for numerical ties under a frozen tolerance. This avoids choosing a post-hoc simplicity tolerance from test performance. The earlier smallest-within-1% suggestion remains a separately labeled selector sensitivity, not the primary selector (main input §13).[21]

Retain one selected program/configuration from **every** outer run. Do not report only the best outer seed. Freeze its AST/configuration, evaluator version, feature schema and hashes before testing.

Conduct human tuning and pilot redesign on development data within the training side. Reserve selection-validation for one automated pass after the training procedure is frozen. Selection itself is part of the method being evaluated; finite validation data can also be overfit.[26] If selection-validation results trigger redesign, record that exposure and acquire a new selection set before making the corresponding clean-selection claim. Never use test feedback to choose the selector.

## 10. Resource accounting and proposed compute envelope

### Inner budget

Each proposed candidate consumes one unit, including duplicates. All neighborhood candidates, objective-dependent scorer queries and repair proposals that inspect objectives must go through the same ledger. A cache saves work, not search opportunities. Record at least: proposal attempts, valid candidates, unique candidates, cache hits, actual objective calculations, interpreter steps, elapsed time and CPU time.

Use objective-free feasibility repair where possible. A hidden scan of hundreds of neighbors cannot count as one evaluation. Preprocessing is shared and separately timed. The archive is initially uncapped; its size is bounded by the proposal count.

### Outer budget

Use **256 candidate-assessment slots** per search arm and outer run, not an unlimited stream of free rejected/cached candidates. A possible GP schedule is 32 initial candidates followed by seven batches of 32 offspring; parent survivors are not re-assessed unless the evaluation contract changes. A duplicate proposed AST consumes a slot but may reuse its fitness. Permit at most ten construction attempts per slot, then record a failed slot under a frozen fallback policy.

This is a deliberate **equal-slot** protocol, not a promise to evaluate 256 distinct ASTs. Report distinct evaluated programs and construction attempts separately. Failed or cached slots do not release allowance for extra candidates; constant tuning also consumes assessment slots. A separately labeled equal-distinct-program sensitivity is possible, but must report its extra construction/time cost. Do not combine the accounting rules of those two experiments.

Evaluate each assessed program/configuration on six training tasks and two common inner seeds with inner budget 256. All arms share these allowances. Give validation at most eight finalists × six tasks × two seeds. Freeze one representative per run, then test it on 24 tasks × ten new seeds.

### Proposed flagship ceilings, not measured runtime

| Item | Charged solution-proposal ceiling |
|---|---:|
| Training for one arm/domain/outer run | 786,432 |
| Training for one arm/domain, 30 outer runs | 23,592,960 |
| Training: five arms × two domains × 30 runs | 235,929,600 |
| Validation with eight finalists per run | 7,372,800 |
| Final test with ten inner seeds | 18,432,000 |
| **Subtotal: training + validation + test** | **261,734,400** |

These ceilings exclude direct-control runs, exact/reference construction, matched-time analyses, secondary ablations, preprocessing and diagnostics. Caching can reduce physical evaluations, but should not expand the assessment allowance. Arithmetic is saved in `docs/evidence/flagship-budget.json`.

**No runtime estimate is claimed yet.** The existing knapsack demo cannot predict the cost of the new covariance, archive and interpreter workload. First run a development smoke configuration: eight programs, generation zero plus three offspring batches, two tasks, one inner seed and budget 64. Its ceiling is 4,096 proposals. Then measure a realistic assessment before authorizing the flagship.

This PC has an observed Ryzen 7 9700X, eight physical cores, 16 logical processors and approximately 31.38 GiB physical RAM. Start with one worker. Consider four independent worker processes only after profiling memory and throughput; preserve deterministic seed allocation and result ordering. A GPU is not required for this initial design.

Report offline algorithm-design cost separately from deployment cost. For repeated-use scenarios, show total cost as `training cost + deployment count × per-instance cost`, with measured components. MIQP branch-and-bound work is not comparable to the heuristic proposal counter; use a separate time-based comparison.

## 11. Statistical analysis

The independent training replication is the **outer run**, not every market window or inner execution. Proposed starting count: **30 outer runs per arm/domain**, justified or revised by a development-only precision/power analysis. Thirty is not a magic guarantee of sensitivity.

For each outer run, compute paired arm differences after averaging the prespecified task/inner-seed outcomes. Share training tasks and evaluation seed schedules; the GP arms additionally share initial populations. Report domain-specific estimates, paired differences, confidence intervals and the full distribution, not just p-values.

**Primary estimand:** the expected difference between complete training-plus-selection procedures, over independent outer randomness, conditional on the registered benchmark suite. Give each registered market date equal weight; if universe variants are later added, average those within a date first. The primary analysis is not the performance of the best discovered outer seed. Use 10,000 bootstrap resamples of whole paired outer-run result vectors for the proposed conditional estimation interval, preserving all arm outcomes in each resampled vector. This is a proposed inferential procedure, not a finite-sample guarantee; validate its behavior and freeze the testing/interval implementation on development data before confirmation.

For the recommended two-domain flagship, preregister the four Full-GP contrasts in each domain as one family of eight confirmatory comparisons. Use Holm-adjusted tests for the corresponding null comparisons; report unadjusted estimation intervals as such and simultaneous uncertainty where claims require family-wise coverage. A positive zero-null test alone does not establish a practically meaningful effect above the chosen margin.

Declare practical superiority only when the relevant prespecified, multiplicity-aware lower bound exceeds the positive margin. Equivalence requires an explicitly registered equivalence procedure and uncertainty contained inside the negative/positive margin; a nonsignificant superiority test does not establish it. Claims about H5 additionally require unchanged-core evidence and domain-specific competitive quality, not a pooled cross-domain average.

Separate two uncertainty targets:

- **Conditional algorithmic uncertainty:** variability across independent training runs on the fixed benchmark suite.
- **Task/population uncertainty:** generalization beyond those task instances and periods. Use a prespecified crossed run/instance resampling design; market instances require consecutive temporal blocks rather than IID month sampling. Preserve pairing in every resample. Use development data to choose block length, with a conservative alternative-length sensitivity. Few effective temporal blocks imply wide uncertainty and restricted market claims.

Overlapping windows, reused assets and inner seeds do not multiply the number of independent markets. Do not call an analysis hierarchical if it merely resamples every row as IID.

Normal search-budget exhaustion returns the valid archive and is not a crash. Method-caused exceptions, invalid/nonfinite archives, or operational timeouts receive the preregistered worst score, normally zero normalized-HV, and count toward failure rates. Infrastructure failures are distinguished by an auditable reason and rerun with the identical seed. A partial archive may be shown diagnostically; it must not quietly replace the failure policy in the primary endpoint.

Before confirmation, freeze handling of missing outcomes, ties, empty fronts, numerical failures, degenerate tasks, multiple contrasts and stopping rules. No optional stopping at the first significant result. Report inconclusive and negative outcomes with the same visibility as improvements.

## 12. Behavioral evidence and secondary ablations

To distinguish program syntax from actual algorithm behavior, record branch execution, operator frequencies, restart timing, candidate/parent lineage and archive improvements. Measure semantic duplicates using fixed development probe tasks. Publish several frozen discovered programs with readable traces and counterexamples, not merely a pretty AST.

Stage these ablations after the core comparison, rather than running a huge factorial grid immediately:

1. Type-directed proposals vs untyped proposals with validation over the **same permitted language**; never execute invalid programs. Measure valid-child throughput, rejects, distinct children, CPU cost and end quality. Include an equal-CPU-time comparison with the time allowance frozen from development measurements. Identical accepted languages do not imply identical proposal distributions: report size/type/edit distributions and do not attribute every difference solely to typing.
2. Knowledge-rich vs minimal initializers/operators, with matching controls. Include body-deletion/replacement and branch ablations to determine whether a learned program actually adds search behavior beyond its initializer.
3. Fixed vs evolved scoring constants, charging all tuning evaluations.
4. Inherited crossover donor vs size-matched random donor.
5. Structural edit size vs behavioral change, motivated by the representation issue in McCarthy's introduction.[3]

Algebraic simplification must respect floating-point behavior and effects. Do not reorder random draws or state-changing instructions because their textual forms look interchangeable. No islands, learned subroutines, LLM-guided proposals or arbitrary-language synthesis in the first flagship.

## 13. Outcome scenarios and honest conclusions

| Observation | Supported conclusion | Not supported |
|---|---|---|
| Full GP beats all strong controls in both domains, with meaningful intervals and competitive time | Evidence for this executable-program methodology on these task classes | A universally superior optimizer |
| Full GP beats Random DSL but not formula-only | Search helps; evolvable control flow has not shown extra value | Discovery of useful algorithmic structure |
| Full GP and Random DSL are similar | Grammar/primitives may dominate; evolutionary selection has no demonstrated benefit here | GP is useless in every setting |
| Mutation-only equals or beats mixed GP | No support for this crossover policy at this budget | Every form of recombination is ineffective |
| Gains disappear against tuned Pareto local search or equal CPU time | No practical advantage over that stronger comparator | A deployment breakthrough based only on proposal counts |
| Portfolio works, scheduling does not | Domain-limited performance evidence; reuse may still work as software | Empirical cross-domain success |
| Historical frontier quality improves, future returns do not | Better optimization of the specified historical task | A profitable investment strategy |
| Intervals include meaningful gain and harm | Inconclusive; estimate uncertainty and consider a new preregistered study | Equivalence or a hidden positive result |
| Tiny exact cases fail or budgets can be bypassed | Stop; implementation invalidates comparison | Permission to compensate with more large runs |

## 14. Milestones with acceptance gates

| Gate | Deliverable | Acceptance criterion |
|---|---|---|
| G0 · Baseline | Frozen inputs, toolchain and upstream checkout | **Completed:** hashes, actual tests and legacy demo recorded |
| G1 · Numeric oracle | K-of-N evaluator and independent reference | All 3,003 tiny portfolios enumerable; direct/covariance agreement and adversarial fixtures pass |
| G2 · Pareto indicators | Archive, normalization and 2-D HV | Known-area fixtures, permutation/order invariance and archive monotonicity pass |
| G3 · Runtime contract | Domain interface, ledger, interpreter, trace | Handwritten programs terminate correctly; duplicates/hidden scans cannot evade budgets; replay deterministic |
| G4 · Typed variation | Mutation and actual crossover | Validity/effect/domain/size preservation; real distinct children; bounded rejection/fallback logs |
| G5 · Data and controls | Frozen pilot snapshot; five search arms | Point-in-time and footprint audits; independent checks of strong baseline behavior |
| G6 · Scheduling reuse | Second adapter and exact scheduling oracle | All 5,040 tiny permutations; unchanged core version; documented domain-only changes |
| G7 · Pilot and freeze | Measured costs, precision analysis, full preregistration | Holdout disclosure resolved, run manifest approved, no open primary-analysis choices |
| G8 · Confirmation | Locked runs and complete result table | All scheduled seeds accounted for, failures retained, artifacts replayable |
| G9 · Paper/artifact | Claim-to-evidence matrix and reproducibility package | Independent rerun of selected cases; code/data licensing checked; conclusions match results |

Follow test-driven vertical slices. G1 is the next implementation step, not a market-data dashboard. Do not begin a large GP search while the small oracle or budget invariants remain questionable.

**Headroom gate before confirmation:** use exact/development cases to establish that the tasks contain nontrivial trade-offs and that relevant strong baselines do not already saturate the frontier. Label degenerate or ceiling cases and retain them as sanity checks. Define any benchmark strata or exclusions independently of whether GP wins; do not remove losing tasks. Lack of headroom calls for a transparent development-stage redesign, not post-test replacement of the benchmark.

## 15. Publication and reproducibility package

The final artifact should contain the grammar and semantics, type/effect rules, proof obligations, immutable configuration, dependency and data hashes, per-run seeds, ASTs and lineage, failure records, raw metric tables, fixed analysis scripts, and instructions that reproduce a small case on a fresh machine. Include data redistribution terms and the original source licenses. An accessible subset should run without a proprietary optimizer license.

The pinned upstream checkout contains no tracked file matching LICENSE or COPYING. Resolve the actual license/author permission before public redistribution; no publication or licensing conclusion is implied by its public availability.

Planned figures: HV vs proposal/time budgets; paired differences by task/regime; quality vs program complexity; primitive/branch behavior; generalization across domains; and a compute-cost table. Generate them from actual run logs only. A secondary financial appendix can be added later under its own frozen portfolio-selection and cost protocol; it must not influence primary optimizer selection.

## 16. Decisions still required from Niki

These do **not** block G1–G4, but they block a confirmatory launch:

1. Which historical datasets, years and results have already influenced this research?
2. Is the first paper primarily a GP/automatic-algorithm-design methodology contribution, as recommended, or a financial application?
3. What maximum local CPU-hours and any external-compute spending are acceptable after the pilot?

No external compute, market account, paid solver, publication or scheduling is authorized by this document. **World-class here means precise claims, strong controls and reproducible evidence, not guaranteed positive results.**

---

## Appendix A · Environment and reproduction

Verified November 1, 2026 on this Windows PC.

### Do we need Leiningen?

**No.** The preserved upstream project declares its dependencies and entry points in `deps.edn`. This workspace continues that model. Clojure CLI-style execution supports a REPL, dependency resolution, aliases and program execution without Leiningen.[7][12]

Leiningen would become relevant if we deliberately adopted a `project.clj`-based project or a necessary Leiningen-only workflow. Installing both now would add a second configuration path without solving a current requirement. Do not confuse Leiningen with the Java runtime: **Java is required**.[5]

### Installed here

| Component | Pinned value | Scope |
|---|---|---|
| Java | Eclipse Temurin 25.0.5+8, Windows x64 | `.tools/jdk/jdk-25.0.5+8/` |
| CLI-compatible launcher | deps.clj 1.12.6.1673, native Windows x64 | `.tools/cli/deps.exe` |
| Clojure tools | 1.12.6.1673 | `.tools/clojure-tools/` |
| Clojure language | 1.12.6 | Root `deps.edn` |
| Upstream-language comparison | 1.12.5 | `:legacy-version` alias |
| Commons Math | 3.6.1 | `:legacy` alias |

The language and CLI have different version numbers and release cycles. Clojure's official installation guide supports Java LTS releases and currently recommends Temurin 25; the release page lists language 1.12.6 as stable. We deliberately pinned these rather than selecting a development alpha.[5][6]

The native launcher is **deps.clj**, a Clojure-CLI-compatible implementation used by the Windows `clj-msi` distribution. This is a portable installation of its released binary, not the Unix bash CLI and not a claim that an MSI was registered system-wide.[11][13][17]

No system PATH, registry Java registration, PowerShell security policy, WSL installation, trading credentials or global package manager configuration was changed. The wrapper sets Java and Clojure configuration locations for the child process only. Maven artifacts are cached under `.cache/m2`; project classpath cache is `.cpcache`. The launcher uses `-Srepro` to omit user-specific Clojure dependency configuration.[12]

### Commands that were exercised

From `C:\Users\meggy\Niki\typed-optimizer-lab` in PowerShell:

```powershell
.\clojure.cmd -Sdescribe
.\clojure.cmd -M:legacy:test
.\clojure.cmd -M:legacy:test:legacy-version
.\clojure.cmd -M:legacy:demo
```

In Git Bash:

```bash
bash ./clojure -Sdescribe
bash ./clojure -M:legacy:test
bash ./clojure -M:legacy:test:legacy-version
bash ./clojure -M:legacy:demo
```

The suite and demo were executed through the Git Bash wrapper; the Windows `.cmd` wrapper was separately smoke-tested with `(println (clojure-version))`, which returned `1.12.6`. Both wrappers dispatch the same native launcher. The launchers intentionally preserve the current working directory; use the project root for the supplied aliases.

Start an interactive REPL with `./clojure.cmd -M` in PowerShell (use `.\clojure.cmd -M` if needed) or `bash ./clojure -M` in Git Bash. Exit using the REPL's normal EOF mechanism. No persistent REPL was left running.

### Actual verification

| Check | Observed result |
|---|---|
| `java --version` | OpenJDK 25.0.5, Temurin build 25.0.5+8 |
| Native launcher description | deps.clj/tools 1.12.6.1673, reproducible mode true |
| Upstream tests, language 1.12.5 | 62 tests, 191 assertions, zero failures/errors |
| Upstream tests, language 1.12.6 | 62 tests, 191 assertions, zero failures/errors |
| `.cmd` language smoke check | 1.12.6 |
| Upstream DSL demo | Valid typed optimizer, feasible result, objective matched its tiny demonstration's DP optimum |

Latest verification logs: `docs/evidence/verified-upstream-tests-legacy.txt`, `verified-upstream-tests-current.txt`, `verified-upstream-demo.txt`, and `verified-protocol-data.txt`. The protocol-data check passed 4 tests and 23 assertions; it does not execute the proposed experiment.

The demo concerns synthetic, single-objective knapsack. It is **not evidence** about the proposed mean-volatility experiment, not a statistical performance result, and not a replication of all upstream reported numbers. The upstream source requests Java 21; these tests used Java 25. If historical environment replication becomes an acceptance requirement, separately pin Java 21 and rerun rather than silently treating the versions as identical.[8]

### Provenance and restoration

`environment.lock.json` records the upstream commit, exact artifact coordinates, publisher SHA-256 values for the JDK/native launcher downloads, local Maven-jar SHA-256 values and observed hardware. `docs/evidence/toolchain-downloads.json` preserves the download provenance. Publisher-checksum equality was checked before extracting the JDK and launcher archives; this is not a claim of independent cryptographic signature verification.[16][17]

For a fresh machine:

1. Restore the project text files, with their license terms.
2. Fetch the two exact ZIPs identified in `environment.lock.json`; verify their SHA-256 before extracting into the recorded `.tools/jdk` and `.tools/cli` locations.
3. Clone the upstream repository into `vendor/evolving-portfolio-dsl` and detach at the exact recorded commit. Do not use an unpinned moving branch as a replacement.
4. Run the root wrapper's `-Sdescribe`; the launcher retrieves its matching Clojure tools if absent.
5. Run the two language-version test commands. Root Maven repositories require checksum validation for release artifacts.
6. Compare the resolved dependency hashes with the lock record and investigate any difference.

This restoration checklist is documentation, not an already tested clean-machine rebuild. The current local installation and both launchers have been exercised.

### Editor and optional tools

An editor is useful but not needed to reproduce the command-line baseline. No editor installation or extension settings were changed. Select editor integration separately; do not make it a scientific dependency. Likewise, Babashka, Docker, WSL, GPU libraries, plotting packages and commercial solvers are not installed or required by this baseline.

The workspace does not depend on an `AGENTS.md` file.

---

## Appendix B · Review decisions

Two independent methodological reviews were considered. Their alternative numerical proposals were not silently merged. The main plan v1.1 and `configs/flagship-proposal.edn` are the canonical proposal, pending pilot and preregistration.

### Accepted improvements

- Add direct prior art on typed GP, executable optimizer evolution, GP scheduling and automatic multiobjective component configuration.
- Treat configured conventional algorithms as a full outer-search arm, not merely an untuned deployed baseline.
- Define the primary estimand over the complete training-plus-selection procedure and retain every outer seed.
- Separate algorithmic randomness on a fixed benchmark from inference about independent market periods.
- Reserve selection-validation for a frozen automated choice and document any exposure-driven redesign.
- Distinguish normal budget exhaustion from crashes and operational timeouts.
- Add a development-stage headroom gate, body/branch attribution checks, and equal-time evaluation of typing efficiency.
- Keep framework reuse, within-domain generalization and unchanged-program cross-domain transfer as different claims.

### Alternatives resolved explicitly

| Design choice | Canonical v1.1 proposal | Reason / limitation |
|---|---|---|
| Portfolio size | N=50, K=5, conditional on the point-in-time audit | No data availability is assumed; revise before confirmation if the audit fails |
| Scheduling size | 30 jobs | Exact correctness uses 7 jobs; size generalization is secondary |
| Training tasks | 6 per domain | Bounded first study; broader task coverage can be preregistered after profiling |
| Outer allowance | 256 assessment slots, at most 10 construction attempts per slot | Duplicates and failed slots consume allowance; not 256 guaranteed distinct programs |
| Validation | At most 8 training-selected finalists, 6 tasks, 2 seeds | Choose maximum mean score; size/canonical ID are tie-breakers, not a post-hoc tolerance |
| Test | 24 tasks, 10 fresh inner seeds, no cross-seed archive pooling | These are execution replicates, not independent markets |
| Independent training | Proposed 30 paired outer runs | Requires a development-only precision analysis |
| Main arms | Mixed GP, mutation-only, Random DSL, formula-only, configured conventional | Other mechanism ablations are staged and budgeted separately |
| Quality metric | HV / 1.1025 with reference (1.05, 1.05) | Instance-derived analytical normalization, not competitor-derived bounds |
| Practical threshold | Proposed 0.01 absolute score units | Validate relevance in development and freeze before test access |

The proposal's training/validation/test ceiling is 261,734,400 solution proposals across five arms, two domains and 30 outer runs. This is arithmetic, not measured runtime or authorization. Direct controls, exact references, secondary ablations, matched-time comparisons and preprocessing are additional costs.

### Unresolved before confirmation

Owner-attested historical exposure; point-in-time asset eligibility; core/grammar and numerical limits; baseline configuration space; measured compute and precision; exact inferential implementation and multiplicity policy; licensing for redistribution. No pristine temporal holdout, scientific superiority or financial return is claimed.

## Sources

[1] https://www-formal.stanford.edu/jmc/lisp/lisp.html?utm_source=chatgpt.com — McCarthy (1979), History of Lisp
[2] https://www-formal.stanford.edu/jmc/recursive.pdf?utm_source=chatgpt.com — McCarthy (1960), Recursive Functions of Symbolic Expressions and Their Computation by Machine, Part I
[3] https://www-formal.stanford.edu/jmc/mcc59/node1.html?utm_source=chatgpt.com — McCarthy, Programs with Common Sense: Introduction
[4] https://www-formal.stanford.edu/jmc/lisp/node3.html — McCarthy, History of Lisp: The implementation of LISP
[5] https://clojure.org/guides/install_clojure — Clojure: Install Clojure
[6] https://clojure.org/releases/downloads — Clojure: Downloads and Compatibility
[7] https://clojure.org/reference/deps_edn — Clojure: deps.edn Reference
[8] https://github.com/npenchev88/evolving-portfolio-dsl — npenchev88/evolving-portfolio-dsl, pinned commit 29252c5b86d9f8a15099d85893e5e97861cb705d
[9] https://people.brunel.ac.uk/~mastjjb/jeb/orlib/portinfo.html — OR-Library: Portfolio optimisation
[10] https://github.com/binance/binance-public-data — Binance Public Data: official data schema and checksums
[11] https://github.com/casselc/clj-msi — clj-msi: Clojure Windows Installer
[12] https://clojure.org/reference/clojure_cli — Clojure CLI Reference
[13] https://github.com/borkdude/deps.clj — deps.clj: Clojure CLI-compatible launcher
[14] https://research.manchester.ac.uk/en/publications/automatically-designing-state-of-the-art-multi-and-many-objective — Bezerra, Lopez-Ibanez and Stutzle (2020), Automatically Designing State-of-the-Art Multi- and Many-Objective Evolutionary Algorithms
[15] https://davidmontana.net/papers/stgp.pdf — Montana (1995), Strongly Typed Genetic Programming
[16] https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse — Eclipse Adoptium: Temurin 25 Windows x64 JDK metadata
[17] https://github.com/borkdude/deps.clj/releases/tag/v1.12.6.1673 — deps.clj release v1.12.6.1673
[18] https://www-formal.stanford.edu/jmc/recursive/recursive.html — McCarthy (1960), Recursive Functions: HTML edition
[19] https://www-formal.stanford.edu/jmc/recursive/node1.html — McCarthy (1960), Recursive Functions: Introduction
[20] https://www-formal.stanford.edu/jmc/recursive/node3.html — McCarthy (1960), Recursive Functions of Symbolic Expressions
[21] file:///C:/Users/meggy/Niki/typed-optimizer-lab/docs/input/typed_clojure_portfolio_plan_bg.pdf — Niki supplied Clojure portfolio plan, 2026-10-31
[22] file:///C:/Users/meggy/Niki/typed-optimizer-lab/docs/input/optimizer_methodology_extension_bg.pdf — Niki supplied methodology extension, 2026-10-31
[23] https://arxiv.org/abs/2103.11746 — Lones (2021), Evolving continuous optimisers from scratch
[24] https://doi.org/10.1007/978-3-319-10762-2_50 — Bezerra et al. (2014), Automatic Design of Evolutionary Algorithms for Multi-Objective Combinatorial Optimization
[25] https://doi.org/10.1016/j.procir.2019.02.118 — Zhou and Yang (2019), Automatic design of scheduling policies for dynamic flexible job shop scheduling by multi-objective genetic programming based hyper-heuristic
[26] https://www.jmlr.org/papers/v11/cawley10a.html — Cawley and Talbot (2010), On Over-fitting in Model Selection and Subsequent Selection Bias in Performance Evaluation
