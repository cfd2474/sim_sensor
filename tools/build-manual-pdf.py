"""Builds docs/USER_MANUAL.pdf from docs/USER_MANUAL.md: US Letter, a cover, a contents page with
page numbers, one chapter per page run, page numbers in the footer.

Printed with Microsoft Edge (or Chrome) headless: set EDGE to its path if it isn't in the usual
place. Renders twice: the first pass finds the page each chapter starts on (pypdf), the second
puts those numbers in the contents. Needs the Python packages markdown and pypdf.

Usage: python tools/build-manual-pdf.py [output.pdf]
"""
import html
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import markdown
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
SOURCE = DOCS / "USER_MANUAL.md"
REPO_URL = "https://github.com/cfd2474/sim_sensor/blob/main/"
BROWSERS = [
    os.environ.get("EDGE", ""),
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "/usr/bin/microsoft-edge", "/usr/bin/google-chrome", "/usr/bin/chromium",
]

CSS = """
@page { size: Letter; margin: 0.7in 0.75in 0.8in 0.75in;
  @bottom-left { content: "Sim Sensor user manual · v%(version)s"; font: 9pt 'Segoe UI', sans-serif; color: #6b7684; }
  @bottom-right { content: counter(page); font: 9pt 'Segoe UI', sans-serif; color: #6b7684; } }
@page cover { margin: 0; @bottom-left { content: none; } @bottom-right { content: none; } }
html { font: 10.5pt/1.45 'Segoe UI', system-ui, sans-serif; color: #16202f; }
body { margin: 0; }
.cover { page: cover; height: 11in; position: relative; background: #021a49; color: #fff; overflow: hidden; }
.cover .band { background: #ffc107; color: #111; font-weight: 700; letter-spacing: .08em; text-align: center;
  padding: 10pt 0; font-size: 12pt; position: absolute; left: 0; right: 0; }
.cover .band.top { top: 0; } .cover .band.bottom { bottom: 0; }
.cover .inner { position: absolute; left: 0.9in; right: 0.9in; top: 2.3in; }
.cover img.icon { width: 1.5in; height: 1.5in; border-radius: 18pt; }
.cover h1 { font-size: 46pt; margin: 22pt 0 0; line-height: 1.05; color: #fff; border: 0; }
.cover .sub { font-size: 20pt; color: #1ef6dd; margin-top: 8pt; font-weight: 600; }
.cover .what { font-size: 13pt; color: #b2c4de; margin-top: 26pt; max-width: 5.4in; line-height: 1.5; }
.cover .meta { position: absolute; left: 0.9in; bottom: 1.2in; font-size: 11pt; color: #b2c4de; }
.cover .meta b { color: #fff; }
.toc { break-before: page; }
.toc h2 { break-before: avoid; }
.toc ol { list-style: none; padding: 0; margin: 14pt 0 0; font-size: 12pt; }
.toc li { display: flex; align-items: baseline; padding: 5pt 0; border-bottom: 1px dotted #c3ccd6; }
.toc li a { color: #16202f; text-decoration: none; flex: 1; }
.toc li span { color: #6b7684; }
.toc .note { margin-top: 26pt; color: #6b7684; font-size: 10pt; }
h1 { font-size: 22pt; }
h2 { font-size: 20pt; margin: 0 0 12pt; padding-bottom: 6pt; border-bottom: 3px solid #021a49; color: #021a49; break-before: page; }
h3 { font-size: 13.5pt; margin: 18pt 0 6pt; color: #021a49; break-after: avoid; }
p, li { orphans: 3; widows: 3; }
a { color: #1565c0; text-decoration: none; }
code { font-family: Consolas, monospace; font-size: 9.5pt; background: #eef2f6; padding: 0 3pt; border-radius: 3pt; }
pre { background: #eef2f6; padding: 8pt 10pt; border-radius: 6pt; break-inside: avoid; white-space: pre-wrap; }
pre code { background: none; padding: 0; }
table { border-collapse: collapse; width: 100%%; margin: 8pt 0 12pt; font-size: 9.5pt; break-inside: auto; }
tr { break-inside: avoid; }
th { background: #eef2f6; text-align: left; }
th, td { border: 1px solid #d3dae2; padding: 4pt 6pt; vertical-align: top; }
blockquote { margin: 10pt 0; padding: 8pt 12pt; background: #fff8e1; border-left: 5px solid #ffc107; }
blockquote p { margin: 0; }
figure { margin: 10pt 0 14pt; text-align: center; break-inside: avoid; }
figure img { max-width: 100%%; max-height: 7.6in; border: 1px solid #d3dae2; border-radius: 4pt; }
figure.phones img { width: 1.85in; margin: 0 6pt; border-radius: 8pt; }
figure.small img { max-width: 4.6in; }
figcaption { font-size: 9pt; color: #5b6676; margin-top: 4pt; font-style: italic; }
h2 + figure, h3 + figure { break-before: avoid; }
p:has(> strong:only-child), p:has(> strong:first-child:last-child) { break-after: avoid; }
p:has(+ table), p:has(+ figure) { break-after: avoid; }
"""


def find_browser():
    for b in BROWSERS:
        if b and Path(b).is_file():
            return b
    sys.exit("No Edge or Chrome found: set EDGE to the browser's path")


def chapters(md):
    """The numbered top-level sections, as (id, title)."""
    out = []
    for m in re.finditer(r"^## (\d+\. .+)$", md, re.M):
        title = m.group(1).strip()
        slug = re.sub(r"[^\w\- ]", "", title.lower()).strip().replace(" ", "-")
        out.append((slug, title))
    return out


def body_html(md):
    # The PDF gets its own contents page and cover; drop the Markdown's.
    md = re.sub(r"^## Contents\n.*?\n---\n", "", md, flags=re.S | re.M)
    md = re.sub(r"^A printable copy.*\n", "", md, flags=re.M)
    md = re.sub(r"^# Sim Sensor user manual\n", "", md, flags=re.M)
    md = re.sub(r"^---\n", "", md, flags=re.M)
    h = markdown.markdown(md, extensions=["tables", "fenced_code", "toc", "sane_lists"])
    # Figures: an image paragraph with its caption.
    h = re.sub(r'<p><img alt="([^"]*)" src="([^"]+)" ?/?>\s*<em>(.*?)</em></p>',
               lambda m: f'<figure><img alt="{m.group(1)}" src="{m.group(2)}"><figcaption>{m.group(3)}</figcaption></figure>', h)
    h = re.sub(r'<p align="center">(.*?)</p>\s*<p><em>(.*?)</em></p>',
               lambda m: '<figure class="{}">{}<figcaption>{}</figcaption></figure>'.format(
                   "phones" if m.group(1).count("<img") > 1 or "app-" in m.group(1) else "small", m.group(1), m.group(2)),
               h, flags=re.S)
    h = re.sub(r' width="\d+"', "", h)
    # Links to other repository files go to GitHub; links inside the manual stay as they are.
    h = re.sub(r'href="(?!https?:|#|mailto:)([^"#]+)(#[^"]*)?"',
               lambda m: f'href="{REPO_URL}{os.path.normpath("docs/" + m.group(1)).replace(os.sep, "/")}{m.group(2) or ""}"', h)
    return h


def page(md, version, pages):
    icon = (DOCS / "brand/play-icon-512.png").as_uri()
    toc = "".join(
        f'<li><a href="#{cid}">{html.escape(title)}</a><span>{pages.get(cid, "")}</span></li>' for cid, title in chapters(md))
    return f"""<!doctype html><html><head><meta charset="utf-8"><base href="{DOCS.as_uri()}/">
<title>Sim Sensor user manual</title><style>{CSS % {"version": version}}</style></head><body>
<section class="cover"><div class="band top">SIMULATOR — TRAINING ONLY</div>
<div class="inner"><img class="icon" src="{icon}"><h1>Sim Sensor</h1><div class="sub">User manual</div>
<div class="what">Install the server, enroll the phones, set up monitors and beacons, build and run gas-detector
training exercises, and manage the system.</div></div>
<div class="meta"><b>Version {version}</b><br>github.com/cfd2474/sim_sensor</div>
<div class="band bottom">SIMULATOR — TRAINING ONLY</div></section>
<section class="toc"><h2 id="contents">Contents</h2><ol>{toc}</ol>
<p class="note">Sim Sensor never measures gas. Every reading is simulated: never use it to detect real hazards.</p></section>
{body_html(md)}
</body></html>"""


def render(browser, html_text, pdf):
    pdf.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        src = Path(tmp) / "manual.html"
        src.write_text(html_text, encoding="utf-8")
        subprocess.run([browser, "--headless", "--disable-gpu", "--no-pdf-header-footer", "--allow-file-access-from-files",
                        f"--user-data-dir={Path(tmp) / 'profile'}", f"--print-to-pdf={pdf}", src.as_uri()],
                       check=True, capture_output=True, timeout=180)
        # On Windows msedge.exe hands over to another process and returns before the PDF is written.
        size, deadline = -1, time.time() + 180
        while time.time() < deadline:
            now = pdf.stat().st_size if pdf.exists() else -1
            if now > 0 and now == size:
                return
            size = now
            time.sleep(2)
        sys.exit(f"The browser didn't write {pdf}")


def chapter_pages(pdf, md):
    """Page number (as printed in the footer) of each chapter's first page."""
    texts = [(p.extract_text() or "") for p in PdfReader(pdf).pages]
    found = {}
    for cid, title in chapters(md):
        for n, text in enumerate(texts[2:], start=3):  # after the cover and the contents
            head = text.split("\n")[:3]  # the footer's text comes first
            if any(line.strip().startswith(title[:24]) for line in head):
                found[cid] = n
                break
    return found


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DOCS / "USER_MANUAL.pdf"
    md = SOURCE.read_text(encoding="utf-8")
    version = (ROOT / "VERSION").read_text().strip()
    browser = find_browser()
    render(browser, page(md, version, {}), out)
    pages = chapter_pages(out, md)
    render(browser, page(md, version, pages), out)
    missing = [t for c, t in chapters(md) if c not in pages]
    print(f"{out}: {len(PdfReader(out).pages)} pages" + (f"; no page found for {missing}" if missing else ""))


if __name__ == "__main__":
    main()
