from __future__ import annotations

from pathlib import Path
import re

import markdown


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = PROJECT_ROOT / "docs"
PDF_DIR = DOCS_DIR / "pdf"

OUTPUT_MD = DOCS_DIR / "PORTFOLIO_COMBINED.md"
OUTPUT_HTML = PDF_DIR / "portfolio-combined.html"
CSS_PATH = PDF_DIR / "portfolio-combined.css"

SECTIONS = [
    ("PORTFOLIO_BULK_INSERT.md", "대회 제출 Insert 경로 최적화"),
    ("PORTFOLIO_JUDGE.md", "JUDGE 서버 설계 과정"),
    ("PORTFOLIO_SCOREBOARD_RECOVERY.md", "Redis Scoreboard Recovery"),
    ("PORTFOLIO_RANK_QUERY_OPTIMIZATION.md", "Rank 조회 경로 최적화"),
]


def slugify(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"[^0-9a-zA-Z가-힣\s-]", "", text)
    text = re.sub(r"\s+", "-", text)
    return text


def read_markdown(path: Path) -> tuple[str, str]:
    content = path.read_text(encoding="utf-8").strip()
    lines = content.splitlines()

    title = path.stem
    body_start = 0

    if lines and lines[0].startswith("# "):
        title = lines[0][2:].strip()
        body_start = 1
        while body_start < len(lines) and not lines[body_start].strip():
            body_start += 1

    body = "\n".join(lines[body_start:]).strip()
    return title, body


def shift_heading_levels(markdown_text: str) -> str:
    shifted_lines = []
    for line in markdown_text.splitlines():
        match = re.match(r"^(#{1,5})(\s+.*)$", line)
        if match:
            hashes, rest = match.groups()
            shifted_lines.append("#" * min(len(hashes) + 1, 6) + rest)
        else:
            shifted_lines.append(line)
    return "\n".join(shifted_lines)


def build_combined_markdown() -> str:
    toc_lines = []
    section_blocks = []

    for index, (file_name, fallback_title) in enumerate(SECTIONS, start=1):
        title, body = read_markdown(DOCS_DIR / file_name)
        title = title or fallback_title
        body = shift_heading_levels(body)
        toc_lines.append(f"{index}. [{title}](#{slugify(f'{index}- {title}')})")

        block = [
            f"## {index}. {title}",
            "",
            body,
        ]
        if index < len(SECTIONS):
            block.extend(["", '<div class="page-break"></div>'])
        section_blocks.append("\n".join(block).strip())

    parts = [
        "# 백엔드 포트폴리오",
        "",
        "Spring Boot 기반 온라인 저지 서비스 개발 과정에서 정리한 포트폴리오 문서를 하나로 합친 문서다.",
        "",
        "## 목차",
        "",
        *toc_lines,
        "",
        '<div class="page-break"></div>',
        "",
        *section_blocks,
        "",
    ]

    return "\n".join(parts).rstrip() + "\n"


def render_html(markdown_text: str) -> str:
    body = markdown.markdown(
        markdown_text,
        extensions=["extra", "fenced_code", "tables", "toc", "sane_lists"],
    )

    return f"""<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>백엔드 포트폴리오</title>
  <link rel="stylesheet" href="{CSS_PATH.name}">
</head>
<body>
  <main class="markdown-body">
    {body}
  </main>
</body>
</html>
"""


def main() -> None:
    combined_markdown = build_combined_markdown()
    OUTPUT_MD.write_text(combined_markdown, encoding="utf-8")
    OUTPUT_HTML.write_text(render_html(combined_markdown), encoding="utf-8")


if __name__ == "__main__":
    main()
