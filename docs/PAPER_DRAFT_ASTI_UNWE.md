# Evolution of Multi-Objective Optimization Hyper-Heuristics via Genetic Programming with Typed S-Expressions in Clojure

**Nikolay Penchev**$^{1}$, **Angel Marchev, Jr.**$^{2}$  
e-mail: ndpenchev@uni-sofia.bg$^{1}$, e-mail: angel.marchev@unwe.bg$^{2}$

---

## Abstract

This study investigates multi-objective optimization hyper-heuristics built from typed symbolic expressions (S-expressions) via Genetic Programming (GP) in Clojure. Instead of tuning parameters inside a fixed metaheuristic, the system evolves executable search procedures encoded as quoted list trees, verifies them against static role and effect rules, and runs them inside a bounded interpreter. Across 30 independent seeds on non-overlapping NASDAQ-50 equity windows (2012–2026, selecting 5 of 50 assets) and 40-job OR-Library wt40 scheduling instances, five configurations are compared under a 256-evaluation budget: mixed GP, mutation-only GP, equal-budget random grammar sampling, fixed-template formula evolution, and configured conventional baselines (Non-dominated Sorting Genetic Algorithm II, Pareto Local Search, and Multi-Start Restart). On wt40, evolved programs attain a mean normalized hypervolume of 0.53974 (597.5 ms per instance) and outperform conventional baselines (0.52751, 632.8 ms) in 28 of 30 seeds while reducing compromise completion time and tardiness. On NASDAQ-50, evolved programs run 22% faster (127.7 versus 164.6 ms), achieve a higher return-to-risk ratio (4.33 versus 4.24), and match conventional hypervolume (0.72975 versus 0.73440) within a 0.01 equivalence band. Ablations confirm that budget-aware initialization (+0.02110 to +0.02316) and static role typing drive most of this gain.

**Key words:** Genetic Programming, Typed S-Expressions, Clojure, Multi-Objective Optimization, Hyper-Heuristics  
**JEL:** C61, C63, G11

---

## Introduction

Automated algorithm design treats the search procedure itself, rather than candidate problem solutions, as the object of optimization. Conventional metaheuristics fix initialization, neighborhood moves, and acceptance criteria in advance, so search tunes only numerical parameters and often requires manual redesign when problem structure shifts or objectives conflict. Generative hyper-heuristics address this by evolving executable search components via Genetic Programming (GP; Burke et al., 2013), while typed grammars preserve syntactic validity across heterogeneous constructs (Montana, 1995).

In earlier studies, we evaluated the Non-dominated Sorting Genetic Algorithm II (NSGA-II) against Random Search and a deterministic Lagrange-multiplier baseline on real NASDAQ binary portfolios (Penchev & Marchev, 2026b), and we evolved single-objective knapsack optimizers via typed symbolic expressions against an exact Dynamic Programming oracle (Penchev & Marchev, 2026a). That typed S-expression setup left four methodological limitations: asset costs and profits were independent, a preset `return-per-cost` rule drove nearly all quality, variation relied solely on mutation without subtree crossover, and no equal-budget random grammar control existed.

To test automated algorithm synthesis directly, we build a six-role typed Clojure domain-specific language (DSL) with static effect verification and a bounded interpreter capped at 256 proposals per instance. The primary focus is methodological rather than domain deployment; historical NASDAQ-50 equity returns (2012–2026) and J. E. Beasley's OR-Library wt40 scheduling instances (Beasley, 1990) serve strictly as frozen multi-objective testbeds without transaction costs. Across 30 independent runs per benchmark, we examine four questions: (i) whether typed S-expressions can evolve multi-objective hyper-heuristics without preset greedy rules, (ii) whether one typed framework transfers from equities to scheduling, (iii) what crossover and selection add beyond equal-budget random grammar sampling and formula tuning, and (iv) how evolved programs compare with tuned conventional baselines.

Every synthesized tree remains compact, averaging 18.7 to 19.2 abstract syntax tree (AST) nodes, and terminates within strict bounds. On wt40 scheduling, mixed GP outperforms tuned conventional baselines by +0.01222 normalized hypervolume (HV; *p* < 0.0001, in 28 of 30 seeds). On NASDAQ-50, it matches conventional HV within the 0.01 equivalence band at 22% lower runtime and higher return-to-risk ratios. The remainder of the paper covers theoretical foundations, methods, empirical results with ablations, and conclusions.

---

## Theoretical Aspects

### Lisp, Clojure, and Homoiconicity

Treating programs as symbolic trees originates in McCarthy's (1960) Lisp formalism of S-expressions and `eval`. Clojure (Hickey, 2008) brings this model to the Java Virtual Machine with immutable persistent lists `(...)`, vectors `[...]`, maps `{...}`, and keywords (`:keyword`). Because persistent collections share unchanged branches in memory, replacing a subtree yields a new tree without mutating parent programs.

Homoiconicity means code and the abstract syntax tree share one list structure. Mainstream languages parse infix text into hidden compiler trees, while Clojure treats a quoted form like `'(:add (:feature :objective-0) (:multiply (:feature :progress) (:feature :objective-1)))` directly as an unevaluated tree (Figure 1). Clojure macros transform code via this same equivalence (Hickey, 2008). Our framework uses a bounded interpreter instead of host macro expansion, yet variation still manipulates quoted trees as plain data without parsing or compilation.

![Figure 1: Homoiconic correspondence between a quoted Clojure S-expression optimizer and its typed abstract syntax tree.](figures/png300/fig0_sexpr_tree_analogy.png)  
Source: Authors' elaboration based on the implemented typed S-expression domain-specific language.  
Figure 1: Homoiconic correspondence between a quoted Clojure S-expression optimizer and its six-role typed abstract syntax tree

### Genetic Programming, Typed Grammars, and Hyper-Heuristics

Koza (1992) grounded tree-based GP in Lisp S-expressions so subtrees could recombine under syntactic closure. Closure breaks when evolved optimizers mix loop bounds, boolean guards, move operators, and floating-point scores, because untyped edits place subtrees in incompatible slots. Strongly Typed GP (Montana, 1995) and grammar-guided GP (Ryan et al., 1998; McKay et al., 2010) preserve validity through slot types. Recent work studies higher-order types (HOTGP; Fernandes et al., 2023), initialization bias against random sampling (Dick & Whigham, 2022), solver synthesis (Parthasarathy et al., 2024), and semantics-aware variation (Wyrwiński & Krawiec, 2025).

Hyper-heuristics automate algorithm construction across instances (Burke et al., 2013), spanning linear GP (Oltean, 2005), modular MOEA assembly (AutoMOEA; Bezerra et al., 2016), and program synthesis (Gulwani et al., 2017). Three current tracks relate closely here: multi-objective GP for cloud scheduling (Zaki et al., 2024; Sun et al., 2024), exact-*K* sparse or robust portfolio models (Kobayashi et al., 2023; Lapucci & Mansueto, 2024), and LLM-evolutionary hybrids such as FunSearch (Romera-Paredes et al., 2024) and EoH (Liu et al., 2024). Table 1 synthesizes these streams.

Table 1: Synthesis of related research streams and positioning of the proposed framework

| Research Stream | Representative Works | Core Focus | Role in This Study |
| :--- | :--- | :--- | :--- |
| Lisp, Clojure, Homoiconicity | McCarthy (1960); Hickey (2008) | Code and tree data share `'(:op ...)` lists with persistent structures. | Encodes immutable S-expression optimizers executed without compilation. |
| GP and Variation | Koza (1992); Wyrwiński & Krawiec (2025) | Tree crossover/mutation and learned semantics-aware variation. | Drives outer search comparing `:gp-mixed` and `:gp-mutation-only`. |
| Typed and Grammar GP | Montana (1995); Ryan et al. (1998); McKay et al. (2010); Dick & Whigham (2022); Fernandes et al. (2023) | Role/grammar rules enforce validity; recent focus on prior bias and higher-order types. | Enforces six roles and static effects while ablating v1/v2 priors and typing. |
| Generative Hyper-Heuristics | Oltean (2005); Burke et al. (2013); Bezerra et al. (2016); Zaki et al. (2024); Sun et al. (2024) | Evolves dispatching rules or modular MOEAs for combinatorial tasks. | Co-evolves multi-stage control flow and dynamic bi-objective scores in one AST. |
| Synthesis and LLM-EC | Gulwani et al. (2017); Parthasarathy et al. (2024); Romera-Paredes et al. (2024); Liu et al. (2024) | Grammar-guided solver synthesis and hybrid LLM-evolutionary search. | Replaces opaque LLMs with a deterministic grammar and random-DSL controls. |
| Discrete Portfolios and Prior Work | Kobayashi et al. (2023); Lapucci & Mansueto (2024); Penchev & Marchev (2026a, 2026b) | Exact-*K* models, real-data NSGA-II selection, and single-objective knapsack ASTs. | Removes greedy presets and generalizes to bi-objective NASDAQ-50 and wt40. |
| **This study** | **Present work (2026)** | **Six-role Clojure S-expressions across NASDAQ-50 and wt40.** | **Isolates v2 prior, typing, H1–H4 contrasts, speed, and AST ablations.** |

Source: Authors' synthesis based on the cited literature.

### Research Gap Addressed

Four gaps motivate this study. First, scheduling hyper-heuristics (Zaki et al., 2024; Sun et al., 2024) evolve priority rules inside fixed simulators, and AutoMOEA (Bezerra et al., 2016) configures predefined templates; neither tests whether one typed DSL can co-evolve control flow and bi-objective scoring across finance and scheduling. Second, LLM-evolutionary hybrids (Romera-Paredes et al., 2024; Liu et al., 2024) mix pretrained priors with evolution, so equal-budget random grammar controls are needed for attribution (Dick & Whigham, 2022). Third, exact-*K* portfolio solvers (Kobayashi et al., 2023; Lapucci & Mansueto, 2024) produce mathematical formulations instead of readable trees. Fourth, our earlier knapsack study (Penchev & Marchev, 2026a) relied on a preset greedy rule without crossover or random-DSL controls. We close these gaps under equal-budget accounting on frozen public benchmarks.

---

## Methods

### Six-Role Typed DSL, Static Effects, and Bounded Interpreter

We build a binder-free typed Clojure DSL rooted at `'(:program <domain> (:budget <operation>))` (Figure 2). Every AST node belongs to one of six disjoint syntactic roles:

- **Control flow (`:operation`):** `:noop`, `:seq`, `:repeat`, `:if`, `:initialize`, `:step`, and `:search`.
- **Loop bounds (`:count`):** bounded iteration steps in $\{2, 4, 8, 12, 16, 24, 32, 64\}$.
- **Initializers (`:initializer`) and moves (`:operator`):** `:random` or `:first` starts paired with domain neighborhood moves.
- **Guards (`:predicate`):** boolean tests `:not`, `:and`, `:less`, `:archive-changed`, `:budget-left`, and `:coin`.
- **Acceptance (`:score`):** scalarization trees combining `:objective-0`, `:objective-1`, `:progress`, constants, `:add`, and `:multiply`.

With no free variables, swapping same-role subtrees always preserves syntax. Static verification enforces effect permissions (`:pure`, `:control`, `:state`) and caps programs at 128 AST nodes, depth 12, and count 256.

![Figure 2: Architecture of the typed S-expression optimizer framework.](figures/png300/fig1_architecture_and_dsl.png)  
Source: Authors' elaboration based on the implemented Clojure architecture.  
Figure 2: Architecture of the typed S-expression optimizer framework showing (a) six-role DSL, (b) meta-evolution and bounded interpreter, and (c, d) benchmark adapters

A bounded interpreter limits execution to 4,096 steps and $B = 256$ proposals per instance. Under `cache? = true`, revisited solutions reuse stored objectives while charging one proposal against $B = 256$, so Pareto archives remain identical to uncached runs. Following Dick and Whigham (2022), we compare development-only-v1 (single-step leaves that halt early) against budget-aware-v2 (nested `:seq`, `:repeat`, and `:search` blocks scaled to 256 proposals). Figure 3 traces this two-level workflow.

![Figure 3: Two-level evolutionary and interpreter workflow across outer GP seeds and base-level runs.](figures/png300/fig1b_evolutionary_workflow.png)  
Source: Authors' elaboration based on the implemented experimental pipeline.  
Figure 3: Complete workflow of outer meta-evolution, static validation, base-level bounded interpretation, and held-out testing

### Benchmark Formulations and Normalized Hypervolume

Each benchmark splits 36 frozen instances into 6 training, 6 validation, and 24 test instances. **Exact-K Portfolio Selection (`:portfolio`)** covers 50 liquid NASDAQ equities (2012-01-03 to 2026-02-27; 3,559 trading dates, 3,558 daily return vectors), sliced into 36 non-overlapping $T = 90$-day windows with 8-day embargo gaps (6 train 2012–2014, 6 val 2014–2016, 24 test 2016–2025) to avoid temporal leakage. Evaluated in DECIMAL128 arithmetic, each equal-weight $K = 5$ portfolio minimizes negative mean daily return and sample standard deviation:

$$\min_{x \in \{0,1\}^N} \left( f_1(x) = -\bar{R}_p(x),\; f_2(x) = s_p(x) \right), \quad \text{s.t. } \sum_{i=1}^{N} x_i = K \qquad (1)$$

Portfolio moves preserve cardinality via `:swap` and `:restart`, with `:first` and `:random` initializers. **Bi-Objective Single-Machine Scheduling on OR-Library wt40 (`:scheduling`)** (Beasley, 1990) draws 36 stratified 40-job instances ($J = 40$; $\text{RDD}, \text{TF} \in \{0.2, \dots, 1.0\}$). Each permutation $\pi \in \mathfrak{S}_J$ minimizes mean completion time and total tardiness:

$$\min_{\pi \in \mathfrak{S}_J} \left( f_1(\pi) = \frac{1}{J}\sum_{j=1}^{J} C_{\pi(j)},\; f_2(\pi) = \sum_{j=1}^{J} \max\left(0, C_{\pi(j)} - d_{\pi(j)}\right) \right) \qquad (2)$$

Scheduling moves include `:insert`, `:swap`, `:reverse`, `:restart`, and Shortest Processing Time (`:first`). Quality is measured by normalized 2D hypervolume over fixed bounds in $[0,1]^2$ with reference $r = (1.05, 1.05)$ and divisor $1.1025$:

$$\mathrm{HV}_{\mathrm{norm}}(\mathcal{P}) = \frac{1}{1.1025} \sum_{m=1}^{|\mathcal{P}'|} \left(1.05 - z_1^{(m)}\right)\left(z_2^{(m-1)} - z_2^{(m)}\right), \quad z_2^{(0)} = 1.05 \qquad (3)$$

Figure 4 displays representative Pareto fronts on both domains. Because the full 50-asset universe contains over 2.1 million feasible combinations, Panel (a) illustrates a 15-asset subset of the first test window so all 3,003 feasible portfolios appear exhaustively as background points.

![Figure 4: Representative Pareto fronts on NASDAQ Portfolio Selection and OR-Library wt40 Scheduling.](figures/png300/fig2_pareto_fronts_2panel.png)  
Source: Authors' experimental runs on frozen benchmark instances.  
Figure 4: Representative non-dominated Pareto fronts on a 15-asset NASDAQ subset ($N=15, K=5, T=90$) for exhaustive visualization (a) and an OR-Library wt40 instance ($J=40$; green diamond: SPT rule; purple square: EDD rule) (b)

### Experimental Protocol and Ethical Statement

Across 30 outer seeds (0–29), each arm runs 256 outer assessments on 6 training instances, advances 8 finalists to 6 validation instances, freezes the top candidate, and tests it once on 24 held-out instances (Table 2 and Table 3). Five arms are evaluated:

- **Mixed GP (A1: `:gp-mixed`) and Mutation-Only GP (A2: `:gp-mutation-only`):** evolve v2 ASTs via role-safe subtree mutation with crossover ($p_c = 0.5$) or without crossover ($p_c = 0.0$).
- **Random DSL (A3: `:random-dsl`):** draws 256 independent v2 trees without selection.
- **Formula-Only (A4: `:formula-only`):** evolves only `:score` trees inside a fixed `:swap` scaffold.
- **Configured Baselines (A5: `:configured-conventional`):** validation-tunes NSGA-II, Pareto Local Search (PLS), and Multi-Start Restart across 256 sampled configurations.

Four paired hypotheses (**H1**: A1 vs. A3; **H2**: A1 vs. A4; **H3**: A1 vs. A2; **H4**: A1 vs. A5) use 10,000-resample paired bootstrap CIs, Holm-Bonferroni correction ($\alpha = 0.05$), and $\Delta_{\mathrm{prac}} = 0.01$. Source code, tests, frozen configs, 30-seed logs, and the NASDAQ-50 script are archived on GitHub.[^3]

**Ethics Statement.** This study uses only public historical data (NASDAQ-50) and OR-Library wt40; no human subjects, personal data, or animal experiments are involved, so ethics approval is not required.

Table 2: Main experimental parameters across both benchmark domains

| Parameter | NASDAQ-50 Portfolio (`:portfolio`) | OR-Library wt40 Scheduling (`:scheduling`) |
| :--- | :--- | :--- |
| Source and Representation | Daily returns (2012-01-03 to 2026-02-27), $N=50, K=5, T=90$ | Beasley wt40 ($J = 40$ jobs), stratified RDD and TF |
| Splits and Budgets | 6 train / 6 val / 24 test; 30 seeds × 256 outer × 256 base | 6 train / 6 val / 24 test; 30 seeds × 256 outer × 256 base |
| AST and Statistics | Max 128 nodes, depth 12; bootstrap (10,000), Holm ($\alpha = 0.05$) | Max 128 nodes, depth 12; bootstrap (10,000), Holm ($\alpha = 0.05$) |

Source: Authors' experimental design.

Table 3: Fixed hyperparameters and configured conventional baseline search space

| Component | Parameter | Fixed Value / Search Space |
| :--- | :--- | :--- |
| **Outer GP (A1–A4)** | Seeds and Outer Slots | 30 seeds (0–29); 256 slots (32 initial + 7 × 32 batches) |
| | Selection and Variation | Binary tournament ($k = 2$); crossover 0.5 (A1) or 0.0 (A2); $\le 10$ retries/slot |
| | Validation and Tie-Breaks | Top 8 train finalists to val (6 × 2 runs); select top 1 (nodes, SHA-256 ID) |
| | A4 Fixed Scaffold | `(:seq (:initialize :random) (:repeat 16 (:search 16 :swap <score>)))` (max 15 nodes, depth 5) |
| **DSL and Interpreter** | AST Bounds and v2 Values | Max 128 nodes, depth 12, count 256; counts $\{2, 4, 8, 12, 16, 24, 32, 64\}$; constants $[-4.0, 4.0]$ |
| | Inner Budget and Seeds | $B = 256$ proposals, $\le 4{,}096$ steps, `cache? = true` (1 prop./hit); 2 train / 2 val / 10 test seeds |
| **Baselines (A5)** | NSGA-II Grid | `population-size` $\in \{4, 8, 16, 32\}$; `perturbation-steps` $\in \{1, 2, 3\}$ |
| (256 samples/seed) | Pareto Local Search (PLS) | `initial-starts`, `neighbors-per-step` $\in \{1, 2, 4, 8\}$; rule {unvisited, crowding, random} |
| | Multi-Start Restart | `restart-period` $\in \{4, 8, 16, 32\}$; weights {balanced, alternating, uniform-grid} |

Source: Authors' frozen experimental configuration, archived in the public repository (Footnote 3).

---

## Results and Discussion

### Five-Arm Performance and Computational Speed Across 30 Seeds

Table 4, Figure 5, and Figure 6 report 30-seed test hypervolume, archive size ($\|ND\|$), runtime, design time, and node count. All typed DSL runs (A1–A3) finished without runtime errors, explored 243.9–253.0 distinct candidates per 256 slots, and produced 30 unique ASTs (14.1–19.2 nodes). Fixed-template `:formula-only` explored fewer candidates (162.4 and 160.9) and repeated identical formulas (9/30 and 19/30 unique).

Table 4: Thirty-seed held-out test hypervolume, runtime, and structural summary (budget-aware-v2 prior)

| Domain and Arm | Test HV (Mean ± SD) | Val. HV | $\|ND\|$ | Run (ms) | Design (s) | Nodes | Unique |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **NASDAQ-50 Portfolio** | | | | | | | |
| A1: `:gp-mixed` | $0.72975 \pm 0.00768$ | $0.75509$ | $14.9$ | $127.7$ | $1056.3 \pm 211.6$ | $18.9$ | $30/30$ |
| A2: `:gp-mutation-only` | $0.72974 \pm 0.01263$ | $0.75449$ | $14.8$ | $137.2$ | $1061.1 \pm 192.8$ | $19.2$ | $30/30$ |
| A3: `:random-dsl` | $0.72785 \pm 0.00822$ | $0.75474$ | $14.8$ | **$125.7$** | **$688.4 \pm 95.5$** | $14.2$ | $30/30$ |
| A4: `:formula-only` | **$0.73587 \pm 0.00241$** | $0.75724$ | $15.6$ | $128.7$ | $1316.2 \pm 164.8$ | $13.7$ | $9/30$ |
| A5: `:configured-conv.` | $0.73440 \pm 0.00241$ | **$0.76167$** | **$16.9$** | $164.6$ | $1499.1 \pm 192.7$ | $6.4$ | 18 / 12* |
| **OR-Library wt40** | | | | | | | |
| A1: `:gp-mixed` | **$0.53974 \pm 0.00438$** | **$0.55240$** | $4.4$ | $597.5$ | $1954.9 \pm 289.2$ | $18.7$ | $30/30$ |
| A2: `:gp-mutation-only` | $0.53933 \pm 0.00403$ | $0.55182$ | $4.4$ | **$589.7$** | $1970.4 \pm 267.5$ | $18.2$ | $30/30$ |
| A3: `:random-dsl` | $0.53970 \pm 0.00472$ | $0.55109$ | $4.5$ | $643.7$ | **$1562.0 \pm 182.9$** | $14.1$ | $30/30$ |
| A4: `:formula-only` | $0.53829 \pm 0.00054$ | $0.54650$ | $3.7$ | $649.3$ | $2213.3 \pm 394.1$ | $14.8$ | $19/30$ |
| A5: `:configured-conv.` | $0.52751 \pm 0.00212$ | $0.54321$ | **$7.4$** | $632.8$ | $2326.8 \pm 408.3$ | $6.4$ | 19 / 11* |

Source: Authors' 30-seed confirmatory experiments on NASDAQ-50 and OR-Library wt40 (bold marks best per column; *A5 selected 18 NSGA-II and 12 PLS on NASDAQ-50, and 19 NSGA-II and 11 PLS on wt40).

On wt40, `:gp-mixed` reaches a median test HV of 0.54164 (mean 0.53974) and outperforms `:configured-conventional` (mean 0.52751, median 0.52786) in 28 of 30 seeds while surpassing `:formula-only`. On NASDAQ-50, `:gp-mixed` reaches a median of 0.73163 (mean 0.72975), wins 12 of 30 seeds against `:configured-conventional`, and remains within the 0.01 equivalence band. Because evolved programs avoid generational sorting and crowding-distance updates, `:gp-mixed` runs 22% faster per NASDAQ-50 window than `:configured-conventional` and also finishes faster on wt40 and in outer design time (Table 4 and Figure 6).

![Figure 5: Thirty-seed test hypervolume distributions across search arms and v1/v2 priors.](figures/png300/fig3_five_arms_and_prior_ablation.png)  
Source: Authors' 30-seed confirmatory and prior-ablation runs.  
Figure 5: Test normalized hypervolume distributions across the five search arms and comparison of budget-aware-v2 versus development-only-v1 priors

![Figure 6: Execution speed per instance and outer design time across the five search arms.](figures/png300/fig3b_runtime_comparison.png)  
Source: Authors' held-out timing runs (`cache? = true`) and 30-seed confirmatory outer logs.  
Figure 6: Base-level execution time per instance in ms (a) and one-time outer design time per seed in s, Mean ± SD across 30 seeds (b)

### Domain-Level Quality and Concrete Solutions

Table 5 converts hypervolume into domain metrics across all 24 test instances. On NASDAQ-50, all four DSL arms attain higher peak daily returns and annualized return-to-risk ratios than `:configured-conventional`. NSGA-II and PLS gain their +0.00465 HV margin by extending the low-volatility extreme and retaining roughly two extra points (17.2 vs. 14.9 in the single-seed replay). On the first test quarter (2016–2017), median `:gp-mixed` forms 17 baskets from defensive (AAPL, ADP, COST, CSCO, ISRG; 11.28% volatility) to high-return (NVDA, AMD, CSX, FITB, HBAN; +0.438% daily). In late 2025, it builds 14 baskets spanning defensive (AAPL, COST, CTSH, IDXX, PEP; 11.99% volatility), balanced (ASML, CTSH, GILD, MAR, MU; 4.94 return-to-risk), and growth (GOOGL, INTC, KLAC, LRCX, MU; +0.686%).

On OR-Library wt40, classical SPT achieves the lowest completion time at high tardiness, whereas classical EDD degrades on both metrics. Evolved `:gp-mixed` lowers minimum tardiness by 11.6% below SPT and dominates `:configured-conventional` at the knee schedule by 46.8 completion units and 481.3 tardiness units (Table 5).

Table 5: Concrete domain-level solution quality averaged across 24 held-out test instances (Mean ± 95% CI)

| Benchmark and Method | Extreme Objective 1 | Extreme Objective 2 | Compromise / Knee Quality | $\|ND\|$ |
| :--- | :---: | :---: | :---: | :---: |
| **NASDAQ-50 Portfolio** | **Max Daily Ret. (Ann. %)** | **Min Daily Risk (Ann. %)** | **Max Ann. Ret./Risk** | **Size** |
| A1: `:gp-mixed` | +0.379% (+95.53 ± 1.29%) | 0.947% (15.04 ± 0.28%) | 4.33 ± 0.05 | 14.9 |
| A2: `:gp-mutation-only` | +0.378% (+95.29 ± 1.25%) | 0.952% (15.11 ± 0.29%) | 4.37 ± 0.05 | 14.9 |
| A3: `:random-dsl` | **+0.380% (+95.87 ± 1.26%)** | 0.962% (15.27 ± 0.33%) | 4.33 ± 0.05 | 14.9 |
| A4: `:formula-only` | +0.379% (+95.44 ± 0.63%) | 0.935% (14.84 ± 0.26%) | **4.43 ± 0.02** | 15.9 |
| A5: `:configured-conv.` | +0.371% (+93.49 ± 0.41%) | **0.845% (13.42 ± 0.23%)** | 4.24 ± 0.04 | **17.2** |
| **OR-Library wt40** | **Min Completion ($f_1$)** | **Min Tardiness ($f_2$)** | **Knee ($f_1$ / $f_2$)** | **Size** |
| Classical SPT | **734.5** | 11,173.8 | 734.5 / 11,173.8 | 1.0 |
| Classical EDD | 1,037.8 | 12,032.6 | 1,037.8 / 12,032.6 | 1.0 |
| A1: `:gp-mixed` | **775.2 ± 3.6** | 9,878.3 ± 124.1 | **781.9 / 9,928.8** | 4.4 |
| A2: `:gp-mutation-only` | 779.7 ± 4.0 | 9,880.2 ± 101.5 | 786.9 / 9,934.9 | 4.4 |
| A3: `:random-dsl` | 777.6 ± 4.6 | **9,847.3 ± 149.1** | 784.6 / 9,895.1 | 4.5 |
| A4: `:formula-only` | 779.6 ± 2.6 | 9,903.2 ± 71.2 | 785.8 / 9,957.0 | 3.7 |
| A5: `:configured-conv.` | 794.9 ± 2.0 | 10,173.7 ± 49.3 | 828.7 / 10,410.1 | **7.4** |

Source: Authors' domain-metric replay across the 30 frozen representatives on a fixed evaluation seed per held-out instance (bold marks best per column; minor $|ND|$ shifts of 0.3 points relative to Table 4 reflect single-seed replay versus 10-inner-seed averaging).

### Hypothesis Tests and Generator Prior Ablation

Table 6 reports paired bootstrap contrasts across 30 seeds. For **H1** and **H3**, mean differences remain near zero with simultaneous CIs inside $[-0.01, +0.01]$, so mixed GP, mutation-only GP, and random v2 sampling perform equivalently at 256 outer slots. Under **H4**, `:gp-mixed` matches `:configured-conventional` on portfolios and significantly outperforms it on wt40 (+0.01222, Holm *p* < 0.0001). Replacing development-only-v1 with budget-aware-v2 yields the largest effect: +0.02110 on NASDAQ-50 and +0.02316 on wt40 for `:gp-mixed`, +0.06149 and +0.01960 for `:gp-mutation-only`, and +0.05463 and +0.02258 for `:random-dsl` (all *p* < 0.0001). This agrees with Dick and Whigham (2022) on grammar prior alignment.

Table 6: Paired bootstrap contrasts across 30 seeds (10,000 resamples, $\Delta_{\mathrm{prac}} = 0.01$)

| Contrast | NASDAQ-50 Diff (Simult. 95% CI) | Portfolio Holm *p* and Verdict | wt40 Diff (Simult. 95% CI) | Scheduling Holm *p* and Verdict |
| :--- | :---: | :---: | :---: | :---: |
| **H1:** A1 vs. A3 | $+0.00190$ ($[-0.00215, +0.00599]$) | *p* = 0.4776 · Pract. equivalent | $+0.00003$ ($[-0.00300, +0.00318]$) | *p* = 1.0000 · Pract. equivalent |
| **H2:** A1 vs. A4 | $-0.00612$ ($[-0.01002, -0.00255]$) | *p* = 0.0008 · Inconclusive* | $+0.00144$ ($[-0.00085, +0.00311]$) | *p* = 0.2538 · Pract. equivalent |
| **H3:** A1 vs. A2 | $+0.00001$ ($[-0.00570, +0.00754]$) | *p* = 0.9318 · Pract. equivalent | $+0.00040$ ($[-0.00197, +0.00265]$) | *p* = 1.0000 · Pract. equivalent |
| **H4:** A1 vs. A5 | $-0.00465$ ($[-0.00846, -0.00111]$) | *p* = 0.0060 · Pract. equivalent | **$+0.01222$** ($[+0.00937, +0.01425]$) | *p* < 0.0001 · Superior* |
| **Prior:** v2 vs. v1 | **$+0.02110$** ($[+0.01392, +0.02848]$) | *p* < 0.0001 · Pract. superior | **$+0.02316$** ($[+0.01637, +0.03060]$) | *p* < 0.0001 · Pract. superior |

Source: Authors' paired bootstrap analysis (*Note:* Scheduling H4 unadjusted 95% CI is $[+0.01022, +0.01390]$; Portfolio H2 unadjusted 95% CI is $[-0.00915, -0.00324]$).

### Anytime Budget Scaling and Mechanism Ablations

To isolate individual mechanisms, we ran diagnostic replays on representative seeds 0 and 1 across all 24 held-out instances and 10 inner seeds (240 runs per candidate). Across budgets $B \in \{16, 32, 64, 128, 256\}$ (Figure 7), `:gp-mixed` converges faster than `:configured-conventional`. On NASDAQ-50, it leads through $B = 128$ (0.66832 vs. 0.64250 at $B = 64$; 0.70545 vs. 0.69347 at $B = 128$) before baselines catch up at $B = 256$. On wt40, it passes `:configured-conventional` by $B = 64$ (0.46570 vs. 0.45707) and reaches 0.54067 vs. 0.52760 on these two seeds, while v1 programs plateau lower (0.70890 and 0.51751) from early exits.

![Figure 7: Anytime normalized test hypervolume across evaluation budgets B = 16 to 256.](figures/png300/fig4_anytime_budget_scaling.png)  
Source: Authors' diagnostic budget-scaling replay on representative seeds 0 and 1 across 24 test instances (240 runs per candidate).  
Figure 7: Anytime normalized test hypervolume scaling across per-instance evaluation budgets $B \in \{16, 32, 64, 128, 256\}$

Figure 8 isolates three mechanisms. First, under **Static Role Typing** (Panel a), 1,000 typed v2 draws yield 100% valid trees (0 rejections), and typed variation passes checks on 86.8%–93.5% of edits. Untyped trees (`:untyped-validated`) reject ~91% of edits (6,199 rejections for 613 valid trees; 38.7% default fallbacks), inspect only 6.0–8.5 trees per 256 slots, and collapse to 0.36461 HV on portfolios and 0.35601 on wt40.

Second, in **AST Knockouts** (Panel b), deleting the search body (K1) reduces HV by -0.37724 on NASDAQ-50 and -0.18466 on wt40. Unlike our earlier knapsack study (Penchev & Marchev, 2026a), evolved loops perform most optimization here. Zeroing `:score` (K3) reduces HV by -0.11241 and -0.11874, while removing `:objective-1` (K6) costs -0.02209 and -0.01690; K2, K4, and K5 each shift HV under 0.001.

Third, under **Cache Accounting** (Panel c), `cache? = true` preserves identical archives (`max-hv-abs-diff = 0.0`), eliminates 17.5% of NASDAQ-50 calls (10,771 calls over 240 runs; 147.0 to 127.7 ms), and accelerates wt40 from 684.8 to 597.5 ms. Uncharged cache hits would grant 22.7–42.5 free proposals and inflate HV by +0.00163 to +0.00256, which justifies strict proposal charging.

![Figure 8: Mechanism ablations for static typing, AST knockouts, and evaluation caching.](figures/png300/fig5_mechanism_ablations_3panel.png)  
Source: Authors' diagnostic mechanism ablations on representative seeds 0 and 1 across frozen test instances.  
Figure 8: Mechanism ablations comparing (a) typed versus untyped validity rates, (b) AST knockouts K0–K6, and (c) cache evaluation savings

### Meta-Evolution Dynamics and Evolved Program Inspection

Across batches B0–B7 (Figure 9a), `:gp-mixed` raises training HV by +0.03113 on NASDAQ-50 (0.73891 to 0.77005) and +0.03067 on wt40 (0.52131 to 0.55198). Without manual hints, final ASTs specialize by domain (Figure 9b). All 30 portfolio trees select `:swap` (12 add `:restart`), as expected for cardinality-preserving moves. On wt40, 26 of 30 programs adopt `:insert`, compared with 9 using `:swap`, 4 using `:restart`, 2 using `:reverse`, and 9 initializing with SPT (`:first`).

![Figure 9: Meta-evolution training trajectories and structural feature frequencies.](figures/png300/fig6_meta_evolution_and_ast_structure.png)  
Source: Authors' 30-seed meta-evolution logs and AST tallies.  
Figure 9: Outer meta-evolution training trajectories (a) and structural operator/feature frequencies across the 30 final `:gp-mixed` programs (b)

Because every optimizer is stored as a quoted Clojure list, its logic reads directly as code. On NASDAQ-50, median `:gp-mixed` (Seed 28, test HV 0.73259, 19 nodes) runs 32 `:restart` moves before 24 repetitions of 24-proposal `:swap` search under $\mathrm{objective}_0 + \mathrm{progress}\cdot \mathrm{objective}_1$:

```clojure
'(:program :portfolio
  (:budget
    (:seq
      (:search (:count 32) (:operator :restart)
        (:add (:feature :objective-0) (:constant -2.0)))
      (:repeat (:count 24)
        (:search (:count 24)
          (:operator :swap)
          (:add (:feature :objective-0)
            (:multiply (:feature :progress) (:feature :objective-1))))))))
```

On wt40, median `:gp-mixed` (Seed 21, test HV 0.54164, 17 nodes) places this same annealing formula inside nested `:insert` loops. Top Seed 0 (test HV 0.54334, 35 nodes) constructs a two-stage strategy that initializes with SPT (`:first`), runs 32 `:swap` proposals under equal weights ($\mathrm{objective}_0 + \mathrm{objective}_1$), and finishes with repeated 64-proposal `:insert` moves under $\mathrm{objective}_0 + \mathrm{progress}\cdot \mathrm{objective}_1$.

### Discussion and Limitations

Unlike our earlier knapsack study (Penchev & Marchev, 2026a), where greedy initialization supplied nearly all quality, AST knockouts here show that evolved loops and dynamic scalarizations drive multi-objective quality. Meanwhile, parity between `:gp-mixed` and `:random-dsl` under v2 (H1) shows that when a grammar prior concentrates mass on budget-aligned loops, 256 outer slots leave modest room for selection to outpace random draws (Dick & Whigham, 2022).

Three limitations apply. First, single-trajectory DSL primitives excel on scheduling and converge fast on portfolios, yet yield narrower fronts than NSGA-II (14.9 vs. 16.9 in Table 4). Second, equal-weight $K = 5$ portfolios omit transaction costs to keep focus on methodology. Third, secondary ablations (Figures 7 and 8) used representative seeds 0 and 1 across all 24 test instances; scaling outer budgets and expanding ablations to 30 seeds remains future work.

---

## Conclusion

This study centers on automated algorithm design methodology rather than a single application domain. NASDAQ-50 portfolios and OR-Library wt40 served strictly as frozen testbeds to test whether six-role Clojure S-expressions can evolve multi-objective hyper-heuristics without preset greedy rules or language priors. Across 30 runs per suite, static checks prevented invalid-tree collapse and yielded compact, readable trees (18.7–19.2 nodes). Ablations isolated the core drivers: evolved loops supplied most hypervolume quality, proposal-charged caching preserved fair budget accounting at lower runtime, and budget-aware initialization (`budget-aware-v2`) produced the largest gain (+0.02110 to +0.02316 HV). Mixed GP outperformed tuned baselines on wt40 (28 of 30 seeds) and matched NSGA-II and PLS within 0.01 on NASDAQ-50 at 22% lower runtime. Future work will integrate a Multi-Stage Selection Procedure (MSSP), add population crossover and crowding-distance operators, scale outer GP budgets, and model transaction costs.

---

## Acknowledgements

This work was financially supported by the UNWE Research Project NID NI-11/2026/A.

---

## References

Beasley, J. E. (1990). OR-Library: Distributing test problems by electronic mail. *Journal of the Operational Research Society*, *41*(11), 1069–1072. https://doi.org/10.1057/jors.1990.166

Bezerra, L. C. T., López-Ibáñez, M., & Stützle, T. (2016). Automatic component-wise design of multiobjective evolutionary algorithms. *IEEE Transactions on Evolutionary Computation*, *20*(3), 403–417. https://doi.org/10.1109/TEVC.2015.2474158

Burke, E. K., Gendreau, M., Hyde, M., Kendall, G., Ochoa, G., Özcan, E., & Qu, R. (2013). Hyper-heuristics: A survey of the state of the art. *Journal of the Operational Research Society*, *64*(12), 1695–1724. https://doi.org/10.1057/jors.2013.71

Dick, G., & Whigham, P. A. (2022). Initialisation and grammar design in grammar-guided evolutionary computation. *arXiv*. https://arxiv.org/abs/2204.07410

Fernandes, M. C., de França, F. O., & Francesquini, E. (2023). HOTGP: Higher-order typed genetic programming. In *Proceedings of the Genetic and Evolutionary Computation Conference (GECCO '23)*. ACM. https://doi.org/10.1145/3583131.3590464

Gulwani, S., Polozov, O., & Singh, R. (2017). Program synthesis. *Foundations and Trends in Programming Languages*, *4*(1–2), 1–119. https://doi.org/10.1561/2500000010

Hickey, R. (2008). The Clojure programming language. In *Proceedings of the 2008 Symposium on Dynamic Languages (DLS '08)* (pp. 1–12). ACM. https://doi.org/10.1145/1408681.1408682

Kobayashi, K., Takano, Y., & Nakata, K. (2023). Cardinality-constrained distributionally robust portfolio optimization. *European Journal of Operational Research*. https://doi.org/10.1016/j.ejor.2023.01.037

Koza, J. R. (1992). *Genetic programming: On the programming of computers by means of natural selection*. MIT Press.

Lapucci, M., & Mansueto, P. (2024). Cardinality-constrained multi-objective optimization: Novel optimality conditions and algorithms. *Journal of Optimization Theory and Applications*. https://doi.org/10.1007/s10957-024-02397-3

Liu, F., Tong, X., Yuan, M., Lin, X., Luo, F., Wang, Z., Lu, Z., & Zhang, Q. (2024). Evolution of heuristics: Towards efficient automatic algorithm design using large language model. In *Proceedings of the International Conference on Machine Learning (ICML 2024)*.

McCarthy, J. (1960). Recursive functions of symbolic expressions and their computation by machine, part I. *Communications of the ACM*, *3*(4), 184–195. https://doi.org/10.1145/367177.367199

McKay, R. I., Hoai, N. X., Whigham, P. A., Shan, Y., & O'Neill, M. (2010). Grammar-based genetic programming: A survey. *Genetic Programming and Evolvable Machines*, *11*, 365–396. https://doi.org/10.1007/s10710-010-9109-y

Montana, D. J. (1995). Strongly typed genetic programming. *Evolutionary Computation*, *3*(2), 199–230. https://doi.org/10.1162/evco.1995.3.2.199

Oltean, M. (2005). Evolving evolutionary algorithms using linear genetic programming. *Evolutionary Computation*, *13*(3), 387–410. https://doi.org/10.1162/1063656054794815

Parthasarathy, D., Mitchell, W. B., & Köstler, H. (2024). Evolving algebraic multigrid methods using grammar-guided genetic programming. *arXiv*. https://arxiv.org/abs/2412.05852

Penchev, N., & Marchev, A., Jr. (2026a). Evolution of interpretable optimization algorithms through typed S-expressions for binary knapsack-like portfolio selection. In *Artificial Intelligence: Methodology, Systems, and Applications (AIMSA)*.

Penchev, N., & Marchev, A., Jr. (2026b). Multi-objective knapsack-like portfolio optimization using evolutionary algorithm (UNWE Research Project No. NID NI-11/2026/A). University of National and World Economy.

Romera-Paredes, B., Barekatain, M., Novikov, A., Balog, M., Kumar, M. P., Dupont, E., Ruiz, F. J. R., Ellenberg, J. S., Wang, P., Fawzi, O., Kohli, P., & Fawzi, A. (2024). Mathematical discoveries from program search with large language models. *Nature*, *625*, 468–475. https://doi.org/10.1038/s41586-023-06924-6

Ryan, C., Collins, J. J., & O'Neill, M. (1998). Grammatical evolution: Evolving programs for an arbitrary language. In *Genetic Programming: First European Workshop, EuroGP'98* (pp. 83–96). Springer. https://doi.org/10.1007/BFb0055930

Sun, Z., Mei, Y., Zhang, F., Huang, H., Gu, C., & Zhang, M. (2024). Multi-tree genetic programming hyper-heuristic for dynamic flexible workflow scheduling in multi-clouds. *IEEE Transactions on Services Computing*. https://doi.org/10.1109/TSC.2024.3394691

Wyrwiński, P., & Krawiec, K. (2025). Learning semantics-aware search operators for genetic programming. In *Proceedings of the Genetic and Evolutionary Computation Conference Companion (GECCO '25 Companion)*. ACM. https://doi.org/10.48550/arXiv.2502.04568

Zaki, T., Zeiträg, Y., Neves, R., & Figueira, J. R. (2024). A cooperative coevolutionary genetic programming hyper-heuristic for multi-objective makespan and cost optimization in cloud workflow scheduling. *Computers & Operations Research*, 106805. https://doi.org/10.1016/j.cor.2024.106805

[^1]: Faculty of Economics and Business Administration, Sofia University "St. Kliment Ohridski", Sofia, Bulgaria, ORCID: 0009-0000-8955-2474.
[^2]: Department of Management, University of National and World Economy (UNWE), and Faculty of Economics and Business Administration, Sofia University "St. Kliment Ohridski", Sofia, Bulgaria, ORCID: 0000-0002-5090-3123.
[^3]: Clojure source code, test suites, frozen configs, 30-seed logs, and the NASDAQ-50 script are archived at https://github.com/npenchev88/typed-sexpr-hyperheuristics alongside OR-Library wt40 (Beasley, 1990).
