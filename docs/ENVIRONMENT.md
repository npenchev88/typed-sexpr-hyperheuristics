# Environment and reproducible baseline

Verified November 1, 2026 on this Windows PC.

## Do we need Leiningen?

**No.** The preserved upstream project declares its dependencies and entry points in `deps.edn`. This workspace continues that model. Clojure CLI-style execution supports a REPL, dependency resolution, aliases and program execution without Leiningen.[7][12]

Leiningen would become relevant if we deliberately adopted a `project.clj`-based project or a necessary Leiningen-only workflow. Installing both now would add a second configuration path without solving a current requirement. Do not confuse Leiningen with the Java runtime: **Java is required**.[5]

## Installed here

| Component | Pinned value | Scope |
|---|---|---|
| Java | Eclipse Temurin 25.0.5+8, Windows x64 | `.tools/jdk/jdk-25.0.5+8/` |
| CLI-compatible launcher | deps.clj 1.12.6.1673, native Windows x64 | `.tools/cli/deps.exe` |
| Clojure tools | 1.12.6.1673 | `.tools/clojure-tools/` |
| Clojure language | 1.12.6 | Root `deps.edn` |
| Upstream-language comparison | 1.12.5 | `:legacy-version` alias |
| Commons Math | 3.6.1 | `:legacy` alias |

The language and CLI have different version numbers and release cycles. Clojure's official installation guide supports Java LTS releases and currently recommends Temurin 25; the release page lists language 1.12.6 as stable. We deliberately pinned these rather than selecting a development alpha.[5][6]

The native launcher is **deps.clj**, a Clojure-CLI-compatible implementation used by the Windows `clj-msi` distribution. This is a portable installation of its released binary, not the Unix bash CLI and not a claim that an MSI was registered system-wide.[11][13][17]

No system PATH, registry Java registration, PowerShell security policy, WSL installation, trading credentials or global package manager configuration was changed. The wrapper sets Java and Clojure configuration locations for the child process only. Maven artifacts are cached under `.cache/m2`; project classpath cache is `.cpcache`. The launcher uses `-Srepro` to omit user-specific Clojure dependency configuration.[12]

## Commands that were exercised

From `C:\Users\meggy\Niki\typed-optimizer-lab` in PowerShell:

```powershell
.\clojure.cmd -Sdescribe
.\clojure.cmd -M:legacy:test
.\clojure.cmd -M:legacy:test:legacy-version
.\clojure.cmd -M:legacy:demo
```

In Git Bash:

```bash
bash ./clojure -Sdescribe
bash ./clojure -M:legacy:test
bash ./clojure -M:legacy:test:legacy-version
bash ./clojure -M:legacy:demo
```

The suite and demo were executed through the Git Bash wrapper; the Windows `.cmd` wrapper was separately smoke-tested with `(println (clojure-version))`, which returned `1.12.6`. Both wrappers dispatch the same native launcher. The launchers intentionally preserve the current working directory; use the project root for the supplied aliases.

Start an interactive REPL with `./clojure.cmd -M` in PowerShell (use `.\clojure.cmd -M` if needed) or `bash ./clojure -M` in Git Bash. Exit using the REPL's normal EOF mechanism. No persistent REPL was left running.

## Actual verification

| Check | Observed result |
|---|---|
| `java --version` | OpenJDK 25.0.5, Temurin build 25.0.5+8 |
| Native launcher description | deps.clj/tools 1.12.6.1673, reproducible mode true |
| Upstream tests, language 1.12.5 | 62 tests, 191 assertions, zero failures/errors |
| Upstream tests, language 1.12.6 | 62 tests, 191 assertions, zero failures/errors |
| `.cmd` language smoke check | 1.12.6 |
| Upstream DSL demo | Valid typed optimizer, feasible result, objective matched its tiny demonstration's DP optimum |

Latest verification logs: `docs/evidence/verified-upstream-tests-legacy.txt`, `verified-upstream-tests-current.txt`, `verified-upstream-demo.txt`, and `verified-protocol-data.txt`. The protocol-data check passed 4 tests and 23 assertions; it does not execute the proposed experiment.

The demo concerns synthetic, single-objective knapsack. It is **not evidence** about the proposed mean-volatility experiment, not a statistical performance result, and not a replication of all upstream reported numbers. The upstream source requests Java 21; these tests used Java 25. If historical environment replication becomes an acceptance requirement, separately pin Java 21 and rerun rather than silently treating the versions as identical.[8]

## Provenance and restoration

`environment.lock.json` records the upstream commit, exact artifact coordinates, publisher SHA-256 values for the JDK/native launcher downloads, local Maven-jar SHA-256 values and observed hardware. `docs/evidence/toolchain-downloads.json` preserves the download provenance. Publisher-checksum equality was checked before extracting the JDK and launcher archives; this is not a claim of independent cryptographic signature verification.[16][17]

For a fresh machine:

1. Restore the project text files, with their license terms.
2. Fetch the two exact ZIPs identified in `environment.lock.json`; verify their SHA-256 before extracting into the recorded `.tools/jdk` and `.tools/cli` locations.
3. Clone the upstream repository into `vendor/evolving-portfolio-dsl` and detach at the exact recorded commit. Do not use an unpinned moving branch as a replacement.
4. Run the root wrapper's `-Sdescribe`; the launcher retrieves its matching Clojure tools if absent.
5. Run the two language-version test commands. Root Maven repositories require checksum validation for release artifacts.
6. Compare the resolved dependency hashes with the lock record and investigate any difference.

This restoration checklist is documentation, not an already tested clean-machine rebuild. The current local installation and both launchers have been exercised.

## Editor and optional tools

An editor is useful but not needed to reproduce the command-line baseline. No editor installation or extension settings were changed. Select editor integration separately; do not make it a scientific dependency. Likewise, Babashka, Docker, WSL, GPU libraries, plotting packages and commercial solvers are not installed or required by this baseline.

The workspace does not depend on an `AGENTS.md` file.

## Sources

[5] https://clojure.org/guides/install_clojure — Clojure: Install Clojure
[6] https://clojure.org/releases/downloads — Clojure: Downloads and Compatibility
[7] https://clojure.org/reference/deps_edn — Clojure: deps.edn Reference
[8] https://github.com/npenchev88/evolving-portfolio-dsl — npenchev88/evolving-portfolio-dsl, pinned commit 29252c5b86d9f8a15099d85893e5e97861cb705d
[11] https://github.com/casselc/clj-msi — clj-msi: Clojure Windows Installer
[12] https://clojure.org/reference/clojure_cli — Clojure CLI Reference
[13] https://github.com/borkdude/deps.clj — deps.clj: Clojure CLI-compatible launcher
[16] https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse — Eclipse Adoptium: Temurin 25 Windows x64 JDK metadata
[17] https://github.com/borkdude/deps.clj/releases/tag/v1.12.6.1673 — deps.clj release v1.12.6.1673
