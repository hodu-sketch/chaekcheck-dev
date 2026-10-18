# dev 설정값 목록 (settings index)

- 서비스·조회 서버의 운영 숫자를 한 곳에서 찾는 목록입니다. 값마다 한 줄: 값 / 어디(파일·속성) / 환경 변수 / 함께 바꿀 곳 / 왜 / 정한 plan 문서·ADR / 바꾸면 다시 켤 것. 코드의 값 옆 주석은 이 문서를 가리킵니다 [확정] (plan 사이클 20 세 관점 검토 #17, plan `docs/settings-index.md` 머리말).
- 근거(계산·결정 원문)는 plan `docs/settings-index.md`(2·3·5·6·6-1·8절)에 있고, 여기에는 **지금 코드의 값과 바꾸는 방법**만 둡니다.
- 환경 변수 이름 규칙 [확정] (plan `docs/settings-index.md` 8절, Spring Boot "Binding From Environment Variables"): 속성 이름의 점 → `_`, `-`는 빼고, 대문자. 예 `chaekcheck.availability.max-concurrent-searches` → `CHAEKCHECK_AVAILABILITY_MAXCONCURRENTSEARCHES`. 운영은 `prod.env`(사이클 17)에 적고 서비스를 다시 켭니다. 아래 표의 환경 변수는 모두 이 규칙으로 만든 이름이라 `application.yml`에 `${…}` 자리가 없어도 덮어씁니다.
- 확인(2026-10-17, 사이클 20 1단계): `CHAEKCHECK_AVAILABILITY_MAXCONCURRENTSEARCHES=2 CHAEKCHECK_AVAILABILITY_OPSSUMMARY_INTERVAL=15s`로 켜니 `GET /api/v1/lookup-status`의 `slots`가 2, 요약 로그가 15초마다 — `application.yml`에 `${…}` 자리가 없어도 환경 변수가 이김(`docs/plan-feedback.md` `# 사이클 20`).
- 사이클 20부터 더한 값부터 채웠습니다. 그 앞 사이클의 값(캐시·마감·로그인·사용 기록 정리 시각 등)은 plan `docs/settings-index.md` 2·3·5·7·9절이 색인하며, 이 표에는 사이클 20이 건드린 것만 있습니다. 새 값을 더하거나 바꾸면 이 표에 한 줄을 더하거나 고칩니다.

## 1. 서비스 (`backend/src/main/resources/application.yml`)

| 값 | 지금 값 | 어디 (속성) | 환경 변수 | 함께 바꿀 곳 | 왜 | 정한 plan 문서 · ADR | 바꾸면 다시 켤 것 |
|---|---|---|---|---|---|---|---|
| 동시 검색 자리 | 40 | `chaekcheck.availability.max-concurrent-searches` | `CHAEKCHECK_AVAILABILITY_MAXCONCURRENTSEARCHES` | 상태 불의 노랑(절반)·빨강(전부)은 이 값에서 계산되므로 따로 없음. 해석 자리 8과 함께 힙 어림(약 340MB) | 20 → 40: 검색 자리가 모자라 503이 나는 것보다 힙이 남음. 배포 뒤 1분 요약 로그(힙·503)로 60까지 올릴지 정함 | 사이클 20 전반 점검 #13, plan `settings-index.md` 3절 | 서비스 |
| 바로 검색 결과 줄 상한 | 10 | `chaekcheck.availability.batch.row-limit` | `CHAEKCHECK_AVAILABILITY_BATCH_ROWLIMIT` | 바로 보기·책 화면(holding 묶음)에는 쓰지 않음 — 그쪽은 묶음 크기 `holding-batch-size`(아래) | 검색 한 번이 쓰는 도서관 요청을 반으로 | ADR-0132, spec R15 | 서비스 |
| 도서관 응답 본문 상한 (직접 호출) | 1MB (압축본도, 풀었을 때도) | `chaekcheck.availability.max-body-size` | `CHAEKCHECK_AVAILABILITY_MAXBODYSIZE` (운영 compose는 `${LOOKUP_MAX_BODY_MB}MB`로 넘김, 사이클 17) | 조회 서버 `fetch.max-body-size`, nginx `client_max_body_size`(2단계 올리기), 확장 `config.js`(2단계) — 모두 같은 1MB | 실제 가장 큰 응답 322KB(2026-10-05)의 약 3배. 넘으면 그 도서관만 실패하고 `body_too_large`로 기록 | 사이클 20 세 관점 검토 #7, 전반 점검 #6, ADR-0132 | 서비스 |
| 메모리 상태 저장소 | `memory` (이 값만 받음; 다른 값이면 켤 때 멈춤) | `chaekcheck.lookup.state-store` | `CHAEKCHECK_LOOKUP_STATESTORE` | 없음 (Redis 구현·컨테이너는 나중에) | 상태 불 메모·마지막 DB 오류 시각·`HostQueueBoard`·`SearchSlots` 거절 시각을 저장소 인터페이스 뒤에 두고 이 값으로 구현을 고름 | ADR-0133, plan `research/maintenance/memory-state-and-redis.md` | 서비스 |
| 책 기준 검색 기본 쪽 크기 | 10 (최대 20은 코드 상수 `BookParams.MAX_PAGE_SIZE` 그대로) | `chaekcheck.catalog.books.default-page-size` | `CHAEKCHECK_CATALOG_BOOKS_DEFAULTPAGESIZE` | 웹은 `pageSize`를 보내지 않음(검토 #30) | 첫 쪽 책 10권 → 대출 상태 조회가 10권 범위 | ADR-0132, spec R15, 세 관점 검토 #23·#30 | 서비스 |
| 해석 자리 수 | 8 | `chaekcheck.availability.parse-slots.max` | `CHAEKCHECK_AVAILABILITY_PARSESLOTS_MAX` | 동시 검색 자리 40(힙 어림), 2단계 확장 올리기도 같은 자리 | 1MB 쪽 해석 실측 7.4MB → 상한 없이 동시 해석하면 힙 512MB를 넘을 수 있음 | 사이클 20 전반 점검 #4, plan `settings-index.md` 6-1절 | 서비스 |
| 해석 자리 기다림 | 2s | `chaekcheck.availability.parse-slots.max-wait` | `CHAEKCHECK_AVAILABILITY_PARSESLOTS_MAXWAIT` | — | 본문이 다 온 뒤 자리를 최대 2초 기다리고, 못 얻으면 그 도서관만 실패(`parse_busy`) | 전반 점검 #4 | 서비스 |
| 상태 응답 메모 | 1s | `chaekcheck.availability.lookup-status.memo` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_MEMO` | 웹의 상태 묻는 간격 10초(`VITE_LOOKUP_STATUS_POLL_SECONDS`) | 같은 1초 안의 요청은 계산 한 번의 값을 받음 | spec 입력·출력 표, ADR-0118 | 서비스 |
| 503 뒤 빨강 | 10s | `chaekcheck.availability.lookup-status.red-after-refusal` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_REDAFTERREFUSAL` | — | 최근 10초 안에 `server_busy`를 한 번이라도 냈으면 빨강 | spec R1 | 서비스 |
| DB 오류면 회색 | 30s | `chaekcheck.availability.lookup-status.gray-after-db-error` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_GRAYAFTERDBERROR` | — | 최근 30초 안에 DB 오류(`DataAccessException`·`CannotCreateTransactionException`)가 있었으면 `GRAY`·`DB_ERROR`, 다른 모든 판정보다 앞섬 | 전반 점검 #9, 설계 검토 #4 | 서비스 |
| 줄 선 도서관: 기다림을 세는 시간 | 10s | `chaekcheck.availability.lookup-status.queue-wait-window` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_QUEUEWAITWINDOW` | 묶음 마감 `batch.deadline` 8s (10초 = 8초 + 2초; 마감을 바꾸면 함께) | 시작한 지 10초 지난 기다림은 세지 않아 짝이 어긋나도 「밀려요」가 저절로 사라짐 | 전반 점검 #3, spec R4 | 서비스 |
| 줄 선 도서관: 시간 초과 남기기 | 60s | `chaekcheck.availability.lookup-status.queue-timeout-window` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_QUEUETIMEOUTWINDOW` | — | 자리 대기 시간 초과 뒤 60초 동안 그 호스트의 도서관을 줄 섬으로 봄 | spec R4 | 서비스 |
| 줄 선 도서관 목록 길이 | 200 | `chaekcheck.availability.lookup-status.max-queued-libraries` | `CHAEKCHECK_AVAILABILITY_LOOKUPSTATUS_MAXQUEUEDLIBRARIES` | — | 상태 응답 `queuedLibraryIds` 최대 [안] | design-delta API 표 | 서비스 |
| 운영 요약 로그 간격 | 1m | `chaekcheck.availability.ops-summary.interval` | `CHAEKCHECK_AVAILABILITY_OPSSUMMARY_INTERVAL` | 사이클 17 CloudWatch "요약 줄 5분 없음" 경보, 503 급증 경보(`refused`) | 1분마다 INFO 한 줄 `lookup ops: slotsMax= slotsInUse= slotsMaxInUse= refused= libraryRequests= bodyTooLarge= dbErrors= parseMax= parseInUse= parseMaxInUse= queuedLibraries= pausedLanes= heapUsedMb= heapMaxMb=` (`docs/plan-feedback.md` `# 사이클 20`) | 전반 점검 #5, ADR-0132 | 서비스 |
| 나가는 길 설정 (사이클 webshare) | 코드 기본 `all`(로컬); **운영 `prod.env`는 `proxy-only`** [확정] (사용자 "프록시만 사용하도록 하고" 2026-10-18, plan f0b6d86 — EC2 자기 IP로는 요청·다시 시험 0건, 비표준 포트 사이트는 운영에서 늘 「확인하지 못한 도서관」) (`all` / `proxy-only` / `direct-only`) | `chaekcheck.availability.egress.mode` | `CHAEKCHECK_AVAILABILITY_EGRESS_MODE` (`../.env`도 읽음) | 조회 서버 `fetch.egress.mode` (운영 compose는 같은 값을 둘에 넘김, 사이클 17) | `proxy-only`면 직접 길로 요청 0건(다시 시험 포함), 프록시가 모두 멈춰도 직접으로 안 넘어감. `proxy-only` + 빈 프록시 목록은 켤 때 멈춤 | ADR-0130, 사이클 20 검토 #24, `from-cycle-20-proxy-only.md`, 인계 1-15 #1 | 서비스 |
| 프록시 길 목록 (사이클 webshare) | 빈 값 (로컬) / 운영 Webshare 한국 정적 20개 | `chaekcheck.availability.egress.proxies` (쉼표로 이은 `주소:포트`, 순서대로 `proxy-01`…) | `CHAEKCHECK_AVAILABILITY_EGRESS_PROXIES` (`../.env` 또는 환경, 주소는 비밀 값) | 조회 서버 `fetch.egress.proxies` (같은 목록) | 길마다 HTTP 클라이언트 하나: 직접 길은 JDK 클라이언트(밑줄 호스트만 Apache), 프록시 길은 **모든 호스트를 Apache HttpClient 5**(`setProxy`, https는 CONNECT 터널)로 보냄(2026-10-18, 아래 아이디·비밀번호 줄). 길 번호에 주소를 넣지 않고 로그에도 안 씀 | ADR-0130, 인계 1-15 #1·#2 | 서비스 |
| 프록시 업체 이름 (사이클 webshare) | `webshare` | `chaekcheck.availability.egress.proxy-vendor` | `CHAEKCHECK_AVAILABILITY_EGRESS_PROXYVENDOR` | 조회 서버 `fetch.egress.proxy-vendor` | 로그 `lane=proxy-03 vendor=webshare`의 업체 칸 | 인계 1-15 #1 | 서비스 |
| 프록시 아이디·비밀번호 (사이클 webshare) | 빈 값 (= 업체가 서버 IP를 허용, 운영 IP Authorization = EC2 IP) | `chaekcheck.availability.egress.proxy-username` / `proxy-password` (모든 프록시 길에 한 쌍) | `CHAEKCHECK_AVAILABILITY_EGRESS_PROXYUSERNAME` / `CHAEKCHECK_AVAILABILITY_EGRESS_PROXYPASSWORD` (`../.env` 또는 환경만, 로그에 안 씀, 커밋 금지) | 조회 서버 `fetch.egress.proxy-username` / `proxy-password`. 둘 다 있으면 프록시 길의 Apache 클라이언트가 요청마다 Basic `Proxy-Authorization`을 **먼저** 보냄(선제 인증, 첫 CONNECT부터; Webshare는 인증 없는 CONNECT에 407만 주고 `Proxy-Authenticate` challenge를 보내지 않아 challenge를 기다리는 JDK 클라이언트는 터널을 못 엶 — 2026-10-18 확인). JVM 옵션 `jdk.http.auth.tunneling.disabledSchemes`는 이제 필요 없음(지움). 빈 값이면 아무 인증도 안 보냄(운영) | Webshare 플랜이 허용 IP 1개(EC2)라 로컬 PC는 대시보드 Proxy Settings → General의 Proxy Username / Password로 붙음 | 사용자 결정 2026-10-17 ("운영 IP Authorization, 로컬 Proxy Username/Password", plan `multi-source-ip.md` 11절) | 서비스 |
| 한 길·한 사이트 동시 / 간격 (뜻 바뀜, 사이클 webshare) | 1 / 1s | `chaekcheck.availability.host-max-concurrency` / `host-min-interval` | `CHAEKCHECK_AVAILABILITY_HOSTMAXCONCURRENCY` / `CHAEKCHECK_AVAILABILITY_HOSTMININTERVAL` | — | 사이클 webshare부터 **길마다** 세는 값(전에는 직접 길 하나라 호스트마다). 길 21개면 한 사이트에 최대 21건/초 | ADR-0094·0130, plan `settings-index.md` 3절, 인계 1-15 #3 | 서비스 |
| 교보 구독형 서버 길마다 동시 (사이클 webshare) | 3 (1초 간격 없음; `*.dkyobobook.co.kr` 호스트 전부를 길마다 한 묶음으로 셈) | `chaekcheck.availability.kyobo.subscription-max-concurrency` / `subscription-host-suffix` | `CHAEKCHECK_AVAILABILITY_KYOBO_SUBSCRIPTIONMAXCONCURRENCY` / `CHAEKCHECK_AVAILABILITY_KYOBO_SUBSCRIPTIONHOSTSUFFIX` | — | 평소 응답 4~6초 서버라 1초 간격이면 8초 안에 1곳. 그 길에서 연달아 3번 실패하거나 응답이 10초 이상이면 동시 1건으로 내리고, 10초 안 정상 응답이면 되돌림(`HostRules`, 숫자 [안]) | ADR-0094, 인계 1-15 #3 | 서비스 |
| 막힌 길: 연속 실패 수 (사이클 webshare) | 5 | `chaekcheck.availability.egress.block.failures-to-block` | `CHAEKCHECK_AVAILABILITY_EGRESS_BLOCK_FAILURESTOBLOCK` | — | 그 길·그 사이트에서 막힘 신호(응답 없음: 연결 거절·연결/읽기/전체 시간 초과·연결 끊김, 또는 HTTP 403·407·429·503)가 5번 연달아 오면 그 길만 그 사이트에서 멈춤. 다른 응답이 오면 0부터 다시. 1MB 넘음·해석 실패(`parse_busy`)·시간 초과 한 번은 막힘 아님 [안: dev, ADR-0094] | 인계 1-15 #4, `from-cycle-20-blocked-ip-resume.md` | 서비스 |
| 막힌 길: 첫 쉼 / 상한 (사이클 webshare) | 10m / 7d | `chaekcheck.availability.egress.block.first-pause` / `max-pause` | `CHAEKCHECK_AVAILABILITY_EGRESS_BLOCK_FIRSTPAUSE` / `CHAEKCHECK_AVAILABILITY_EGRESS_BLOCK_MAXPAUSE` | — | 10분 뒤 시험 요청 1건(그 길의 1건·1초 자리 안에서), 정상(2xx)이면 풂, 또 막혔으면 쉼 두 배(10 → 20 → 40 …), 상한 7일 | 사용자 승인 2026-10-15 ("상한은 일주일로 가자") | 서비스 |
| 막힌 길: 시험 확인 간격 / 시험 마감 (사이클 webshare) | 30s / 20s | `chaekcheck.availability.egress.block.retest-check-interval` / `retest-timeout` | `CHAEKCHECK_AVAILABILITY_EGRESS_BLOCK_RETESTCHECKINTERVAL` / `CHAEKCHECK_AVAILABILITY_EGRESS_BLOCK_RETESTTIMEOUT` | — | 다음 시험 시각이 지난 (길, 사이트) 줄을 30초마다 찾아 시험 1건을 보냄; 시험 한 건의 마감(자리 기다림 포함) 20초 [안] | 인계 1-15 #4 | 서비스 |

## 2. 조회 서버 (`fetch-service/src/main/resources/application.yml`)

| 값 | 지금 값 | 어디 (속성) | 환경 변수 | 함께 바꿀 곳 | 왜 | 정한 plan 문서 · ADR | 바꾸면 다시 켤 것 |
|---|---|---|---|---|---|---|---|
| 도서관 응답 본문 상한 | 1MB (압축본도, 풀었을 때도) | `fetch.max-body-size` | `FETCH_MAXBODYSIZE` (운영 compose는 `${LOOKUP_MAX_BODY_MB}MB`) | 서비스 `chaekcheck.availability.max-body-size` | 서비스와 같은 상한. 넘으면 `outcome=BODY_TOO_LARGE`로 돌려주고 서비스가 `body_too_large`로 기록 | 사이클 20 세 관점 검토 #7, 전반 점검 #6 | 조회 서버 |
| 나가는 길 (사이클 webshare) | 서비스와 같은 값: `all` / 빈 목록 / `webshare` / 빈 아이디·비밀번호 | `fetch.egress.mode` / `proxies` / `proxy-vendor` / `proxy-username` / `proxy-password` | `FETCH_EGRESS_MODE` / `FETCH_EGRESS_PROXIES` / `FETCH_EGRESS_PROXYVENDOR` / `FETCH_EGRESS_PROXYUSERNAME` / `FETCH_EGRESS_PROXYPASSWORD` | 서비스 `chaekcheck.availability.egress.*` (운영 compose가 한 값을 둘에 넘김, 사이클 17 [안: `LOOKUP_EGRESS_*`]) | 길 고르기·간격·막힌 길은 본 서비스가 하고 요청에 길 번호(`lane`)를 실음; 조회 서버는 번호 → 프록시만 찾아 그 길의 클라이언트(직접 길 JDK + 밑줄 호스트 Apache, 프록시 길은 모든 호스트 Apache, 아이디·비밀번호는 선제 인증)로 부름. 프록시가 CONNECT를 거절하면(407) `RESPONDED` + 그 상태로 돌려줌(본 서비스가 막힘 신호로 셈). `lane` 없으면 `direct`, 모르는 번호나 설정에서 뺀 길이면 `outcome=ERROR`. 응답에 `lane`을 돌려줌 | ADR-0130, 인계 1-15 #5 | 조회 서버 |

## 3. 웹 · 확장

- 웹 값은 빌드 때 `VITE_*`, 확장 값은 `extension/config.js` 한 파일(2단계) — 둘 다 다시 빌드·다시 깔아야 바뀝니다. 웹은 `web/.env.local`에 적습니다(`.env.example` 없음).

| 값 | 지금 값 | 어디 | 함께 바꿀 곳 | 왜 | 정한 plan 문서 · ADR | 바꾸면 다시 할 것 |
|---|---|---|---|---|---|---|
| 상태 묻는 간격 | 10초 | `VITE_LOOKUP_STATUS_POLL_SECONDS` (`web/src/api/lookupStatus.ts`) | 서버 상태 메모 1초(1절) | 탭이 보일 때만 묻고, 마지막 성공 뒤 30초(`LOOKUP_STATUS_STALE_MS`, 코드 상수)가 지나면 회색 | spec R1·R5, ADR-0118 | 웹 빌드 |
| 바로 보기 묶음 크기 (사이클 17, 자동 이어 부르기를 대신함) | 10 | `chaekcheck.availability.batch.holding-batch-size` | 묶음 하나가 묻는 소장 줄 = 우리 DB 순서(책 순서, 그다음 도서관 순서)로 다음 10개. 필터와 상관없이 셈. 화면은 검색 한 번에 1묶음, 「더 보기」마다 1묶음(자동 이어 부르기 없음, `VITE_HOLDING_AUTO_BATCHES` 없앰) | 검색 한 번에 여는 상세 페이지를 10개 안팎으로(전에는 보이는 책의 도서관 전부, 3묶음 자동 → 책 한 권이 105곳이면 40곳 넘게) | 사용자 결정 2026-10-18("결과를 10개 정도 타겟", "db결과대로 이어서"), 사이클 20 R15-1을 바꿈 | 서비스 |

## 4. 코드 상수 (설정이 아닌 것)

| 값 | 지금 값 | 어디 | 왜 설정이 아닌가 |
|---|---|---|---|
| 책 기준 검색 최대 쪽 크기 | 20 | `catalog/internal/web/BookParams.MAX_PAGE_SIZE` | API 계약(21이면 400). 기본값만 설정으로 뺌 |
| 검색 자리 안전 반납 여유 | 10s | `availability/internal/search/BatchRunner.SLOT_SAFETY_MARGIN` | 조정값이 아니라 자리가 새지 않게 하는 안전망 |
| 줄 선 도서관 보드의 길 열쇠 | `any` ("아무 길이나 기다림") | `availability/internal/fetch/HostQueueBoard.DEFAULT_LANE` | 기다리는 동안은 길이 아직 정해지지 않음(사이클 webshare `EgressLanes`가 자리를 얻는 순간 길을 고름); 모든 길이 막힌 사이트는 기다리지 않고 바로 실패라 줄에 안 섬 (인계 1-15 #3) |
| 교보 느려짐 기준 | 연속 실패 3번 / 응답 10초 | `availability/internal/egress/HostRules.KYOBO_SLOW_AFTER_FAILURES` / `KYOBO_SLOW_RESPONSE` | 그 길의 교보 묶음을 동시 1건으로 내리는 기준 [안: dev]. 설정으로 뺄 만큼 확인된 값이 아님(ADR-0094 "기준 숫자는 dev") |
| 요약 로그 `pausedLaneList` 최대 | 20쌍 | `availability/internal/ops/LookupOpsSummaryLog.PAUSED_LIST_MAX` | 로그 한 줄 길이 제한; 쌍마다의 변화는 `lane_paused`·`lane_resumed` 줄이 모두 남김 |
