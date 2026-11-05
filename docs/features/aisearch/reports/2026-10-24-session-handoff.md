# 사이클 24 다음 세션 인계 (클라우드 dev 세션 → API 키가 있는 새 세션)

> **이 문서가 답하는 것**: 2026-10-24, AI 책 찾기(사이클 24)를 만들던 중 클라우드 세션이 API 키가 있는 다음 세션에 넘긴 말. 그때 어디까지 했고, 무엇을 이미 정했고, 다음 세션이 무엇부터 할지.
>
> **답하지 않는 것**: 지금 요청 하나가 어떻게 도는지 → [1-how-it-works.md](../1-how-it-works.md). 버전마다 바뀐 것 → [2-versions.md](../2-versions.md). 운영 명령·설정 → [6-aisearch.md](../../../maintenance/6-aisearch.md).
>
> **그때 운영 버전**: AI 검색은 운영에 없었음(main에 코드는 들어갔지만 `CHAEKCHECK_AISEARCH_ENABLED=false`). 처음 켠 것은 다음 날 [v1.10.0](../2-versions.md#v1100)(2026-10-25 09:34).
>
> **지금(v1.19.0)과 다른 점**: 아래 「0. 그 뒤 바뀐 것」 표. 본문(1절부터)은 2026-10-24에 쓴 그대로 두었습니다. 이 파일은 `docs/cycle24-session-handoff.md`에서 옮겨 왔으므로, 맨 아래 「시작 글」의 경로는 옛 경로입니다.

## 0. 그 뒤 바뀐 것 (2026-10-29 덧붙임)

이 인계서가 「남음」, 「[안]」, 「나중에」로 적은 것이 그 뒤 어떻게 되었는지입니다. 근거는 오른쪽 칸(코드·설정 파일, [plan-feedback.md](../../../cycles/plan-feedback.md)의 절 이름, [2-versions.md](../2-versions.md)).

| # | 인계서에 적힌 것 | 그 뒤 | 근거 |
|---|---|---|---|
| 1 | 2단계: PC 스크립트 + API 키로 한 권씩 바로 불러 찾기용 한 줄을 만듦 | 목록 책 한 줄을 이 방식으로 만든 기록은 없습니다. 실제로는 판마다 다른 방법을 썼습니다. ① v1.10.0: 목록 책 1,911권에 **추천도서 AI 요약을 임시 한 줄로** 넣음(`search-lines-temp-2026-10-25.tsv`, 사용자 「전체 수집 정리 이후에 새로 넣을거고」). ② v1.12.0(3만 권): PC의 Claude Code 세션에서 **Haiku 하위 에이전트**가 소개 원문으로 만듦(처음 506권만 API 바로 호출). ③ 10만 권(2026-10-26): **Haiku 5.5 Message Batches**. 목록 책 1,911권의 임시 한 줄을 바꿔 넣었다는 기록은 찾지 못했습니다 | plan-feedback 「배포 v1.10.0」, [6-aisearch 6·9절](../../../maintenance/6-aisearch.md), [2-versions v1.10.0](../2-versions.md#v1100) |
| 2 | 1단계 두 번째 실행(2단계 TSV로) | v1.10.0 배포 기록의 「남은 것」에 그대로 있고, 그 뒤 했다는 기록은 찾지 못했습니다 | plan-feedback 「배포 v1.10.0」 |
| 3 | 4~8단계 서버, 9·10단계 웹 | 2026-10-25 v1.10.0으로 운영에 켬. 그 뒤 흐름은 크게 바뀜: 1차는 Haiku 대신 질문 벡터 위 MLP(v1.16.0), 후보는 낱말 + 벡터 RRF(v1.15.0), 대상 책 1,911 → 76,759권, 되묻기(v1.17.0·v1.18.0) | [2-versions 한눈에](../2-versions.md#한눈에-운영에-나간-순서) |
| 4 | 11단계: 처리방침 새 판은 사용자 확인 뒤 | 처리방침 3판(`sitecontent/004-privacy-policy-v3.yaml`, 시행 2026-10-25)을 v1.10.0과 함께 냄. 사용자 「이대로 올려」 | plan-feedback 「배포 v1.10.0」, `CHANGELOG.md` v1.10.0 |
| 5 | 12단계: CHANGELOG 맨 위 절, AC 대응표 | CHANGELOG는 v1.10.0부터 AI 검색 줄이 있음. 사이클 24의 AC 대응표는 (확인 못 함) | `CHANGELOG.md` |
| 6 | Anthropic 사용량 한도 결정 | Console 월 한도는 묻지 않았고, 서버의 하루 비용 상한 $2만 둠(`chaekcheck.aisearch.budget.daily-usd: 2`) | plan-feedback 「배포 v1.10.0」, `application.yml` |
| 7 | 동시 AI 찾기 상한: 처음 값 20, 503 `server_busy`(이름은 [안]) | 그대로 들어감: `chaekcheck.aisearch.max-concurrent: 20`(지금 검색의 60자리와 따로), 넘으면 스트림을 열기 전 503 `server_busy` + `Retry-After: 5` | `application.yml`, `AiSearchErrors`, plan-feedback 「8단계」 |
| 8 | `occurred_at` → `received_at`은 쓰기 전에 사용자 확인 | `usagelog.ai_search_event.received_at`으로 들어감(changelog 주석: 「the design's occurred_at; user 2026-10-24」) | `db/changelog/usagelog/008-ai-search-event.yaml` |
| 9 | 저자를 캐시가 직접 드는 일은 후속 작업 24-F1 | 아직 안 함. `TODO(24-F1)`가 `catalog/BookAuthors`, `picks/ListedBooks`, `picks/internal/cache/CacheListedBooks`에 그대로 있음 | 코드 |
| 10 | `picks/package-info.java`의 공개 형식 설명은 23과 합칠 때 함께 고침 | 아직 안 고침. 지금도 「One public Java type, `CuratedListTransfer`」이고 `ListedBooks` 설명이 없음(사이클 23의 `BookTagTransfer` 줄만 더해짐) | `picks/package-info.java` |
| 11 | 스트림 이벤트의 표지 칸 이름(`coverPath` / `coverUrl`)은 8단계에서 정함 | `card` 이벤트는 `coverUrl`. 값은 목록 책이면 `ListedBook.coverPath`, 아니면 「책첵 추천」 표지(v1.12.2부터) | `AiSearchEvents.Card`, plan-feedback 「8단계」 |
| 12 | `ModularityTests`는 4단계에서 고침 | 고쳐짐: `aisearch`는 `picks`, `library`, `usagelog`, `foryou`, `catalog`만 쓴다고 검사함(인계서 때 계획은 `picks`, `library`, `usagelog`. `foryou`는 v1.11.0의 이웃 읽기부터) | `ModularityTests.aisearchUsesPicksLibraryAndUsagelogOnly` |
| 13 | 시험 DB를 소스로 빌드(2절) | 그때 클라우드 환경에서만 쓴 방법. 로컬 PC는 저장소의 `docker/postgres-bigm` 이미지로 `db-test`를 띄움 | 저장소 `CLAUDE.md` |

---

- 작성 2026-10-24, 앞 세션(`chaekcheck-dev-cycle-24`). 사용자 요청: "너가 api키 필요하면 새로운 세션 해야 한다고 했자나 그 세션에게 전달할 말 좀 만들어 줘"
- 새 세션 시작 글(사용자가 붙여 넣음)은 이 파일 맨 아래 "시작 글"입니다. 자세한 것은 이 파일과 `docs/cycles/plan-feedback.md` `# 사이클 24` 절에 있습니다.

## 1. 지금 어디까지 했나

| 단계 | 상태 | 기록 |
|---|---|---|
| 0단계 대조 | 끝. 다른 점 10개를 사용자에게 알리고 답을 받음 | `plan-feedback.md` `# 사이클 24` "0단계 대조" |
| 1단계 Haiku 프로토타입 | 첫 실행 끝(아래 "1단계 첫 실행" 줄) | 아래 3절 |
| 2단계 PC 배치 | 방식만 정함((가) 바로 호출 + 요청 파일을 따로). 실행은 사용자 노트북 | `# 사이클 24` "2단계 배치 방식", "자체 LLM 조사" (그 뒤 → 0절 #1) |
| 3단계 `ListedBooks` | 코드·시험 작성(아래 "3단계 결과") | 브랜치 `claude/awesome-carson-8416jq` |
| 1단계 첫 실행 | 끝(2026-10-24, API 키 세션). 두 번째 실행은 2단계 TSV(반영 뒤 DB 번호)로 | `# 사이클 24` "1단계" |
| 4~8단계 서버 | 끝(2026-10-24, API 키 세션): `aisearch` 모듈·표·`book-texts`, 낱말 맞추기, `LlmClient`, 횟수·비용·이용 기록, 흐름·컨트롤러. 코드 검토 반영 | `# 사이클 24` "4단계"~"8단계" |
| 11단계 | 일부: nginx `ai-search`·16KB, 운영 compose·`prod.env` 스위치. 처리방침 새 판은 사용자 확인 뒤 | `# 사이클 24` "8단계" 끝 |
| 9·10단계 웹 | 끝(2026-10-24): 첫 화면 AI/직접 메뉴·예시 칩, `/ai-search` 대화 화면(ChatGPT 모양), 머리말 「AI 검색 · 직접 검색」, 카드 → 추천도서 책 창. 사용자가 노트북에서 보며 고친 것은 `# 사이클 24` "8단계 뒤 사용자 결정". 2026-10-24 main에 반영(운영은 `CHAEKCHECK_AISEARCH_ENABLED=false`라 꺼진 채) | `# 사이클 24` |
| 12단계 | 남음: CHANGELOG 맨 위 절, AC 대응표 | — (그 뒤 → 0절 #5) |
| 남은 일 | 2단계 PC 배치로 한 줄 만들기·반영(노트북), 1단계 두 번째 실행, 처리방침 새 판(사용자 확인), Anthropic 사용량 한도 결정 | — (그 뒤 → 0절 #1·#2·#4·#6) |

### 사용자가 이미 정한 것 (다시 묻지 않음)

- 저자는 **우리 책 DB**(`catalog.book.author_display`)에서. 캐시가 저자를 직접 드는 일은 **후속 작업 24-F1**(`plan-feedback.md`, `docs/maintenance/4-picks.md` 10절, 코드의 `TODO(24-F1)`) (그 뒤 → 0절 #9)
- 서버 전체 동시 AI 찾기 상한: 지금 검색(60)과 **따로 하나**, 처음 값 20, 넘으면 503 `server_busy`(코드·초·문구·설정 이름 `chaekcheck.aisearch.max-concurrent`는 [안]) (그 뒤 → 0절 #7)
- 2단계: (가) PC 스크립트 + API 키, 한 권씩 바로 호출. 스크립트는 ① 요청 파일(OpenAI Batch JSONL 모양, `custom_id`=`book_id`, 고정 지시문 맨 앞) ② 보내기 ③ TSV로 나눔
- 0단계 다른 점 중 사용자 답을 아직 따로 받지 않은 것(`received_at` 칸 이름, `daily_usage` 트리거, IP 직접 읽기, nginx `ai-search` 경로, compose 환경 변수, `GET /api/v1/ai-searches/status`, `ModularityTests` 고침): 앞 세션이 그대로 진행한다고 알렸고 사용자가 반대하지 않음. **`occurred_at` → `received_at`은 [확정] 칸 이름이라 4·7단계에서 쓰기 전에 사용자에게 한 번 확인할 것** (그 뒤 → 0절 #8)

## 2. 새 세션 준비 (이 클라우드 환경에서 실제로 된 방법)

1. **plan 저장소**: `add_repo`로 `hodu-sketch/chaekcheck-plan`을 붙이고 브랜치 `claude/compassionate-lovelace-mzvp4f`를 받음(사이클 24는 plan main에 없음). 읽을 차례는 시작 글 2번
2. **API 키**: 환경 변수 `CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY`. 값을 출력·로그·커밋·문서에 남기지 않음. 있는지만 `test -n "$CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY" && echo set`으로 봄
3. **시험 DB (PostgreSQL 18 + pg_bigm, 5433)** (그 뒤 → 0절 #13): `docker/postgres-bigm` 이미지는 이 환경에서 못 만듦(Debian apt·GitHub 압축 파일이 프록시에서 403). 대신 소스로 빌드:
   ```bash
   apt-get install -y bison flex libicu-dev libreadline-dev zlib1g-dev pkg-config build-essential
   S=<scratchpad>; git clone --depth 1 --branch REL_18_6 https://github.com/postgres/postgres $S/pgsrc
   (cd $S/pgsrc && ./configure --prefix=/opt/pg18 --without-readline && make -j"$(nproc)" -s && make install -s)
   git clone --depth 1 --branch v1.2-20250903 https://github.com/pgbigm/pg_bigm $S/pg_bigm
   (cd $S/pg_bigm && make USE_PGXS=1 PG_CONFIG=/opt/pg18/bin/pg_config && make USE_PGXS=1 PG_CONFIG=/opt/pg18/bin/pg_config install)
   useradd -m pgrun; mkdir -p /opt/pgdata && chown pgrun /opt/pgdata
   su pgrun -c "/opt/pg18/bin/initdb -D /opt/pgdata/test -U postgres -A trust -E UTF8 --locale=C.UTF-8"
   printf "shared_preload_libraries = 'pg_bigm'\nport = 5433\n" >> /opt/pgdata/test/postgresql.conf
   su pgrun -c "/opt/pg18/bin/pg_ctl -D /opt/pgdata/test -l /opt/pgdata/test.log start"
   /opt/pg18/bin/psql -h localhost -p 5433 -U postgres -c "CREATE ROLE chaekcheck LOGIN PASSWORD '<로컬 시험용 아무 값>' SUPERUSER" -c "CREATE DATABASE chaekcheck_test OWNER chaekcheck"
   ```
   저장소 맨 위 `.env`(git 무시됨)에 `TEST_DB_NAME=chaekcheck_test`, `TEST_DB_USERNAME=chaekcheck`, `TEST_DB_PASSWORD=<위 값>`, `TEST_DB_PORT=5433`
4. **백엔드 빌드**: `gradlew`에 실행 권한이 없어 `bash ./gradlew build --no-daemon`. 내보내기·반영 시험용으로 `export TEST_PG_COMMAND="env,PATH=/opt/pg18/bin:/usr/bin:/bin,PGHOST=localhost,PGPORT=5433,PGPASSWORD=<위 값>"`. Maven Central이 가끔 429를 주면 잠깐 뒤 다시. 429가 계속되면(2026-10-24 두 번째 세션: 한 시간 넘게 계속, Gradle은 "Could not find"로 보임) 저장소 파일은 그대로 두고 이 환경에만 `~/.gradle/init.d/central-mirror.gradle`을 두어 Google의 Maven Central 사본 `https://maven-central.storage-download.googleapis.com/maven2/`을 먼저 보게 하고 `--refresh-dependencies`로 다시 돌림(그때 1,188개 중 같은 3개만 실패)
5. **이 환경에서 원래 실패하는 시험 3개**(앞 세션 변경을 빼도 매번 실패, 2026-10-24): `LaneThrottleTests.sameLaneKeepsTheIntervalWhileOtherSitesDoNotWait`, `LaneThrottleTests.sameHostNameOnDifferentPortsSharesOneSlot`("no free lane … before the deadline"), `CatalogTransferTests.brokenFileKeepsTheCatalog`. 원인은 아직 안 봄
6. **웹**: 이 환경은 Node 22, `web/package.json`은 Node 24 이상 → 9·10단계 전에 Node 24 준비
7. `dockerd`는 직접 띄워야 함(`dockerd > /tmp/dockerd.log 2>&1 &`). 컨테이너 안에서는 프록시를 못 써서 이미지 빌드에 쓰기 어려움

## 3. 새 세션의 첫 일: 1단계 프로토타입

- 인계서 1단계 그대로(plan `docs/cycles/24-ai-book-search/handoff.md` "1단계"). 먼저 `claude-api` 스킬을 읽고 공식 문서로 `anthropic-version`·base URL·스트림에서 `usage`가 오는 자리·structured outputs·thinking/effort 끄는 법을 확인하고 URL과 날짜를 적음
- 도구 자리 [안]: `scripts/aisearch/`(빌드·CI 밖, 2단계 PC 배치 스크립트와 같은 곳). 질문 20개는 plan `docs/research/ai-recommend/llm-test-set.md` 1절, 2차 지시문 초안 3절
- 후보 책: 2단계 전이라 한 줄이 없음 → 제목·저자만. 클라우드에는 수집 DB도 운영 DB도 없으므로 후보 목록을 어디서 얻을지 먼저 사용자에게 물음(예: 노트북에서 목록 책 제목·저자만 뽑아 붙여 줌)
- 잴 것과 기록 칸: 인계서 1단계 "잴 것", `plan-feedback.md` `# 사이클 24`에 질문마다 한 줄. **본 구현(6·8단계)에 들어가기 전에 값을 사용자·plan에 알림**
- 질문 글·키를 로그에 남기지 않음(프로토타입 출력도)

## 4. 3단계 결과 (앞 세션)

- 새 파일만: `catalog/BookAuthors.java` + `catalog/internal/JdbcBookAuthors.java`, `picks/ListedBooks.java` + `picks/internal/cache/CacheListedBooks.java`, 시험 `BookAuthorsTests`·`ListedBooksTests`(db-test)·`CacheListedBooksTests`(단위). 고친 기존 파일은 `catalog/package-info.java` 주석뿐(`picks` 기존 파일은 손대지 않음 — 그래서 `picks/package-info.java`의 공개 형식 설명은 아직 `CuratedListTransfer`만. 23과 합칠 때 함께 고침) (그 뒤 → 0절 #10)
- `current()`는 캐시 사본이 바뀌었을 때만 다시 만들고 그때 `catalog`를 한 번 읽음(저자). 저자 읽기가 실패하면 앞서 알던 저자로 쓰고 1분에 한 번만 다시 시도. 인계서의 "DB 호출 없음"과 다른 점 → `# 사이클 24` "3단계"에 적음
- 카드 칸 이름은 `coverPath`(design-delta는 `coverUrl`, dev 관례로 바꿈). 스트림 이벤트 JSON의 칸 이름은 8단계에서 design-delta대로 `coverUrl`로 내보낼지 정함 (그 뒤 → 0절 #11)
- `loadFromDb()`는 같은 패키지에서 새 `PicksCache`를 만들어 `refresh()`, 줄이 있는데 읽지 못한 목록이 있으면 `ListsNotReadException`
- `ModularityTests`는 아직 안 고침(`aisearch`가 생기는 4단계에서) (그 뒤 → 0절 #12)

## 5. 지킬 것 (요약)

- 사이클 23과 나란히: `picks`는 새 파일만, `usagelog` changelog 번호는 합칠 때(changeSet id `usagelog-ai-search-event`), 첫 화면은 24만·추천도서 화면은 23만
- [확정] 값·문구를 바꾸기 전에 사용자에게 물음. 설계와 다르면 코드 전에 알림. 다른 세션에 일을 넘기지 않음
- PC 수집 DB가 필요한 일(0단계 #15, 2단계 실행)은 하지 않고 사용자에게 알림

---

## 시작 글 (사용자가 새 세션 첫 메시지로 붙여 넣음)

```
chaekcheck 사이클 24 "AI 책 찾기"를 이어서 개발합니다. 앞 클라우드 세션이 0단계와 3단계를 했고, 이 세션은 Anthropic API 키(환경 변수 CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY)가 있는 세션입니다.

1. dev 브랜치 claude/awesome-carson-8416jq 에서 일합니다. 먼저 docs/cycle24-session-handoff.md 를 끝까지 읽고, 그다음 docs/cycles/plan-feedback.md 의 "# 사이클 24" 절을 읽습니다. 사용자가 이미 정한 것은 다시 묻지 않습니다.
2. plan 문서: 이 세션에 hodu-sketch/chaekcheck-plan 이 없으면 add_repo로 붙이고 브랜치 claude/compassionate-lovelace-mzvp4f 를 받습니다. 읽을 차례: docs/cycles/24-ai-book-search/handoff.md → design-delta.md("검토 뒤 덧붙임"·"확인 필요" 포함) → spec.md·flow.md → docs/adr/0150~0153 → docs/settings-index.md 10절 → docs/research/ai-recommend/llm-test-set.md
3. 시험 DB와 빌드는 인계 파일 2절대로 준비합니다(이 환경에서는 Docker 이미지 대신 PostgreSQL 18을 소스로 빌드).
4. 첫 일은 1단계 Haiku 프로토타입입니다(인계 파일 3절). 키 값은 어디에도 출력·저장하지 않습니다. 후보 책 목록을 어디서 얻을지는 먼저 저에게 물어 주세요.
5. 1단계 값을 저에게 알린 뒤 4단계부터 이어 갑니다. 설계와 다른 점이 생기면 코드를 쓰기 전에 알려 주세요. 다른 세션에 대신 맡기지 않습니다.
```
