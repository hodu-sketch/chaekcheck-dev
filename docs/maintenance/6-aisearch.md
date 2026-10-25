# AI 책 찾기: 찾기용 한 줄 넣기 (`book-texts`)

- 사이클 24(plan ADR-0151). AI 책 찾기는 추천도서 목록 책과 **보유 도서관이 많은 3만 권**(2026-10-25 사용자 요청, 아래 6절)의 제목·저자·보유 도서관 **사본**과 찾기용 한 줄을 `aisearch.search_book`에 두고 낱말을 맞춥니다. 이 표는 이 명령으로만 바뀝니다.
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
- **changeset을 올린 직후 `book-texts`를 다시 돌려야** 합니다. 그 전 줄은 `library_ids`가 빈 배열이라 아무 책도 맞지 않습니다(AI 검색 답이 늘 "못 찾았어요"). v1.12.0 배포 때 실제로 약 1분 30초 그랬습니다: 넣기 파일을 배포 전에 서버에 올려 두고, 버전 주소가 새 버전을 보이자마자 넣기를 돌립니다
- 한 줄 파일 만들기(PC, 원문은 PC 밖으로 안 나감): 대상 `chaekcheck-dataisearch\pool-targets-2026-10-25.tsv`(번호·제목·저자·출판사), 소개 `chaekcheck-data\ml\intro-books.tsv`(사이클 23 시험이 수집 DB에서 뽑은 것). `scripts/aisearch/agent_search_lines.py split`으로 150권씩 묶고, Claude Code 세션에서 Haiku 하위 에이전트가 묶음마다 `RULES.md`대로 `out-NNN.tsv`를 쓰고, `join`으로 검사해 합침(규칙 파일 `chaekcheck-dataisearchgent-chunks\RULES.md`: 25줄씩 읽기, 소개가 비면 `-`). 처음 506권은 API 바로 호출(`make_search_lines.py`)로 만들었고 같은 지시문. 2026-10-25 결과 25,187권에 한 줄, 넣기 파일 `chaekcheck-dataisearchook-texts-30k-2026-10-25.tsv`(27,098줄). 합친 뒤 묶음마다 줄 수와 첫 칸이 책 번호인지 꼭 확인(한 묶음이 줄 번호를 쓴 적 있음)
- 운영 넣기 파일 = 목록 책 임시 한 줄(`data/aisearch/search-lines-temp-2026-10-25.tsv`) + 3만 권 한 줄. 내 PC DB와 운영 DB의 책 번호가 같음(가져오기 14번, 2026-10-25 확인: 목록 책 1,911권 전부, 3만 권 무작위 40권 제목 일치). 수집을 반영해 번호가 바뀌면 다시 만듦

## 7. 질문 벡터와 기준 책 이웃 (2026-10-25)

- 사용자 요청 "AI검색을 강화할 방법 … 숫자"(보고서 [`docs/reports/ai-search-eval-2026-10-25.md`](../reports/ai-search-eval-2026-10-25.md)) → "운영 코드로 해줘". 보고서의 H(낱말 50위 + 벡터 60위를 RRF로 섞어 30권)와 S(「○○ 같은 책」은 그 책의 이웃 20권 먼저)
- **기준 책 이웃**은 따로 켤 것이 없습니다(`foryou.book_neighbour`가 있으면 돎). **질문 벡터**는 `CHAEKCHECK_AISEARCH_VECTOR_ENABLED=true`(prod.env)와 모델 폴더가 있어야 켜짐
- 모델 폴더(서버 `/srv/chaekcheck/aisearch-model`, 컨테이너 `/models/aisearch` 읽기만, 약 420MB): PC 데이터 폴더 `chaekcheck-data\mlisearch-model\`
  - `kure-query-int8-ext.onnx` + `kure-query-int8-ext.data`: KURE-v1을 낱말표 88,446조각으로 자르고 ONNX int8(채널별)로 줄인 질문 모델(사이클 24 원래 세션, `plan-feedback.md` 기록). 원래 모델과 벡터 닮음 0.988
  - `tokenizer-cut.json`: 같은 88,446조각 토크나이저(DJL이 읽음)
  - `book-vectors.bin`: `scripts/aisearch/make_book_vectors.py OUT.bin --ml <chaekcheck-data\ml>` — 이웃을 만들 때의 책 벡터(`foryouectors.npy` + `pool-30000-intro.npy`, 30,937권)를 줄마다 int8로(최악 코사인 0.99975, 30.6MB)
  - `parity.json`(선택): Python으로 만든 질문 벡터 5개. `AISEARCH_MODEL_DIR=<폴더> ./gradlew test --tests '*KureQuestionVectorsModelTests'`가 Java 벡터와 비교(내적 0.999 넘음, 2026-10-25)
- 올리기: `scp -r`로 폴더째 `/srv/chaekcheck/aisearch-model/`에(**`/tmp` 말고**, 운영 `/tmp`는 메모리). 그다음 prod.env를 켜고 배포하면 백엔드 시작 로그에 `ai search vectors on: 30937 books x 1024, loaded in … ms`. 실패하면 `ai search vectors off: <이유>`이고 검색은 낱말만으로 그대로 됨
- 한 번의 검색 로그: `ai search: … candidates 30 (vector N[, named book]) …` — N은 섞인 벡터 후보 수(0이면 꺼짐·늦음·실패)
- 메모리: 서버에서 잰 값(원래 세션, 2026-10-25) 프로세스 약 450MB(대부분 모델 파일 mmap), 질문 하나 중간 106ms(2스레드). 힙(-Xmx640m) 밖이라 켠 뒤 `free -m`·`docker stats`로 여유를 봄
- **책 번호**: 벡터 파일의 번호는 이웃을 만든 가져오기(14번)의 번호입니다. 수집을 반영해 `catalog` 번호가 바뀌면 이웃·찾기용 한 줄과 함께 다시 만들어야 합니다(맞지 않는 번호의 책은 후보에서 빠질 뿐 오류는 없음)
- 다시 재기: 보고서 7절 도구 + `AiSearchEvalReplayTests`(`AISEARCH_EVAL_DIR`·`AISEARCH_MODEL_DIR`): 운영 `AiSearchFlow`로 평가 질문 57개를 돌려 2차 호출에 가는 후보를 `replay-on.json`/`replay-off.json`에 씀

### 7-1. 질문 벡터를 만드는 곳: 서버 안(local) 또는 임베딩 서버(remote)

사용자 요청(2026-10-25): "micro 한 대를 임베딩 전용으로 두자. 서로 다른 곳에서 소통하나 서버에 직접 임베딩하나 이걸 선택으로서 가능하게". `CHAEKCHECK_AISEARCH_VECTOR_ENCODER`(prod.env)로 고릅니다. 책 벡터(`book-vectors.bin`, 31MB)는 어느 쪽이든 백엔드 메모리에 있고, 바뀌는 것은 질문 한 줄을 벡터로 바꾸는 곳뿐입니다.

| | `local`(기본) | `remote` |
|---|---|---|
| 질문 → 벡터 | 백엔드 안 ONNX Runtime(`OnnxQuestionEncoder`) | 임베딩 서버 `POST /api/v1/embed`(`RemoteQuestionEncoder`) |
| 운영 서버 메모리 | 약 +450MB(모델 mmap) | 약 +31MB(책 벡터만) |
| 모델 폴더(운영) | 모델·토크나이저·책 벡터 | `book-vectors.bin`만 있어도 됨 |
| 더 필요한 것 | — | `CHAEKCHECK_AISEARCH_EMBED_URL`(임베딩 서버 사설 주소), `CHAEKCHECK_AISEARCH_EMBED_TOKEN`(Parameter Store) |
| 실패하면 | 그 검색은 낱말만 | 시간 넘김(1.5초)·오류·401이면 그 검색은 낱말만 |

- 임베딩 서버: `embed-service/`(Spring Boot, DB 없음, 포트 8090). 모델 폴더는 운영과 같은 파일(`kure-query-int8-ext.onnx`·`.data`, `tokenizer-cut.json`)을 `EMBED_MODEL_DIR`로. 토큰 `EMBED_TOKEN`이 비면 모든 요청을 401로 거절. 글은 로그에 남기지 않음. `GET /api/v1/health` → `{"ready":true}`
- 두 쪽의 모델 코드는 같습니다(백엔드 `OnnxQuestionEncoder` = `embed-service`의 `QueryEncoder`). 고칠 때 함께 고치고, 두 쪽 모델 시험(`KureQuestionVectorsModelTests`, `QueryEncoderModelTests`, `AISEARCH_MODEL_DIR`)이 `parity.json`과 맞는지 봅니다
- 이 PC에서 잰 값(2026-10-25, `-Xmx128m`): 올리기 1.9초, 질문 하나 40~60ms, 프로세스 약 565MB

