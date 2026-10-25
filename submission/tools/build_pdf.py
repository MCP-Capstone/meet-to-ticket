"""마크다운을 PDF로 바꾼다 (마크다운 -> HTML -> Edge 또는 Chrome의 헤드리스 인쇄).

사용법
    pip install markdown
    python submission/tools/build_pdf.py submission/01-design/design.md out/설계서.pdf
    python submission/tools/build_pdf.py submission/04-slides/slides.md out/발표.pdf --slides

--slides는 가로 페이지로 만들고, 첫 번째 "---" 줄 앞까지의 "## 슬라이드 N" 구간을 한 장씩 만든다.
브라우저를 직접 지정하려면 환경 변수 BROWSER에 실행 파일 경로를 넣는다.
"""

from __future__ import annotations

import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import markdown
except ImportError:
    sys.exit("markdown 패키지가 필요하다: pip install markdown")

BROWSER_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "google-chrome",
    "chromium",
    "microsoft-edge",
]

CSS_COMMON = """
* { box-sizing: border-box; }
body { font-family: 'Malgun Gothic','Apple SD Gothic Neo','Noto Sans KR',sans-serif; color: #1f2937; line-height: 1.55; word-break: keep-all; overflow-wrap: break-word; }
h1 { font-size: 22pt; margin: 0 0 8pt; }
h2 { font-size: 15pt; margin: 18pt 0 6pt; border-bottom: 1.5pt solid #2b5fd9; padding-bottom: 3pt; }
h3 { font-size: 12pt; margin: 12pt 0 4pt; }
table { border-collapse: collapse; width: 100%; margin: 6pt 0 10pt; font-size: 9.5pt; }
th, td { border: 0.75pt solid #9aa5b8; padding: 4pt 6pt; vertical-align: top; text-align: left; }
th { background: #eef2fa; }
th:first-child { white-space: nowrap; }
code { font-family: Consolas,'D2Coding',monospace; font-size: 0.92em; background: #f3f4f6; padding: 0 2pt; word-break: break-all; }
pre { background: #f3f4f6; padding: 8pt; font-size: 8.5pt; white-space: pre-wrap; word-break: break-all; }
pre code { background: none; padding: 0; }
img { max-width: 100%; }
ul, ol { margin: 4pt 0 8pt 16pt; padding: 0; }
tr, img, pre { page-break-inside: avoid; }
"""

CSS_DOC = "@page { size: A4; margin: 16mm 14mm; } body { font-size: 10pt; }"

CSS_SLIDES = """
@page { size: A4 landscape; margin: 0; }
body { font-size: 15pt; }
section.slide { width: 297mm; height: 209mm; padding: 16mm 18mm; page-break-after: always; overflow: hidden; }
section.slide:last-child { page-break-after: auto; }
section.slide h2 { font-size: 24pt; border-bottom: 2pt solid #2b5fd9; margin-top: 0; }
section.slide li { margin-bottom: 5pt; }
section.slide table { font-size: 12pt; }
section.slide img { max-height: 95mm; display: block; margin: 4pt auto 8pt; }
"""


def find_browser() -> str:
    env = os.environ.get("BROWSER")
    if env:
        return env
    for cand in BROWSER_CANDIDATES:
        if os.path.isabs(cand):
            if os.path.exists(cand):
                return cand
        elif shutil.which(cand):
            return cand
    sys.exit("Edge 또는 Chrome을 찾지 못했다. 환경 변수 BROWSER에 실행 파일 경로를 넣는다")


def render(md_text: str) -> str:
    return markdown.markdown(md_text, extensions=["tables", "fenced_code", "sane_lists"])


def slides_html(md_text: str) -> str:
    head = md_text.split("\n---\n", 1)[0]
    parts = re.split(r"^## (?=슬라이드 )", head, flags=re.M)[1:]
    sections = [f'<section class="slide">{render("## " + p)}</section>' for p in parts]
    if not sections:
        sys.exit("'## 슬라이드 N' 제목을 찾지 못했다")
    return "\n".join(sections)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path)
    ap.add_argument("output", type=Path)
    ap.add_argument("--slides", action="store_true", help="슬라이드 모드(가로, 한 구간이 한 장)")
    args = ap.parse_args()

    md_text = args.source.read_text(encoding="utf-8")
    body = slides_html(md_text) if args.slides else render(md_text)
    base = args.source.resolve().parent.as_uri() + "/"
    page = (
        "<!doctype html><html lang='ko'><head><meta charset='utf-8'>"
        f"<base href='{html.escape(base)}'>"
        f"<style>{CSS_COMMON}{CSS_SLIDES if args.slides else CSS_DOC}</style></head><body>{body}</body></html>"
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    out_abs = args.output.resolve()
    with tempfile.TemporaryDirectory() as tmp:
        page_path = Path(tmp) / "page.html"
        page_path.write_text(page, encoding="utf-8")
        cmd = [
            find_browser(), "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
            f"--user-data-dir={Path(tmp) / 'profile'}",
            f"--print-to-pdf={out_abs}", page_path.as_uri(),
        ]
        subprocess.run(cmd, check=True, timeout=180, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not out_abs.exists() or out_abs.stat().st_size == 0:
        sys.exit("PDF가 만들어지지 않았다")
    print(f"{out_abs} ({out_abs.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
