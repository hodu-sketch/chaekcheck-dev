# 정기 유지보수: ① 도서관 수집 → ② 책 수집 → 가져오기

- 주기적으로 하는 유지보수 작업의 **절차 원본**입니다(plan ADR-0047, 2026-10-04). 원칙: 절차의 원본은 dev, 조사 기록·근거·결정은 plan [확정] (사용자, 2026-10-01).
- 옮겨 온 곳: plan `docs/research/maintenance/periodic-tasks.md` 0\~4절 (2026-10-04). 그 문서의 5절(웹러브 북스 기관을 서비스 도서관으로 옮기기, 지금 이름으로는 수집 목록에서 찾은 도서관을 서비스에 넣기)과 6절(확인 필요)은 plan에 남습니다.
- 수집은 두 갈래입니다 [확정] (사용자 승인, 2026-10-05, plan 사이클 07): **① 도서관 수집**(어느 전자도서관이 있나 → 서비스 DB에 넣기) / **② 책 수집**(도서관마다 어떤 책이 있나). 그 뒤 **가져오기**가 ②의 결과를 서비스 DB 책 표로 옮깁니다. 원칙과 결정은 plan `docs/research/collection/1-libraries.md`, `2-books.md`에 있고, 여기에는 절차만 둡니다(ADR-0047).
- 도서관 목록은 둘입니다 [확정] (사용자, 2026-10-04): **등록 도서관**(dev seed `library/001`\~`006`, 280줄) / **수집 목록에서 찾은 도서관**(수집 DB `library` 번호 100001\~, 웹러브 북스 등에서 찾음). 사이클 07부터 전체 수집이 끝난 곳은 서비스 DB에도 새 번호로 들어갑니다(2026-10-05 2,394곳 `library/008` seed, 사이클 13부터는 넣기 명령 `library-insert`). 자세히는 [1-libraries.md](1-libraries.md) "도서관 목록 두 가지".
- plan 저장소: [hodu-sketch/chaekcheck-plan](https://github.com/hodu-sketch/chaekcheck-plan), 내 PC에서는 이 저장소 옆 폴더 `..\chaekcheck-plan\` (아래 "plan `…`"은 그 저장소 기준 경로). 문서 색인은 plan `docs/reference-index.md`.
- 주기와 순서는 모두 [안]입니다. 정하는 곳은 주기 갱신 사이클입니다(별도 사이클 [확정], plan `docs/research/ebook-catalog/catalog-refresh.md` 머리말).

## 0. 한눈에

| 갈래 | 하는 일 | 주기 | 절차 (dev) | plan 짝 문서 | 실행 도구 |
|---|---|---|---|---|---|
| ① 도서관 수집 | 수집 목록에서 찾기 → 확인 → 수집 → **넣기**(넣기 명령 `library-insert`: 미리 보기 → 보고서 → 넣기, 같은 사이트·같은 호스트 거르기 포함) → 구독 규칙·마감 확인. 등록 도서관 확인(새 곳, 바뀐 주소, 꺼진 곳, 가입 자격, 책 번호 체계)도 여기 | [안] 월 1회 + 운영 로그 신호가 있을 때 | [1-libraries.md](1-libraries.md) | `docs/research/collection/1-libraries.md` | 찾기·확인 `scripts/library/discover/probe_*.py`, 넣기 `backend/`에서 `./gradlew bootRun` 프로필 `library-insert`(규칙 `scripts/library/rules/`), 등록 도서관 확인 `scripts/library/`(`CheckKyoboSites.java` …), 마감 확인 `scripts/library/probe_kyobo_limits.py` |
| ② 책 수집 | 도서관마다 전자책 목록 받기 | 주 1회 전체 + 매일 신간 [확정] (plan `crawl-method.md` 2절). **신간 수집 명령은 아직 없음** → 지금은 전체만 | [2-crawl.md](2-crawl.md), 플랫폼별 방법 [platforms.md](platforms.md) | `docs/research/collection/2-books.md` | `scripts\crawl\resume.cmd` (`scripts/crawl/crawl_catalog.py`). 2026-10-12부터 이것만 돌림(옛 코드는 `chaekcheck-data\_old-scripts-2026-10-12\`에 보관) |
| 가져오기 | ②의 결과(수집 DB 사본)를 서비스 DB 책 표로 (정리·같은 책 묶기·`book.library_ids`) | [안] ② 전체 수집이 끝날 때마다 | [3-import.md](3-import.md) | `docs/research/collection/2-books.md` 6절 | `backend/`에서 `./gradlew bootRun` 프로필 `collection-import` |
| 책 표지 올리기 | 판 표지·목록 그림을 PC가 S3 `covers/<sha1>.jpg`에 올리고(CloudFront로 보임), 다 올린 뒤 `book-texts`의 `cover_file`로 책과 이음 (사이클 26) | 새 표지를 받았을 때, 새 목록 줄 그림이 생겼을 때 | [8-covers.md](8-covers.md) | plan `docs/cycles/26-book-descriptions/` | PowerShell에서 `aws.exe s3 sync` (프로필 `chaekcheck-covers`) |
| AI 찾기 한 줄 넣기 | 추천도서 목록 책과 보유 도서관 많은 10만 권 판(운영 76,759권) 사본, PC에서 만든 찾기용 한 줄을 `catalog.book_search`에 (사이클 24, 사이클 26부터 이 표). 운영 넣기는 `--chaekcheck.collection.import.book-texts-top-books=100000`을 꼭 붙임 | 새 한 줄 파일이 나올 때, 목록 책이 바뀐 뒤 | [6-aisearch.md](6-aisearch.md) | plan `docs/cycles/24-ai-book-search/` | `backend/`에서 `./gradlew bootRun` 프로필 `collection-import`, `mode=book-texts` |
| (자동) 서비스 | 사용자가 책을 찾으면 서비스 DB에서 찾고, 도서관 상세 페이지로 대출 상태를 실시간 조회 | 늘 | 아래 4절 | — | 서비스(`catalog`, `availability` 모듈) |

① → ② → 가져오기 순서로 이어집니다. ①에서 도서관이 바뀌면 ②에서 그 도서관을 받고, 가져오기를 다시 해야 서비스 검색에 반영됩니다. 자동 단계는 사람이 돌리지 않지만, 거기서 나는 오류(상세 페이지를 못 읽음)가 ①을 할 신호가 됩니다.

## 1. ① 도서관 수집 (등록 도서관 확인·수집 목록에서 찾은 도서관)

절차: [1-libraries.md](1-libraries.md) → [`docs/libraries/library-maintenance.md`](../libraries/library-maintenance.md). 볼 곳과 순서 [안]:

1. 목록 출처 다시 보기: 교보 공식 목록, 공공데이터포털 전국도서관표준데이터, 통합 포털 목록, 웹러브 북스 (4-0\~4-5). 교보 공식 목록 재확인은 요청 약 400건·20분, plan `docs/cycles/02-library-expansion/data/kyobo-institutions.tsv`와 비교 (4-1, 4-9)
2. 주소 모양으로 화면 종류 나누기, 최종 주소 확정 (4-6, 4-7, 3절 도구)
3. 가입 자격·방문 가입 판정 (1-1, 1-2, 4-8, 4-10). 사람이 정한 값은 `scripts/library/join_overrides.tsv`가 마지막에 이김
4. 책 번호 체계 점검: `check_code_spaces.py` → 기준값과 비교. 크게 바뀌거나 새 화면 종류가 생기면 사용자에게 알림 [확정] (사용자, 2026-09-30) (4-11)
5. 날짜가 정해진 것 확인: 꺼 둔 도서관 id 59·131 재개 확인 2027-01 (6-2), 중간 인증서 만료 2027-05-21·2027-11-02 ([`docs/libraries/library-site-issues.md`](../libraries/library-site-issues.md) 3절)
6. 남은 확인 목록: `library-maintenance.md` 14절, plan `docs/reference-index.md` 6절 "도서관 목록·분류"
7. 수집 목록에서 도서관 찾기: 웹러브 북스 기관을 한 번씩 확인하고 수집 DB에 넣음 ([1-libraries.md](1-libraries.md) "수집 목록에서 도서관 찾기")
8. 서비스 DB에 넣기(넣기 명령 `library-insert`, seed로 넣지 않음, ADR-0084): [1-libraries.md](1-libraries.md) "새 도서관 넣기". 같은 사이트·같은 호스트 거르기도 그 안에서
9. 구독 규칙·마감 확인: 교보 구독형 사이트의 팝업·공지 제목과 날짜 ([1-libraries.md](1-libraries.md) "구독 규칙·마감 확인")

운영 중 신호(3xx, 읽기 실패, 시간 초과)별로 할 일은 `library-maintenance.md` 4-9절에 있습니다.

## 2. ② 책 수집 (도서관마다 전자책 목록 받기)

절차: [2-crawl.md](2-crawl.md).

0. 지난 전체 수집·표지 받기 기록(2026-10-23\~29, 끝남): [2-crawl.md](2-crawl.md) 0-0절. 새로 띄우기 전에 도는 수집이 있는지 거기 적힌 방법으로 확인합니다
1. `scripts\crawl\resume.cmd` 실행(2026-10-12부터 이것만, 2-crawl 0-1절). 끝난 도서관은 넘어가고, 받던 도서관은 마지막으로 저장한 쪽 다음부터 받음 (2-crawl 3절)
2. 줄 세우기: IP 하나에 한 줄, 한 줄에 동시 1건·1초 간격. 데이터 폴더(`chaekcheck-data`)의 `queue-by-ip.txt`가 있으면 IP 기준, 없으면 도메인 이름 기준 [확정] (사용자, 2026-10-04) (2-crawl 2절). 교보 구독형 서버(`*.dkyobobook.co.kr`)에는 요청을 보내지 않고, 그 서버용이던 `host-slots.txt`는 사이클 13부터 읽지 않음 (2-crawl 0-1·2-1절)
3. 상태 보기: `python -X utf8 scripts\crawl\crawl_catalog.py status`, 로그 데이터 폴더 `logs\crawl-*.log` (2-crawl 3·5절)
4. 실패한 도서관: 2-crawl 6절 "문제별 대처". 막힌 사이트는 우회하지 않고 `crawl_skip`으로 표시. 다시 시도 뒤에도 403·시간 초과면 수집기가 그 도서관만 표시함 (2-crawl 0-1·5-1절)
5. 끝난 뒤: 상세 주소 확인 `scripts\crawl\verify_detail_urls.py` (2-crawl 9절)

화면 종류별 목록 주소와 예외는 [platforms.md](platforms.md), 원칙은 plan `docs/research/ebook-catalog/crawl-method.md`에 있습니다. 진행 상태(어디까지 받았나)는 2026-10-12부터 [2-crawl.md](2-crawl.md) 1·8·11절에 있습니다(plan `crawl-runbook.md`에서 옮김).

## 3. 가져오기 (받은 목록을 서비스 DB로)

절차: [3-import.md](3-import.md). 원본은 배포용 사본을 권합니다(3-import 2절). 수집이 도는 중에 원본 파일을 읽어도 됩니다(근거는 3-import 1절).

1. 먼저 세기만(dry run) → 2. 요약 숫자를 지난번과 비교 → 3. 실제 가져오기 → 4. 검색 몇 가지로 확인

다시 가져오면 책 번호가 새로 매겨집니다(3-import 6절).

## 4. (자동) 서비스에서 책 찾기와 대출 조회

- 흐름: plan `docs/current/flows.md` 흐름 6(책 목록 검색과 바로 보기), 흐름 7(책 먼저 고르기 → 책 화면). 구현: `BookSearchController`, `BookAvailabilityStreamController`, `BookAvailabilityBatchStreamController` (사이클 04)
- 대출 상태는 저장하지 않고 사용자가 볼 때 도서관 상세 페이지를 읽음 [확정] (plan `crawl-method.md` 2절). 같은 호스트 1건·1초, 캐시 3분
- 유지보수에서 볼 것: 상세 페이지를 못 읽는 도서관이 늘면 화면이 바뀐 것 → 1단계(`library-maintenance.md` 4-9절, 12절 화면 종류별 상세 형식)

## 5. (자동) 사서추천도서 받기

- 국립중앙도서관 사서추천도서를 Open API로 받음(사이클 14, 사이클 10 인기 도서를 대체). 코드 기본은 켜짐이지만 **운영은 꺼져 있음**(`deploy/prod/compose.yaml` `CHAEKCHECK_PICKS_FETCH_ENABLED: "false"`, 목록은 DB에 있음). 켜면 서버가 켜질 때와 매일 05:00에 받을지 따져 매주 월요일 최근 12번 추천을 받음(표지는 받지 않음, v1.20.6\~)(실패하면 성공할 때까지 매일, 놓친 주는 따라잡기). 절차와 실패 읽는 법: [4-picks.md](4-picks.md)
- 유지보수에서 볼 것(운영에서 켠 경우만): 최근 7일 안에 `picks.fetch_run`에 `SUCCEEDED`가 있는지, `new_category_codes`. 권리자의 표지 내림 요청은 늘([4-picks.md](4-picks.md) 4절)

## 5-1. (자동) 「나에게 맞는 책」 새벽 만들기 (사이클 23)

- 매일 03:30과 켤 때 기본 목록 40권을 만듦(교보 일반 4곳·YES24 V1 2곳 인기순, 추천목록). 절차·볼 곳·넣기: [7-foryou.md](7-foryou.md)
- 유지보수에서 볼 것: `foryou.build_run`의 최근 `succeeded`, `note`의 사이트별 줄 수(이틀 연속 0이면 그 출처를 끄고 plan에 알림)

## 5-2. (자동) AI 책 찾기 (사이클 24)

- 사용자가 문장으로 물으면 질문 벡터(임베딩 서버) + 1차 ML + 2차 Haiku로 책을 고름. 지금 운영 설정과 모델 폴더: [6-aisearch.md](6-aisearch.md) 0절
- 유지보수에서 볼 것: 임베딩 서버 `GET http://<임베딩 서버 사설 IP>:8090/api/v1/health`가 `{"ready":true}`(운영 서버에서, 7-1절), 백엔드 시작 로그 `ai search vectors on (REMOTE, FUNNEL): … rows x 1024 (up to … a book)`·`ai search first call: ml, … book profiles`(7·8절), 검색 로그 `ai search: …`의 `vector N`(0이면 벡터 꺼짐·늦음·실패)과 `encoder failed`·`haiku makes the first step` 줄

## 6. (필요할 때) 공지·서비스 안내·약관 글

- 운영자 화면 없이 Claude가 운영 SQL로 넣음(사이클 16). 사용자 확인 뒤, 한 트랜잭션, 판은 고치지 않고 새로 넣음. 절차와 SQL: [5-sitecontent.md](5-sitecontent.md)

## 6-1. (몇 달에 한 번) 첫 화면 예시 지역 자료

- 첫 화면 예시 카드는 방문자 IP로 시·도만 보고 그 시·도의 예시를 고름(2026-10-20 사용자 결정, 모르면 서울). 자료는 DB-IP 무료 「IP to City Lite」(CC BY 4.0, 소개 화면에 출처 표기)를 한국 줄만 줄인 `backend/src/main/resources/geo/kr-sido.csv.gz`. 새로 할 때: 그달 CSV(`dbip-city-lite-YYYY-MM.csv.gz`, https://db-ip.com/db/download/ip-to-city-lite)를 `data/geo/`에 받고 `python -X utf8 scripts/geo/build_kr_sido.py data/geo/dbip-city-lite-YYYY-MM.csv.gz` → 배포. 지역 이름이 바뀌어 스크립트가 멈추면 스크립트 안 `SIDO` 표를 고침
- 예시 지역 목록 `web/src/features/search/startExamples.ts`는 `python -X utf8 scripts/web/gen_start_examples.py`(공개 도서관 목록 API로 만듦). 도서관·가입 조건이 바뀐 뒤 다시 돌림

## 7. 설정값 목록

- 서비스·조회 서버의 운영 숫자(동시 검색 자리, 결과 줄 상한, 본문 상한, 해석 자리, 상태 불 시간, 요약 로그 간격 …)와 그 환경 변수 이름은 [settings-index.md](settings-index.md)에 한 줄씩 있습니다. 값을 바꾸거나 더할 때 그 표를 함께 고칩니다(사이클 20).

## 체크리스트 (한 바퀴)

- [ ] ①: `library-maintenance.md` 4절 순서대로 등록 도서관 확인, 바뀐 도서관은 수정(6절, 새 도서관은 넣기 명령), 결과를 13절 작업 기록과 `docs/cycles/plan-feedback.md`에 남김. 수집 목록에서 도서관 찾기 → 수집 → 넣기 명령(`library-insert` 미리 보기 → 의심·바뀐 곳·사라진 곳만 사용자에게 → 넣기) → 구독 규칙·마감 확인은 `1-libraries.md`
- [ ] ②: `resume.cmd` → `status`로 모든 도서관이 끝났는지(실패·받지 않음은 이유 확인) → `verify_detail_urls.py`
- [ ] 가져오기: 사본 만들기 → dry run → 숫자 비교 → 실제 가져오기 → 검색 확인 → 결과 숫자를 `3-import.md` 5절 표에 더함
- [ ] AI 책 찾기 사본: 가져오기·`lists` 모드·사서추천도서 월 받기 뒤에 `book-texts`를 다시 넣음(파일에 `search_summary`·`search_terms` 줄 포함, [6-aisearch.md](6-aisearch.md) 2·15절, `book-texts-top-books=100000`, [4-picks.md](4-picks.md) 10절, 후속 작업 24-F1). 가져오기 뒤에는 책 번호가 바뀌므로 책 벡터·프로필(`mode=book-profiles` → `catalog.book_profile`, 넣은 뒤 `bin/switch.sh backend <지금 판>`)·이웃도 다시 만듦([6-aisearch.md](6-aisearch.md) 7·8·9절)
- [ ] 4단계: 서비스 로그에서 상세 페이지 읽기 실패가 늘었는지
- [ ] 5단계(운영에서 사서추천 받기를 켠 경우만): `picks.fetch_run`에 최근 7일 안의 `SUCCEEDED`가 있는지, `FAILED`면 `failure_reason`([4-picks.md](4-picks.md) 3절)
