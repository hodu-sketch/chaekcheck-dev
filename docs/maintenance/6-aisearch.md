# AI 책 찾기 운영: 넣기·모델 파일·임베딩 서버·설정·로그

> **이 문서가 답하는 것**: AI 책 찾기(사이클 24, plan ADR-0151)를 운영하는 데 필요한 것 — 찾기용 한 줄 넣기(`book-texts`) 명령과 확인 SQL, 서버의 모델 폴더와 파일 만들기, 임베딩 서버(EC2) 정보와 띄우기 명령, 켜고 끄는 설정(prod.env), 시작·검색 로그 줄, 그때 잰 운영 숫자, 다시 재는 스크립트.
>
> **답하지 않는 것**:
> - 질문 하나가 어느 표·파일을 어떤 계산으로 다뤄 무엇을 내나(정본) → [../aisearch/1-how-it-works.md](../aisearch/1-how-it-works.md). 이 문서의 「하는 일」 줄은 그 문서 절로 가는 한 줄 요약입니다.
> - 버전마다 무엇을 왜 바꿨나 → [../aisearch/2-versions.md](../aisearch/2-versions.md)
> - ML 모델을 어떻게 학습했나 → [../aisearch/3-ml-models.md](../aisearch/3-ml-models.md)
> - 실험 숫자의 자세한 방법 → [../aisearch/4-portfolio.md](../aisearch/4-portfolio.md)와 [../aisearch/reports/](../aisearch/reports/)

- AI 책 찾기는 추천도서 목록 책과 **보유 도서관이 많은 책**의 제목·저자·보유 도서관 **사본**과 찾기용 한 줄을 `catalog.book_search`에 두고 낱말을 맞춥니다. 처음은 3만 권(2026-10-25, 6절), 지금은 **10만 권 판**(소개 있는 76,759권, 2026-10-26 21:59~, CHANGELOG는 v1.17.0 줄, 9절)입니다. 이 표는 `book-texts` 명령으로만 바뀝니다.
- **사이클 26 배포 1부터(plan ADR-0165)**: 이 표는 `catalog.book_search`입니다(제목·저자 칸 없음, 찾기 글의 제목·저자와 도서관은 넣을 때 `catalog.book`에서). AI 검색은 `catalog.AiSearchIndex`로 읽고, 카드 제목·저자도 `catalog.book.title`·`author_display`입니다(목록의 보이는 이름이 아님). 옛 `aisearch.search_book`은 사이클 26 배포 2(v1.20.6, `aisearch/005-drop-search-book.yaml`)에서 지웠습니다. 아래 절의 `search_book`은 그 전 기록이고, 확인 SQL은 `catalog.book_search`로 읽습니다.
- 한 줄은 내 PC에서 Haiku 배치로 만듭니다(plan 인계서 2단계, 소개 원문은 PC 밖으로 나가지 않음). 서버에는 한 줄만 들어갑니다.

## 0. 지금 운영 (2026-10-31, v1.23.0)

- **v1.23.0(2026-10-31, 15절)**: 두 층 책 벡터(요약 한 줄 + 소개 512토큰 조각, `book-vectors.bin` 238,392줄·81,078권, 책마다 10줄까지), 낱말 후보는 pg_search BM25(`KEYWORD_MATCH=bm25`), 2차가 읽는 글은 요약 + 맞은 검색 낱말(`READING_TEXT=summary`), ML 1차 빼기 끔(`RULES_OUT=false`). DB 이미지 `chaekcheck/postgres-bigm:18`(pg_search 0.26.3, vector 0.8.7, preload `pg_bigm,pg_search`). 옛 것: `aisearch-model/book-vectors-100k.bin`, `prod.env.before-v1.23.0`, `compose.yaml.before-v1.23.0`, 이미지 `chaekcheck/postgres-bigm:18-before-search`. 아래 줄 중 「LIKE」·「한 줄」 설명은 v1.22까지의 길이며 스위치를 `like`/`line`으로 돌리면 그대로 돕니다.

- 켠 것(`deploy/prod/prod.env`): `CHAEKCHECK_AISEARCH_ENABLED=true`, `CHAEKCHECK_AISEARCH_VECTOR_ENABLED=true`, `CHAEKCHECK_AISEARCH_VECTOR_ENCODER=remote`, `CHAEKCHECK_AISEARCH_EMBED_URL=http://172.31.45.141:8090`(임베딩 서버, 7-1절), `CHAEKCHECK_AISEARCH_FIRST_CALL=ml`(8절), `CHAEKCHECK_AISEARCH_FOLLOW_UP=relation`, `CHAEKCHECK_AISEARCH_PICK_ASK_BACK=true`, `CHAEKCHECK_AISEARCH_LLM_THINKING=disabled`, `CHAEKCHECK_AISEARCH_LLM_CACHE_SYSTEM=true`(10절). 모델 폴더 `CHAEKCHECK_AISEARCH_MODEL_DIR=/models/aisearch`는 `deploy/prod/compose.yaml`. effort는 `application.yml`의 `low`. 비밀값 `CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY`·`CHAEKCHECK_AISEARCH_EMBED_TOKEN`은 Parameter Store(`up.sh`가 `secrets.env`로)
- 켜지 않은 것: `CHAEKCHECK_AISEARCH_PICK_ASK_ONE_WORD`(기본 `false`, 한 낱말 질문은 되묻지 않고 바로 답). 이 값은 `deploy/prod/compose.yaml`이 컨테이너에 넘기지 않으므로 켜려면 compose에 줄을 더해야 함
- 코드 기본값은 다릅니다(`application.yml`: AI 검색 꺼짐, 1차 `haiku`, 되묻기 `false`, 벡터 꺼짐, encoder `local`, 생각 칸 안 보냄 = 모델 기본, 캐싱 끔). 운영이 prod.env로 켭니다. 값 목록은 [settings-index.md](settings-index.md) 1-1절, 뜻은 [1-how-it-works 8절](../aisearch/1-how-it-works.md#8-설정-값). 크기: 2차 `max_tokens` 4096, 앞 대화 9(웹 대화 10번)
- 설정을 바꿔 적용하기(blue/green, 사이클 27): prod.env를 `/srv/chaekcheck/prod.env`에 올리고 `bin/switch.sh backend <slots.env의 지금 판>`. 새 자리(`backend-blue`/`backend-green`)가 뜨고 nginx가 옮겨 간 뒤 옛 자리가 멈춤(03:25~05:10은 거절). 배포는 `deploy/prod/bin/release.sh`
- 한 검색(자세히는 [1-how-it-works 1절](../aisearch/1-how-it-works.md#1-한눈에)):
  - 1차: 질문 벡터(임베딩 서버, 이어 묻기면 3개) 위의 MLP가 길 5갈래 + 칸 11개를, 이어 묻기면 관계 분류기가 관계 7가지를 정함. 모델 호출 없음. 낱말은 질문 낱말 그대로(최대 8). 벡터가 2초 안에 안 오거나 실패하면 그 검색만 Haiku 1차
  - 후보: 낱말 후보(`catalog.book_search`에 `LIKE`, ≤50권)와 벡터 후보(책 벡터 내적, ≤60권)를 RRF(Σ 1/(60+순위)) + 칸 보너스(0.004 × 맞은 칸 수)로 한 줄 세워 30권. 첫 질문은 낱말 후보 SQL을 질문 벡터와 동시에 시작
  - 2차: Haiku 한 번(스트림)이 30권 중 fit 2 이상 최대 8권과 카드 글. 되묻기 형식(`ask` 칸)은 4낱말 이하 첫 질문과 이어 묻기에만. 표지·요약은 Haiku를 부른 뒤 읽음
  - 길이 책첵 사용법 질문이면 Haiku 1번으로 답(`done "site"`). 책 찾기 아님(한 낱말 포함)이면 Haiku 대화 호출(`Prompts.CHAT_SYSTEM`) 한 번이 답과 고를 답을 냄(로그 `, ask chat`), 실패하면 고정 되묻기(예시 10개 중 3개, `, ask not-book-ask`)
  - 못 찾으면: 되묻기가 켜 있을 때 Haiku 한 번(`Prompts.NO_MATCH_SYSTEM`)이 이어 물을 고를 답을 냄(로그 `, ask no-match choices`, 2026-10-30). 실패하면 웹의 예시
- 서버가 모델 폴더(서버 `/srv/chaekcheck/aisearch-model` → 컨테이너 `/models/aisearch`, 읽기만)에서 읽는 파일: `book-vectors.bin`(81,078권), `first-call-model.bin`(라우터 B, v1.16.1~), `follow-up-model.bin`. `encoder=local`일 때만 `kure-query-int8-ext.onnx`·`kure-query-int8-ext.data`, `tokenizer-cut.json`도 읽음(운영은 `remote`라 열지 않음, 임베딩 서버의 `/srv/embed/model/`이 씀). 운영 서버 폴더에 인코더 파일이 실제로 있는지는 이 문서에서 확인 못 함. PC 쪽 같은 파일 `chaekcheck-data\ml\aisearch-model-100k\`(폴더 README)
- 책 프로필: v1.22.1부터 `book-profiles.tsv`는 읽지 않고 `catalog.book_profile`을 시작할 때 읽음(넣기 모드 `book-profiles`, [7-foryou.md](7-foryou.md) 2-2절). 넣은 뒤 `bin/switch.sh backend <지금 판>`으로 다시 띄워야 읽음. 운영 폴더의 `book-profiles.tsv`는 2026-10-30 지움, PC 원본 `chaekcheck-data/aisearch/100k/book-profiles-export.tsv`
- 표: 찾기 표 `catalog.book_search`, 프로필 `catalog.book_profile`, 하루 사용량 `aisearch.daily_usage`, 사용 기록 `usagelog.ai_search_event`. 옛 `aisearch.search_book`·`foryou.book_blurb`·`foryou.book_cover`는 v1.20.6에서 지움
- 책 수: AI 검색 책(`catalog.book_search`) 76,759권(2026-10-27 넣기), 책 벡터 81,078권, 프로필 77,551권(PC 파일 기준, 10만 권 판, 9절)
- 어디에 무엇: 넣기 1~5절, 3만 권 6절, 질문 벡터·임베딩 서버 7절, ML 1차·이어 묻기 8절, 10만 권 9절, 되묻기·생각 끄기·캐시 10절, 표 줄이기 11절, 첫 글 시간 12절. 로그 한 줄의 칸 뜻은 [1-how-it-works 7절](../aisearch/1-how-it-works.md#7-로그-한-줄)

## 1. 언제 돌리나

- 2단계 PC 배치로 새 TSV를 만들었을 때
- 목록 책이 바뀐 뒤: 가져오기 바꿔 끼우기([3-import.md](3-import.md) 8·13절), 목록만 넣기(`lists` 모드, 3-import.md 14절), 사서추천도서 월 받기([4-picks.md](4-picks.md) 2절), 책 맞춤 규칙 변경. 돌리지 않으면 새 책은 다음에 돌릴 때까지 AI 찾기에 나오지 않습니다(결정 #3에서 감수, [4-picks.md](4-picks.md) 10절)
- 한 줄이 아직 없을 때(처음): 머리줄만 있는 파일이면 목록 책 전부가 제목·저자만으로 들어갑니다. **이미 한 줄이 든 표에 한 줄 0개 파일을 넣으면 거부합니다**(잘못된 파일로 한 줄을 모두 잃지 않게, 표 그대로)

## 2. 파일

- **사이클 26부터 첫 줄은 `# export_id=<uuid>`**(파일의 책 번호를 뽑은 DB의 마지막 가져오기 `export_id`), 둘째 줄이 머리줄 `kind	book_id	source	made_on	text`. 넣는 DB의 마지막 SUCCEEDED 가져오기와 다르면 아무것도 넣지 않고 거절합니다(번호가 같다고 확실할 때만 `--chaekcheck.collection.import.force=true`). 운영(v1.20.0~)은 이 첫 줄이 없으면 파일 전체를 거절합니다(`ExportIds.parse`). BOM과 Windows 줄 끝(`\r\n`)은 받습니다
- `kind`: 이 문서는 `search_line`입니다. 사이클 23의 `for_you_blurb`·`tags`·`cover_file`은 [7-foryou.md](7-foryou.md) 2-2절(그 kind만 있는 파일은 `catalog.book_search`를 건드리지 않음). 다른 값은 건너뛰고 종류별 수를 로그에 남깁니다
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

- 운영(EC2)에서는 서버의 백엔드 이미지로 돌립니다(비밀값을 PC로 가져오지 않음). TSV를 `/srv/chaekcheck/book-texts/`에 올리고(644) `/srv/chaekcheck`에서(`<판>` = `slots.env`의 일하는 자리 판):
  `( set -a; . ./secrets.env; set +a; docker run --rm --network chaekcheck_default -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/chaekcheck -e DB_NAME=chaekcheck -e DB_USERNAME=chaekcheck -e DB_PASSWORD -e TZ=Asia/Seoul -e JAVA_TOOL_OPTIONS=-Xmx384m -e CHAEKCHECK_AUTH_ENABLED=false -e CHAEKCHECK_USAGELOG_ENABLED=false -e CHAEKCHECK_AISEARCH_ENABLED=false -v /srv/chaekcheck/book-texts:/book-texts:ro chaekcheck/backend:<판> --spring.profiles.active=collection-import --chaekcheck.collection.import.mode=book-texts --chaekcheck.collection.import.book-texts-file=/book-texts/<파일> --chaekcheck.collection.import.book-texts-top-books=100000 )`.
  **`book-texts-top-books=100000`을 빼면 파일 기본 30000으로 넣어 운영 AI 검색 책이 10만 권 판(76,759권, 2026-10-26~)에서 약 3만 권으로 줄어듭니다**(9절).
  로그인을 끄지 않으면 뜨지 않습니다(`HttpSecurity` 없음, 2026-10-25)
- 차례: 파일 검사 → 목록 책을 DB에서 바로 읽음(`ListedBooks.loadFromDb()`, 서버 캐시 없이) → 보유 도서관 많은 책 `book-texts-top-books`권(파일 기본 30000, **운영은 100000**, `catalog.MostHeldBooks`)을 읽음 → 한 트랜잭션에서 `catalog.book_search`를 지우고 목록 책 전부와 목록에 없는 그 책들을 넣음(한 줄이 있으면 함께, 보유 도서관 `library_ids`도 복사). 목록에도 있는 책은 목록 쪽(보이는 이름) 한 줄로
- `--chaekcheck.collection.import.book-texts-top-books=0`이면 예전처럼 목록 책만
- **목록 책이 0권이면 거부하고 표를 그대로 둡니다.** 읽지 못한 목록이 있어도(`ListsNotReadException`) 거부합니다
- 목록에 없는 책 번호의 줄은 건너뛰고 수를 셉니다. 1줄이라도 있으면 WARN 한 줄(다른 DB에서 뽑은 파일인지 확인)
- 로그 한 줄 예: `book-texts: file rows 30939, search lines 30939, other kinds skipped {}, listed books 1911, most held 30000 (29028 not listed), stored 30939, with line …, lines for books not stored 0`
- **카탈로그를 다시 가져온 뒤에는 반드시 다시 돌립니다**: 10만 권(9절)의 차례와 보유 도서관이 바뀌고, 사본의 `library_ids`로 계획 도서관 수를 세기 때문입니다. 가져오기는 책 번호를 새로 매기므로 넣기 파일과 함께 `book-vectors.bin`(7절), 이웃(`foryou.book_neighbour`)도 새 번호로 다시 만들고, 프로필 TSV를 새 번호로 만들어 `mode=book-profiles`로 `catalog.book_profile`에 넣은 뒤(7-foryou.md 2-2절) `bin/switch.sh backend <지금 판>`으로 다시 띄웁니다

## 4. 확인

```sql
SELECT count(*) AS books, count(line) AS with_line,
       count(*) FILTER (WHERE line_source = 'intro') AS intro,
       count(*) FILTER (WHERE line_source = 'bibliographic') AS bibliographic,
       max(imported_at) AS imported_at
FROM catalog.book_search;
```

- `books`가 로그의 `stored`와 같은지(운영 2026-10-27 76,759), `with_line`이 기대한 수인지 봅니다
- 제목·저자가 지금 목록과 같은지(후속 작업 24-F1 완료 확인과 같은 쿼리)는 [4-picks.md](4-picks.md) 10절

## 5. 되돌리기

- 앞 TSV로 다시 돌리면 그때 상태로 돌아갑니다(표 전체를 바꿔 끼우므로)
- 모듈 전체를 거둘 때: `DROP SCHEMA aisearch CASCADE`와 Liquibase 기록(`aisearch-001-*` ~ `aisearch-005-*`) 정리. 찾기 표 `catalog.book_search`는 사이클 26부터 `catalog`에 있어 이것으로 지워지지 않음

## 6. 보유 도서관 많은 3만 권 (2026-10-25)

- 사용자 요청 "ai검색 되는 도서를 보유량 많은 3만 권 기준으로도 같은 흐름으로 되게끔". 정한 것(사용자 선택, `plan-feedback.md` `# AI 검색 3만 권`): 목록 책과 **한 풀, 구분 없음**(점수 → 계획 도서관 수 → 번호), 3만 권 찾기용 한 줄은 **PC에서 하위 에이전트(Haiku)로**, 표지는 **사이클 23 표지를 합친 뒤**(그 전까지 3만 권 카드는 표지·AI 요약 없음), 보유 도서관은 **넣기 때 사본에**
- 고르는 기준: `catalog.book ORDER BY cardinality(library_ids) DESC, id LIMIT 30000` — 사이클 23 3만 권 풀(`scripts/crawl/probe_embeddings.py`)과 같은 식
- 서버: `catalog.book_search.library_ids`(처음 changeset `aisearch-003`, 사이클 26부터 `catalog`)로 계획 도서관 수를 SQL에서 셈(`CandidateQuery`). 제목·저자는 서버 메모리 사본(`SearchBooks`, 표가 바뀌면 1분 안에 다시 읽음)
- **changeset을 올린 직후 `book-texts`를 다시 돌려야** 합니다. 그 전 줄은 `library_ids`가 빈 배열이라 아무 책도 맞지 않습니다(AI 검색 답이 늘 "못 찾았어요"). v1.12.0 배포 때 실제로 약 1분 30초 그랬습니다: 넣기 파일을 배포 전에 서버에 올려 두고, 버전 주소가 새 버전을 보이자마자 넣기를 돌립니다
- 한 줄 파일 만들기(PC, 원문은 PC 밖으로 안 나감): 대상 `chaekcheck-data\aisearch\pool-targets-2026-10-25.tsv`(번호·제목·저자·출판사), 소개 `chaekcheck-data\ml\intro-books.tsv`(사이클 23 시험이 수집 DB에서 뽑은 것). `scripts/aisearch/agent_search_lines.py split`으로 150권씩 묶고, Claude Code 세션에서 Haiku 하위 에이전트가 묶음마다 `RULES.md`대로 `out-NNN.tsv`를 쓰고, `join`으로 검사해 합침(규칙 파일 `chaekcheck-data\aisearch\agent-chunks\RULES.md`: 25줄씩 읽기, 소개가 비면 `-`). 처음 506권은 API 바로 호출(`make_search_lines.py`)로 만들었고 같은 지시문. 2026-10-25 결과 25,187권에 한 줄, 넣기 파일 `chaekcheck-data\aisearch\book-texts-30k-2026-10-25.tsv`(27,098줄). 합친 뒤 묶음마다 줄 수와 첫 칸이 책 번호인지 꼭 확인(한 묶음이 줄 번호를 쓴 적 있음)
- 운영 넣기 파일 = 목록 책 임시 한 줄(`data/aisearch/search-lines-temp-2026-10-25.tsv`) + 3만 권 한 줄. 내 PC DB와 운영 DB의 책 번호가 같음(가져오기 14번, 2026-10-25 확인: 목록 책 1,911권 전부, 3만 권 무작위 40권 제목 일치). 수집을 반영해 번호가 바뀌면 다시 만듦
- **소개가 없거나 잘못된 책 빼기**(사용자 결정 2026-10-25, [개선 보고서](../aisearch/reports/2026-10-25-improvement.md) 5절): 넣기 파일에 `search_exclude` 줄(`search_exclude	<book_id>			<이유>`)을 두면 그 책은 보유 도서관 많은 책에서 빠지고, 빈자리를 다음 책으로 채우지 않음(목록 책은 빠지지 않음). 목록 `chaekcheck-data\aisearch\excluded-books-2026-10-25.tsv`(3,712권: 소개 빔 2,728, 소개 줄 없음 864, 다른 책 소개 120). 넣기 파일 `chaekcheck-data\aisearch\book-texts-30k-exclude-2026-10-25.tsv` = 30k 파일 27,098줄 + 새로 채운 한 줄 129줄(`search-lines-noline-2026-10-25.tsv`) + 뺄 책 3,712줄 → 넣으면 27,227권, 모두 한 줄 있음. 로그 `most held 30000 (… not listed, 3712 excluded)`

## 7. 질문 벡터와 기준 책 이웃 (2026-10-25)

- 사용자 요청 "AI검색을 강화할 방법 … 숫자"(보고서 [`aisearch/reports/2026-10-25-eval.md`](../aisearch/reports/2026-10-25-eval.md)) → "운영 코드로 해줘". 보고서의 H(낱말 후보 50위 + 벡터 후보 60위를 RRF로 한 줄 세워 30권)와 S(「○○ 같은 책」은 그 책의 이웃 20권 먼저). 지금 계산은 [1-how-it-works 4-6·4-8·4-9절](../aisearch/1-how-it-works.md#4-9-합치기-mix)
- **기준 책 이웃**은 따로 켤 것이 없습니다(`foryou.book_neighbour`가 있으면 돎). **질문 벡터**는 `CHAEKCHECK_AISEARCH_VECTOR_ENABLED=true`(prod.env)와 모델 폴더가 있어야 켜짐
- 모델 폴더(서버 `/srv/chaekcheck/aisearch-model`, 컨테이너 `/models/aisearch` 읽기만, v1.15.0 때 약 420MB): 그때 PC 데이터 폴더 `chaekcheck-data\ml\aisearch-model\`(지금은 운영 파일이 아닌 작업 폴더. 지금 운영과 같은 파일은 `chaekcheck-data\ml\aisearch-model-100k\`, 0절)
  - `kure-query-int8-ext.onnx` + `kure-query-int8-ext.data`: KURE-v1을 낱말표 88,446조각으로 자르고 ONNX int8(채널별)로 줄인 질문 모델(사이클 24 원래 세션, `plan-feedback.md` 기록). 원래 모델과 벡터 닮음 0.988(운영 서버 임시 컨테이너, PC는 0.989, [임베딩 보고서](../aisearch/reports/2026-10-25-embedding.md) 4·5절)
  - `tokenizer-cut.json`: 같은 88,446조각 토크나이저(DJL이 읽음)
  - `book-vectors.bin`: `scripts/aisearch/make_book_vectors.py OUT.bin --ml <chaekcheck-data\ml>` — 이웃을 만들 때의 책 벡터(`foryou\vectors.npy` + `pool-30000-intro.npy`, 30,937권)를 줄마다 int8로(최악 코사인 0.99975, 30.6MB). **지금 운영은 10만 권 판(v1.17.0~)**: `--pool pool-100000-intro`로 81,078권, 약 80MB(9절)
  - `parity.json`(선택): Python으로 만든 질문 벡터 5개. `AISEARCH_MODEL_DIR=<폴더> ./gradlew test --tests '*KureQuestionVectorsModelTests'`가 Java 벡터와 비교(내적 0.999 넘음, 2026-10-25)
- 올리기: `scp -r`로 폴더째 `/srv/chaekcheck/aisearch-model/`에(**`/tmp` 말고**, 운영 `/tmp`는 메모리). 그다음 prod.env를 켜고 배포하면 백엔드 시작 로그에 `ai search vectors on (REMOTE): 81078 books x 1024, loaded in … ms`(10만 권 판, v1.17.0~. 괄호는 질문 벡터 만드는 곳, 7-1절). 실패하면 `ai search vectors off: <이유>`이고 v1.15.x에서는 검색이 낱말 후보만으로 그대로 됨(v1.16.0부터는 ML 1차도 질문 벡터가 있어야 해서 모든 검색이 Haiku 1차 + 낱말 후보만, [1-how-it-works 6절](../aisearch/1-how-it-works.md#6-실패대체-길) 1번)
- 한 번의 검색 로그: `ai search: … candidates 30 (vector N[, named book]) …` — N은 RRF에 넣은 벡터 후보 수(0이면 꺼짐·늦음·실패). v1.19.0부터 괄호 안에 `, early keyword`가 더 붙을 수 있음. 칸 전체는 [1-how-it-works 7절](../aisearch/1-how-it-works.md#7-로그-한-줄)
- 메모리: 서버에서 잰 값(원래 세션, 2026-10-25) 프로세스 약 450MB(대부분 모델 파일 mmap), 질문 하나 중간 106ms(2스레드). 힙(-Xmx640m) 밖이라 켠 뒤 `free -m`·`docker stats`로 여유를 봄
- **책 번호**: 벡터 파일의 번호는 이웃을 만든 가져오기(14번)의 번호입니다. 수집을 반영해 `catalog` 번호가 바뀌면 이웃·찾기용 한 줄과 함께 다시 만들어야 합니다(맞지 않는 번호의 책은 후보에서 빠질 뿐 오류는 없음)
- 다시 재기: 보고서 7절 도구 + `AiSearchEvalReplayTests`(`AISEARCH_EVAL_DIR`·`AISEARCH_MODEL_DIR`): 운영 `AiSearchFlow`로 평가 질문 57개를 돌려 2차 호출에 가는 후보를 `replay-on.json`/`replay-off.json`에 씀

### 7-1. 질문 벡터를 만드는 곳: 서버 안(local) 또는 임베딩 서버(remote)

사용자 요청(2026-10-25): "micro 한 대를 임베딩 전용으로 두자. 서로 다른 곳에서 소통하나 서버에 직접 임베딩하나 이걸 선택으로서 가능하게". `CHAEKCHECK_AISEARCH_VECTOR_ENCODER`(prod.env)로 고릅니다. 책 벡터(`book-vectors.bin`, 10만 권 판 약 80MB, v1.17.0~)는 어느 쪽이든 백엔드 메모리에 있고, 바뀌는 것은 질문 한 줄을 벡터로 바꾸는 곳뿐입니다.

| | `local`(기본) | `remote` |
|---|---|---|
| 질문 → 벡터 | 백엔드 안 ONNX Runtime(`OnnxQuestionEncoder`) | 임베딩 서버 `POST /api/v1/embed`(`RemoteQuestionEncoder`) |
| 운영 서버 메모리 | 약 +450MB(모델 mmap) | 약 +80MB(책 벡터만, 10만 권 판) |
| 모델 폴더(운영) | 모델·토크나이저·책 벡터 | `book-vectors.bin`만 있어도 됨 |
| 더 필요한 것 | — | `CHAEKCHECK_AISEARCH_EMBED_URL`(임베딩 서버 사설 주소), `CHAEKCHECK_AISEARCH_EMBED_TOKEN`(Parameter Store) |
| 실패하면 | 그 검색은 낱말만 | 시간 넘김(1.5초)·오류·401이면 그 검색은 낱말만 |
| 계속 실패하면 | (같음) | 3번 연달아 실패하면 30초 동안 묻지 않음(서킷 브레이커, v1.15.3): 그동안 검색은 기다림 없이 낱말만. 로그 `ai search vectors: the encoder failed (…), not asked for 30 s` → 되살아나면 `the encoder answers again; N search(es) went without vectors` |

- 위 표의 「낱말만」은 v1.15.x 때입니다. v1.16.0부터(`FIRST_CALL=ml`) 질문 벡터가 실패·2초 넘김·차단기 열림이면 그 검색은 **Haiku 1차**로 가고, 벡터 후보는 질문 하나로 다시 요청해 오면 쓰고 안 오면 낱말 후보만 씁니다(로그 `haiku makes the first step`, [1-how-it-works 4-2절·6절](../aisearch/1-how-it-works.md#6-실패대체-길)).

- 임베딩 서버: `embed-service/`(Spring Boot, DB 없음, 포트 8090). 모델 폴더는 운영과 같은 파일(`kure-query-int8-ext.onnx`·`.data`, `tokenizer-cut.json`)을 `EMBED_MODEL_DIR`로. 토큰 `EMBED_TOKEN`이 비면 모든 요청을 401로 거절. 글은 로그에 남기지 않음. `GET /api/v1/health` → `{"ready":true}`
- 두 쪽의 모델 코드는 같습니다(백엔드 `OnnxQuestionEncoder` = `embed-service`의 `QueryEncoder`). 고칠 때 함께 고치고, 두 쪽 모델 시험(`KureQuestionVectorsModelTests`, `QueryEncoderModelTests`, `AISEARCH_MODEL_DIR`)이 `parity.json`과 맞는지 봅니다
- 이 PC에서 잰 값(2026-10-25, `-Xmx128m`): 올리기 1.9초, 질문 하나 40~60ms, 프로세스 약 565MB

#### 임베딩 서버 (micro EC2, 2026-10-25)

| 항목 | 값 |
|---|---|
| 인스턴스 | `chaekcheck-embed` `i-098cd64ff48a14550`, t4g.micro(ARM, 메모리 904MB + 스왑 1GB), Ubuntu 24.04, 16GB gp3, ap-southeast-2b(운영과 같은 서브넷 `subnet-0e10283f90a669274`) |
| 주소 | 사설 `172.31.45.141`(백엔드가 쓰는 주소, 멈췄다 켜도 그대로), 공인 `13.211.237.221`(SSH용, 멈췄다 켜면 바뀜) |
| 보안 그룹 | `chaekcheck-embed` `sg-01e9e764c2cf06b25`: 8090은 운영 서버 보안 그룹 `sg-0f424434c56c5d021`에서만, 22는 관리 PC IP에서만(IP가 바뀌면 이 규칙을 고침) |
| SSH | 운영과 같은 키: `ssh -i .secrets/chaekcheck-key.pem ubuntu@13.211.237.221` |
| 복구 | 하드웨어 문제는 AWS 자동 복구(`MaintenanceOptions.AutoRecovery = default`, 켜짐). 서버 안이 멈춰 상태 검사(`StatusCheckFailed_Instance`)가 3분 연속 실패하면 CloudWatch 알람 `chaekcheck-embed-reboot-on-instance-check`가 재부팅(2026-10-25). 컨테이너는 `--restart unless-stopped`라 재부팅 뒤 저절로 뜸. 알림 메일은 아직 없음 |
| 비용 | 무료 플랜 크레딧에서 나감(계정은 무료 플랜, 2026-10-25 남은 크레딧 $197.96) |
| 파일 | `/srv/embed/model/`(모델 폴더, `parity.json` 포함), `/srv/embed/embed.env`(chmod 600, `EMBED_TOKEN=`) |
| 컨테이너 | `docker run -d --name embed --restart unless-stopped -p 8090:8090 --env-file /srv/embed/embed.env -e EMBED_MODEL_DIR=/model -e JAVA_TOOL_OPTIONS="-Xmx128m -XX:+ExitOnOutOfMemoryError" -e TZ=Asia/Seoul -v /srv/embed/model:/model:ro --memory=750m chaekcheck/embed:0.1.0` |
| 이미지 | PC에서 `embed-service/`의 `./gradlew bootJar` 뒤 WSL `docker buildx build --platform linux/arm64 -t chaekcheck/embed:X.Y.Z --load .` → `docker save | gzip` → scp → `docker load` |

- 잰 값(2026-10-25): 모델 올리기 4.3초, 컨테이너 435~456MB, 질문 하나 60~68ms(첫 번째 1.9초), 운영 서버→임베딩 서버 3~5ms. 운영 서버에서 `curl http://172.31.45.141:8090/api/v1/health` → `{"ready":true}`, 토큰 없이 `/api/v1/embed` → 401, 인터넷에서는 막힘
- **토큰**: Parameter Store `/chaekcheck/prod/CHAEKCHECK_AISEARCH_EMBED_TOKEN`(SecureString, 무작위 값)을 사람이 넣음. 운영은 `up.sh`가 secrets.env로 읽고, 임베딩 서버는 같은 값을 `/srv/embed/embed.env`의 `EMBED_TOKEN=`에 넣고 `docker rm -f embed` 뒤 위 명령으로 다시 띄움. 토큰이 비어 있으면 모든 요청이 401이고, 백엔드는 벡터 없이 검색함(v1.16.0부터는 Haiku 1차 + 낱말 후보, 위 7-1 표 아래 줄)
- 운영에서 켜기(prod.env): `CHAEKCHECK_AISEARCH_VECTOR_ENABLED=true`, `CHAEKCHECK_AISEARCH_VECTOR_ENCODER=remote`, `CHAEKCHECK_AISEARCH_EMBED_URL=http://172.31.45.141:8090`, 운영 모델 폴더에는 `book-vectors.bin`만. 서버 안으로 바꾸려면 `ENCODER=local`과 모델 파일 셋을 더 둠


## 8. ML 1차 호출 (2026-10-26)

- 사용자 결정(2026-10-26): "1차를 5갈래 MLP로 바꿔요(M4 방식)", "이어 묻는 질문도 ML로 하기 → 넣음", "책첵 질문에 사이트 정보로 답하게 해요", "설정으로 켜고 끌 수 있게". 잰 숫자와 넣고 뺀 것: [`aisearch/reports/2026-10-26-ml-first-call-decisions.md`](../aisearch/reports/2026-10-26-ml-first-call-decisions.md). 모델 설명: [3-ml-models.md](../aisearch/3-ml-models.md)
- **켜기**: `CHAEKCHECK_AISEARCH_FIRST_CALL=ml`(prod.env). 기본 `haiku`(지금까지와 같음). 질문 벡터가 켜져 있어야 하고(7절) 모델 폴더에 `first-call-model.bin`·`follow-up-model.bin`, 그리고 `catalog.book_profile`이 비어 있지 않아야 함(`book-profiles` 넣기, v1.22.1~). 없으면 시작 로그 `ai search first call: haiku, the ml one is off: <이유>`이고 지금처럼 Haiku. 켜지면 `ai search first call: ml, N book profiles from catalog.book_profile, follow-ups by relation`
- **되돌리기**: `CHAEKCHECK_AISEARCH_FIRST_CALL=haiku`로 바꾸고 prod.env를 `/srv/chaekcheck/prod.env`에 올린 뒤 `bin/switch.sh backend <slots.env의 지금 판>`. 파일은 그대로 둬도 됨
- 모델 폴더에 더할 파일(PC `chaekcheck-data\ml\aisearch-model\first-call-v2-2026-10-26\`, 약 35MB. v1.16.0은 `first-call-2026-10-26\`. 지금 운영과 같은 폴더는 `chaekcheck-data\ml\aisearch-model-100k\`)
  - `first-call-model.bin`(25MB, v2 = 짧은 장르 질문 300개를 더한 라우터 B): 길 5갈래(책 찾기 아님 · 제목만 · 앞 책 가리킴 · 책첵 질문 · 보통 질문) + 칸 머리 11개(큰 칸·작은 칸·장르·형식·빼 달라는 큰 칸·작은 칸·장르·소설인지·나라·대상·수준). 각 머리는 KURE 벡터 1024 → 512(ReLU) → 칸
  - `book-profiles.tsv`(1.6MB, v1.22.1부터 서버는 이 파일을 읽지 않음: `book-profiles` 넣기의 원본으로만 씀): 책 27,227권의 상세 사전 칸(지금 운영은 10만 권 판 77,551권, 9절)(번호 = 책 벡터와 같은 가져오기 번호. 수집으로 `catalog` 번호가 바뀌면 책 벡터와 함께 다시 만듦)
  - 만들기: `scripts/aisearch/eval/export_first_call.py OUT_DIR`(평가 데이터 `data/aisearch-eval-2026-10-25/`의 `qgen/`·`route/`와 `clf/`의 벡터가 있는 폴더 배치에서 돌림; 칸 머리를 학습하고, 운영과 같은 int8 질문 모델로 다시 재서 `first-call-check.json`에 남김). 2026-10-26 값(v1.16.0 판): 길 나누기 fp32 94.3% → int8 94.2%, 큰 칸 F1 0.899 → 0.903
  - 지금 운영 판(라우터 B, v1.16.1~)은 `ROUTE_PT=route5b-mlp.pt`로 `scripts/aisearch/eval/export_first_call2.py OUT_DIR`(라우터 파일만 다르고 나머지 차례는 같음, 스크립트 머리 주석). 그 `first-call-check.json`(`first-call-v2-2026-10-26\`): 길 나누기 fp32 93.7% → int8 93.5%, 큰 칸 F1 0.899 → 0.903. 머리마다 값은 [3-ml-models 8절](../aisearch/3-ml-models.md)
  - `follow-up-model.bin`(8MB): 이어 묻는 질문의 관계 7가지(refers · ask_one · continue · shift · feedback · new · none). 입력 4,096 = 지금 질문 · 앞 질문 · 「앞 + 지금」 벡터와 앞·지금 벡터의 곱 → 512(ReLU) → 7. 만들기 `scripts/aisearch/eval/rel_train.py`(학습 쌍 `data/aisearch-eval-2026-10-25/rel/`) 뒤 `export_follow_up.py OUT_DIR`. 시험 700쌍 96.4%
  - 확인: `fixture_first_call.py OUT_DIR`가 질문 12개의 기대값을 `first-call-fixture.json`에 쓰고, `AISEARCH_FIRST_CALL_DIR=<폴더> ./gradlew test --tests '*FirstCallModelFileTests'`가 Java 계산과 비교(확률 차 1e-4 안, 2026-10-26 통과)
- **한 검색에서 하는 일**(`AiSearchFlow`, `MlFirstCall`): 계산의 정본은 [1-how-it-works 4-2~4-9절](../aisearch/1-how-it-works.md#4-3-ml-1차-ml). 운영에서 알아 둘 것만 한 줄씩:
  - 질문 벡터: 첫 질문 1번, 이어 묻는 질문이면 「지금 / 앞 / 앞 + 지금」 3번을 임베딩 서버에 동시에 보냄([4-2절](../aisearch/1-how-it-works.md#4-2-질문-벡터-encode)). 낱말은 질문 낱말 그대로(`QuestionWords`)
  - 길: 책 찾기 아님 → 낱말 하나면 2차로, 아니면 고정 되묻기(v1.17.0~, 10절) / 책첵 질문 → Haiku 한 번(`Prompts.SITE_SYSTEM`, 소개 탭·첫 화면·바닥글 글, IP 줄 뺌)이 1~3문장, 웹은 답과 「책첵이란? 자세히 보기 →」(`/about`). **소개 탭·첫 화면·바닥글 글을 바꾸면 `SITE_SYSTEM`도 함께 고침** / 제목만 → 「직접 찾기에서 보기」 줄 뒤 보통 검색 / 앞 책 가리킴 → 앞에 보인 책 + 이웃 + 그 전 턴들의 책([4-5·4-6절](../aisearch/1-how-it-works.md#4-5-길에-따라-여기서-끝나는-경우))
  - 앞 질문 이어 붙이기: 「두 개」, 「둘 다」, 「섞은」, 「합친」, 「모두」 등(`flow/EarlierTopics`)이면 앞 질문 여러 개를 이어 찾고 로그 관계 뒤에 ` joined`. 앞에서 보인 책을 다시 후보에 넣는 경우는 [4-7절](../aisearch/1-how-it-works.md#4-7-낱말-후보-keyword). 잰 값: [결정 기록 3-6](../aisearch/reports/2026-10-26-ml-first-call-decisions.md)
  - 후보: 낱말 50위 + 벡터 60위 RRF + 맞은 칸 하나에 0.004(`shelf-bonus`), 빼 달라는 칸·「소설 말고」 책은 맨 뒤 → 30권 → 2차 Haiku([4-9절](../aisearch/1-how-it-works.md#4-9-합치기-mix))
  - 이어 묻는 질문(설정 `CHAEKCHECK_AISEARCH_FOLLOW_UP`, 기본 `relation`): 관계 분류기 7가지(refers · ask_one · continue · shift · feedback · new · none)에 따라 낱말·검색 벡터·칸을 정함(관계별 표는 [4-3절 (바)](../aisearch/1-how-it-works.md#4-3-ml-1차-ml)). 시작 로그 `follow-ups by relation`. 되돌리는 값: `rule`(v1.16.0: 지금 질문만으로 큰 칸이 정해지고 앞과 다르면 앞 질문을 버림), `strict`(「앞 + 지금」의 칸도 다를 때만). 잰 값: [결정 기록 3-4](../aisearch/reports/2026-10-26-ml-first-call-decisions.md) (대화 80개, 맞음 relation 75.4% / rule 72.2% / strict 73.3%; 3번씩 다시 돌린 평균은 relation 76.2% / rule 74.3%로 전체 차이는 흔들림 안, continue 76 / 67%는 흔들림 넘음: [3-5](../aisearch/reports/2026-10-26-ml-first-call-decisions.md))
  - 질문 벡터가 실패하거나 `max-wait`(2초)를 넘기면 그 검색은 Haiku 1차로 감(로그 `the question vector was late, haiku makes the first step` / `the question vector failed (…), haiku makes the first step`, 신경망 계산이 예외면 `the ml first call failed (…), haiku makes the first step`)
- 로그 한 줄: `ai search: first ml BOOK continue, keyword OK, pick …`(길 다음에 이어 묻는 관계, 첫 질문은 `first`; Haiku면 `first haiku`)
- 2차 지시문에 이 무렵(v1.16.1~v1.16.3, 사용자 2026-10-26) 더한 것: 후보마다 「출간: YYYY년」(`catalog`의 `BookCards`, 한 번에 읽음)과 맨 위 「올해」, 책 정보의 한계(「책 정보만으로는 알기 어려워요」)를 쓰지 않기, 연도·「최신」은 시기를 물을 때만, 앞 주제를 가리키면 그 주제 책만·둘을 합치면 함께 다룬 책 먼저, 조건과 반대인 책은 fit 1, 조건을 조금만 채우면 글에도 그만큼만. 지금 지시문 요점은 [4-11절](../aisearch/1-how-it-works.md#4-11-2차-호출-llm-first-llm-done), 버전별은 [2-versions v1.16.1~v1.16.3](../aisearch/2-versions.md#v1161)
- 2차 Haiku에 보내는 앞 대화: 책을 보인 턴은 제목만, 되묻기 턴은 「되물은 말·고를 답」(v1.18.0~, 10-1절)
- 사용 기록(`usagelog.ai_search_event`): ML 1차는 `keyword_result = ok`로, 책첵 질문 답은 `pick_result = skipped`·카드 0으로 남음(칸을 늘리지 않음. 따로 세려면 plan에 기록할 일)
- 메모리: 모델 25MB + 프로필 약 10MB가 힙에. 질문 하나 계산은 1ms 안쪽(PC). 운영 로그 단계 `ml` 중간값은 12ms(이어 묻기면 관계 분류기 포함, 검색 8번, [첫 글 시간 보고서](../aisearch/reports/2026-10-27-latency.md) 1절)

## 9. 보유 도서관 많은 10만 권 (2026-10-26)

- 사용자 요청(2026-10-26): "지금 서비스되는 도서 좀 늘리게 도서관 보유량 많은 거 순으로 10만권까지 임베딩 벡터", "그 책들도 … 하이쿠 배치로 ai요약", "cycle 24 참고해서 프로필 만드는 것도 추가", "어지간한 작업은 하이쿠 배치로 … api써서", "소개글 없는 건 따로 빼줘". 수집 PC 세션이 만들고, 이웃 표·표지·운영 넣기는 사이클 23·24 세션이 맡음(2026-10-26 세션끼리 정함)
- 대상: `catalog.book ORDER BY cardinality(library_ids) DESC, id LIMIT 100000`(6절과 같은 식, 마지막 책 보유 36곳; 5만 권이면 52곳). 가져오기 14번 번호. 소개(수집 DB `holding.intro_raw`, 책의 소장 줄 가운데 가장 긴 것)가 **50자 이상인 76,974권만** 만들고, 나머지 23,026권은 모든 단계에서 빼고 `left-out-no-intro.tsv`에 둠
- 코드(main): `scripts/aisearch/build_100k.py`(prepare → kdc → requests → submit → collect → retry → collect → book-texts → profiles-tsv), `scripts/aisearch/build_100k_vectors.py`(벡터, 2,000권씩 나눠 저장·이어 하기, `join`), `scripts/aisearch/make_book_vectors.py --pool pool-100000-intro`. 키는 `.env`의 `CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY`, 데이터 폴더 venv의 `anthropic` SDK
- 도서관 사이트 요청은 없음: 소개는 수집 DB(읽기만), KDC·대상은 PC의 국가서지 TTL(`scripts/aisearch/eval/nlk_kdc.py`, ISBN 있는 58,103권 중 50,939권), 책은 PC 서비스 DB(SELECT만)
- Haiku 5.5 Message Batches(effort low, 1만 건씩), 지시문은 3만 권 때 그대로:
  - `search_line`: `make_search_lines.py`의 SYSTEM + 에이전트 RULES.md의 "내용 없으면 -" 규칙, JSON 형식. 49,726권(내용 없음 `-` 696권은 `search_exclude`로)
  - `for_you_blurb`(AI 요약): `scripts/crawl/foryou_blurbs.py` SYSTEM과 R8-4 검사. **첫 판은 합니다체가 많아**(51,798건 중 31,382건이 「~입니다.」) 걸린 책만 해요체 끝·150자·8자 베낌 규칙을 더 적은 지시문(`BLURB_RETRY_RULE`)으로 두 번 다시 보냄 → 43,227권. 못 만든 책은 `dropped-for_you_blurb.tsv`(베낌 5,557, NONE 2,153, 길이 596, 금지어 244: 「인공지능」을 다루는 책도 금지어 검사에 걸림, 기타 21)
  - `profile`: `eval/profile-RULES-detailed.md`를 시스템 프롬프트로, `docs/reports/ai-search-eval/taxonomy2.json` 값을 JSON 형식의 enum으로. 50,324권. 사전 밖 `subfield` 379개는 빈칸으로 고침
  - 비용(응답 토큰 × 배치 가격, 캐시 반영) 34.34달러: 첫 판 21.0, 다시 보내기 두 번 13.3
- 벡터: KURE-v1 CPU, 6절 3만 권과 같은 글(제목 · 저자 · 출판사 · 연도 + 다음 줄 소개 앞 300자). 3만 권 벡터는 그대로 두고 새 50,549권만(2026-10-26 12:04~19:31, 초당 약 1.75~1.9권) → `ml\pool-100000-intro.npy/.ids.json` 80,549권 → `book-vectors.bin` 81,078권(추천목록 책 벡터 포함), 80.1MB, int8 최악 코사인 0.99975
- 결과 파일(데이터 폴더, git 밖)

| 파일 | 내용 |
|---|---|
| `aisearch\100k\book-texts-100k-exclude-2026-10-26.tsv` | AI 검색 넣기 파일 100,531줄 = 30k 파일 30,939줄 그대로 + 새 `search_line` 49,532 + 새 `search_exclude` 20,060. 넣을 때 `--chaekcheck.collection.import.book-texts-top-books=100000` |
| `ml\foryou\book-texts-for_you_blurb-100k.tsv` | 새 AI 요약 43,227권(그때 `foryou.book_blurb`에 없던 책만, 사이클 26부터 요약은 `catalog.book_summary`). [7-foryou.md](7-foryou.md) 2-2절로 넣음 |
| `aisearch\100k\book-profiles.tsv` | 1차 ML용 프로필 77,551권(기존 27,227 + 새 50,324, `export_first_call.py` 형식) |
| `aisearch\100k\book-vectors.bin` | 10만 권 책 벡터(7절 형식). 운영 백엔드가 힙에 통째로 읽음. 운영 반영(v1.17.0, 2026-10-26 21:59) |
| `aisearch\100k\targets.tsv`, `left-out-no-intro.tsv`, `bands.json`, `report.json`, `dropped-*.tsv` | 대상, 뺀 책, 구간별 소개·표지 수, 배치 결과 |

- 구간별(2026-10-26): 1~3만 위 소개 26,425 / 표지 28,602, 3만~5만 위 17,760 / 19,648, 5만~10만 위 32,789 / 46,716(표지는 fetch-covers DONE, 그 뒤로 더 늘어남)
- **책 번호가 바뀌면**(수집을 반영한 가져오기) 대상부터 다시 만듭니다: prepare가 새 번호로 대상을 뽑고, 이미 만든 글은 책 번호로만 건너뛰므로 번호가 바뀐 뒤에는 `requests`의 건너뛰기 기준(`done_ids`)을 새 번호에 맞춰야 함

## 10. 되묻기, Haiku 생각 끄기, 프롬프트 캐싱 (v1.17.0, 2026-10-27)

사용자 결정(2026-10-26): "AGENT도 쓰긴 할건데 되묻기만 하는 걸로", "되묻기 + 생각 끄기 + 캐시로 배포해줘". 도구 루프 에이전트(`search_again` 등)는 브랜치 `claude/aisearch-agent`에만 있음. 잰 값: [에이전트 보고서](../aisearch/reports/2026-10-26-agent.md) 10·11절.

- **되묻기** `CHAEKCHECK_AISEARCH_PICK_ASK_BACK=true`(prod.env): 2차가 `PICK_ASK_SYSTEM`·`PICK_ASK_SCHEMA`(JSON 맨 앞 `ask` {question, choices})를 쓰고, question이 차 있으면 머리말·카드 없이 `ask` 이벤트와 done `asked`(usagelog `SKIPPED`), 고를 답 4개·40자까지. 어떤 질문에 이 형식을 쓰는지(지금은 4낱말 이하 첫 질문·이어 묻기, 앞 질문 이어 붙이기·되묻기의 답은 빼고)와 이벤트·화면은 [1-how-it-works 4-11·4-12·5절](../aisearch/1-how-it-works.md#4-11-2차-호출-llm-first-llm-done)
  - 1차 ML이 「책 찾기 아님」(v1.17.0 때는 한 낱말이면 2차로, 아니면 AI 호출 없이 고정 되묻기): 2026-10-29부터 한 낱말을 포함해 모두 Haiku 대화 호출(`Prompts.CHAT_SYSTEM`) 한 번이 답과 고를 답을 내고, 그 호출이 실패할 때만 `Prompts.NOT_BOOK_ASK` + `NOT_BOOK_CHOICES` 10개 중 3개 [안].
  - 못 찾음 뒤 고를 답(2026-10-30): `PICK_ASK_BACK=true`일 때 Haiku 한 번(`Prompts.NO_MATCH_SYSTEM`, `AiSearchFlow.noMatchChoices`)이 이어 물을 고를 답을 냄. 실패하면 웹의 예시. 계산은 [1-how-it-works 4-5절](../aisearch/1-how-it-works.md#4-5-길에-따라-여기서-끝나는-경우). 되묻기를 끄면 이 경우는 done `no_match`
  - 로그 끝 `, ask single-ask` / `chat` / `not-book-ask` / `no-match choices`
- **생각 끄기** `CHAEKCHECK_AISEARCH_LLM_THINKING=disabled`: 모든 JSON 호출(2차, 책첵 질문 답, Haiku 1차)에 `thinking: {type: disabled}`. 비우면 모델 기본(Haiku 5.5는 생각 켬). Haiku 5.5는 effort `high` 이하에서만 끌 수 있음(지금 `low`)
- **프롬프트 캐싱** `CHAEKCHECK_AISEARCH_LLM_CACHE_SYSTEM=true`: system을 `cache_control: ephemeral` 블록으로. 사용량의 캐시 읽기·쓰기 토큰도 입력으로 세어 하루 예산은 보수적으로 셈
- **단계별 시간 로그**: 검색 한 줄 끝 `, steps encode=… ml=… nearest=… neighbours=… keyword=… vector=… mix=… cards=… llm-first=… llm-done=…`(요청 시작부터 ms). `llm-first`가 Haiku 첫 글자
- **되돌리기**: prod.env에서 셋을 지우거나 `false`/빈 값으로 두고 `bin/switch.sh backend <지금 판>` — 코드는 그대로 둬도 v1.16.3과 같은 동작

### 10-1. 되묻기 v2 (v1.18.0, 2026-10-27)

v1.17.0 뒤 사용자가 「사진」이 되묻지 않는 것, 되묻기에 버튼 대신 직접 쓴 답(「그림」 → 「인상주의」)을 찾음. 규칙을 모두 모아 부딪히는 곳을 찾고 80문항 규칙 시험을 만들어 고침: [규칙 점검](../aisearch/reports/2026-10-27-rules.md).

바꾼 것 넷(코드 위치와 계산은 [1-how-it-works 4-0·4-11절](../aisearch/1-how-it-works.md#4-11-2차-호출-llm-first-llm-done), 버전 기록은 [2-versions v1.18.0](../aisearch/2-versions.md#v1180)):

- **8권까지 채우기**(P9): 지시문에 「맞는 책이 여럿이면 8권까지(조건과 반대인 책으로는 안 채움)」. 생각 끄기 뒤 줄어든 카드 수 보충
- **「후보 분야」 줄**(C4): 되묻기가 켜 있고 질문이 2낱말 이하(`AiSearchFlow.SHORT_QUESTION_WORDS` [안])면 후보 30권을 책 프로필(`catalog.book_profile`)의 field(+소설/비소설)로 센 수(`MlFirstCall.shelfCounts`, 많은 순 6개)를 책 목록 아래 「후보 분야: 예술·취미(비소설) 9권, …」로 붙임
- **되묻는 말**(A2): 고를 답 말고 원하는 게 있으면 직접 적어 달라는 뜻을 질문에 맞게 덧붙임
- **되묻기에 직접 답하기**(P16·C3): 웹이 앞 대화의 되묻기 턴에 `askQuestion`·`askChoices`를 실어 보냄(`AiSearchRequest.HistoryTurn`, 고를 답 4개까지). 백엔드는 그 다음 질문을 앞 질문과 이어 붙여 찾고, 되묻지 않고, Haiku에게 앞 대화를 「되물은 말: … / 고를 답: …」으로 보여 줌. 그 뜻은 `PICK_SYSTEM`(P16)에 적힘
- 규칙 시험(80문항 두 번): 카드 맞음 76 → 92%, 틀린 카드 1.29 → 0.39장, 애매한 한 낱말 되묻기 3/32 → 30/32, 직접 답한 뒤 맞음 48 → 96%. 대가: 흔한 취미·기술 낱말(강아지, 요가, 캠핑…)에도 되물음 20번 중 9번
- 다시 재기: `scripts/aisearch/eval/rules_run.py <이름> <로그>`(로컬 백엔드), `prep` → Sonnet 하위 에이전트가 `rules-JUDGE_RULES.md`대로 채점 → `score`

## 11. search_book 줄이기 (aisearch-004, v1.18.0, 2026-10-27; 옛 `aisearch.search_book`, 지금 `catalog.book_search`)

사용자 요청(2026-10-27): 넣기 뒤 빈 공간(비효율 1), `library_ids`가 가장 큰 칸(비효율 2). 재 보니 더 큰 문제가 따로 있었음: **글 칸의 ANALYZE가 칸마다 약 200초**.

| | 바꾸기 전 | 바꾼 뒤 |
|---|---|---|
| 표 + 인덱스(76,759권, 로컬) | 188MB(표 136 + 인덱스 51) | **115MB**(표 75 + 인덱스 38) |
| `library_ids` | bigint[] 42MB | **integer[] 25MB** |
| ANALYZE(`line`·`search_text` 칸) | 각 약 200초(DB 정렬 규칙 `en_US.utf8`로 긴 한국어를 정렬) | **`C` 정렬 규칙 1초** |
| 넣기 뒤 | 지운 줄 자리가 남음(넣은 직후 216MB), 자동 ANALYZE가 몇 분~15분 CPU(운영 2026-10-26: 21:58 넣기, 22:13 끝) | 넣기 끝에 `VACUUM (FULL, ANALYZE)` **10초**(그동안 AI 검색이 기다림) |
| 낱말 검색 「소설」 / 「기후 위기」 / 「다이어트 식단 레시피 건강」 / 30권 도서관 수 | 140 / 40 / 12 / 0.4ms | 바꾼 직후 138 / 39 / 12 / 0.3ms, 넣기 + 정리 뒤 141 / 34 / **41** / 0.3ms — 빨라지지 않음(표가 메모리에 있고 LIKE 계산이 시간을 정함). 네 낱말은 정리 뒤 실행 계획이 바뀌어 느려짐(정리만 한 bigint 표에서도 41ms) → 운영 로그 `keyword` 단계로 지켜볼 것 |

- changeset `aisearch-004-search-book-slim`: `library_ids` integer[](가장 큰 번호 2,681), `line`·`search_text` `COLLATE "C"`. 둘 다 사람에게 정렬해 보이지 않고 LIKE + pg_bigm으로만 찾으므로 결과는 같음. 표를 다시 쓰므로 운영 시작 때 몇 초(로컬 4.6초 + ANALYZE 6초) 걸림
- `catalog.AiSearchIndex.compact()`(`JdbcAiSearchIndex`): book-texts 넣기가 끝난 뒤(트랜잭션 밖) `VACUUM (FULL, ANALYZE)`. 트랜잭션 안이면 건너뛰고, 실패하면 경고만(책은 이미 들어가 있음). 로그 `search books compacted in N ms`
- 잰 방법: 실제 표의 복사본에 같은 변경을 하고 크기·쿼리 시간(EXPLAIN ANALYZE 9번 중 첫 번 뺀 중간값, 도서관 25곳)·넣기·VACUUM을 잼(`scripts/aisearch/eval/search_book_slim_test.py`). 원본: [`search-book-first-test-2026-10-27.json`](../reports/ai-search-eval/search-book-first-test-2026-10-27.json)(칸 크기 42 → 25MB 포함), [`search-book-slim-2026-10-27.json`](../reports/ai-search-eval/search-book-slim-2026-10-27.json), 칸별 ANALYZE [`search-book-analyze-2026-10-27.json`](../reports/ai-search-eval/search-book-analyze-2026-10-27.json)
- **개선안(아직 안 함)**: 찾기에 쓰는 칸(`book_id`, `search_text`, `library_ids`)만 좁은 표로 빼면 낱말 검색이 읽는 양이 더 줄지만, 지금 검색 시간은 읽는 양이 아니라 LIKE 계산이 정하므로 효과가 작을 것으로 봄 [추론]. 넣기를 「새 표에 쓰고 이름 바꾸기」로 바꾸면 VACUUM의 10초 기다림도 없앨 수 있음

## 12. 첫 글 시간 줄이기 (v1.19.0, 2026-10-27)

사용자 요청(2026-10-27): Haiku가 첫 응답을 하기까지의 시간 줄이기. SSE라 첫 글 시간이 중요. 자세한 숫자: [첫 글 시간](../aisearch/reports/2026-10-27-latency.md). 바꾼 셋의 계산은 [1-how-it-works 4-1·4-10·4-11절](../aisearch/1-how-it-works.md#4-1-이른-낱말-검색-첫-질문만), 운영에서 알아 둘 것만:

- **1안 낱말 검색을 질문 벡터와 같이**: 첫 질문이면 `MlFirstCall.firstKeywords(질문)`으로 낱말 후보 SQL을 바로 시작하고, 조건이 맞을 때만 그 결과를 씀(아니면 다시 찾음 — 후보는 같음). 로그 한 줄에 `, early keyword`(괄호 안), 실패하면 경고 `the early keyword search failed (…)` 뒤 다시 찾음
- **2안 표지·요약은 Haiku를 부른 뒤에**: 표지·AI 요약을 2차 호출과 동시에 읽고 첫 카드 때 받음. 읽기가 실패하면 경고 `ai search: the book covers were not read (…)` / `ai search: the book summaries were not read (…)`와 함께 표지·요약 없이 카드만
- **4안 되묻기 형식은 짧은 질문·이어지는 질문에만**: `AiSearchFlow.ASK_QUESTION_WORDS`(4 [안]) 낱말 이하 첫 질문이나 앞 대화가 있는 질문만 `PICK_ASK_SYSTEM`·`PICK_ASK_SCHEMA`, 5낱말 이상 첫 질문은 `PICK_SYSTEM`·`PICK_SCHEMA`
- 규칙 시험(80문항, v1.18.0과 번갈아 두 번씩, 로컬): 5낱말 이상 첫 질문 첫 글 1.68 → 1.43초(느린 10% 2.15 → 1.75초), 전체 1.84 → 1.69초, 카드 맞음 91 → 92%, 되묻기 기대대로 146 → 148/156
- 다시 재기: 위 규칙 시험을 두 판으로 돌린 뒤 `python scripts/aisearch/eval/timing_compare.py <기준> <비교>`(로그의 `steps`를 단계별로 비교). Haiku만 따로: `ttft_probe.py`, `ttft_probe2.py`
- 운영에서 볼 것: 로그 `steps`의 `keyword`가 `neighbours`와 거의 같고(이미 끝남), `cards → llm-first`가 줄었는지
- 운영(배포 전후 같은 첫 질문 24개): 5낱말 이상 첫 질문 첫 글 2.63 → 2.06~2.25초, `early keyword` 24/24. 다시 재기: `python scripts/aisearch/eval/prod_timing.py <이름>`(한 IP 분당 10번 제한이라 7초 간격)

## 13. 소개글 넣는 법 실험 (2026-10-29, 운영 변경 없음)

사용자 요청(2026-10-29): 임베딩 소개 글 압축 방법 비교, 전문적·지엽적인 질문을 못 찾는 문제. 자세한 숫자와 다시 돌리는 법: [소개글 보고서](../aisearch/reports/2026-10-29-intro-text.md). 운영에서 알아 둘 것만:

- 지금 세 군데가 소개의 일부만 봄: 책 벡터 = 소개 앞 300자, 낱말 검색 `search_text`와 Haiku가 읽는 글 = 같은 「한 줄」(약 100자). 둘은 같은 `catalog.book_search.line`.
- 실험 추천(1,000권, 검색어 800개): 낱말 검색을 pg_search 한국어 BM25(제목 + 저자 + 한 줄 + 소개 전문)로, 책 벡터 글을 Haiku 요약으로, Haiku가 읽는 글을 요약 + 질문 낱말이 나온 소개 토막으로. 정답이 30위 안에 드는 비율 80~98% → 95~100%(검색어 8종).
- pg_search 0.26.3은 운영 이미지 `chaekcheck/postgres-bigm:18` 위에 `postgresql-18-pgvector` + 데비안 trixie용 .deb로 올라가고, `shared_preload_libraries=pg_bigm,pg_search`로 함께 켜짐(시험용 컨테이너에서 확인). 넣으려면 DB 재시작, `CREATE EXTENSION pg_search CASCADE`(슈퍼유저, pgvector가 같이 켜짐), AGPL-3.0 확인, plan ADR이 필요.
- 아직 넣지 않음. 순서 제안: 7만 7천 권으로 다시 재기 → 로컬 서버에서 `LIKE`/`BM25` 스위치 비교 → 배포(보고서 6절).

## 14. 책이 늘 때 가까운 책 찾기: Java 깔때기 (2026-10-30, 운영 v1.23.0~)

사용자 계획: AI 검색 책을 20만 권, 그 뒤로도 점점 늘림. 자세한 숫자: [책 수 늘리기 보고서](../aisearch/reports/2026-10-30-vector-scaling.md)(앞 단계 [HNSW 보고서](../aisearch/reports/2026-10-29-hnsw.md)). 운영에서 알아 둘 것만:

- 지금 `BookVectorFile.nearest`는 int8 전수 비교, 파일 전체를 힙에 올림(책당 1,036바이트). 운영 97ms·힙 81MB(81,078권). 20만 권 약 240ms·207MB, 50만 권부터 -Xmx640m을 넘음 [추정].
- 사용자 결정 "A. Java 깔때기로 개발해줘": 힙에는 부호 비트(책당 128바이트)만 → 해밍으로 1만 → 질문 대 비트(비대칭 binary)로 2,000 → `book-vectors.bin`(메모리 매핑)에서 그 줄만 읽어 지금과 같은 점수로 120. 클래스 `BookVectorFunnel`, 고르는 곳 `BookVectors.open`.
- 잰 값(PC, 질문 222개): 81,078권 79.8 → 6.9ms, 20만 190.9 → 10.2ms, 50만 484.8 → 21.9ms, 100만 715.7 → 32.0ms. 81k 정확한 상위 120의 99.19%, 1위 같은 질문 221/222.
- 설정: `chaekcheck.aisearch.vector.search.mode`(기본 `funnel`, `CHAEKCHECK_AISEARCH_VECTOR_SEARCH`), `hamming-keep: 10000`, `binary-keep: 2000`. 되돌리기: prod.env에 `CHAEKCHECK_AISEARCH_VECTOR_SEARCH=exact` 후 재시작.
- 운영에서 볼 것: 시작 로그 `ai search vectors on (REMOTE, FUNNEL): … books x 1024, loaded in … ms`, 검색 로그 `steps`의 `neighbours`(97ms → 약 10ms 기대 [추정]).
- **`book-vectors.bin`을 그 자리에서 덮어쓰지 말 것**(매핑 중이라 리눅스에서 읽던 줄이 바뀜): 새 이름으로 올린 뒤 `mv`로 바꾸고 백엔드 재시작.
- 다른 길(쓰지 않음): pgvector halfvec HNSW는 색인(100만 권 약 2.7GB)이 캐시에 다 있어야 빠름. 2GB 서버에서는 안 맞음.
- 전용 벡터 DB(Qdrant 등)는 새 인프라라 plan 결정이 먼저.

## 15. 두 층 + BM25 + 요약 읽기 (2026-10-30, 운영 v1.23.0~ 2026-10-31)

사용자 결정(2026-10-30): "두 층 (조각 512) + BM25 이걸로 개발해", "한 줄 + Haiku 검색 낱말 (원문 불필요)로 가자". 원문 소개는 PC에만 두고(사이클 23 [확정]), 운영에는 Haiku가 쓴 요약·검색 낱말과 벡터만 갑니다. 측정은 [소개글 보고서](../aisearch/reports/2026-10-29-intro-text.md) 10·11절, 규모는 [책 수 늘리기 보고서](../aisearch/reports/2026-10-30-vector-scaling.md) 10절.

| 단계 | 무엇 | 파일·명령 | 상태 |
|---|---|---|---|
| 1 | Haiku 한 번 호출로 요약(평균 270자) + 검색 낱말(평균 41개), 배치 API | `scripts/aisearch/build_summary_terms.py prepare/submit/collect/retry` → `chaekcheck-data\aisearch\two-layer\summary-terms.tsv` | 76,974권 끝(실패 16 → 다시 받아 0). 입력 1억 4,815만·출력 4,597만 토큰, 약 $19 [추정, 배치 반값] |
| 2a | 소개 512토큰 조각 벡터(KURE-v1, 6,000자까지) | `scripts/aisearch/build_chunk_vectors.py` (이어 하기 됨) → `join`; 남은 파트는 `scripts/aisearch/gpu_chunk_vectors.py pack/encode/take`(빌린 GPU) | 끝(2026-10-31): 157,314조각, 76,974권. 남은 32파트는 vast.ai RTX 4090에서 약 15분, $0.21 |
| 2b | 요약 벡터(「제목 · 저자 · 출판사 · 연도」 + 요약) → 책마다 요약 줄 + 조각 줄들의 `book-vectors.bin` | `scripts/aisearch/build_two_layer_vectors.py summary` / `file OUT.bin` | 끝: `chaekcheck-data\ml\two-layer\book-vectors-two-layer.bin` 238,392줄, 81,078권, 235.5 MB. 넣기 파일 `chaekcheck-data\aisearch\two-layer\book-texts-two-layer-2026-10-31-export.tsv`(`build_summary_terms.py book-texts`) |
| 3 | 벡터 검색이 책 하나의 여러 줄을 최고 점수로 묶음 | `BookVectors`, `BookVectorFunnel`, `BookVectorFile` | 코드 끝 |
| 4 | BM25: `book_search`에 `summary`·`terms`·`written_on`·`keyword_text`·`library_words`(생성 칼럼), 색인 `book_search_bm25` | Liquibase `catalog/005-library-words.sql`, `catalog/006-book-search-bm25.yaml`; 넣기 파일 `search_summary`·`search_terms` 줄 | 코드 끝, 스위치 기본 `like` |
| 5 | 2차가 읽는 글 = 요약 + 맞은 검색 낱말 | `JdbcAiSearchIndex.readingTexts`, `AiSearchFlow` 한 줄 | 코드 끝, 스위치 기본 `line` |
| 6 | 8만 권 전체 다시 재기(계획 도서관 거르기 넣고, 진짜 Haiku, 프로필 켬·끔) | `AiSearchTwoLayerEvalTests`, `scripts/aisearch/eval/two_layer_step6.py` | 끝(2026-10-31, [6단계 보고서](../aisearch/reports/2026-10-31-two-layer-step6.md)): 카드에 찾는 책 40.3 → 56.1%, 1번 카드 29.5 → 39.7%, 주제 77.7 → 79.1%. 빼기(rulesOut)만 끄면 60.1% / 42.3% / 84.0%(운영 값) |
| 7 | plan 기록(pg_search AGPL, 원문 규칙, rulesOut 끔), 배포(이미지 바꾸고 DB 재시작) | 아래 「7단계 배포 순서」, `docs/plan-feedback.md` 「AI 검색 두 층 + BM25」 | 끝: v1.23.0(6fc12672) 2026-10-31, dev 24. 넣기 76,759 / summary 76,182 / keyword_text 76,759, 시작 로그 `238392 rows x 1024 (up to 10 a book)`. 운영 질문 「불확정성 원리」 4장 4.7초(keyword 0.4초), 재시작 뒤 첫 검색만 13.4초 |

### 7단계 배포 순서 (2026-10-31)

바뀌는 것: DB 이미지(pg_search 0.26.3 + pgvector, preload `pg_bigm,pg_search`), Liquibase `catalog-006`(칸 다섯 + BM25 색인), 넣기 파일(요약·검색 낱말), `book-vectors.bin`(두 층), prod.env(`RULES_OUT=false`, 뒤에 `KEYWORD_MATCH=bm25`·`READING_TEXT=summary`).

1. **넣기 파일과 벡터 파일을 미리 올림**: PC `chaekcheck-dataisearch	wo-layerook-texts-two-layer-2026-10-31-export.tsv` → 서버 `/srv/chaekcheck/book-texts/`(644). PC `chaekcheck-data\ml	wo-layerook-vectors-two-layer.bin`(235.5 MB) → 서버 `/srv/chaekcheck/aisearch-model/book-vectors-two-layer.bin`(아직 이름 바꾸지 않음).
   - 넣기 파일의 바탕은 2026-10-28 운영 내보내기(`book-texts-100k-exclude-2026-10-28-export.tsv`, search_line 76,759 · for_you_blurb 43,227 · search_exclude 23,772)에 요약·검색 낱말 76,974권(76,182권 한 줄 있음)을 더한 것입니다. 넣기는 `book_search`를 통째로 바꾸므로, 그 뒤 운영 한 줄·소개가 바뀌었으면 먼저 다시 내보내 `build_summary_terms.py book-texts <새 내보내기> <출력>`으로 다시 만듭니다. 확인: `SELECT count(*), count(line) FROM catalog.book_search` = 76,759 / 76,759.
2. **DB 이미지를 서버에서 다시 빌드**(arm64, `deploy/test/README.md` 5번과 같은 방식): `docker build -t chaekcheck/postgres-bigm:18 postgres-bigm`(새 `docker/postgres-bigm/Dockerfile`). 확인: `docker run --rm chaekcheck/postgres-bigm:18 ls /usr/share/postgresql/18/extension | grep pg_search`.
3. **DB 재시작**: 새 `deploy/prod/compose.yaml`(preload `pg_bigm,pg_search`)로 `db`만 다시 만듦. 끊김은 DB가 다시 뜰 때까지(로컬 몇 초 [추정]). 옛 이미지에 새 preload를 쓰면 DB가 안 뜨므로 2번이 먼저입니다. 확인: `SHOW shared_preload_libraries` = `pg_bigm,pg_search`.
4. **새 판 배포**(release.sh, prod.env는 `RULES_OUT=false`, `KEYWORD_MATCH=like`, `READING_TEXT=line`): 시작할 때 Liquibase가 `catalog-006-book-search-texts`(칸 다섯)와 `catalog-006-book-search-bm25`(`CREATE EXTENSION pg_search CASCADE` + 색인)를 돌립니다. 이때 `keyword_text`가 비어 있어도 `like`라서 검색은 전과 같습니다. 확인: `SELECT to_regclass('catalog.book_search_bm25')`가 null이 아님, 시작 로그에 BM25 경고 없음.
5. **넣기**: 6-aisearch 「한 줄 넣기」의 운영 명령에 1번 파일, `--chaekcheck.collection.import.book-texts-top-books=100000`. 확인: `SELECT count(*), count(summary), count(*) FILTER (WHERE keyword_text <> '') FROM catalog.book_search` = 76,759 / 76,182 / 76,759.
6. **벡터 파일 바꾸기와 스위치**: `mv book-vectors-two-layer.bin book-vectors.bin`(덮어쓰지 말고 `mv`, 옛 파일은 `book-vectors-100k.bin`으로 남김), prod.env를 `CHAEKCHECK_AISEARCH_KEYWORD_MATCH=bm25`, `CHAEKCHECK_AISEARCH_READING_TEXT=summary`로 바꾸고 `bin/switch.sh backend <지금 판>`으로 다시 띄움(BM25 색인이 있는지는 시작 뒤 첫 검색에서 한 번 묻고 기억하므로, 색인이 생긴 뒤 재시작해야 `bm25`가 됨). 확인: 시작 로그 `ai search vectors on (REMOTE, FUNNEL): 238392 … books`(줄 수), 검색 로그 `steps`의 `keyword`가 평소 범위, 질문 몇 개로 카드 확인(예: 「불확정성 원리」, 「백화점 무너진 뒤 살아남은 여자와 임신한 이웃의 이야기가 담긴 단편집」).
7. **되돌리기**: 스위치만 `like`/`line` + 옛 벡터 파일로 `mv` 후 재시작. `RULES_OUT=true`로 빼기를 다시 켤 수 있음. DB 이미지·색인은 그대로 두어도 `like`에서는 쓰이지 않습니다.

PC 로컬: `compose.yaml`도 preload `pg_bigm,pg_search`. 이 변경 뒤 `docker compose up`은 pg_search가 든 이미지가 있어야 뜹니다(`wsl -d Ubuntu -- docker compose build`, 또는 이미 지은 `chaekcheck/postgres-bigm:18-search`를 `:18`로 태그).

- **ANALYZE가 40분 넘게 걸림(v1.23.0 배포 때)**: `book_search`의 `summary`·`terms`·`keyword_text`(한글 약 300·300·420자) 통계가 표본을 DB 정렬 규칙(en_US.utf8)으로 정렬하느라 칸마다 1분을 넘김(한 줄 100자는 0.1초). SQL에서 거르거나 정렬하지 않는 칸이라 통계를 끔(Liquibase `catalog-006-book-search-statistics`, `…-bm25-statistics`, BM25 색인의 식 열 둘도). 측정 복사본에서 ANALYZE 10분 넘음 → 0.4초. 넣기 뒤 `VACUUM (FULL, ANALYZE)`도 이 시간만큼 줄어듦. 확인: `SELECT attname, attstattarget FROM pg_attribute WHERE attrelid IN ('catalog.book_search'::regclass, 'catalog.book_search_bm25'::regclass) AND attstattarget IS NOT NULL` → 다섯 줄 모두 0.

운영에서 알아 둘 것:
- **DB 이미지**: `docker/postgres-bigm`에 pg_search 0.26.3(.deb, PC amd64·서버 arm64) + pgvector. `shared_preload_libraries=pg_bigm,pg_search`. compose(로컬·운영)의 `command`는 아직 `pg_bigm`만: **이미지를 새로 지은 뒤에만** `pg_search`를 더할 것(옛 이미지에 미리 불러오기를 넣으면 DB가 안 뜸). 그 전까지는 Liquibase가 BM25 색인 단계를 건너뛰고(`onFail: CONTINUE`, 다음 시작에 다시 시도) 서버는 `like`로 돔.
- **색인을 만들 때**: 이미지와 preload가 바뀐 뒤 첫 시작에서 `CREATE EXTENSION pg_search CASCADE` + 색인 만들기(PC 10만 줄 15초, 운영 ARM은 더 김 [추정]).
- **도서관 거르기는 색인 안**: 도서관 번호를 `library_words`(예: `l3 l17`) 칸으로 넣고 `||| '…'::pdb.boost(0)`. 색인 밖 `&&`로 거르면 10만 줄 125ms(안에서는 7ms).
- **넣기 파일**: `search_summary`(1~500자)·`search_terms`(1~2,000자) 줄을 담은 book-texts 파일. 새 칸이 비어 있는 파일은 이미 있는 요약을 지우지 않게 거절.
- 로컬 시험 DB: 다른 세션과 겹치지 않게 `chaekcheck-bm25-db-test`(이미지 `chaekcheck/postgres-bigm:18-search`, 포트 5513), 테스트는 `TEST_PG_COMMAND=wsl,-d,Ubuntu,--exec,docker,exec,-i,chaekcheck-bm25-db-test`.

