# AI 책 찾기: 질문 하나가 지나가는 길 (운영 v1.22.3)

## 0. 이 문서가 답하는 것 / 답하지 않는 것

- 답하는 것: 지금 운영(v1.22.3, `deploy/prod/prod.env`)에서 질문 하나가 브라우저를 떠나 마지막 SSE 이벤트가 올 때까지 **어느 데이터를 읽고, 어떤 계산을 하고, 무엇을 내보내는지**. 모든 내용은 이 저장소의 코드·설정과 모델 폴더(`chaekcheck-data\ml\aisearch-model-100k\`, 운영 `/srv/chaekcheck/aisearch-model`과 같은 파일)에서 확인한 것입니다. 확인하지 못한 것은 「(코드에서 확인 못 함)」으로 적었습니다.
- 답하지 않는 것:
  - 버전마다 무엇이 바뀌었나 → [2-versions.md](2-versions.md)
  - 1차 신경망·관계 분류기를 어떻게 학습했나 → [3-ml-models.md](3-ml-models.md)
  - 넣기·배포·켜고 끄기 같은 운영 명령 → [../maintenance/6-aisearch.md](../maintenance/6-aisearch.md)
- **사이클 26 배포 1부터(plan ADR-0161·0165)**: 책 설명이 `catalog` 한 곳으로 모였습니다. 찾기 표는 `catalog.book_search`(`catalog.AiSearchIndex`로 읽음, 제목·저자는 `catalog.book`), 카드 표지·요약은 `catalog.BookTexts`(표지는 CloudFront 주소, 요약은 책첵 요약 하나), 1차 호출 프로필은 `catalog.book_profile`(`catalog.BookProfiles`; 표가 비면 ML 1차 호출이 꺼지고 Haiku로 감, 모델 폴더의 `book-profiles.tsv`는 v1.22.1부터 읽지 않음). 아래 글은 이 기준입니다. 옛 `aisearch.search_book`·`foryou.book_blurb`·`foryou.book_cover`는 배포 2(v1.20.6)에서 지웠습니다.
- 코드 경로 줄임: `aisearch/…`는 `backend/src/main/java/com/chaekcheck/aisearch/…`입니다. 웹은 `web/src/…`입니다.

### 이 문서에서 쓰는 말

| 말 | 뜻(코드에서) |
|---|---|
| 1차 | 질문을 받아 「어떤 길로 갈지」와 「찾을 낱말」을 정하는 첫 단계. 운영은 **ML 1차**: 질문 벡터 위의 작은 신경망(`MlFirstCall`), 모델 호출 없음. 질문 벡터가 늦거나 실패한 검색만 **Haiku 1차**(`AiSearchFlow.keywords`, Claude Haiku 호출 1번) |
| 2차 | 후보 책 30권 이하를 Claude Haiku에 보내 「맞는 책 0~8권 + 머리말 + 책마다 글」을 스트림으로 받는 호출(`AiSearchFlow.picks`) |
| 길(route) | 1차가 고르는 갈래 5가지: `NOT_BOOK`(책 찾는 말 아님), `TITLE_LIKE`(제목·저자 자체), `REFERS_SHOWN`(앞에서 보여 준 책을 가리킴), `SITE`(책첵 사용법 질문), `BOOK`(책 찾는 질문) |
| 칸(shelf) | 책 분류 사전의 칸. 큰 분야(field) 22개, 작은 분야(subfield) 99개, 장르(genre) 14개, 형식(form) 7개, 소설 여부(fiction), 나라(country), 독자(audience), 수준(level). 질문이 원하는 칸과 빼 달라는 칸(ex_field, ex_subfield, ex_genre)을 1차가 맞힘(`Shelves`) |
| 계획 도서관 | 사용자가 고른 지역·도서관으로 계산한, 이 사용자가 가입해 빌릴 수 있는 도서관 id 묶음(`LibraryCatalog.planSearch`). 후보 책은 이 도서관 가운데 한 곳 이상이 가진 책만 |
| 후보 | 2차에 보낼 책. 최대 30권(`limits.candidates`) |
| 되묻기 | 책 대신 「어느 쪽을 찾으세요?」와 고를 답 2~4개를 보여 주는 답(`ask` 이벤트) |
| 이어 묻기(follow-up) | 같은 대화의 두 번째 이후 질문. 웹이 앞 턴(질문과 보여 준 책 id)을 `history`로 함께 보냄 |
| 합치기(mix) | 낱말 후보 목록과 벡터 후보 목록을 RRF 점수(Σ 1/(60 + 순위)) 합으로 한 줄로 다시 세우는 것(4-9절). 「섞기」라는 말은 쓰지 않습니다 |

## 1. 한눈에

세 가지로 나눠 봅니다. 1-1은 시간 순서와 동시에 도는 것, 1-2는 어디서 갈라져 끝나는지, 1-3은 LLM·임베딩 호출이 길마다 몇 번인지입니다. 각 단계의 자세한 내용은 4절입니다.

### 1-1. 시간 순서와 동시에 도는 것 (v1.22.3)

`┌ ├ └`로 묶은 줄은 **동시에** 돕니다. `[옆]`은 다른 가상 스레드(`beside`)에서 도는 일, 나머지는 요청 스레드에서 차례로 도는 일입니다.

```
브라우저 AiChatPage.send ── POST /api/v1/ai-searches/stream (질문 + 앞 턴 ≤9 + 지역·도서관 id)
   │
[검사] AiSearchController.stream   (스트림을 열기 전. 걸리면 JSON 오류로 끝)
   켜짐 → IP 한도(분 10·하루 100) → 본문 → 계획 도서관 계산 → 오늘 비용 < US$2 → 동시 실행 자리(20)
   │  통과하면 가상 스레드에서 AiSearchFlow.answer
   ▼
① 대화 말 보기  TalkRefs: 「아까 ~」 「그거 말고」 「모르겠어」 → 찾을 주제를 정함                       (4-0-1)
     └ 「아까」가 가리킬 앞 질문 고르기만 조각 벡터를 씀 (encode, 최대 1.5초 기다림)
   ▼
② 동시에 시작                                                                                        (4-1~4-4)
   ┌ [옆] 이른 낱말 검색   첫 질문만. book_search LIKE ≤50권
   └      질문 벡터 encode(이어 묻기면 글 3개) → ML 1차(길·칸·낱말·관계) → 가까운 책 600개(4-8에서 120 → ≤60)
   ▼
③ 길 나누기 (1-2 표)  SITE·NOT_BOOK은 여기서 끝, TITLE_LIKE는 제목 단추를 보내고 계속
   ▼
④ 찾는 조건  최신(fromYear) · 주제 나누기(A나 B) · 「다 같이」(되물은 선택지 전부)                    (4-0-2)
   ▼
⑤ 후보 모으기 ≤30권                                                                                  (4-6~4-9)
   먼저 넣기: 앞 카드와 그 이웃 / 질문이 이름 댄 책의 이웃 / 제목 책 / 「더 쉬운 거」면 앞 카드
   낱말 후보: ②의 이른 결과(조건이 없을 때) 또는 다시 찾기. 주제가 여럿이면 주제마다 따로 찾아 번갈아
   벡터 후보: 600개 → 앞 120개 → 계획 도서관 책 ≤60권. 주제가 여럿이면 주제마다
   합치기:   RRF + 칸 보너스, 같은 제목은 하나만
   ▼
⑥ 2차 준비와 호출                                                                                    (4-10~4-11)
   한 줄 소개·출판 연도 읽기 → 이어서 셋이 동시에
   ┌ [옆] 표지 주소 읽기
   ├ [옆] 책첵 요약 읽기        (첫 카드를 보낼 때만 기다림)
   └      Haiku 2차 스트림 (머리말 → 책 번호·맞음·글 → 상황별 정리)
   ▼
⑦ 스트림을 읽는 대로 SSE로 보냄                                                                     (4-12)
   head-delta … → card → card-delta … (책마다, 최대 8권) → 다 받으면 tail-delta(서버가 제목 넣은 상황별 정리) → done
   ▼
⑧ 끝  usagelog.ai_search_event 1행, aisearch.daily_usage 비용 더하기, 로그 한 줄                     (4-13)
```

### 1-2. 어디서 갈라지나 (끝나는 곳과 화면)

| 어디서 | 조건 | 무엇을 함 | 화면 | done |
|---|---|---|---|---|
| 검사 | 꺼짐·한도·비용·자리 | 스트림을 열지 않음 | 거절 문구 | (JSON 오류) |
| ③ 길 | `SITE` (책첵 사용법) | Haiku가 책첵 안내문으로 답 | 머리말 + 「소개」 링크 | `site` |
| ③ 길 | `NOT_BOOK` (인사·잡담, 「안녕」. 낱말 하나여도 같음) | Haiku 대화 호출이 답 + 선택지(≤4). 실패하면 고정 문구 + 예시 3개 | 질문 + 번호 상자 | `asked` |
| ③ 길 | `TITLE_LIKE` (제목처럼 보임) | 제목 단추를 보내고 ④로 계속 | 「이 제목 찾기」 단추 | — |
| ③ 길 | `REFERS_SHOWN` (앞 책을 가리킴) | ⑤에서 앞 카드와 이웃을 먼저 넣고 계속 | — | — |
| ⑤ 후보 | 0권 | Haiku가 질문 + 선택지 3개 | Haiku의 질문 + 번호 상자. 선택지가 없으면 「맞는 책을 찾지 못했어요…」 + 안내 문구 | `no_match` |
| ⑦ 2차 | 되묻기 | 책 대신 질문과 선택지 | 질문 + 번호 상자 | `asked` |
| ⑦ 2차 | 고른 책 0권 | Haiku가 선택지 3개 (머리말 아래에 질문 없이) | 머리말 + 번호 상자. 선택지가 없으면 안내 문구 | `no_match` |
| ⑦ 2차 | 실패·시간 초과 | 글 없는 카드 | 카드만 | `no_text` |
| ⑦ 2차 | 정상 | — | 머리말 + 카드 + 「상황별로 고르면」 | `ok` |

### 1-3. 길마다 바깥 호출 수

| 길 | Haiku 호출 | 질문 벡터 encode |
|---|---|---|
| 책 찾기 (보통) | 1 (2차) | 1 (이어 묻기면 글 3개를 한 번에) |
| ML 1차가 늦거나 실패 | 2 (Haiku 1차 + 2차) | 1 |
| `SITE`, `NOT_BOOK` | 1 | 1 |
| 후보 0권 | 1 (선택지) | 1 |
| 2차가 0권 | 2 (2차 + 선택지) | 1 |
| 「아까」, 「A나 B」, 「최신」 판단 | 위와 같음 | +1 (조각·예시 비교용, 최대 1.5초) |

근거: `AiSearchController.stream`, `AiSearchFlow.answer`(① ~ ⑥, `beside`가 옆 스레드), `mlStep`(encode → ml → nearest 차례), `picks`·`noMatchChoices`·`chat`·`site`, `web/src/features/aisearch/AiChatPage.tsx`의 `send`.

## 2. 요청

### 2-1. 주소와 본문

- `POST /api/v1/ai-searches/stream`. 질문은 본문에만 들어가고 주소에는 들어가지 않습니다.
- 로그인 기능이 켜져 있으면 CSRF 헤더 `X-XSRF-TOKEN`이 필요합니다. 웹은 403 `csrf_invalid`를 받으면 토큰을 새로 받아 한 번 더 보냅니다(`web/src/api/aiSearch.ts`의 `post`).
- 본문(JSON, 모르는 칸은 무시):

| 칸 | 형식 | 검사(어기면) |
|---|---|---|
| `question` | 문자열 | 필수(400 `validation_error`, field `question`). 앞뒤 빈칸을 자르고, 짝 없는 서로게이트 글자는 U+FFFD로 바꾼 뒤(`WholeText.of`) 글자 수(코드포인트) 2~200. 어기면 400 `invalid_query` |
| `turnNo` | 정수 | 1~20. 어기면 400 `validation_error`(`out_of_range`) |
| `history` | 배열, 없으면 빈 배열 | 최대 9개(`limits.history-turns`, 대화 10질문). 각 턴: `question` 1~200자, `bookIds` 최대 8개(null 없음), `askQuestion` 200자 이하, `askChoices` 최대 4개·각 200자 이하(`askQuestion`이 비면 `askChoices`는 버림), `head`(그 턴의 머리말) 400자 넘으면 거절하지 않고 400자에서 자름(`HEAD_MAX_CHARS`) |
| `residenceRegionIds`, `workRegionIds` | 정수 배열 | 1 이상(아니면 400 `validation_error`). 지금 있는 시군구 id여야 함: 시도 id·없는 id·없어진 지역이면 422 `invalid_region`(`NOT_SIGUNGU`·`NOT_FOUND`·`ABOLISHED`) |
| `libraryIds` | 정수 배열 | 1 이상. 사용자가 직접 고른 도서관 |
| `scope` | `all` \| `joinable` | 없으면 `all`. 웹은 늘 `joinable`을 보냄 |
| `visit` | `any` \| `none` | 없으면 `any`. 웹은 사용자의 찾기 설정 값(`loadPrefs().visit`) |

- 본문 전체는 32KB(32,768바이트, `MAX_BODY_CHARS`) 이하. 넘거나 비거나 JSON이 아니면 400 `validation_error`(field `body`). 오류 메시지 글은 아직 「16KB」라고 적혀 있습니다(`AiSearchController.read`).

근거: `AiSearchController.read`·`question`·`turnNo`·`history`·`ids`·`scope`·`visit`, `AiSearchRequest`, `AiSearchErrors`.

### 2-2. 검사 순서와 거절

스트림을 열기 **전에** 아래 차례로 검사합니다. 거절은 보통 JSON 오류(RFC 9457 Problem Details, `code` 칸)로 갑니다.

| 차례 | 검사 | 데이터 | 거절 |
|---|---|---|---|
| 1 | 켜짐 | `chaekcheck.aisearch.enabled`(운영 true) | 404 `not_found` |
| 2 | IP 한도 | 서버 메모리(`InMemoryAiSearchQuota`). IP는 nginx가 넣는 `X-Real-IP` 헤더. IPv4는 그대로, IPv6는 앞 4묶음(/64)으로 셈. 최근 1분(미끄러지는 창)에 10번, 서울 날짜 하루에 100번. 거절된 요청·잘못된 요청도 셈(본문 검사보다 먼저) | 429 `too_many_requests` + `Retry-After`(풀리는 때까지 초, 올림) |
| 3 | 본문 검사 | 2-1절 | 400 / 422 |
| 4 | 계획 도서관 | 2-3절 | 422 `invalid_region` |
| 5 | 오늘 비용 | `aisearch.daily_usage.cost_micro_usd`(오늘 서울 날짜 행) < 2,000,000 (US$2 × 10⁶) | 503 `ai_search_paused` |
| 6 | 동시 실행 자리 | 세마포어 20개(`max-concurrent`) | 503 `server_busy` + `Retry-After: 5` |

- 통과하면 `daily_usage.request_count`를 1 더하고, 가상 스레드에서 검색을 돌리며 SSE 스트림을 엽니다. 스트림 응답 헤더: `Cache-Control: no-cache`, `X-Accel-Buffering: no`(nginx가 모아 두지 않게). 스트림 시간 한도 = 5초 + 15초 + 10초 = 30초.
- 2번과 5번에서 거절한 수는 메모리에 모았다가 10초마다 `daily_usage.rate_limited_count`, `paused_count`에 더합니다.
- IP 한도는 서버 메모리라 재시작하면 처음부터 다시 셉니다. IP는 저장하지 않습니다.

근거: `AiSearchController.stream`, `InMemoryAiSearchQuota.tryAcquire`, `ClientIp.of`·`quotaKey`, `DailyBudget.allowsStart`·`countRequest`·`flushRefusals`, `AiSearchSlots`, `AiSearchErrors`.

### 2-3. 계획 도서관 계산

- 입력: `residenceRegionIds`(사는 곳 시군구 id), `workRegionIds`(일하는 곳 시군구 id), `PlanOptions(scope, visit, libraryIds)`.
- 읽는 표: `library.library`(`enabled = true`이고, 조사가 끝난 도서관이거나 사용자가 직접 고른 도서관), `library.eligibility_rule`(도서관별 가입 규칙: 사는 곳·일하는 곳 지역, 전국).
- 도서관마다 그룹을 정합니다.
  - 지역을 보냈을 때: 규칙이 고른 시군구와 맞으면 `MY_SIGUNGU`, 그 시도와 맞으면 `MY_SIDO`, 전국 규칙이면 `OPEN`, (지역 규칙이 안 맞고) 규칙이 아예 없거나 도서관에 추가 조건 글(`extra_condition_text`)이 있으면 `NEEDS_CHECK`, 그 밖은 뺌.
  - 지역이 없을 때: 전국 규칙이면 `OPEN`, 지역 규칙이 있으면 `QUALIFIED`, 없으면 `NEEDS_CHECK`.
  - 직접 고른 도서관은 규칙이 안 맞아도 넣고(`QUALIFIED`) 맨 앞에 둡니다.
- 마지막 거르기(`PlanOptions.apply`): `scope=joinable`이면 가입 자격이 `OPEN` 또는 `ELIGIBLE`인 도서관과 직접 고른 도서관만 남김. `visit=none`이면 방문 없이 가입되는 도서관과 직접 고른 도서관만 남김.
- 출력: `List<SearchTarget>` → 컨트롤러가 id만 뽑아 `Set<Long> planLibraryIds`로 만들어 검색에 넘김. 웹은 늘 `joinable`을 보내므로, **지역도 도서관도 고르지 않은 사용자는 전국 규칙 도서관(OPEN)만** 계획 도서관이 됩니다.
- 이 묶음은 SQL의 `:plan` 값으로만 쓰입니다(4-7, 4-8절). 도서관 이름은 모델에 보내지 않습니다.

근거: `LibraryService.planSearch`·`place`, `PlanOptions.apply`, `SearchTarget.joinable`, `AiSearchController.stream`.

## 3. 데이터가 있는 곳

### 3-1. 데이터베이스

| 표.칸 | 형식 | 무엇 / 어떻게 채워지나 | 이 요청에서 쓰는 곳 |
|---|---|---|---|
| `catalog.book_search.book_id` | bigint, PK | 우리 책 번호(`catalog.book.id`, 외래 키 없음) | 모든 후보의 id |
| (제목·저자) | — | 칸 없음. `catalog.book.title`·`author_display`를 `book_id`로 이어 읽음(`AiSearchIndex.books()`). 사이클 26 전에는 목록 책이면 목록에 보이는 이름이었음 | 2차 책 목록, 카드 제목·저자, 제목 비교 |
| `catalog.book_search.line` | text COLLATE "C", 1~300자, null 가능 | 「찾기용 한 줄」: PC에서 Haiku 배치가 만든 문장(넣기 파일의 `search_line` 행). 도서관 책 소개로 만들었으면 `line_source = intro`, 서지 정보만으로 만들었으면 `bibliographic`(`catalog.BookTextsTransfer.Source`) | 2차 책 목록의 「한 줄」 |
| `catalog.book_search.line_source` / `line_made_on` | text(`intro`\|`bibliographic`) / date | 한 줄을 만든 방법과 날짜. `line`과 함께 있거나 함께 없음 | 이 요청에서는 안 읽음 |
| `catalog.book_search.search_text` | text COLLATE "C", GIN 인덱스 `gin_bigm_ops`(pg_bigm) | `tidy(제목) + " " + tidy(저자) + " " + tidy(한 줄)`. `tidy` = NFKC 정규화 → 소문자 → 모든 빈칸과 보이지 않는 서식 문자 삭제 | 낱말 검색 `LIKE` |
| `catalog.book_search.library_ids` | integer[] | 넣을 때 `catalog.book.library_ids`를 복사한 「이 책을 가진 도서관 id」 | 계획 도서관과 겹치나(`&&`), 몇 곳 겹치나(`plan_count`) |
| `catalog.book_search.imported_at` | timestamptz | 넣은 때 | 서버가 표가 바뀌었나 볼 때 |
| `aisearch.daily_usage` | 서울 날짜 1행 | `request_count`, `llm_call_count`, `input_tokens`, `output_tokens`, `cost_micro_usd`, `rate_limited_count`, `paused_count` | 시작 때 비용 검사, 끝에 더하기 |
| `foryou.book_neighbour.neighbour_ids` | integer[], 1~20개 | 크롤 PC가 KURE-v1 책 벡터로 계산한 가까운 책 20권, 가까운 순 | 앞 책·이름 댄 책의 이웃 |
| `catalog.book_cover.file_key` | text, sha1 40자 | PC가 받아 S3에 올린 표지 이름 → `https://d1bdzw8sfjph5d.cloudfront.net/covers/<file_key>.jpg`(`catalog.CoverUrls`) | 목록 표지가 없는 카드의 표지 |
| `catalog.book_summary.summary` | text COLLATE "C", 1~300자 | 책첵 요약(해요체 한 종류, 추천목록·책첵 추천·책 상세와 같은 글) | 카드의 `aiSummary` |
| `catalog.book_profile` | 책마다 9칸 | 1차 호출 칸 비교용 프로필(`book-profiles` 넣기 모드) | 서버 시작 때 한 번(3-3절) |
| `catalog.book.published_on` | date | 그 책 보유본들의 가장 흔한 출판일 | 2차 책 목록의 「출간: YYYY년」 |
| `library.library`, `library.eligibility_rule` | | 도서관과 가입 규칙 | 계획 도서관(2-3절) |
| `usagelog.ai_search_event` | 1검색 1행 | 걸린 시간, 턴 번호, 1차 결과, 제목 같음 여부, 2차 결과, 후보 수, 보여 준 책 id, 토큰 수. 질문 글은 없음 | 끝에 1행 |

- `book_search`는 `collection` 가져오기의 `book-texts` 모드만 바꿉니다(`BookTextsImport.run` → `catalog.BookTextsTransfer.replaceSearchBooks`; 파일 첫 줄 `# export_id=`가 그 DB의 마지막 가져오기와 같아야 함). 찾기 글의 제목·저자와 `library_ids`는 넣을 때 `catalog.book`에서 읽습니다. 다시 가져오기의 바꿔 끼우기는 새 목록에 없는 번호의 줄을 지웁니다(plan ADR-0162). 한 트랜잭션에서 모든 행을 지우고, 「추천 목록 책 전부(`ListedBooks.loadFromDb`)」 + 「보유 도서관이 많은 순 상위 N권(`catalog.book`을 `cardinality(library_ids) DESC, id` 순, `MostHeldBooks.top`)에서 목록 책과 넣기 파일의 `search_exclude` 책을 뺀 것」을 500행씩 넣습니다. N은 파일 기본 30000(`application-collection-import.yml`), 운영 명령은 `book-texts-top-books=100000`([6-aisearch.md](../maintenance/6-aisearch.md) 3절). 넣은 뒤 `VACUUM (FULL, ANALYZE)`로 표를 다시 씁니다.
- 행 수: 운영 76,759권. 이 수는 코드가 정하지 않고 [6-aisearch.md](../maintenance/6-aisearch.md) 0절·4절(운영 넣기 로그 `stored`, 2026-10-27)에 적힌 값입니다.

근거: `db/changelog/catalog/002-book-descriptions.yaml`(사이클 26, 옛 `aisearch/001`·`003`·`004`의 규칙 그대로), `catalog.SearchText.tidy`·`ofBook`, `BookTextsImport.searchBooks`, `catalog/internal/texts/JdbcBookTextsTransfer.replaceSearchBooks`, `JdbcAiSearchIndex.compact`, `JdbcMostHeldBooks.top`, `foryou/internal/store/BookTexts`, `JdbcBookCards.cards`, `db/changelog/foryou/` 첫 파일, `db/changelog/usagelog/008-ai-search-event.yaml`.

### 3-2. 다른 모듈에서 공개 API로 읽는 것

| 공개 API | 뒤에 있는 데이터 | 돌려주는 것 |
|---|---|---|
| `picks.ListedBooks.current()` | picks 모듈의 서버 메모리 캐시(`PicksCache`, 원본 `picks.list_book` 등). 저자는 `catalog.book`에서, AI 요약은 `catalog.book_summary`(책첵 요약)에서 | 목록 책마다 `bookId`, `coverPath`(CloudFront 주소: 그 판 → 같은 작품 다른 판 → 목록 그림, plan ADR-0164), `aiSummary` |
| `catalog.BookCards.cards(ids)` | `catalog.book`, `catalog.book_contributor` | 이 요청은 `publishedYear`(= `extract(year FROM published_on)`)만 씀 |
| `foryou.BookNeighbours.of(ids)` | `foryou.book_neighbour` | `Map<책 id, 이웃 id 목록(가까운 순, ≤20)>` |
| `catalog.AiSearchIndex` | `catalog.book_search`(+ `catalog.book` 제목·저자) | 낱말 후보(`find`), 계획 도서관 수(`planCounts`), 한 줄(`lines`), 책 목록(`books`), 바뀜 확인(`version`) |
| `catalog.BookTexts.coverUrls(ids)` | `catalog.book_cover` | `Map<책 id, CloudFront 주소>` |
| `catalog.BookTexts.summaries(ids)` | `catalog.book_summary` | `Map<책 id, 요약 글>` |
| `catalog.BookProfiles.all()` | `catalog.book_profile` | 서버 시작 때 프로필 전부 |
| `library.LibraryCatalog.planSearch` | `library.library`, `library.eligibility_rule` | 계획 도서관 목록 |

근거: `ListedBooks`, `CacheListedBooks`, `PicksCache.coverPath`, `BookCards`, `JdbcBookCards`, `BookNeighbours`, `catalog.AiSearchIndex`·`BookTexts`·`BookProfiles`.

### 3-3. 서버 메모리에 올려 두는 것

| 무엇 | 언제 읽나 | 크기 |
|---|---|---|
| `SearchBooks`: `AiSearchIndex.books()`(`book_search`의 `book_id`와 `catalog.book`의 제목·저자·출간 연도) 전부 + 제목·저자의 「글자만」 열쇠(`SearchText.letters`: tidy 뒤 글자·숫자 말고 모두 삭제). 읽을 때 제목 목록을 `TitleWords`에도 넘김(4-0-2) | 요청 때 1분에 한 번까지 `SELECT count(*), max(imported_at)`로 바뀌었나 보고, 바뀌었으면 다시 읽음 | 76,759행 |
| `BookVectorFile`: `book-vectors.bin` 통째로 | 서버 시작 때 | 81,078 × 1024바이트 + id·scale |
| `FirstCallModel` 2개, `BookProfiles` | 서버 시작 때 | 아래 3-4절 |
| `IntentVectors`: `resources/aisearch/intent-examples.tsv` 80줄(recency 40, all_choices 40)의 벡터 | 처음 쓸 때 옆에서 만듦(실패하면 1분 뒤 다시) | 80 × 1024 |

근거: `SearchBooks.fresh`·`read`, `KureQuestionVectors` 생성자, `MlFirstCall` 생성자, `IntentVectors.ready`·`encodeAll`.

### 3-4. 모델 폴더 파일

운영 경로: 서버 `/srv/chaekcheck/aisearch-model` → 컨테이너 `/models/aisearch`(읽기 전용, `deploy/prod/compose.yaml`의 `CHAEKCHECK_AISEARCH_MODEL_DIR`). 아래 숫자는 PC의 같은 파일 헤더를 Python으로 읽어 확인했습니다(2026-10-29).

**`book-vectors.bin`** (83,996,822바이트)
- 리틀엔디언. `"CCBV1\n"`(6바이트) → `count` int32 = **81,078** → `dim` int32 = **1024** → 책 id int64 × 81,078 → 줄마다 scale float32 × 81,078 → 벡터 int8 × 81,078 × 1024.
- 「int8 줄 × 그 줄의 scale」이 그 책의 길이 1짜리 KURE-v1 벡터입니다. 만들 때: 벡터를 길이 1로 나누고 → `scale = max(|v|) / 127` → `round(v / scale)`을 −127~127로 자름(`scripts/aisearch/make_book_vectors.py`).
- 책 쪽 글(제목 · 저자 · 출판사 · 연도 + 소개 앞 300자)은 PC가 계산했고 이 저장소 코드가 아닙니다([6-aisearch.md](../maintenance/6-aisearch.md) 9절 기록).

**프로필** (77,551권): 사이클 26부터 `catalog.book_profile`에서 서버 시작 때 한 번 읽음(시작 로그 `ai search first call: ml, N book profiles from catalog.book_profile`). 표가 비어 있으면 경고 한 줄(`… the ml one is off: catalog.book_profile is empty …`)을 남기고 ML 1차 호출이 꺼져 Haiku로 감. 서버는 그대로 뜸. 모델 폴더의 파일 `book-profiles.tsv`를 대신 읽는 길은 v1.22.1에서 지움(plan 사이클 26 회고, 사용자 2026-10-29 「지우기해줘」)
- 표에 넣는 PC 파일(`book-profiles` 넣기, [7-foryou.md](../maintenance/7-foryou.md) 2절): UTF-8, 첫 줄 `# export_id=…`, 그다음 한 줄에 책 하나, 탭으로 나눈 9칸: `책 id`, `field`, `subfield`, `fiction`(1·0·빈칸), `genre`(`|`로 이음), `country`, `audience`, `level`, `form`(`|`로 이음). 예: `7	자기계발	공부법	0			교양	교양	`.

**`first-call-model.bin`** (25,824,240바이트)
- 리틀엔디언. `"CCFC1\n"` → 머리 JSON 길이 int32(5,918) → UTF-8 머리 JSON(`dim` 1024, `hidden` 512, `heads` 목록) → 머리(head)마다 float32로 `W1[512×1024]`, `b1[512]`, `W2[출력×512]`, `b2[출력]`.
- 머리 12개(한 머리 = 은닉층 하나짜리 작은 신경망):

| 머리 | 종류 | 출력 수 | 고르는 규칙 |
|---|---|---|---|
| `route` | route(softmax) | 5: `not_book, title_like, refers_shown, site, book` | tau 0.55(파일 값 0.5499999999999999) |
| `field` | multi(시그모이드) | 22 | 0.1 이상, 최대 2개 |
| `subfield` | multi | 99 | 0.3 이상, 최대 2개 |
| `genre` | multi | 14 | 0.25 이상, 최대 2개 |
| `form` | multi | 7 | 0.65 이상, 최대 2개 |
| `ex_field` | multi | 22 | 0.5 이상, 최대 2개 |
| `ex_subfield` | multi | 99 | 0.05 이상, 최대 2개 |
| `ex_genre` | multi | 14 | 0.05 이상, 최대 2개 |
| `fiction` | single(softmax) | 3: `True, False, None` | 가장 큰 것 |
| `country` | single | 9: `"", 한국, 일본, 중국, 영미, 프랑스, 독일, 러시아, 기타` | 가장 큰 것(`""` = 말 안 함) |
| `audience` | single | 8: `"", 교양, 실용, 청소년, 중고 학습, 초등 학습, 아동, 전문` | 가장 큰 것 |
| `level` | single | 4: `"", 입문, 교양, 전문` | 가장 큰 것 |

- field 22개: 소설, 시·희곡, 에세이, 인문·철학, 역사, 사회·정치, 경제·경영, 자기계발, 심리, 과학, 기술·IT, 건강·의학, 요리·생활, 육아·교육, 예술·취미, 여행, 종교, 어린이, 청소년, 만화, 외국어, 기타.

**`follow-up-model.bin`** (8,405,204바이트)
- 같은 형식. 머리 JSON 174바이트: `dim` **4096**, `hidden` 512, 머리 1개 `relation`(single, 7개: `refers, ask_one, continue, shift, feedback, new, none`).

**`kure-query-int8-ext.onnx` + `.data`, `tokenizer-cut.json`**
- 질문을 벡터로 바꾸는 KURE-v1 모델(24층)을 int8로 줄인 ONNX. 무게는 `.data`(402MB)에 따로 있고 mmap으로 읽음. 토크나이저는 88,446조각으로 줄인 Hugging Face 토크나이저.
- 그래프 입력: `input_ids`, `attention_mask`(둘 다 `[b, s]`). 출력: `embedding` `[b, 1024]`.
- 그래프 끝(onnx로 열어 확인): 마지막 층 LayerNorm 출력 → `Gather(axis=1, index=0)` = **첫 토큰(CLS) 위치 벡터** → `ReduceL2` → `Clip(최소 1e-12)` → `Div` = **길이 1로 나눔**. 그래서 서버는 따로 평균내거나 정규화하지 않습니다.
- 운영은 이 두 파일을 **임베딩 서버**가 씁니다. 백엔드는 `encoder=remote`라 이 파일을 열지 않습니다(`encoder=local`이면 백엔드가 직접 엶).

근거: `BookVectorFile.read`, `BookProfiles.read`, `FirstCallModel.read`·`outputs`·`route`·`labels`·`label`, `MlFirstCall` 생성자(머리 이름·관계 7개·`dim == 4 × 1024` 검사), `OnnxQuestionEncoder`, `scripts/aisearch/make_book_vectors.py`, `scripts/aisearch/eval/export_first_call.py`(multi 머리 `max = 2`).

### 3-5. 임베딩 서버 (`embed-service/`)

- 운영: 같은 VPC 안의 micro EC2, `http://172.31.45.141:8090`(prod.env `CHAEKCHECK_AISEARCH_EMBED_URL`).
- 요청: `POST <url>/api/v1/embed`, 헤더 `Content-Type: application/json`, `X-Embed-Token: <비밀값>`, 본문 `{"text": "<글>"}`. 백엔드 쪽 연결·전체 시간 한도 1.5초(`remote.timeout`).
- 응답: 200 `{"vector": [1024개 수]}`. 토큰이 틀리면 401, 글이 없거나 1,000자 넘으면 400, 모델을 못 올렸으면 503.
- 서버 안: `QueryEncoder.encode` = 토크나이저(특수 토큰 넣음, 256조각에서 자름, 채우기 없음) → ONNX 실행(스레드 2, 동시 실행 2) → `float[1024]`. 백엔드의 `OnnxQuestionEncoder`와 같은 코드입니다.

근거: `RemoteQuestionEncoder.encode`, `embed-service/.../EmbedController.embed`, `QueryEncoder`, `OnnxEmbedder`, `embed-service/src/main/resources/application.yml`.

## 4. 단계별

각 절 제목 뒤 괄호는 로그의 단계 이름입니다(7절). 시각은 요청이 들어온 때부터 잰 ms입니다.

### 4-0. 시작: 앞 턴 정리

검색 스레드가 처음 하는 일입니다.

- **앞 질문(earlierQuestion)**: 첫 질문이면 없음. 이어 묻기면 `EarlierTopics.of(이번 질문, 앞 질문들)`:
  - 앞 질문이 1개면 그것.
  - 이번 질문에 `모두|전부|것들|거 다|걸 다|다 합|다 섞`이 있으면 앞 질문 전부를 빈칸 하나로 이음.
  - 아니면 `세 개·세 가지·세 주제·세 분야·세 장르·셋`이 있으면 마지막 3개.
  - 아니면 `두 개/가지/주제/분야/장르`(뒤에 「중」이 오면 제외), `둘 다/을/를/이/섞/합/같이/모두`, `그 둘`, `섞`, `합쳐`, `합친`, `합해`, `합한`, `겹치`가 있으면 마지막 2개.
  - 그 밖은 마지막 1개.
- **answersAsk**: 바로 앞 턴이 되묻기였으면(`askQuestion`이 있으면) 참. 이번 질문은 그 되묻기의 답으로 읽습니다.
- **pointsBack**: 앞 질문이 있고, (answersAsk이거나 앞 질문이 마지막 질문 하나가 아니라 여러 개를 이은 것)이면 참.
- **앞에서 보여 준 책**: `history`의 턴마다 `bookIds` 가운데 `book_search`에 있는 것만, 중복 없이, 최대 8개. 2차 지시문에는 책마다 `<제목> / 저자: <저자>`(저자를 모르면 제목만, `shownLine`; 제목·저자는 `AiSearchIndex.books()`)로, 그 턴의 머리말(`head`)과 함께 넣음(4-11). `shownBefore` = 모든 턴의 책, `lastShown` = 마지막 턴의 책(되묻기 턴은 책이 0권).
- 목록 책 지도: `ListedBooks.current()`를 `Map<책 id, ListedBook>`으로(카드 표지·요약용).

근거: `AiSearchFlow.answer` 앞부분, `EarlierTopics.of`.

#### 4-0-1. 대화를 가리키는 말 (v1.20.5부터)

질문이 책 주제가 아니라 대화를 가리키면(「모르겠어」·「그거 말고」·「아까 이야기한 거」) 위 「앞 질문」 대신 `AiSearchFlow.talk(input)`이 정한 주제로 아래 단계를 돕니다. 대화 말이 아니면 `talk`는 `null`이고 예전과 같습니다.

- **알아보기**: `TalkRefs.of(질문)`. 소문자로 바꾼 질문에 정규식 셋을 이 차례로 대어 처음 맞는 것이 종류입니다.
  - `BACK_TO`: 「아까·처음·앞에서·앞서·전에·먼저」 뒤에 「말한·이야기·얘기·물어본·그거·다시·거잖·건데」 등.
  - `NOT_THAT`: 「그거·이거·저거·그런 거·그게·둘 다」 바로 뒤 「말고·아니·아냐」, 「말고 다른」, 맨 앞 「아니·아뇨」, 「다른 거」, 「딴 거」.
  - `DONT_KNOW`: 「모르겠·몰라·아무거나·다 좋·글쎄·딱히·상관없·알아서·그냥 추천·뭐든」.
- **남은 낱말**: 빈칸·문장부호로 자른 낱말 가운데 대화 낱말 목록(`TalkRefs.TALK`, 약 90개)만으로 통째로 쪼개지는 것(동적 계획법, 「그거말고」·「이야기한거잖아」)을 버리고, 끝 조사를 뗀 두 글자 이상만 남깁니다. `NOT_THAT`·`DONT_KNOW`인데 남은 낱말이 있으면(「그거 말고 에세이」) 대화 말로 보지 않습니다.
- **대화의 주제 목록**: `TalkRefs.topics(앞 질문들, 턴마다 되물었나)`. 대화 말인 턴은 건너뛰고, 되묻기에 답한 턴은 앞 주제 뒤에 이어 붙이고, `BACK_TO` 턴은 그 주제를 맨 뒤(지금 주제)로 옮깁니다.
- **정하는 것**(`Talk(종류, 주제, 되묻기, 앞 책 빼기, 메모)`):

| 종류 | 앞 턴 | 찾는 주제 | 되묻기 | 앞에서 보인 책 |
|---|---|---|---|---|
| DONT_KNOW | 되묻기 | 그 되묻기의 주제 | 하지 않음(NEVER) | 다시 나와도 됨 |
| DONT_KNOW | 카드 | 지금 주제(여러 갈래를 섞으라는 메모) | NEVER | 다시 나와도 됨 |
| NOT_THAT | 되묻기(그 앞도 되묻기) | 지금 주제 | NEVER | 다시 나와도 됨 |
| NOT_THAT | 되묻기 | 지금 주제 | 꼭 함(FORCE) | 다시 나와도 됨 |
| NOT_THAT | 카드 | 지금 주제 | 보통(NORMAL) | 뺌(같은 `titleKey`의 다른 판도) |
| BACK_TO | — | `TalkRefs.back()`이 고른 앞 주제(남은 낱말을 품은 것 → 「처음」이면 첫 주제 → 주제가 둘이면 앞 것) | NEVER | 다시 나와도 됨 |
| 모름(「저거」, 주제를 못 고름, 카드 없는 NOT_THAT) | — | 지금 주제 | FORCE | 다시 나와도 됨 |

- 앞 턴에 카드도 되묻기도 없는데 DONT_KNOW이면 대화 말로 보지 않고(`talk`가 `null`) 보통 질문으로 찾습니다.

- **아래 단계에 미치는 것**:
  - ML 1차(4-3)와 질문 벡터(4-2, 4-4)는 질문 글 대신 이 주제로 돌립니다.
  - 사이트 안내 길과 「책 찾기 아님」은 건너뛰고, 앞 책 가리킴(4-6)은 끕니다.
  - 2차 호출(4-11)의 사용자 메시지에는 「질문:」 줄 바로 아래에 `대화 메모: …` 한 줄이 붙습니다(무엇을 가리켰는지, 다시 묻지 말 것 / 다른 갈래로 물을 것 / 돌아왔다고 자연스럽게 말할 것). NEVER면 되묻기 칸이 없는 `PICK_SYSTEM`·`PICK_SCHEMA`로 부르고, FORCE면 되묻기 형식에 「후보 분야」 줄을 넣습니다.
- `QuestionWords`의 빼는 낱말에 「그거·이거·저거·그런·그게·아니·아니아니·다른·아까·다시·그거말고·이거말고·얘기·이야기」가 있어, 대화 말이 아닌 질문에서도 낱말 검색에 들어가지 않습니다.
- 로그 한 줄에 `talk <종류> <되묻기>`가 붙습니다(7절).

근거: `TalkRefs`, `AiSearchFlow.talk`, `Prompts.pickMessages`. 잰 결과: [reports/2026-10-29-talk-refs](reports/2026-10-29-talk-refs.md).

- **v1.22.1부터 `BACK_TO`의 주제 고르기**: 「아까」 없이 「뇌과학 말한거」, 「했던거」도 `BACK_TO`입니다. 고르는 차례는 4-0-2의 「아까」 줄입니다(위 표의 `TalkRefs.back()`은 벡터가 없을 때만 씀).

#### 4-0-2. 이어지는 질문의 조건과 주제 (v1.22.1부터)

앞 대화가 있을 때 아래를 정한 뒤 4-1절로 갑니다. 「벡터」는 질문(또는 그 조각)을 4-2절과 같은 인코더로 만든 벡터이고, 예시 문장(`resources/aisearch/intent-examples.tsv`, 80줄)의 벡터와 견줘 가장 가까운 5개가 가까운 만큼 표를 던집니다(`IntentVectors`). 예시는 처음 쓸 때 한 번 만들어 메모리에 두고, 그 전이나 벡터가 꺼졌으면 규칙만 씁니다. 조각 벡터는 최대 1.5초 기다립니다.

| 무엇 | 언제 | 규칙 | 벡터 | 결과 |
|---|---|---|---|---|
| 「다 같이」 | 바로 앞 턴이 고를 답이 있는 되묻기였을 때: 2차 되묻기, 대화 호출의 되묻기, 책 없음 뒤 선택지(고정 되묻기 `NOT_BOOK_ASK`만 제외). 책 없음 뒤 선택지는 질문 글이 비어 오지만, 웹이 `askQuestion`에 그 턴의 머리말을 넣어 보내므로(`ask.question \|\| head`) 서버가 선택지를 버리지 않음 | 「다 같이·전부·모두·다 줘/보여/찾아/골라·둘 다·셋 다·다」, 거절 말(별로·싫·말고·아니·빼고·없)이 있으면 아님 | 예시 `all_choices`: all / one / reject / other | 전부면 그 되묻기의 선택지 전부가 검색 주제(아래 「A나 B」처럼 주제마다 찾음) |
| 조건 말 | 2낱말 이하 이어 묻기, 대화 말 아님, 바로 앞 턴이 되묻기가 아님 | `CONDITION`: 최신·신간·신작·요즘·요새·최근·새로 나온·짧·얇·쉬운·쉽게·어려운·가벼운·두꺼운·만화·그림·번역·국내·외국·해외·○○○○년 | — | 4-3절 ML 1차를 pointsBack으로 돌려 앞 주제와 이어 찾음(관계가 new여도 `joined`). 질문이 최신 말뿐(「요새꺼」)이면 가까운 책은 앞 질문의 벡터로 찾음 |
| 최신 | 질문 글, 그리고 관계가 new가 아니면 앞 질문들(새것부터) | 「2020년 이후·부터·넘어·뒤·이상·후」면 그 해(올해보다 크면 무시). 아니면 최신 말(최신·신간·신작·요즘·요새·최근·새로/갓 나온·올해)이 있는지 | 예시 `recency`가 recent일 때만 최신(「요즘 지쳤는데」는 아님) | 첫 해 = 올해 − 3(2026년이면 2023). 4-7·4-8절에서 최근 책이 먼저, 최신 말 낱말(「최신책」)은 검색어에서 뺌, 2차 메모에 「사용자는 최근에 나온 책을 원한다. 2023년 이후…」 |
| 「아까」 (`BACK_TO`) | 4-0-1절 | 순서 말(처음·첫번째·두번째·세번째·끝·마지막·앞에 둘·전부)이 고르는 주제 + 질문 낱말(대화 말 뺌)이 든 주제 | 대화 말·최신 말을 뺀 질문 조각마다 가장 가까운 앞 질문 하나(0.3 이상, v1.22.2부터 합쳐진 주제가 아니라 질문 하나하나와 견줌) | 고른 주제·질문을 이어 붙인 글이 찾는 주제. 앞 질문에 그 조각의 낱말이 없으면 조각도 붙임(「세게사 책」 + 「세계사」) |
| 「A나 B」 | 찾는 질문 글(위 주제 포함) | 나·이나·랑·이랑·하고·쉼표·또는·아니면·혹은으로 이은 주제, 또는 목록 말(둘다·셋다·섞어·아무거나·중에·아님·든, 끝의 「다」) 앞의 주제 낱말들. 2개 이상일 때만 | — | 4-7절 낱말 검색과 4-8절 벡터 검색을 주제마다 따로 하고 번갈아 담음(로그 `parts N`) |

- 한 낱말 질문의 「흔한 갈래를 섞는다」 메모(4-11절)는 첫 질문에만 붙습니다.
- `QuestionWords`는 끝의 「나·랑」을 앞에 두 글자 이상 남을 때 뗍니다(「세계역사나」 → 「세계역사」). 책 제목에 그 낱말이 통째로 있으면(「코로나」, 「바르셀로나」) 떼지 않고 주제로도 나누지 않습니다(`TitleWords`, 제목 목록은 `SearchBooks`가 읽을 때 넘김).

근거: `AiSearchFlow.answer`·`allChoices`·`fromYear`·`backTopics`·`keywordCandidates`·`partNearest`·`onlyRecency`, `Recency`, `TopicParts`, `TalkRefs.ordered`, `IntentVectors`. 잰 결과: [reports/2026-10-29-follow-ups](reports/2026-10-29-follow-ups.md).

### 4-1. 이른 낱말 검색 (첫 질문만)

- 조건: `history`가 비었을 때만.
- 낱말: `QuestionWords.of(질문)`(4-3절 규칙). 이 낱말로 4-7절과 같은 SQL을 **가상 스레드에서 바로** 시작합니다. `LIMIT` = max(50, 30) = 50, 뺄 책 없음, 중심 낱말 없음.
- 결과를 쓰는 조건(4-7절에서 판단): 벡터 합치기를 하고(`mix`), 뺄 책이 하나도 없고, 중심 낱말이 없고, 최신 조건이 없고(`fromYear` 없음), 주제가 하나이고(4-0-2 「A나 B」 아님), 1차가 정한 낱말 목록이 이 낱말 목록과 **똑같을 때**. 이름 댄 책이 있어 먼저 넣은 책이 있으면(뺄 책이 생김) 쓰지 않고 다시 찾습니다. 실패하면 경고를 남기고 다시 찾습니다.
- 출력: `CompletableFuture<List<Candidate>>`.

근거: `AiSearchFlow.answer`의 `earlyWords`·`earlyKeywords`·`joined`, `MlFirstCall.firstKeywords`.

### 4-2. 질문 벡터 (encode)

- 조건: `first-call.mode=ml`(운영)이고, ML 파일을 읽었고(`MlFirstCall.on`), 벡터가 켜져 있을 때(`QuestionVectors.on`).
- 만드는 글: 첫 질문이면 `[질문]` 1개. 이어 묻기면 `[질문, 앞 질문, 앞 질문 + " " + 질문]` 3개. 대화 말(4-0-1)이면 앞 질문 없이 그 주제 1개만 만들고, 관계는 `first`가 됩니다. 「다 같이」면 「질문」 자리에 선택지를 이은 글이 들어갑니다. 앞 질문도 임베딩 서버로 갑니다(로그에는 남지 않음).
- 계산: 글마다 임베딩 서버에 따로 POST(가상 스레드, 동시에). 각각 3-5절 → `float[1024]`, 길이 1.
- 기다림: 모두 끝날 때까지 최대 **2초**(`vector.max-wait`). HTTP 한 번은 최대 1.5초.
- 안 될 때(어느 경우든 이 검색은 **Haiku 1차**로 감, 6절):
  - 2초 안에 안 끝남 → 기다리던 요청을 취소, 로그 `the question vector was late, haiku makes the first step`.
  - 서버가 200이 아님·응답에 벡터 없음·연결 실패 → 로그 `the question vector failed (...)`.
  - 차단기(circuit breaker)가 열려 있음 → 묻지 않고 바로 Haiku(로그 없음). 차단기: 연속 3번 실패하면(늦음 포함) 30초 동안 묻지 않고, 30초 뒤 한 번만 시험해 성공하면 닫힘. 열리고 닫힐 때 로그 한 줄씩.
- 출력: `List<float[]>` 1개 또는 3개.

근거: `AiSearchFlow.mlStep`, `KureQuestionVectors.vectors`·`counted`, `CircuitBreaker`, `RemoteQuestionEncoder`.

### 4-3. ML 1차 (ml)

입력은 질문 벡터 `v`(그리고 이어 묻기면 앞 질문 벡터 `e`, 이은 글 벡터 `j`)입니다. 모델 호출은 없습니다.

**(가) 한 머리의 계산** (`FirstCallModel.outputs`)
- `h = ReLU(W1 · v + b1)` : 1024 → 512
- `z = W2 · h + b2` : 512 → 출력 수
- route·single 머리: `softmax(z)`(가장 큰 값을 빼고 exp, 합으로 나눔). multi 머리: 칸마다 `1 / (1 + e^(−z))`.

**(나) 길 고르기** (`FirstCallModel.route`)
- 5개 확률 중 가장 큰 것 k. k가 `book`이거나 확률이 0.55 이상이면 k, 아니면 `book`. 즉 `book`이 아닌 길은 0.55 이상일 때만 고릅니다.

**(다) 칸 고르기** (`MlFirstCall.shelves`)
- multi 머리: 기준값 이상인 칸을 **칸 순서대로(확률 순이 아님)** 앞에서 최대 2개.
- single 머리: 가장 큰 칸. `fiction`은 `True`→소설, `False`→소설 아님, `None`→말 안 함.
- 결과 `Shelves`: 원하는 field·subfield·fiction·genre·country·audience·level·form, 빼 달라는 ex_field·ex_subfield·ex_genre.

**(라) 낱말** (`QuestionWords.of`)
1. 빈칸과 `, . ? ! · ~ " ' ( )`에서 자름.
2. 조각 끝의 조사 하나를 뗌: `으로|에서|에게|한테|이랑|하고|까지|부터|처럼|같은|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요`.
3. 2글자 미만, 중복, 아래 말은 버림: 책, 도서, 관련, 사람, 처음, 위한, 대한, 읽는, 추천, 추천해줘, 추천해, 알려줘, 찾아줘, 읽을, 읽기, 좋은, 있어, 있나요, 있을까요, 싶어, 싶은, 거, 것, 걸, 같은, 비슷한, 처럼, 주세요, 해줘, 좀, 그냥, 말고, 빼고, 없는.
4. 앞에서 최대 8개.
- 예(규칙대로 계산한 것): 「잠들기 전에 조금씩 읽을 과학책」 → `잠들기`, `전`(1글자라 버림)… → `[잠들기, 조금씩, 과학책]`.

**(마) 첫 질문의 결과**: `Plan(길, 자기 낱말, 이은 벡터 안 씀, 새 주제 아님, 자기 칸, 관계 "first")`.

**(바) 이어 묻기의 결과** (운영 `follow-up=relation`, `follow-up-model.bin` 있음)
- 관계 신경망 입력 4,096 = `[v (1024), e (1024), j (1024), v⊙e (칸마다 곱, 1024)]` → 512 ReLU → 7 softmax → 가장 큰 관계.
- 이은 낱말 = 자기 낱말 + 앞 질문 낱말(중복 없이).
- pointsBack이 참이고 관계가 `none`이 아니면: 관계와 상관없이 길 `BOOK`, 이은 낱말, 이은 벡터 `j`, 칸 = 자기 칸을 앞 칸 위에 덮음. 로그 관계는 `<관계> joined`.
- 그 밖에는 관계에 따라:

| 관계 | 뜻(코드 주석) | 길 | 낱말 | 검색 벡터 | 칸 |
|---|---|---|---|---|---|
| `refers` | 보여 준 책들 안에서 | `REFERS_SHOWN` | 이은 낱말 | `j` | 덮음 |
| `ask_one` | 보여 준 책 가운데 하나 | `REFERS_SHOWN` | 이은 낱말 | `j` | 덮음 |
| `continue` | 같은 주제를 더 | `BOOK` | 이은 낱말 | `j` | 덮음 |
| `feedback` | 보여 준 답에 대한 반응 | `BOOK` | 이은 낱말 | `j` | 덮음 |
| `shift` | 작은 주제를 옮김(「식단 말고 운동 쪽은?」) | `BOOK` | 자기 낱말 | `j` | 자기 칸, 자기 field가 비면 앞 field |
| `new` | 새 주제 | 길 머리 결과(단 `REFERS_SHOWN`이면 `BOOK`) | 자기 낱말 | `v` | 자기 칸 |
| `none` | 책 찾기 아님 | 길 머리가 `BOOK`·`REFERS_SHOWN`이면 `NOT_BOOK`, 아니면 그대로 | 자기 낱말 | `v` | 자기 칸 |

- 「덮음」(`Shelves.over`): 이번 질문이 준 칸은 이번 것, 비어 있는 칸은 앞 질문 것. 빼 달라는 칸은 둘의 합집합.
- `follow-up-model.bin`이 없을 때만 쓰는 규칙(운영은 안 씀): 이번 질문의 field가 있고 그 가운데 앞 질문 field와 겹치는 것이 없으면 새 주제, 아니면 이어감.

근거: `MlFirstCall.plan`·`features`·`shelves`, `FirstCallModel`, `QuestionWords`, `Shelves.over`·`keepingFieldOf`·`newTopicAfter`.

### 4-4. 가까운 책 (nearest)

- 검색 벡터: 이은 벡터를 쓰기로 했으면 `j`, 아니면 `v`.
- 계산(`BookVectorFile.nearest`, 검색 스레드에서 바로): 81,078줄마다 `점수 = (Σ_{i=0..1023} int8[i] × q[i]) × scale[줄]`. 책 벡터와 질문 벡터가 모두 길이 1이라 이 값은 코사인 유사도에 가깝습니다(int8로 줄인 오차, 만들 때 잰 최악 코사인 0.99975). 크기 600의 최소 힙으로 상위 600개를 남김.
- 개수: `vector-depth × NEAREST_POOL` = 60 × 10 = **600**(v1.22.1~). 최신 책을 앞으로 옮길 여유를 두려는 것이고, 120개로 자르는 일은 4-8절에서 합니다. 질문이 최신 말뿐이면(「요새꺼」) 앞 질문 벡터 `e`로 찾습니다.
- 출력: `List<Long>` 책 id 600개, 가까운 순.
- 이 단계가 예외로 끝나면 이 검색은 Haiku 1차로 감(로그 `the ml first call failed`).

근거: `AiSearchFlow.mlStep`, `KureQuestionVectors.nearest(float[], int)`, `BookVectorFile.nearest`.

### 4-5. 길에 따라 여기서 끝나는 경우

1차 결과를 받은 직후입니다.

- `SITE`: 책을 찾지 않고 4-12절 (가)로 감.
- `NOT_BOOK`(대화 말이 아닐 때): 운영은 되묻기가 켜져 있어(`pick.ask-back=true`) 책을 찾지 않고 4-12절 (나) 대화 호출로 감. 낱말 하나(「엄마」, 「AI」)여도 같습니다(v1.22.x. 그 전에는 낱말 하나면 책 찾기로 바꿨음). 되묻기가 꺼져 있으면 `done "no_match"`.
- `TITLE_LIKE`: 제목 비교를 하고(아래) 보통 검색을 이어감. 비교가 맞으면 4-6절 (다)로 그 제목의 책을 맨 앞에 둠.
  - 비교 글 = 질문 전체. 「글자만」 열쇠로 바꿔 2글자 이상이고, `book_search` 책 가운데 하나라도 (제목 열쇠와 같음) 또는 (제목 열쇠가 그것으로 시작하고 그 길이가 제목의 절반 이상) 또는 (그것이 제목 열쇠로 시작하고 나머지가 저자 열쇠 안에 있음) 또는 (저자 열쇠 안에 있음)이면 `title-guess` 이벤트 `{"title": "<질문>"}`을 보냄.
- `REFERS_SHOWN`: 마지막 턴에 보여 준 책이 있으면 4-6절 (가). 없으면(첫 질문, 되묻기 다음) 보통 검색.

근거: `AiSearchFlow.answer`·`chat`, `TitleMatch.close`.

### 4-6. 앞 책·이름 댄 책 (neighbours)

후보 30자리 가운데 먼저 채울 책입니다. 여기서 고른 책은 `chosen`(순서 있는 집합)에 들어갑니다.

**(가) 앞 책을 가리킬 때** (`REFERS_SHOWN`이고 `lastShown`이 있음)
1. `foryou.book_neighbour`에서 `lastShown` 각 책의 이웃(≤20)을 한 번에 읽음.
2. 물어볼 책 = `lastShown` + 그 이웃 + 그 전 턴들에서 보여 준 책. 한 SQL로 계획 도서관 수를 셈(4-7절의 `planCounts`). 계획 도서관이 0곳인 책은 빠짐.
3. 순서대로 넣음: `lastShown`(가진 것) → 그 전 턴 책(가진 것) → 이웃을 「책마다 1번째 이웃, 다음 책마다 2번째 이웃 …」 차례로, 앞에서 보여 주지 않았고 `book_search`에 있는 것 최대 **12개**. 모두 합쳐 30개까지.

**(나) 질문이 책 이름을 댈 때** (가)가 아닐 때 (`NamedBook.find`)
- 질문을 「글자만」으로 바꾼 뒤 `같은|비슷|처럼|류의|풍의`가 나오는 곳마다, 그 바로 앞 글(끝의 `이랑|하고|와|과|랑`을 뗀 것도)의 끝 40~2글자를 긴 것부터 `book_search` 제목 열쇠(제목에서 첫 `( [ . :` 앞까지의 글자만)와 맞춰 봄. 가장 긴 제목이 이름 댄 책이고, 같은 제목의 모든 판이 그 책입니다.
- 그 판들의 이웃을 읽어, 판 자신·앞에서 보여 준 책·제목에 그 이름이 든 책(다른 권)을 빼고 최대 **20개**. 계획 도서관 수를 세어 가진 것만, 같은 제목은 하나만 `chosen`에 넣음. 로그 `named book`.

**(다) 질문이 책 제목일 때** (`TITLE_LIKE`이고 4-5절 비교가 맞음, (가)도 대화 말도 아님)
- 질문을 「글자만」으로 바꿔 **3글자 이상**(`MIN_TITLE_LETTERS`)일 때만. 「바다」, 「커피」처럼 짧은 말은 주제로 봅니다.
- `NamedBook.titled`: 제목 열쇠가 그 글로 시작하고 나머지가 없거나 숫자로 시작하는(권 번호, 「토지 1」) 책을 짧은 제목부터 최대 **3권**(`MAX_TITLE_BOOKS`). 계획 도서관이 가진 것만, 같은 제목은 하나만.
- 이 책들을 `chosen` **맨 앞**으로 옮기고(이름 댄 책의 이웃보다 앞), 2차에 메모 「사용자가 쓴 「…」는 책 제목이다. 그 제목의 책을 먼저 고르고, 이어서 비슷한 책을 고른다. 되묻지 않는다. 머리말에서 그 책을 찾았다고 말한다.」를 보냄. 이때 되묻기 형식을 쓰지 않음(4-11).

**(라) 「더 쉬운 거」** (관계 `continue`, (가)도 대화 말도 아니고 질문이 `MORE_SO` 「더 ○○운/은/한/는/진/적인 거·걸·것·책·게」에 맞음)
- 마지막에 보여 준 책도 계획 도서관 수를 세어 `chosen`에 넣음(같은 제목은 하나만). 그 가운데 가장 맞는 책을 고를 수 있게 하려는 것이고, 「더 보여줘」는 해당하지 않습니다.

- 출력: `chosen`(≤30)과 그 책들의 계획 도서관 수.

근거: `AiSearchFlow.answer`의 `refers` 부분·`nearby`·`namedNeighbours`·`titleNote`·`MORE_SO`, `NamedBook.find`·`key`·`titled`, `CandidateQuery.planCounts`.

### 4-7. 낱말 후보 (keyword)

**뺄 책(excluded)**
- 앞 책을 가리킴(`refers`), pointsBack, 관계 `continue` 가운데 하나면 앞에서 보여 준 책을 빼지 않음. 아니면 앞에서 보여 준 책을 모두 뺌. 대화 말(4-0-1)이면 그 표의 「앞에서 보인 책」 칸대로(카드 뒤 NOT_THAT만 뺌).
- 4-6절에서 이미 고른 책은 늘 뺌.

**낱말 다듬기** (`CandidateQuery.usableKeywords`)
- 낱말마다 `tidy`(NFKC, 소문자, 빈칸 삭제) → 2글자 이상만 → 중복 없이 → 앞 8개.
- 중심 낱말(core)은 앞 4개를 먼저 두고 낱말을 뒤에 붙임. ML 1차는 중심 낱말이 늘 비어 있고, Haiku 1차만 채웁니다.
- 낱말이 0개거나 계획 도서관이 0곳이면 SQL 없이 빈 목록.

**SQL** (낱말이 `w0, w1, …`이고 각 `:kN` = `'%' + 낱말 + '%'`, `\ % _`는 글자 그대로 맞춤)

```sql
SELECT book_id, score, plan_count FROM (
  SELECT s.book_id,
         (s.search_text LIKE :k0 ESCAPE '\')::int + (s.search_text LIKE :k1 ESCAPE '\')::int + … AS score,
         (SELECT count(*) FROM unnest(s.library_ids) AS l(id)
           WHERE l.id = ANY(CAST(:plan AS integer[])))::int AS plan_count,
         <중심 낱말만의 같은 합, 없으면 0> AS core_score
  FROM catalog.book_search s
  WHERE (s.search_text LIKE :k0 ESCAPE '\' OR s.search_text LIKE :k1 ESCAPE '\' OR …)
    AND s.library_ids && CAST(:plan AS integer[])
    AND NOT (s.book_id = ANY(CAST(:excluded AS bigint[])))
) m
ORDER BY core_score DESC, score DESC, plan_count DESC, book_id
LIMIT :limit
```

- **최신일 때(v1.22.1~)**: 4-0-2절의 첫 해가 있으면 바깥 줄에 `coalesce((SELECT (b.published_on >= make_date(:from,1,1))::int FROM catalog.book b WHERE b.id = s.book_id), 0) AS recent`를 더하고 `ORDER BY core_score DESC, score DESC, recent DESC, plan_count DESC, book_id`. 같은 수의 낱말을 가진 책 가운데 최근 책이 먼저입니다. 이른 검색(4-1)은 쓰지 않습니다.
- **주제가 여럿일 때(v1.22.1~)**: 주제마다 `QuestionWords.of(주제)`를 중심 낱말로, 1차 낱말을 그 뒤에 붙여 `LIMIT` = ⌈50 ÷ 주제 수⌉로 따로 찾고, 1위·1위·2위·2위… 순으로 겹치지 않게 50개까지 담습니다.

- `score` = 들어 있는 낱말 수(낱말 하나에 1점). `plan_count` = 그 책을 가진 계획 도서관 수. 자른 뒤가 아니라 줄 세운 뒤에 `LIMIT`으로 자릅니다. `LIKE '%…%'`는 pg_bigm GIN 인덱스(`book_search_search_text_bigm`)를 탑니다.
- `LIMIT`: 벡터와 합칠 때(`mix`) **50**(= max(`keyword-depth` 50, `candidates` 30)). 합치지 않을 때 30 − (이미 고른 수).
- 4-1절 이른 검색의 조건이 맞으면 이 SQL을 다시 돌리지 않고 그 결과를 씀(로그 `early keyword`).
- **실행 계획 고정(v1.20.1~)**: 이 SQL은 읽기 전용 트랜잭션 안에서 `SET LOCAL plan_cache_mode = force_custom_plan` 뒤에 돌립니다(`JdbcAiSearchIndex.find`). 이 설정이 없을 때 JDBC 드라이버(pgjdbc, `prepareThreshold=5`)는 한 연결에서 같은 꼴의 SQL이 5번을 넘으면 서버 준비문으로 바꾸고, PostgreSQL이 낱말 값과 상관없는 일반 계획을 골라 맞는 줄 전부에 `plan_count`를 셌습니다(「소설」·「위로」 16,418줄, 5.17초 vs 맞춤 계획 0.24초). v1.19.0 이전에도 같았고, 운영에서 v1.20.0 r3·r4 측정에 드러남. 자세한 원리: [사이클 26 일반 계획 설명](../cycle26-book-descriptions/3-postgres-generic-plan.md), 전후 숫자: [첫 글 시간 7절](reports/2026-10-27-latency.md#7-사이클-26-배포-1과-실행-계획-고정-v1200v1201-2026-10-29).
- 출력: `List<Candidate(bookId, score, planCount)>`. 그다음 서버 메모리의 `book_search` 사본에 없는 책(요청 사이에 표가 바뀐 경우)은 버림.

**합치지 않을 때**(`mix`가 거짓: 앞 책을 가리킴, 또는 벡터가 꺼짐): 이 순서 그대로 `chosen` 뒤에 붙이고 4-10절로. 같은 제목 거르기는 하지 않습니다.

근거: `AiSearchFlow.answer`, `CandidateQuery.find`·`usableKeywords`·`escapeLike`·`PLAN_COUNT`, `aisearch/001-aisearch-schema.yaml`(인덱스).

### 4-8. 벡터 후보 (vector)

- 입력: 가까운 책 600개(`vector-depth` × `NEAREST_POOL` 10, v1.22.1~). ML 1차면 4-4절의 것, Haiku 1차로 온 검색은 질문만으로 따로 만든 벡터의 600개(6절). 주제가 여럿이면(4-0-2 「A나 B」) 주제마다 벡터를 만들어(최대 1.5초) 600 ÷ 주제 수개씩 읽고 번갈아 담은 목록을 씁니다(주제 벡터가 안 오면 질문 하나의 것).
- 기다림: 그 결과를 최대 2초 기다림. 늦거나 실패하면 빈 목록(로그 `the question vector was late` / `failed`)이고 낱말 후보만으로 이어감.
- 거르기 차례: `book_search`에 있고 뺄 책이 아닌 것 → 최신이면 앞 240개(`RECENT_WINDOW` 4 × 60) 안에서 출간 연도(`SearchBooks`가 `catalog.book.published_on`에서 읽은 해)가 첫 해 이상인 책을 앞으로 옮김 → 앞 **120개**(`vector-depth` × 2) → 한 SQL(`planCounts`)로 계획 도서관 수를 세어 1곳 이상 가진 것 → 앞에서 **60개**(`vector-depth`).
- 출력: `List<Long>` ≤60, 가까운 순. 계획 도서관 수는 카드용으로 기억.

근거: `AiSearchFlow.vectorCandidates`·`partNearest`, `CandidateQuery.planCounts`.

### 4-9. 합치기 (mix)

낱말 후보 목록(≤50)과 벡터 후보 목록(≤60)을 하나의 순서로 만듭니다(`Fusion.rrf`).

1. 순위 점수(RRF, reciprocal rank fusion): 각 목록에서 책의 순위 r(1부터)마다 `1 / (k + r)`, **k = 60**(`rrf-k`). 두 목록에 다 있으면 두 값을 더함.
   - 예(계산): 한 목록 1위만 = 1/61 = 0.01639. 한 목록 2위 = 1/62 = 0.01613. 두 목록 모두 10위 = 2/70 = 0.02857.
2. 칸 보너스(ML 1차일 때만): `0.004 × 맞은 칸 수`. 맞은 칸 수(0~8) = 책 프로필(`catalog.book_profile`)과 질문 칸을 비교해 field 같음, subfield 같음, fiction 같음, genre 하나라도 겹침, country 같음, audience 같음, level 같음, form 하나라도 겹침을 각 1. 질문이 말하지 않은 칸과 프로필이 없는 책은 0.
   - 크기 비교(계산): 1위와 2위의 차이가 0.00026이므로 칸 하나(0.004)는 위쪽 순위 여러 계단을 올립니다.
3. 합으로 큰 순 정렬. 같으면 낱말 순위 → 벡터 순위 → 책 id 작은 순.
4. 뺄 칸 책을 맨 뒤로(ML 1차일 때만): 질문이 「소설 아님」인데 책이 소설이거나, 책의 field·subfield가 빼 달라는 칸에 있거나, genre가 빼 달라는 장르와 겹치면 그 책들을 같은 순서로 맨 뒤에.
5. 자르기: 이 순서로 `chosen` 뒤에 붙이되, 「글자만」 제목 열쇠가 이미 있는 책(같은 책 다른 판)은 건너뛰고 전체 **30권**에서 멈춤.

- 출력: 후보 `List<Long>` ≤30. 0권이면 4-12절 (라)(책 없음 뒤 선택지 호출 → `ask`) 다음 `done {"result":"no_match","bookIds":[]}`로 끝.

근거: `Fusion.rrf`, `MlFirstCall.bonus`·`rulesOut`, `Shelves.matches`·`excludes`, `AiSearchFlow.answer`.

### 4-10. 카드 재료 (cards)

- 2차에 보낼 것(검색 스레드에서 차례로):
  - 한 줄: `SELECT book_id, line FROM catalog.book_search WHERE book_id = ANY(:ids) AND line IS NOT NULL`.
  - 출간 연도: `BookCards.cards(ids)` → `catalog.book.published_on`의 연도. 실패하면 연도 없이 이어감.
  - 제목·저자: 서버 메모리의 `book_search` 사본.
- 카드에만 쓸 것(2차에 보내지 않음, **가상 스레드에서 2차와 동시에 읽음**):
  - 표지: `catalog.BookTexts.coverUrls(ids)`.
  - 요약: `catalog.BookTexts.summaries(ids)`.
- 카드 한 장 = 제목·저자(`catalog.book`), 표지(목록 책의 `coverPath`가 있으면 그것, 없으면 그 판의 CloudFront 표지, 둘 다 없으면 null), `myLibraryCount`(그 책의 계획 도서관 수, 모르면 0), `aiSummary`(책첵 요약; 목록 요약과 같은 글이라 차례가 없음). 첫 카드를 보낼 때 표지·요약 읽기가 아직이면 그때 기다립니다. 읽기가 실패하면 표지·요약 없이 카드만.

근거: `AiSearchFlow.answer` 끝부분, `CandidateQuery.lines`, `AiSearchFlow.publishYears`, `AiSearchFlow.Cards.card`.

### 4-11. 2차 호출 (llm-first, llm-done)

**되묻기 형식을 쓸지** (`askBack`)
- 참 조건: 운영 `pick.ask-back=true` **그리고** 제목 메모가 없음(4-6 (다)) **그리고** 「낱말 하나」가 아님 **그리고**
  - 대화 말(4-0-1)이면: 되묻기가 NEVER가 아님(NORMAL·FORCE).
  - 아니면: pointsBack이 아님(앞 주제를 합치는 말이나 되묻기의 답은 되묻지 않음) **그리고** (이어 묻기 **또는** 첫 질문의 낱말 수 ≤ **4**). 낱말 수 = 빈칸으로 나눈 조각 수.
- 「낱말 하나」(`oneWordTopic`): 첫 질문, 대화 말·제목 메모 없음, 빈칸이 없고 한글 자모(ㄱ~ㅣ, U+3131~U+318E)를 뺀 글자가 2개 이상(「사진」, 「AI」), `pick.ask-one-word=false`(운영). 되묻지 않고 메모 「질문이 낱말 하나(「…」)다. 되묻지 않는다. 뜻이 여럿이면 흔한 갈래 2~3개의 책을 고루 섞어 고르고…」를 보냄. ML 길이 `NOT_BOOK`인 낱말 하나는 여기까지 오지 않고 4-12절 (나)로 갑니다.
- 참이면 지시문 `PICK_ASK_SYSTEM` + 형식 `PICK_ASK_SCHEMA`, 거짓이면 `PICK_SYSTEM` + `PICK_SCHEMA`.
- 「후보 분야」 줄: askBack이 참이고 (낱말 수 ≤ **2** 또는 대화 말이 FORCE)일 때만. 후보 책들의 프로필 field로 셈: `field + "(소설)"`(fiction 1) / `"(비소설)"`(0) / 붙이지 않음(빈칸). 많은 순, 같으면 이름 순, 앞 **6개**. 프로필 없는 책은 셈에서 빠짐.

**요청 본문** (`AnthropicLlmClient.request`, `POST https://api.anthropic.com/v1/messages`)

```json
{
  "model": "claude-haiku-5-5",
  "max_tokens": 4096,
  "system": [{"type": "text", "text": "<지시문>", "cache_control": {"type": "ephemeral"}}],
  "thinking": {"type": "disabled"},
  "messages": [ …앞 턴…, {"role": "user", "content": "<아래 사용자 글>"} ],
  "output_config": {"effort": "low", "format": {"type": "json_schema", "schema": { … }}},
  "stream": true
}
```
- 헤더: `x-api-key`(비밀값), `anthropic-version: 2026-10-21`, `accept: text/event-stream`.
- `system`이 배열이고 `cache_control`이 붙는 것은 운영 `cache-system=true` 때문입니다(기본 false면 글 그대로). `thinking`은 운영 `disabled`(기본은 칸 자체를 안 보냄). `effort: low`는 `application.yml` 값입니다.
- 시간 한도: 보내기부터 스트림 끝까지 **15초**(`pick-timeout`). 넘으면 별도 스레드가 응답 본문을 닫아 읽기를 끝냄. 연결은 3초 안에 열려야 함.

**앞 턴 메시지** (`Prompts.history`, 앞 턴마다 2개)
- user: 그 턴의 질문.
- assistant: 책을 보여 준 턴이면, 머리말이 있으면 `머리말: <head>` 줄, 이어서 `보여 준 책:` 다음 줄마다 `- <n>번째 책: <제목> / 저자: <저자>`(보인 차례, 저자를 모르면 제목만, 책이 없으면 `(없음)`). 되묻기 턴이면 `되물은 말: <되물은 글>` 다음 줄 `고를 답: <답1> / <답2> …`.

**마지막 user 글** (`Prompts.pickMessages`)
```
올해: 2026년
질문: <이번 질문>
대화 메모: <메모>
고를 수 있는 책:
1. 제목: <제목> / 저자: <저자 또는 (모름)> / 출간: <연도>년 / 한 줄: <한 줄>
2. …
…
30. …
후보 분야: <분야> <n>권, <분야> <n>권, …
```
- 「대화 메모」 줄은 메모가 있을 때만: 대화 말 메모(4-0-1) / 제목 메모(4-6 (다)) / 낱말 하나 메모 가운데 하나(이 차례로 먼저 있는 것). 최신이면(4-0-2) 그 뒤에 「사용자는 최근에 나온 책을 원한다. <첫 해>년 이후에 나온 책을 먼저 고르고, 오래된 책은 맞는 최근 책이 모자랄 때만 고른다…」를 붙임(`withRecency`).
- 「올해」는 서울 기준 연도. 「출간」은 연도를 알 때만, 「한 줄」은 있을 때만, 「후보 분야」 줄은 위 조건일 때만.
- 보내지 않는 것: 회원 번호, IP, 도서관 이름, 표지, 요약.

**지시문 요점** (전문은 `Prompts.PICK_SYSTEM`)
- 아래 번호 안에서만 0~8권. 억지로 고르지 않음.
- 맨 앞 `head`: 한국어 한 문장 머리말. 고를 책을 먼저 정하고 씀: 「골랐다」고 썼으면 `picks`를 비우지 않고, `picks`가 비면 「골랐다」고 쓰지 않음. 고른 책이 없으면 무엇을 찾지 못했는지만 한 문장으로(「쿠버네티스 오퍼레이터만 다룬 책은 찾지 못했어요.」), 「책이 적어요」처럼 뭉뚱그리지 않고, 다시 묻는 방법은 화면 안내가 따로 나오니 쓰지 않음.
- 앞에서 보여 준 책 자체를 물으면(「작가가 누구야?」, 「두 번째 책 어때?」) `head`에서 먼저 답함(앞 대화의 제목·저자·머리말로, 이때 2~3문장 가능). 그다음 이어지는 책을 고름.
- `fit` 0~3: 3 중심 주제와 조건 다 맞음, 2 중심 주제는 맞고 조건은 덜 맞거나 모름, 1 관련은 있으나 중심 주제가 다름(책 정보가 조건과 반대여도 1), 0 안 맞음. **fit 2 이상만** 고르고, 중심 주제가 맞는 책이 여럿이면 8권까지 채움.
- 책마다 `text`: 한국어 2~3문장, 왜 맞는지. 책 정보(제목·저자·한 줄)로 확인되는 것만. 「후보」, 「목록」, 책 번호를 쓰지 않음. 출간 연도·올해는 질문이 시기를 물을 때만.
- 앞 대화에 「되물은 말」이 있으면 이번 질문은 그 답으로 읽음. 앞 주제 둘을 합치라면 둘을 함께 다룬 책을 먼저.
- 맨 끝 `tail`(상황별 정리): 고른 책이 2권 이상이면 `lines`에 상황 2~4개. 상황마다 `situation`(사용자가 말한 것보다 한 걸음 구체적인 짧은 구, 30자 안)과 `nos`(그 상황에 맞는 고른 책 번호 1~3개, 한 책은 한 상황에만). 제목은 서버가 넣으므로 쓰지 않음. `summary`는 빈 글. 고른 책이 0~1권이거나 되물을 때는 `situation` 빈 글·`nos` 빈 목록인 항목 하나만.
- `PICK_ASK_SYSTEM`은 위에 더해: 맨 앞 `ask`는 질문만으로 고를 책의 갈래가 크게 달라질 때만(낱말 하나에 뜻이 여럿, 「아니 그런 거 말고」, 「음...」). 되물을 때는 `question`(해요체 한두 문장. 화면에 입력창 안내가 따로 나오니 직접 적어 달라는 말은 쓰지 않음) + `choices` 2~4개(짧은 명사구), `head`는 빈 글, `picks`와 `tail.lines`는 빈 목록. 「후보 분야」가 한 분야에 몰리면 되묻지 않음.

**JSON 형식**
- `PICK_SCHEMA`: `{"head": string, "picks": [{"no": integer, "fit": integer, "text": string}], "tail": {"lines": [{"situation": string, "nos": [integer]}], "summary": string}}`. `tail.lines`는 `minItems` 1, 모든 칸 필수, 다른 칸 없음.
- `PICK_ASK_SCHEMA`: 맨 앞에 `"ask": {"question": string, "choices": [string]}`이 더 있음.
- 형식을 강제하므로 칸이 이 순서(ask → head → picks → tail)로 옵니다. 그래서 서버는 `no`와 `fit`을 다 읽은 뒤 그 책의 `text`가 오기 전에 카드를 보낼지 정할 수 있습니다.

**받는 스트림**: Anthropic SSE의 `message_start`(입력 토큰 = `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`), `content_block_delta`/`text_delta`(글 조각 → 바로 파서로), `message_delta`(누적 출력 토큰, 멈춘 까닭), `message_stop`(끝), `error`(`overloaded_error`면 OVERLOADED). 첫 글 조각이 온 때 `llm-first`, 끝난 때 `llm-done`을 기록.

근거: `AiSearchFlow.picks`·`words`, `MlFirstCall.shelfCounts`, `Prompts`(`PICK_SYSTEM`, `PICK_ASK_SYSTEM`, `PICK_SCHEMA`, `PICK_ASK_SCHEMA`, `pickMessages`, `history`, `appendChoices`, `MAX_SHELVES`), `AnthropicLlmClient.request`·`stream`·`readEvents`·`dispatch`, `LlmConfiguration`, `AnthropicProperties`.

### 4-12. 스트림 읽기 → SSE 이벤트

`PickStreamParser`가 JSON 조각을 한 글자씩 읽으며(이스케이프·서로게이트 쌍이 조각 사이에서 잘려도 이어 읽음) 알려 주면, 서버가 아래처럼 이벤트를 보냅니다. SSE 한 건 = `event:<이름>` 줄 + `data:<JSON>` 줄 + 빈 줄. JSON의 null 칸도 그대로 `null`로 갑니다.

| 파서가 알림 | 서버가 하는 일 | 이벤트 |
|---|---|---|
| `ask.question` 글이 끝남 | 되묻기 글로 기억 | (아직 안 보냄) |
| `ask.choices`의 한 항목이 끝남 | 되묻기 글이 있으면, 최대 4개, 각 40글자에서 자름 | (아직 안 보냄) |
| `head` 글 조각 | 되묻기 글이 없을 때만 | `head-delta` `{"text": "<조각>"}` |
| `picks[].no` 끝 | 되묻기면 무시. 이미 8권 보냈거나, 1~후보 수 밖이거나, 앞에 나온 번호면 무시. 아니면 그 번호의 책을 「대기」 | — |
| `picks[].fit` 끝 | 대기 책이 있고 fit ≥ **2**면 카드 보냄. 아니면 그 책은 카드도 글도 안 보냄 | `card` `{"no": <1부터 순번>, "bookId", "title", "author", "coverUrl", "myLibraryCount", "aiSummary"}` |
| `picks[].text` 조각 | 지금 카드가 있을 때만. 카드 하나에 합쳐 **300글자**(코드포인트)까지, 넘는 부분은 잘라 버림 | `card-delta` `{"bookId": <id>, "text": "<조각>"}` |
| 한 pick 끝 | 대기·지금 카드를 비움 | — |
| `tail` 안 문자열 조각 | 길이만 셈(정리 글은 끝난 뒤 전체 답에서 읽음). 합쳐 **500자**(`TAIL_MAX_CHARS`)를 넘으면 스트림을 끊음. 카드는 이미 나갔으므로 실패로 치지 않고 로그 `tail too long` | — |

스트림이 끝난 뒤:

| 상황 | 이벤트 | `done.result` |
|---|---|---|
| 정상 끝이고 되묻기 글이 있음 | `ask` `{"question": "...", "choices": [...]}` | `asked` |
| 실패이고 보낸 카드 0장 | 후보 앞에서부터 최대 8권을 글 없이 `card`로 | `no_text` |
| 실패이고 카드 1장 이상 | — | `partial` |
| 정상 끝이고 카드 0장 | 아래 (바) 책 없음 뒤 선택지 호출 → 선택지가 있으면 `ask` | `no_match` |
| 정상 끝이고 카드 1장 | — | `ok` |
| 정상 끝이고 카드 2장 이상 | 상황별 정리를 만들면 `tail-delta` `{"text": "<줄들>"}` 1번 | `ok` |

- 상황별 정리(`TailLines.render`): `tail.lines`의 번호를 후보 순서로 읽어, 보낸 카드인 책만 「`<상황>: 『제목』, 『제목』`」 한 줄로(줄 최대 4개, 상황 40자에서 자름, 한 책은 한 줄에만, 책이 안 남은 줄은 버림). 한 줄도 안 남으면 번호를 카드 순서(1 = 첫 카드)로 다시 읽음. 그래도 없으면 `summary`가 『』·「n번」이 없고 300자 이하일 때 그 글. 못 만들면 보내지 않고 로그 `tail <까닭>`(`none` / `unshown <번호들>` / `bad summary` / `too long`, `TailLines.whyNone`).
- 마지막은 늘 `done` `{"result": "<위 값>", "bookIds": [보낸 카드 책 id, 순서대로]}`.
- 「실패」 = Anthropic 호출이 예외로 끝남(시간 초과, HTTP 오류, 과부하, 연결 끊김) 또는 파서가 JSON 끝(`}`)까지 못 읽음(`max_tokens` 4096에서 잘림 포함) 또는 파서가 JSON이 아닌 글을 만남. `tail`이 500자를 넘어 끊은 것은 실패가 아님.
- 사용자가 떠나면(SSE 쓰기 실패·스트림 시간 초과) 다음 글 조각에서 호출을 끊고 `done`을 보내지 않음. 로그 `client gone`.
- 검색 중 예상 못 한 예외: `error` `{"code": "internal_error"}`.

**2차 스트림 밖에서 나가는 답**

(가)·(나)·(라)·(바)의 호출은 모두 Haiku 1번, 스트림 아님, `max_tokens` 512(`first-call.site-max-tokens`), 15초(`pick-timeout`), 앞 턴 메시지(위 「앞 턴 메시지」와 같은 꼴) + 이번 질문입니다. (나)·(라)·(바)는 형식 `CHAT_SCHEMA` `{"question": string, "choices": [string]}`이고, 선택지는 최대 4개·각 40글자에서 자릅니다.

| 경우 | 언제 | 이벤트 | 모델 호출 |
|---|---|---|---|
| (가) 책첵 질문 | 길 `SITE`, 대화 말 아님 | `SITE_SYSTEM`(책첵 소개·첫 화면 안내 글, 형식 `{"answer": string}`). 답이 비어 있지 않으면 `head-delta` 1번(답 전체) → `done "site"`. 실패하면 `done "no_match"` | 1번 |
| (나) 책 찾는 말 아님 | 길 `NOT_BOOK`(낱말 하나 포함), 대화 말 아님, 되묻기 켜짐 | 대화 호출 `CHAT_SYSTEM`: 그 말에 사람처럼 답하고 책 이야기로 이끄는 `question` + 바로 찾을 수 있는 질문 `choices` 3개. 둘 다 있으면 `ask` → `done "asked"`(로그 `ask chat`). 실패하거나 비면 고정 되묻기 `ask` `{"question": "어떤 책을 찾으세요? 이렇게 물어보셔도 돼요.", "choices": [10개 예시 중 섞어서 3개]}` → `done "asked"`(로그 `ask not-book-ask`). 되묻기가 꺼져 있으면 호출 없이 `done "no_match"` | 1번(꺼짐이면 없음) |
| (다) 제목 같음 | 길 `TITLE_LIKE`이고 4-5절 비교가 맞음 | 맨 앞에 `title-guess` `{"title": "..."}`, 그다음 보통 검색(4-6 (다)) | 2차는 있음 |
| (라) 후보 0권 | 4-9절 결과가 비었음 | 책 없음 뒤 선택지 호출 `NO_MATCH_SYSTEM`: 그 말에 짧게 답하고 못 찾았다고 말한 뒤 다른 방향을 권하는 `question` + `choices` 3개. 선택지가 있으면 `ask`(머리말이 없으므로 `question`도 담음, 로그 `ask no-match choices`) → `done "no_match"`. 실패하거나 선택지가 없으면 `ask` 없이 `done "no_match"` | 1번(되묻기 꺼짐이면 없음) |
| (마) 2차 실패 | 4-12절 | `card`(글 없음) ×≤8 → `done "no_text"`, 또는 `done "partial"` | 2차 |
| (바) 2차가 0권 | 정상 끝, 되묻기 아님, 카드 0장 | (라)와 같은 호출. 2차가 머리말을 썼으면 `ask`의 `question`은 빈 글(선택지만) → `done "no_match"` | 2차 + 1번 |

- (나)의 고정 예시 10개: 요즘 많이 읽는 소설 / 마음이 편해지는 에세이 / 출근길에 가볍게 읽을 책 / 잠들기 전에 읽기 좋은 책 / 처음 읽는 경제 교양서 / 웃기고 가벼운 소설 / 반전이 있는 추리 소설 / 아이와 함께 읽을 그림책 / 생각이 깊어지는 인문학 책 / 쉽게 읽는 과학책.
- 호출이 실패해도 `NOT_CONFIGURED`(API 키 없음)가 아니면 토큰·비용은 셉니다(4-13).

근거: `PickStreamParser`, `TailLines.render`·`whyNone`, `AiSearchFlow.picks`·`chat`·`noMatchChoices`·`site`·`answer`·`finish`·`run`, `AiSearchEvents`, `Prompts.SITE_SYSTEM`·`SITE_SCHEMA`·`CHAT_SYSTEM`·`NO_MATCH_SYSTEM`·`CHAT_SCHEMA`·`NOT_BOOK_ASK`·`NOT_BOOK_CHOICES`, `AiSearchController.EmitterSink`.

### 4-13. 끝: 기록과 비용

- 모델 호출이 끝날 때마다 `aisearch.daily_usage`에 `llm_call_count + 1`, 입력·출력 토큰, 비용을 더함. 비용(백만분의 1달러, 올림) = 입력 토큰 × 0.10 + 출력 토큰 × 0.50(가격은 100만 토큰당 US$). 출력 토큰을 모른 채 끊긴 호출은 `max_tokens`만큼 출력한 것으로 셈. 캐시에서 읽은 입력 토큰도 같은 값으로 셈. API 키가 없어 보내지도 않은 호출은 세지 않음.
- 검색 하나가 끝나면 `usagelog.ai_search_event` 1행(`UsageLog.recordAiSearch`): 걸린 ms, `turnNo`, 1차 결과(`OK`/`FAILED`/`TIMEOUT`, ML 1차는 늘 `OK`), 제목 같음, 2차 결과(`OK`/`NO_MATCH`/`NO_TEXT`/`PARTIAL`/`SKIPPED`), 후보 수, 보여 준 책 id, 토큰 합. 질문과 history는 남기지 않음.
- 로그 한 줄(7절), 그리고 스트림을 닫음.

근거: `AiSearchFlow.count`·`run`의 `finally`, `DailyBudget.addCall`·`costMicroUsd`, `UsageLog.AiSearchRecord`.

## 5. 웹 화면

- 시작: 첫 화면 AI 탭(`AiSearchTab`)에서 2글자 이상 쓰면 **새 대화**로 시작(앞 대화는 버림) → `/ai-search`로 이동. 대화 화면 아래 칸에서 이어 묻기(Enter 보내기, Shift+Enter 줄바꿈, 200자 넘게는 못 씀, 180자부터 `n/200` 표시).
- 보낼 때(`AiChatPage.send`):
  - `history` = 이번 턴 앞의 턴 가운데 상태가 `done`인 것만, 마지막 **9개**(`HISTORY_SENT` = `TURNS_MAX` − 1). 책을 보여 준 턴은 `{question, bookIds, head}`(`head`는 머리말이 있을 때만), 되묻기 턴은 `{question, bookIds: [], askQuestion, askChoices}`이고 `askQuestion` = 되묻기 글, 그것이 비면(책 없음 뒤 선택지) 그 턴의 머리말(`ask.question || head`).
  - `turnNo` = min(대화 안 순번, `TURNS_MAX` 10). 서버는 1~20을 받음. 지역 = 지역 설정(`useRegions`)의 사는 곳·일하는 곳·고른 도서관. `scope` = `joinable` 고정, `visit` = 찾기 설정.
  - 지역을 아직 읽는 중이면 기다렸다가 보냄. 한 번에 한 질문만(답을 받는 동안 보내기 단추가 꺼짐).
- 이벤트를 그리는 법(`apply`, `Answer`):

| 이벤트 | 화면 |
|---|---|
| (첫 이벤트 전) | 「책을 찾고 있어요」 |
| `title-guess` | 「「<제목>」을(를) 찾으시나요?」 + [직접 찾기에서 보기] → `/search?title=<제목>` |
| `head-delta` | 머리말에 이어 붙임(글자 그대로, 마크업 아님) |
| `card` | 카드 추가: 표지(없으면 빈 칸), 제목, 저자, 「내 도서관 n곳」. 같은 책 id가 이미 있으면 무시. 누르면 추천도서와 같은 책 창(`PicksBookDialog`, `aiSummary` 보임) |
| `card-delta` | 그 카드 글에 이어 붙임 |
| `ask` | 되묻기 글을 머리말 자리에(글이 빈 글이면 안 그림. 책 없음 뒤 선택지는 머리말 아래라 비어 옴), 고를 답을 번호 붙은 상자 하나로(`NumberedChoices`). 누르면 그 답이 다음 질문이 됨(마지막 턴만 누를 수 있음). 마지막 턴이면 「고르시면 그 답으로 이어서 찾아요. 원하는 게 없으면 아래 입력창에 적어 주세요.」(폰에서도 같음) |
| `tail-delta` | 정리 글에 이어 붙임. 카드가 1장 이상일 때만 카드 아래에 그림(`Tail`): 줄이 모두 「`상황: 『제목』…`」 꼴이면 제목 「상황별로 고르면」 + 상황(굵게)과 책 목록, 아니면 한 문단 |
| `done "ok"` | 그대로 |
| `done "no_match"` | 머리말은 그대로 둠. `ask` 이벤트가 왔으면 위 번호 상자가 대신 나옴. 안 왔으면: 머리말이 없을 때만 「맞는 책을 찾지 못했어요. 다른 말로 써 보시겠어요?」, 마지막 턴이면 「조금 넓은 주제나 다른 말로 바꿔 보세요. 찾는 책의 주제나 읽고 싶은 상황을 아래 입력창에 적어 주세요.」(`noMatchHint` + `noMatchHintOwn`). 예시 단추는 없음 |
| `done "no_text"` | 머리말 자리에 「지금은 설명을 쓰지 못했어요」 + 글 없는 카드 |
| `done "partial"` | 카드 아래 작은 글로 「지금은 설명을 쓰지 못했어요」 |
| `done "site"` | 머리말 + 「책첵이란? 자세히 보기 →」(`/about`) + 「답은 책첵 소개와 첫 화면 안내를 바탕으로 AI가 썼어요.」 |
| `done "asked"` | 되묻기 그대로 |
| `error` | 카드가 있으면 `partial`처럼, 없으면 「답을 쓰다가 문제가 생겼어요」 + [다시 시도] |
| 스트림이 `done` 없이 끝남·연결 끊김 | 「연결이 원활하지 않아요」 + [다시 시도] |

- 웹이 덧붙이는 것: 입력 칸 아래 늘 「책 설명은 AI가 질문에 맞춰 썼어요. 실제 내용과 다를 수 있어요」. 지역도 도서관도 고르지 않았으면 마지막 답 아래 「누구나 빌릴 수 있는 도서관에서만 찾고 있어요. 더 많은 도서관에서 찾으려면 마이페이지에서 지역과 도서관을 골라 주세요」.
- 「내 도서관 n곳」 다시 세기: 답이 끝났을 때와 지역 설정이 바뀔 때마다, 대화에 나온 모든 카드 책 id(중복 없이. 웹은 많아야 10턴 × 8권 = 80개. 서버는 160개까지 받고 넘으면 400)로 `POST /api/v1/ai-searches/library-counts`를 보내 숫자만 바꿈. 이 주소는 모델을 부르지 않아 IP 한도·비용에 들지 않음(같은 `planSearch` + `planCounts` SQL).
- 10턴 제한(`TURNS_MAX`): 대화가 10턴이 되면 입력 칸 대신 「새 대화를 시작해 주세요」 + [새 대화].
- 저장: 브라우저 `sessionStorage` 열쇠 `chaekcheck.aiChat`(탭을 닫으면 사라짐, 새로 고침에는 남음). 새로 고침 때 받는 중이던 턴은 다시 받지 않음: 카드가 있으면 `partial`, 없으면 「연결이 원활하지 않아요」. 서버에는 대화를 저장하지 않음.

근거: `web/src/features/aisearch/AiChatPage.tsx`(`send`, `apply`, `Answer`, `noticeOf`, `refusal`, library-counts 효과), `aiChatStore.ts`, `aiSearchText.ts`, `AiSearchTab.tsx`, `web/src/api/aiSearch.ts`, `AiSearchController.libraryCounts`.

## 6. 실패·대체 길

일어나는 차례대로입니다.

| 차례 | 무엇이 안 됨 | 서버가 하는 일 | 사용자에게 보이는 것 |
|---|---|---|---|
| 1 | 시작 때 모델 폴더 파일이 없거나 못 읽음 | 벡터 끔(`ai search vectors off: …`) 또는 ML 1차 끔(`ai search first call: haiku, the ml one is off: …`). 모든 검색이 Haiku 1차, 벡터 없으면 낱말 후보만 | 답은 그대로 나옴(느리거나 덜 맞을 수 있음) |
| 2 | 질문 벡터가 2초 넘게 걸림·실패·차단기 열림 | 이 검색만 **Haiku 1차**: `KEYWORD_SYSTEM` 지시문, 앞 턴 + 질문, 형식 `{notBook, titleLike, title, refersToShown, keywords[], core[]}`, `max_tokens` 512, 5초, 스트림 아님(`stop_reason`이 `end_turn`이 아니면 실패). 동시에 질문 하나로 벡터를 다시 요청(`vectors.nearest(질문, 600)`, 차단기가 열려 있으면 바로 빈 목록). 이 길에서는 칸이 없어 칸 보너스·뒤로 보내기가 없고, `core`가 있으면 4-7절 SQL의 `core_score`가 쓰이며, `notBook`이면 4-12절 (나) 대화 호출로, `titleLike`면 Haiku가 적은 `title`이 질문 안에 있을 때 그 글, 아니면 질문 전체로 제목 비교 | 같은 모양의 답 |
| 3 | Haiku 1차도 실패(시간 초과·HTTP·형식) | 낱말 = 질문을 빈칸으로 자른 조각들, 길은 「책 찾기」, 1차 결과 `FAILED`/`TIMEOUT` | 같은 모양의 답 |
| 4 | 4-8절에서 벡터가 2초 안에 안 옴 | 벡터 후보 0개, 낱말 후보만 RRF | 같은 모양의 답 |
| 5 | 표지·요약 읽기 실패 | 카드에 표지·요약 없이 | 빈 표지 |
| 6 | 출간 연도 읽기 실패 | 2차 목록에 「출간」 없이 | 같음 |
| 7 | 2차 실패, 카드 0장 | 후보 앞 8권을 글 없이 → `no_text` | 「지금은 설명을 쓰지 못했어요」 + 카드 |
| 8 | 2차 실패, 카드 1장 이상 | `partial` | 카드 + 작은 글 「지금은 설명을 쓰지 못했어요」 |
| 9 | 책첵 질문 호출 실패(4-12 (가)) | `no_match` | 「맞는 책을 찾지 못했어요…」 + 마지막 턴이면 안내 문구. 예시 단추 없음 |
| 9-1 | 책 찾는 말 아님의 대화 호출 실패·형식 틀림·빈 답(4-12 (나)) | 고정 되묻기(「어떤 책을 찾으세요? …」 + 예시 10개 중 3개) → `asked`, 로그 `ask not-book-ask` | 질문 + 번호 상자 |
| 9-2 | 책 없음 뒤 선택지 호출 실패·선택지 없음(4-12 (라)·(바), `NO_MATCH_SYSTEM`) | 더 보내지 않고 `no_match` | 머리말(있으면) 또는 「맞는 책을 찾지 못했어요…」 + 마지막 턴이면 안내 문구 |
| 10 | API 키가 없음 | 모든 호출이 보내지 않고 실패(`NOT_CONFIGURED`), 비용 안 셈 → 위 3·7·9·9-1·9-2 | 위와 같음 |
| 11 | IP 한도(429) / 동시 실행 자리 없음(503 `server_busy`) | 스트림을 열지 않음 | 「잠시 뒤에 다시 써 주세요」 |
| 12 | 오늘 비용 US$2 다 씀(503 `ai_search_paused`) | 스트림을 열지 않음. 이미 도는 검색은 끝까지 감 | 「오늘은 AI 책 찾기를 쉬어요. 직접 찾기를 써 주세요」 + [직접 찾기로] |
| 13 | AI 검색 꺼짐(404) | | 쉬는 안내 + 첫 화면에서 AI 탭 숨김 |
| 14 | 본문·지역 오류(400·422) | | 「답을 쓰다가 문제가 생겼어요」 + [다시 시도] |

근거: `KureQuestionVectors` 생성자, `MlFirstCall` 생성자, `AiSearchFlow.mlStep`·`keywords`·`vectorCandidates`·`publishYears`·`Cards`·`picks`·`site`·`chat`·`noMatchChoices`, `AnthropicLlmClient.complete`·`request`, `Prompts.KEYWORD_SYSTEM`·`KEYWORD_SCHEMA`, `AiChatPage.refusal`·`noticeOf`.

## 7. 로그 한 줄

검색마다 끝에 `AiSearchFlow` 로거가 INFO로 남깁니다.

형식:
```
ai search: first <1차>, keyword <1차 결과>, pick <2차 결과>, candidates <후보 수> (vector <벡터 후보 수>[, named book][, early keyword][, talk <종류> <되묻기>]), cards <카드 수>, <걸린 ms> ms, tokens <입력>/<출력>[, ask <되묻기>][, tail <정리 글>][, from <첫 해>][, parts <주제 수>][, text <카드 글 글자 수>[ (long <넘친 카드 수>)]][, client gone][, steps <단계=ms …>]
```

| 칸 | 값 |
|---|---|
| `first` | ML 1차면 `ml <길> <관계>`(길: `NOT_BOOK`·`TITLE_LIKE`·`REFERS_SHOWN`·`SITE`·`BOOK`, 관계: 첫 질문 `first`, 이어 묻기 `refers`·`ask_one`·`continue`·`shift`·`feedback`·`new`·`none`, pointsBack이면 뒤에 ` joined`, 규칙이면 `rule new`/`rule continue`). Haiku 1차면 `haiku` |
| `keyword` | `OK`, `FAILED`, `TIMEOUT`(Haiku 1차 결과. ML은 `OK`) |
| `pick` | `OK`, `NO_MATCH`, `NO_TEXT`, `PARTIAL`, `SKIPPED`(2차를 안 했거나 책 없이 끝남: 되묻기(2차의 되묻기, 책 찾는 말이 아니고 되묻기가 켜졌을 때의 대화·고정 되묻기 포함), 책첵 답, 카드 0장에서 사용자가 떠남). 카드 1장 이상 보낸 뒤 사용자가 떠나면 `PARTIAL`. `NO_MATCH` = 후보가 0권, 2차가 카드 0장(이 둘은 뒤에 `ask no-match choices`가 붙을 수 있음), 책첵 답 실패, 책 찾는 말이 아니고 되묻기가 꺼짐 |
| `candidates` | 2차에 보낸 책 수(0~30) |
| `vector` | RRF에 넣은 벡터 후보 수(0~60) |
| `named book` | 질문이 책 이름을 대서 그 이웃을 먼저 넣음 |
| `early keyword` | 4-1절 이른 낱말 검색 결과를 씀 |
| `talk` | 대화 말(4-0-1). 종류 `DONT_KNOW`·`NOT_THAT`·`BACK_TO`, 되묻기 `NORMAL`·`NEVER`·`FORCE` |
| `cards` | 보낸 카드 수(0~8) |
| `ms` | 요청이 들어온 때부터 끝까지 |
| `tokens` | 이 검색의 모든 호출 합. 입력은 캐시 토큰 포함, 출력은 모르면 `max_tokens` |
| `ask` | `single-ask`(2차가 되물음), `chat`(책 찾는 말이 아니어서 대화 호출이 되물음), `not-book-ask`(대화 호출이 실패해 고정 되묻기), `no-match choices`(책 없음 뒤 선택지를 보냄) |
| `tail` | 상황별 정리 글이 길어서 끊김(`too long`, v1.21.x). v1.22.2부터 카드 2장 이상인데 정리 글이 없으면 그 까닭: `none`(lines·summary가 빔), `unshown <번호들>`(번호가 보인 카드가 아님), `bad summary` |
| `text` | 2차가 카드 글에 쓴 글자 수(카드 상한 300자를 넘겨 버린 글자 포함). `long N`은 상한을 넘긴 카드 수(v1.22.2) |
| `from` | 최신 조건의 첫 해(4-0-2, v1.22.1~) |
| `parts` | 주제를 나눠 찾은 수(4-0-2, v1.22.1~) |
| `client gone` | 사용자가 떠남 |
| `steps` | 단계가 끝난 때(요청 시작부터 ms). 차례: `encode`(질문 벡터) `ml`(1차 신경망) `nearest`(가까운 책 600) — 이 셋은 ML 1차일 때만 — `neighbours`(앞 책·이름 댄 책) `keyword`(낱말 후보) `vector`(벡터 후보, 합칠 때만) `mix`(RRF) `cards`(한 줄·연도) `llm-first`(2차 첫 글 조각) `llm-done`(2차 끝) |

예(형식을 보이려고 만든 줄이며 실제 로그가 아닙니다):
```
ai search: first ml BOOK first, keyword OK, pick OK, candidates 30 (vector 60, early keyword), cards 8, 6400 ms, tokens 8500/1400, steps encode=150 ml=162 nearest=260 neighbours=261 keyword=262 vector=270 mix=271 cards=375 llm-first=1700 llm-done=6390
```

- 질문, 앞 질문, 모델 글, API 키는 어느 로그에도 남지 않습니다.
- 운영에서 잰 단계별 시간(중간값 encode 146ms, ml 12ms, nearest 97ms, keyword 176ms 등)은 [응답 시간 보고서 1절](reports/2026-10-27-latency.md#1-지금-시간이-어디에-쓰이나-운영-v1180)에 있습니다(v1.18.0 기준. v1.19.0에서 5낱말 이상 첫 질문의 낱말 검색 대기가 176 → 0ms가 된 것은 같은 보고서 6절).

근거: `AiSearchFlow.run`의 `log.info`, `AiSearchFlow.Run.mark`.

## 8. 설정 값

「코드 기본」은 `backend/src/main/resources/application.yml`(괄호 안은 그 값을 바꾸는 환경 변수), 「운영」은 `deploy/prod/prod.env`(없으면 코드 기본이 그대로) 또는 `deploy/prod/compose.yaml`입니다. 앞에 `chaekcheck.aisearch.`를 줄였습니다.

| 이름 | 코드 기본 | 운영 | 뜻 |
|---|---|---|---|
| `enabled` | false (`CHAEKCHECK_AISEARCH_ENABLED`) | **true** | 꺼지면 주소가 404, 웹이 AI 탭을 숨김 |
| `zone` | Asia/Seoul | 같음 | 하루 한도·비용의 날짜 |
| `keyword-timeout` | 5s | 같음 | Haiku 1차 한도(ML 1차에는 안 씀) |
| `pick-timeout` | 15s | 같음 | 2차 전체, 책첵 답 호출 한도 |
| `keyword-max-tokens` | 512 | 같음 | Haiku 1차 출력 상한 |
| `pick-max-tokens` | 4096 (v1.21.0~) | 같음 | 2차 출력 상한 |
| `max-concurrent` | 20 | 같음 | 서버 하나에서 동시에 도는 AI 검색 |
| `rate.per-minute` / `rate.per-day` | 10 / 100 | 같음 | IP 하나의 한도 |
| `budget.daily-usd` | 2 | 같음 | 하루 비용 상한(시작 때만 검사) |
| `price.input-per-mtok` / `output-per-mtok` | 0.10 / 0.50 | 같음 | 비용 계산 가격(US$/100만 토큰) |
| `limits.candidates` | 30 | 같음 | 2차에 보내는 책 |
| `limits.picks` | 8 | 같음 | 답 하나의 카드, history 턴의 책 |
| `limits.history-turns` | 9 (v1.21.0~) | 같음 | 받는 앞 턴 |
| `limits.question-max-length` | 200 | 같음 | 질문 글자 수 |
| `limits.card-text-max-chars` | 300 | 같음 | 카드 글 글자 수 |
| `vector.enabled` | false (`CHAEKCHECK_AISEARCH_VECTOR_ENABLED`) | **true** | 질문 벡터·벡터 후보 |
| `vector.model-dir` | 빈 값 (`CHAEKCHECK_AISEARCH_MODEL_DIR`) | **`/models/aisearch`**(compose.yaml) | 모델 폴더 |
| `vector.threads` | 2 | 같음 | 백엔드 안에서 돌릴 때의 스레드(운영은 안 씀) |
| `vector.keyword-depth` | 50 | 같음 | RRF에 넣는 낱말 후보 |
| `vector.vector-depth` | 60 | 같음 | RRF에 넣는 벡터 후보(가까운 책은 그 10배 600을 읽어 2배 120에서 자름) |
| `vector.rrf-k` | 60 | 같음 | RRF의 k |
| `vector.max-wait` | 2s | 같음 | 질문 벡터를 기다리는 시간 |
| `vector.encoder` | local (`CHAEKCHECK_AISEARCH_VECTOR_ENCODER`) | **remote** | 질문 벡터를 만드는 곳 |
| `vector.remote.url` | 빈 값 (`CHAEKCHECK_AISEARCH_EMBED_URL`) | **`http://172.31.45.141:8090`** | 임베딩 서버 |
| `vector.remote.token` | 빈 값 (`CHAEKCHECK_AISEARCH_EMBED_TOKEN`) | Parameter Store 비밀값 | `X-Embed-Token` |
| `vector.remote.timeout` | 1500ms | 같음 | 임베딩 요청 하나 |
| `vector.failures-to-open` / `open-for` | 3 / 30s | 같음 | 차단기 |
| `first-call.mode` | haiku (`CHAEKCHECK_AISEARCH_FIRST_CALL`) | **ml** | 1차를 누가 |
| `first-call.shelf-bonus` | 0.004 | 같음 | 맞은 칸 하나당 RRF 보너스 |
| `first-call.site-max-tokens` | 512 | 같음 | 책첵 답 출력 상한 |
| `first-call.follow-up` | relation (`CHAEKCHECK_AISEARCH_FOLLOW_UP`; 값이 비면 코드가 rule) | relation | 이어 묻기를 관계 신경망으로 |
| `pick.ask-back` | false (`CHAEKCHECK_AISEARCH_PICK_ASK_BACK`) | **true** | 되묻기 |
| `pick.ask-one-word` | false (`CHAEKCHECK_AISEARCH_PICK_ASK_ONE_WORD`) | 같음(`deploy/prod/compose.yaml`이 넘기지 않음) | 첫 질문이 낱말 하나여도 되묻기 형식을 씀(v1.17.0~v1.20.5 방식) |
| `llm.anthropic.api-key` | 빈 값 (`CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY`) | Parameter Store 비밀값 | 비면 모든 호출이 실패 → 대체 길 |
| `llm.anthropic.model` | claude-haiku-5-5 | 같음 | 1차(Haiku)·2차·책첵 답 모델 |
| `llm.anthropic.base-url` / `version` | https://api.anthropic.com / 2026-10-21 | 같음 | API 주소, `anthropic-version` |
| `llm.anthropic.effort` | low | 같음 | `output_config.effort` |
| `llm.anthropic.thinking` | 빈 값 = 칸을 안 보냄 (`CHAEKCHECK_AISEARCH_LLM_THINKING`) | **disabled** | 생각 단계 |
| `llm.anthropic.cache-system` | false (`CHAEKCHECK_AISEARCH_LLM_CACHE_SYSTEM`) | **true** | 지시문 프롬프트 캐싱 |

코드 상수(설정으로 못 바꿈):

| 상수 | 값 | 자리 |
|---|---|---|
| 본문 최대 | 32KB | `AiSearchController.MAX_BODY_CHARS` |
| 앞 턴 머리말 최대(넘으면 자름) | 400자 | `AiSearchController.HEAD_MAX_CHARS` |
| 내 도서관 다시 세기 책 최대 | 160 | `AiSearchController.MAX_COUNT_BOOKS` |
| 스트림 여유 | 10초 | `AiSearchController.EMITTER_GRACE` |
| `server_busy`의 `Retry-After` | 5초 | `AiSearchErrors.BUSY_RETRY_AFTER_SECONDS` |
| 카드로 보낼 최소 fit | 2 | `AiSearchFlow.MIN_FIT` |
| 되묻기 답 최대 / 한 답 글자 | 4 / 40 | `AiSearchFlow.MAX_ASK_CHOICES`, `MAX_ASK_CHOICE_CHARS` |
| 되묻기 형식을 쓰는 첫 질문 낱말 수 | ≤4 | `AiSearchFlow.ASK_QUESTION_WORDS` |
| 「후보 분야」 줄을 넣는 낱말 수 / 분야 수 | ≤2 / 6 | `AiSearchFlow.SHORT_QUESTION_WORDS`, `Prompts.MAX_SHELVES` |
| 앞 책 이웃 / 이름 댄 책 이웃 | 12 / 20 | `AiSearchFlow.MAX_NEIGHBOUR_CANDIDATES`, `MAX_NAMED_NEIGHBOURS` |
| 낱말 / 중심 낱말 / 낱말 최소 글자 | 8 / 4 / 2 | `CandidateQuery.MAX_KEYWORDS`, `MAX_CORE_WORDS`, `MIN_KEYWORD_CHARS` |
| 질문 낱말 최대 | 8 | `QuestionWords.MAX` |
| 이름 댄 제목 길이 | 2~40글자 | `NamedBook.MIN_TITLE`, `MAX_TITLE` |
| `book_search` 바뀜 확인 간격 | 1분 | `SearchBooks.CHECK_EVERY` |
| Anthropic 연결 한도 | 3초 | `LlmConfiguration.llmClient` |
| 토크나이저 최대 조각 | 256 | `OnnxQuestionEncoder.MAX_PIECES`, `QueryEncoder.MAX_PIECES` |
| 1차 길 기준값, 칸 기준값·최대 2개 | 3-4절 표 | `first-call-model.bin` 머리 JSON(파일을 바꾸면 바뀜) |

근거: `AiSearchProperties`, `PickProperties`, `AnthropicProperties`, `application.yml`의 `chaekcheck.aisearch`, `deploy/prod/prod.env`, `deploy/prod/compose.yaml`, 위 표의 각 상수.

## 9. 코드에서 확인 못 한 것

- 책 벡터를 만든 글(제목 · 저자 · 출판사 · 연도 + 소개 앞 300자)과 KURE-v1 설정: PC 작업이라 이 저장소 코드에 없음. [6-aisearch.md](../maintenance/6-aisearch.md) 9절과 [임베딩 보고서](reports/2026-10-25-embedding.md)의 기록을 옮겼습니다.
- `book_search` 76,759권: 운영 넣기 로그를 적은 문서 값입니다. 지금 운영 표의 행 수를 직접 세지 않았습니다.
- 찾기용 한 줄을 만든 지시문: PC 쪽 규칙 파일(`chaekcheck-data\aisearch\agent-chunks\RULES.md` 등)이라 이 문서에서 다루지 않았습니다.
- picks 캐시가 `picks.*` 표에서 목록 책을 고르는 자세한 규칙(어느 목록을 어떤 순서로 합치나)은 `CacheListedBooks`·`PicksCache`에 있고, 이 요청은 결과의 `coverPath`와 `aiSummary`만 씁니다.
