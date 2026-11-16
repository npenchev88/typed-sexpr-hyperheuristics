# G3 runtime implementation evidence

Scope is `runtime.clj`, `portfolio_runtime.clj`, `runtime_test.clj` and these
`g3-runtime-*` evidence files. No G4 variation, search experiment, market-data
operation, dependency, vendor edit, staging or commit was performed.

## Runtime conventions and review assumptions

- `run` performs preflight with the real DSL checker and its **default** execution
  limits, host options, domain, prepared fixed G2 normalization and every
  selected initializer/operator capability before launching. Options currently
  validate before checking the program; both are preflight construction errors.
- Budgets are nonnegative immutable integer values within the contract ceilings;
  seed is within signed-long bounds. Only literal Boolean cache/trace flags are
  accepted. Unknown options fail closed. Host adapter functions are ordinary
  Clojure functions; adapters, immutable canonical solutions, preparation and
  objective implementations are trusted bounded host code.
- All production accounting/state is persistent data. The sole advancing
  run-local object is the contract's seeded `java.util.Random`. Test atoms/proxy
  RNGs are observers used to independently count actual host calls/draws.
- Each visited node consumes one fuel unit before its semantics. Every entered
  repeat/search iteration consumes another transition. A node with the final
  available unit may finish its semantics; the next visited node/iteration is
  refused. Zero-count loops visit Count but neither operator nor body.
- Invalid feasibility (`valid?` not literal true) stops as `:method-failure`
  with `:invalid-candidate`; construction/canonicalization/validation Exceptions
  stop with `:primitive-error`, and objective/normalization Exceptions stop with
  `:evaluation-error`. Charges/partial archive survive these failures. JVM fatal
  Errors are not swallowed. No primary hypervolume/performance score is returned,
  so a failure cannot be implicitly scored as a successful archive.
- Initialization/step adopt immediately after a successful charged request.
  Search archives before scoring; without a current point it adopts directly
  (no unnecessary score visits). Otherwise both current and candidate scores
  consume node fuel, and candidate score <= current score adopts. Exhaustion
  during scoring retains the archived candidate but not its adoption.
- Objective features read fixed G2 **normalized** points, never raw coordinates
  or new objective calls. Before initialization objective features are zero.
  Progress is charged proposals / host proposal budget, or zero at budget zero.
  Arithmetic and reads clamp to [-1000000,1000000]. Every operand is finite and
  bounded before arithmetic, so products cannot overflow binary64.
- Objective keys use G2 canonical double coordinates (including positive zero).
  `:archive-solutions` is a vector aligned with the sorted `:archive`, retaining
  the first requested representative of each retained objective vector. Identity
  caches key canonical feasible solutions, not objective equality. Unique counts
  are independent of cache enablement; feasibility checks precede cache lookup.
- Trace cap is 2048 **events**, not a byte/heap-isolation claim. Events expose node
  forms/steps, if branches, proposal parents/operators, candidate duplicate/cache
  results/front changes, and search acceptance/normalized scores. Excess events
  increment `:trace-dropped` and set `:trace-truncated?`; trace-off does not change
  execution/counters and is not called truncation.
- Only `:timing` is excluded for replay. It contains measured elapsed nanoseconds,
  current-thread CPU nanoseconds when supported/enabled (otherwise nil), and
  optional adapter `:preparation-timing` under `:preparation`. Adapter preparation
  computes fixed G2 bounds once and times it separately from candidate budgets.
  Prepared G1 instance construction precedes make-adapter and remains the host's
  separately accounted responsibility.
- The portfolio adapter uses persistent-vector partial Fisher-Yates sampling:
  exactly K bounded integer draws from N,N-1,...,N-K+1. No rejection loop exists.
  Swap selects one member and one complement member (two draws), except K=N,
  where it returns a charged duplicate without a draw. Nil current constructs a
  random feasible subset once. Restart also samples once. Direct unsupported
  initializer/operator kinds throw; G1/G2 source stayed untouched.

## Structural-induction sketch (runtime contribution to P1/P2)

The DSL checker supplies a finite, role-correct AST, protected wrappers and
bounded literal Counts. For pure leaves, visiting first guards and increments
fuel. Score induction combines two finite bounded child values and clamps;
predicate induction combines booleans and short-circuits `and`. Operation
induction sequences only typed children; repeat/search iterate only a checked
Count and guard an extra transition before each body. Thus no AST path can make
interpreter steps exceed the host bound. Exhaustion preserves the pre-transition
state. This is a bound on interpreter transitions, not elapsed host-call time.

For proposals, one common request path guards proposal allowance, increments it,
and only then invokes construction, canonicalization, feasibility, cache or
objective work. Objective calls increment their own ledger immediately before
invocation; an Exception retains that increment. There is no refund, including
for duplicates or infeasible candidates. By induction over requests, proposals
never exceed the host bound; evaluations never exceed charged proposals, and
cache/unique/valid counters describe actual requests independently of fuel/trace.
Only validated finite two-coordinate points enter G2's archive. Representative
keys are the same canonical vectors as the front. Neither scoring nor trace can
query objectives or replenish budgets.

These arguments assume host primitives terminate, expose no hidden objective
queries, preserve immutable canonical identities, and are deterministic given
inputs/RNG. They are **not** JVM sandboxing, worker timeout or heap isolation;
those remain later operational gates before G7. This work stops for review.

## Test/evidence interpretation

Ten sequential RED -> GREEN slices are recorded. Missing-node-semantics REDs
(04-06) are explicit `No matching clause` errors, not parse/typo failures. Slice
09 caught and fixed a real signed-zero canonical-objective trace defect; separate
observer tests also prove actual thrown-evaluator charging and cache-order rules.
The synthetic G1 fixture and degenerate N=K=1/K=N cases run in unit tests only.

Early integration logs retain the parent DSL's then-missing `program-id` and
partial checker. The final runtime uses the real `dsl/program-id`; no DSL API var
was fabricated. A Windows inline `-e` attempt exposed launcher quote ambiguity;
`g3-runtime-native-test.clj` exercises the exact same standalone tests through
native launchers without inline quoting, and passes on both language versions.

Aggregate G3 runs initially passed, then observed an unrelated new intentional
DSL serialization RED (missing two-argument `canonical-edn`). The parent reports
that its fix is waiting for user approval. After that steering, no aggregate
or blocked serialization regression was executed again; final checks are solely
standalone owned runtime tests. No attempt was made to approve, bypass or modify
that work. `g3-runtime-report.json` records exact commands, all observed counts,
logs and this remaining integration gate.
