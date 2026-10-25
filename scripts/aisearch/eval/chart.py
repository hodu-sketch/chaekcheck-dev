"""Horizontal bar chart as a plain SVG (no plotting library on this PC).
usage: chart.py out.svg "title" "unit" label=value[:highlight] ...   (highlight: any text marks the bar)"""
import sys
from xml.sax.saxutils import escape

out, title, unit = sys.argv[1], sys.argv[2], sys.argv[3]
bars = []
for arg in sys.argv[4:]:
    label, rest = arg.rsplit("=", 1)
    value, _, mark = rest.partition(":")
    bars.append((label, float(value), bool(mark)))

W, left, right, top, row = 720, 230, 70, 56, 34
H = top + row * len(bars) + 24
vmax = max(v for _, v, _ in bars) or 1
plot = W - left - right
parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
         'font-family="Pretendard, \'Apple SD Gothic Neo\', \'Malgun Gothic\', sans-serif">',
         f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
         f'<text x="20" y="32" font-size="18" font-weight="700" fill="#1f1d2b">{escape(title)}</text>']
for i, (label, value, mark) in enumerate(bars):
    y = top + i * row
    w = plot * value / vmax
    color = "#6b4fe0" if mark else "#c9c2ee"
    parts.append(f'<text x="{left - 12}" y="{y + 19}" font-size="14" text-anchor="end" fill="#33313f">{escape(label)}</text>')
    parts.append(f'<rect x="{left}" y="{y + 4}" width="{w:.1f}" height="{row - 12}" rx="4" fill="{color}"/>')
    shown = f"{value:g}{unit}"
    parts.append(f'<text x="{left + w + 8:.1f}" y="{y + 19}" font-size="14" font-weight="{700 if mark else 400}" '
                 f'fill="#1f1d2b">{escape(shown)}</text>')
parts.append("</svg>")
open(out, "w", encoding="utf-8").write("\n".join(parts))
