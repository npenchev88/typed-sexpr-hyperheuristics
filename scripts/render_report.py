"""Build a local research dossier from the reviewed Markdown sources.

Requires Python's Markdown package for document rendering only.
Chrome runs with a new scratch profile; no personal browser profile is read.
This script does not execute or authorize any optimization experiment.
"""
from __future__ import annotations

import argparse
import html
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import markdown

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"


def without_sources(text: str) -> str:
    return re.split(r"(?m)^## Sources\s*$", text, maxsplit=1)[0].rstrip()


def appendix(text: str, heading: str) -> str:
    lines = without_sources(text).splitlines()[1:]
    body = "\n".join(lines).lstrip()
    body = re.sub(r"(?m)^(#{1,5}) ", r"#\1 ", body)
    return "\n\n---\n\n## " + heading + "\n\n" + body


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chrome", required=True)
    parser.add_argument("--citation-script", required=True)
    args = parser.parse_args()

    dossier = without_sources((DOCS / "FINAL_EXPERIMENT_PLAN.md").read_text(encoding="utf-8"))
    dossier += appendix((DOCS / "ENVIRONMENT.md").read_text(encoding="utf-8"),
                        "Appendix A · Environment and reproduction")
    dossier += appendix((DOCS / "REVIEW_RESOLUTION.md").read_text(encoding="utf-8"),
                        "Appendix B · Review decisions")
    md_path = DOCS / "NIKI_EXPERIMENT_DOSSIER.md"
    md_path.write_text(dossier + "\n", encoding="utf-8")
    citation = [sys.executable, args.citation_script, "--ledger",
                str(DOCS / "evidence" / "sources.json")]
    subprocess.run(citation + ["render", "--replace-in", str(md_path)], check=True)
    subprocess.run(citation + ["verify", str(md_path), "--strict"], check=True)
    dossier = md_path.read_text(encoding="utf-8")
    # Bibliography entries must remain separate paragraphs in Markdown.
    render_text = re.sub(r"(?m)^(\[\d+\] .+)$", r"\n\1\n", dossier)
    converter = markdown.Markdown(extensions=["tables", "fenced_code", "toc"],
                                  extension_configs={"toc": {"toc_depth": "2-2"}})
    article = converter.convert(render_text)
    css = """
:root { color-scheme: light; --ink:#24212a; --muted:#625b6a; --accent:#68418b; --rule:#d6cfdc; }
* { box-sizing:border-box; }
body { margin:0; padding:34px 38px; color:var(--ink); background:#fff;
       font:12pt/1.46 Georgia, 'Times New Roman', serif; }
main { max-width:188mm; margin:0; }
.eyebrow { color:var(--accent); font:600 12pt/1.3 'Segoe UI',sans-serif;
            letter-spacing:.08em; margin:0 0 17px; }
h1,h2,h3 { font-family:'Segoe UI',Arial,sans-serif; line-height:1.22; text-wrap:balance;
            break-after:avoid; page-break-after:avoid; }
h1 { font-size:29pt; letter-spacing:-.025em; margin:0 0 15px; }
h2 { font-size:19pt; color:var(--accent); margin:27px 0 12px; padding-top:7px; }
h3 { font-size:14pt; margin:23px 0 9px; }
p,li { orphans:3; widows:3; }
p { margin:9px 0 13px; }
li { margin:5px 0; }
blockquote { margin:20px 0; padding:16px 0; border-top:1px solid var(--rule);
             border-bottom:1px solid var(--rule); font-size:13pt; }
blockquote p { margin:0; }
table { border-collapse:collapse; width:100%; table-layout:fixed; margin:15px 0 22px;
        font-size:12pt; line-height:1.32; }
thead { display:table-header-group; }
tr { break-inside:avoid; page-break-inside:avoid; }
th { font-family:'Segoe UI',Arial,sans-serif; font-weight:650; text-align:left;
     background:#f1edf5; border-bottom:1px solid #95869f; }
th,td { padding:9px 8px; border-bottom:1px solid var(--rule); vertical-align:top;
        overflow-wrap:anywhere; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; padding:12px; background:#f6f4f8;
      border:1px solid var(--rule); font:12pt/1.4 Consolas,'Courier New',monospace;
      break-inside:avoid; }
code { font:12pt Consolas,'Courier New',monospace; overflow-wrap:anywhere; }
a { color:var(--accent); text-decoration:underline; overflow-wrap:anywhere; }
hr { border:0; border-top:1px solid var(--rule); margin:28px 0; }
nav { border-top:1px solid var(--rule); border-bottom:1px solid var(--rule);
      padding:12px 0; margin:25px 0; font:12pt/1.4 'Segoe UI',Arial,sans-serif; }
nav ul { margin:5px 0; padding-left:21px; columns:2; column-gap:24px; }
nav li { break-inside:avoid; margin:5px 0; }
nav a { text-decoration:none; }
@page { size:A4; margin:16mm 14mm 18mm;
        @bottom-left { content:'Niki · Typed Optimizer Lab · Protocol v1.1';
          font:10pt 'Segoe UI',Arial,sans-serif; color:#625b6a; }
        @bottom-right { content:counter(page); font:10pt 'Segoe UI',Arial,sans-serif; color:#625b6a; } }
@media print { body { padding:0; } main { max-width:none; } nav { break-inside:avoid; }
              h2 { margin-top:22px; } a { color:inherit; } }
@media (max-width:700px) { body { padding:20px; } h1 { font-size:25pt; } nav ul { columns:1; } }
"""
    html_path = DOCS / "NIKI_EXPERIMENT_PLAN.html"
    # Put navigation after the first recommendation paragraph, not before the recommendation.
    # Select actual sections, omitting the subtitle from the contents list.
    headings = re.findall(r'<h2 id="([^"]+)">(.*?)</h2>', article)
    links = []
    for anchor, label in headings:
        label = html.unescape(re.sub(r"<[^>]+>", "", label))
        if re.match(r"\d+\.", label) or label.startswith("Appendix ") or label == "Sources":
            links.append('<li><a href="#' + html.escape(anchor, quote=True) + '">' +
                         html.escape(label) + '</a></li>')
    nav = '<nav aria-label="Contents"><strong>Contents</strong><ul>' + ''.join(links) + '</ul></nav>'
    insert_at = article.find("<h2 id=\"1-")
    if insert_at >= 0:
        article = article[:insert_at] + nav + article[insert_at:]
    else:
        article += nav
    page = ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            "<title>Niki · Typed Evolution of Executable Optimizers</title><style>" + css +
            "</style></head><body><main><div class=\"eyebrow\">RESEARCH PLAN · 02 NOVEMBER 2026</div>" +
            article + "</main></body></html>")
    html_path.write_text(page, encoding="utf-8")
    pdf_path = DOCS / "NIKI_EXPERIMENT_PLAN.pdf"
    png_path = DOCS / "evidence" / "report-preview.png"
    scratch = Path(os.environ.get("HERMES_HOME", str(Path.home() / "AppData" / "Local" / "hermes"))) / "cache" / "scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="niki-report-chrome-", dir=scratch)
    command = [args.chrome, "--headless=new", "--no-pdf-header-footer",
               "--user-data-dir=" + profile, "--window-size=1440,1100",
               "--print-to-pdf=" + str(pdf_path), "--screenshot=" + str(png_path),
               html_path.as_uri()]
    rendered = subprocess.run(command, capture_output=True, text=True, timeout=90)
    if rendered.returncode:
        raise RuntimeError(rendered.stderr[-5000:])
    assert pdf_path.read_bytes().startswith(b"%PDF-"), "No PDF generated"
    assert png_path.read_bytes().startswith(b"\x89PNG"), "No preview generated"
    print(json.dumps({"markdown":str(md_path), "html":str(html_path),
                      "pdf":str(pdf_path), "pdf_bytes":pdf_path.stat().st_size,
                      "preview":str(png_path), "isolated_browser_profile":profile}, indent=2))


if __name__ == "__main__":
    main()
