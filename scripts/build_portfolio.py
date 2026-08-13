"""docs/portfolio/*.md 를 하나의 HTML로 합친다.

- 섹션 사이에 page break 를 넣는다.
- `![alt](diagrams/x.svg)` 를 SVG 원본 그대로 인라인 삽입한다.
  (외부 파일 참조를 남기면 headless Chrome 의 file:// 상대경로에서 깨진다)
- 파일 간 상대 링크(`02-submission-pipeline.md`)를 문서 내 앵커로 바꾼다.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import markdown
except ImportError:  # pragma: no cover
    sys.exit("markdown 패키지가 필요합니다:  pip install markdown")


PROJECT_ROOT = Path(__file__).resolve().parent.parent
PORTFOLIO_DIR = PROJECT_ROOT / "docs" / "portfolio"
DIAGRAM_DIR = PORTFOLIO_DIR / "diagrams"
PDF_DIR = PROJECT_ROOT / "docs" / "pdf"

OUTPUT_HTML = PDF_DIR / "portfolio.html"
CSS_NAME = "portfolio.css"

# 제출본과 그 근거 문서. 이전 4분할 구성은 docs/portfolio/archive/ 로 옮겼다.
SECTIONS = [
    "SUBMISSION.md",
    "MEASUREMENTS.md",
]

# 목차에 넣지 않을 섹션(표지)
TOC_SKIP: set[str] = set()


def slugify(text: str, separator: str = "-") -> str:
    """heading id 생성 규칙.

    python-markdown 의 기본 slugify 는 NFKD 정규화 후 ASCII 로 인코딩하므로
    한글 제목이 통째로 사라진다("한 줄 소개" -> "_1"). toc 확장에도 이 함수를
    주입해서 본문 heading id 와 목차 링크가 같은 규칙을 쓰게 한다.
    """
    text = re.sub(r"[^\w\s-]", "", text, flags=re.UNICODE).strip().lower()
    return re.sub(rf"[{re.escape(separator)}\s]+", separator, text)


def read_section(path: Path) -> tuple[str, str, list[str]]:
    """(h1 제목, 본문, h2 제목 목록)을 돌려준다."""
    lines = path.read_text(encoding="utf-8").strip().splitlines()

    title = path.stem
    start = 0
    if lines and lines[0].startswith("# "):
        title = lines[0][2:].strip()
        start = 1

    body = "\n".join(lines[start:]).strip()

    in_fence = False
    subtitles: list[str] = []
    for line in body.splitlines():
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        elif not in_fence and line.startswith("## "):
            subtitles.append(line[3:].strip())

    return title, body, subtitles


def rewrite_cross_links(text: str, file_to_anchor: dict[str, str]) -> str:
    """`](02-xxx.md)` -> `](#앵커)`. 문서를 합치면 파일 링크가 죽기 때문."""
    for file_name, anchor in file_to_anchor.items():
        text = text.replace(f"]({file_name})", f"](#{anchor})")
    return text


def inline_svg(html: str) -> str:
    """<img src="diagrams/x.svg"> 를 SVG 본문으로 치환하고 캡션을 붙인다."""
    pattern = re.compile(
        r'<img\s+[^>]*?src="diagrams/(?P<name>[\w.-]+\.svg)"[^>]*?/?>'
    )

    def replace(match: re.Match[str]) -> str:
        name = match.group("name")
        svg_path = DIAGRAM_DIR / name
        if not svg_path.exists():
            raise FileNotFoundError(f"다이어그램을 찾을 수 없습니다: {svg_path}")

        alt_match = re.search(r'alt="(?P<alt>[^"]*)"', match.group(0))
        alt = alt_match.group("alt") if alt_match else ""

        svg = svg_path.read_text(encoding="utf-8").strip()
        svg = re.sub(r"^<\?xml[^>]*\?>\s*", "", svg)

        caption = f"<figcaption>{alt}</figcaption>" if alt else ""
        return f'<figure class="diagram">{svg}{caption}</figure>'

    return pattern.sub(replace, html)


def render(sections: list[tuple[str, str]], toc_html: str) -> str:
    md = markdown.Markdown(
        extensions=["extra", "fenced_code", "tables", "toc", "sane_lists"],
        extension_configs={"toc": {"slugify": slugify}},
    )

    blocks: list[str] = []
    for title, body in sections:
        rendered = md.convert(body)
        md.reset()
        blocks.append(
            f'<section class="page">\n'
            f'<h1 id="{slugify(title)}">{title}</h1>\n{rendered}\n</section>'
        )

    # 표지(첫 섹션) 다음에 목차를 끼운다.
    blocks.insert(1, toc_html)
    body_html = "\n".join(blocks)

    return f"""<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <title>백엔드 개발자 포트폴리오</title>
  <link rel="stylesheet" href="{CSS_NAME}">
</head>
<body>
  <main class="doc">
{body_html}
  </main>
</body>
</html>
"""


def build_toc(entries: list[tuple[str, list[str]]]) -> str:
    parts = ['<section class="page toc">\n<h1>목차</h1>\n<ol class="toc-list">']
    for title, subtitles in entries:
        parts.append(f'<li><a href="#{slugify(title)}">{title}</a>')
        if subtitles:
            parts.append("<ul>")
            parts.extend(
                f'<li><a href="#{slugify(s)}">{s}</a></li>' for s in subtitles
            )
            parts.append("</ul>")
        parts.append("</li>")
    parts.append("</ol>\n</section>")
    return "\n".join(parts)


def main() -> None:
    PDF_DIR.mkdir(parents=True, exist_ok=True)

    file_to_anchor: dict[str, str] = {}
    loaded: list[tuple[str, str, str, list[str]]] = []

    for file_name in SECTIONS:
        path = PORTFOLIO_DIR / file_name
        if not path.exists():
            raise FileNotFoundError(f"섹션 파일이 없습니다: {path}")
        title, body, subtitles = read_section(path)
        file_to_anchor[file_name] = slugify(title)
        loaded.append((file_name, title, body, subtitles))

    sections = [
        (title, rewrite_cross_links(body, file_to_anchor))
        for _, title, body, _ in loaded
    ]
    toc_entries = [
        (title, subtitles)
        for file_name, title, _, subtitles in loaded
        if file_name not in TOC_SKIP
    ]

    html = inline_svg(render(sections, build_toc(toc_entries)))
    OUTPUT_HTML.write_text(html, encoding="utf-8")

    diagrams = len(re.findall(r'<figure class="diagram">', html))
    print(f"섹션 {len(sections)}개, 다이어그램 {diagrams}개 → {OUTPUT_HTML}")


if __name__ == "__main__":
    main()
