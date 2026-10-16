"""Tests of recommended_lists.py (cycle 21 handoff step 7; AC-11..AC-14). No network and no database: the requests,
the YouTube answers and the catalog are stand-ins.

  python -m unittest scripts/crawl/test_recommended_lists.py
"""
import io
import json
import shutil
import tempfile
import unittest
import urllib.error
from argparse import Namespace
from pathlib import Path

import recommended_lists as rl

HERE = Path(__file__).resolve().parent
KEY = 'AIzaTESTKEY000'
SEOJI_KEY = 'seojiTESTKEY'


class Response(io.BytesIO):
    def __init__(self, status, body, content_type='application/json; charset=UTF-8'):
        super().__init__(body)
        self.status = status
        self.headers = {'Content-Type': content_type}

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class Opener:
    """Answers by path; every URL asked is kept."""

    def __init__(self, answers):
        self.answers = answers
        self.urls = []

    def __call__(self, request, timeout=None):
        url = request.full_url
        self.urls.append(url)
        for prefix, answer in self.answers.items():
            if prefix in url:
                status, body = answer(url) if callable(answer) else answer
                if status >= 400:
                    raise urllib.error.HTTPError(url, status, 'error', {'Content-Type': 'application/json'},
                                                 io.BytesIO(body))
                return Response(status, body)
        raise urllib.error.URLError('no answer')


def item(video_id, published, title, description='', public=True):
    return {'contentDetails': {'videoId': video_id, 'videoPublishedAt': published + 'T10:00:00Z'},
            'snippet': {'title': title, 'description': description},
            'status': {'privacyStatus': 'public' if public else 'private'}}


def youtube_answers(monthly, uploads):
    def playlist_items(url):
        items = monthly if rl.MONTHLY_PLAYLIST in url else uploads
        return 200, json.dumps({'items': items}).encode()
    return {
        '/youtube/v3/playlists': (200, json.dumps({'items': [{'snippet': {'channelId': 'UCx'}}]}).encode()),
        '/youtube/v3/channels': (200, json.dumps(
            {'items': [{'contentDetails': {'relatedPlaylists': {'uploads': 'UUx'}}}]}).encode()),
        '/youtube/v3/playlistItems': playlist_items,
        '/seoji/SearchApi.do': (200, json.dumps({'docs': [
            {'TITLE': '새 책', 'EA_ISBN': '9791100000001', 'AUTHOR': '새 저자 지음', 'SUBJECT': '9'}]}).encode()),
    }


LEE_HEADER = rl.LEEDONGJIN_COLUMNS


def lee_row(month, position, video, title, field='history', pinned_by='', isbn='', **extra):
    row = {c: '' for c in LEE_HEADER}
    row.update(month=month, position=str(position), video_id=video, field=field, title=title, pinned_by=pinned_by,
               isbn13=isbn, checked_on='2026-09-01', **extra)
    return row


class ListsDir:
    def __init__(self, lee_rows, snu_rows=()):
        self.path = Path(tempfile.mkdtemp())
        rl.write_tsv(self.path / 'leedongjin.tsv', LEE_HEADER, list(lee_rows))
        rl.write_tsv(self.path / 'snu100.tsv', rl.SNU_COLUMNS, list(snu_rows))
        rl.write_tsv(self.path / 'subject_fields.tsv', ['subject', 'field_suggestion', 'note'],
                     [{'subject': '9', 'field_suggestion': 'history', 'note': ''}])
        (self.path / '.env').write_text(
            f'CHAEKCHECK_YOUTUBE_API_KEY={KEY}\nCHAEKCHECK_PICKS_NLK_API_KEY={SEOJI_KEY}\n', encoding='utf-8')

    def args(self, **extra):
        values = dict(lists_dir=str(self.path), env=str(self.path / '.env'), db_port=None, sqlite=None,
                      log=str(self.path / 'collect.log'), today='2026-10-16', skip_youtube=False, skip_editions=True,
                      skip_covers=True)
        values.update(extra)
        return Namespace(**values)

    def text(self):
        return {p.name: p.read_text(encoding='utf-8') for p in self.path.glob('*.tsv')}

    def close(self):
        shutil.rmtree(self.path)


def run(lists, answers):
    opener = Opener(answers)
    status = rl.run(lists.args(), http_opener=opener, sleep=lambda s: None)
    return status, opener


class YouTubeTests(unittest.TestCase):

    def setUp(self):
        self.lists = ListsDir([lee_row('2026-06', 1, 'lMx0zEd1qtY', '히스토리아 비테이', field='science')])
        self.addCleanup(self.lists.close)

    def test_a_new_video_gives_a_pending_row_without_its_title_or_description(self):  # AC-11, R4-3
        monthly = [item('lMx0zEd1qtY', '2026-06-24', '[이달의 책] 《히스토리아 비테이》'),
                   item('NEWVIDEO001', '2026-10-30', '10월의 책 영상 제목 비밀문구', '설명란 비밀문구\n🔖 새 책')]
        status, opener = run(self.lists, youtube_answers(monthly, []))
        self.assertEqual(status, 0)
        _, pending = rl.read_tsv(self.lists.path / 'leedongjin_pending.tsv')
        self.assertEqual(pending, [{'video_id': 'NEWVIDEO001', 'month': '2026-10', 'reason': '', 'title': '새 책',
                                    'author': '새 저자 지음', 'isbn13': '9791100000001', 'field_suggestion': 'history'}])
        everything = ''.join(self.lists.text().values()) + (self.lists.path / 'collect.log').read_text(encoding='utf-8')
        self.assertNotIn('비밀문구', everything)
        self.assertNotIn(KEY, everything)
        self.assertNotIn(SEOJI_KEY, everything)
        # the listed video, still public: checked today
        _, rows = rl.read_tsv(self.lists.path / 'leedongjin.tsv')
        self.assertEqual(rows[0]['checked_on'], '2026-10-16')

    def test_only_the_data_api_is_asked(self):  # AC-12
        monthly = [item('lMx0zEd1qtY', '2026-06-24', '《히스토리아 비테이》')]
        _, opener = run(self.lists, youtube_answers(monthly, []))
        hosts = {u.split('/')[2] for u in opener.urls}
        self.assertEqual(hosts, {'www.googleapis.com'})
        self.assertFalse(any('www.youtube.com' in u for u in opener.urls))

    def test_a_refused_key_leaves_the_files_and_logs_one_line(self):  # AC-13
        before = self.lists.text()
        refused = json.dumps({'error': {'code': 400, 'errors': [{'reason': 'keyInvalid'}]}}).encode()
        status, _ = run(self.lists, {'/youtube/v3/': (400, refused)})
        self.assertEqual(status, 1)
        self.assertEqual(self.lists.text(), before)
        log = (self.lists.path / 'collect.log').read_text(encoding='utf-8')
        failures = [line for line in log.splitlines() if 'failed' in line]
        self.assertEqual(len(failures), 1)
        self.assertIn('keyInvalid', failures[0])
        self.assertNotIn(KEY, log)

    def test_no_answer_leaves_the_files(self):  # R4-6
        before = self.lists.text()
        status, _ = run(self.lists, {})
        self.assertEqual(status, 1)
        self.assertEqual(self.lists.text(), before)

    def test_a_video_naming_no_book_is_left_for_a_person(self):  # AC-14
        monthly = [item('lMx0zEd1qtY', '2026-06-24', '《히스토리아 비테이》'),
                   item('NOBOOK00001', '2026-11-28', '11월 이야기', '책 이름이 없는 설명')]
        run(self.lists, youtube_answers(monthly, []))
        _, pending = rl.read_tsv(self.lists.path / 'leedongjin_pending.tsv')
        self.assertEqual([(p['video_id'], p['reason']) for p in pending], [('NOBOOK00001', 'no_book_name')])
        _, rows = rl.read_tsv(self.lists.path / 'leedongjin.tsv')
        self.assertEqual([r['video_id'] for r in rows], ['lMx0zEd1qtY'])

    def test_a_gone_video_loses_its_rows(self):  # 30-day rule
        monthly = [item('lMx0zEd1qtY', '2026-06-24', '《히스토리아 비테이》', public=False)]
        run(self.lists, youtube_answers(monthly, []))
        _, rows = rl.read_tsv(self.lists.path / 'leedongjin.tsv')
        self.assertEqual(rows, [])
        self.assertIn('is deleted', (self.lists.path / 'collect.log').read_text(encoding='utf-8'))

    def test_a_book_of_the_year_moves_to_december(self):  # R3-5
        monthly = [item('lMx0zEd1qtY', '2026-06-24', '《히스토리아 비테이》'),
                   item('YEARBOOK001', '2026-12-24', '2026 올해의 책 베스트북', '『히스토리아 비테이』')]
        run(self.lists, youtube_answers(monthly, []))
        _, rows = rl.read_tsv(self.lists.path / 'leedongjin.tsv')
        self.assertEqual([(r['month'], r['video_id'], r['year_book']) for r in rows],
                         [('2026-12', 'YEARBOOK001', '2026')])
        self.assertFalse((self.lists.path / 'leedongjin_pending.tsv').exists())

    def test_book_names_and_channel_words(self):  # R4-2
        video = rl.Video('x', '2026-10-01', True, '[이달의 책] 《가》 〈나〉', '『다』\n🔖 라 마\n[베스트북]')
        self.assertEqual(rl.book_names(video), ['가', '나', '다', '라 마'])


class FakeCatalog:
    def __init__(self, books):
        self.books = {b['id']: dict(b, names=b.get('names', []), authors=set(b.get('authors', []))) for b in books}

    def by_isbn(self, isbn):
        return [b for b in self.books.values() if b['isbn13'] == isbn]

    def by_title(self, title, publisher):
        return [b for b in self.books.values() if b['title'] == title]

    def same_book(self, anchor):
        base = rl.text_key(rl.title_base(anchor['title'])) if anchor.get('title') else anchor['main']
        found = [b for b in self.books.values() if b['main'] == anchor['main']
                 or rl.text_key(rl.title_base(b['title'])) == base]
        return [b for b in found if not anchor['authors'] or b['authors'] & anchor['authors']]


def book(id, title, publisher='출판사', authors=('김만중',), libs=(1,), isbn13=None, names=None):
    return {'id': id, 'title': title, 'author': ', '.join(authors), 'publisher': publisher, 'isbn13': isbn13,
            'main': rl.text_key(title), 'library_ids': list(libs), 'authors': set(authors),
            'names': list(names or authors)}


class EditionTests(unittest.TestCase):
    kinds = {1: False, 2: False, 3: False, 11: True, 12: True}

    def test_manual_rows_stay_and_others_take_the_edition_held_most(self):
        catalog = FakeCatalog([book(1, '구운몽', libs=(1,)), book(2, '구운몽', publisher='명현', libs=(1, 2, 3)),
                               book(3, '구운몽', publisher='어린이책방', libs=(1, 2, 3, 11)),
                               book(4, '미국의 민주주의 : 자유 너머의 인간', authors=('토크빌',), libs=(1,))])
        rows = [{'title': '구운몽', 'pinned_title': '구운몽', 'pinned_publisher': '출판사', 'pinned_by': ''},
                {'title': '미국의 민주주의', 'pinned_title': '미국의 민주주의 : 자유 너머의 인간',
                 'pinned_author': '알렉시 드 토크빌', 'pinned_publisher': '위즈덤커넥트', 'pinned_by': 'manual'}]
        chosen = rl.choose_editions(rows, catalog, self.kinds, rl.Log(None))
        # 3 is held most but for children (R3-4)
        self.assertEqual(chosen, {0: 2, 1: 4})
        self.assertEqual((rows[0]['pinned_publisher'], rows[0]['pinned_by']), ('명현', 'collector'))
        self.assertEqual(rows[1]['pinned_publisher'], '위즈덤커넥트')

    def test_excluded_editions_fall_back_to_the_title(self):  # R3-4: 실천이성비판
        catalog = FakeCatalog([
            book(1, '실천이성비판: 흔들리는 시대', authors=('칸트', '번역AI'), names=('칸트', '통합언어인텔리전스기반번역AI'),
                 libs=(1, 2, 3)),
            book(2, '실천이성비판', publisher='아카넷', authors=('백종현',), libs=(1, 2))])
        rows = [{'title': '실천이성비판', 'pinned_title': '실천이성비판: 흔들리는 시대', 'pinned_by': ''}]
        self.assertEqual(rl.choose_editions(rows, catalog, self.kinds, rl.Log(None)), {0: 2})

    def test_every_volume_is_one_book_and_the_first_wins_a_tie(self):  # AC-7
        catalog = FakeCatalog([book(5, '탁류 3', libs=(1, 2)), book(6, '탁류 1', libs=(2, 3)), book(7, '탁류 2', libs=(1, 3))])
        rows = [{'title': '탁류', 'pinned_title': '탁류 3', 'pinned_by': ''}]
        self.assertEqual(rl.choose_editions(rows, catalog, self.kinds, rl.Log(None)), {0: 6})
        self.assertEqual(rows[0]['pinned_title'], '탁류 1')

    def test_no_edition_clears_the_fixed_one(self):
        rows = [{'title': '광장', 'pinned_title': '없는 판', 'pinned_by': 'collector'}]
        self.assertEqual(rl.choose_editions(rows, FakeCatalog([]), self.kinds, rl.Log(None)), {})
        self.assertEqual(rows[0]['pinned_title'], '')


class SameRuleAsJava(unittest.TestCase):
    """The one fixture Java's BookChoiceTests reads too (ADR-0124)."""

    def test_the_same_choice(self):
        data = json.loads((HERE / 'testdata' / 'book_choice_cases.json').read_text(encoding='utf-8'))
        kinds = {int(k): v for k, v in data['kinds'].items()}
        for case in data['cases']:
            books = [{'id': c['id'], 'title': c['title'], 'library_ids': c['libraryIds']} for c in case['candidates']]
            with self.subTest(case['name']):
                self.assertEqual(rl.choose(books, kinds)['id'], case['chosen'])

    def test_volumes_read_like_shown_title(self):
        for title, base, volume in [('토지. 1(1부 1권)', '토지', '1'), ('위대한 유산(상)', '위대한 유산', '상'),
                                    ('탁류 3', '탁류', '3'), ('화씨 451', '화씨 451', None), ('1984', '1984', None),
                                    ('백석의 사슴 오리지널판', '백석의 사슴', None), ('햄릿(Hamlet)', '햄릿', None),
                                    ('삼국지 02권', '삼국지', '2')]:
            with self.subTest(title):
                self.assertEqual(rl.read_title(title), (base, volume))


class CoverTests(unittest.TestCase):

    def test_a_cover_is_made_200px_wide_jpeg(self):
        from PIL import Image
        source = io.BytesIO()
        Image.new('RGBA', (400, 600), (200, 30, 30, 255)).save(source, 'PNG')
        jpeg = rl.shrink(source.getvalue())
        with Image.open(io.BytesIO(jpeg)) as image:
            self.assertEqual((image.format, image.width, image.height), ('JPEG', 200, 300))

    def test_not_an_image_is_refused(self):
        with self.assertRaises(Exception):
            rl.shrink(b'<html>')

    def test_an_original_over_5mb_is_not_read(self):
        big = b'x' * (rl.COVER_MAX_BYTES + 1)
        http = rl.Http(rl.Log(None), [], sleep=lambda s: None,
                       opener=Opener({'example.test': (200, big)}))
        with self.assertRaises(rl.CollectorFailure):
            http.get('https://example.test/a.jpg', rl.COVER_MAX_BYTES)

    def test_collected_covers_put_yes24_and_kyobo_first_as_https(self):
        import sqlite3
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE holding (library_id INTEGER, item_key TEXT, cover_url TEXT)')
        db.executemany('INSERT INTO holding VALUES (?, ?, ?)', [
            (1, 'a', 'http://lib.example.go.kr/c.jpg'), (2, 'b', 'https://image.yes24.com/goods/1/M'),
            (3, 'c', None), (4, 'd', 'https://dcontent.dkyobobook.co.kr/x.jpg')])
        self.assertEqual(rl.collected_covers(db, [(1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')]),
                         ['https://image.yes24.com/goods/1/M', 'https://dcontent.dkyobobook.co.kr/x.jpg',
                          'https://lib.example.go.kr/c.jpg'])


if __name__ == '__main__':
    unittest.main()
