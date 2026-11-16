# G3/G4 grammar and proof obligations

**Status:** development grammar v1; implementation checks and internal acceptance are tracked separately. These arguments are conditional correctness arguments, not empirical optimizer results or a frozen preregistration.

The executable grammar/API is specified in `G3_G4_CONTRACT.md` and implemented by `niki.optimizer.dsl`. The root is `[:program D [:budget O]]`. Scalar keyword/numeric payloads are not editable subtrees. Every vector is a node. The host supplies budgets; neither wrapper is an evolvable budget value.

## Typing and effects

Use a judgment `D ; A |- t : R ! E`, where D is the domain, R is a grammar role, E is the set of actual effects, and A is the role's permitted effect set. All children must have their declared role in the same D. Constructors have exact arities. Effects are the union of intrinsic effects and child effects, and E must be a subset of A.

| Role R | Permitted effects A |
|---|---|
| Operation | read, random, propose |
| Predicate | read, random |
| Score | read |
| Initializer, Operator | random |
| Count | none |

`:constant` and `:count` are pure. Feature reads are read effects. Coin is random; other predicate terminals read state. Random initialization is random; first initialization is pure. Proposal operations add propose; local search also reads state. Combinators union children. This language has no variables/binders or user functions, so no free-variable context or capture-avoiding substitution is needed.

Domain indexing of Count and Score is deliberately conservative, even though their syntax is shared: donation across domain IDs is rejected. Scheduling operator signatures do not imply an implemented scheduling evaluator. Extending the signature table changes the grammar and requires renewed tests/review.

## P1: replacement preserves well-typedness

Let a checked program contain editable subtree t at path p, typed R in domain D. Replace t by u typed R in D, with actual effects within that context's permitted allowance. Assume no surrounding grammar/signature changes.

**Base:** at p, the replacement has the exact required role/domain. Each leaf has a whitelisted payload and its intrinsic effects fit its role.

**Induction:** the enclosing constructor retains its opcode/arity and other children. The changed child still has the required role/domain. Its effects may differ, but they fit the child role's allowance; for this particular grammar the parent role permits the union of its intrinsic effects and all allowed child effects. Thus the enclosing node is well-typed. Repeat to the protected wrapper/root.

This preserves membership in the accepted language, not equality of actual effects, semantics, search quality, size or depth. Node/depth/count bounds are additional obligations. `replace-subtree` rechecks the entire resulting program, and crossover must recheck **both** children. Root/wrapper paths are never exposed as edit sites. The argument would need revision for lexical bindings, refined contextual effects, arbitrary calls or new constructors.

## P2: charged resource bounds

Let requested proposal budget B and fuel F be nonnegative integers. Assume the only route from program execution to candidate construction/evaluation is the request function, and every primitive is a bounded trusted host implementation.

Maintain invariants `0 <= charged-proposals <= B` and `0 <= charged-steps <= F`.

1. Before a request, test allowance. If none remains, terminate normally without invoking a primitive. Otherwise increment charged-proposals before construction, feasibility, cache lookup or evaluation. Duplication, invalid results and exceptions cannot refund that increment.
2. Before every interpreter transition, test fuel. If none remains, terminate without performing the transition. Otherwise increment charged-steps. Count visits, expression evaluation, branches and each loop iteration are covered; nested loops cannot bypass this check.
3. Cache hits may avoid a physical objective call but cannot avoid a charged request. Log objective calls immediately before invocation, including calls that fail.
4. Invalid candidates/primitive failures stop as method failures with diagnostic partial state; they do not continue on a replenished budget.

Induction over transitions gives the bounds. A finite program that finishes exactly on its allowance can return completed; exhaustion means execution attempted another unavailable transition/request. A program may terminate early without using the whole allowance.

**Assumptions and limits:** Fuel does not preempt a stuck or malicious JVM callback. Trusted adapters must not make hidden objective queries, perform IO, or contain unbounded rejection loops. Built-in portfolio sampling must use finite indexed loops; all instance vectors are finite. Worker wall-time/heap isolation is a distinct operational gate before expensive runs. This is not a security sandbox for arbitrary Clojure, native code or adversarial host objects.

Trace truncation must not alter execution, random draws, counters or archive updates. Timings are measured instrumentation and excluded from deterministic replay equality. Reproducibility requires the same canonical AST, seed, adapter/data version, numerical contract, runtime version and options—not the seed alone.

## Scoring and archives

Scores read already evaluated normalized points. Scoring an existing current/candidate point is not a new objective query; producing any new point is. Candidate archive insertion precedes scalar local-search acceptance, so rejection as the current solution must not discard nondominated evidence already paid for. Constant/feature reads and arithmetic are fuel-charged. Bounded arithmetic is part of grammar semantics, not algebra over real numbers.

The G2 exact-dominance objective front remains uncapped. Runtime maintains solution representatives separately. P3 relies on the fixed G2 geometry; no per-run or archive-derived normalization is permitted. A changed objective front is not necessarily a strict HV improvement, so the branch terminal is named `:archive-changed`.

## G4 is not a GP experiment

Typed generation and edit kernels establish language/limit preservation and actual two-parent inheritance. They do not establish unbiased sampling, useful behavior, competitive search or crossover efficacy. Construction retries/fallbacks must be visible and bounded. A later G5 assessment-slot ledger must charge duplicate/failed slots and ensure crossover replaces mutation rather than receiving an additional assessment. No outer evolutionary loop is implemented by these kernels.

The generation distribution remains a documented development choice. Before confirmation, freeze the accepted grammar, distribution/size prior, actual runtime/worker limits, operator mixture, fallback policy and all budget semantics after the external checkpoint and development pilot.
