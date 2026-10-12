"""Lists the libraries left out of the service, as a markdown document (docs/library-left-out.md).

User request 2026-10-12: a separate list of the libraries left out of the service. Libraries already in the service
DB stay in by default (user decision 2026-10-12); section B is information only.

  A. Libraries of the collection DB that the library-insert command does not put into the service, grouped by the
     reason in the command's report (key leftOut), with the detail of each reason.
  B. Service libraries with a problem: disabled ones (reason from disabled_reasons.tsv), the ones in
     rules/gone_known.tsv (checked by a person, kept), and any other enabled library whose collection library is
     missing, marked crawl_skip or never fully crawled.

Usage (from the repository root):
  python scripts/library/list_left_out.py --report <library-insert-<id>.json> --snapshot <copy.sqlite>
      --service <service-library.tsv> [--rules <dir>] [--disabled <tsv>] [--date YYYY-MM-DD] [--out <file.md>]

  --report    preview report of library-insert (JSON: leftOut, goneKnown, newLibraries, reportId, snapshot)
  --snapshot  collection DB copy, opened read-only (table library)
  --service   TSV export of the service library table with a header: id, name, enabled, base_url, platform_type and,
              optionally, collection_library_id (the collection.collection_link pair). Made with:

      wsl -d Ubuntu -- docker compose -p <compose-project> exec -T db psql -U chaekcheck -d chaekcheck -c
        "\\copy (select l.id, l.name, l.enabled, l.base_url, l.platform_type, c.collection_library_id
                 from library.library l left join collection.collection_link c on c.library_id = l.id
                 order by l.id) to stdout with (format csv, delimiter E'\\t', header true)" > service-library.tsv

  --rules     rules folder, default scripts/library/rules/ (kyobo_probe.tsv, demo_or_closed.tsv, same_site.tsv,
              library_kinds.tsv, gone_known.tsv; files and columns: rules/README.md)
  --disabled  why each disabled service library is off, default scripts/library/disabled_reasons.tsv
              (library_id, name, disabled_on, reason, source)
  --date      date written in the document, default the report's createdAt date
  --out       write the markdown here instead of stdout

Standard library only. Nothing is written except --out.
Test: python -m unittest scripts/library/test_list_left_out.py
"""
import argparse
import csv
import json
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Reason key of the report -> (title, meaning). Order = the order the command checks (LibraryInsertPlanner, R2-5).
REASONS = [
    ('kyobo_rental_mixed', '교보 구독형 대여·혼합',
     '교보 구독형(`KYOBO_SUBSCRIPTION_V1`) 중 probe(`kyobo_probe.tsv`, `base_url` 끝 `/`를 떼고 맞춤) 운영 방식이 '
     '대여(`RENTAL`)나 혼합(`MIXED`)인 곳. 수집도 서비스도 하지 않음(plan ADR-0067)'),
    ('crawl_skip', '수집 DB가 받지 않음',
     '수집 DB `library.crawl_skip`에 이유가 적힌 곳. 이유는 그 칸 글 그대로'),
    ('no_full_success', '전체 수집이 끝나지 않음',
     '수집 DB `library.last_full_success_at`이 비어 있는 곳. 수집이 한 번 끝나면 다음 넣기에서 들어감'),
    ('demo_or_closed', '체험용·운영 중단', '`demo_or_closed.tsv`에 있는 곳(사람이 정함)'),
    ('same_site', '서비스 도서관과 같은 사이트 (다른 포트)', '`same_site.tsv`에 있는 곳(사람이 정함)'),
    ('test', '시험용 (종류 `TEST`)', '`library_kinds.tsv` 종류가 `TEST`인 곳'),
]
TRUE = {'t', 'true', '1', 'y', 'yes'}


def read_tsv(path):
    """Rows of a rules TSV (UTF-8, tab, header, no quoting: rules/README.md)."""
    with open(path, encoding='utf-8', newline='') as f:
        lines = f.read().split('\n')
    header = lines[0].rstrip('\r').split('\t')
    rows = []
    for line in lines[1:]:
        line = line.rstrip('\r')
        if line:
            cells = line.split('\t')
            rows.append(dict(zip(header, cells + [''] * (len(header) - len(cells)))))
    return rows


def read_service(path):
    """Service library export (psql \\copy csv with tab delimiter, so quoting may appear)."""
    with open(path, encoding='utf-8', newline='') as f:
        rows = list(csv.DictReader(f, delimiter='\t'))
    for column in ('id', 'name', 'enabled', 'base_url', 'platform_type'):
        if rows and column not in rows[0]:
            raise SystemExit('service TSV has no column ' + column)
    return rows


def read_snapshot(path):
    uri = 'file:' + os.path.abspath(path).replace('\\', '/') + '?mode=ro'
    con = sqlite3.connect(uri, uri=True)
    try:
        columns = {row[1] for row in con.execute('PRAGMA table_info(library)')}
        wanted = ['id', 'name', 'platform_type', 'base_url', 'crawl_skip', 'last_full_success_at', 'vendor']
        select = ', '.join(c if c in columns else 'NULL' for c in wanted)
        return {row[0]: dict(zip(wanted, row)) for row in con.execute('SELECT ' + select + ' FROM library')}
    finally:
        con.close()


def cell(value):
    """A markdown table cell: no pipes or line breaks."""
    if value is None or value == '':
        return '—'
    return str(value).replace('\r', ' ').replace('\n', ' ').replace('|', '\\|').strip()


def code(value):
    return '`' + str(value) + '`' if value else '—'


def table(header, rows):
    out = ['| ' + ' | '.join(header) + ' |', '|' + '---|' * len(header)]
    out += ['| ' + ' | '.join(row) + ' |' for row in rows]
    return out


def details(rules_dir):
    """Per-reason detail of a collection library: collection id -> text."""
    probe = {}
    for row in read_tsv(os.path.join(rules_dir, 'kyobo_probe.tsv')):
        probe[row['site_url'].rstrip('/')] = row
    demo = {int(r['collection_id']): r for r in read_tsv(os.path.join(rules_dir, 'demo_or_closed.tsv'))}
    same = {int(r['collection_id']): r for r in read_tsv(os.path.join(rules_dir, 'same_site.tsv'))}
    kinds = {int(r['collection_id']): r for r in read_tsv(os.path.join(rules_dir, 'library_kinds.tsv'))}
    return probe, demo, same, kinds


def detail_of(reason, library, probe, demo, same, kinds):
    cid = library['id']
    if reason == 'kyobo_rental_mixed':
        row = probe.get((library['base_url'] or '').rstrip('/'))
        if row is None:
            return 'probe 줄 없음'
        return 'probe {} ({}), {}'.format(row['model'], row['lending_model'], row['checked_at'][:10])
    if reason == 'crawl_skip':
        text = library['crawl_skip'] or ''
        if cid in same:
            text += ' / `same_site.tsv`: 서비스 {}와 같은 사이트'.format(same[cid]['library_id'])
        return text
    if reason == 'no_full_success':
        return '`last_full_success_at` 없음'
    if reason == 'demo_or_closed':
        row = demo.get(cid)
        return '{} ({})'.format(row['source'], row['decided_on']) if row else '`demo_or_closed.tsv` 줄 없음'
    if reason == 'same_site':
        row = same.get(cid)
        return '서비스 {}와 같은 사이트 ({})'.format(row['library_id'], row['decided_on']) if row else ''
    if reason == 'test':
        row = kinds.get(cid)
        return row['note'] if row else ''
    return library['crawl_skip'] or ''


def collection_state(library):
    if library is None:
        return '수집 DB에 없음'
    parts = []
    if library['crawl_skip']:
        parts.append('`crawl_skip`: ' + library['crawl_skip'])
    parts.append('마지막 전체 수집 ' + (library['last_full_success_at'] or '없음'))
    return ' / '.join(parts)


def render(report, snapshot_name, collected, rules_dir, service, disabled, date):
    probe, demo, same, kinds = details(rules_dir)
    gone_rows = {int(r['collection_id']): r for r in read_tsv(os.path.join(rules_dir, 'gone_known.tsv'))}
    left_out = report.get('leftOut', {})
    total = sum(len(v) for v in left_out.values())
    known = [r[0] for r in REASONS]
    reasons = REASONS + [(k, k, '알 수 없는 이유 키(보고서에만 있음)') for k in left_out if k not in known]
    new = report.get('newLibraries', [])

    out = ['# 서비스에 넣지 않은 도서관 목록', '']
    out.append('- 작성일: ' + date)
    out.append('- 목적: 수집 DB에는 있지만 서비스에 넣지 않는 도서관(A)과, 서비스에 있지만 문제가 있는 도서관(B)을 한곳에 '
               '모읍니다(사용자 요청 2026-10-12, 두 절 모두).')
    out.append('- **서비스 DB에 이미 있는 도서관은 기본으로 서비스에 그대로 둡니다**(사용자 결정 2026-10-12). '
               'B절은 알림용이며, 끄거나 빼는 결정이 아닙니다.')
    out.append('- 출처: 넣기 명령 `library-insert` 미리보기 보고서 `{}`(만든 때 {}), 수집 DB 사본 `{}`(읽기 전용), '
               '규칙 폴더 `scripts/library/rules/`(`kyobo_probe.tsv`, `demo_or_closed.tsv`, `same_site.tsv`, '
               '`library_kinds.tsv`, `gone_known.tsv`), 서비스 `library.library`·`collection.collection_link` 내보내기, '
               '끈 이유 `scripts/library/disabled_reasons.tsv`.'.format(
                   report.get('reportId', '?'), report.get('createdAt', '?'), snapshot_name))
    out.append('- 다시 만들기: `python scripts/library/list_left_out.py`(사용법은 스크립트 머리말). 손으로 고치지 않습니다.')
    out.append('- 관련: [`library-site-issues.md`](library-site-issues.md)(빠진 사이트와 이유의 옛 기록), '
               '[`library-maintenance.md`](library-maintenance.md) 6-2(끄기)·13절(작업 기록).')
    out.append('')

    out.append('## A. 수집 DB에 있지만 넣지 않는 곳 ({}곳)'.format(total))
    out.append('')
    out.append('넣기 명령은 서비스와 짝이 없는 수집 도서관마다 아래 규칙을 차례로 보고, **처음 맞는 규칙 하나**로 뺍니다'
               '(`LibraryInsertPlanner`, plan R2-5). 그래서 한 곳은 한 표에만 나옵니다. '
               '같은 보고서로 새로 넣는 곳은 {}곳입니다: {}.'.format(
                   len(new), ', '.join('{} {}'.format(n['collectionId'], n['library']['name']) for n in new) or '없음'))
    out.append('')
    summary = []
    for i, (key, title, meaning) in enumerate(reasons, 1):
        summary.append(['A-{}'.format(i), title + ' (`{}`)'.format(key), str(len(left_out.get(key, []))), meaning])
    out += table(['절', '이유', '곳', '뜻'], summary)
    out.append('')

    for i, (key, title, meaning) in enumerate(reasons, 1):
        ids = sorted(left_out.get(key, []))
        out.append('### A-{}. {} ({}곳)'.format(i, title, len(ids)))
        out.append('')
        out.append(meaning + '.')
        out.append('')
        if key == 'kyobo_rental_mixed' and ids:
            counts = {}
            for cid in ids:
                row = probe.get(((collected.get(cid) or {}).get('base_url') or '').rstrip('/'))
                lending = row['lending_model'] if row else '(probe 없음)'
                counts[lending] = counts.get(lending, 0) + 1
            out.append('운영 방식별: ' + ', '.join('{} {}곳'.format(k, v) for k, v in sorted(counts.items())) + '.')
            out.append('')
        if key == 'same_site':
            earlier = sorted(c for c in same if c not in ids and any(c in v for v in left_out.values()))
            if earlier:
                out.append('`same_site.tsv` {}줄 중 {}곳은 앞 규칙(대개 `crawl_skip`)에서 이미 빠져 여기에는 나오지 않습니다: {}.'
                           .format(len(same), len(earlier), ', '.join(str(c) for c in earlier)))
                out.append('')
        if not ids:
            out.append('없음.')
            out.append('')
            continue
        rows = []
        for cid in ids:
            library = collected.get(cid)
            if library is None:
                rows.append([str(cid), '(사본에 없음)', '—', '—', '—'])
                continue
            rows.append([str(cid), cell(library['name']), code(library['platform_type']), code(library['base_url']),
                         cell(detail_of(key, library, probe, demo, same, kinds))])
        out += table(['수집 번호', '이름', '화면 종류', '주소', '자세히'], rows)
        out.append('')

    # Section B
    has_link = bool(service) and 'collection_library_id' in service[0]
    by_id = {int(r['id']): r for r in service}

    def link_of(row):
        value = row.get('collection_library_id') if has_link else None
        return int(value) if value else None

    disabled_rows = [r for r in service if r['enabled'].strip().lower() not in TRUE]
    gone_ids = sorted(gone_rows)
    gone_service = {}
    for r in service:
        cid = link_of(r)
        if cid in gone_rows:
            gone_service[cid] = r
    others = []
    if has_link:
        for r in service:
            cid = link_of(r)
            if r in disabled_rows or cid in gone_rows:
                continue
            library = collected.get(cid)
            if library is None or library['crawl_skip'] or not library['last_full_success_at']:
                others.append(r)

    out.append('## B. 서비스에 있지만 문제가 있는 곳 ({}곳, 알림용)'.format(
        len(disabled_rows) + len(gone_ids) + len(others)))
    out.append('')
    out.append('서비스 도서관 {}곳 중 꺼 둔 곳과, 켜져 있지만 수집 DB가 더 받지 않는 곳입니다. '
               '**모두 서비스에 그대로 둡니다**(사용자 결정 2026-10-12). 넣기 명령도 이들을 바꾸거나 지우지 않습니다.'
               .format(len(service)))
    out.append('')

    out.append('### B-1. 꺼 둔 곳 (`enabled = false`, {}곳)'.format(len(disabled_rows)))
    out.append('')
    out.append('검색에서 빠져 있고 데이터는 남아 있습니다. 다시 켜는 법은 `library-maintenance.md` 6-2.')
    out.append('')
    rows = []
    for r in disabled_rows:
        lid = int(r['id'])
        reason = disabled.get(lid)
        cid = link_of(r)
        rows.append([str(lid), cell(r['name']), code(r['platform_type']), code(r['base_url']),
                     cell(reason['disabled_on']) if reason else '—',
                     cell(reason['reason']) if reason else '**이유 기록 없음 (확인 필요)**',
                     cell(reason['source']) if reason else '—',
                     (str(cid) + ': ' + cell(collection_state(collected.get(cid)))) if cid else '—'])
    out += table(['서비스 번호', '이름', '화면 종류', '주소', '끈 날', '이유', '출처', '수집 번호: 수집 DB 상태'], rows) \
        if rows else ['없음.']
    out.append('')
    crawled = []
    for r in disabled_rows:
        reason = disabled.get(int(r['id']))
        library = collected.get(link_of(r))
        if reason and library and not library['crawl_skip'] \
                and (library['last_full_success_at'] or '')[:10] >= reason['disabled_on']:
            crawled.append(r['id'])
    if crawled:
        out.append('- 끈 날 뒤에도 수집 DB의 전체 수집이 끝난 곳: {}. 사이트가 돌아왔을 수 있으니 다시 켤지 6-2대로 '
                   '확인할 만합니다(이 문서는 정하지 않음).'.format(', '.join(crawled)))
    missing = [r['id'] for r in disabled_rows if int(r['id']) not in disabled]
    if missing:
        out.append('- 끈 이유가 `disabled_reasons.tsv`에 없는 곳: {}. changelog와 `library-maintenance.md` 6-2를 보고 '
                   '적어야 합니다.'.format(', '.join(missing)))
    if crawled or missing:
        out.append('')

    out.append('### B-2. 켜져 있지만 수집하지 않음, 사람이 확인하고 둠 (`gone_known.tsv`, {}곳)'.format(len(gone_ids)))
    out.append('')
    out.append('넣기 보고서의 `goneKnown` {}. 다시 묻지 않습니다(`rules/README.md`).'.format(
        report.get('goneKnown', [])))
    out.append('')
    rows = []
    for cid in gone_ids:
        g = gone_rows[cid]
        s = gone_service.get(cid) or by_id.get(cid)
        library = collected.get(cid)
        rows.append([str(cid), str(s['id']) if s else '—', cell(g['name']),
                     code(s['platform_type']) if s else '—', code(s['base_url']) if s else '—',
                     cell(g['checked_on']), cell(g['state']),
                     cell(library['crawl_skip']) if library else '수집 DB에 없음'])
    out += table(['수집 번호', '서비스 번호', '이름', '화면 종류', '주소', '확인한 날', '확인한 상태', '수집 DB `crawl_skip`'],
                 rows) if rows else ['없음.']
    out.append('')

    out.append('### B-3. 그 밖에 켜져 있지만 수집 DB에 없거나 받지 않는 곳 ({}곳)'.format(len(others)))
    out.append('')
    if not has_link:
        out.append('서비스 내보내기에 `collection_library_id` 칸이 없어 보지 못했습니다.')
    elif not others:
        out.append('없음. 켜진 서비스 도서관은 모두 수집 DB에 있고, `crawl_skip`이 없고, 전체 수집이 끝났습니다.')
    else:
        out.append('새로 생긴 곳입니다. 확인해 `gone_known.tsv`에 적거나 6-2로 끌지 사람이 정합니다.')
        out.append('')
        rows = [[r['id'], cell(r['name']), code(r['platform_type']), code(r['base_url']),
                 str(link_of(r)) + ': ' + cell(collection_state(collected.get(link_of(r))))] for r in others]
        out += table(['서비스 번호', '이름', '화면 종류', '주소', '수집 번호: 수집 DB 상태'], rows)
    out.append('')
    return '\n'.join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description='List the libraries left out of the service (markdown).')
    parser.add_argument('--report', required=True)
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--service', required=True)
    parser.add_argument('--rules', default=os.path.join(HERE, 'rules'))
    parser.add_argument('--disabled', default=os.path.join(HERE, 'disabled_reasons.tsv'))
    parser.add_argument('--date')
    parser.add_argument('--out')
    args = parser.parse_args(argv)

    with open(args.report, encoding='utf-8') as f:
        report = json.load(f)
    snapshot_name = os.path.basename(args.snapshot)
    if report.get('snapshot') and report['snapshot'] != snapshot_name:
        print('warning: report was made from {}, not {}'.format(report['snapshot'], snapshot_name), file=sys.stderr)
    collected = read_snapshot(args.snapshot)
    service = read_service(args.service)
    disabled = {int(r['library_id']): r for r in read_tsv(args.disabled)}
    date = args.date or (report.get('createdAt') or '')[:10] or '?'
    text = render(report, snapshot_name, collected, args.rules, service, disabled, date)
    if args.out:
        with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
    else:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stdout.write(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
