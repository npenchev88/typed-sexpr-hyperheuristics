# Independent Review Report — Niki · Typed Optimizer Lab (G1 & Experimental Design)

- **Date:** November 7, 2026
- **Project Root:** `C:\Users\meggy\Niki\typed-optimizer-lab`
- **Reviewed Scope:** `docs/EXTERNAL_REVIEW_BRIEF.md`, `docs/G1_STATUS.md`, `docs/FINAL_EXPERIMENT_PLAN.md` (v1.1), `docs/DECISIONS.md`, `docs/REVIEW_RESOLUTION.md`, `configs/flagship-proposal.edn`, `configs/smoke-proposal.edn`, `src/niki/optimizer/`, `test/niki/optimizer/`, `scripts/verify-g1.clj`, `scripts/validate-protocol.clj`, `docs/evidence/*`, `docs/input/*.pdf`, and `vendor/evolving-portfolio-dsl/` (pinned at `29252c5b86d9f8a15099d85893e5e97861cb705d`).
- **Stop-Boundary Compliance:** No existing files were modified (`0` SHA-256 mismatches against `docs/evidence/g1-deliverables.json`), G2 was not started, no parameters were tuned, no holdouts were accessed, and no optimization runs were launched.

---

## 1. Executed vs. Statically Inferred

### 1.1 Actually Executed on This Machine (Temurin 25.0.5+8, Clojure 1.12.6 / 1.12.5)
1. `.\clojure.cmd -M:g1-test` → **13 tests, 12,156 assertions, 0 failures, 0 errors**.
2. `.\clojure.cmd -M:g1-test:legacy-version` → **13 tests, 12,156 assertions, 0 failures, 0 errors**.
3. `.\clojure.cmd -M:legacy:test` → **62 tests, 191 assertions, 0 failures, 0 errors**.
4. `.\clojure.cmd -M scripts/validate-protocol.clj` → **4 tests, 23 assertions, 0 failures, 0 errors**.
5. **In-memory stdin differential probes (`.\clojure.cmd -M -`)** testing counterexamples for `niki.optimizer.portfolio/prepare-instance`, `niki.optimizer.portfolio/evaluate`, `niki.optimizer.portfolio-reference/prepare-reference`, and `niki.optimizer.portfolio-reference/evaluate`.
6. **In-memory stdin micro-benchmark (`.\clojure.cmd -M -`)** measuring `niki.optimizer.portfolio/evaluate` runtime on a synthetic panel at the proposed flagship dimensions ($N=50, K=5, T=365$) on the local Ryzen 7 9700X.
7. **SHA-256 verification** of all 29 tracked files listed in `docs/evidence/g1-deliverables.json` (`0` mismatches).

### 1.2 Statically Inspected / Not Executed
- Did not re-run the historical 30-seed experiments in `vendor/evolving-portfolio-dsl`.
- Did not inspect the two earlier Penchev & Marchev research-article PDFs directly because those original PDFs are absent from `docs/input/` (their summaries in `docs/input/typed_clojure_portfolio_plan_bg.pdf` and `docs/input/optimizer_methodology_extension_bg.pdf` were treated as second-hand).
- Unimplemented gates (G2–G9) were evaluated from the canonical design documents (`docs/FINAL_EXPERIMENT_PLAN.md` and `configs/flagship-proposal.edn`).

---

## 2. Overall Verdict

**Verdict: Conditional Pass for G1; Pause Before G2/G3 to Resolve 2 Experimental/Runtime Blockers and 3 G1 Edge-Case/Oracle Findings.**

The scientific framing and epistemic hygiene of this project are unusually strong. In the preserved prototype (`vendor/evolving-portfolio-dsl/README.md`, lines 14–15, 84–116), the ablation study honestly revealed that `Greedy-only` (`0.998205` at $N=100$) accounted for almost all of `Evolved DSL`'s quality (`0.998217`), with outer evolution mainly selecting the supplied `return-per-cost` heuristic and shrinking the tree around it. Designing the new study around causal ablations—specifically testing whether **evolved control flow (A1/A2)** adds value beyond **random DSL sampling (A3)**, **formula-only evolution in a fixed control scaffold (A4)**, **supplied initializers**, and **configured conventional algorithms (A5)**—is the right methodological next step.

However, our audit identified **two blocking design/runtime issues** and **three concrete G1 code/oracle issues** (with executed counterexamples below) that should be addressed before resuming G2.

---

## 3. Blocking Issues

### Blocker B1 — Inner Evaluation Budget (`256`) vs. Neighborhood Size and Domain Structure Risks Repeating the Ceiling / Indistinguishability Effect

1. **Portfolio ($N=50, K=5$) neighborhood size vs. `:solution-proposals 256` (`configs/flagship-proposal.edn`, lines 8–12, 31–36, 48–50):**
   - For $N=50, K=5$, there are $\binom{50}{5} = 2,118,760$ feasible portfolios.
   - The 1-swap neighborhood of a single portfolio has size $K(N-K) = 5 \times 45 = 225$ candidates.
   - Under `:objective-aware-neighbor-scans :charge-each-candidate` (`configs/flagship-proposal.edn`, line 49; `docs/FINAL_EXPERIMENT_PLAN.md`, lines 250–253), evaluating a full 1-swap neighborhood of **one** portfolio consumes `225` of the `256` proposal budget (88% of the entire run).
   - Even if `local-search` operates as in the legacy runtime (`vendor/evolving-portfolio-dsl/src/portfolio_evolution/optimizer_runtime.clj`, lines 876–925) by applying one mutation/swap per individual in a population of size 20–50, each `local-search` or `transform` step consumes 20–50 proposals. That allows only **5 to 12 loop iterations** before the 256-proposal budget is exhausted.
   - **Why this threatens Hypothesis H2 (`Full GP` vs. `Formula-only`):** Stateful control flow (e.g., `(if (stagnant-for? 4) (perturb ...) (local-search ...))`) requires enough search steps for stagnation predicates, phase transitions, and restarts to meaningfully fire and recover. At 5–12 loop steps, an evolved multi-branch control tree cannot behaviorally separate itself from a fixed scaffold (`A4 Formula-only`), biasing H2 toward the null.

2. **Single-machine scheduling ($J=30$, objectives: mean completion time and total tardiness, `docs/FINAL_EXPERIMENT_PLAN.md`, lines 110–123):**
   - With all jobs released at $t=0$ and no preemption ($1 \parallel \sum C_j, \sum T_j$):
     - **SPT (Shortest Processing Time)** is mathematically **exact** for minimizing mean completion time ($\frac{1}{J}\sum C_j$).
     - **EDD (Earliest Due Date)** minimizes maximum lateness $L_{\max}$ and is a classic strong heuristic for total tardiness ($\sum T_j$).
     - Simple static priority-rule combinations of $p_j$ and $d_j$ (e.g., parameterized combinations of SPT/EDD/ATC/MDD sorted without objective evaluations) already trace a large portion of the Pareto front on 30-job single-machine instances.
   - **Risk:** Just as `return-per-cost` saturated the 0–1 knapsack benchmark in `evolving-portfolio-dsl`, static job-priority formulas (`A4 Formula-only`) + SPT/EDD initializers may saturate the 30-job single-machine Pareto front, leaving negligible headroom for evolved search control (`A1/A2`). The development **Headroom Gate** (`docs/FINAL_EXPERIMENT_PLAN.md`, line 350) must be treated as a hard stop/redesign gate for both domains before freezing the protocol.

### Blocker B2 — Per-Evaluation $O(K \cdot T)$ `BigDecimal` Construction in `niki.optimizer.portfolio/evaluate` (`src/niki/optimizer/portfolio.clj`, lines 78–108)

- **Measured benchmark on this machine (Ryzen 7 9700X, Temurin 25.0.5+8, Clojure 1.12.6):**
  Executing `niki.optimizer.portfolio/evaluate` on a synthetic $N=50, K=5, T=365$ instance takes **0.723 ms per evaluation** (~`1,384` evaluations/sec after warm-up).
  ```clojure
  ;; Executed benchmark summary (N=50, K=5, T=365, 2000 iterations after 500 warmup):
  {:iters 2000,
   :elapsed-ms 1445.4763,
   :per-eval-ms 0.72273815,
   :evals-per-sec 1383.626974721066,
   :projected-1worker-hours-for-261M 52.545954457600004}
  ```
- **Root cause in `src/niki/optimizer/portfolio.clj`:**
  1. Lines 87–91: On **every call** to `evaluate`, `(BigDecimal. (double x))` is executed $K \times T = 5 \times 365 = 1,825$ times on static asset returns that never change after `prepare-instance`. Because exact binary64 fractions typically have ~53–55 decimal digits (exceeding `BigDecimal`'s 18-digit `intCompact` fast path), every conversion allocates a `BigInteger` on the heap.
  2. Lines 94–96: Every call to `evaluate` computes `:portfolio-returns` (`series`) by executing $T = 365$ `BigDecimal.divide(..., MathContext/DECIMAL128)` calls, even when the caller (an optimizer inner loop) only inspects `:objectives`.
  3. At `0.723 ms` per portfolio evaluation, the portfolio half of the flagship ceiling (`130.87M` proposals) requires **~26.3 CPU-hours** on 1 worker just inside `portfolio/evaluate`, before accounting for interpreter steps, archive dominance updates, hypervolume calculation, or matched-time ablations.
- **How to preserve 100% bit-for-bit identical `BigDecimal` exactness in $O(K^2)$ instead of $O(K \cdot T)$:**
  Because $S_t = \sum_{i \in S} r_{i,t}$ and $Q = \sum_{t=1}^T S_t = \sum_{i \in S} Q_i$ (where $Q_i = \sum_{t=1}^T r_{i,t}$), the centered residual $d_t = T S_t - Q = \sum_{i \in S} (T r_{i,t} - Q_i)$ satisfies the exact algebraic identity over `BigDecimal`:
  $$\text{variance-numerator} = \sum_{t=1}^T d_t^2 = \sum_{i \in S} \sum_{j \in S} C_{ij}, \quad \text{where } C_{ij} = \sum_{t=1}^T (T r_{i,t} - Q_i)(T r_{j,t} - Q_j)$$
  Precomputing the exact `BigDecimal` vector $Q_i$ ($N$ entries) and the exact $N \times N$ `BigDecimal` matrix $C_{ij}$ ($50 \times 50 = 2,500$ entries) **once** inside `prepare-instance` reduces `evaluate` from $1,825$ `BigDecimal` constructions + $365$ squarings to **$K=5$ additions for `total` and $K^2 = 25$ additions for `variance-numerator`**, yielding the **exact same `BigDecimal` numerator and `DECIMAL128` risk** with a $50\times$–$100\times$ speedup (and `:portfolio-returns` can be computed lazily or in a separate helper when needed).
- **Bonus benefit of $O(K^2)$ evaluation:** At $10\text{–}15\ \mu\text{s}$ per evaluation, you can exhaustively evaluate **all $\binom{50}{5} = 2,118,760$ portfolios of an $N=50, K=5$ market instance in ~20–30 seconds (or ~2 seconds in primitive/parallel form)**, giving you the **true exact discrete Pareto front** for $N=50, K=5$ without needing a commercial MIQP solver!

---

## 4. Concrete G1 Code & Oracle Findings (With Executed Counterexamples)

### Finding G1-1 (Major) — Input Coercion Mismatch Between `portfolio.clj` and `portfolio_reference.clj`

- **Locations:**
  - `src/niki/optimizer/portfolio.clj`, line 54 (`(mapv #(mapv double (:returns %)) assets)`) and line 89 (`(BigDecimal. (double x))`).
  - `src/niki/optimizer/portfolio_reference.clj`, line 30 (`(mapv #(mapv rationalize (:returns %)) (:assets panel))`).
- **Mechanism of defect:**
  1. **When inputs are IEEE 754 `double`s:** `portfolio/evaluate` converts each `double` via `new BigDecimal(double)` (its exact 53-bit binary64 dyadic rational value). By contrast, `portfolio-reference/prepare-reference` calls Clojure's `(rationalize x)`, which for a `Double` calls `BigDecimal.valueOf(double)`—parsing `Double.toString(x)` (the shortest decimal representation, at most 17 significant digits), **not** the exact binary64 value!
  2. **When inputs are 64-bit `Long`s $> 2^{53}$, high-precision `BigDecimal`s, or `Ratio`s:** `portfolio/prepare-instance` coerces them to `double` (losing precision beyond 53 bits), whereas `portfolio-reference/prepare-reference` preserves their exact uncoerced rational values.
  3. **When inputs are non-standard `java.lang.Number` subclasses (e.g., `DoubleAdder`):** `portfolio/prepare-instance` converts via `(double x)`, whereas Clojure's `rationalize` falls through to integer `LONG_OPS` and truncates via `.longValue()`.

- **Executed Counterexample 1A (Pure `double` panel where `direct` and `reference` disagree by $1.48 \times 10^6$):**
  ```clojure
  (require '[niki.optimizer.portfolio :as p]
           '[niki.optimizer.portfolio-reference :as r])

  (let [panel {:k 4
               :assets [{:id :a0 :dates ["2000-01-01" "2000-01-02"] :returns [1.0e23 0.0]}
                        {:id :a1 :dates ["2000-01-01" "2000-01-02"] :returns [0.0 2.0e22]}
                        {:id :a2 :dates ["2000-01-01" "2000-01-02"] :returns [0.0 4.0e22]}
                        {:id :a3 :dates ["2000-01-01" "2000-01-02"] :returns [0.0 4.0e22]}]}
        d   (p/evaluate (p/prepare-instance panel) [0 1 2 3])
        ref (r/evaluate (r/prepare-reference panel) [0 1 2 3])]
    {:direct-risk (:sample-standard-deviation d)
     :ref-risk    (:sample-standard-deviation ref)
     :ref-exact-var (:exact-variance ref)})
  ;; Executed output:
  ;; => {:direct-risk 1482910.4003789306, :ref-risk 0.0, :ref-exact-var 0N}
  ```
  *Why:* $2\times 10^{22}$ and $4\times 10^{22}$ are exact in binary64 ($5^{22} < 2^{53}$), whereas the `double` literal `1.0e23` has exact binary64 value $10^{23} - 8,388,608$ ($5^{23} > 2^{53}$). `portfolio-reference` rationalizes `"1.0E23"` to $10^{23}$ and returns `0.0` risk, whereas `portfolio/evaluate` uses the exact binary64 value and returns `1,482,910.4`.

- **Executed Counterexample 1B (Ordinary daily returns `0.3`, `0.1`, `0.2` as `double`s):**
  ```clojure
  (let [panel {:k 2
               :assets [{:id :a0 :dates ["2000-01-01" "2000-01-02"] :returns [0.3 0.1]}
                        {:id :a1 :dates ["2000-01-01" "2000-01-02"] :returns [0.0 0.2]}]}
        d   (p/evaluate (p/prepare-instance panel) [0 1])
        ref (r/evaluate (r/prepare-reference panel) [0 1])]
    {:direct-risk (:sample-standard-deviation d)
     :ref-risk    (:sample-standard-deviation ref)
     :ref-exact-var (:exact-variance ref)})
  ;; Executed output:
  ;; => {:direct-risk 9.813077866773595E-18, :ref-risk 0.0, :ref-exact-var 0N}
  ```
  *Why:* In binary64, `(BigDecimal. 0.1) + (BigDecimal. 0.2) != (BigDecimal. 0.3)`. Thus `portfolio/evaluate` reports a positive standard deviation (`9.813e-18`), whereas `portfolio-reference/evaluate` reports `0.0` (`0N` variance), failing any strict zero-volatility check (such as `docs/evidence/g1-adversarial-probe.clj`, line 33: `(if (zero? expected) (zero? actual) ...)`). Notice that `g1-adversarial-probe.clj` (lines 20–25) had to manually pre-convert `:returns` in `ref-panel` to `(BigDecimal. (double x))` to prevent `r/prepare-reference` from calling `(rationalize double)`!

- **Executed Counterexample 1C (64-bit `Long` returns losing precision in `portfolio/prepare-instance`):**
  ```clojure
  (let [panel {:k 1 :assets [{:id :a0 :dates ["2000-01-01" "2000-01-02"]
                              :returns [9223372036854775807 9223372036854775000]}]}
        d   (p/evaluate (p/prepare-instance panel) [0])
        ref (r/evaluate (r/prepare-reference panel) [0])]
    {:direct-risk (:sample-standard-deviation d)
     :ref-risk    (:sample-standard-deviation ref)
     :ref-exact-var (:exact-variance ref)})
  ;; Executed output:
  ;; => {:direct-risk 724.0773439350247, :ref-risk 570.6351724175438, :ref-exact-var 651249/2}
  ```

- **Recommendation:** In `src/niki/optimizer/portfolio_reference.clj`, either:
  - Default `prepare-reference` to rationalize the **normalized binary64 returns** `(mapv #(mapv (fn [x] (rationalize (BigDecimal. (double x)))) %) (:returns instance))` so both evaluators share the exact same post-`prepare-instance` binary64 input domain, **or**
  - Provide explicit 2-arity `(prepare-reference panel {:keys [input-mode] :or {input-mode :normalized-binary64}})` supporting both `:normalized-binary64` and `:exact-rational-input`.

### Finding G1-2 (Minor) — `prepare-instance` Accepts Non-Map Sequential Key-Value Lists and Can Hang on Lazy Seqs

- **Location:** `src/niki/optimizer/portfolio.clj`, line 31 (`(defn prepare-instance [{:keys [k assets]}] ...)`) and line 46 (`(doseq [asset assets] ...)`).
- **Executed Counterexample:**
  ```clojure
  (p/prepare-instance '(:k 1 :assets [{:id :a :dates ["2000-01-01" "2000-01-02"] :returns [0.0 0.0]}]))
  ;; Executed output:
  ;; => #niki.optimizer.portfolio.PreparedPortfolio{:k 1, :asset-ids [:a], :dates ["2000-01-01" "2000-01-02"], :returns [[0.0 0.0]]}
  ```
- **Mechanism of defect:** Clojure's associative destructuring `[{:keys [k assets]}]` coerces sequential collections (`ISeq`) into maps via `PersistentHashMap/createAsIfByAssoc` before entering the function body. As a result:
  1. A list `'(:k 1 :assets [...])` is silently accepted even though the input contract (`docs/G1_STATUS.md`, line 65) requires a map.
  2. Passing an infinite lazy sequence (e.g., `(range)`) hangs inside Clojure's destructuring macro before the first `ensure!` is reached.
- **Recommendation:** Change signature to `(defn prepare-instance [panel] ...)` and check `(ensure! (map? panel) :invalid-panel "Expected a panel map")` and `(ensure! (every? map? assets) :invalid-assets "Every asset must be a map")` before destructuring. Also restrict return values in `finite-number?` to standard numeric types (`integer?`, `ratio?`, `decimal?`, `float?`) so mutable `java.lang.Number` subclasses like `DoubleAdder` are rejected.

### Finding G1-3 (Minor) — Shared Validation Path Between `portfolio` and `portfolio-reference`

- **Location:** `src/niki/optimizer/portfolio_reference.clj`, lines 29 and 53.
- **Observation:** Properly disclosed in `docs/G1_STATUS.md` (line 15). While acceptable for G1 because `test/niki/optimizer/portfolio_test.clj` (lines 73–113) directly tests malformed panels and selections, **do not share normalization, dominance, or archive-filtering helpers between the production G2 implementation and the G2 reference oracle.**

---

## 5. Methodological & Statistical Critique (Questions 1, 2, 3, 5, 6, 7)

### 5.1 Novelty & Publishability Against Prior Art (Question 1)
- **What will not pass peer review as a standalone claim:** Using Clojure, homoiconic S-expressions, Strongly Typed GP (Montana 1995), evolving stateful optimizers (Lones 2021), GP scheduling hyper-heuristics (Zhou & Yang 2019), or automatic MOEA configuration (Bezerra et al. 2014, 2020).
- **What is genuinely strong and publishable:** A **controlled causal attribution study** that answers *when and whether evolving executable search control flow (`A1/A2`) improves held-out Pareto approximation over evolving only the scoring formula inside a fixed control scaffold (`A4`), random sampling of the same grammar (`A3`), and equally budgeted configuration of conventional multi-objective optimizers (`A5`)*.
- In evolutionary computation venues (*GECCO*, *PPSN*, *EvoCOP*, *IEEE TEVC*, *Evolutionary Computation*), rigorous ablation studies that cleanly separate **representation prior**, **initializer strength**, **scoring formula evolution**, and **control-flow evolution** across two distinct combinatorial representations (subsets and permutations) are well-regarded—even if the empirical outcome shows that `A4` (formula-only) or `A5` matches `A1` on one or both domains.

### 5.2 Isolating Control Flow from Scoring Formulas and Heuristics (Question 2)
To make the `A1/A2` vs. `A4` (`Formula-only`) contrast airtight in G3/G4:
1. **Use a strong, non-crippled fixed scaffold for Arm A4 (`Formula-only`):** If A4's fixed scaffold is merely `(repeat-until-budget (local-search <evolved-scorer>))` without any diversification/perturbation step, and A1 wins because it combines `local-search` with `perturb`, reviewers will argue A4's scaffold was artificially weak. Give A4 a standard memetic/ILS scaffold (e.g., initialize $\to$ repeat: select archive/population $\to$ if stagnant perturb else local-search with `<evolved-scorer>`), or test A4 across the top conventional scaffold template.
2. **Define the inner multi-objective local-search & candidate-scorer semantics explicitly before G3:**
   - In single-objective knapsack (`vendor/evolving-portfolio-dsl/src/portfolio_evolution/optimizer_runtime.clj`, lines 17–68, 699–745), `drop-add` used `asset-scorer` to rank individual assets in $O(N \log N)$ without consuming objective evaluations, and then evaluated **one** resulting portfolio.
   - In the new bi-objective portfolio problem (`docs/input/typed_clojure_portfolio_plan_bg.pdf`, p. 15–16; `docs/FINAL_EXPERIMENT_PLAN.md`, lines 250–253), risk depends on pairwise covariance across all $K$ selected assets. If a `CandidateScorer` uses `:normalized-risk-change` of a proposed swap, and every evaluated swap is charged 1 unit of the 256 proposal budget, then ranking $K(N-K) = 225$ swaps to pick the best move consumes 225 proposals!
   - **Action needed before G3:** Explicitly specify in the DSL design whether:
     - (a) `CandidateScorer` scores a **small sampled tournament of $m \in \{2, 4\}$ random feasible neighbors** (charging $m$ proposals per local-search move), or
     - (b) `AssetScorer` uses precomputed instance-level asset statistics (individual $\mu_i$, $\sigma_i$, and mean correlation $\bar{\rho}_i$) to propose a single swap without objective calls, charging 1 proposal when the resulting portfolio is evaluated.

### 5.3 Hypervolume Normalization Bounds Could Artificially Compress Effect Sizes (Question 3 — Major Methodological Issue for G2)
- **Location:** `docs/FINAL_EXPERIMENT_PLAN.md`, lines 224–232 (`[-max(mu_i), -min(mu_i)]` and `[0, max(sigma_i)]` for portfolios; `[0, P]` and `[0, sum_j max(0, P - d_j)]` for scheduling).
- **The mathematical problem:**
  1. **Portfolios:** Every feasible solution is an equal-weight subset of **exactly $K=5$ distinct assets**. Therefore, no feasible portfolio can ever achieve $\max_i \mu_i$ (the best single asset) or $\min_i \mu_i$ (the worst single asset). The true analytical range of $f_1 = -\text{mean}$ over all feasible $K$-subsets is:
     $$f_{1,\min} = -\frac{1}{K}\sum_{r=1}^K \mu_{(N-r+1)}, \qquad f_{1,\max} = -\frac{1}{K}\sum_{r=1}^K \mu_{(r)}$$
     where $\mu_{(1)} \le \dots \le \mu_{(N)}$ are the sorted asset means. In crypto markets where a single outlier token can have $\mu_{(N)} = 5 \times \mu_{(N-1)}$, normalizing by the single-asset extremum $[-\max_i \mu_i, -\min_i \mu_i]$ squeezes all feasible $K=5$ portfolios into a narrow band of $[0,1]$, inflating or compressing hypervolume differences and distorting the `0.01` practical margin!
  2. **Scheduling:** For $J=30$ jobs with positive durations $p_j > 0$ and no idle time, the mean completion time $\frac{1}{J}\sum_{j=1}^J C_j$ can **never** be `0` and can **never** reach $P = \sum_j p_j$ (except when $J=1$). Its exact analytical minimum over all $J!$ permutations is the **SPT (Shortest Processing Time) schedule's mean completion time**, and its exact analytical maximum is the **LPT (Longest Processing Time) schedule's mean completion time**—both computable in $O(J \log J)$ from the job durations alone! Using `[0, P]` makes the normalization interval more than $2\times$ wider than the feasible permutation range.
- **Recommendation for G2:** Use the exact instance-derived $K$-subset bounds (average of top-$K$ and bottom-$K$ asset means) for portfolio $f_1$, and the exact SPT/LPT bounds for scheduling $f_1$. Both are 100% analytical, instance-only (zero competitor leakage), and computable in $O(N \log N)$.

### 5.4 Outer Assessment Slots (`256` slots = `32` pop + `7` batches $\times$ `32`) (Question 3)
- **Location:** `configs/flagship-proposal.edn`, lines 21–29.
- Seven generations (`:offspring-batches 7`) is very short for tree-based GP, especially for testing whether subtree crossover (`H3`) assembles useful building blocks over multiple generations. Furthermore, charging duplicate ASTs a full slot (`:duplicate-consumes-slot? true`) penalizes GP arms (`A1/A2`) more than `Random DSL Search (A3)` if mutations/crossovers near small trees produce frequent structural duplicates.
- **Recommendation:** During the G7 pilot on development data, profile (a) `16` pop $\times$ `15` batches (`256` slots) vs. `32` pop $\times$ `7` batches (`256` slots), and (b) measure the duplicate slot rate of `A1`, `A2`, and `A3` before locking the outer generation schedule.

### 5.5 Missing Source Documents & Provenance Needed Before Paper Submission (Question 7)
1. **Original PDFs of the two earlier Penchev & Marchev papers** (currently summarized only second-hand in `docs/input/typed_clojure_portfolio_plan_bg.pdf`, p. 2, 24):
   - *Penchev, N.; Marchev, A. jr. — Testing the Case of Knapsack-like Portfolio Optimization Using Various Evolutionary Algorithms*
   - *Penchev, N.; Marchev, A. jr. — Multi-Objective Knapsack-like Portfolio Optimization using Evolutionary Algorithm*
2. **Written Prior-Data-Exposure Attestation (`docs/DATA_EXPOSURE.md`)** by Niki specifying which Binance Spot symbols/periods (e.g., from `summer-school-2025-crypto-forecasting`) and NASDAQ snapshots (from `Multi-objective-optimization`) were previously inspected or tuned on.
3. **Explicit License File** for `npenchev88/evolving-portfolio-dsl` if the artifact will be publicly redistributed.

---

## 6. Prioritized Action Plan Before Resuming G2

1. **[G1 Minor Patch — `src/niki/optimizer/portfolio.clj` & `portfolio_reference.clj`]:**
   - Validate `(map? panel)` and `(every? map? assets)` at the top of `prepare-instance`, and restrict `finite-number?` to canonical numeric types.
   - Align `portfolio-reference/prepare-reference` with `portfolio/prepare-instance` by rationalizing the normalized binary64 values `(BigDecimal. (double x))` (or supporting an explicit `:input-mode` flag) so `portfolio` and `portfolio-reference` agree on all `double` and `Long` inputs without external test workarounds.
   - Precompute exact `BigDecimal` asset totals $Q_i$ and exact centered cross-product matrix $C_{ij}$ in `prepare-instance` so `portfolio/evaluate` runs in $O(K^2)$ instead of $O(K \cdot T)$ while preserving bit-for-bit identical `BigDecimal` results.
2. **[G2 Design Update — `docs/FINAL_EXPERIMENT_PLAN.md` §9]:**
   - Tighten analytical HV normalization bounds for portfolio $f_1$ to top-$K$ / bottom-$K$ asset means, and for scheduling $f_1$ to exact SPT / LPT mean completion times.
   - Ensure G2 production and G2 reference oracle share zero normalization or dominance helper code.
3. **[G3 Design Update — `docs/FINAL_EXPERIMENT_PLAN.md` §6 & §10]:**
   - Specify the exact proposal-budget accounting and selection rule for multi-objective `local-search` and `CandidateScorer` so a single local-search step does not consume 225 proposals.
   - Specify the fixed control-flow scaffold for Arm `A4 (Formula-only)`.
4. **[Deferrals]:**
   - Defer external MIQP solvers (use exact $\binom{50}{5} = 2,118,760$ enumeration enabled by the $O(K^2)$ evaluator if needed), defer 30-day buy-and-hold backtesting, and defer Section 12 secondary ablations until the core 5-arm study is complete.
