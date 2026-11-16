# G2 — Pareto archive, normalization and 2-D hypervolume

**Scope:** correctness infrastructure only; no optimizer search, market data or confirmatory run.  
**Status:** G2 completed and independently reviewed on November 7, 2026. Combined regression checks pass; no blocking review findings.

## Implemented

One new production namespace, `src/niki/optimizer/pareto.clj`:

| Function | Contract |
|---|---|
| `dominates?` | Two minimized objectives, weak improvement in both and strict improvement in at least one |
| `front` | Sorted, unique, uncapped nondominated objective vectors |
| `insert` | Incremental insertion, removing only dominated/duplicate vectors |
| `prepare-normalization` | Fixed two-coordinate bounds, with explicit zero-width-coordinate indices |
| `normalize-point` | Unit-box mapping; material out-of-bounds values rejected |
| `hypervolume-2d` | Dominated union area against a fixed weakly-worse reference point |
| `normalized-hypervolume` | Fixed reference `[1.05 1.05]`, divisor `1.1025` |
| `portfolio-normalization` | Instance-only exact-K return extrema and conservative risk bound |

The archive contains **objective vectors**, not a new candidate/payload object system. The future runtime will retain solution identities; the G2 verification CSV already links each front point to a representative feasible subset.

Input coordinates are finite immutable numbers, canonicalized to binary64. Comparisons and dominance are exact on that domain, with no epsilon-dominance. Negative zero is canonicalized. The normalization box is prepared once per task and is a trusted internal value, not an untrusted serialization boundary.

Normalization uses exact decimal differences of binary64 coordinates before division, preventing finite-width overflow/cancellation in wide or narrow boxes. Hypervolume uses a sorted-front strip sweep and exact decimal rectangle accumulation before conversion. This is a small standard-library implementation, not a new numerical framework or evaluator-performance rewrite.

## Fixed numerical behavior

- Both objectives minimized; no raw aggregation across domains.
- Archive duplicates are objective duplicates; no archive-size cap or truncation.
- Constant-bound coordinates map to zero **only** if the point equals the bound; constant-coordinate indices are exposed for reporting. This flags zero-width analytical bounds, not every possible lack-of-headroom case.
- Boundary overshoot is accepted only within **1e-12 normalized-coordinate units**, then rounded to the relevant endpoint. Larger excursions fail; they are not silently clipped.
- Reference must be weakly worse than every input point, including dominated points; malformed reference inputs fail.
- Empty archive HV/score is zero; unrepresentable infinite HV fails rather than returning infinity.
- The proposed practical effect margin remains **0.01**; no sensitivity or compute-budget claim is inferred from these checks.

Portfolio return bounds use the best/worst **feasible K-subset means**, selected by exact per-asset binary64 totals, not the looser single-asset extrema. The bound endpoints are evaluated through the current portfolio evaluator. Risk bounds remain `[0, max(single-asset sample SD)]`, a conservative bound for nonnegative equal weights. This task preparation must be shared and separately timed later; no cost-free optimizer calls are implied.

Scheduling adapter/bound construction remains deferred to G6. Generic fixed-box normalization is available; no scheduling runtime was added here.

## Checks already executed

`bash ./clojure -M:g2-test`:

**9 tests, 6,466 assertions; zero failures/errors** on each of Clojure 1.12.6 and 1.12.5, both using Java 25.0.5. The native Windows launcher also passed the G2 suite.

The parent combined rerun also passed G1 (19 tests / 12,227 assertions), upstream (62 / 191), each on both language versions, and proposal validation (4 / 23). See `docs/evidence/g2-regression.txt`.

Coverage includes:

- Strict dominance, equality, trade-offs, duplicate coordinates, signed zero and invalid/nonfinite inputs.
- Known rectangle-union areas and reference-boundary behavior.
- All **512 subsets** of a 3-by-3 grid compared with independent quadratic dominance and rational cell-grid union oracles.
- Input-order and duplicate invariance, incremental-versus-batch archive equality, and HV nondecrease over insertion traces.
- **100 seeded cases** for independent normalization/area comparisons.
- Constant, very wide, narrow-offset and tiny-width normalization boxes.
- Portfolio exact-K return bounds and the full **3,003-portfolio** tiny-case pipeline.

The test-only `test/niki/optimizer/pareto_reference.clj` imports **no production normalization, dominance or area helpers**. Its union algorithm partitions the plane into rational grid cells; production uses a sorted strip sweep.

The executable `scripts/verify-g2.clj` evaluated the synthetic 15-asset/K=5 fixture and recorded:

- Portfolios checked: **3,003**.
- Nondominated archive points: **13**.
- Normalized HV: **0.7786491221991364**.
- Fixed bounds: `[[-0.007966666666666667, 0.00605], [0, 0.03312362061164574]]`.
- Batch/incremental agreement, input-order invariance and HV monotonicity: **true**.

This is a synthetic numerical artifact, not measured optimizer efficacy or a newly discovered investment strategy.

## Reproduce

From the project root, PowerShell:

```powershell
.\clojure.cmd -M:g2-test
.\clojure.cmd -M:g2-test:legacy-version
.\clojure.cmd -M scripts/verify-g2.clj
```

Git Bash:

```bash
bash ./clojure -M:g2-test
bash ./clojure -M:g2-test:legacy-version
bash ./clojure -M scripts/verify-g2.clj
```

## Evidence and deliberately excluded work

- `docs/evidence/g2-red-*.txt` / `g2-green-*.txt`: recorded feature-first RED/GREEN cycles.
- `docs/evidence/g2-front.csv`: front coordinates, fixed-box normalized values and representative subsets.
- `docs/evidence/g2-verification.edn`: timestamp, fixture hash, numerical settings and checks.
- `docs/evidence/g2-python-crosscheck.json`: independent Python front/grid area plus representative portfolio replay.
- `docs/evidence/g1-contract-fix.json`: bounded G1 corrections and RED/GREEN evidence.
- `docs/evidence/g2-code-review.json`: independent approval of the current source snapshot; review executed no source code.
- `docs/evidence/g2-static-scan.json`: exact reviewed file hashes and local scan.
- `docs/evidence/g2-deliverables.json`: current G1/G2 delivery manifest, superseding the historical G1-only snapshot for current-state verification.
- Proposal v1.2 clarifies portfolio-return bounds only; no population, proposal budget, dataset, margin or launch authorization was changed.

Confirmed G1 contract corrections are integrated: map checks precede destructuring, unsupported/mutable numbers are rejected, and the reference defaults to explicit normalized-binary64 semantics with a separate exact-input diagnostic mode. The G1 suite now passes 19 tests / 12,227 assertions on both language versions; production objective arithmetic was not rewritten. Earlier review bundles/manifests are historical snapshots, not claims that authorized source changes leave old hashes unchanged.

Not included: precomputed covariance performance optimization, new budgets/population schedules, GP/crossover, a market-data snapshot, scheduling implementation, an experiment runner, paid solvers or a confirmatory launch. The next implementation gate after accepted G2 is G3's bounded runtime and resource ledger.
