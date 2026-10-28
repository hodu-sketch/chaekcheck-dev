"""Charts of the 책첵 추천 measurements for docs/reports/foryou-portfolio.md (user 2026-10-28: "수치 꼭 기록하고
포트폴리오용으로 할 수 있는 거 다 하고"). The numbers are the measured ones recorded in docs/plan-feedback.md
(「# 사이클 23」, 「# 사이클 23 (이어서)」); this script only draws them, in the style of docs/reports/ai-search-eval/.

  python -X utf8 scripts/foryou/eval/make_charts.py      # writes docs/reports/foryou-eval/*.svg
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parents[3] / 'docs' / 'reports' / 'foryou-eval'
FONT = "Pretendard, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
INK, SOFT, STRONG, MUTED = '#1f1d2b', '#c9c2ee', '#6b4fe0', '#33313f'


def bars(name, title, rows, unit='%', scale=100.0, note=None, label_w=230):
    """rows: (label, value, strong). One horizontal bar per row, all on one scale from 0 to `scale`."""
    width, bar_w = 720, 720 - label_w - 90
    height = 60 + 34 * len(rows) + (30 if note else 10)
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" '
           f'font-family="{FONT}">', f'<rect width="{width}" height="{height}" fill="#ffffff"/>',
           f'<text x="20" y="32" font-size="18" font-weight="700" fill="{INK}">{escape(title)}</text>']
    for i, (label, value, strong) in enumerate(rows):
        y = 60 + 34 * i
        w = bar_w * value / scale
        shown = f'{value:,.1f}'.rstrip('0').rstrip('.') if isinstance(value, float) else f'{value:,}'
        out += [f'<text x="{label_w - 12}" y="{y + 15}" font-size="14" text-anchor="end" fill="{MUTED}">{escape(label)}</text>',
                f'<rect x="{label_w}" y="{y}" width="{w:.1f}" height="22" rx="4" fill="{STRONG if strong else SOFT}"/>',
                f'<text x="{label_w + w + 8:.1f}" y="{y + 15}" font-size="14" font-weight="{700 if strong else 400}" '
                f'fill="{INK}">{shown}{unit}</text>']
    if note:
        out.append(f'<text x="20" y="{height - 12}" font-size="12" fill="{MUTED}">{escape(note)}</text>')
    out.append('</svg>')
    (OUT / name).write_text('\n'.join(out) + '\n', encoding='utf-8')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bars('1-baseline-judge.svg', 'AI 판정 「잘 맞는다」(4~5점) 비율 — 기준 점수',
         [('보유 도서관 많은 순 (그때 운영)', 55.0, False), ('유사도 순', 80.0, True), ('무작위 (바닥선)', 1.0, False)],
         note='3만 권, Haiku 판정 600쌍(방식 가림), 2026-10-25')
    bars('2-ordering-judge.svg', 'AI 판정 「잘 맞는다」(4~5점) 비율 — 순서 방식',
         [('보유 많은 순', 61.6, False), ('유사도 순 (채택)', 70.0, True), ('유사도 + 겹침 줄이기(MMR)', 54.4, False),
          ('유사도 + 겹침 + 분야 비율', 64.4, False)],
         note='3만 권, Haiku 판정 1,000쌍(사용자 50명 × 1·5·10·15·20번째, 방식 가림), 2026-10-25')
    bars('3-popular-share.svg', '추천 20권 중 흔한 책(보유 상위 10%) 비율 — 낮을수록 덜 뻔함',
         [('보유 많은 순', 43.5, False), ('유사도 순 (채택)', 10.7, True), ('무작위', 10.0, False)],
         note='가상 사용자 1,000명, 3만 권, 2026-10-25')
    bars('4-pool-30k-100k.svg', 'AI 판정 「잘 맞는다」(4~5점) 비율 — 대상 책 범위',
         [('3만 권', 61.6, False), ('10만 권 (채택)', 68.8, True)],
         note='유사도 순, 같은 사용자 50명 각 250쌍을 섞어 방식 가림, 2026-10-26')
    bars('5-same-work.svg', '추천에 같은 작품(다른 판·권)이 섞인 쌍 — 250쌍 중',
         [('규칙 강화 전', 11, False), ('규칙 강화 후', 5, True)], unit='쌍', scale=12.0,
         note='10만 권, Haiku 「같은 작품」 판정, 같은 판정 실행 안 비교, 2026-10-26')
    bars('6-data-growth.svg', '책첵 추천 자료 — 3만 권 판 → 10만 권 판(운영)',
         [('이웃 표 3만 판', 30937, False), ('이웃 표 10만 판', 81080, True), ('표지 3만 판', 29588, False),
          ('표지 10만 판', 80091, True), ('요약 3만 판', 25187, False), ('요약 10만 판', 68414, True)],
         unit='권', scale=90000.0, note='운영 DB에서 센 수, 2026-10-25 / 2026-10-26')
    print('\n'.join(sorted(p.name for p in OUT.glob('*.svg'))))


if __name__ == '__main__':
    main()
