> 기록 보관(2026-10-24 클라우드 → 로컬 세션 인계, 그때 그대로). 여기 적힌 「지금 상태·다음 할 일」은 지난 것. 지금은 [README.md](../README.md)에서 시작

# 사이클 23 다음 세션 인계 (클라우드 dev 세션 → 로컬 PC 세션)

- 작성 2026-10-24, 클라우드 세션 `사이클 23 「나에게 맞는 책」 개발`. 사용자 요청: "다음에 로컬 세션에서 이어서 할 수 있게 전달하는 문서 만들어줘"
- 새 세션 시작 글(사용자가 붙여 넣음)은 이 파일 맨 아래 "시작 글"입니다. 자세한 기록은 `docs/cycles/plan-feedback.md` `# 사이클 23` 절에 있습니다.

## 1. 어디서 이어 가나

- dev 브랜치 **`claude/cycle-23-for-you-picks-kzjabw`**(main `adba2d3`에서 시작, 끝 커밋은 이 문서를 더한 커밋). 로컬에서 `git fetch origin claude/cycle-23-for-you-picks-kzjabw` 뒤 그 브랜치로 작업합니다
- plan 문서(읽기 전용): plan main `18133c3` 이후 `docs/cycles/23-for-you-picks/`의 `handoff.md`(인계서), `design-delta.md`(기준 설계), `to-dev-popular-list-check.md`, `to-dev-intro-test-2026-10-24.md`. 로컬 경로 `..\chaekcheck-plan\`
- `docs/current/handoff.md`는 사이클 20 그대로이고 끝에 23 안내 한 줄만 있습니다(dev `CLAUDE.md`가 불러오는 것은 사이클 20). 23은 위 사이클 폴더의 인계서를 따릅니다

## 2. 지금까지 한 것

| 단계 | 상태 | 커밋 |
|---|---|---|
| 0 계획을 바꿀 수 있는 확인 | 끝. 멈춤 조건 없음(프록시 길 있음, 교보 일반·YES24 V1 인기순은 지금 파서로 읽음) | `a86ef6a` |
| 사용자 답 | 시작점 "24가 main에 들어갈 때까지 기다림", 출판 연도 "대표 출판일 그대로", `category` "all / base / similar" | `8d4e6ec` |
| 1 Liquibase | `foryou/001-for-you.yaml`(표 일곱), `picks/006-tag-dictionary-and-book-tag.yaml`. `usagelog`는 24 뒤 | `7d284e7` |
| 2 `catalog` | 새 공개 타입 `BookCards`(카드 칸·소장 키 역조회·`workKeys`) | `cd264f2` |
| 3 `availability` | 새 공개 타입 `RankedListings`(교보 일반 80줄·YES24 V1 50줄 쪽, `*.dkyobobook.co.kr` 막음) | `967ef9b` |
| 4 `picks` 꼬리표 몫 | `BookTagTransfer`, `forCard`. `ListedBooks`는 24 뒤 | `591fb73` |
| 5 새벽 만들기 | `foryou` 모듈, 매일 03:30 [안] + 켤 때 | `ef66bd8` |
| 6 API | `GET /api/v1/for-you/books`, `/similar-books` | `6918396` |
| 7 `foryou` 몫 | `ForYouTransfer`(이웃 full·add, 탭 글·표지). `collection` 모드는 24 뒤 | `1057138` |
| 9 웹 | S18 `/picks/for-you`(맨 앞 탭), S19, 본 책 저장소, 차례 규칙 | `afa75bc` |
| 문서 | 설정 색인, `docs/maintenance/7-foryou.md`, `# 사이클 23` 기록 | `03aa879` |
| 코드 검토 반영 | java-reviewer·react-reviewer 지적 고침 | `ce50c3d` |

- 빌드: 백엔드 `./gradlew build` 1,223개 중 실패 2개(클라우드 환경에서 원래 실패하는 `LaneThrottleTests` 시간 시험, 로컬에서는 통과해야 함 — 로컬에서 실패하면 원인을 봄). 웹 `npm run build`·vitest 662/662
- 인기 사이트 `library_id`: 교보 일반 79(서초구립반포)·99(연천군)·60(도봉문화정보)·73(부천시립), YES24 V1 263(국립세종도서관(YES24))·229(성남시도서관사업소) → `application.yml` 기본값

## 3. 다음에 할 일 (차례)

### 3-1. 사용자에게 받을 답 (기록만)

- 24 plan 세션에 배치 결정(#7) 전달 → 받은 답과 날짜를 `# 사이클 23` "24와의 일"의 "알린 날·받은 답" 줄에 적음. 전달 글은 클라우드 세션이 사용자에게 줌(같은 내용이 그 절에 있음)
- 설계와 다른 점 넷은 2026-10-24에 사용자가 정함(`# 사이클 23` "사용자 답 (설계와 다른 점 확인)"): S14 연도 줄·AI 요약은 24 뒤에 붙임, 빼기는 사이트마다, 점수 > 0만 인기, 03:30. 다시 묻지 않음

### 3-2. 체크포인트 B·C (로컬 PC에서만 됨)

- 절차: `docs/maintenance/7-foryou.md` 1절 "체크포인트 B·C를 내 PC에서 돌리기"
- 준비: 로컬 `db`가 운영 꼴 사본인지, `../.env`에 나가는 길(`CHAEKCHECK_AVAILABILITY_EGRESS_MODE`·`…PROXIES`·`…PROXYUSERNAME`·`…PROXYPASSWORD`, PC는 Webshare 아이디·비밀번호 쌍), `CHAEKCHECK_FORYOU_BUILD_ENABLED=true`(기본값). **돌고 있는 책 수집이 쓰는 서버에는 보내지 않음**: 수집 중이면 그 사이트를 `CHAEKCHECK_FORYOU_SOURCES_*_LIBRARYIDS`에서 잠시 뺌. `*.dkyobobook.co.kr`에는 어떤 요청도 보내지 않음(코드가 막음)
- `backend/`에서 `./gradlew bootRun` → 켤 때 한 번 만듦(오늘 succeeded가 있으면 `DELETE FROM foryou.build_run WHERE built_on = current_date` 뒤 다시 켬)
- B 보고: `foryou.build_run.note`의 사이트마다 `rows`·`matched`·`kept/left_out`, 로그 `ranked_list_failed`, 걸린 시간·요청 수(사이트당 2쪽 = 12건). 교보 80줄 한 쪽이 본문 상한 1MB를 넘는지(넘으면 `chaekcheck.availability.ranked.kyobo-page-size`를 낮추고 `# 사이클 23`에 적음)
- C: 로그 `foryou base position=… total=… demand=… recent=… boost=… sales=… title="…"` 40줄로 표(책, 출판 연도, 점수, 재료별 점수, ①인지)를 만들어 **사용자에게 보이고 가중치를 정함**(R2-3a). 24 전에는 추천목록 책이 없어 인기 출처만의 모양. 정한 값은 `chaekcheck.foryou.score.*`와 `# 사이클 23`에
- 같은 때 `to-dev-popular-list-check.md` 1절 다섯 가지를 한 번 봄

### 3-3. 24가 main에 들어간 뒤 (이 브랜치를 main 위로 rebase)

- `foryou/internal/listed/ListedSource`를 `picks.ListedBooks.current()` 어댑터로 바꿈(`ForYouConfiguration.listedSource()`의 TODO). `ListedBook`에 `tags`(nullable, `BookTagTransfer.forCard`) 칸
- S14를 이 탭에서 열 때 연도 줄·AI 요약을 지금 규칙대로(사용자 확인 뒤, 위 #11)
- `collection`: 새 모드 `neighbours`(TSV 머리줄 `# export_id=…`, 운영 `catalog_import.export_id`와 다르면 멈춤·`--force`, 줄 규칙은 `ForYouTransfer.Neighbours`), `tag-dictionary`, 24의 `book-texts`에 kind `for_you_blurb`·`tags`·`cover_file`(모르는 kind는 건너뛰고 수 보고). `collection/package-info.java`에 `foryou`, `ModularityTests.collectionUsesLibraryAndLibraryNotCollection`에 `foryou`
- `usagelog/00N-picks-list-for-you.yaml`(24의 `usagelog-ai-search-event` 다음), `UsageEventReader.PICKS_LISTS`에 `for_you`, `UsageLogSchemaTests`
- master changelog 끝 차례 정리, 파일 번호 확정 → `# 사이클 23`에 적음
- `picks/package-info.java` 공개 타입 설명(24 기록: 23과 합칠 때 함께 고침)

### 3-4. 그 뒤

- 10단계(수집 PC): KURE-v1 이웃 전체 계산, Haiku 배치(24와 합친 한 스크립트), 꼬리표 사전 TSV(사람이 확정), 표지 옮기기 — 인계서 10단계와 `docs/maintenance/7-foryou.md` 2절의 파일 모양
- 체크포인트 D: 사용자 화면 확인, **사용자 폰(Samsung Internet)으로 `web-lan-5174`** 확인
- 11단계: 운영 꼴 사본에서 연습 → 사용자 승인 → 운영 반영 한 번

## 4. 반영 전 확인할 것

- 운영 넣기와 서비스가 같은 DB 역할인지: 저장소 설정으로는 둘 다 `chaekcheck`(`deploy/prod/compose.yaml`, `3-import.md` 14절). EC2에서 확인: `docker inspect chaekcheck-backend-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep DB_USERNAME`, `docker exec -i chaekcheck-db-1 psql -U chaekcheck -d chaekcheck -c "\du"`, 첫 `neighbours full` 뒤 `SELECT tableowner FROM pg_tables WHERE schemaname='foryou' AND tablename='book_neighbour'`가 `chaekcheck`
- 운영 디스크 여유(이웃 약 0.22GB, 바꿔 끼울 때 잠깐 두 벌, 표지 약 0.3GB) — 마지막 기록은 2026-10-20 여유 12GB
- 교보문고 약관 제11조⑨(사용자 판단, 공개 반영 전)
- 출판유통·「화제의 책 200선」·KLWAVE는 사용자 PC 원문 확인 전까지 `enabled=false`(읽는 코드 없음)

## 5. 클라우드 세션에서만 쓴 우회 (로컬에서는 필요 없음)

- 시험 DB: `docker/postgres-bigm` 이미지를 못 만들어 공식 `postgres:18` + upstream 소스로 빌드한 pg_bigm을 씀. 로컬은 원래대로 `wsl -d Ubuntu -- docker compose up -d db-test`
- Gradle: Maven Central 429 때문에 `~/.gradle/init.d/`에 Google 사본을 둠(저장소 파일은 그대로)
- 화면 확인: Playwright MCP가 끊겨 미리 깔린 Playwright로 API를 가짜로 답해 1440·768·375만 봄. 로컬에서는 실제 서버로 다시 봄

---

## 시작 글 (사용자가 로컬 세션 첫 메시지로 붙여 넣음)

````
사이클 23 「나에게 맞는 책」을 이어서 개발합니다. 클라우드 세션에서 하던 일을 로컬 PC에서 이어 갑니다.

1. 브랜치: dev `claude/cycle-23-for-you-picks-kzjabw`를 받아 그 위에서 작업합니다(git fetch origin claude/cycle-23-for-you-picks-kzjabw 후 checkout). 다른 브랜치로 push하지 않습니다.
2. 먼저 읽을 것(이 차례로)
   - dev `docs/features/foryou/archive/2026-10-24-session-handoff.md` (이 인계, 지금 상태와 다음 할 일)
   - dev `docs/cycles/plan-feedback.md`의 `# 사이클 23` 절 (0단계 결과, 사용자 답, 설계와 다르게 한 것, AC 확인표)
   - plan `docs/cycles/23-for-you-picks/handoff.md` (인계서, plan main 18133c3 이후)와 같은 폴더 `design-delta.md`, `to-dev-popular-list-check.md`, `to-dev-intro-test-2026-10-24.md`
   - dev `docs/maintenance/7-foryou.md`
3. 지금 할 일: 인계 문서 3-2 체크포인트 B·C를 이 PC에서 돌립니다.
   - 운영 꼴 DB와 나가는 길(.env의 egress 설정)로 bootRun 해서 새벽 만들기를 한 번 돌리고, 사이트별 줄 수·맞춤 수·시간(B)을 `# 사이클 23`에 적습니다.
   - 만들기 로그의 `foryou base position=…` 40줄로 상위 40권 표(책, 출판 연도, 점수, 재료별 점수, ①인지)를 만들어 저에게 보여 주세요. 가중치를 그때 정합니다.
   - 돌고 있는 책 수집이 쓰는 서버에는 보내지 않습니다. *.dkyobobook.co.kr에는 어떤 요청도 보내지 않습니다.
4. 사이클 24가 main에 들어갔으면 이 브랜치를 main 위로 rebase하고 인계 문서 3-3(ListedBooks 연결, collection 모드, usagelog for_you)을 합니다. 아직이면 3-3은 기다립니다.
   - 3-3 때 이 탭에서 연 책 창(S14)에 연도 줄·AI 요약을 지금 규칙대로(추천목록 책에만) 붙입니다.
5. 이미 정한 것(다시 묻지 않음, 2026-10-24): 책 창 연도 줄·AI 요약은 24 뒤에 붙임 / 출처 빼기는 사이트마다 / 점수 > 0인 책만 인기 책 / 새벽 만들기 03:30. 시작점은 "24가 main에 들어갈 때까지 기다림", 출판 연도는 대표 출판일, 사용 알림 category는 all·base·similar.
6. 지킬 것
   - 운영 반영은 한 번, 제 승인 뒤입니다.
   - 설계와 다르게 해야 하면 조용히 바꾸지 말고 먼저 알려 주고 `# 사이클 23` 절에 이유를 적습니다.
   - 단계마다 backend `./gradlew build`, web `npm run build`·vitest를 통과시키고 커밋·push합니다.
````
