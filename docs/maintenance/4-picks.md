# 추천도서: 사서추천도서 받기와 이동진 추천도서·서울대 100선 목록 (사이클 14, 21)

- 사이클 14(사이클 10 인기 도서를 대체). 결정과 근거는 plan ADR-0089~0092와 `docs/cycles/14-librarian-picks/`에 있고, 여기에는 운영 절차만 둡니다.
- 하는 일: 국립중앙도서관 사서추천도서 Open API(`saseoApi.do`)에서 최근 3년 등록분을 요청 1건으로 받아, 추천 연월 최근 12개 달(12번 추천)의 책만 `picks` 스키마에 저장합니다. 새 표지만 받아 폭 200px JPEG로 줄여 저장합니다. 화면은 `/picks/librarian`, API는 `GET /api/v1/picks/librarian`(사이클 21부터 목록마다 주소가 따로입니다: `librarian`·`leedongjin`·`snu100`, ADR-0126). 사이클 21부터 저장 표는 `picks.list_book`(사서 줄은 `list_key = 'librarian'`)과 `picks.list_field`입니다(ADR-0122). 이동진 추천도서·서울대 100선은 6절.

## 1. 켜고 끄기, 인증키

- 설정 `chaekcheck.picks.fetch.enabled`, 환경 변수 `CHAEKCHECK_PICKS_FETCH_ENABLED`. 기본값 true(운영)
- 꺼 두는 곳: 로컬 `.env`(`false`), 테스트(`application-test.yml`), `collection-import` 프로필
- 인증키: 환경 변수 `CHAEKCHECK_PICKS_NLK_API_KEY`(설정 `chaekcheck.picks.fetch.api-key`). **비밀값**입니다. 로컬은 `.env`(git 밖)에 사람이 직접 넣고, 운영은 배포 비밀값으로 넣습니다. 코드·설정 파일·`.env.example`·문서·커밋·로그에 값을 적지 않습니다
  - 키가 비어 있으면 요청을 보내지 않고 `fetch_run`에 `FAILED`/`key_missing` 한 줄과 WARN 로그 한 줄을 남깁니다
  - 키는 요청 주소(`key=`)에 들어가므로 로그에는 `key=***`로만 찍힙니다
- 로컬에서 받기를 시험할 때만 켭니다(사용자 승인, 하루 최대 1번). 한 번 받으면 목록 요청 1건 + 새 표지(처음엔 약 108장, 그 뒤엔 새 추천분만), 표지는 1초 이상 간격
- 꺼도 공용 캐시 10분 새로 고침은 돕니다(저장된 목록을 화면에 보여 줌)

## 2. 언제 받나 (ADR-0092)

- 서버가 켜질 때 한 번, 그리고 매일 05:00(Asia/Seoul)에 "받을지"를 따집니다(`FetchDecision`). 하나라도 맞으면 받습니다
  1. 오늘 이미 성공했으면 받지 않음
  2. 저장된 목록이 없음 → 시작 때 `STARTUP`, 05:00이면 `CATCH_UP`
  3. 05:00이고 월요일 → `SCHEDULED`
  4. 05:00이고 마지막 받기가 `FAILED`(그 뒤 성공 없음) → `RETRY`. 성공할 때까지 매일
  5. 마지막 성공이 7일보다 오래됨(서버가 꺼져 월요일을 놓침) → `CATCH_UP`(시작 때나 05:00)
- 서버 시작 때는 2·5만 봅니다(실패 뒤 다시 받기는 05:00에만). 관리자 다시 받기 명령은 없습니다(이번 범위 밖)

## 3. 받기 기록 읽기 (`picks.fetch_run`)

```sql
SELECT id, trigger, status, failure_reason, failure_detail, changed, started_at, finished_at, request_count,
       received_count, kept_count, missing_isbn_count, new_category_codes, cover_fetched_count, cover_failed_count
FROM picks.fetch_run ORDER BY started_at DESC LIMIT 10;
```

| `trigger` | 뜻 |
|---|---|
| `STARTUP` | 저장된 목록 없이 서버가 켜짐 |
| `SCHEDULED` | 월요일 05:00 |
| `RETRY` | 앞 받기가 실패해서 다음 날 05:00에 다시 |
| `CATCH_UP` | 마지막 성공이 7일보다 오래됨, 또는 05:00에 목록이 없음 |

| `failure_reason` | 뜻 | 할 일 |
|---|---|---|
| `key_missing` | 인증키가 설정되지 않음(요청 안 보냄) | 환경 변수 `CHAEKCHECK_PICKS_NLK_API_KEY` 확인 |
| `network_error` | 연결 실패, 시간 초과(연결 5초, 읽기 10초) | 계속되면 국립중앙도서관 사이트 상태 확인 |
| `request_rejected` | 200이 아님(3xx 포함, redirect 안 따름), 또는 오류 코드 요소가 옴(`failure_detail` `API error <코드>`) | 키가 거절됐는지, 주소가 바뀌었는지 확인 |
| `format_changed` | `<channel>`·`<totalCount>` 없음, `recomNo`·제목·분야 코드·추천 연월이 틀림, 본문 10MB 넘음 | 응답 모양이 바뀌었는지 확인(`SaseoXmlParser`) |
| `incomplete` | `totalCount`가 받은 수보다 큼 | `end-row`(1000)보다 많아졌는지 확인 |
| `no_books` | 최근 12개 달에 책이 0권 | 사이트 쪽 문제일 수 있음. 다음 날 다시 받음 |

- `FAILED`면 목록은 바뀌지 않고 앞 목록이 그대로 보입니다. `failure_detail`에는 HTTP 상태나 오류 코드만 있고 키·주소·본문은 없습니다
- `SUCCEEDED`이고 `changed=false`면 받은 목록이 저장된 것과 같아서 아무것도 바꾸지 않았다는 뜻입니다(표지도 받지 않음)
- `new_category_codes`에 값이 있으면 모르는 분야 코드가 왔다는 뜻입니다. 화면에는 "전체"와 맨 뒤 새 분류(이름은 API의 `drCodeName`, 없으면 "기타")로 나옵니다
- 요청마다 INFO 로그 `NLK GET <경로, key=***> -> <상태> in <ms>ms, <바이트> bytes, <간격>ms after the request before`. 실패는 `log.error`

## 4. 표지

- `recomfilepath`를 https로 바꾼 뒤 호스트가 `www.nl.go.kr`이고 경로가 `/afile/`로 시작하는 것만 받습니다. redirect는 따르지 않습니다
- 받는 크기 상한 5MB, 픽셀 수 상한 1,600만, `Content-Type`이 `image/`여야 함(아니면 실패로 셈). 받자마자 폭 200px JPEG(품질 0.8)로 줄여 `picks.cover`에 저장
- 목록이 바뀐 받기(`changed=true`)에서만 받고, 저장 안 된 표지만 받습니다. 끝나면 지금 목록이 쓰지 않는 표지를 지웁니다
- 실패한 표지는 다음에 목록이 바뀔 때 다시 받습니다(목록이 같은 주에는 다시 받지 않음)
- TODO(확인 필요): 배포 전 국립중앙도서관 표지 이용 조건 확인(공식 문서 / 문의 정보기술기반과 02-590-0727)

### 권리자가 표지를 내려 달라고 할 때

화면 `/about`의 연락처로 요청이 오면 그 책의 표지만 지웁니다. 그 책은 제목 글자 카드로 보입니다.

```sql
-- 1) 그 책의 표지 주소 찾기 (ISBN 또는 제목)
SELECT list_key, source_no, isbn13, title, cover_source FROM picks.list_book WHERE isbn13 = '<ISBN>';
-- 2) 목록에서 주소를 비우고 표지를 지움 (한 트랜잭션)
BEGIN;
UPDATE picks.list_book SET cover_source = NULL WHERE cover_source = '<cover_source>';
DELETE FROM picks.cover WHERE source_url = '<cover_source>';
COMMIT;
```

- 공용 캐시가 10분 안에 새로 고쳐져 화면에서 사라집니다. 브라우저는 표지를 30일 저장(`immutable`)하므로 이미 본 사람에게는 남아 있을 수 있습니다
- 이동진·서울대 목록의 표지는 다음 가져오기 반영 때 다시 들어옵니다. 그 책의 `scripts/crawl/lists/*.tsv` 줄에서 `cover_url`을 비우고 `covers/`의 파일을 지운 뒤 커밋해야 다시 들어오지 않습니다(6절)
- 다음에 목록이 바뀌면 같은 책 표지를 다시 받을 수 있으므로, 요청받은 표지는 따로 적어 두고 받기 뒤에 다시 지웁니다 [안: 막는 목록은 아직 없음]
- 요청과 처리 날짜는 `docs/plan-feedback.md`에 남깁니다

## 5. 맞추기 (우리 책과 잇기)

- 사서 줄은 저장하지 않고 공용 캐시를 만들 때 맞춥니다(ADR-0090): ① ISBN(`BookLookup.byIsbn13`) ② ①로 못 맞은 책만 본제목 + 첫 저자(`BookLookup.byTitleAndAuthor`). 후보가 여럿이면 `BookChoice`(사이클 21, ADR-0125): 켜진 도서관만 세고 구독형은 몇 곳이든 1, 여러 권짜리는 같은 출판사 세트를 한 판으로 보아 가장 많이 가진 권의 수로 세고 그 세트의 1권을 씀, 같으면 책 번호가 작은 책. 규칙은 [rules-index.md](rules-index.md) 4절
- 이동진·서울대 줄은 가져오기 export 때 맞춘 책 번호(`list_book.book_id`)를 저장하고, 캐시는 그 번호로 읽기만 합니다(ADR-0124, 6절)
- 가져오기(3-import)로 책 번호가 바뀌어도 다음 캐시 새로 고침(10분 안)에 다시 맞춰집니다

## 6. 이동진 추천도서·서울대 100선 (사이클 21)

결정과 근거는 plan `docs/cycles/21-recommended-books/`(ADR-0122~0129)에 있습니다. 여기에는 다른 세션이 이 문서만 읽고 돌리고 고칠 수 있게 절차만 둡니다.

### 6-1. 흐름 한눈에

1. **수집기**(내 PC, 사람이 돌림): `scripts/crawl/recommended_lists.py`가 목록 파일 `scripts/crawl/lists/`를 고칩니다(YouTube 확인, 판 고르기, 표지). 새 책은 바로 목록에 들어가지 않고 확인 필요 파일에 들어갑니다
2. **사람**: 확인 필요 파일을 보고 분야를 정해 `leedongjin.tsv`로 옮깁니다(6-4). 목록 파일을 커밋합니다
3. **가져오기 export**(내 PC, [3-import.md](3-import.md) 13절): 목록 폴더를 검사하고(없거나 틀리면 사본을 읽기 전에 멈춤), 새 책 표에 맞춰 반영 파일의 `notes.listPart`(파일 형식 번호 3)에 싣습니다
4. **apply / finish / in-place**: 책 표를 바꿔 끼운 **뒤** 작은 트랜잭션 하나로 이동진·서울대 줄을 바꿉니다. 목록 넣기만 실패하면 책 표는 바뀌고 옛 목록이 남으며, 가져오기 줄의 `step_seconds`에 `lists failed`가 남습니다(로그 ERROR)
5. **화면**: 서버를 다시 켜지 않아도 공용 캐시 새로 고침(10분 안)에 나옵니다

사서추천도서는 이 길을 타지 않고 지금처럼 서버가 받습니다(1~5절).

### 6-2. 수집기 돌리기

- 언제: 다른 수집과 같이 사람이 돌릴 때, 지난 갱신 뒤 한 달이 넘었으면 함께 돌립니다(spec R4-6). YouTube 30일 규칙 때문에 한 달을 넘기지 않습니다. export가 30일 넘게 확인 못 한 이동진 줄을 경고합니다(로그 WARN `not checked since`)
- 준비
  - Python 의존성: `pip install -r scripts/crawl/requirements.txt`(`psycopg[binary]`, `Pillow` 포함)
  - `.env`(저장소 뿌리, git 밖)의 `CHAEKCHECK_YOUTUBE_API_KEY`(YouTube Data API v3)·`CHAEKCHECK_PICKS_NLK_API_KEY`(국립중앙도서관 키, ISBN 서지에도 씀)·`DB_*`(내 PC DB). 키 값은 어디에도 적지 않습니다
  - 수집 사본(`chaekcheck-data/snapshots/…sqlite`): 표지 주소(`holding.cover_url`)를 읽습니다
- 명령(저장소 뿌리에서)

```bash
python scripts/crawl/recommended_lists.py --sqlite ../chaekcheck-data/snapshots/<사본>.sqlite
```

  - 고를 수 있는 것: `--db-port <포트>`(내 PC DB가 `.env`의 `DB_PORT`와 다를 때), `--skip-youtube`·`--skip-editions`·`--skip-covers`(한 부분만 다시 할 때), `--lists-dir`, `--log`
  - 걸리는 시간(2026-10-16): YouTube 요청 약 15번(할당량 15단위, 하루 10,000), 새 표지가 없으면 1분 안팎. 처음 표지 122장은 약 3분(호스트마다 1초 간격)
- 입력: `scripts/crawl/lists/`의 목록 파일과 규칙 파일([README](../../scripts/crawl/lists/README.md)), 내 PC DB(`catalog.book`·`book_contributor`·`holding`, `library.library`, `collection.collection_link`, 읽기만), 수집 사본
- 출력
  - `leedongjin.tsv`·`snu100.tsv`(고정 판 `pinned_*`, `cover_url`, `checked_on`)
  - `leedongjin_pending.tsv`(확인 필요), `covers/<sha1(cover_url)>.jpg`(폭 200px JPEG, 쓰지 않는 파일은 지움)
  - 기록 `data/recommended-lists/collect.log`(git 밖): 요청마다 `GET 호스트 경로`(키 없음), 지운 영상, 새 책, 판을 못 고른 줄, 표지 실패, 끝에 `hosts asked: [...]`
- 확인
  - 기록 끝의 `hosts asked`에 `www.youtube.com`이 없어야 합니다(약관, AC-12). YouTube는 `www.googleapis.com`만 부릅니다
  - `editions: leedongjin N/43, snu100 N/100`: 2026-10-16 기준 35/43·87/100. 크게 줄었으면 내 PC DB가 비었거나 다른 DB에 붙은 것입니다
  - `git diff scripts/crawl/lists`로 바뀐 줄을 보고 커밋합니다(표지 파일 포함)
- 실패: YouTube·국립중앙도서관 API가 키를 거절하거나 할당량을 넘거나 답이 없으면 목록 파일을 건드리지 않고 기록에 `youtube: failed …` 한 줄을 남기고 끝납니다(종료 코드 1). DB에 못 붙으면 고정 판을 그대로 두고 `editions: database failed` 한 줄. 규칙 파일이 없거나 틀리면 아무 요청도 하지 않고 멈춥니다
- 시험(네트워크·DB 없음): `cd scripts/crawl && python -m unittest test_recommended_lists`

### 6-3. 판 고르기와 표지 (수집기가 하는 일)

- 같은 책: 시작 판(지금 고정 판, 없으면 줄의 ISBN, 없으면 줄의 제목 + 첫 저자로 내 PC DB에서 찾은 책)과 `main_title_norm`이 같거나 **권 번호를 덜어 낸 본제목**이 같고, 저자(`name_exact`)를 하나 이상 공유하는 판
- 거르기: `collector_words.tsv`의 `excluded_*` 낱말(요약·어린이·기계 번역 판, spec R3-3·R3-4). 다 걸러지면 고정 판을 비웁니다(저자 무관으로 다시 고르지 않음, plan 21 회고 결정 #2 2026-10-17). 그 줄은 export가 줄의 제목 + 저자로 다시 찾고, 그래도 못 맞춤이면 못 맞춘 책으로 둡니다 → 바른 판이 카탈로그에 있으면 지침(plan `matching-guide.md` 4절)대로 사람이 `manual`로 고정합니다. 기록에 `edition: every same-author edition excluded for …` 한 줄이 남습니다
- 고르기: Java `BookChoice`와 같은 공식(rules-index 4절 "판 고르기"). 같은 시험 자료 `scripts/crawl/testdata/book_choice_cases.json`을 Java·Python 시험이 함께 읽습니다
- `pinned_by = manual` 줄은 덮어쓰지 않습니다. 사람이 판을 정하려면 `pinned_title`·`pinned_author`·`pinned_publisher`(필요하면 `pinned_isbn13`)를 적고 `pinned_by`에 `manual`, `note`에 까닭과 날짜를 적습니다
- 표지는 모든 줄에 찾습니다. 우리 책을 못 맞춘 줄도 인터넷에서 찾습니다(사용자 2026-10-16: "책 표지 무조건 다 보였으면 해 인터넷 수집해서(어디서 수집해야 하는지는 기록 다 해)채워")
- 표지 차례는 `cover_sources.tsv`(rules-index 4절 "표지 출처 차례"): ① 고정 판 ISBN의 국립중앙도서관 ISBN 서지 표지 ② 수집 사본의 그 책 소장 줄 표지 주소 가운데 `cover_hosts.tsv` 차례(YES24, 교보) ③ 수집 사본의 그 밖의 주소 ④ 국립중앙도서관 소장자료 검색(`https://www.nl.go.kr/NL/search/openApi/search.do`, ISBN 서지와 같은 키 `CHAEKCHECK_PICKS_NLK_API_KEY`): ISBN, 없으면 제목 + 첫 저자의 성. 그림은 `https://cover.nl.go.kr/` + `imageUrl` ⑤ ISBN 서지 제목 검색. 출처마다 5개까지 시도하고, 다 안 되면 표지 없이(화면은 제목 글자 표지). 원본 5MB가 넘으면 받지 않습니다
- 빈 표지 그림(국립중앙도서관 로고, 교보 「제공된 상품이미지가 없습니다」)은 `cover_placeholders.tsv`의 해시와 견주어 건너뜁니다
- 목록 제목이 한 책이 아니면 `cover_title` 칸의 제목으로 찾습니다(「근현대사 4부작」 → 「혁명의 시대」)
- 고정 판이 바뀌면 수집 사본 표지를 다시 받습니다(「토지」 2권 → 1권). 국립중앙도서관 표지는 그대로 둡니다
- 네이버 책 검색은 쓰지 않습니다: 지금 키가 401을 돌려줌(2026-10-16)
- 카카오 책 검색은 쓰지 않습니다(2026-10-17부터): 썸네일을 오래 저장하면 안 된다는 운영자 답변(plan `docs/research/popular-books/cover-copyright.md` 5절). 사용자 "도서관정보나루라던가 아니면 공공도서관 api라던가 출처 많자나 … 그걸로 다시 조사하고 반영해봐". 도서관 정보나루는 저장 금지·비영리 표시만이고 ADR-0072로 쓰지 않습니다. 카카오에서 받았던 18장(이동진 6, 서울대 12)을 국립중앙도서관 소장자료 검색으로 다시 받았습니다
- 2026-10-17 결과: 이동진 43/43, 사서 108/108, 서울대 97/100. 「가족이라는 사치」는 국립중앙도서관 소장 기록에 그림이 없어 김영사 공식 도서 페이지(https://www.gimmyoung.com/book/books/view/book/10685)의 표지를 손으로 받음(사용자 2026-10-17 "「가족이라는 사치」만 김영사 공식 페이지"). `leedongjin.tsv` `note`에 출처를 적었고, 수집기는 파일이 있는 표지를 다시 받지 않음(`OUTSIDE_COVER_HOSTS`에 `www.gimmyoung.com`). 표지 없는 3권(제목 글자 표지): 「변신인형」(같은 제목의 판에는 그림이 없고 「변신하는 인형」에만 있음), 「보조법어」(결과에 저자 지눌이 보이지 않음), 「제자백가 선독」(그 이름의 책이 없음. plan 21: spec R2-4대로 못 찾은 책, 다른 책의 표지를 붙이지 않음)
- 손으로 표지를 넣는 법: 표지를 받아 수집기 `shrink()`로 줄여 `covers/<sha1(cover_url)>.jpg`로 두고, 그 줄의 `cover_url`과 `note`(출처·날짜)를 적은 뒤, 출처 호스트를 `OUTSIDE_COVER_HOSTS`에 더합니다
- TODO(확인 필요): 배포 전 국립중앙도서관 소장자료 검색 표지(`cover.nl.go.kr`)를 화면에 써도 되는지. ISBN 서지 표지 확인(4절, 인계서 #17)과 같이 합니다

### 6-4. 확인 필요 파일 다루기 (`leedongjin_pending.tsv`)

새 영상의 책은 사람이 분야를 정해야 목록에 들어갑니다(사용자 결정, 2026-10-16). 한 줄 = 영상 하나의 책 이름 하나입니다.

| `reason` | 뜻 | 할 일 |
|---|---|---|
| (빈 값) | ISBN 서지에서 이름이 같은 책 하나를 찾음 | `field_suggestion`을 참고해 분야를 정하고 아래처럼 옮깁니다 |
| `many_books` | 이름이 같은 책이 여럿(판이 여럿이거나 다른 책) | 영상을 보고 맞는 책의 ISBN을 고릅니다 |
| `not_found` | ISBN 서지에 없음. 영화·음악 이름이 설명란 괄호에 든 경우가 많음 | 책이 아니면 줄을 지웁니다. 책이면 ISBN을 찾아 적습니다 |
| `no_book_name` | 제목·설명에 책 이름 표시가 없음 | 영상을 보고 책을 알아내 적거나, 책 영상이 아니면 줄을 지웁니다 |

옮기는 법:
1. `leedongjin.tsv`에 줄을 더합니다: `month`(pending의 `month`), `position`(그 달 안 소개 순서), `video_id`, `field`(사람이 정함, `fields.tsv`의 `leedongjin` 코드), `title`·`author`·`publisher`·`isbn13`, `checked_on`(오늘). 올해의 책 영상이면 `year_book`에 그해
2. 같은 분야에 같은 책이 이미 있으면 새 줄 하나만 남깁니다(가장 최근 추천, spec R3-5). 이달의 책이 그해 올해의 책에 다시 나오면 그해 12월 줄로 옮깁니다(수집기가 이미 목록에 있는 책은 자동으로 옮김)
3. pending 파일에서 그 줄을 지웁니다. 영상 하나의 줄을 모두 정리하면 다음 수집 때 그 영상은 다시 들어오지 않습니다(목록 파일에 그 영상 ID가 있으므로). 책이 아니어서 지운 영상은 다음 수집 때 다시 들어오므로, 그런 영상은 `reason`을 `not_a_book`으로 바꿔 남겨 둡니다 [안]
4. 수집기를 `--skip-youtube`로 한 번 돌려 판과 표지를 채우고 커밋합니다

### 6-5. 화면과 확인

- 화면 `/picks/leedongjin`, `/picks/librarian`, `/picks/snu100`(`/picks`는 `/picks/librarian`으로 넘어감). 머리말 「추천도서」 메뉴
- 반영 뒤 확인: `GET /api/v1/picks/<목록>`의 `categories[0].books` 수와 `bookId`가 있는 수(2026-10-16 c21 복사본: 서울대 87/100, 이동진 36/43, 사서 72/108)
- DB에서: `SELECT list_key, count(*), count(book_id) FROM picks.list_book GROUP BY 1`

## 7. KAIST 도서관 추천도서 (2026-10-19 사용자 요청)

- 요청: "이동진 추천 빼고 여기 넣어줘" — 화면의 「이동진 추천」 탭 자리에 KAIST 도서관 목록을 넣음. 이동진 목록 자료(seed·파일)는 지우지 않고 화면에서만 뺄 예정.
- 출처: https://library.kaist.ac.kr/search/ctlgSearch/collections/specialCollection.do (KAIST 도서관 > 소장자료검색 > Collections > 추천도서). 페이지 글: "사서추천도서, 스페셜도서, 독서문화위원회 추천도서를 확인할 수 있습니다."
- 페이지 구성(2026-10-19 확인): 탭 「사서추천도서」 「스페셜도서」 「독서문화위원회」, 해마다 목록(2026 · 2025 · 2023 · 2022 · ~2021). 주소가 가리키는 「스페셜도서」 2026은 주제 4개 × 20권: 자연이 우리에게 주는 메시지(20) · AI가 우리에게 주는 메시지(20) · 문화가 우리에게 주는 메시지(20) · AI대학(20). 각 주제에 "검색결과 더보기"(전체 목록)와 "Holdings" 링크가 있음.
- 사용자 결정(2026-10-19): 「스페셜도서」 2026 네 주제("스페셜도서 책"), 그 뒤 「AI대학」은 뺌("kaist 추천에서 AI대학 이건 빼자", 영문 원서가 많아 우리 책과 0/20) → 세 주제 60권, 화면 탭 이름 "KAIST 추천", 탭 순서 사서 추천 → KAIST 추천 → 서울대 100선("사서 추천을 맨 앞에 그 다음에 카이스추천 서울대 추천").
- 이용 조건 확인(2026-10-19): `https://library.kaist.ac.kr/robots.txt`는 없음(404, 사이트 오류 페이지). 페이지 아래에 이용 약관 링크는 없고 "Copyright (C) 2019 KAIST Library All Rights Reserved"와 개인정보처리방침만 있음. 목록의 서명·저자·출판사·ISBN은 사실 정보로 보고 쓰고, 책 소개 글(교보문고 제공)과 표지 그림은 가져오지 않음.
- 읽는 길(2026-10-19 확인, 모두 GET, 로그인 없음)
  - 해 목록: `/search/ctlgSearch/collections/specialCollection.do?bookTy=sc2026` — 주제마다 앞 5권과 주제 코드(`fn_recommendList('sc15')` … `sc18`)
  - 주제 전체: `/search/ctlgSearch/collections/specialCollectionList.do?bookCl=20&bookTy=sc2026&bookTyLower=<sc15~sc18>` — 20권의 추천 번호(`fn_recommendView('<번호>')`), 소장 번호(`fn_searchView('<bibctrlno>')`), 표지 alt의 서명
  - 책 한 권: `/search/ctlgSearch/collections/recommendView.do?recomendBookNo=<번호>&bookCl=20&bookTy=sc2026` — 서명, 저자, 출판사, 출판년, ISBN, 가격, 책소개
- 수집 방법: 해 목록 1번 + 주제 4번 + 책 80번 = 85번, 책 페이지는 2초 간격(약 3분). 스크립트는 내 PC 임시 폴더에서 돌렸고, 결과 표는 아래 파일로 남김.
- 넣는 방법(6절과 같은 길): 목록 파일(제목·저자·출판사·ISBN·주제)을 만들고 `CuratedListTransfer`로 내보내기 → 운영에 적용. 맞추기는 5절·ADR-0124 규칙 그대로(ISBN → 제목+첫 저자+출판사).
- 표지: KAIST 사이트 표지는 쓰지 않음(우리 책 목록 표지 또는 제목 표지). 목록 글(서명·저자)은 사실 정보로 보지만, 수집 전에 사이트 이용 조건·robots.txt를 확인해 이 절에 적을 것. 수집은 페이지를 몇 번만 읽는 정도로(도서관 트래픽 최소).
- 수집 결과(2026-10-19): 85번 읽음, 80권(주제마다 20), 80권 모두 ISBN 있음. 내 PC 책 표와 ISBN이 같은 책 25권(25권 모두 소장 줄 있음, 제목·저자 맞추기 전). 「AI대학」 주제는 영문 원서가 많음(예 "Applied mathematics…", "Applied machine learning in…").
- 남긴 파일(git 밖): `data/recommended-lists/kaist/kaist-sc2026.tsv`(칸: theme_code, theme, recomend_no, bibctrlno, title, author, publisher, year, isbn), 수집 스크립트 `data/recommended-lists/kaist/collect.py`(목록 HTML이 같은 폴더에 있어야 함). 운영에 넣는 길은 아래.
- 사용자 결정(2026-10-19): "지금 화면 바뀐 것들은 먼저 v1.4.0으로 배포하고(이동진 탭은 잠시 그대로), KAIST 추천은 목록만 운영에 넣는 방법까지 만들어서 다음에 따로 배포".
- 목록 파일 `scripts/crawl/lists/kaist.tsv`(80줄, 「AI대학」 20줄을 뺀 뒤 60줄): 칸 `field, position, title, author, publisher, isbn13, cover_url, pinned_*, pinned_by, note`. `field`는 주제 코드 `nature`(자연이 우리에게 주는 메시지)·`ai`(AI가 우리에게 주는 메시지)·`culture`(문화가 우리에게 주는 메시지), 화면 칩 이름은 짧게 자연·AI·문화(`fields.tsv`의 `kaist` 줄; `ai_college`(AI대학)는 사용자 결정으로 뺌). `title`은 KAIST 서명에서 부제(" : ", " = " 뒤)를 뗀 것, `author`는 첫 저자, `note`에 KAIST 주제와 추천번호.
- 수집기 `scripts/crawl/recommended_lists.py`가 `kaist.tsv`도 서울대처럼 고정 판·표지를 고름. 2026-10-19 첫 실행(`--skip-youtube`, 사본 `ebook-catalog-2026-10-12-0135.sqlite`): 고정 판 27/80, 표지 53장(`covers/`). 「AI대학」의 영문 원서는 우리 책 표에 거의 없음(제목 표지로 보임).
- 화면: 탭 순서 사서 추천 → KAIST 추천 → 서울대 100선. 이동진 목록은 탭에서만 빼고(주소 `/picks/leedongjin`은 그대로), 자료는 지우지 않음.
- 운영 반영: 책 표 가져오기 없이 목록만 넣는 `lists` 모드([3-import.md](3-import.md) 14절: PC 명령, EC2 일회용 컨테이너 명령). 폴더의 세 목록(이동진·서울대·KAIST)을 통째로 바꿔 넣으므로 목록 폴더 전체가 있어야 함. 2026-10-19 내 PC `db`에서 돌려 kaist 80줄 중 29권 맞춤(자연 10·AI 9·문화 10·AI대학 0). 목록 키 `kaist`는 새 Liquibase 변경으로 picks·usagelog 검사 제약에 더함.

### 7-2. 사서추천도서로 바꿈 (2026-10-20)

- 사용자: "카이스트말이야 몇 년도 추천인지 이 창에서 나오도록 하고 사서추천도서로 바꿔줘 그리고 분류는 너가 판단했을 때 적절하게 해줘 스페셜 도서는 결과가 너무 없어", "카이스트는 2026, 2025, 2024 다 해줘". 탭 이름은 "국립중앙도서관"·"KAIST"("사서 추천 다 빼고 국립중앙도서관 / KAIST 로 바꿔줘"). 위 「스페셜도서」(7절 앞부분)와 「AI대학」 빼기는 이것으로 대신함.
- 출처: KAIST 도서관 소장자료검색 > Collections > 추천도서 > 「사서추천도서」 `https://library.kaist.ac.kr/search/ctlgSearch/collections/recommend.do` (`bookCl=2`, `bookTy=ad`). 그룹은 「국내도서」 하나(`bookTyLower=ad2`, 전체 15,120권), 해 목록 버튼은 2026 · 2025 · 2024 · 전체.
- 읽는 길(2026-10-20 확인, 모두 GET, 로그인 없음)
  - 해 목록: `/search/ctlgSearch/collections/recommendList.do?bookCl=2&bookTy=ad&bookTyLower=ad2&registDt=<해>&pageIndex=<쪽>` — 한 쪽 20권(추천 번호 `fn_recommendView('<번호>')`, 소장 번호 `fn_searchView('<bibctrlno>')`), 맨 위 권수
  - 책 한 권: `/search/ctlgSearch/collections/recommendView.do?recomendBookNo=<번호>&bookCl=2&bookTy=ad` — 서명, 저자, 출판사, 출판년, ISBN(책소개는 저장하지 않음)
- 수집(2026-10-20): 해 목록 125쪽 + 책 2,481쪽 = 2,606번, 같은 호스트 2초 간격(약 1시간 40분), UA "chaekcheck list check". 권수 2026 482 · 2025 979 · 2024 1,020 = 2,481권, ISBN 없는 책 2권, 두 해에 걸쳐 추천된 책 약 30권(해마다 한 줄씩 둠).
- 분류(사용자: "분류는 너가 판단했을 때 적절하게"): 국립중앙도서관 ISBN 서지 API의 `SUBJECT`(KDC 주류)와 부가기호 끝 세 자리로 나눔 — 8 문학 · 1·2·7·9 인문 · 3 사회 · 4 과학 · 5(의학·요리 포함)와 000번대(컴퓨터·AI) 기술 · 6 예술. 서지에 주제가 없던 25권은 서명·저자로 손으로 정함(`fallback.json`). 결과: 문학 879 · 인문 651 · 사회 515 · 기술 187 · 과학 148 · 예술 101.
- 목록 파일 `kaist.tsv`: 칸 `year, field, position, title, author, publisher, isbn13, cover_url, pinned_*, pinned_by, note`. `year`는 추천 해(DB `year_book`에 둠, Liquibase `picks-004-kaist-librarian-years`), `position`은 그 해 KAIST 목록 순서(최근 추천 먼저). 화면은 최근 해부터, 해 안은 KAIST 순서. 책 창에 "2025년 KAIST 사서추천" 한 줄.
- 서명: KAIST 페이지는 서명 안의 `<…>`를 그대로 두어(예 "<카모메 식당>의 저자") 태그를 지운 글로 읽으면 깨짐 — 책 페이지의 `<div class="rd_txt_cont"><p>…</p>`를 그대로 읽음(깨졌던 2권: 9791156162940, 9791141108601). `kaist.tsv`의 서명은 부제(" : ", " = " 뒤)를 떼고 큰따옴표를 뺌(따옴표로 읽는 CSV 도구가 줄을 합치지 않게).
- 수집기(2026-10-20, `--skip-youtube`, 사본 `ebook-catalog-2026-10-12-0135.sqlite`): 고정 판 1,677/2,481, 표지 2,310장(약 3시간, 첫 실행은 표지 찾기 한 곳의 예외와 콘솔 문자 코드(cp949)로 멈춰 수집기를 고침: 찾기 실패는 그 출처만 건너뜀, 콘솔에 못 쓰는 글자는 ?로). 스페셜도서 때의 표지 48장은 지움.
- 내 PC `db`에 `lists` 모드(2026-10-20): kaist 2,481줄 중 1,707권 맞춤, 표지 2,249장 넣음(20 fields, 2,624 books). 운영 반영은 ux4와 함께 다음 배포(v1.7.0) 때 3-import.md 14절로.
- 남긴 파일(git 밖, `data/recommended-lists/kaist/`): `kaist-librarian.tsv`(수집 원본), `subjects.tsv`(ISBN별 주제·부가기호), 스크립트 `collect.py`·`classify.py`·`build_list.py`, `fallback.json`.

## 8. AI 요약 (2026-10-19~20 사용자 요청)

- 요청: "너 하이쿠 모델 써서 추천도서의 모든 책들 ai 추천 요약글 한두줄만 다 써줄래? 물론 짧게 ai가 요약한거라고 어디에 누르면 알려주게 하고"(그림 안 B, "하이쿠라고 알려주지는 마"), 2026-10-20 "왜 일부 책들은 AI요약이 안 나오냐... 다 나오도록 해 하이쿠로 쓰고", "내용 이상하지 않나 다 검증하고 꼼꼼히 테스트하고".
- 화면: 책 창(S14) 「검색하는 도서관 중 앞의 3곳」 위에 「AI 요약 ⓘ」 꼬리표와 한두 문장(회색보다 진한 글자). ⓘ = "AI가 책 정보를 바탕으로 쓴 짧은 요약이에요. 실제 내용과 다를 수 있어요."(모델 이름 없음).
- 파일: `backend/src/main/resources/picks/ai-summaries.tsv`(칸 `list, isbn13, title, summary`). 서버(`AiSummaries`)는 줄의 ISBN(`list_book.isbn13`)으로, 없으면 목록+목록 제목으로 찾음. 빌드에 실리므로 고친 뒤 배포해야 화면에 나옴. 사서추천도서는 달마다 새 책이 들어오므로 그때 아래 절차로 더함.
- 절차(2026-10-20)
  1. 운영 DB에서 목록 책을 읽기 전용으로 뽑음: `list_key, coalesce(pinned_isbn13, isbn13), isbn13, title, author, publisher`(picks.list_book).
  2. 책 소개 글: 카카오 책 검색 API(`dapi.kakao.com/v3/search/book`, `.env`의 `KAKAO_REST_API_KEY`, 0.5초 간격)로 ISBN, 없으면 제목+첫 저자 → `contents`(출판사 소개 앞부분). 소개 글은 내 PC 임시 폴더에만 두고 저장소에 넣지 않음. 2026-10-20: 251권 중 소개 글 217권(ISBN 203, 제목+저자 22, 못 찾음 26 — 대부분 서울대 100선 고전).
  3. Haiku 하위 에이전트(84권씩 셋)에게 소개 글을 주고 "해요체 한두 문장, 40~80자, 무엇에 관한 어떤 책인지 구체적으로, 소개 글을 자기 말로, 칭찬·결말 누설·AI 언급 없음, 소개 글이 없으면 그 작품을 아는 만큼(고전), 모르면 지어내지 않기"로 쓰게 함. 처음(2026-10-19)에는 소개 글 없이 제목만 주어 모르는 책을 비워 두었고, 비운 책을 다시 쓰게 했더니 "'○○'이라는 제목의 책이에요" 같은 빈 문장이 나와 이 방법으로 바꿈.
  4. 사람(이 세션)이 모든 줄을 소개 글과 나란히 놓고 읽어 고침: 판(edition) 이야기만 한 줄(「자본론」 청소년판, 「사기」 열전), 범위가 틀린 줄(「일리아스, 오디세이아」를 오디세이아만), 소개 글 없는 고전(엔트로피·객관성의 칼날·같기도 하고 아니 같기도 하고·변신인형·근현대사 4부작 등), 사실이 틀린 줄(부처스 크로싱 배경) 등 20줄.
- 결과(2026-10-20): 사서 108·이동진 43·서울대 100 = 251권 모두 요약 있음(29~79자). 노벨연구소 100권도 같은 날 같은 절차(카카오 소개 글 82권)로 써서 모두 넣음(목록+제목으로 찾음, 모든 줄 사람이 읽음). KAIST 사서추천도서(2024~2026)도 같은 날 같은 절차로 씀: 2,481줄 중 겹치는 책을 빼고 2,453권, Haiku 묶음 17개를 병렬로(160줄씩, 소개 글은 160자로 잘라 넣음; 70줄 묶음에 소개 글 전체를 넣으니 맥락을 넘음). 자동 검사(빈 줄·25자 미만·150자 초과·메타 문구·ISBN 순서)로 고친 곳: ISBN과 요약 사이 탭이 빠진 1줄, 다른 줄의 ISBN을 베낀 1줄(입력 순서로 맞춤). `kaist.tsv`에 제목이 HTML 조각으로 깨진 2줄(9791156162940, 9791141108601)이 있어 따옴표 처리로 읽으면 뒤 42줄이 합쳐짐 — 읽을 때 `quoting=csv.QUOTE_NONE`, 두 줄 제목은 카카오 제목으로 씀(목록 파일 수정은 수집기 쪽에 알림).

## 9. 노벨연구소 선정 최고의 책 (2026-10-20 사용자 요청)

- 요청: "노벨연구소 선정 최고의 책 이것도 추천도서에 추가해주라 목록에는 노벨연구소만 보이게 해줘" — 다섯째 목록, 목록 키 `nobel`, 화면 이름 「노벨연구소 선정 최고의 책」, 탭 이름은 「노벨연구소」만. 탭 순서 국립중앙도서관 → KAIST → 서울대 → 노벨연구소(맨 뒤).
- 목록: Bokklubben World Library(노르웨이어 Verdensbiblioteket). 2002년 노르웨이 북클럽(De norske Bokklubbene)이 노르웨이 노벨연구소와 함께 54개 나라 작가 100명에게 10권씩 물어 고른 100권(작가 100명). 순위·분류 없이 모두 같은 자리이고, 「돈키호테」만 "가장 뛰어난 문학 작품"으로 따로 꼽힘.
- 출처: 영어 위키백과 「Bokklubben World Library」 https://en.wikipedia.org/wiki/Bokklubben_World_Library 의 표 「List of books」(칸 Title, Author, Year, Country, Language). 2026-10-20(KST) 한 번 읽음: `https://en.wikipedia.org/w/index.php?title=Bokklubben_World_Library&action=raw`(위키 원문, 요청 1번, User-Agent에 chaekcheck). 표 순서(저자 성의 알파벳 순)를 목록 순서로 씀. 위키 원문은 내 PC 임시 폴더에만 두고 저장소에 넣지 않음.
- 파일 `scripts/crawl/lists/nobel.tsv`(100줄): 칸 `field, position, title, author, publisher, isbn13, cover_url, pinned_*, pinned_by, note`(KAIST에서 `year`를 뺀 것). `title`·`author`는 한국어판에서 흔히 쓰는 제목·이름(「돈키호테」/미겔 데 세르반테스, 「백년 동안의 고독」/가브리엘 가르시아 마르케스, 「일리아스」/호메로스 …), 지은이를 모르는 작품(「길가메시 서사시」·「욥기」·「천일야화」·「날의 사가」)은 「작자 미상」. 여러 작품을 묶은 항목은 목록 말 그대로 우리말로(「몰로이·말론 죽다·이름 붙일 수 없는 자」, 첼란 「시집」, 레오파르디 「시 전집」, 「체호프 단편선」·「카프카 단편선」·「에드거 앨런 포 단편선」). `publisher`·`isbn13`은 비워 두고 수집기가 제목+첫 저자로 판을 고름. `note`는 "Bokklubben World Library: 영어 제목 / 지은이 / 해 / 나라"(위키 표 그대로).
- 분야(`fields.tsv`의 `nobel` 줄, 시대로 셋): `ancient` 고대·중세(1500년 무렵까지, 19권) · `early_modern` 근대(1500~1899년, 35권) · `modern` 20세기(46권). `position`은 분야 안 목록 순서. 화면은 분야 순서, 그 안에서 목록 순서(「전체」도 같은 차례).
- 서버: 목록 키 `nobel`을 `ListFiles`·`CuratedLists`·`PicksCache`(분야 순서 → 분야 안 순서)·`UsageEventReader`에 더함. Liquibase `picks/005-nobel-list.yaml`(`picks-005-nobel-list-key`: `list_field_list_key_check`에 `nobel`; `picks-005-nobel-rows`: `list_book_nobel_check`(달·영상·올해의 책·목차·해·`source_no` 없음, 표지 https) + 분야 안 자리 하나 `list_book_nobel_position_key`), `usagelog/007-screen-event-picks-list-nobel.yaml`(`screen_event_picks_list_check`에 `nobel`). export·`lists` 모드가 읽는 파일에 `nobel.tsv`가 더해짐(없으면 멈춤).
- 수집기(`recommended_lists.py`): `nobel.tsv`도 KAIST처럼 고정 판·표지를 고름(파일이 없으면 건너뜀). 새 옵션 `--lists nobel`(쉼표로 여러 개)은 그 목록만 판·표지를 고르고 그 파일만 다시 씀(다른 목록 파일은 그대로, 표지 정리는 모든 목록의 표지를 남김). 목록 제목 그대로인 책이 없으면 권 표시·판 표시를 뗀 제목(`title_base`)이 같은 책도 찾음(「한밤의 아이들」 → 「한밤의 아이들. 1」). 원어판을 고르지 않도록 `collector_words.tsv` `excluded_title`에 「프랑스어판」·「독일어판」을 더함(「운명론자 자크와 그의 주인」이 고품격 프랑스어판으로 잡혔음).
- 수집 결과(2026-10-20, `--skip-youtube --lists nobel`, 사본 `ebook-catalog-2026-10-12-0135.sqlite`, 내 PC `db`): 고정 판 76/100, 표지 86줄(새 파일 82장). 판이 없는 24권: 우리 전자책 표에 없거나(「모든 것이 산산이 무너지다」·「픽션들」·「밤 끝으로의 여행」·「콜레라 시대의 사랑」·「하드리아누스 황제의 회상록」 등) 지은이가 다르게 적힘(「욥기」·「라마야나」·「마하바라타」), 묶음 항목(베케트 3부작·첼란·레오파르디), 원어판만 있음(「운명론자 자크와 그의 주인」). 이들은 제목 표지 또는 국립중앙도서관 표지로 보임. 판이 없어도 가져오기가 제목으로 우리 책에 이은 줄은 그 책과 같은 판의 표지를 씀(사용자 2026-10-20: "다른 출판사의 표지자나 하지 마 / 같은 출판사의 표지로 하고"): 「운명론자 자크와 그의 주인」은 이어진 유페이퍼 판의 교보 표지를 `cover_url`에 직접 넣음. 다른 판(민음사)으로 고정하는 것은 하지 않음. 이동진·서울대·KAIST 파일은 바뀌지 않음.
- 확인할 것 [안]: 「날의 사가」(Njáls saga)의 한국어 제목, 「천일야화」의 고정 판(유페이퍼 「천일야화 (The Thousand and One NightsI)」)과 「부덴브로크 가의 사람들」의 고정 판(유페이퍼, 독일어 원제 붙음)이 우리말 번역본인지.
- 화면(`web/src/features/picks/picksLists.ts`): 이름 「노벨연구소 선정 최고의 책」, 탭 「노벨연구소」, 추천 기준 "2002년 노르웨이 북클럽과 노르웨이 노벨연구소가 54개 나라 작가 100명에게 물어 고른 100권이에요" · "분류는 작품이 쓰인 시대에 따라 책첵이 나눴어요" · "책첵은 노벨연구소와 관계가 없어요", 출처 "Bokklubben World Library (노르웨이 북클럽·노르웨이 노벨연구소, 2002)". 책 창에 해 줄은 없음.
- 운영 반영: 이 판을 배포한 뒤 `lists` 모드([3-import.md](3-import.md) 14절)로 목록 폴더 전체를 넣음. AI 요약은 8절대로 넣음.
