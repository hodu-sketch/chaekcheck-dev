# 사이클 29 AI 책 찾기 첫 글 지연 고치기

plan에 넘기는 dev 기록입니다. 기준 문서는 plan `docs/cycles/29-ai-first-text-latency/handoff.md`(plan main `04da883`), ADR-0174, 같은 폴더 `measurements.md`.

## 시작 상태 (2026-11-09)

- worktree: `../chaekcheck-dev-c29`, 브랜치 `claude/cycle-29-ai-first-text-latency`
- 시작 커밋: `8719c6b8` (main 끝, "Release v1.23.2")
- worktree에 메인 체크아웃의 `.env`를 복사(git 무시)

## 한 것 (할 일 1·2)

### 1. 책 목록: 켜질 때 읽기 + 다시 읽기 요청

- `aisearch.internal.match.SearchBooks`: `CHECK_EVERY`와 요청 길의 `fresh()`를 지움. 질문은 메모리의 목록만 읽음. `reload()`가 뒤에서 읽어 한 번에 바꿔 끼우고, 실패하면 옛 목록을 두고 경고 `ai search books not read, the books in use stay: …`. 첫 읽기 전에 질문이 오면 그 질문이 한 번 읽음(실패하면 빈 목록으로 답하고 다음 질문에서 다시)
- 로그: `ai search books read: N in M ms` [안 그대로]
- `aisearch.internal.web.SearchBooksReload`: 켜질 때(`ApplicationReadyEvent`, AI 검색이 켜져 있을 때만) 한 번 읽고, `POST /internal/aisearch/books/reload`가 오면 뒤에서 읽음. **응답 `202`**(뒤에서 읽기 시작)를 고름. 읽는 중에 요청이 또 오면 끝난 뒤 한 번 더 읽음(읽는 동안 바꿔 끼운 표를 놓치지 않게)
- `catalog.AiSearchIndex.version()`: **지움** [안 → 지우기로 정함]. 부르는 곳이 없어짐
- 막는 방법:
  - nginx: `/internal`을 넘기지 않음을 확인. `deploy/nginx/chaekcheck-locations.conf`는 `/api/`만 backend로 넘기고, 나머지는 `location /`의 `try_files $uri =404`(정적 파일). 인계서가 말한 `active-backend.conf`는 upstream만 정하는 파일이라 경로와 무관
  - backend 포트는 운영 compose에서 밖으로 열려 있지 않음(`backend-blue`·`backend-green`에 `ports:` 없음, Docker 네트워크 안에서만 닿음)
  - 더해서: 컨트롤러가 요청 주소가 루프백·사설(172.x 등)이 아니면 `404`
  - **인계서에 없던 것**: 로그인이 켜진 운영에서는 Spring Security CSRF가 쿠키·헤더 없는 `POST`를 403으로 막으므로 `auth.internal.security.SecurityConfiguration`의 CSRF 예외에 `/internal/**`를 더함(웹훅과 같은 방식). 인증 규칙(`/api/v1/me` 보호)은 그대로
- 블루·그린 모두에 보냄 [안 그대로]: 가져오기 명령에 새 설정 `chaekcheck.collection.import.reload-urls`(쉼표로 여럿)를 주고, 운영 명령은 `http://backend-blue:8080/...`, `http://backend-green:8080/...`(compose 네트워크 별칭). 떠 있지 않은 자리는 경고 한 줄 뒤 넘어감(5초 시간 제한 [안])

### 2. `book-texts` 넣기를 새 표 바꿔 끼우기로

- `JdbcBookTextsTransfer.replaceSearchBooks`: `CREATE TABLE catalog.book_search_next (LIKE catalog.book_search INCLUDING ALL EXCLUDING INDEXES)` → 넣기 → 지금 표의 색인을 `pg_index`에서 읽어 새 표에 같은 정의로 만듦(기본 키, `search_text` bigm, BM25 `book_search_bm25`; 뒤에 changeset이 더하는 색인도 따라옴) → 칸·색인 칸의 통계 설정(catalog/006의 `SET STATISTICS 0`)도 옮김 → `ANALYZE` → `SET LOCAL lock_timeout = '10s'` [안], 지금 표 잠금·DROP, 새 표와 색인 이름 바꾸기. `foryou.book_neighbour` 바꿔 끼우기와 같은 모양
- 트랜잭션: 넣기의 한 트랜잭션 안에서 함(PostgreSQL의 DDL은 트랜잭션 안에서 됨). 잠금은 커밋까지 가므로 `BookTextsImport`에서 표 바꿔 끼우기를 **트랜잭션의 맨 끝**으로 옮김(전에는 맨 앞, 뒤에 요약·태그·표지 쓰기)
- `AiSearchIndex.compact()`(`VACUUM FULL`): **지움**. 새 표는 `ANALYZE`만 [안 그대로]
- 넣기가 성공하면 다시 읽기 요청 [확정]. 카탈로그 가져오기도 `in-place`(dry-run 아님)·`apply`·`finish` 끝에 보냄
- **ADR-0170 체크리스트**: 칸·표 정의는 바뀌지 않음, Liquibase 변경 없음. 바꿔 끼우는 동안 옛 판·새 판 모두 같은 이름 `catalog.book_search`를 읽고, 잠금은 이름 바꾸기 한 번(최대 10초 기다림)뿐. 옛 판(v1.23.2)은 1분 확인으로 스스로 다시 읽음 → 허용 범위로 봄
- 문서: `docs/maintenance/6-aisearch.md` 3절 넣기 차례·운영 명령(`reload-urls`, 손으로 다시 읽히는 명령), 11절 표의 238·242행, 문제 해결 310행. `docs/features/aisearch/1-how-it-works.md` 3절·8절 표. `settings-index.md`에 `reload-urls`. CHANGELOG `다음 판`

## 시험

- 새 시험: `SearchBooksReadTests`(한 번 읽기, 다시 읽기, 실패하면 옛 목록), `SearchBooksReloadTests`(루프백·사설 주소만), `AiSearchIndexTests.theNewTableIsSwappedInWithTheIndexesOfTheOld`(두 번 바꿔 끼운 뒤 색인 이름·통계 설정 그대로, `_next` 표 없음)
- 시험 도우미 `TestSearchBooks`는 바꿔 끼우기 대신 줄을 직접 넣음: 바꿔 끼우기의 잠금은 커밋까지 가는데 `@Transactional` 시험은 커밋하지 않아, 흐름의 낱말 검색(다른 연결)이 끝없이 기다림(2026-11-09 전체 빌드가 35분 멈춰 원인 확인). 운영 넣기는 바로 커밋하므로 해당 없음
- `./gradlew build`(2026-11-09): 1,482개 중 실패 1(`AiSearchOffTests`). 원인은 worktree에 복사한 `.env`의 `CHAEKCHECK_AISEARCH_ENABLED=true`(시험은 꺼진 기본값을 기대). 시험이 `chaekcheck.aisearch.enabled=false`를 직접 정하게 고쳐 `.env`와 상관없이 통과

## 배포 (2026-11-09)

- v1.24.0(`21039861`), 백엔드만. 15:34 green 자리로 바꿈(준비 39초, 전 판 1.23.1 blue)
- 켜질 때 읽기: `ai search books read: 76759 in 8527 ms`(15:34:07, 켜진 뒤 뒤에서 읽음)
- 다시 읽기 요청: `chaekcheck-web-1` 컨테이너에서 `POST http://backend-green:8080/internal/aisearch/books/reload` → `202`, `ai search books read: 76759 in 2884 ms`(다른 컨테이너의 사설 주소·CSRF 예외가 운영에서 동작)
- 밖에서: `POST https://chaekcheck.com/internal/aisearch/books/reload` → `404`

## 아직 안 한 것

- 3. 배포 뒤 다시 재기: plan이 함
- 4. `pg_prewarm` 시험: 1\~3 배포와 다시 재기 뒤. 결과는 이 파일에 숫자로 더함
