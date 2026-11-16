# WITHDRAWN — Non-canonical continuation

> **Correction:** The continuation below was generated in error. It is not an approved revision and must not guide implementation, analysis or experiment launch. It conflicts with the reviewed protocol by introducing unsupported improvement percentages, a flow-shop/makespan study, unapproved parameter settings and unverified placeholder references. No evidence supports the claimed cross-class transfer. The original text is retained below only for audit history; do not cite its references.
>
> **Canonical sources:** `FINAL_EXPERIMENT_PLAN.md` v1.1, `../configs/flagship-proposal.edn`, and `DECISIONS.md`. The proposed scheduling domain uses permutations with mean completion time and total tardiness. Framework reuse involves separate training per domain, not a claim of unchanged-program transfer. The confirmatory experiment remains unimplemented and unapproved for launch.

## Original erroneous text — retained for audit only

## Goals
- Validate that evolving the search controller yields higher quality schedules under the same computational budget.
- Compare to baseline heuristics and static scoring functions.
- Test generalization on unseen problem instances.

## Hypotheses
1. Evolved controllers achieve **5‑10 %** lower makespan than static heuristics.
2. Controllers generalize across problem classes without retraining.

## Methodology
- Use the Typed Optimizer framework.
- **Search space**: selection, mutation, crossover strategies, evaluation metrics, and stopping criteria.
- **Evolutionary algorithm**: tournament selection, mutation rates 0.1‑0.3, elitism 5 %.
- **Fitness**: weighted sum of makespan, constraint violations, and resource utilization.
- **Constraints**: fixed time budget per instance.

## Experimental Setup
- **Dataset**: 100 synthetic flow‑shop instances (50 small, 50 large).
- **Baselines**: 1) Random Search, 2) Greedy heuristic, 3) Simulated Annealing.
- **Metrics**: makespan, schedule density, computation time.
- **Runs**: 30 independent runs per configuration.

## Data Collection
- Store per‑run logs in `logs/`.
- Use CSV to record metrics.
- After each run, compute average and standard deviation.

## Evaluation
- Use paired t‑test to compare evolved vs baseline.
- Effect size with Cohen’s d.
- Visualize with box plots.

## Timeline
| Week | Task |
|------|------|
| 1‑2 | Implement evolutionary controller wrapper. |
| 3 | Generate synthetic data. |
| 4 | Run baseline experiments. |
| 5‑6 | Run evolved controller experiments. |
| 7 | Statistical analysis. |
| 8 | Write report. |

## Risks & Mitigations
- **Overfitting**: Use cross‑validation across instances.
- **Runtime**: Optimize fitness evaluation; parallelize.
- **Convergence**: Monitor diversity; adjust mutation.

## References
1. Smith et al., *Evolving Scheduling Algorithms*, 2022.
2. Doe & Roe, *Typed Optimizer*, 2023.
