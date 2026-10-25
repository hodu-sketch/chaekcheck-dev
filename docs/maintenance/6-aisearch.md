# AI 책 찾기: 찾기용 한 줄 넣기 (`book-texts`)

- 사이클 24(plan ADR-0151). AI 책 찾기는 추천도서 목록 책과 **보유 도서관이 많은 3만 권**(2026-10-25 사용자 요청, 아래 6절)의 제목·저자·보유 도서관 **사본**과 찾기용 한 줄을 `aisearch.search_book`에 두고 낱말을 맞춥니다. 이 표는 이 명령으로만 바뀝니다.
- 한 줄은 내 PC에서 Haiku 배치로 만듭니다(plan 인계서 2단계, 소개 원문은 PC 밖으로 나가지 않음). 서버에는 한 줄만 들어갑니다.

## 1. 언제 돌리나

- 2단계 PC 배치로 새 TSV를 만들었을 때
- 목록 책이 바뀐 뒤: 가져오기 바꿔 끼우기([3-import.md](3-import.md) 8·13절), 목록만 넣기(`lists` 모드, 3-import.md 14절), 사서추천도서 월 받기([4-picks.md](4-picks.md) 2절), 책 맞춤 규칙 변경. 돌리지 않으면 새 책은 다음에 돌릴 때까지 AI 찾기에 나오지 않습니다(결정 #3에서 감수, [4-picks.md](4-picks.md) 10절)
- 한 줄이 아직 없을 때(처음): 머리줄만 있는 파일이면 목록 책 전부가 제목·저자만으로 들어갑니다. **이미 한 줄이 든 표에 한 줄 0개 파일을 넣으면 거부합니다**(잘못된 파일로 한 줄을 모두 잃지 않게, 표 그대로)

## 2. 파일

- UTF-8 TSV, 머리줄 `kind	book_id	source	made_on	text` [안]. BOM과 Windows 줄 끝(`\r\n`)은 받습니다
- `kind`: 이번에는 `search_line`만 읽습니다. 다른 값(사이클 23의 글 등)은 건너뛰고 종류별 수를 로그에 남깁니다
- `book_id`: 우리 책 번호(`catalog.book.id`). **수집을 반영한 뒤의 DB 번호**로 뽑습니다(사용자 결정 2026-10-24, `plan-feedback.md` `# 사이클 24` "1단계")
- `source`: `intro`(소개 원문으로 만듦) | `bibliographic`(서지만으로 만듦). `made_on`: `YYYY-MM-DD`
- `text`: 1~300자. 탭·줄바꿈 없음
- **파일 전체를 거절하는 경우**(아무것도 넣지 않음, 줄 번호와 함께 처음 20개 문제를 보여 줌): 머리줄이 다름, 칸 수가 5가 아님(글 안의 탭·줄바꿈), 빈 줄, 300자 넘음, 빈 글·빈칸만 있는 글, 숫자가 아닌 `book_id`나 1보다 작은 번호, 모르는 `source`, 날짜가 아닌 `made_on`, 같은 책 두 줄. UTF-8이 아니면 읽기에서 멈춥니다

## 3. 돌리기

`backend/`에서(운영은 운영 DB 환경 변수로, 사이클 17 방식):

```
./gradlew bootRun --args='--spring.profiles.active=collection-import
  --chaekcheck.collection.import.mode=book-texts
  --chaekcheck.collection.import.book-texts-file=<TSV 경로>'
```

- 운영(EC2)에서는 서버의 백엔드 이미지로 돌립니다(비밀값을 PC로 가져오지 않음). TSV를 `/srv/chaekcheck/book-texts/`에 올리고(644) `/srv/chaekcheck`에서:
  `docker compose --env-file secrets.env --env-file prod.env run --rm --no-deps -v /srv/chaekcheck/book-texts:/book-texts:ro -e JAVA_TOOL_OPTIONS=-Xmx384m -e CHAEKCHECK_AUTH_ENABLED=false -e CHAEKCHECK_USAGELOG_ENABLED=false -e CHAEKCHECK_AISEARCH_ENABLED=false backend --spring.profiles.active=collection-import --chaekcheck.collection.import.mode=book-texts --chaekcheck.collection.import.book-texts-file=/book-texts/<파일>`.
  로그인을 끄지 않으면 뜨지 않습니다(`HttpSecurity` 없음, 2026-10-25)
- 차례: 파일 검사 → 목록 책을 DB에서 바로 읽음(`ListedBooks.loadFromDb()`, 서버 캐시 없이) → 보유 도서관 많은 책 `book-texts-top-books`권(기본 30000, `catalog.MostHeldBooks`)을 읽음 → 한 트랜잭션에서 `search_book`을 지우고 목록 책 전부와 목록에 없는 3만 권을 넣음(한 줄이 있으면 함께, 보유 도서관 `library_ids`도 복사). 목록에도 있는 책은 목록 쪽(보이는 이름) 한 줄로
- `--chaekcheck.collection.import.book-texts-top-books=0`이면 예전처럼 목록 책만
- **목록 책이 0권이면 거부하고 표를 그대로 둡니다.** 읽지 못한 목록이 있어도(`ListsNotReadException`) 거부합니다
- 목록에 없는 책 번호의 줄은 건너뛰고 수를 셉니다. 1줄이라도 있으면 WARN 한 줄(다른 DB에서 뽑은 파일인지 확인)
- 로그 한 줄 예: `book-texts: file rows 30939, search lines 30939, other kinds skipped {}, listed books 1911, most held 30000 (29028 not listed), stored 30939, with line …, lines for books not stored 0`
- **카탈로그를 다시 가져온 뒤에는 반드시 다시 돌립니다**: 3만 권의 차례와 보유 도서관이 바뀌고, 사본의 `library_ids`로 계획 도서관 수를 세기 때문입니다

## 4. 확인

```sql
SELECT count(*) AS books, count(line) AS with_line,
       count(*) FILTER (WHERE line_source = 'intro') AS intro,
       count(*) FILTER (WHERE line_source = 'bibliographic') AS bibliographic,
       max(imported_at) AS imported_at
FROM aisearch.search_book;
```

- `books`가 로그의 `listed books`와 같은지, `with_line`이 기대한 수인지 봅니다
- 제목·저자가 지금 목록과 같은지(후속 작업 24-F1 완료 확인과 같은 쿼리)는 [4-picks.md](4-picks.md) 10절

## 5. 되돌리기

- 앞 TSV로 다시 돌리면 그때 상태로 돌아갑니다(표 전체를 바꿔 끼우므로)
- 모듈 전체를 거둘 때: `DROP SCHEMA aisearch CASCADE`와 Liquibase 기록(`aisearch-001-*`, `aisearch-002-*`) 정리. 다른 표·모드에는 영향 없음

## 6. 보유 도서관 많은 3만 권 (2026-10-25)

- 사용자 요청 "ai검색 되는 도서를 보유량 많은 3만 권 기준으로도 같은 흐름으로 되게끔". 정한 것(사용자 선택, `plan-feedback.md` `# AI 검색 3만 권`): 목록 책과 **한 풀, 구분 없음**(점수 → 계획 도서관 수 → 번호), 3만 권 찾기용 한 줄은 **PC에서 하위 에이전트(Haiku)로**, 표지는 **사이클 23 표지를 합친 뒤**(그 전까지 3만 권 카드는 표지·AI 요약 없음), 보유 도서관은 **넣기 때 사본에**
- 고르는 기준: `catalog.book ORDER BY cardinality(library_ids) DESC, id LIMIT 30000` — 사이클 23 3만 권 풀(`scripts/crawl/probe_embeddings.py`)과 같은 식
- 서버: `aisearch.search_book.library_ids`(changeset `aisearch-003`)로 계획 도서관 수를 SQL에서 셈(`CandidateQuery`). 제목·저자는 서버 메모리 사본(`SearchBooks`, 표가 바뀌면 1분 안에 다시 읽음)
- **changeset을 올린 직후 `book-texts`를 다시 돌려야** 합니다. 그 전 줄은 `library_ids`가 빈 배열이라 아무 책도 맞지 않습니다(AI 검색 답이 늘 "못 찾았어요")
- 한 줄 파일 만들기(PC, 원문은 PC 밖으로 안 나감): 대상 `chaekcheck-dataisearch\pool-targets-2026-10-25.tsv`(번호·제목·저자·출판사), 소개 `chaekcheck-data\ml\intro-books.tsv`(사이클 23 시험이 수집 DB에서 뽑은 것). `scripts/aisearch/agent_search_lines.py split`으로 150권씩 묶고, Claude Code 세션에서 Haiku 하위 에이전트가 묶음마다 `RULES.md`대로 `out-NNN.tsv`를 쓰고, `join`으로 검사해 합침(규칙 파일 `chaekcheck-dataisearchgent-chunks\RULES.md`: 25줄씩 읽기, 소개가 비면 `-`). 처음 506권은 API 바로 호출(`make_search_lines.py`)로 만들었고 같은 지시문. 2026-10-25 결과 25,187권에 한 줄, 넣기 파일 `chaekcheck-dataisearchook-texts-30k-2026-10-25.tsv`(27,098줄). 합친 뒤 묶음마다 줄 수와 첫 칸이 책 번호인지 꼭 확인(한 묶음이 줄 번호를 쓴 적 있음)
- 운영 넣기 파일 = 목록 책 임시 한 줄(`data/aisearch/search-lines-temp-2026-10-25.tsv`) + 3만 권 한 줄. 내 PC DB와 운영 DB의 책 번호가 같음(가져오기 14번, 2026-10-25 확인: 목록 책 1,911권 전부, 3만 권 무작위 40권 제목 일치). 수집을 반영해 번호가 바뀌면 다시 만듦
