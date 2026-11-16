"""Verify existing logs/artifacts and write the scoped shape-fix report; runs no Clojure tests."""
from pathlib import Path
import difflib
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs/evidence"
PREFIX = "g3-g4-shape-fix-"

def sha(data):
    return hashlib.sha256(data).hexdigest()

def relative(path):
    return path.relative_to(ROOT).as_posix()

before = {}
for line in (EVIDENCE / (PREFIX + "before.sha256")).read_text().splitlines():
    digest, name = line.split(" ", 1)
    before[name.strip().lstrip("*")] = digest

# Reverse ONLY the recorded guards in memory, then require exact pre-edit hashes.
# This proves the diff is exhaustive even though the repository sources are untracked.
dsl_replacements = [
    ('(every? #(and (keyword? %) (contains? hard %)) (keys limits))',
     '(every? (set (keys hard)) (keys limits))'),
    ('(= 3 (count program)) (keyword? (first program)) (= :program (first program))',
     '(= 3 (count program)) (= :program (first program))'),
    ('(and (keyword? domain) (#{:portfolio :scheduling} domain))',
     '(#{:portfolio :scheduling} domain)'),
    ('(= 2 (count wrapper)) (keyword? (first wrapper)) (= :budget (first wrapper))',
     '(= 2 (count wrapper)) (= :budget (first wrapper))'),
    ('op (first node)\n                         _ (ensure! (keyword? op) "Expected keyword form tag" {:path path})\n                         [role arity children intrinsic] (get forms op)',
     'op (first node) [role arity children intrinsic] (get forms op)'),
    ('(and (keyword? x) (#{:random :first} x))', '(#{:random :first} x)'),
    ('(and (keyword? x) ((if (= domain :portfolio) #{:swap :restart} #{:swap :restart :insert :reverse}) x))',
     '((if (= domain :portfolio) #{:swap :restart} #{:swap :restart :insert :reverse}) x)'),
    ('(and (keyword? x) (#{:objective-0 :objective-1 :progress} x))',
     '(#{:objective-0 :objective-1 :progress} x)'),
    ('(and (keyword? x) (#{:archive-changed :budget-left :coin} x))',
     '(#{:archive-changed :budget-left :coin} x)'),
]
variation_replacements = [
    ('(every? #(and (keyword? %) (#{:seed :limits :max-attempts} %)) (keys options))',
     '(every? #{:seed :limits :max-attempts} (keys options))'),
]
source_records = []
diffs = []
serializer = None
for name, replacements in [("src/niki/optimizer/dsl.clj", dsl_replacements),
                           ("src/niki/optimizer/variation.clj", variation_replacements)]:
    current = (ROOT / name).read_bytes()
    original = current
    for new, old in replacements:
        new, old = new.encode(), old.encode()
        assert original.count(new) == 1, (name, new)
        original = original.replace(new, old, 1)
    assert sha(original) == before[name], ("preimage mismatch", name)
    source_records.append({"path": name, "before_sha256": before[name],
                           "after_sha256": sha(current), "exact_preimage_verified": True})
    diffs.extend(difflib.unified_diff(original.decode().splitlines(True),
                                    current.decode().splitlines(True),
                                    fromfile="before/" + name, tofile="after/" + name))
    if name.endswith("/dsl.clj"):
        marker = b"(defn canonical-edn"
        old_tail, new_tail = original[original.index(marker):], current[current.index(marker):]
        assert old_tail == new_tail
        serializer = {"region": "dsl.clj from (defn canonical-edn through EOF (includes program-id)",
                      "unchanged": True, "sha256": sha(new_tail)}
(EVIDENCE / (PREFIX + "changes.diff")).write_text("".join(diffs), encoding="utf-8")
protected = []
for name, digest in before.items():
    if name.startswith("src/"):
        continue
    now = sha((ROOT / name).read_bytes())
    assert now == digest, ("protected file changed", name)
    protected.append({"path": name, "sha256": now, "unchanged": True})

versions = ["1.12.6", "1.12.5"]
phases = []
for phase in ["slice1-red", "slice1-green", "slice2-red", "slice2-green-attempt1",
              "slice2-green", "shape-green", "g4", "runtime"]:
    for version in versions:
        path = EVIDENCE / f"{PREFIX}{phase}-{version}.log"
        text = path.read_text()
        match = re.search(r"Ran (\d+) tests containing (\d+) assertions\.\s*(\d+) failures, (\d+) errors\.", text)
        assert match, relative(path)
        tests, assertions, failures, errors = map(int, match.groups())
        is_expected_red = phase in {"slice1-red", "slice2-red", "slice2-green-attempt1"}
        assert errors == 0
        assert (failures > 0) if is_expected_red else (failures == 0)
        alias = "" if version == "1.12.6" else ":legacy-version"
        if phase == "g4":
            command = f"bash ./clojure -M{alias}:g4-test"
        elif phase == "runtime":
            command = f"bash ./clojure -M{alias} docs/evidence/g3-runtime-native-test.clj"
        else:
            selection = " slice1" if phase.startswith("slice1") else " slice2" if phase.startswith("slice2") else ""
            command = "bash ./clojure -Sdeps '{:paths [\"src\" \"resources\" \"test\"]}' " + f"-M{alias} -m niki.optimizer.shape-validation-test{selection}"
        phases.append({"phase": phase, "clojure": version, "command": command,
                       "stdout_stderr_log": relative(path), "exit_code_observed": 1 if is_expected_red else 0,
                       "tests": tests, "assertions": assertions, "failures": failures,
                       "errors": errors, "expected_result": "failure reproduces blocker" if is_expected_red else "pass"})

snapshots = []
all_snapshot_bytes = []
for version in versions:
    old = EVIDENCE / f"{PREFIX}seeded-before-{version}.edn"
    new = EVIDENCE / f"{PREFIX}seeded-after-{version}.edn"
    old_data, new_data = old.read_bytes(), new.read_bytes()
    assert old_data == new_data, ("valid output changed", version)
    for when in ["before", "after"]:
        assert not (EVIDENCE / f"{PREFIX}seeded-{when}-{version}.err").read_bytes()
    lines = len(new_data.splitlines())
    assert lines == 216
    all_snapshot_bytes.append(new_data)
    alias = "" if version == "1.12.6" else ":legacy-version"
    snapshots.append({"clojure": version, "byte_identical_before_after": True,
                      "sha256": sha(new_data), "bytes": len(new_data), "records": lines,
                      "seeded_records": 200, "explicit_grammar_records": lines - 200,
                      "before_path": relative(old), "after_path": relative(new),
                      "command": f"bash ./clojure -M{alias} docs/evidence/g3-g4-shape-fix-seeded-snapshot.clj"})
assert all_snapshot_bytes[0] == all_snapshot_bytes[1], "cross-version snapshot differs"
for version in versions:
    assert (EVIDENCE / f"{PREFIX}exits-{version}.log").read_text().strip() == f"{version} shape=0 g4=0 runtime=0 seeded=0"

report = {
    "status": "two_confirmed_shape_blockers_fixed_and_scoped_checks_passed",
    "review_input": "docs/evidence/g4-code-review-01.json",
    "production_sources": source_records,
    "new_test": {"path": "test/niki/optimizer/shape_validation_test.clj",
                 "sha256": sha((ROOT / "test/niki/optimizer/shape_validation_test.clj").read_bytes()),
                 "standalone": True, "imports_dsl_test": False,
                 "fixture_safety": "Fresh bounded lazy-seq sets atom then throws immediately; fixtures use vector identity labels and array-map, never hash poison. Assertions report class/labels only.",
                 "rejection_contract": "ExceptionInfo and atom remains false for every case",
                 "domains": ["portfolio", "scheduling"],
                 "slice1": "Option keys and nested limit keys across generate/mutate/crossover, plus direct checker/effective-limits and root/nonmap options/limits",
                 "slice2": "Domain and all six role form-tag positions, initializer/operator/feature/predicate payloads through check-program/mutate/both crossover parents; program/budget tags, root/wrapper/body and numeric-payload poison; generation domain"},
    "tdd_order": ["slice1 RED both versions", "slice1 minimal guards", "slice1 GREEN both versions",
                  "slice2 tests added", "slice2 RED both versions", "slice2 domain/form/payload guards",
                  "slice2 first GREEN attempt exposed remaining program/budget tag realization (32 failures each)",
                  "program/budget keyword guards added", "slice2 GREEN both versions",
                  "full standalone shape, standalone G4, standalone runtime and valid-output snapshots both versions"],
    "runs": phases,
    "valid_behavior_equivalence": {"snapshots": snapshots, "cross_version_byte_identical": True,
                                   "corpus": "Seeds 0..99 for each domain, three envelopes, generated programs/full metadata, checked structure/sites/effects, canonical EDN, IDs, mutation and crossover; explicit corpus covers all grammar tags and keyword payloads including scheduling insert/reverse"},
    "serialization_source": serializer,
    "protected_file_checks": protected,
    "changes": ["Keyword shape before variation option membership",
                "Keyword shape before DSL limit membership (contains? on existing hard-limit map)",
                "Keyword shape before domain set lookup, AST form lookup and four keyword-payload set lookups",
                "Keyword shape before program/budget tag equality, required by bounded root-tag poison regressions"],
    "limitations": ["dsl_test.clj was never executed or imported; its unrelated serializer regression remains pending user approval and unchanged",
                    "Aggregate :g3-test was never run; no claim of aggregate G3/serializer gate completion",
                    "No infinite lazy probes were run; only bounded set-atom-then-throw poison",
                    "Finite valid corpus establishes exact equivalence for exercised inputs, not exhaustive generation-distribution equivalence",
                    "Custom hostile map implementations and arbitrary non-keyword API positions are outside this narrow fix",
                    "No dependencies, runners, other docs, vendor files, serialization implementation, stage/commit or project task closure were changed"],
    "issues_encountered": ["First slice2 GREEN attempt retained program/budget tag realization through equality; retained failed logs and added only keyword guards at these keyword-bearing positions",
                           "Initial artifact checksum manifest used Windows CRLF, which Git Bash sha256sum -c treated as filename characters; regenerated the manifest as LF bytes and rechecked"],
    "diff_path": "docs/evidence/" + PREFIX + "changes.diff",
}
report_path = EVIDENCE / (PREFIX + "report.json")
report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
artifacts = []
for path in sorted(EVIDENCE.glob(PREFIX + "*")):
    if path.is_file() and path.name != PREFIX + "artifacts.sha256":
        artifacts.append(f"{sha(path.read_bytes())}  {relative(path)}\n")
(EVIDENCE / (PREFIX + "artifacts.sha256")).write_bytes("".join(artifacts).encode("utf-8"))
print(json.dumps({"report": relative(report_path), "runs": len(phases),
                  "verified_source_preimages": source_records,
                  "protected_files_unchanged": len(protected),
                  "snapshot_sha256": snapshots[0]["sha256"],
                  "snapshot_records_per_version": snapshots[0]["records"],
                  "cross_version_byte_identical": True,
                  "artifact_hash_entries": len(artifacts)}, indent=2))
