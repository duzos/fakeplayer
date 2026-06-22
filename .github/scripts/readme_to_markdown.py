#!/usr/bin/env python3
"""Convert README.md into pure-markdown README_nohtml.md for CurseForge.

CurseForge's description editor only accepts markdown (no raw HTML), so the
hand-written HTML in README.md (centering divs, screenshot tables, <img> badges)
has to be lowered to plain markdown. Badges are kept as markdown badges
([![alt](img)](link)) and relative image paths are rewritten to absolute raw
GitHub URLs so they resolve off-site.

Usage: readme_to_markdown.py <input.md> <output.md>
"""
import re
import sys

RAW_BASE = "https://raw.githubusercontent.com/Duzos/fakeplayer/master/"


def abs_url(src):
    src = src.strip()
    if src.startswith(("http://", "https://", "//")):
        return src
    return RAW_BASE + src.lstrip("./")


def img_to_md(m):
    attrs = m.group(1)
    src = re.search(r'src\s*=\s*"([^"]*)"', attrs)
    alt = re.search(r'alt\s*=\s*"([^"]*)"', attrs)
    return "![{}]({})".format(alt.group(1) if alt else "", abs_url(src.group(1)) if src else "")


def convert(text):
    # <img> (incl. those wrapped in [..](url) badge links) -> markdown image
    text = re.sub(r"<img\b([^>]*?)/?>", img_to_md, text)
    # line breaks -> newline
    text = re.sub(r"<br\s*/?>", "\n", text)
    # inline emphasis
    text = text.replace("<b>", "**").replace("</b>", "**")
    text = text.replace("<i>", "*").replace("</i>", "*")
    # drop sub/sup wrappers, keep their text
    text = re.sub(r"</?su[bp]\b[^>]*>", "", text)
    # table cells become their own lines, other layout tags are dropped
    text = re.sub(r"</?td\b[^>]*>", "\n", text)
    text = re.sub(r"</?(div|table|tbody|thead|tr)\b[^>]*>", "", text)
    # entities
    text = text.replace("&nbsp;", " ")
    # tidy: strip trailing spaces, collapse blank runs
    text = "\n".join(line.rstrip() for line in text.splitlines())
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip() + "\n"


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: readme_to_markdown.py <input.md> <output.md>")
    with open(sys.argv[1], encoding="utf-8") as f:
        out = convert(f.read())
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        f.write(out)


if __name__ == "__main__":
    main()
