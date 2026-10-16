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
- 거르기: `collector_words.tsv`의 `excluded_*` 낱말(요약·어린이·기계 번역 판, spec R3-3·R3-4). 다 걸러지면 같은 본제목 판 전체(저자 무관)에서 다시 고르고, 그래도 없으면 고정 판을 비웁니다(export가 줄의 제목 + 저자로 다시 찾음)
- 고르기: Java `BookChoice`와 같은 공식(rules-index 4절 "판 고르기"). 같은 시험 자료 `scripts/crawl/testdata/book_choice_cases.json`을 Java·Python 시험이 함께 읽습니다
- `pinned_by = manual` 줄은 덮어쓰지 않습니다. 사람이 판을 정하려면 `pinned_title`·`pinned_author`·`pinned_publisher`(필요하면 `pinned_isbn13`)를 적고 `pinned_by`에 `manual`, `note`에 까닭과 날짜를 적습니다
- 표지는 모든 줄에 찾습니다. 우리 책을 못 맞춘 줄도 인터넷에서 찾습니다(사용자 2026-10-16: "책 표지 무조건 다 보였으면 해 인터넷 수집해서(어디서 수집해야 하는지는 기록 다 해)채워")
- 표지 차례는 `cover_sources.tsv`(rules-index 4절 "표지 출처 차례"): ① 고정 판 ISBN의 국립중앙도서관 ISBN 서지 표지 ② 수집 사본의 그 책 소장 줄 표지 주소 가운데 `cover_hosts.tsv` 차례(YES24, 교보) ③ 수집 사본의 그 밖의 주소 ④ 카카오 책 검색(`dapi.kakao.com/v3/search/book`, `.env`의 `KAKAO_REST_API_KEY`): ISBN, 없으면 제목 + 첫 저자의 성, 끝으로 같은 제목. Daum CDN 원본 그림을 받음 ⑤ ISBN 서지 제목 검색. 출처마다 5개까지 시도하고, 다 안 되면 표지 없이(화면은 제목 글자 표지). 원본 5MB가 넘으면 받지 않습니다
- 빈 표지 그림(국립중앙도서관 로고, 교보 「제공된 상품이미지가 없습니다」)은 `cover_placeholders.tsv`의 해시와 견주어 건너뜁니다
- 목록 제목이 한 책이 아니면 `cover_title` 칸의 제목으로 찾습니다(「근현대사 4부작」 → 「혁명의 시대」)
- 고정 판이 바뀌면 수집 사본 표지를 다시 받습니다(「토지」 2권 → 1권). 국립중앙도서관·카카오 표지는 그대로 둡니다
- 네이버 책 검색은 쓰지 않습니다: 지금 키가 401을 돌려줌(2026-10-16)
- 2026-10-16 결과: 이동진 43/43, 사서 108/108, 서울대 99/100. 남은 하나 「제자백가 선독」은 카카오(「제자백가선독」·「諸子百家選讀」도), ISBN 서지 제목, 국립중앙도서관 소장 검색(`NL/search/openApi/search.do`) 모두 그 이름의 책이 0건입니다(「제자백가」는 223건). 다른 「제자백가」 판 표지를 쓰면 틀린 책이 보이므로 제목 글자 표지로 둡니다. 판을 사람이 정하면 `cover_url`을 비우고 다시 돌립니다
- TODO(확인 필요): 배포 전 카카오 책 검색 표지를 화면에 써도 되는지(카카오 API 이용 약관, Daum 이미지 권리). 국립중앙도서관 표지 확인(4절)과 같이 합니다

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
