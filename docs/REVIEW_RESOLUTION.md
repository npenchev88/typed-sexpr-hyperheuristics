# Review resolution · November 2, 2026

Two independent methodological reviews were considered. Their alternative numerical proposals were not silently merged. The main plan v1.1 and `configs/flagship-proposal.edn` are the canonical proposal, pending pilot and preregistration.

## Accepted improvements

- Add direct prior art on typed GP, executable optimizer evolution, GP scheduling and automatic multiobjective component configuration.
- Treat configured conventional algorithms as a full outer-search arm, not merely an untuned deployed baseline.
- Define the primary estimand over the complete training-plus-selection procedure and retain every outer seed.
- Separate algorithmic randomness on a fixed benchmark from inference about independent market periods.
- Reserve selection-validation for a frozen automated choice and document any exposure-driven redesign.
- Distinguish normal budget exhaustion from crashes and operational timeouts.
- Add a development-stage headroom gate, body/branch attribution checks, and equal-time evaluation of typing efficiency.
- Keep framework reuse, within-domain generalization and unchanged-program cross-domain transfer as different claims.

## Alternatives resolved explicitly

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

## Unresolved before confirmation

Owner-attested historical exposure; point-in-time asset eligibility; core/grammar and numerical limits; baseline configuration space; measured compute and precision; exact inferential implementation and multiplicity policy; licensing for redistribution. No pristine temporal holdout, scientific superiority or financial return is claimed.
