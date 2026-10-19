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
        for name in ('collector_words.tsv', 'cover_hosts.tsv', 'cover_sources.tsv'):
            shutil.copy(rl.LISTS_DIR / name, self.path / name)
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

    def test_a_december_upload_with_a_year_word_is_left_for_a_person(self):  # widened 2026-10-16
        monthly = [item('lMx0zEd1qtY', '2026-06-24', '《히스토리아 비테이》')]
        uploads = [item('MOVIES00001', '2026-12-20', '2026 올해의 영화 결산', '《어떤 영화》'),
                   item('NOVEMBER001', '2026-11-20', '올해의 책 예고', '《다른 책》')]
        run(self.lists, youtube_answers(monthly, uploads))
        _, pending = rl.read_tsv(self.lists.path / 'leedongjin_pending.tsv')
        self.assertEqual([(p['video_id'], p['month'], p['title']) for p in pending],
                         [('MOVIES00001', '2026-12', '어떤 영화')])

    def test_a_missing_rules_file_stops_before_any_request(self):
        (self.lists.path / 'collector_words.tsv').unlink()
        before = self.lists.text()
        status, opener = run(self.lists, youtube_answers([], []))
        self.assertEqual((status, opener.urls), (1, []))
        self.assertEqual(self.lists.text(), before)

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

    def by_title_base(self, title):
        base = rl.text_key(rl.title_base(title))
        return [b for b in self.books.values() if rl.text_key(rl.title_base(b['title'])) == base]

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

    def test_all_same_author_editions_excluded_leaves_the_row_unmatched(self):  # R3-3·R3-4: 실천이성비판
        # the only non-excluded edition with the same title is another author's: no re-pick by the title alone
        # (plan retro 21 decision #2)
        catalog = FakeCatalog([
            book(1, '실천이성비판: 흔들리는 시대', authors=('칸트', '번역AI'), names=('칸트', '통합언어인텔리전스기반번역AI'),
                 libs=(1, 2, 3)),
            book(2, '실천이성비판', publisher='아카넷', authors=('백종현',), libs=(1, 2))])
        rows = [{'title': '실천이성비판', 'pinned_title': '실천이성비판: 흔들리는 시대', 'pinned_author': '칸트',
                 'pinned_publisher': '출판사', 'pinned_isbn13': '', 'pinned_by': 'collector'}]
        self.assertEqual(rl.choose_editions(rows, catalog, self.kinds, rl.Log(None)), {})
        for column in ('pinned_title', 'pinned_author', 'pinned_publisher', 'pinned_isbn13', 'pinned_by'):
            self.assertEqual(rows[0][column], '', column)

    def test_a_non_excluded_same_author_edition_is_chosen(self):  # R3-4: 실천이성비판, 칸트 named on the 아카넷 edition
        catalog = FakeCatalog([
            book(1, '실천이성비판', authors=('칸트', '번역AI'), names=('칸트', '통합언어인텔리전스기반번역AI'), libs=(1, 2, 3)),
            book(2, '실천이성비판', publisher='아카넷', authors=('칸트', '백종현'), libs=(1, 2))])
        rows = [{'title': '실천이성비판', 'pinned_title': '실천이성비판', 'pinned_publisher': '출판사', 'pinned_by': ''}]
        self.assertEqual(rl.choose_editions(rows, catalog, self.kinds, rl.Log(None)), {0: 2})
        self.assertEqual((rows[0]['pinned_publisher'], rows[0]['pinned_by']), ('아카넷', 'collector'))

    def test_every_volume_is_one_book_and_the_first_wins_a_tie(self):  # AC-7
        catalog = FakeCatalog([book(5, '탁류 3', libs=(1, 2)), book(6, '탁류 1', libs=(2, 3)), book(7, '탁류 2', libs=(1, 3))])
        rows = [{'title': '탁류', 'pinned_title': '탁류 3', 'pinned_by': ''}]
        self.assertEqual(rl.choose_editions(rows, catalog, self.kinds, rl.Log(None)), {0: 6})
        self.assertEqual(rows[0]['pinned_title'], '탁류 1')

    def test_no_edition_clears_the_fixed_one(self):
        rows = [{'title': '광장', 'pinned_title': '없는 판', 'pinned_by': 'collector'}]
        self.assertEqual(rl.choose_editions(rows, FakeCatalog([]), self.kinds, rl.Log(None)), {})
        self.assertEqual(rows[0]['pinned_title'], '')


    def test_a_work_title_finds_the_volumes_of_a_set(self):  # user 2026-10-20: the Bokklubben list names works
        catalog = FakeCatalog([book(1, '한밤의 아이들. 1', authors=('살만루슈디',), libs=(1, 2)),
                               book(2, '한밤의 아이들. 2', authors=('살만루슈디',), libs=(1, 2)),
                               book(3, '한밤의 아이들', authors=('다른사람',), libs=(1, 2, 3))])
        rows = [{'title': '한밤의 아이들', 'author': '살만 루슈디', 'pinned_by': ''}]
        self.assertEqual(rl.choose_editions(rows, catalog, self.kinds, rl.Log(None)), {0: 1})
        self.assertEqual((rows[0]['pinned_title'], rows[0]['pinned_by']), ('한밤의 아이들. 1', 'collector'))


class ListChoiceTests(unittest.TestCase):
    """--lists (user 2026-10-20): only the named lists' files are written."""

    def test_only_the_named_list_is_written(self):
        lists = ListsDir([lee_row('2026-06', 1, 'lMx0zEd1qtY', '히스토리아 비테이', field='science')])
        self.addCleanup(lists.close)
        nobel = {c: '' for c in rl.NOBEL_COLUMNS}
        nobel.update(field='ancient', position='1', title='일리아스', author='호메로스')
        rl.write_tsv(lists.path / 'nobel.tsv', rl.NOBEL_COLUMNS, [nobel])
        kaist = {c: '' for c in rl.KAIST_COLUMNS}
        kaist.update(field='nature', position='1', title='책', author='저자')
        rl.write_tsv(lists.path / 'kaist.tsv', rl.KAIST_COLUMNS, [kaist])
        crlf = (lists.path / 'kaist.tsv').read_bytes().replace(bytes([10]), bytes([13, 10]))
        (lists.path / 'kaist.tsv').write_bytes(crlf)
        before = {p.name: p.read_bytes() for p in lists.path.glob('*.tsv')}
        status = rl.run(lists.args(skip_youtube=True, lists='nobel'), http_opener=Opener({}), sleep=lambda s: None)
        self.assertEqual(status, 0)
        after = {p.name: p.read_bytes() for p in lists.path.glob('*.tsv')}
        for name in ('kaist.tsv', 'leedongjin.tsv', 'snu100.tsv'):
            self.assertEqual(after[name], before[name], name)

    def test_an_unknown_list_stops_before_anything(self):
        lists = ListsDir([])
        self.addCleanup(lists.close)
        status = rl.run(lists.args(skip_youtube=True, lists='nobel,other'), http_opener=Opener({}),
                        sleep=lambda s: None)
        self.assertEqual(status, 1)


class SameRuleAsJava(unittest.TestCase):
    """The one fixture Java's BookChoiceTests reads too (ADR-0124)."""

    def test_the_same_choice(self):
        data = json.loads((HERE / 'testdata' / 'book_choice_cases.json').read_text(encoding='utf-8'))
        kinds = {int(k): v for k, v in data['kinds'].items()}
        for case in data['cases']:
            books = [{'id': c['id'], 'title': c['title'], 'publisher': c['publisher'], 'library_ids': c['libraryIds']}
                     for c in case['candidates']]
            with self.subTest(case['name']):
                self.assertEqual(rl.choose(books, kinds)['id'], case['chosen'])

    def test_volumes_read_like_shown_title(self):
        for title, base, volume in [('토지. 1(1부 1권)', '토지', '1'), ('위대한 유산(상)', '위대한 유산', '상'),
                                    ('탁류 3', '탁류', '3'), ('화씨 451', '화씨 451', None), ('1984', '1984', None),
                                    ('백석의 사슴 오리지널판', '백석의 사슴', None), ('햄릿(Hamlet)', '햄릿', None),
                                    ('안나 카레니나 세트', '안나 카레니나', None),
                                    ('삼국지 02권', '삼국지', '2'),
                                    # plan matching-guide.md 2절 (2026-10-17)
                                    ('카라마조프가의 형제들. 1', '카라마조프가의 형제들', '1'),
                                    ('카라마조프가의 형제들 1 [한글판]', '카라마조프가의 형제들', '1'),
                                    ('카라마조프가의 형제들 2 - 세계문학전집 158', '카라마조프가의 형제들', '2'),
                                    ('카라마조프가의 형제들1', '카라마조프가의 형제들', '1'),
                                    ('토지 1부 1권', '토지', '1부 1권'), ('토지 2부 3권', '토지', '2부 3권'),
                                    ('블랙홀1984', '블랙홀1984', None), ('책 [1]', '책', '1')]:
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

    @staticmethod
    def holdings(*documents):
        return json.dumps({'result': [dict(zip(('titleInfo', 'authorInfo', 'imageUrl'), d)) for d in documents]}).encode()

    def test_a_book_we_do_not_have_gets_its_cover_from_the_nlk_holdings_not_kakao(self):  # user 2026-10-17
        from PIL import Image
        png = io.BytesIO()
        Image.new('RGB', (300, 450), (10, 20, 30)).save(png, 'PNG')
        lists = ListsDir([])
        self.addCleanup(lists.close)
        # an anthology with the same name comes first; the author picks the right one
        found = self.holdings(('<span class="searching_txt">광장</span>', '김사과 외', 'kolis/2019/OTHER.jpg'),
                              ('<span class="searching_txt">광장</span> : 최인훈 장편소설', '최인훈', 'kolis_on/2021/RIGHT.jpg'))
        opener = Opener({'www.nl.go.kr/NL/search/openApi/search.do': (200, found),
                         'cover.nl.go.kr/kolis_on/2021/RIGHT.jpg': (200, png.getvalue())})
        rows = [{'title': '광장', 'author': '최인훈', 'cover_url': ''}]
        http = rl.Http(rl.Log(None), [], sleep=lambda s: None, opener=opener)
        rl.fetch_covers(lists.path, rows, {}, None, None, rl.Seoji(http, 'k'), http, rl.Log(None), rl.KakaoBooks(http, 'k'))
        self.assertEqual(rows[0]['cover_url'], 'https://cover.nl.go.kr/kolis_on/2021/RIGHT.jpg')
        self.assertTrue(rl.cover_file(lists.path, rows[0]['cover_url']).exists())
        self.assertFalse([u for u in opener.urls if 'kakao' in u])

    def test_a_classic_whose_author_does_not_show_stays_a_title_card(self):  # no "same title by anyone"
        lists = ListsDir([])
        self.addCleanup(lists.close)
        found = self.holdings(('<span class="searching_txt">광장</span>', '김사과 외', 'kolis/2019/OTHER.jpg'))
        opener = Opener({'www.nl.go.kr/NL/search/openApi/search.do': (200, found),
                         'www.nl.go.kr/seoji': (200, json.dumps({'docs': []}).encode())})
        rows = [{'title': '광장', 'author': '최인훈', 'cover_url': ''}]
        http = rl.Http(rl.Log(None), [], sleep=lambda s: None, opener=opener)
        rl.fetch_covers(lists.path, rows, {}, None, None, rl.Seoji(http, 'k'), http, rl.Log(None))
        self.assertEqual(rows[0]['cover_url'], '')

    def test_placeholder_picture_is_skipped_for_the_next_one(self):  # NLK logo, Kyobo "no image" (2026-10-16)
        import dataclasses
        from unittest import mock
        from PIL import Image

        def picture(box):
            image = Image.new('RGB', (300, 450), (255, 255, 255))
            image.paste((0, 0, 0), box)
            out = io.BytesIO()
            image.save(out, 'PNG')
            return out.getvalue()
        no_image, real = picture((150, 0, 300, 450)), picture((0, 225, 300, 450))
        known = rl.image_hash(rl.shrink(no_image))
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(rl, '_rules', dataclasses.replace(rl.rules(), placeholders=(known,))).start()
        lists = ListsDir([])
        self.addCleanup(lists.close)
        found = self.holdings(('광장', '최인훈', 'kolis/1.jpg'), ('광장', '최인훈', 'kolis/2.jpg'))
        opener = Opener({'www.nl.go.kr/NL/search/openApi/search.do': (200, found),
                         'cover.nl.go.kr/kolis/1.jpg': (200, no_image), 'cover.nl.go.kr/kolis/2.jpg': (200, real)})
        rows = [{'title': '광장', 'author': '최인훈', 'cover_url': ''}]
        http = rl.Http(rl.Log(None), [], sleep=lambda s: None, opener=opener)
        rl.fetch_covers(lists.path, rows, {}, None, None, rl.Seoji(http, 'k'), http, rl.Log(None))
        self.assertEqual(rows[0]['cover_url'], 'https://cover.nl.go.kr/kolis/2.jpg')
        self.assertFalse(rl.cover_file(lists.path, 'https://cover.nl.go.kr/kolis/1.jpg').exists())

    def test_stale_catalog_cover_is_taken_again_when_the_edition_changed(self):  # 「토지」 volume 2 → 1
        import sqlite3
        from PIL import Image
        png = io.BytesIO()
        Image.new('RGB', (300, 450), (10, 20, 30)).save(png, 'PNG')
        lists = ListsDir([])
        self.addCleanup(lists.close)
        old = 'https://image.yes24.com/goods/2/M'
        path = rl.cover_file(lists.path, old)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(rl.shrink(png.getvalue()))
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE holding (library_id INTEGER, item_key TEXT, cover_url TEXT)')
        db.execute("INSERT INTO holding VALUES (2, 'b', 'https://image.yes24.com/goods/1/M')")

        class Catalog:
            def holding_keys(self, book_id):
                return [(2, 'b')] if book_id == 1780 else []
        rows = [{'title': '토지', 'author': '박경리', 'cover_url': old}]
        http = rl.Http(rl.Log(None), [], sleep=lambda s: None,
                       opener=Opener({'image.yes24.com/goods/1/M': (200, png.getvalue())}))
        rl.fetch_covers(lists.path, rows, {0: 1780}, Catalog(), db, None, http, rl.Log(None), None)
        self.assertEqual(rows[0]['cover_url'], 'https://image.yes24.com/goods/1/M')

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
