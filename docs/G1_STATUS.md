# G1 — Portfolio evaluator correctness

**Implementation date:** November 7, 2026.  
**Status:** G1 implemented; confirmed external-review contract gaps corrected on November 7, 2026. Current suite is 19 tests / 12,227 assertions on each tested language version. Combined final review is tracked in `G2_STATUS.md`.  
**Scope:** synthetic development fixtures only. No optimizer evolution, market data, trading, holdout access or confirmatory run.

## Delivered

- `src/niki/optimizer/portfolio.clj`: asset/date/return validation; exact-cardinality selection validation; equal-weight portfolio return series; mean and sample standard deviation; minimization objectives `[-mean, standard-deviation]`.
- `src/niki/optimizer/portfolio_reference.clj`: exhaustive subset enumeration and an independent rational sample-covariance reference. Means use `mu^T w`; variance uses `w^T Sigma w`. Square roots use DECIMAL128 before conversion to binary64.
- `resources/fixtures/g1-portfolio.edn`: deterministic synthetic panel, 15 assets, 12 observations, K=5. Fixture generator is independently checked in the tests. This is not a market sample.
- `test/niki/optimizer/`: tests, including exhaustive comparison of all 3,003 subsets and independent bitmask verification of enumeration completeness.
- `scripts/verify-g1.clj`: repeatable exhaustive numerical check, CSV output and EDN evidence report; never launches an optimizer experiment.

Production and reference share input/selection validation, but not their numerical implementations. The default reference now uses **`:normalized-binary64`**, the exact same normalized input values as production. Use `(prepare-reference panel :exact-input)` only for original-input rational/decimal quantization diagnostics; the returned `:input-mode` records the choice. Unknown modes fail. The reference does not establish that shared validation is independently correct; explicit malformed-input tests cover that contract separately. “Exact” refers to complete enumeration and rational reference moments, not exact real-valued floating-point square roots.

## Review findings and fixes

The first review found rounded-mean risk errors and Long overflow in the exact reference. The second found cancellation during first-observation shifts. Both reports and all RED/GREEN logs are retained. The final arithmetic replacement passed parent-run regressions and independent review with no blocking findings (`docs/evidence/g1-code-review-03.json`). Reviewed source hashes were checked against the current files before acceptance.

The direct path now constructs exact `BigDecimal(double)` values from normalized binary64 inputs, with no oracle numerical helper reused:

```text
S[t] = exact sum of selected returns at observation t
Q    = exact sum_t S[t]
d[t] = T*S[t] - Q
mean = Q / (K*T)
var  = sum_t d[t]^2 / (K^2*T^2*(T-1))
risk = sqrt(var)
```

Only final divisions/root round to DECIMAL128 and then binary64. The covariance reference remains a separate rational calculation with promoting sums, differences and products. The three cancellation regressions now return `sqrt(2)`, `sqrt(1/2)` and `sqrt(1/8)`; four integral-overflow regressions pass.

Displayed binary64 period values can round flat while correctly accumulated risk remains nonzero; consumers must not recompute objectives from that rounded display series. Initial binary64 input coercion can itself lose information. The default reference follows that same domain; the explicit `:exact-input` diagnostic retains original rational/decimal inputs. Final outputs are rounded, not universally guaranteed correctly rounded. Exact accumulation adds allocation cost that must be measured during the runtime pilot, not inferred from local correctness-check timings.

Fix evidence: `docs/evidence/g1-fix-report.json`, `g1-fix2-report.json` and their linked logs.

## Measured checks

On the installed Java 25.0.5:

| Check | Clojure 1.12.6 | Clojure 1.12.5 |
|---|---|---|
| New G1 tests | 19 tests, 12,227 assertions; zero failures/errors | 19 tests, 12,227 assertions; zero failures/errors |
| Preserved upstream tests | 62 tests, 191 assertions; zero failures/errors | 62 tests, 191 assertions; zero failures/errors |

Proposal-data validator: 4 tests, 23 assertions; zero failures/errors.

Exhaustive verification using Clojure 1.12.6:

- Expected / checked / unique selections: **3,003 / 3,003 / 3,003**.
- Failed numerical crosschecks: **0**.
- Maximum absolute mean error against rational reference: **8.673617379884035e-19**.
- Maximum absolute standard-deviation error against the default same-domain reference: **0.0**.
- Acceptance tolerance: `absolute-error <= 1e-12 + 1e-10 * abs(reference)`.
- Extreme-magnitude tests use relative-error checks so a zero answer cannot hide behind the absolute tolerance.
- Fixture SHA-256: `fb6c44113ce3d0a29a77b8998b120448710b16512455639b6e4279faa6fef758`.

Tests cover the sample denominator, K=1 and K=N, canonical order/replay, duplicate/invalid selections, zero volatility, identical and anticorrelated assets, singular covariance, ties, missing/unaligned/duplicate/unsorted dates, malformed return panels, NaN/infinity, impossible simple returns below -1, and large/tiny finite magnitudes.

Supplemental bounded checks (not included in the formal suite count): **200 panels / 400 objective comparisons** passed against covariance of exact normalized binary64 inputs, using a fixed seed and a palette spanning -1, subnormals, large offsets and Double/MAX_VALUE. Zero/positive/finite and relative/ULP-sensitive checks are recorded in `docs/evidence/g1-adversarial-probe.edn`; the repeatable probe is `docs/evidence/g1-adversarial-probe.clj`. A separate 200-panel rational algebra check of the centered-numerator identity passed; that identity check alone is not a source-code test.

## Input contract

`prepare-instance` accepts a map with `:k` and a nonempty vector of assets. Each asset contains:

```clojure
{:id :asset-a
 :dates ["2000-01-01" "2000-01-02" "2000-01-03"]
 :returns [0.01 -0.02 0.03]}
```

Root input and every asset must be maps, checked before root destructuring or field access. Return values must be standard immutable numeric classes; mutable/custom numbers, including both signs of DoubleAdder, fail. Both exact lower-bound and canonical finite-double checks apply.

Asset IDs are unique keywords or nonblank strings. All date vectors must be identical, strictly increasing ISO dates, with at least two observations. Return vectors must match the dates and contain finite simple returns >= -1. Selections are vectors of exactly K distinct, zero-based asset indices. Malformed inputs produce `ExceptionInfo` with an `:error` code. Prepared records are trusted internal values; do not deserialize or alter their fields as a validation bypass.

The evaluator consumes **returns, not prices**. Price denominators, corporate actions, asset eligibility, delistings and historical exposure require a later audited data adapter. It does not fill missing data, annualize values or model transaction costs. Equal weights are fixed analytically at each observation, not buy-and-hold quantities.

## Reproduce

Run from `C:\Users\meggy\Niki\typed-optimizer-lab`.

PowerShell:

```powershell
.\clojure.cmd -M:g1-test
.\clojure.cmd -M:g1-test:legacy-version
.\clojure.cmd -M scripts/verify-g1.clj
```

Git Bash:

```bash
bash ./clojure -M:g1-test
bash ./clojure -M:g1-test:legacy-version
bash ./clojure -M scripts/verify-g1.clj
```

Evidence:

- `docs/evidence/g1-objectives.csv`: one row per feasible portfolio, with direct/reference values, exact reference variance and errors.
- `docs/evidence/g1-verification.edn`: timestamp, fixture hash, versions, counts, tolerances and error maxima.
- `docs/evidence/g1-regression.txt`: new/upstream suites on both language versions and proposal validator.
- `docs/evidence/g1-red-*.txt`, `g1-green-*.txt`: recorded test-first implementation cycles.
- `docs/evidence/g1-static-scan-03.json`: final source/test/configuration/fixture hashes and local added-source scan.
- `docs/evidence/g1-code-review-03.json`: independent approval of that exact snapshot.
- `docs/evidence/g1-deliverables.json`: historical pre-contract-correction delivery manifest; its old source hashes are not current after the authorized fixes.
- `docs/evidence/g1-contract-fix.json`: corrective scope and individual RED/GREEN logs.
- `docs/evidence/g2-regression.txt`: current combined suites and verification. The new G2 delivery manifest supersedes the old snapshot for current-state verification.
- `docs/evidence/g1-python-crosscheck.json`: an additional independent Python Fraction/80-digit Decimal series calculation checked every exported row, exact variance and subset completeness; 3,003 passed.

## Next gate

G2 Pareto/normalization/hypervolume is now implemented; see `G2_STATUS.md`. G3 (bounded runtime/resource ledger) is next only after G2 acceptance. No confirmation is authorized; the flagship remains a proposal with launch disabled and holdout not locked.
