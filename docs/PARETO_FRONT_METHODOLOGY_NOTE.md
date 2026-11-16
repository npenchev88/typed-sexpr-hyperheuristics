# Methodology Note: Discrete Combinatorial Pareto Fronts, Orthogonal Hypervolume Staircases, and Smooth Trade-Off Curves

This note records the mathematical and visual conventions used for the Pareto frontier figures ([`docs/figures/portfolio_pareto_front.svg`](file:///C:/Users/meggy/Niki/typed-optimizer-lab/docs/figures/portfolio_pareto_front.svg) and [`docs/figures/scheduling_pareto_front.svg`](file:///C:/Users/meggy/Niki/typed-optimizer-lab/docs/figures/scheduling_pareto_front.svg)) in the paper.

---

## 1. Why the Exact Pareto Front is a Finite Set of Discrete Points (Not a Continuous Surface)

In textbook continuous multi-objective optimization (where decision variables $x \in \mathbb{R}^n$ vary continuously, such as unconstrained Markowitz portfolio weights $w_i \in [0, 1]$ with $\sum_i w_i = 1$), the image of the feasible set in objective space is a connected region and its efficient boundary (the Pareto frontier) is a continuous, smooth curve.

In contrast, both benchmark domains in this study are **discrete combinatorial optimization problems**:

1. **Cardinality-Constrained Equal-Weight Portfolio Selection (`:portfolio`):**
   - A feasible candidate is a discrete subset $S \subseteq \{0, \dots, N-1\}$ of cardinality $|S| = K$, assigning weight $1/K$ to each selected asset and $0$ to all others.
   - Because the feasible set has finite cardinality $\binom{N}{K}$ ($\binom{15}{5} = 3,003$ in the G1 exact oracle fixture; $\binom{50}{5} = 2,118,760$ in the flagship benchmark), its image in $(\text{Objective}_1, \text{Objective}_2) = (-\mu_p, \sigma_p)$ is a finite cloud of isolated points.
   - Consequently, the true nondominated Pareto frontier $F^\star = \{p^{(1)}, \dots, p^{(m)}\}$ is a **finite set of discrete points** (e.g., $m = 13$ points in `g1-portfolio.edn`), often containing *unsupported* non-convex regions where no size-$K$ subset exists on the convex hull.
2. **Single-Machine Bi-Objective Scheduling (`:scheduling`):**
   - A feasible schedule is a discrete permutation $\pi \in S_n$ of $n$ jobs ($7! = 5,040$ in the G6 exact oracle fixture; $30! \approx 2.65 \times 10^{32}$ in the flagship benchmark).
   - Because fractional job ordering does not exist, the Pareto frontier in $(\bar{C}, T_{\text{total}})$ is likewise a **finite set of isolated points** ($m = 16$ points in `g6-scheduling.edn`).

---

## 2. Why Two Visual Boundaries Appear in Our Pareto Front Figures

When plotting a discrete nondominated set $F = \{(x_1, y_1), \dots, (x_m, y_m)\}$ ordered by $x_1 < x_2 < \dots < x_m$ and $y_1 > y_2 > \dots > y_m$ under minimization of both objectives, two distinct geometric objects are relevant:

### A. The Orthogonal Dominance Staircase (Exact 2-D Hypervolume Boundary)
Under weak Pareto dominance ($\preceq$), a single objective vector $(x_i, y_i)$ dominates only the upper-right orthant $[x_i, \infty) \times [y_i, \infty)$. Therefore, the union of regions dominated by $F$ inside the reference box bounded by $r = (r_x, r_y)$ is:

$$
\mathcal{D}(F, r) = \bigcup_{i=1}^{m} \bigl([x_i, r_x] \times [y_i, r_y]\bigr)
$$

The lower-left boundary of $\mathcal{D}(F, r)$ is an **orthogonal step function (staircase)** alternating between horizontal segments $(x_i, y_i) \to (x_{i+1}, y_i)$ and vertical drops $(x_{i+1}, y_i) \to (x_{i+1}, y_{i+1})$. Its exact Lebesgue measure is the 2-D Hypervolume computed by [`niki.optimizer.pareto/hypervolume`](file:///C:/Users/meggy/Niki/typed-optimizer-lab/src/niki/optimizer/pareto.clj#L95-L120):

$$
\text{HV}(F, r) = \sum_{i=1}^{m} (x_{\text{next}(i)} - x_i)(r_y - y_i)
$$

Points lying below the horizontal step $y = y_i$ and above the diagonal/smooth curve connecting $(x_i, y_i)$ to $(x_{i+1}, y_{i+1})$ are **not** dominated by $F$. In our SVG figures, we render this exact hypervolume region as the **shaded emerald polygon bounded by a faint dashed orthogonal staircase** (`stroke-dasharray="4,4"`), so the shaded area corresponds 1-to-1 with the reported `normalized-HV` metric.

### B. The Smooth Monotone Trade-Off Curve (Visual Trend Interpolation)
To make the trade-off slope between Objective 1 and Objective 2 immediately legible (matching the visual convention of continuous Pareto frontier diagrams), we overlay a **Fritsch–Carlson monotone cubic Hermite spline** (`monotone-cubic-svg-path` in [`scripts/generate-paper-svgs.clj`](file:///C:/Users/meggy/Niki/typed-optimizer-lab/scripts/generate-paper-svgs.clj#L30-L80)) passing directly through the discrete nondominated points $(x_1, y_1), \dots, (x_m, y_m)$.

Because Fritsch–Carlson tangents clamp interior slopes to preserve strict monotonicity on every interval $[x_i, x_{i+1}]$, the interpolated curve is guaranteed never to overshoot or introduce spurious oscillations between adjacent Pareto-optimal solutions.
