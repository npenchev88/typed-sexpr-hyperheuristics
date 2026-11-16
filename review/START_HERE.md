# Start here — independent G1/design review

**As of November 7, 2026. G2 is paused at Niki's request.** This package is for a separate check and opinion, not implementation or a confirmatory run.

## Read first

1. `docs/EXTERNAL_REVIEW_BRIEF.md`: self-contained research summary, prior-work provenance, motivation, current state and requested critique.
2. `docs/G1_STATUS.md`: current implemented milestone and acceptance evidence.
3. `docs/FINAL_EXPERIMENT_PLAN.md`, `configs/flagship-proposal.edn`: proposed methodology and unapproved protocol data.
4. `docs/DECISIONS.md`, `docs/REVIEW_RESOLUTION.md`: rationale and unresolved gates.
5. `src/niki/optimizer/`, `test/niki/optimizer/`, `resources/fixtures/`, and `scripts/verify-g1.clj`: new G1 code and checks.
6. `docs/input/`: the two supplied Bulgarian planning PDFs and the preserved McCarthy recursive-functions PDF.
7. `vendor/evolving-portfolio-dsl/`: tracked files from pinned upstream commit `29252c5b86d9f8a15099d85893e5e97861cb705d`, including historical results. This is the older single-objective knapsack prototype, not new experiment output.

Use `review/REVIEW_PROMPT.txt` as the initial instruction to the reviewing model. `docs/EXPERIMENT_OVERVIEW.png` is the visual overview.

## Provenance and known limitations

- Original earlier **Penchev/Marchev research-article PDFs are not available here**. Their summaries come from sections 0 and 9/references of the supplied planning PDFs. Publication years, venues, original tables and dataset identity are not independently established by this package. Obtain the originals from Niki before making article-specific claims.
- Related repositories other than the pinned Clojure prototype are described through the supplied plans, not bundled or freshly audited.
- The PDF/HTML/dossier are November 2 planning snapshots, not the latest implementation report. The main Markdown plan has a November 7 G1 milestone annotation but its opening status still has pre-G1 wording. Use `G1_STATUS.md` and the evidence for current state; no inconsistent paragraph should be used to claim a new experiment has run.
- `EXPERIMENT_OVERVIEW_CONTINUE.md` is withdrawn erroneous output, excluded from this package. Do not use it as a protocol or source of references.
- Final independent AI code review passed; this is not publication peer review or a universal numerical proof. Failed reviews and their test-first fixes are preserved.
- Upstream redistribution/license status remains unresolved; this local review copy is not a public-release authorization. Nothing has been uploaded or sent to the reviewing service by this packaging step.

## What is and is not in the archive

Included: design documents, new source/tests, synthetic fixture, configurations, numerical result CSV, review and regression evidence, original supplied planning PDFs, tracked prior-prototype code/results, environment lock, launchers, source ledger, file map and SHA-256 manifest.

Excluded: `.git` directories, `.tools` JDK/CLI binaries, Maven caches, user config, personal files outside the named project, market datasets, credentials, and unpublished original article PDFs that were not provided here.

This is a **review-source/evidence bundle**, not a self-contained installed runtime. On the original PC, use the existing project-local launchers. On another machine restore the pinned environment per `docs/ENVIRONMENT.md` and `environment.lock.json`, or use an appropriate installed Clojure CLI/JDK with the root `deps.edn`. The clean-machine rebuild itself has not been validated.

From the original project root in PowerShell:

```powershell
.\clojure.cmd -M:g1-test
.\clojure.cmd -M:g1-test:legacy-version
.\clojure.cmd -M scripts/verify-g1.clj
```

With a compatible installed Clojure CLI, equivalent source-level commands are:

```text
clojure -M:g1-test
clojure -M:g1-test:legacy-version
clojure -M scripts/verify-g1.clj
```

Do not invoke full upstream experiments merely to review this package. Recorded tests and static inspection are a different scope from reproducing historical performance experiments.

`review/G1_REVIEW_MANIFEST.json` inventories archived files and hashes. `docs/evidence/g1-deliverables.json` is the preceding accepted-G1 snapshot manifest; the package builder verifies those files still match. Archive CRCs and every member hash are checked after creation.
