# dev 설정값 목록 (settings index)

- 서비스·조회 서버의 운영 숫자를 한 곳에서 찾는 목록입니다. 값마다 한 줄: 값 / 어디(파일·속성) / 환경 변수 / 함께 바꿀 곳 / 왜 / 정한 plan 문서·ADR / 바꾸면 다시 켤 것. 코드의 값 옆 주석은 이 문서를 가리킵니다 [확정] (plan 사이클 20 세 관점 검토 #17, plan `docs/settings-index.md` 머리말).
- 근거(계산·결정 원문)는 plan `docs/settings-index.md`(2·3·5·6·6-1·8절)에 있고, 여기에는 **지금 코드의 값과 바꾸는 방법**만 둡니다.
- 환경 변수 이름 규칙 [확정] (plan `docs/settings-index.md` 8절, Spring Boot "Binding From Environment Variables"): 속성 이름의 점 → `_`, `-`는 빼고, 대문자. 예 `chaekcheck.availability.max-concurrent-searches` → `CHAEKCHECK_AVAILABILITY_MAXCONCURRENTSEARCHES`. 운영은 `prod.env`(사이클 17)에 적고 서비스를 다시 켭니다. 아래 표의 환경 변수는 모두 이 규칙으로 만든 이름이라 `application.yml`에 `${…}` 자리가 없어도 덮어씁니다.
- 확인(2026-10-17, 사이클 20 1단계): `CHAEKCHECK_AVAILABILITY_MAXCONCURRENTSEARCHES=2`로 켜면 `GET /api/v1/lookup-status`의 `slots`가 2 — 결과는 `docs/plan-feedback.md` `# 사이클 20`.
- 사이클 20부터 더한 값부터 채웠습니다. 그 앞 사이클의 값(캐시·마감·로그인·사용 기록 정리 시각 등)은 plan `docs/settings-index.md` 2·3·5·7·9절이 색인하며, 이 표에는 사이클 20이 건드린 것만 있습니다. 새 값을 더하거나 바꾸면 이 표에 한 줄을 더하거나 고칩니다.

## 1. 서비스 (`backend/src/main/resources/application.yml`)

| 값 | 지금 값 | 어디 (속성) | 환경 변수 | 함께 바꿀 곳 | 왜 | 정한 plan 문서 · ADR | 바꾸면 다시 켤 것 |
|---|---|---|---|---|---|---|---|
| 동시 검색 자리 | 40 | `chaekcheck.availability.max-concurrent-searches` | `CHAEKCHECK_AVAILABILITY_MAXCONCURRENTSEARCHES` | 상태 불의 노랑(절반)·빨강(전부)은 이 값에서 계산되므로 따로 없음. 해석 자리 8과 함께 힙 어림(약 340MB) | 20 → 40: 검색 자리가 모자라 503이 나는 것보다 힙이 남음. 배포 뒤 1분 요약 로그(힙·503)로 60까지 올릴지 정함 | 사이클 20 전반 점검 #13, plan `settings-index.md` 3절 | 서비스 |
| 바로 검색 결과 줄 상한 | 10 | `chaekcheck.availability.batch.row-limit` | `CHAEKCHECK_AVAILABILITY_BATCH_ROWLIMIT` | 책 화면 대출 상태(holding 묶음)에는 쓰지 않음(상한 없음, R15-1) | 검색 한 번이 쓰는 도서관 요청을 반으로 | ADR-0132, spec R15 | 서비스 |
| 도서관 응답 본문 상한 (직접 호출) | 1MB (압축본도, 풀었을 때도) | `chaekcheck.availability.max-body-size` | `CHAEKCHECK_AVAILABILITY_MAXBODYSIZE` (운영 compose는 `${LOOKUP_MAX_BODY_MB}MB`로 넘김, 사이클 17) | 조회 서버 `fetch.max-body-size`, nginx `client_max_body_size`(2단계 올리기), 확장 `config.js`(2단계) — 모두 같은 1MB | 실제 가장 큰 응답 322KB(2026-10-05)의 약 3배. 넘으면 그 도서관만 실패하고 `body_too_large`로 기록 | 사이클 20 세 관점 검토 #7, 전반 점검 #6, ADR-0132 | 서비스 |
| 메모리 상태 저장소 | `memory` (이 값만 받음; 다른 값이면 켤 때 멈춤) | `chaekcheck.lookup.state-store` | `CHAEKCHECK_LOOKUP_STATESTORE` | 없음 (Redis 구현·컨테이너는 나중에) | 상태 불 메모·마지막 DB 오류 시각·`HostQueueBoard`·`SearchSlots` 거절 시각을 저장소 인터페이스 뒤에 두고 이 값으로 구현을 고름 | ADR-0133, plan `research/maintenance/memory-state-and-redis.md` | 서비스 |
| 책 기준 검색 기본 쪽 크기 | 10 (최대 20은 코드 상수 `BookParams.MAX_PAGE_SIZE` 그대로) | `chaekcheck.catalog.books.default-page-size` | `CHAEKCHECK_CATALOG_BOOKS_DEFAULTPAGESIZE` | 웹은 `pageSize`를 보내지 않음(검토 #30) | 첫 쪽 책 10권 → 대출 상태 조회가 10권 범위 | ADR-0132, spec R15, 세 관점 검토 #23·#30 | 서비스 |
| 해석 자리 수 | 8 | `chaekcheck.availability.parse-slots.max` | `CHAEKCHECK_AVAILABILITY_PARSESLOTS_MAX` | 동시 검색 자리 40(힙 어림), 2단계 확장 올리기도 같은 자리 | 1MB 쪽 해석 실측 7.4MB → 상한 없이 동시 해석하면 힙 512MB를 넘을 수 있음 | 사이클 20 전반 점검 #4, plan `settings-index.md` 6-1절 | 서비스 |
| 해석 자리 기다림 | 2s | `chaekcheck.availability.parse-slots.wait` | `CHAEKCHECK_AVAILABILITY_PARSESLOTS_WAIT` | — | 본문이 다 온 뒤 자리를 최대 2초 기다리고, 못 얻으면 그 도서관만 실패(`parse_busy`) | 전반 점검 #4 | 서비스 |
| 상태 응답 메모 | 1s | `chaekcheck.availability.lookup-status.memo` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_MEMO` | 웹의 상태 묻는 간격 10초(`VITE_LOOKUP_STATUS_POLL_SECONDS`) | 같은 1초 안의 요청은 계산 한 번의 값을 받음 | spec 입력·출력 표, ADR-0118 | 서비스 |
| 503 뒤 빨강 | 10s | `chaekcheck.availability.lookup-status.red-after-refusal` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_REDAFTERREFUSAL` | — | 최근 10초 안에 `server_busy`를 한 번이라도 냈으면 빨강 | spec R1 | 서비스 |
| DB 오류면 회색 | 30s | `chaekcheck.availability.lookup-status.gray-after-db-error` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_GRAYAFTERDBERROR` | — | 최근 30초 안에 DB 오류(`DataAccessException`·`CannotCreateTransactionException`)가 있었으면 `GRAY`·`DB_ERROR`, 다른 모든 판정보다 앞섬 | 전반 점검 #9, 설계 검토 #4 | 서비스 |
| 줄 선 도서관: 기다림을 세는 시간 | 10s | `chaekcheck.availability.lookup-status.queue-wait-window` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_QUEUEWAITWINDOW` | 묶음 마감 `batch.deadline` 8s (10초 = 8초 + 2초; 마감을 바꾸면 함께) | 시작한 지 10초 지난 기다림은 세지 않아 짝이 어긋나도 「밀려요」가 저절로 사라짐 | 전반 점검 #3, spec R4 | 서비스 |
| 줄 선 도서관: 시간 초과 남기기 | 60s | `chaekcheck.availability.lookup-status.queue-timeout-window` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_QUEUETIMEOUTWINDOW` | — | 자리 대기 시간 초과 뒤 60초 동안 그 호스트의 도서관을 줄 섬으로 봄 | spec R4 | 서비스 |
| 줄 선 도서관 목록 길이 | 200 | `chaekcheck.availability.lookup-status.max-queued-libraries` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_MAXQUEUEDLIBRARIES` | — | 상태 응답 `queuedLibraryIds` 최대 [안] | design-delta API 표 | 서비스 |
| 운영 요약 로그 간격 | 1m | `chaekcheck.availability.ops-summary.interval` | `CHAEKCHECK_AVAILABILITY_OPSSUMMARY_INTERVAL` | 사이클 17 CloudWatch "요약 줄 5분 없음" 경보 | 1분마다 INFO 한 줄 `lookup ops …` (필드는 `docs/plan-feedback.md` `# 사이클 20` 1-11) | 전반 점검 #5, ADR-0132 | 서비스 |

## 2. 조회 서버 (`fetch-service/src/main/resources/application.yml`)

| 값 | 지금 값 | 어디 (속성) | 환경 변수 | 함께 바꿀 곳 | 왜 | 정한 plan 문서 · ADR | 바꾸면 다시 켤 것 |
|---|---|---|---|---|---|---|---|
| 도서관 응답 본문 상한 | 1MB (압축본도, 풀었을 때도) | `fetch.max-body-size` | `FETCH_MAXBODYSIZE` (운영 compose는 `${LOOKUP_MAX_BODY_MB}MB`) | 서비스 `chaekcheck.availability.max-body-size` | 서비스와 같은 상한. 넘으면 `outcome=BODY_TOO_LARGE`로 돌려주고 서비스가 `body_too_large`로 기록 | 사이클 20 세 관점 검토 #7, 전반 점검 #6 | 조회 서버 |

## 3. 웹 · 확장 (사이클 20 뒤 단계에서 채움)

- 웹 값은 빌드 때 `VITE_*`(예 `VITE_LOOKUP_STATUS_POLL_SECONDS`=10, 책 화면 자동 이어 부르기 3묶음), 확장 값은 `extension/config.js` 한 파일 — 둘 다 다시 빌드·다시 깔아야 바뀝니다. 1단계 웹 작업과 2단계 확장 작업이 이 절에 한 줄씩 더합니다.

## 4. 코드 상수 (설정이 아닌 것)

| 값 | 지금 값 | 어디 | 왜 설정이 아닌가 |
|---|---|---|---|
| 책 기준 검색 최대 쪽 크기 | 20 | `catalog/internal/web/BookParams.MAX_PAGE_SIZE` | API 계약(21이면 400). 기본값만 설정으로 뺌 |
| 검색 자리 안전 반납 여유 | 10s | `availability/internal/search/BatchRunner.SLOT_SAFETY_MARGIN` | 조정값이 아니라 자리가 새지 않게 하는 안전망 |
| 나가는 길 번호 (사이클 17 전) | `direct` 하나 | `availability/internal/fetch/HostQueueBoard.DEFAULT_LANE` | 사이클 17/22의 길(lane)이 들어오면 그 코드가 길마다 넘김 |
