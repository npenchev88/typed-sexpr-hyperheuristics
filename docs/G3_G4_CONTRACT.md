# G3/G4 development contract — version 1

Scope: a small binder-free typed DSL, bounded runtime, and typed variation. This is implementation detail of the proposed protocol, not a frozen confirmatory grammar. Stop for external review after G4. No G5–G9 execution is authorized.

## Grammar and types

Programs are vectors only: `[:program domain [:budget operation]]`. Domains are `:portfolio` and `:scheduling`; scheduling signatures are reserved for G6, not an implemented scheduling adapter. Root and budget wrapper are protected from variation. Budgets are supplied by the host runner, never increased by a program.

Body forms (fixed arities):

- Operation: `[:noop]`, `[:seq operation operation]`, `[:repeat count operation]`, `[:if predicate operation operation]`, `[:initialize initializer]`, `[:step operator]`, `[:search count operator score]`.
- Count: `[:count nonnegative-integer]`.
- Initializer: `[:initializer :random]` or `[:initializer :first]`.
- Operator: `[:operator :swap]`, `[:operator :restart]`; scheduling additionally permits `:insert` and `:reverse`.
- Predicate: `[:predicate :archive-changed]`, `[:predicate :budget-left]`, `[:predicate :coin]`, `[:not predicate]`, `[:and predicate predicate]`, `[:less score score]`.
- Score: `[:constant finite-number]`, `[:feature :objective-0]`, `[:feature :objective-1]`, `[:feature :progress]`, `[:add score score]`, `[:multiply score score]`.

Every role is indexed by domain, including otherwise portable Count/Score roles. This conservative convention forbids cross-domain subtree donation altogether. No binders, function calls, host symbols, tagged literals, maps, lists, sets, strings, recursion or arbitrary host evaluation are accepted in serialized programs. Both domains use the same control grammar and generic normalized-objective features.

Effects are inferred compositionally: pure leaves have no effects; features read state; coin reads randomness; random initializer and proposal operators use randomness; initialize/step/search propose solutions; seq/repeat/if combine child effects. Role-level permitted effect sets bound substitution: operation `#{:read :random :propose}`, predicate `#{:read :random}`, score `#{:read}`, initializer/operator `#{:random}`, count `#{}`. Actual inferred effects may be smaller. Replacements must inhabit the same role/domain and fit the context's permitted effects; whole-program validation follows every edit.

DSL public API, namespace `niki.optimizer.dsl`:

- `grammar-version` = 1.
- `(check-program program)` / `(check-program program limits)` returns `{:program canonical-program :domain domain :node-count n :depth d :sites [...] :limits effective-limits}` or throws ExceptionInfo. Every site is an editable AST vector, starting at body path `[2 1]`; fields include `:path`, `:type [role domain]`, actual `:effects` and `:allowed-effects`.
- `(read-program source)` / `(read-program source limits)` returns the checked canonical AST. Bound source length and bracket depth before EDN reading, reject disallowed lexical characters/tags, require exactly one form. Use clojure.edn, never the host reader/eval.
- `(replace-subtree program path replacement limits)` returns a fully rechecked canonical AST; only an existing editable site is legal.
- `(canonical-edn program)` / `(canonical-edn program limits)` returns canonical serialization; `(program-id program)` / `(program-id program limits)` SHA-256 of UTF-8 canonical serialization. Supply the same admission limits when serializing/identifying programs admitted under nondefault limits; one-argument forms retain default limits.

Default limits: max-nodes 128, max-depth 12, max-count 256, max-source-chars 65536. Hard accepted maxima: 4096 nodes, depth 64, count 4096, source chars 262144. Limits are positive standard immutable integers, max-nodes/max-depth must permit root and wrapper; unknown keys fail. Counts can be zero. Node count includes every AST vector, including root and wrapper, but not keyword/numeric payloads. Depth counts those vectors. Number constants must be standard immutable numeric classes, finite after binary64 conversion and within [-1000000,1000000]; canonical constants are doubles with signed zero removed; counts are canonical longs.

## Runtime

Namespace `niki.optimizer.runtime` implements `(run program adapter options)`. Options require `:solution-budget`, `:step-budget`, `:seed` (nonnegative budgets including zero; signed-long seed). Optional `:cache?` defaults true, `:trace?` defaults true; reject unknown options. Budget hard ceilings are development operational ceilings, not proposed experiment budgets: 1000000 proposals / 10000000 steps. Check program and adapter before execution. Runtime checks the program with DSL default limits (variation may use tighter limits, never rely on looser limits for execution).

Adapter is trusted host code, not supplied by DSL serialization. Required fields: `:domain`, stable `:id`, `:normalization`, initializer/operator sets, `:initialize` function `(kind rng)->solution`, `:propose` function `(kind current rng)->solution`, `:canonicalize` function, `:valid?` function and `:evaluate` function returning a two-coordinate objective vector. Check required capabilities against the program. Adapter functions must be bounded, and may not perform hidden objective queries. Runtime is not a JVM security sandbox for malicious host adapters.

All candidate construction, including initialization, restart, local-search neighbors, duplicates and invalid candidates, goes through one request function. Charge a proposal BEFORE calling the adapter, validation, cache lookup or objective evaluation. Charge actual objective calls immediately BEFORE the call, including calls that throw. No replenishment for duplicates or caching. A failed request consumes its charge. Canonicalization plus feasibility validation precede caching. Adapter solutions are canonical immutable values; built-in portfolio solutions are sorted exact-K integer vectors.

Every visited AST node (including root/wrapper, predicate/score/count/operator/initializer leaves) consumes fuel BEFORE its semantics. Repeat/search loop iterations also consume a transition, including a search body with no explicit AST operation node; zero-count loops visit the Count once. Recheck fuel on each transition; never call an adapter or evaluator after exhaustion. Budget exhaustion is normal status, not a method failure. The returned charge counters never exceed requested bounds.

`:initialize` and `:step` adopt the charged candidate. `:step` before initialization asks the adapter to propose from nil; portfolio handles nil by constructing a random feasible subset, charged once. `:search count operator score` requests at most count neighbors, always archives feasible evaluated points, and adopts a candidate if no current solution exists or its scalar score is <= current score. Scores use normalized objective coordinates already present in state, never new objective queries. Feature values before initialization are zero. Progress is charged proposals / solution budget (zero if budget is zero). Add/multiply clamp to [-1000000,1000000], as do feature reads; finite inputs thus cannot overflow scoring. Lower score is preferred. Score evaluation itself consumes fuel; partial work may therefore stop after archiving a candidate but before adopting it.

Predicates: archive-changed means the last successful proposal changed the objective front (not necessarily strictly increased HV); budget-left reads proposal allowance; coin draws Java Random.nextBoolean; and short-circuits; less compares score values at current point. All visited expression nodes consume fuel.

Return `{:archive objective-front :archive-solutions representative-solutions :current current-solution :charged-costs {...} :status ... :trace [...] :diagnostics ... :seed ... :program-id ... :adapter-id ... :timing {...}}`. Retain solution identities separately from G2's objective-vector archive. Counters include `:proposal-attempts`, `:valid-candidates`, `:unique-candidates`, `:cache-hits`, `:objective-calculations`, `:interpreter-steps`. Unique refers to distinct canonical feasible candidates requested, irrespective of caching. Statuses: `:completed`, `:proposal-budget-exhausted`, `:step-budget-exhausted`, `:method-failure`. Do not reinterpret a partial archive on method failure as a successful primary score. Diagnostics distinguish invalid candidate and primitive/evaluation errors. Invalid programs/options/adapters throw before run (they are construction/host errors, not a launched method failure).

Trace is deterministically ordered and records visited forms, branches, proposal lineage/operator, duplicate/cache accounting and archive changes. Bound trace memory by a documented cap; expose truncation explicitly and keep all counters even when trace is off/truncated. Replay is equality of all deterministic fields with same AST, adapter version, options and seed; `:timing` alone is excluded. Timing reports measured elapsed time and current-thread CPU time when available, not invented values. Fuel does NOT bound elapsed time inside arbitrary host code. Worker timeout/heap isolation remain operational gates before G7; no claim of general sandboxing is made here.

Namespace `niki.optimizer.portfolio-runtime` implements `(make-adapter prepared-instance)` with bounded objective-free random/first initializers, swap/restart operators, G1 evaluation, and fixed G2 normalization prepared once. Use bounded sampling without replacement (no potentially unbounded duplicate-rejection loop). K=N and N=K=1 must terminate. Initialization and every candidate are charged by runtime. Shared task preparation/normalization is outside the candidate budget and separately timed, not free scientific compute.

## Typed variation

Namespace `niki.optimizer.variation` exposes seeded generation, subtree mutation and actual two-parent crossover. Use only this grammar and full validation. Initial sizes/depth distributions are explicit development defaults, NOT frozen Random DSL priors. Generation is total within feasible role/structure limits; no open-ended rejection loops. Operations expose construction-attempt counts, rejection/fallback reasons, paths, parent/child IDs and whether children differ from parents. At most 10 attempts; an explicit unchanged-parent fallback consumes the attempted offspring slot later (G5 will own that ledger). Mixed-arm scheduling is not implemented here; crossover does not create an extra assessment allowance.

Protect both wrappers. Donors match role/domain and permitted effects. Check BOTH whole children for node/depth/count limits. A failed operation never returns an invalid AST. Log same-parent, identical-subtree, incompatible-domain and structural-limit failures explicitly. No silent fallback to a second offspring outside the requested slot.

## Acceptance and external-review boundary

Use sequential test-first slices with preserved RED/GREEN evidence. Cover malformed/lazy/tagged input, bounded parsing, role/domain/effect violations, budgets 0/1/exact exhaustion, duplicates with/without caching, multi-neighbor charging, invalid/throwing candidates, deterministic replay and randomness, branches, trace truncation and exact-K edge cases. G4 tests must include real changed offspring, both-child validity, protected wrappers, seeded reproducibility, bounded rejection/fallback and broad generated preservation checks; use independent structural assertions as well as the production checker.

Provide grammar and structural-induction sketches for P1/P2, with trusted-host assumptions explicit. Run G1/G2/upstream/protocol regressions, both language versions, and native Windows launchers. Obtain focused internal independent reviews. Prepare a self-contained external review brief/package after G4. Stop there: do not ingest market data, implement G5 arms, create scheduling data or launch an optimizer experiment.
