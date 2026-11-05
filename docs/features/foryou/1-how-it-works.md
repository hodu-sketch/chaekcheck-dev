# 1. 책첵 추천은 지금 어떻게 동작하나 (운영 v1.20.0, 2026-10-29 기준)

- **이 문서가 답하는 것**: 사용자가 「책첵 추천」 탭(`/picks/for-you`)을 열었을 때 브라우저·서버가 **어떤 자료를 어디서 읽어, 어떤 계산으로, 어떤 형식의 결과를** 만드는지. 코드 위치와 표·칸 이름까지.
- **답하지 않는 것**: 이웃 표·요약·표지를 PC에서 어떻게 만드나 → [2-data.md](2-data.md). 왜 이렇게 정했고 숫자가 얼마였나 → [3-evaluation.md](3-evaluation.md). 언제 무엇이 바뀌었나 → [4-history.md](4-history.md). 앞으로 바꿀 것 → [5-improvements.md](5-improvements.md).
- 사이클 26 배포 1(v1.20.0, 2026-10-29)로 요약·표지·꼬리표를 `catalog` 표에서 읽습니다. 문턱 3(4절 5번)은 v1.20.2(2026-10-29)로 운영에 들었습니다.

## 0. 한눈에

```
[브라우저 localStorage "chaekcheck.viewedBooks.v1"]  본 책 기록(책마다 점수 0~6)
   │ 점수 순 상위 10권 → 기준 책 ≤ 6권(분야당 ≤ 2) , 다시 보여 줄 책 ≤ 6권
   ▼
GET /api/v1/for-you/books?{계획}                         → 기본 목록 카드 배열
GET /api/v1/for-you/similar-books?baseBookIds=…&reshowBookIds=…&{계획}
   │ 서버: foryou.book_neighbour에서 기준 책마다 이웃 20권 → 순위대로 번갈아 모음
   │       → 같은 작품·기준 책 빼기 → 계획 도서관 0곳인 책 빼기 → 최대 60권
   ▼                                                      → 개인 추천 카드 배열 + 다시 보여 줄 카드
[브라우저]  개인 추천(+8·16번째에 다시 보여 줄 책) 뒤에 기본 목록 → 분야 돌려 놓기(본 책 < 3)
            / 안 본 분야 한 권 당기기(본 책 ≥ 3) → 칩으로 거르기 → 20권씩 화면에
```

## 1. 브라우저의 본 책 기록

- 저장 위치: `localStorage` 키 **`chaekcheck.viewedBooks.v1`**, 값은 JSON 배열(`web/src/features/picks/viewedBooks.ts`). 로그인 여부와 상관없이 이것만 씀(서버의 이용 기록은 읽지 않음, 10절)
- 한 줄 형식: `{bookId, title, opened, dwelled, libraryClicked, lastAt(ms), removedUntil(ms|null), fieldGroup?}`
- 언제 쓰나
  | 일 | 기록 | 코드 |
  |---|---|---|
  | 책 상세 화면이나 추천 탭의 책 창을 엶 | `opened = true` (책 창이면 그 책의 `fieldGroup`도) | `recordOpened`, `useBookVisit.ts` |
  | 그 화면이 보이는 채로 누적 10초(`DWELL_MS = 10_000`) | `dwelled = true` | `recordDwelled` |
  | 그 화면에서 도서관 줄을 누름 | `libraryClicked = true` | `recordLibraryClicked`(`web/src/lib/libraryClick.ts`) |
- 점수 = `opened`면 1 + `dwelled`면 2 + `libraryClicked`면 3 (0~6)
- 보관: `lastAt`이 30일(`KEEP_DAYS`) 지난 줄은 읽을 때 버리고, 200줄(`MAX_BOOKS`)을 넘으면 `lastAt`이 오래된 줄부터 버림
- 마이페이지 「책첵 추천 기준 책」(`BaseBooksDialog`)에서 점수 순 10권을 보이고, [빼기]는 `removedUntil = 지금 + 30일`, 「모두 지우기」는 키를 지움

### 1-1. 기준 책 고르기 (`baseBooks`)
1. 빼지 않은 책을 점수 내림차순(같으면 `lastAt` 최신 먼저)으로 정렬해 **상위 10권**(`recentBooks`, `DIALOG_COUNT = 10`)만 후보로
2. 후보를 차례로 보며 `fieldGroup`(없으면 `other`)마다 2권(`BASE_PER_FIELD`)까지 담아 6권(`BASE_COUNT`)이 될 때까지
3. 6권이 안 되면 후보에서 안 담긴 책을 점수 순으로 채움 → 다시 점수 순 정렬

### 1-2. 다시 보여 줄 책 (`reshowBooks`)
`lastAt`이 3일(`RESHOW_AFTER_DAYS`) 넘게 지났고, `libraryClicked`가 아니고, 기준 책이 아니고, 빼지 않은 책을 점수 순으로 최대 6권.

## 2. 서버로 보내는 요청 (`web/src/api/client.ts`)

| 요청 | 언제 | 칸 |
|---|---|---|
| `GET /api/v1/for-you/books` | 탭을 열 때 늘 | 계획 칸만 |
| `GET /api/v1/for-you/similar-books` | 기준 책이 1권 이상일 때 | `baseBookIds=1,2,…`(≤ 6), `reshowBookIds=…`(≤ 6, 있을 때), 계획 칸 |

- **계획 칸**(`planParams`): `scope`(`all`이 기본이라 생략, `joinable`일 때만 보냄), `visit`(`any` 생략, `none`일 때만), `libraryIds`(직접 더한 도서관), `residenceRegionIds`, `workRegionIds`
- **계획 도서관 집합**(서버 `LibraryCatalog.planSearch` → `LibraryService`): `library.library`에서 `enabled = true`이고 `join_survey`가 `SURVEYED`·`NOT_FOUND`인 곳(2026-10-29 로컬 377곳) + `libraryIds`로 직접 더한 곳(`LibraryRepository.findPlannable`). `scope=joinable`이면 그중 사용자의 거주·근무 지역 기준 자격이 `OPEN`·`ELIGIBLE`인 곳과 직접 더한 곳만, `visit=none`이면 방문 가입 필요한 곳을 뺌
- 브라우저 밖으로 나가는 것은 이 책 번호들과 계획 칸뿐(본 책 기록 자체는 안 나감)

## 3. 서버: 기본 목록 `GET /api/v1/for-you/books` (`foryou/internal/web/ForYouController.books`)

1. 계획 도서관 집합 `planIds`를 구함(2절)
2. 추천목록 책 `listed`: `ListedSource.listed()` → `picks`의 추천목록 캐시(`CacheListedBooks.merge`). 목록 차례 `librarian, leedongjin, snu100, kaist, nobel`로 각 목록의 책을 돌며 우리 책 번호(`picks.list_book.book_id`)가 있는 책만 한 줄로 합침. 책마다 `listKeys`(실린 목록들), `libraryIds`(`catalog.book.library_ids`), `recommendedMonth`(사서추천 달), `yearBook`(KAIST 연도), `fieldGroup`(처음 실린 목록의 분야 → 7절 표)
3. 차례
   - 새벽 만들기가 성공한 날이 있으면 `foryou.base_entry`의 `position` 순
   - **운영은 새벽 만들기가 꺼져 있어(`CHAEKCHECK_FORYOU_BUILD_ENABLED=false`) 이쪽**: `listed`를 `libraryIds` 개수(전체 도서관 기준 보유 수) 내림차순, 같으면 `book_id` 오름차순
4. 카드 만들기(`cards`, 6절): 계획 도서관 수 `planLibraryCount = |catalog.book.library_ids ∩ planIds|`가 0인 책은 뺌

응답: `{builtOn: 날짜|null, books: [카드…]}`

## 4. 서버: 개인 추천 `GET /api/v1/for-you/similar-books` (`ForYouController.similarBooks`)

1. 이웃 읽기: `SELECT book_id, neighbour_ids FROM foryou.book_neighbour WHERE book_id = ANY(:기준 책)` (`foryou/internal/store/BookTexts.neighbours`). `neighbour_ids`는 `integer[]`, 가까운 순 20개
2. **순서 만들기**(`SimilarOrder.byCloseness`, v1.15.1부터): 순위 0부터 19까지, 순위마다 기준 책을 요청 차례대로 돌며 그 순위의 이웃을 넣음(이미 있으면 건너뜀). 즉 [기준1의 1위, 기준2의 1위, …, 기준1의 2위, 기준2의 2위, …]
3. 같은 작품 정리: `BookCards.workKeys`(`catalog/internal/JdbcBookCards`) — `catalog.book.main_title_norm`이 같고 `catalog.book_contributor.name_exact`(역할 `AUTHOR`·`UNKNOWN`)를 하나라도 나누는 판끼리 한 작품(이름 사슬로 이어지면 모두 한 작품), 대표 판 = `cardinality(library_ids)`가 가장 큰 판(같으면 작은 번호). 후보를 대표 판 번호로 바꾸고, 기준 책과 같은 작품은 뺌. (새벽 점수가 있으면 점수 있는 판을 대표로 씀 — 운영에는 점수가 없음)
4. 카드 만들기(6절): 계획 도서관 0곳·모르는 번호는 조용히 뺌
5. **문턱**(`SimilarOrder.availableFirst`, 사용자 2026-10-29 「문턱 3으로」, v1.20.2부터 운영): 계획 도서관 목록(`LibraryCatalog.planSearch`의 `SearchTarget`) 중 `lendingModel`이 `SUBSCRIPTION`·`RENTAL`인 곳을 「바로 대출 도서관」으로 둠. 책마다 `catalog.book.library_ids ∩ 계획 도서관` 수를 세어 **3 이상(`MIN_PLAN_LIBRARIES`)이거나 그중 바로 대출 도서관이 있으면 통과**. 통과한 책을 차례 그대로 앞에, 못 넘은 책을 차례 그대로 뒤에(통과한 책이 60권에 못 미칠 때만 보임)
6. 앞에서 최대 60권(`MAX_SIMILAR`)
7. `reshowBookIds` 중 기준 책이 아닌 것도 카드로(`reshowBooks`)

응답: `{baseBooks: [{bookId, title}], books: [카드 ≤ 60], reshowBooks: [카드]}`

## 5. (참고) 서버가 하지 않는 것
- 대출 가능 여부를 보지 않음(목록 단계에서는 도서관 사이트에 요청하지 않음)
- 보유 도서관 수나 인기로 다시 줄 세우지 않음(v1.15.1부터)
- 사람별 기록을 읽지 않음

## 6. 카드 칸과 출처 (`ForYouController.Card`, `cards()`)

| 칸 | 값 | 출처 |
|---|---|---|
| `bookId`, `title`, `author`, `publisher` | | `catalog.book.id`, `title`, `author_display`, `publisher` (`BookCards.cards`) |
| `authorKey` | 첫 저자 이름 | `catalog.book_contributor`에서 역할 `AUTHOR`·`UNKNOWN`, `position` 가장 앞의 `name_exact` |
| `planLibraryCount` | 계획 도서관 중 가진 곳 수 | `catalog.book.library_ids ∩ planIds` |
| `coverUrl` | CloudFront 완성 주소 `<chaekcheck.covers.base-url>/covers/<file_key>.jpg` 또는 null | `catalog.book_cover.file_key`(sha1 40자), 공개 API `catalog.BookTexts.coverUrls` → `CoverUrls.of` (v1.20.0부터, 전에는 `/covers/…`를 운영 nginx가 보냄) |
| `blurb` | 요약 한 종류(해요체 한두 문장) 또는 null | `catalog.book_summary.summary`, `catalog.BookTexts.summaries` (v1.20.0부터. 전에는 `foryou.book_blurb`와 추천목록 요약 `aiSummary` 둘이었음) |
| `lists`, `tags`, `recommendedMonth`, `yearBook` | | 추천목록 책만(3절 2번). `tags`는 `catalog.book_tag`(지금 0행) |
| `fieldGroup` | 6묶음 중 하나 또는 null | 7절 |

## 7. 분야 묶음 `fieldGroup`

- 값: `literature`, `humanities`, `society`, `science`, `arts`, `other` (`picks/FieldGroups.java`)
- **추천목록 책**: 처음 실린 목록(3절 2번 차례)의 `picks.list_book.field_code`를 아래 표로 바꿈. 노벨 목록은 모두 `literature`. 표에 없는 코드는 null

  | 목록:분야 코드 | 묶음 |
  |---|---|
  | kaist:literature · leedongjin:korean_fiction, foreign_fiction, graphic_novel, essay · librarian:11 · snu100:korean_lit, foreign_lit | literature |
  | kaist:humanities · leedongjin:humanities, history · librarian:6 · snu100:eastern_thought, western_thought | humanities |
  | kaist:society · leedongjin:society, economy · librarian:5 | society |
  | kaist:science, technology · leedongjin:science · librarian:4 · snu100:science_tech | science |
  | kaist:arts | arts |
  | librarian:425 | other |

- **추천목록 밖 책**(`ForYouController.guessFieldGroups`): 그 책의 `foryou.book_neighbour.neighbour_ids` 20권 중 추천목록 책들의 묶음을 셈 → 가장 많은 묶음이 **2표 이상이고 단독 1위**면 그 묶음, 아니면 null. 10만 권 판 이웃 표에서 목록 밖 책의 약 6.7%만 묶음을 얻음([3-evaluation.md](3-evaluation.md) 5절)

## 8. 화면에서 목록 만들기 (`web/src/features/picks/ForYouPage.tsx`, `forYouOrder.ts`)

`seen` = 본 책 기록의 책 번호 전부, `seen.size` = 본 책 수.

1. **개인 추천 정리**(`layOut`): 서버의 개인 추천에서 `seen`에 있는 책을 뺌 → (본 책 < 3이면 `mixFields`) → `limitAuthorRuns` → 앞에서 60권
2. **다시 보여 줄 책 끼우기**: 개인 추천을 20권씩 자르고, 묶음마다 8번째·16번째 자리(`RESHOW_PLACES`)에 다시 보여 줄 책을 차례로 끼움(개인 추천에 이미 있는 책은 빼고)
3. **기본 목록 붙이기**: 서버의 기본 목록에서 위에 이미 나온 책을 뺌 → (본 책 < 3이면 `mixFields`) → 개인 추천 뒤에 붙임
4. **안 본 분야 한 권**(`withSurprises`, 본 책 ≥ 3이고 본 책 기록에 `fieldGroup`이 하나라도 있을 때만): 10번째 자리(`SURPRISE_FIRST`)부터 15자리마다(`SURPRISE_EVERY`), 그 자리 이후에서 **본 책 기록의 어떤 `fieldGroup`과도 다른 묶음**(null 제외)을 가진 첫 책을 그 자리로 옮김. 더할 것도 뺄 것도 없음, 나머지 차례 그대로
5. 칩으로 거르기(9절) → 처음 20권(`MORE_STEP`), 「더 보기」마다 20권 더

- **`mixFields`**(분야 돌려 놓기): 카드를 `fieldGroup`(null은 `other`)별 줄로 나눔(줄 차례 = 그 묶음의 첫 책이 나온 차례) → 0번째 자리부터 각 줄의 그 자리 책을 차례로 꺼냄. 결과: [묶음A 1번째, 묶음B 1번째, …, 묶음A 2번째, …]. 첫 책은 그대로 첫 자리
- **`limitAuthorRuns`**: 앞에서부터 놓으면서, 바로 앞 2권의 `authorKey`가 모두 같은 책이면 그 책을 뒤로 미루고 다음 후보를 놓음(`authorKey` 없는 책은 같은 저자로 안 셈). 한 저자만 남으면 그대로

## 9. 칩 (`forYouChips.ts`)

- 「전체」, 개인 추천이 1권이라도 목록에 있으면 「최근 본 책과 비슷한」, 그 뒤 분야 칩 6개 중 **지금 목록에 그 묶음 책이 1권 이상 있는 것만**
- 분야 칩 이름 ↔ 묶음: 소설·에세이 `literature`, 마음·자기계발 `mind`, 역사·철학 `humanities`, 세상·경제 `society`, 과학·기술 `science`, 예술·취미 `arts`. **서버는 `mind`를 주는 책이 없어 「마음·자기계발」 칩은 늘 숨겨짐**
- 분야 칩 차례: 본 책 기록에서 그 묶음이 나온 수 내림차순, 같으면 위 차례
- 칩을 누르면 같은 목록에서 그 묶음(또는 개인 추천)만 거름

## 10. 책 창과 기록

- 표지를 누르면 책 창(`PicksBookDialog`)이 열리고 `GET /api/v1/books/{bookId}?{계획}`을 부름 → 계획 도서관별 소장과 대출 상태(도서관 사이트에 실제 조회, `availability` 모듈). 「AI 요약」 칸은 카드의 `blurb`(v1.20.0부터 요약 한 종류, `forYouBook.ts` `pickBook`)
- 서버 이용 기록(`usagelog`, ADR-0134, 1년 보관, IP 30일): `GET /api/v1/books/{id}`는 `book_view` 한 줄. 탭 목록을 처음 받으면 `screen_event`(`kind = picks_list_view`, `picks_list = for_you`, `picks_category = all`), 카드를 누르면 `screen_event`(`kind = picks_book_view`, `picks_category = similar`(서버 개인 추천 또는 다시 보여 줄 책에 든 책) 또는 `base`, `book_id`), 도서관 줄을 누르면 `library_click`(`screen = picks` — 추천도서 탭 전체가 같은 값). 줄마다 `visit_key`(탭을 열 때 `sessionStorage`에 만든 32자 난수)와 로그인했으면 `account_id`
- 책첵 추천은 이 서버 기록을 읽지 않음

## 11. 지금 꺼져 있거나 곧 바뀌는 것

| 것 | 상태 |
|---|---|
| 새벽 만들기(`foryou.build_run`, `popular_row`, `book_score`, `base_entry`) | 운영에서 꺼짐 → 기본 목록은 3절 3번, 새벽 점수 없음. 켤 때의 동작은 [`docs/maintenance/7-foryou.md`](../../maintenance/7-foryou.md) 1절 |
| 꼬리표(`catalog.tag_dictionary`, `catalog.book_tag`) | 0행 |
| 사이클 26 배포 2 | 옛 표(`foryou.book_blurb`·`book_cover`, `picks.book_tag`·`tag_dictionary`)를 지움. plan `docs/cycles/26-book-descriptions/to-dev23-24.md` 3절 |
| 문턱 3 | v1.20.2 운영(4절 5번) → [5-improvements.md](5-improvements.md) I-01 |
