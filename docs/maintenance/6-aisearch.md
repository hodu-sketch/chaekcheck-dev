# AI 책 찾기: 찾기용 한 줄 넣기 (`book-texts`)

- 사이클 24(plan ADR-0151). AI 책 찾기는 추천도서 목록 책의 제목·저자 **사본**과 찾기용 한 줄을 `aisearch.search_book`에 두고 낱말을 맞춥니다. 이 표는 이 명령으로만 바뀝니다.
- 한 줄은 내 PC에서 Haiku 배치로 만듭니다(plan 인계서 2단계, 소개 원문은 PC 밖으로 나가지 않음). 서버에는 한 줄만 들어갑니다.

## 1. 언제 돌리나

- 2단계 PC 배치로 새 TSV를 만들었을 때
- 목록 책이 바뀐 뒤: 가져오기 바꿔 끼우기([3-import.md](3-import.md) 8·13절), 목록만 넣기(`lists` 모드, 3-import.md 14절), 사서추천도서 월 받기([4-picks.md](4-picks.md) 2절), 책 맞춤 규칙 변경. 돌리지 않으면 새 책은 다음에 돌릴 때까지 AI 찾기에 나오지 않습니다(결정 #3에서 감수, [4-picks.md](4-picks.md) 10절)
- 한 줄이 아직 없을 때(처음): 머리줄만 있는 파일이면 목록 책 전부가 제목·저자만으로 들어갑니다. **이미 한 줄이 든 표에 한 줄 0개 파일을 넣으면 거부합니다**(잘못된 파일로 한 줄을 모두 잃지 않게, 표 그대로)

## 2. 파일

- UTF-8 TSV, 머리줄 `kind	book_id	source	made_on	text` [안]. BOM과 Windows 줄 끝(`\r\n`)은 받습니다
- `kind`: 이 문서는 `search_line`입니다. 사이클 23의 `for_you_blurb`·`tags`·`cover_file`은 [7-foryou.md](7-foryou.md) 2-2절(그 kind만 있는 파일은 `search_book`을 건드리지 않음). 다른 값은 건너뛰고 종류별 수를 로그에 남깁니다
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
- 차례: 파일 검사 → 목록 책을 DB에서 바로 읽음(`ListedBooks.loadFromDb()`, 서버 캐시 없이) → 한 트랜잭션에서 `search_book`을 지우고 목록 책 전부를 넣음(한 줄이 있으면 함께)
- **목록 책이 0권이면 거부하고 표를 그대로 둡니다.** 읽지 못한 목록이 있어도(`ListsNotReadException`) 거부합니다
- 목록에 없는 책 번호의 줄은 건너뛰고 수를 셉니다. 1줄이라도 있으면 WARN 한 줄(다른 DB에서 뽑은 파일인지 확인)
- 로그 한 줄 예: `book-texts: file rows 2800, search lines 2650, other kinds skipped {}, listed books 2804, with line 2650, lines for books not listed 0`

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
