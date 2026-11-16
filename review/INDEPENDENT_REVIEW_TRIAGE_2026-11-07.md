# Triage of the independent review — November 7, 2026

## Scope and stop boundary

This is an assessment of `docs/INDEPENDENT_REVIEW_2026-11-07.md` and Niki's pasted conversation log. **No G1 implementation, configuration, accepted evidence file, or G2 code was changed. G2 remains paused.**

Both saved copies of the independent report exist and are byte-identical:

- `docs/INDEPENDENT_REVIEW_2026-11-07.md`
- `review/INDEPENDENT_REVIEW_2026-11-07.md`

All files in the accepted `docs/evidence/g1-deliverables.json` snapshot still match their hashes. The existing review ZIP was not rebuilt; these new triage/probe files are separate from that earlier snapshot.

## Bottom line

The external review identifies real contract gaps and useful experimental-design risks. Its concrete examples deserve action; its speedup, full-front runtime and publication predictions should not be accepted as established results.

**Recommended disposition:** retain the earlier G1 completion as a historical acceptance result, but conduct a targeted G1 contract-correction pass before treating the evaluator/reference interface as settled. Do not proceed to G2 without Niki's approval. Separate correctness changes from performance engineering and from revisions to the proposed experiment.

## 1. Findings independently reproduced in this pass

I ran bounded probes against the current unchanged implementation. These were not optimizer experiments. Outputs are in `review/EXTERNAL_REVIEW_PROBES_2026-11-07.edn`; the matching probe source is `review/EXTERNAL_REVIEW_PROBES_2026-11-07.clj`.

### 1.1 Direct/reference numeric-domain mismatch — confirmed

| Input case | Direct risk | Current original-input reference risk | Reference after exact normalized-binary64 conversion |
|---|---:|---:|---:|
| Four-asset `1e23` example | 1482910.4003789306 | 0.0 | 1482910.4003789306 |
| Ordinary `0.3`, `0.1`, `0.2` example | 9.813077866773595e-18 | 0.0 | 9.813077866773595e-18 |
| Large Long example | 724.0773439350247 | 570.6351724175438 | 724.0773439350247 |
| DoubleAdder containing 0.5 | 0.3535533905932738 | 0.0 | 0.3535533905932738 |

The same-domain comparison matched production in every reproduced case. This does **not** demonstrate that production's centered-numerator formula is wrong. It demonstrates that the current reference and production can calculate different mathematical inputs under a superficially identical panel API.

The distinction was disclosed in `G1_STATUS.md` and the handoff, but disclosure alone is not an adequate default oracle interface. A generic crosscheck needs an explicit common domain.

Recommended correction, subject to approval:

- Make a clearly named normalized-binary64 reference the primary production comparator.
- Retain an explicitly named exact-original-input mode for separate input-quantization diagnostics.
- Convert normalized doubles through `BigDecimal(double)` before rationalization when the goal is their actual binary values, not their shortest decimal spellings.
- Do not call input-conversion differences production numerical error.
- Reject unsupported/mutable numeric subclasses or specify a safe snapshot and comparison policy.

### 1.2 A further input-validation failure — confirmed

A `DoubleAdder` containing **-1.5** passes `prepare-instance`, and the stored return is `-1.5`. This violates the advertised simple-return lower bound of -1.

This is more than an oracle disagreement. Accepting broad `Number` subclasses while relying on generic numeric comparisons allows the raw comparison and later double conversion to disagree. Restricting the input contract to supported numeric types, with tests on the canonicalized domain, is a correctness change worth making before resuming.

### 1.3 Non-map root input and lazy realization — confirmed

The sequential key-value input `(:k 1 :assets [...])` is accepted as a panel, although the contract says the root must be a map.

A bounded lazy-sequence probe also proved that the input is realized during parameter destructuring before a map check can reject it. I did **not** run an infinite input or deliberately hang the process.

Recommended correction: accept a plain parameter, check `map?` before destructuring, and check asset maps before field access. Keep malformed/lazy input regressions separate from numeric correctness tests.

### 1.4 Shared validation is a limitation, not a newly discovered numerical defect

The production/reference validation sharing was already disclosed and explicitly tested for the cases present at G1 acceptance. The external report's warning is sound, but its third item should not be counted as an additional newly reproduced arithmetic bug.

For later archive/normalization/HV references, avoid sharing the algorithm under test with its supposed oracle. This is a design requirement, not permission to start G2 now.

## 2. Design concerns I would keep

### Budget and behavioral headroom

The arithmetic is correct: N=50, K=5 gives **2,118,760** subsets and **225** one-swap neighbors. A complete neighborhood scan consumes **87.890625%** of 256 charged proposals.

However, it does not follow that every future local-search instruction must scan the whole neighborhood or use the old runtime's population-wide semantics. G3 is not implemented. The stronger claim that H2 is necessarily biased toward the null remains a hypothesis, not an observed result.

Before freezing the protocol, define sampled/full neighborhoods, candidate scoring, acceptance in two-objective space, and the charged cost of objective-dependent features. On development tasks measure branch activation, effective search steps, stagnation/restart behavior, duplicate AST yield and quality-versus-budget curves. Do not choose tasks or budgets solely because Full GP wins.

### Formula-only scaffold

The fixed scaffold must be competent and give access to relevant diversification mechanisms. Otherwise a Full-GP win could reflect a deliberately weak comparator. Predefine the scaffold or a properly budgeted template-selection procedure; do not choose it after test exposure.

### Tighter analytical normalization bounds

Top-K/bottom-K asset means and scheduling SPT/LPT mean-completion bounds are sensible refinements to examine before freezing normalization. Existing bounds are conservative, not automatically mathematically invalid. Changing the bounds also changes the score geometry and potentially the practical meaning of the proposed 0.01 margin.

Some illustrations in the review are overstated. For example, a 30-job instance with one duration 100 and 29 durations of 1 (within the proposed duration range) has loose `[0,P]` interval width only about **1.348 times** the exact SPT–LPT interval width, not universally more than twice. Its total duration is 129, SPT mean is 94/5, and LPT mean is 229/2. The pasted outlier-asset example likewise does not imply a universal normalized band `[0.45,0.55]`. These illustrative errors do not invalidate the tighter-bound suggestion. Arithmetic checks are saved in `review/EXTERNAL_REVIEW_ARITHMETIC_2026-11-07.json`.

Claims that static scheduling rules already cover most of this particular generator's frontier have not been established by the review's reported experiments. Treat them as headroom risks to test, not measured facts about our unimplemented scheduling domain.

## 3. Performance proposal: promising identity, unmeasured speed claims

Precomputing exact asset totals and centered cross-products is algebraically compatible with the current exact variance numerator. It is a reasonable performance candidate, not yet an implemented or benchmarked speedup in this project.

Important conditions:

- Keep an independent direct time-series calculation as a crosscheck if the hot path becomes a covariance/cross-product lookup. Otherwise production and its reference become much more similar numerically.
- Time and report task preparation separately, including matrix construction and memory. All arms must receive the same evaluated information and evaluator implementation.
- An objectives-only path can use O(K^2) lookup work after preprocessing, but returning a complete T-element portfolio series still requires output work dependent on T. Preserve or explicitly revise API semantics rather than silently advertising the whole existing API as O(K^2).
- Test numerical output equivalence and failure behavior before performance claims.

I did **not** repeat the reviewer-specific microbenchmark. Its approximately 0.723 ms rate is reported benchmark evidence, not a performance measurement from this triage.

Using the review's own more precise reported rate, the portfolio proposal ceiling projects to **26.2729772288 hours**, but only under the assumptions that proposals are actual objective computations, throughput is representative and stable, and single-worker work is not reduced by caching. Interpreter, archive and analysis costs are extra. This projection is not a measured complete-run cost.

The **50–100x** claim and **approximately two-second exact front** claim remain unmeasured projections. At 10–15 microseconds per objective calculation, evaluating 2,118,760 portfolios alone takes approximately **21.19–31.78 seconds**, before exact front extraction, storage and other overhead. Two seconds would require under one microsecond per candidate including whatever work is claimed. A primitive/parallel alternative also needs its own correctness, exactness and resource-accounting evaluation.

Enumeration may become a useful reference option, but neither its full cost nor MIQP's removal from the plan is settled by these projections.

## 4. Recommended order, not authorization to implement

1. **G1 contract corrections:** root/asset shape checks, supported numeric types, explicit oracle input mode, and concrete failing regressions for the reproduced cases.
2. **Separate evaluator performance pilot:** cache/precompute experiments, objectives-only API if warranted, preparation cost, allocation and warm-up-aware timings, identical evaluator access across arms, independent direct-path crosscheck.
3. **Design amendments before freezing:** normalization, practical margin, formula-only scaffold, scorer/acceptance rules, inner-budget diagnostic sweep and outer diversity/convergence pilot.
4. Resume G2 only when Niki authorizes it. Keep market/holdout access and confirmation disabled.

The scientific framing remains plausible, but no model review can guarantee venue acceptance, novelty clearance, a GP victory, or a highly publishable negative result.

## 5. What was not changed or claimed

- Accepted G1 files and their hashes remain unchanged.
- Existing passed tests are historical execution evidence, not a proof over all accepted input objects.
- No new full suite rerun, performance benchmark, GP search, exhaustive flagship front, market-data access or G2 implementation was performed in this triage.
- No automatic adoption of the external model's suggested parameters or protocol changes.
