"""Build and verify a local review-only snapshot. Does not run optimization."""
from __future__ import annotations

import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "review"
ARCHIVE = OUT / "G1_EXTERNAL_REVIEW_2026-11-07.zip"
MANIFEST = OUT / "G1_REVIEW_MANIFEST.json"
FILE_MAP = OUT / "FILE_MAP.csv"
VERIFICATION = OUT / "G1_EXTERNAL_REVIEW_2026-11-07.verification.json"
PIN = "29252c5b86d9f8a15099d85893e5e97861cb705d"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    for target in (ARCHIVE, MANIFEST, FILE_MAP, VERIFICATION):
        if target.exists():
            raise FileExistsError(f"Refusing to replace an existing review artifact: {target}")
    prior = json.loads((ROOT / "docs/evidence/g1-deliverables.json").read_text(encoding="utf-8"))
    for item in prior["files"]:
        assert sha256((ROOT / item["path"]).read_bytes()) == item["sha256"], item["path"]
    vendor = ROOT / "vendor/evolving-portfolio-dsl"
    assert subprocess.check_output(["git", "-C", str(vendor), "rev-parse", "HEAD"], text=True).strip() == PIN
    assert not subprocess.check_output(["git", "-C", str(vendor), "status", "--porcelain"], text=True).strip()
    tracked = [p for p in subprocess.check_output(["git", "-C", str(vendor), "ls-files", "-z"]).decode().split("\0") if p]
    names = {
        ".gitignore", "README.md", "deps.edn", "environment.lock.json", "clojure", "clojure.cmd",
        "docs/EXTERNAL_REVIEW_BRIEF.md", "docs/G1_STATUS.md", "docs/FINAL_EXPERIMENT_PLAN.md",
        "docs/DECISIONS.md", "docs/REVIEW_RESOLUTION.md", "docs/ENVIRONMENT.md",
        "docs/NIKI_EXPERIMENT_PLAN.pdf", "docs/NIKI_EXPERIMENT_PLAN.html", "docs/NIKI_EXPERIMENT_DOSSIER.md",
        "docs/EXPERIMENT_OVERVIEW.html", "docs/EXPERIMENT_OVERVIEW.png", "docs/EXPERIMENT_OVERVIEW.svg",
        "docs/input/typed_clojure_portfolio_plan_bg.pdf", "docs/input/optimizer_methodology_extension_bg.pdf",
        "docs/input/mccarthy-recursive.pdf", "scripts/verify-g1.clj", "scripts/validate-protocol.clj",
        "docs/evidence/verification.txt", "docs/evidence/verified-upstream-demo.txt",
        "docs/evidence/sources.json", "docs/evidence/input-manifest.json",
        "docs/evidence/live-source-checks.json", "docs/evidence/toolchain-downloads.json",
        "review/START_HERE.md", "review/REVIEW_PROMPT.txt", "review/build_review_package.py",
    }
    for folder in ("src", "test", "resources/fixtures", "configs"):
        names.update(p.relative_to(ROOT).as_posix() for p in (ROOT / folder).rglob("*") if p.is_file())
    names.update(p.relative_to(ROOT).as_posix() for p in (ROOT / "docs/evidence").glob("g1-*") if p.is_file())
    names.update("vendor/evolving-portfolio-dsl/" + p for p in tracked)
    assert "docs/EXPERIMENT_OVERVIEW_CONTINUE.md" not in names
    secret_patterns = [
        re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
        re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
        re.compile(rb"\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{36,}\b"),
    ]
    entries = []
    for name in sorted(names):
        path = ROOT / name
        assert path.resolve().is_relative_to(ROOT), name
        assert path.is_file(), name
        assert not any(part in {".git", ".tools", ".cache", ".config", ".cpcache", "__pycache__"} for part in Path(name).parts), name
        data = path.read_bytes()
        if any(pattern.search(data) for pattern in secret_patterns):
            raise RuntimeError(f"Potential secret detected; inspect before packaging: {name}")
        entries.append({"archive_path": name, "original_path": str(path), "bytes": len(data), "sha256": sha256(data)})
    csv_stream = io.StringIO(newline="")
    writer = csv.DictWriter(csv_stream, fieldnames=["archive_path", "original_path", "bytes", "sha256"])
    writer.writeheader()
    writer.writerows(entries)
    FILE_MAP.write_text(csv_stream.getvalue(), encoding="utf-8")
    map_data = FILE_MAP.read_bytes()
    entries.append({"archive_path": FILE_MAP.relative_to(ROOT).as_posix(), "original_path": str(FILE_MAP),
                    "bytes": len(map_data), "sha256": sha256(map_data)})
    payload = {
        "prepared_at": datetime.now(timezone.utc).isoformat(), "project_root": str(ROOT),
        "scope": "Independent design/G1 review only; G2 paused; no optimizer experiment launched",
        "upstream_commit": PIN, "upstream_tracked_files": len(tracked),
        "accepted_g1_snapshot_verified": True, "credential_pattern_matches": 0,
        "omitted": ["toolchain binaries", "caches", ".git", "withdrawn continuation", "unavailable original research-article PDFs"],
        "content_file_count": len(entries), "entries": entries,
        "manifest_note": "This manifest is an additional archive member; it does not include its own hash.",
    }
    MANIFEST.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    scratch = Path(os.environ.get("HERMES_HOME", str(Path.home() / "AppData/Local/hermes"))) / "cache/scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    temp_zip = scratch / ("niki-g1-review-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".zip")
    try:
        with zipfile.ZipFile(temp_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for item in entries:
                archive.write(ROOT / item["archive_path"], arcname=item["archive_path"])
            archive.write(MANIFEST, arcname=MANIFEST.relative_to(ROOT).as_posix())
        with zipfile.ZipFile(temp_zip) as archive:
            assert archive.testzip() is None
            members = archive.namelist()
            assert len(members) == len(set(members)) == len(entries) + 1
            archived_manifest = json.loads(archive.read(MANIFEST.relative_to(ROOT).as_posix()))
            assert archived_manifest == payload
            for item in archived_manifest["entries"]:
                assert sha256(archive.read(item["archive_path"])) == item["sha256"], item["archive_path"]
                assert sha256((ROOT / item["archive_path"]).read_bytes()) == item["sha256"], item["archive_path"]
        temp_zip.replace(ARCHIVE)
    finally:
        if temp_zip.exists():
            temp_zip.unlink()
    report = {
        "status": "passed", "archive": str(ARCHIVE), "archive_bytes": ARCHIVE.stat().st_size,
        "archive_sha256": sha256(ARCHIVE.read_bytes()), "archive_members": len(entries) + 1,
        "all_member_hashes_verified": True, "crc_verified": True,
        "accepted_g1_snapshot_unchanged": True, "upstream_commit": PIN,
        "g2_started": False, "external_upload_performed": False,
    }
    VERIFICATION.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
