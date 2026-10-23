# ② 책 수집: 도서관마다 어떤 책이 있나 (전자책 목록 받기)

- plan 짝 문서: `../chaekcheck-plan/docs/research/collection/2-books.md`. 여기에는 절차만 둡니다(ADR-0047). 파일 이름은 그대로 둡니다(다른 문서가 `2-crawl.md`를 가리킴).

- 정기 유지보수 2단계의 **절차 원본**입니다(plan ADR-0047, 2026-10-04). 순서 전체는 [README.md](README.md).
- 옮겨 온 곳: plan `docs/research/ebook-catalog/crawl-runbook.md` 0·2·2-1·3·4·5·5-1·6·7·9·10절 (2026-10-04, 내용은 그대로, 경로만 고침). 2절(받는 순서와 호출 예의)은 요청서 목록에 없었지만 2-1절과 IP 줄 세우기의 전제라 함께 옮겼습니다.
- **진행 상태와 진행 표도 옮겨 옴 (2026-10-12, 사이클 13 인계서 1단계 7)**: plan `docs/research/ebook-catalog/crawl-runbook.md` 1·8절을 이 문서 1절·8절로 옮겼습니다(2026-10-12 복사, 내용은 그대로, 경로와 도서관 이름만 고침). 같은 문서 2·3절은 2026-10-04에 이미 이 문서 2절·2-1절로 옮겨 와 있어 다시 옮기지 않았고, 3절 끝의 "배포용 사본" 한 줄만 1절에 더했습니다 [확정] (사용자 승인, 2026-10-12, plan `docs/cycles/13-crawler-to-dev/easy/2026-10-12-where-to-crawl.html`). 앞으로 진행 숫자는 이 문서에 적습니다.
- plan에 남는 것: 결정 기록(`crawl-runbook.md`), 원칙 `crawl-method.md`, 조사 `research.md`, 규칙 근거 `catalog-refresh.md`·`same-book-isbn.md`·`search-and-normalization.md` (모두 plan `docs/research/ebook-catalog/`).
- 수집 DB(`ebook-catalog.sqlite`) 표·칸 설명: [`scripts/crawl/README.md`](../../scripts/crawl/README.md) (2026-10-05 수집기와 함께 옮김, ADR-0047 B).
- 요청 머리글(User-Agent)에 개인 연락처를 넣지 않습니다(plan `crawl-method.md`). 연락처는 서비스 주소나 공개 문의 주소가 생기면 넣습니다 [확정] (사용자 선택, 2026-10-12, plan 사이클 13 spec R1-8). 그때 `crawl_catalog.py`의 `USER_AGENT`를 고칩니다.
- 도서관 목록 이름 [확정] (사용자, 2026-10-04): **등록 도서관**(서비스 DB, dev seed, 278곳) / **수집 목록에서 찾은 도서관**(수집 DB `library` 번호 100001~, 웹러브 북스에서 찾음, 2,738곳). 수집기는 둘 다 받습니다. 자세히는 [1-libraries.md](1-libraries.md).
- 옮긴 뒤 plan 수집 세션에서 바뀐 것(2-1절 서버별 동시 요청 수, 4절 이어 받기·다른 정렬로 채우기·알려진 빈틈, 6절 알라딘·YES24 오디오북·0권)은 plan `crawl-runbook.md` 4절 "dev에 반영할 것"에서 더했습니다(2026-10-04, ADR-0047 요청서 D).

## 0. 읽는 순서

이 수집은 정기 유지보수의 2단계입니다. 앞뒤 단계(도서관 목록 확인, 서비스 DB로 가져오기)는 [README.md](README.md).

**수집 전 점검 (사이클 19, plan ADR-0117)**: [rules-index.md](rules-index.md) 1절(수집기 규칙)을 훑습니다. 통합 포털 새 책 목록 한 쪽을 포털마다 한 번 열어 `div.info` 칸 순서가 `PUBLISHER_FIRST_PORTAL_HOSTS`와 맞는지 확인합니다(출판사가 먼저인 곳만 상수에 있음, [platforms.md](platforms.md) 5절).

| 순서 | 문서 | 무엇을 알 수 있나 |
|---|---|---|
| 1 | 이 문서 | 이어 받기, 명령, 문제별 대처, 새 사이트 붙이는 순서 |
| 1-1 | 이 문서 1·8·11절 | 지금 상태(어디까지 받았나), 수집 목록에서 찾은 도서관(웹러브 북스 기관) 진행, 수집기 갈아타기 뒤 진행 기록. 결정 기록은 plan `crawl-runbook.md` |
| 2 | [platforms.md](platforms.md) | **플랫폼별 크롤링 방법**: 검색 주소, 전체 목록 주소, 한 쪽 크기, 줄 선택자, 상세 URL, 오디오북 구분, 사이트별 예외 |
| 3 | plan `crawl-method.md` | 원칙(호출 예의, 저장 범위, 날짜 3층, 수집 항목)과 그 근거가 된 사용자 결정 |
| 4 | [`scripts/crawl/README.md`](../../scripts/crawl/README.md) | 수집 DB(`ebook-catalog.sqlite`) 표·칸 설명, 파일 목록, 데이터 폴더 |
| 참고 | plan `research.md` | 왜 모으는지, 법적 쟁점, 첫 조사 |
| 참고 | plan `catalog-refresh.md` | 주기 갱신(별도 사이클) 참고. **운영에서 새로 알게 된 것은 그 문서 "변경 기록"에 더함** |
| 참고 | plan `search-and-normalization.md`, `same-book-isbn.md` | 모은 값을 검색용으로 정리하고 같은 책을 묶는 규칙(다른 세션 작성). 수집 DB는 원문만 두고 정리는 거기서 |
| 색인 | plan `docs/reference-index.md` 3절 | plan 전체에서 이 문서들이 어디에 있는지 |

코드는 dev `scripts/crawl/`(수집기)와 `scripts/library/`(수집 목록에서 도서관 찾기, 구독 규칙·마감 확인)에 있습니다(2026-10-05 복사, ADR-0047 B·C). 수집한 데이터는 git 밖 **데이터 폴더**에 둡니다: `--data-dir` 인자 > 환경 변수 `CHAEKCHECK_DATA` > 저장소 옆 `chaekcheck-data` 폴더(기본값). 수집 DB, `queue-by-ip.txt`, `logs\`, `other-platforms\`, `secrets\`가 거기 있습니다(`host-slots.txt`는 사이클 13부터 읽지 않음, 0-1절).

- **갈아타기는 끝났습니다 (2026-10-12)**: 이제 dev 수집기(`scripts\crawl\resume.cmd`, `scripts\crawl\crawl_catalog.py`)만 돌립니다. 자세히는 아래 0-1절.
- 옮길 때 바꾼 것: 데이터 폴더 위치(위), dev seed CSV를 저장소 안 상대 경로로, `status`는 수집 DB를 읽기 전용으로 열어 표 구조를 건드리지 않음(수집 중에도 안전), 확인 스크립트가 `scripts/crawl/`의 수집기를 불러 씀. 받는 방법·예의 규칙은 바꾸지 않음.

| 파일 | 역할 |
|---|---|
| `scripts/crawl/crawl_catalog.py` | 수집기. 도서관 목록 불러오기, 손으로 찾은 곳 넣기(`add-library`, 정한 번호로), 전체 수집, 이어 받기, 상태, 다시 받기 표시, 받지 않기 표시 |
| `scripts/crawl/verify_detail_urls.py` | 도서관·종류마다 상세 URL 한 건을 열어 그 책 제목이 나오는지 확인 |
| `scripts/library/discover/probe_other_platforms.py` | 북큐브·알라딘 기관 사이트를 한 번씩 호출해 열리는지·권수 확인 |
| `scripts/library/discover/probe_kyobo_institutions.py`, `probe_yes24.py`, `probe_aladin.py` | 교보 기관·YES24·알라딘 사이트를 한 번씩 확인(열리는지, 화면 종류, 권수). 쓰는 순서는 [1-libraries.md](1-libraries.md) "수집 목록에서 도서관 찾기" (원문은 이 문서 8-1절) |
| `scripts/library/probe_kyobo_limits.py`, `limits-daily.cmd` | 교보 구독형 사이트의 팝업·공지 제목과 날짜 확인. 절차는 [1-libraries.md](1-libraries.md) "구독 규칙·마감 확인" |
| `scripts/crawl/resume.cmd` | 이어 받기 한 번 실행(두 개 동시 실행을 막음, `refill-pubdt`가 돌 때도). 데이터 폴더의 `queue-by-ip.txt`를 읽고 로그를 데이터 폴더 `logs\`에 씀. `host-slots.txt`는 있어도 읽지 않고 알림 한 줄만 냄 |
| `scripts/crawl/test_parse_seoul.py` | 서울도서관 분류 목록 읽기(`parse_seoul`) 시험, 저장 JSON `testdata/seoul-catesearch-*.json`: `python -m unittest scripts/crawl/test_parse_seoul.py` |
| `scripts/crawl/test_crawl_migrate.py` | `migrate`(표 구조 맞추기)를 작은 SQLite에 두 번 돌려 보는 시험: `python -m unittest scripts/crawl/test_crawl_migrate.py` |
| `scripts/crawl/requirements.txt` | 실행 환경: Python 3.11+, `beautifulsoup4`, `lxml` |

`probe_*.py`(수집 목록에서 도서관 찾기, 구독 규칙·마감 확인)는 수집 일 나눔에서 ① 도서관 정보 도구라 `scripts/crawl/`이 아니라 `scripts/library/`에 둡니다 [확정] (사용자, 2026-10-04, ADR-0047 요청서 C).

## 0-1. 수집기 갈아타기 (2026-10-12, 사이클 13, plan ADR-0088)

- **2026-10-12부터 dev 수집기만 돕니다.** 이어 받기는 `scripts\crawl\resume.cmd`, 명령은 `scripts\crawl\crawl_catalog.py`입니다.
- **옛 코드는 옮겨 두었고 지우지 않았습니다** [확정]: 데이터 폴더의 옛 `scripts\`(옛 `crawl_catalog.py`, `refill_pubdt.py`, `probe_*.py` 등)와 맨 위 `resume.cmd`는 `chaekcheck-data\_old-scripts-2026-10-12\`에 있습니다. 데이터 폴더 맨 위에는 이제 `resume.cmd`가 없습니다. 옛 코드를 다시 돌리지 않습니다.
- 옛 데이터 폴더 도구 중 `fetch.py`, `probe_logos.py`, `probe_home_logos.py`는 dev `scripts/library/`로 복사했습니다(데이터 폴더 규칙 `--data-dir` > `CHAEKCHECK_DATA` > 저장소 옆 `chaekcheck-data`). `apply_rename.py`는 옮기지 않았습니다 [확정].
- **빈 곳 채우기 `refill-pubdt`** (옛 `refill_pubdt.py`를 대신함): 한 도서관의 목록을 출판일순으로 다시 읽어 빠진 책을 더합니다. 지우지 않고 더하기만 하고, 그 도서관의 마지막 실행(`crawl_run`)에 붙습니다.
  - `python -X utf8 scripts\crawl\crawl_catalog.py --data-dir … refill-pubdt --only 102084` (여러 곳은 `--only 1,2`, 도서관마다 따로 돌고 호출 예절은 수집과 같음)
  - 되는 화면 종류: 출판일순 목록이 있는 YES24(V1·V2·V3), 북큐브, 알라딘. 받지 않음(`crawl_skip`), 교보 구독형 서버, 한 번도 받지 않은 곳(먼저 `full`)은 로그 `not refilled (…)`를 남기고 건너뜁니다.
  - 로그: 시작 `refill in pubdt order (run N), X rows before`, 끝 `refill done, Y rows after (+Z)`. 실패는 `refill FAILED …`
- **수집 DB `library.vendor` 칸**: 사이트 시스템 업체(`KYOBO`, `YES24`, `BOOKCUBE`, `ALADIN`, `PORTAL`, `IN_HOUSE`). 화면 종류 대응표와 다를 때만 수집 세션이 적고, 넣기 명령 `library-insert`가 읽습니다(plan spec R1-2·R2-6). 2026-10-12 데이터 폴더 옛 수집기에 먼저 생긴 칸을 dev로 옮겼습니다.
  - `migrate`: 따로 부르는 명령이 아니라 `status`를 뺀 모든 명령이 DB를 열 때 표 구조를 맞춥니다. 칸이 없으면 `ALTER TABLE library ADD COLUMN vendor TEXT CHECK (…)`, 이미 있으면 건너뜁니다(두 번 돌아도 됨, `test_crawl_migrate.py`). `status`는 읽기 전용이라 고치지 않습니다.
- **교보 구독형 서버(`*.dkyobobook.co.kr`)에는 요청을 보내지 않습니다** [확정] (plan spec R1-6): 우리 IP를 막았고(8-2-1절), 교보 대여·혼합은 서비스에서 뺐습니다(plan ADR-0067). 수집기는 그 서버의 도서관을 받을 목록에서 빼고(로그 `not crawled: N libraries on *.dkyobobook.co.kr …`), 요청 직전에도 한 번 더 걸러 보내지 않습니다(`BlockedHost`).
  - 그래서 `--include-big-subscription`은 남아 있지만 아무 효과가 없고, `resume.cmd`도 넘기지 않습니다.
  - 데이터 폴더의 `host-slots.txt`(그 서버에 동시 4건)는 읽지 않습니다. 파일이 있으면 `resume.cmd`가 "host-slots.txt is ignored since cycle 13" 한 줄을 냅니다. `--host-slots` 인자 자체는 남아 있습니다(2-1절).
- **사이트가 막으면 그 도서관만 받지 않음** [확정] (plan spec R1-7): 다시 시도를 모두 해도 HTTP 403이거나 시간 초과면 그 도서관에 `crawl_skip` `blocked {날짜}: HTTP 403 (clear: --skip {id} '')`(또는 `time-out`)을 적고 다른 도서관은 계속 받습니다. 로그 `site blocks us (…); marked not crawled`. 다시 받으려면 사이트를 확인한 뒤 `status --skip {id} ""`(5-1절). `full`과 `refill-pubdt` 모두 같습니다.
- **robots.txt는 기록만 하고 수집은 합니다** [확정] (사용자 선택, 2026-10-12, plan spec R1-7). 실제로 막히면(위) 그 도서관만 받지 않습니다. 수집기는 지금 robots.txt를 읽지 않으므로, 기록할 일이 생기면 사람이 이 문서나 [platforms.md](platforms.md)에 적습니다.
- **User-Agent 연락처**는 서비스 주소나 공개 문의 주소가 생기면 넣습니다 [확정] (plan spec R1-8). 지금 값은 [`scripts/crawl/README.md`](../../scripts/crawl/README.md) "실행".
- **두 벌 실행 검사**: `resume.cmd`는 `crawl_catalog.py … full`이나 `crawl_catalog.py … refill-pubdt`, 옛 `refill_pubdt.py`가 돌고 있으면 시작하지 않습니다("crawl_catalog.py is already running. Not starting a second copy."). 옛 복사본에서 띄운 것도 찾습니다. `refill-pubdt`를 손으로 띄울 때는 수집이 돌고 있지 않은지 먼저 봅니다(명령 자체는 검사하지 않음).

## 1. 지금 상태 (2026-10-03 04:19, 순위 1~3 끝남)

옮겨 온 곳: plan `docs/research/ebook-catalog/crawl-runbook.md` 1절 (2026-10-12 복사). 이 표는 그때 값입니다. 2026-10-12 갈아타기 뒤 기록은 11절.

| 순위 | 대상 | 도서관 | 끝남 | 받지 않음 | 남음 |
|---|---|---|---|---|---|
| 1 | dev seed의 켜진 도서관 (교보·YES24·포털·강남구·작은 구독형) | 262 | 258 | 4 (5-1절) | 0 |
| 2 | 큰 교보 구독형 | 16 | 16 | 0 | 0 |
| 3 | 북큐브 (웹러브 북스 목록에서 공공으로 보이고 열린 곳) | 134 | 134 | 0 | 0 |
| 4 | 웹러브 북스 나머지 기관 (교보 전 기관, YES24·북큐브·알라딘 전 기관) | 2026-10-03 시작 | — | — | 8절 |

- DB: 6,177,468줄 (빠짐 표시 제외 6,177,349줄). 종류: 전자책 6,119,320 / 오디오북 51,829 / 동영상북 2,522 / 키즈북 3,003 / 학술논문 74 / 모름 601. 제목은 오디오북인데 표시가 다른 줄(`kind_check`) 1,466
- 덜 받은 곳: 경산 59,432/59,451권(제목순 18쪽이 늘 깨짐, 출판일순으로 채운 뒤 남은 19권). 교보 구독형은 서로 다른 책이 사이트 총 건수보다 1.5~2% 적음(목록 안 중복, 목록은 끝까지 읽음)
- 다시 받기에서 애먹은 곳과 쓴 방법: 칠곡(분야별 목록 → 10권·1권 쪽, 9,347/9,348), 경산·아산·금산(출판일순 다시 읽기), 인천 포털(공백 검색), 경기교육청 포털·칠곡(쪽 줄이기), 강서(사이트가 며칠 동안 접속 거부 → 2026-10-03 다시 열려 20,581권 모두)
- 이 표는 시점 값입니다. 지금 값은 3절의 `status`로 봅니다.
- **배포용 사본** (2026-10-05 07:42 시점, plan `crawl-runbook.md` 3절 끝에서 옮김): `chaekcheck-data\snapshots\ebook-catalog-2026-10-05-0745.sqlite`. 내용과 쓰는 법은 plan `docs/research/ebook-catalog/snapshot-2026-10-05.md`. 수집기는 원본 DB에서 계속 돎. 그 뒤 사본은 [3-import.md](3-import.md) 5절 표.

## 2. 받는 순서 [확정] (사용자, 2026-09-30: "278곳 먼저가 우선인데 웹러브 북스에 있는 책은 거의 다 수집")

- 순위 1 → 2 → 3 → 4. 순서는 **같은 호스트 안에서** 지키고, 호스트가 다르면 동시에 받습니다.
  - 순위: 1 등록 도서관 중 dev seed에서 켜진 곳(교보·YES24·포털·강남구·작은 구독형), 2 큰 교보 구독형 16곳(등록 도서관), 3 수집 목록에서 찾은 북큐브 도서관(웹러브 북스 목록에서 공공으로 보이고 열린 곳), 4 수집 목록에서 찾은 나머지 도서관(웹러브 북스 기관) (1절 표)
- 호출 예의: 같은 호스트 한 번에 1건, 1초 이상 간격 [확정] (사이클 01 spec 5.6). 공용 호스팅 `*.dkyobobook.co.kr`, `*.bookcube.biz`, `*.dasangng.co.kr`, `*.aladinebook.co.kr`, `*.yes24library.com`, `*.libworks.co.kr`, `*.hongjicns.co.kr`은 각각 한 서버로 봅니다(`crawl_catalog.py`의 `host_key`). 포트만 다른 사이트(`ebook.jbe.go.kr:10031`, `:10111` …)도 같은 호스트입니다.
- **사이트 종류·서버가 다르면 병렬로** [확정] (사용자, 2026-10-03): 교보 서버 작업, 북큐브 서버 작업, YES24 작업, 도서관 자체 서버 작업은 따로 동시에 돌립니다. 확인(probe)과 수집(crawl)도 서버가 겹치지 않으면 동시에 돌립니다. 같은 서버를 쓰는 두 작업을 동시에 띄우지 않습니다(띄우기 전에 서버가 겹치는지 확인).
- 큰 교보 구독형 16곳은 모두 한 서버라 한 곳씩 차례로 받습니다(한 쪽 80권 약 4초, 한 곳 3~4시간).

## 2-1. 병렬 정도 고르기 (실행할 때마다)

사용자 요청 "나중에 병렬도 얼마만큼 병렬로 할 것인지 선택했으면 해" (2026-10-03). 기본값은 지금까지의 규칙 그대로이고, 실행할 때 바꿀 수 있습니다.

| 값 | 뜻 | 기본 | 명령 |
|---|---|---|---|
| WORKERS | 동시에 받는 서버 수 | 40 (`resume.cmd`), 16 (`crawl_catalog.py` 단독) | `--workers N` |
| PER_HOST | 한 서버에 동시에 보내는 요청 수 | 1 | `--per-host N` |
| GAP | 같은 서버 요청 사이 간격(초) | 1.0 | `--gap S` |

- `resume.cmd [WORKERS] [PER_HOST] [GAP]` (예: `resume.cmd 60 1 1.0`)
- PER_HOST가 1이면 앞 요청이 **끝난 뒤** GAP초를 기다립니다. 2 이상이면 요청 **시작** 사이를 GAP초로 띄우고 그만큼 겹쳐 보냅니다(그 서버의 도서관을 PER_HOST개 줄로 나눠 받음)
- WORKERS는 서버가 다른 작업끼리의 병렬이라 예의 규칙과 부딪히지 않습니다. 서버 수보다 크게 잡아도 효과는 없습니다
- PER_HOST·GAP 기본값(1, 1.0초)은 사이클 01에서 정한 예의 규칙 [확정]입니다. 바꾸는 것은 사용자 결정이고, 바꾼 실행은 로그 첫 줄 `parallelism: …`에 남습니다. 공용 서버(교보·북큐브·YES24 호스팅)는 여러 도서관이 함께 쓰므로 PER_HOST를 올리면 그 업체 서버 하나에 부담이 그대로 늘어납니다 [안: 올리더라도 2~3까지, 차단·오류가 늘면 1로]
- YES24 공용 서버(`*.yes24library.com`)만 동시 요청을 올리는 안(3건·5건·초당 10회)을 검토했고, 사용자가 **지금처럼 1건**으로 정함 [확정] (2026-10-04, [이유 미확인]). 검토 때 잰 값: 이 서버는 한 쪽(10권 고정)에 약 6초 걸려 1건이면 시간당 약 500쪽, 남은 약 51,000쪽은 약 100시간
- **YES24 공용 서버 IP 나눠 받기 시험** (2026-10-04). 사용자 요청 "서버 규모에 맞게 요청을 보내고 싶어서". 이어서 사용자가 "몇 IP만 먼저 시험"을 고름 [확정]
  - 조사: `*.yes24library.com` 주소 399개를 DNS로 조회하니 IP 12개였음. 주소마다 IP는 하나씩. 211.63.172.x 대역 11개(12·13·15·16·21·22·23·26·30·31·33)와 구미 등 2곳의 210.204.86.165. 서버가 실제 몇 대인지, 뒤에서 DB를 같이 쓰는지는 바깥에서 알 수 없음 [확인 필요: 시험 중 응답 시간으로 간접 확인]
  - 시험: 남은 쪽이 가장 많은 211.63.172.12·13·21만 각자 한 줄(IP마다 1건, 1초)로 받음. 나머지 IP는 지금처럼 한 줄
  - 되돌림 조건 [안]: 따로 세운 줄의 평균 응답이 12초를 넘거나(지금 약 6초의 두 배) 5번 연달아 실패하면, 수집기가 따로 세운 줄을 모두 공용 한 줄로 다시 묶음. 로그에 `SPLIT OFF`
  - 첫 20분 결과 (09:34~09:52): 공용 한 줄 평균 응답 2.6초(200건째, 400건째 같음), 따로 세운 .12 3.4초, .13 5.3초, .21 5.5초. 시간당 받은 쪽은 공용 한 줄만일 때 약 1,000쪽에서 4줄 합계 약 2,900쪽으로 늘어남. 따로 세운 IP가 원래 느렸는지는 비교 값이 없어 모름. 앞서 적은 "쪽당 약 6초, 약 100시간"은 로그 줄 수로 거꾸로 계산한 값이었고, 직접 잰 공용 줄은 쪽당 약 3.6초(응답 + 간격 1초)
  - 이어서 사용자가 "지금 11개 전부"로 다시 고름 [확정] (10:14 다시 시작)
- **IP 기준 줄 세우기** [확정] (사용자 선택 "합치기 + 북큐브·알라딘 나누기", 2026-10-04 10:27부터)
  - 계기: 사용자 질문 "다른 것들도 각 아이피 따로따로 보내는 건?". 조사해 보니 공용 서버가 IP 여러 개로 나뉘어 있었음(북큐브 101.202.40.100·.101, 다산도 같은 IP, 알라딘 115.68.102.12·115.68.17.209). 반대로 이름이 다른 도메인이 같은 IP를 쓰는 곳도 있었음(115.68.102.12에 `*.aladinebook.co.kr`과 `ebook.yego.or.kr`·`ebook.kcmf.or.kr`·`ebook.jingwan.sen.ms.kr`, 211.63.172.24에 `yes24.gimcheon.ac.kr`·`ebook.koswec.or.kr`). 이전 방식에서는 이런 IP에 동시에 2~4건이 갔음
  - 방법: 수집기가 요청 전에 도메인의 IP를 조회(DNS)하고, 같은 IP로 가는 요청은 한 줄(1건, 1초 간격)로 세움. 요청 주소는 그대로 도메인. 공용 서버는 IP 수만큼 줄이 생기고(YES24 11, 북큐브 2, 알라딘 2), 같은 IP의 다른 도메인은 한 줄로 합쳐짐
  - 사용자 질문 "ip로 가면 막을 위험이 더 크지 않을까?"에 대한 설명: 위험을 키우는 것은 기준이 아니라 한 업체로 동시에 가는 요청 수. YES24 IP 11개는 같은 대역이라, 앞에 차단 장치가 하나 있다면 요청이 최대 11배로 보임 [확인 필요: 바깥에서 알 수 없음]
  - 되돌림 조건 [안]: 공용 서버의 IP 줄 하나라도 평균 응답이 12초를 넘거나(요청 30건 이상 뒤) 5번 연달아 실패하면, 그 업체의 IP 전부를 한 줄로 다시 묶음. 로그 `SPLIT OFF: <IP> (<업체>)`
  - 설정: 데이터 폴더의 `queue-by-ip.txt` 파일이 있으면 `resume.cmd`가 `--queue-by-ip`로 실행. 파일을 지우면 도메인 이름 기준(이전 방식)으로 돌아감. 응답 시간은 로그 `timing <IP> (<업체>): avg …s`(줄마다 200건마다). 시험 때 쓰던 `split-ip.txt`·`--split-ip`는 없앰
  - 쉬운 설명: plan `docs/research/ebook-catalog/easy/2026-10-04-crawl-method-now.html`(지금 수집 방법), `…/easy/2026-10-04-platform-servers.html`(플랫폼 타입별 서버 구조)
- **서버(줄)별 동시 요청 수** `--host-slots KEY=N,…` (2026-10-04). 사용자가 정한 줄만 동시 N건으로 받습니다.
  - 설정 파일: 데이터 폴더의 `host-slots.txt`(`resume.cmd`가 읽어 `--host-slots`로 넘김). 그 줄은 N개 목록으로 나눠 받고 요청 **시작** 사이 1초 이상
  - 되돌림 조건 [안]: 평균 응답 14초 초과(요청 30건 이상 뒤)나 5번 연달아 실패면 1건으로 돌아감. 로그 `SLOTS OFF`
  - 첫 사용: 수집 목록에서 찾은 교보 대여·혼합형 232곳. 모두 IP 103.182.126.192 한 대라 한 줄. 한 쪽(80권)에 약 8초(응답 약 7초 + 간격 1초), 약 52만 쪽이라 1건이면 약 50일 → **이 IP만 동시 4건, 요청 시작 사이 1초 이상** [확정] (사용자, 2026-10-04 canvas: "4건으로 해줘"). 설정 한 줄 `103.182.126.192=4`. 2026-10-04 17:03 시작, 예상 약 12일. 결정 페이지 plan `docs/research/ebook-catalog/easy/2026-10-04-kyobo-rental-speed-decisions.html`
  - 수집기를 옮긴 뒤에도 같은 파일을 같은 뜻으로 읽었습니다: 옮긴 `scripts/crawl/resume.cmd`는 데이터 폴더(`chaekcheck-data`)의 `queue-by-ip.txt`·`host-slots.txt`를 그대로 읽음(2026-10-05). 이 파일이 없으면 모든 서버 1건으로 돌아갑니다(예의 규칙은 지켜지지만 느려짐)
  - **사이클 13부터(2026-10-12) `resume.cmd`는 `host-slots.txt`를 읽지 않습니다** (plan spec R1-6): 유일한 줄 `103.182.126.192=4`가 교보 구독형 서버이고, 그 서버에는 요청을 보내지 않기 때문입니다(0-1절). 파일이 있으면 무시한다는 알림 한 줄만 냅니다. `queue-by-ip.txt`는 그대로 읽습니다
- 확인 스크립트(`probe_*.py`)는 아직 이 값을 받지 않습니다(서버마다 1건, 1초 고정)
- **`--per-host`와 `--host-slots`를 함께 줄 때** (코드 확인 2026-10-05, `crawl_catalog.py` `slots_for`): 줄마다 `--host-slots`에 적힌 값이 먼저이고, 적히지 않은 줄은 `--per-host` 값을 씁니다. 느려지거나 실패가 이어져 "SLOTS OFF"가 된 줄은 둘 다 무시하고 1건으로 돌아갑니다. 줄 열쇠는 `--queue-by-ip`면 IP, 아니면 호스트 이름이라 `--host-slots`의 KEY도 같은 기준으로 적어야 맞습니다

## 3. 이어 받기와 상태 보기

PC를 켠 뒤 한 번 실행합니다. 몇 번을 실행해도 안전합니다.

```
scripts\crawl\resume.cmd
```

(2026-10-12 갈아타기 전에는 데이터 폴더 `resume.cmd`였습니다. 지금은 `chaekcheck-data\_old-scripts-2026-10-12\`에 보관만 하고 돌리지 않습니다. 0-1절)

- 끝난 도서관은 건너뛰고, 받다 멈춘 도서관은 저장된 마지막 쪽 다음부터 이어 받습니다.
- 이미 수집(`full`)이나 빈 곳 채우기(`refill-pubdt`)가 돌고 있으면 두 번째는 시작하지 않습니다. 로그는 데이터 폴더 `logs\crawl-{날짜_시각}.log`.
- Bash에서 부를 때는 `cmd //c resume.cmd`가 파일을 못 찾으므로 PowerShell에서 `& "…\resume.cmd"`로 부릅니다.
- **Windows 절전**이 켜져 있으면 멈춥니다. 오래 받을 때는 절전을 끄거나 다시 실행합니다.

상태 보기:

```
python -X utf8 scripts\crawl\crawl_catalog.py status
```

```sql
-- 도서관별 마지막 실행
SELECT l.id, l.name, l.platform_type, r.status, r.last_page, r.site_total, r.rows_seen, r.started_at, r.error, l.crawl_skip
FROM library l LEFT JOIN crawl_run r ON r.id = (SELECT max(id) FROM crawl_run WHERE library_id = l.id)
ORDER BY r.status, l.id;

-- 전체 목록을 한 번도 끝까지 받지 못한 도서관
SELECT id, name, platform_type, crawl_skip FROM library WHERE last_full_success_at IS NULL;
```

## 4. 수집기가 하는 일 (규칙 요약)

| 상황 | 동작 |
|---|---|
| 목록이 하나인 사이트(교보, 교보 구독형, 강남구, 북큐브, 오디오북 메뉴 없는 YES24) | `crawl_run.last_page`까지 저장. 다음 실행 때 그 다음 쪽부터 |
| 오디오북 메뉴 있는 YES24(MAIN·AUDIO) | 읽던 목록(`crawl_run.cur_section`)과 쪽(`last_page`)부터 이어 받음. 끝난 목록의 건수는 `done_total` (2026-10-04 15:56부터. 그 전에는 다시 켤 때마다 1쪽부터 다시 받아 여주대·경운대·대림대처럼 6,000~8,000쪽인 곳이 이날 다섯 번 처음으로 돌아감) |
| 목록이 여럿인 포털(EBK·ADO) | 처음부터 다시(작아서 괜찮음) |
| 이어 받을 실행 고르기 | 그 도서관의 **마지막** 실행이 "받는 중"일 때만 이어 받음. 더 오래된 "받는 중"은 버림 |
| 다시 받을 도서관 고르기 (`--retry-failed`) | 마지막 실행이 실패·불일치·받는 중이거나, **전체 목록을 한 번도 끝까지 받지 못한** 곳 (오디오북 목록만 성공한 경우 포함) |
| 성공 판정 | 서로 다른 책 수가 사이트 총 건수와 1% 안, 또는 **읽은 줄 수**가 총 건수와 1% 안(목록 안에 같은 책이 겹치는 교보 구독형). 포털은 총 건수가 없어 빈 쪽까지 읽으면 성공 |
| 빠진 책 | 성공한 전체 수집에서만, 이번에 못 본 줄에 `removed_at`을 찍음(지우지 않음). 받은 구역(`section`) 안에서만 |
| 요청 실패 | 30초 간격 3번 다시. 첫 쪽이면 그 도서관 실패 |
| 목록 대신 오류 화면 | 같은 쪽을 30초 간격 3번 다시 읽음(영천·영주에서 봄) |
| 한 쪽이 깨짐(HTTP 5xx, 또는 다시 읽어도 오류 화면) | 그 쪽만 건너뛰고 계속. 끝난 뒤 **건너뛴 쪽만 다시 읽음**(대부분 잠깐 오류라 여기서 채워짐). 그래도 깨지는 쪽이 있으면 **다른 정렬(출판일순)로 한 번 더** 읽어 채움: YES24 `sort=pubdt`(아산 131쪽, 금산 20쪽), 북큐브 `sort=2`, 알라딘 `order_list=1` (북큐브·알라딘은 2026-10-04부터. 전에는 YES24만이라 대원대·성균관대(북큐브), 아주자동차대(알라딘)가 사이트 오류 쪽 때문에 `MISMATCH`였음). 남은 쪽은 `crawl_run.error`에 적음 (2026-10-02: 경산이 잠깐 오류 10여 쪽 때문에 5,946쪽 전체를 다시 읽던 것을 고침) |
| 알려진 빈틈: 다시 켜기 전에 건너뛴 쪽 | 수집기를 다시 켜기 전에 건너뛴 쪽(사이트 오류)은 다시 켠 뒤 기억하지 못해 다시 읽지 않음 → 그 쪽 책이 빠짐(천상중학교 1,181/1,201). 그 도서관은 "전체 성공 없음"으로 남아 다음 재시작 때 처음부터 다시 받음. 고칠 방법 [안]: 건너뛴 쪽 목록을 `crawl_run`에 저장 |
| 책 코드 없는 줄 | 그 줄만 건너뛰고 개수를 `crawl_run.error`에 적음. 총 건수 비교에서 뺌 |
| 받지 않는 도서관 | `library.crawl_skip`에 이유가 있으면 건너뜀(5-1절) |
| 교보 구독형 서버(`*.dkyobobook.co.kr`) | 받을 목록에서 빼고, 요청 직전에도 걸러 보내지 않음 (2026-10-12부터, 0-1절) |
| 사이트가 막음(다시 시도 뒤에도 HTTP 403·시간 초과) | 그 도서관만 `crawl_skip` `blocked …`로 표시하고 나머지는 계속 (2026-10-12부터, 0-1절) |
| 오디오북·상세 URL | `content_type`·`is_audiobook`·`kind_check`·`detail_url`은 SQLite 트리거가 채움(6·7절) |
| 책 소개 (2026-10-23부터) | 목록 줄에 실려 오는 소개를 `holding.intro_raw`에 원문 그대로(태그만 벗김). 교보·YES24·북큐브·강남구만; 알라딘·포털·서울도서관 목록에는 없어 NULL. 상세 쪽은 열지 않음(요청 수 그대로). 서비스에는 넣지 않음([README](../../scripts/crawl/README.md) DB 구조) |

## 5. 명령 모음 (저장소 뿌리에서. 데이터 폴더가 기본값과 다르면 `--data-dir 경로`)

| 명령 | 하는 일 |
|---|---|
| `python -X utf8 scripts\crawl\crawl_catalog.py init` | dev seed CSV에서 도서관 목록을 다시 읽음(dev 파일은 읽기만). 이미 있는 줄은 갱신 |
| `… import-bookcube [--probe 파일]` | `probe_other_platforms.py` 결과에서 열린 북큐브 사이트를 수집 DB 도서관 목록에 넣음(id 100001부터, `source=okbari-2026-09-30`. 2026-10-03 것은 `--source okbari-2026-10-03`) |
| `… import-probe --probe 파일 --source okbari-2026-10-03` | 교보 기관·YES24·알라딘 확인 결과에서 열린 사이트를 수집 DB 도서관 목록에 넣음. 절차는 [1-libraries.md](1-libraries.md) "수집 목록에서 도서관 찾기" |
| `… full --retry-failed --audio-pass --workers 40` | 이어 받기(`resume.cmd`가 부르는 것). `--include-big-subscription`은 2026-10-12부터 효과 없음(0-1절) |
| `… full … --workers N --per-host N --gap S` | 병렬 정도 고르기 (2-1절) |
| `… full … --host-slots KEY=N,…` | 정한 줄만 동시 N건 (2-1절). `resume.cmd`는 2026-10-12부터 `host-slots.txt`를 읽지 않음 |
| `… refill-pubdt --only ID[,ID…]` | 빈 곳 채우기: 출판일순으로 다시 읽어 빠진 책만 더함(옛 `refill_pubdt.py`를 대신함, 0-1절). 로그 끝 `refill done` |
| `… full --only 12,13` | 도서관 몇 곳만 |
| `… full --platforms YES24_V1,YES24_V2` | 화면 종류 몇 개만 |
| `… full --sections AUDIO` | YES24 오디오북 목록만(그 구역 안에서만 빠짐 표시) |
| `… full --limit-pages 2` | 시험 삼아 앞 두 쪽만(결과는 불일치로 남음) |
| `… invalidate --platforms X,Y --reason "이유"` | 그 화면 종류 도서관을 모두 처음부터 다시 받게 표시(파서를 고친 뒤) |
| `… status --skip {id} "{이유}"` | 그 도서관을 받지 않게 표시. 이유를 `""`로 주면 다시 받음 |
| `… backfill-types` | 오디오북 표시·상세 URL을 모든 줄에 다시 계산(수집이 돌 때는 오래 막히므로 멈춘 뒤에) |
| `… status` | 상태 요약. 수집 DB를 읽기 전용으로 엶(수집 중에도 됨) |
| `… fetch-covers [--plan-only / --fetch-only / --status / --export]` | 표지 받기(2026-10-23, 12절). 목록 수집이 끝난 뒤 따로 |
| `python -X utf8 scripts\crawl\verify_detail_urls.py [결과.tsv] [화면종류,…]` | 상세 URL 확인. **수집과 같은 서버를 동시에 치지 않게** 화면 종류를 골라서 |
| `python -X utf8 scripts\library\discover\probe_other_platforms.py 입력.tsv 결과.tsv` | 기관 사이트 한 번씩 확인(입력: platform, name, url) |

- 추천 목록 (사이클 21, plan ADR-0128): 수집기를 돌릴 때, 지난 갱신 뒤 한 달이 넘었으면 `python scripts/crawl/recommended_lists.py --sqlite <수집 사본>`도 함께 돌립니다. 이동진 영상 다시 확인(30일 규칙), 새 책은 `scripts/crawl/lists/leedongjin_pending.tsv`에 분야 제안과 함께 들어가며(사람이 분야를 정해 `leedongjin.tsv`로 옮김), 판 고르기와 표지를 다시 합니다. 키는 `.env`의 `CHAEKCHECK_YOUTUBE_API_KEY`·`CHAEKCHECK_PICKS_NLK_API_KEY`, 기록은 `data/recommended-lists/collect.log`

## 5-1. 받지 않는 도서관 (`library.crawl_skip`)

주기 갱신 때 다시 확인할 대상입니다. 아래 표는 2026-10-01 기준이고, 지금 목록은 `status`나 `SELECT id, name, crawl_skip FROM library WHERE crawl_skip IS NOT NULL`로 봅니다. 차단된 곳을 받으려면 도서관에 자동 수집 허용이나 자료 제공을 문의하는 것이 방법입니다 [안]. **브라우저 흉내나 차단 우회는 하지 않습니다.**

| id | 도서관 | 이유 (2026-10-01) |
|---|---|---|
| 44 | 국회도서관 | 브라우저가 아닌 요청에는 머리글과 관계없이 빈 쪽(19바이트). 자동 접근 차단으로 보고 우회하지 않음 (브라우저 기준 17,162권) |
| 135 | 전라북도교육청통합도서관 (교보 `:10111`) | 브라우저에도 "L7 Secure connect fail". 같은 교육청 책은 통합 포털(15)에 교보 47,868줄로 있음 |
| 261 | 군포시도서관(YES24) | 주소가 교보 군포(46, 수집 끝남)로 넘어감 |
| 269 | 김천시립도서관 (YES24) | 2026-09-30부터 스크립트 연결을 끊음(브라우저는 열림). 우회하지 않음. 같은 서버 북큐브(100035)는 막히기 전 3,481종 받음 |

- 44·261·269는 2026-10-12에 다시 열어 위와 같은 상태임을 확인하고 `scripts/library/rules/gone_known.tsv`에 적어 서비스에 두었습니다(사용자 선택, 2026-10-12). **2026-10-14 사이클 18에서 셋을 끄고**(`library/016-library-disabled-cycle18.yaml`, [library-site-issues.md](../library-site-issues.md) 11절) `gone_known.tsv`에서 지웠습니다. 꺼진 곳은 넣기 명령이 비교하지 않아 "사라진 곳"에 다시 나오지 않습니다([1-libraries.md](1-libraries.md)).
- **2026-10-12부터 수집기가 스스로 표시하는 곳** (plan spec R1-7, 0-1절): 다시 시도 뒤에도 HTTP 403이나 시간 초과면 `crawl_skip`이 `blocked {날짜}: HTTP 403 (clear: --skip {id} '')`(또는 `time-out`)이 됩니다. 이 표시는 그 도서관 하나에만 붙습니다. 사람이 사이트를 확인하고 여기 표에 이유를 더하거나, 잠깐 오류였으면 `status --skip {id} ""`로 풉니다.
- 교보 구독형 서버(`*.dkyobobook.co.kr`)의 도서관은 `crawl_skip`이 없어도 받지 않습니다(0-1절).

- **2026-10-11 교보 대여·혼합 230곳** (plan ADR-0067, 사이클 08 인계서 10-1): 이유 "교보 대여·혼합 제외 (plan ADR-0067, 2026-10-11)". 대상은 `other-platforms/kyobo-institutions-probe-2026-10-03.tsv`에서 `model`이 대여·혼합인 243곳 중 수집 목록에서 찾은 곳(수집 번호 100001 이상) 232곳에서, 이미 "same site as registered library"로 표시된 2곳(102575, 102576)을 뺀 230곳(인계서 수와 같음). 등록 도서관 11곳은 표시하지 않음. 줄은 지우지 않음. 번호 목록은 git 밖 `data/cycle08/kyobo-skip-ids.txt`.
  - 방법: `status --skip`은 한 번 부를 때마다 전체 상태 보고를 만들어(1곳 약 50초) 24곳 뒤에 멈추고, 같은 문장(`UPDATE library SET crawl_skip=? WHERE id=?`)을 남은 곳에 한 트랜잭션으로 실행함. 여러 곳을 한 번에 표시할 때는 이렇게 하는 것이 빠름. 수집기가 돌지 않을 때 함.
- **2026-10-11 주소 고침: 수집 번호 100142 (서비스 411) GS건설 자이 전자책 도서관**: `http://ebook2.ezville.net/FxLibrary` → `https://gsenc.bookcube.biz/FxLibrary`(옛 주소가 리다이렉트로 넘어가는 곳, 사이클 07 `library-site-issues.md` 9절·plan-feedback). `UPDATE library SET base_url=…, host=… WHERE id=100142` → `full --only 100142 --limit-pages 2`로 두 쪽 시험(새 `detail_url`이 새 호스트로 채워짐 확인) → `full --only 100142`. 서비스에 다시 들어오는 것은 다음 사본·가져오기 때.

## 6. 문제별 대처

| 증상 (로그·상태) | 먼저 볼 것 | 대처 |
|---|---|---|
| `FAILED … fetch failed … Timeout` | 사이트가 느린지 브라우저로 열어 봄 | 교보·포털이면 그 호스트를 `PAGE_SIZE_BY_HOST`에 넣어 한 쪽을 줄임(칠곡 100, 경기교육청 포털 50, 전북 교육문화회관 100) |
| `IncompleteRead` | 큰 쪽에서 응답이 끊김 | 위와 같이 한 쪽을 줄임 |
| `IntegrityError('NOT NULL constraint failed: holding.title')` | 제목이 빈 책 | 북큐브 3곳(백석 `bu.bookcube.biz`, 안산대 `ebook.ansan.ac.kr`, 경북보건대 `lib.kcs.ac.kr:8080`)에서 났음. 안산 `F0002074`는 XML 목록과 상세 화면 모두 제목이 비어 있었음. 그 줄만 건너뛰고 셈(`skip`)하도록 고침 (2026-10-04, 사용자 "북큐브 실패 3곳도 고쳐서 받아줘") |
| `SPLIT OFF` | 공용 서버의 한 IP가 느려지거나 계속 실패 | 그 업체 IP가 모두 한 줄로 묶여 계속 받음. 로그 `timing`으로 응답 시간을 보고, 차단이 의심되면 수집을 멈추고 사용자에게 알림 |
| `SLOTS OFF` | `--host-slots`로 N건 보내던 줄이 평균 응답 14초 초과(요청 30건 이상 뒤)나 5번 연달아 실패 [안] | 그 줄이 1건으로 돌아가 계속 받음. 차단이 의심되면 수집을 멈추고 사용자에게 알림 (2-1절) |
| `div.total em missing`, `book_resultTxt missing`이 첫 쪽에서 | 브라우저로 첫 화면을 봄 | 주소가 옮겼는지(군포), 화면 종류가 바뀌었는지 확인. 옮겼으면 새 주소가 이미 목록에 있는지 보고 없으면 넣고, 있으면 `--skip` |
| 빈 쪽·연결 끊김인데 브라우저는 열림 | 머리글을 바꿔도 같은지 한두 번만 시험 | 자동 접근 차단 → `--skip`에 이유 적고 사용자에게 알림(국회·김천) |
| 브라우저도 안 열림 | 같은 기관의 다른 주소(통합 포털 등)에 같은 책이 있는지 | 있으면 `--skip`, 없으면 다음 갱신 때 다시 |
| 포털 목록에 "로그인 후 이용" | — | 수집기가 자동으로 **공백 한 글자 검색**(`search/table.do … search_text=%20`)으로 바꿔 받음(인천) |
| `MISMATCH`인데 `rows_seen`이 총 건수와 같음 | 목록 안 중복 | 지금 규칙으로는 성공. 예전 실행이면 `crawl_run` 상태를 SUCCEEDED로 바꾸고 이유를 `error`에 적음 |
| `MISMATCH`이고 받은 수가 크게 적음 | 책 번호를 잘못 읽는지(예: 숫자만 읽음) | 파서를 고치고 `invalidate`로 그 화면 종류를 다시 받음 |
| 상세 URL이 다른 책·빈 화면 | `verify_detail_urls.py` 결과 | 목록 링크를 그대로 쓰는지, 필요한 값(`menu_idx` 등)이 빠졌는지 확인하고 고친 뒤 `invalidate` |
| 같은 쪽이 늘 끊김(시간 초과)이고 정렬을 바꿔도 그대로 | 문제의 책이 든 쪽이 끊기는 것 | **목록을 작게 나눠 읽음**: 교보는 분야별 목록(`content/contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId={분야 앞 두 자리}`)을 80권씩 → 끊긴 쪽만 10권씩 → 그래도 끊기면 1권씩. 칠곡(2026-10-02~03)은 1권씩 읽은 쪽이 모두 열려 9,348권 중 9,347권을 받음(분야 목록 합계 기준으로는 전부). 분야 코드 앞 두 자리는 이미 받은 줄의 `category_code`에서 얻음. YES24는 출판일순 전체 다시 읽기(경산 59,432/59,451권) |
| 알라딘 `MISMATCH`가 거의 모든 곳에서, 서로 다른 책이 약 100권뿐 | 쪽을 넘겨도 거의 같은 목록이 오는지 | `startPage`는 쪽 번호가 아니라 0부터 세는 책 순번([platforms.md](platforms.md) 8절). 수집기를 `(쪽-1)×100`으로 고치고 `invalidate --platforms ALADIN_V1` (2026-10-04 15:50) |
| 알라딘 `empty page N of M`, `skipped … row without goods_id` | 책 번호에 영문이 섞였는지 | 책 번호에 영문이 섞임(`BOOKRAILN0809003`, 한국해양대 `kmou.aladinebook.co.kr`). 영문·숫자로 읽게 고치고 알라딘 25곳 다시 받음 (2026-10-04 16:01) |
| 성공인데 0권이고 웹러브 북스 숫자는 큼 | 브라우저로 목록을 다시 봄 | 그때 사이트가 빈 목록을 줌. 국립군산대(교보 `ebook.kunsan.ac.kr:88`, 18,771권): 다시 보니 정상이라 다시 받음 (2026-10-04) |
| `AUDIO list unreadable, skipped` (`crawl_run.error`에 `AUDIO-list-unreadable`) | YES24 `AUDIO` 첫 쪽이 오류(500)나 다른 화면(`div.total em missing`)인지 | 전에는 도서관 전체가 실패하던 것 → 오디오북 목록만 건너뛰고 전자책 목록은 받음 (2026-10-04, 칠보중학교 `chilbo.yes24library.com`, 국제뇌교육종합대학원대학교 `ube.yes24library.com`) |
| 새 종류 표시 값(`content_kind`)이 생김 | `SELECT content_kind, count(*) … GROUP BY 1` | `crawl_catalog.py`의 `TYPE_CASE`에 더하고 해당 줄만 `UPDATE`(북큐브 `book/aud` = 오디오북이 이렇게 발견됨) |

## 7. 새 사이트·새 플랫폼 붙이는 순서

0. 그 사이트를 서비스에 넣을 수 있는지(기존 adapter가 읽는지, 검색 API, 수량 칸, 권수, 가입 자격)는 먼저 [library-maintenance.md](../library-maintenance.md) 4-14 방법으로 조사·기록함(첫 사례: 서울도서관 2026-10-20, [platforms.md](platforms.md) 11절).
   - **새 화면 종류(adapter)가 필요하면** (서울도서관 `SEOUL_ELIB_V1`이 본보기, 2026-10-20): `backend` `PlatformType` 값 + CHECK를 바꾸는 새 Liquibase changeset(`library/020-…` 참고, 적용된 001은 고치지 않음) + `availability/internal/adapter/` adapter(저장 응답으로 시험) + `AvailabilityResolvers` 판정 방식 + 넣기 명령 `LibraryInsertPlanner`의 `VENDORS`·`lendingModel` + 웹 `web/src/api/types.ts` `PlatformType` + 시험용 `TestTargets.vendorOf`, 그리고 아래 3번 수집기. 비교용 `scripts/library/build_library_seed.py`의 `VENDORS`·`lending_model_of`도 맞춤.
1. **표본 한 곳을 브라우저와 스크립트로 모두 열어 봄.** 스크립트 요청이 막히면 여기서 멈추고 사용자에게 알림.
2. [platforms.md](platforms.md)의 같은 플랫폼 절을 봄. 없으면 새 절을 만들고 다음을 채움: 알아보는 법(주소 모양), 검색 주소(제목·저자·출판사), **전체 목록 주소**(빈 검색, 공백 검색, 전체 목록 메뉴, XML 등), 한 쪽 최대 줄 수, 전체 건수 위치, 줄 선택자, 책 코드, 상세 URL, 오디오북 등 종류 구분, 문자 인코딩, 확인 날짜.
3. `crawl_catalog.py`에 더함: `PLATFORMS`, `PAGE_SIZE`, `page_url`, `parse`(와 `parse_…` 함수), 필요하면 `segments`(목록이 여럿일 때). 상세 경로는 **목록의 링크를 그대로** 저장.
4. 도서관 줄 넣기: dev seed에 있으면 `init`, 제3자 목록이면 `probe_other_platforms.py` → `import-bookcube`처럼 불러오는 함수를 만들어 `source`를 적음. 손으로 찾은 한 곳은 `add-library --id N --name … --platform … --base-url … --source …`(번호를 정해 규칙 파일 `public_join.tsv`·`library_kinds.tsv`·`final_origins.tsv`에 먼저 적을 수 있음, 서울도서관 102739).
5. `full --only {한 곳} --limit-pages 2`로 시험 → 몇 줄을 DB에서 눈으로 확인 → `full --only {한 곳}` 끝까지 → 사이트 총 건수와 비교.
6. `verify_detail_urls.py … {화면종류}`로 상세 URL 확인. 상세 화면 HTML에 제목이 없고 스크립트가 채우는 사이트(서울도서관)는 이 도구가 "제목 없음"으로 봄: adapter가 읽는 상세 JSON을 한 건 열어 확인하고 그 결과를 platforms.md에 적음.
7. [platforms.md](platforms.md), 이 문서 1절 표(지금은 11절 진행 기록), plan `catalog-refresh.md` 변경 기록을 고침.

## 8. 순위 4: 웹러브 북스 나머지 기관 (2026-10-03 시작)

옮겨 온 곳: plan `docs/research/ebook-catalog/crawl-runbook.md` 8·8-1·8-2·8-2-1·8-3절 (2026-10-12 복사, 내용은 그대로, 스크립트·plan 문서 경로와 도서관 이름만 고침). 절차의 지금 판은 [1-libraries.md](1-libraries.md) "수집 목록에서 도서관 찾기"입니다.

사용자 요청 "시작해줘", "병렬적으로 수집하면 안 돼?", "교보 따로 다른 사이트들 따로" (2026-10-03).

### 8-1. 절차 (플랫폼마다 따로, 서로 병렬)

| 단계 | 교보 | YES24 | 북큐브 |
|---|---|---|---|
| 1. 기관 목록 | plan `docs/cycles/02-library-expansion/data/kyobo-institutions.tsv` (교보 공식 목록, 2,000줄 → 사이트 1,937곳) | 웹러브 북스 기관 탭을 브라우저로 모음 → `chaekcheck-data/other-platforms/okbari-2026-10-03/part1~3.tsv`, `yes24-candidates.tsv`(시험·개발용 이름 제외 775곳) | 같은 방법 → `bookcube-candidates.tsv`(495곳) |
| 2. 한 번씩 확인 (열리는지, 화면 종류, 권수) | `scripts/library/discover/probe_kyobo_institutions.py` → `other-platforms/kyobo-institutions-probe-2026-10-03.tsv`. 첫 화면 `library_model`(소장·대여·혼합)도 적음 | `scripts/library/discover/probe_yes24.py` → `okbari-2026-10-03/yes24-probe.tsv`. V1·V2·V3를 가림 | `scripts/library/discover/probe_other_platforms.py` → `okbari-2026-10-03/bookcube-probe.tsv` |
| 3. 도서관 목록에 넣기 | `crawl_catalog.py import-probe --probe … --source okbari-2026-10-03` | 같음 | `crawl_catalog.py import-bookcube --probe … --source okbari-2026-10-03` |
| 4. 받기 | `full --retry-failed --platforms …` 또는 `resume.cmd` | 같음 | 같음 |

- 웹러브 북스 목록은 제3자 자료(수집 기준일 2026-02-06으로 보임)라 그대로 믿지 않고 2단계에서 한 번씩 확인합니다. 이미 있는 주소는 3단계에서 건너뜁니다.
- 새로 넣은 도서관은 `library.source = okbari-2026-10-03`, id 100001부터. 받는 순위는 3(2절)
- 알라딘도 같은 4단계: 기관 목록 `okbari-2026-10-03/aladin-candidates.tsv`(시험용 2곳 제외 163곳) → `scripts/library/discover/probe_aladin.py` → `import-probe` → 받기. 사이트마다 다른 전자책 메뉴 코드는 `base_url` 뒤 `?nav_code=…`로 저장
- 확인 스크립트는 모두 결과를 한 줄씩 바로 저장하고, 다시 돌리면 이미 확인한 곳을 건너뜀. 도구의 백그라운드 작업은 30분쯤에 끊기므로 긴 작업은 PowerShell `Start-Process`로 따로 띄움(2026-10-03 교보 확인이 끊겨 결과를 잃은 뒤 고침)
- 입력·결과 TSV 이름의 `candidates`는 데이터 폴더에 이미 있는 파일 이름이라 그대로 둡니다.

### 8-2. 진행 (2026-10-03 18:52)

| 플랫폼 | 확인 결과 | 넣음 | 수집 |
|---|---|---|---|
| 북큐브 | 495곳 중 열림 336, 접속 오류 155, XML 없음 4 | 324곳 (약 496만 종) | 12:42 시작, 18:50까지 146곳 끝남 |
| YES24 | 775곳 중 열림 484(V1 82·V2 377·V3 25), 오류 267, YES24 화면 아님 24 | 484곳 (약 199만) | 18:52 시작 |
| 교보 | 1,937곳 중 열림 1,828: 일반 화면 398, 구독형 화면 1,430(소장 1,187·혼합 196·대여 47), 오류 96 | 일반 357 + 소장 1,182 = 1,539곳 (약 573만, 이미 있던 46곳 제외) | 18:52 시작 |
| 교보 대여·혼합 | 243곳, 합계 약 4,412만 줄(235곳이 5만 권 이상, 교보 구독 목록) | **2026-10-04 넣음 (232곳, 11곳은 이미 있음)** | 일단 빼고 나머지 먼저 [확정] (사용자, 2026-10-03: "대여·혼합형은 일단 빼고 나머지 먼저 받아줘") → **받기로 함** [확정] (사용자, 2026-10-04: "적합하게 대여/혼합형도 해줘", "도서관마다 거의 책이 같다 하더라도 중요한 건 바로 도서관-책 매핑이야"). 이미 받은 공공 구독형 16곳은 323만 줄 중 서로 다른 책 24만 9천 권(두 곳끼리 겹침 72~99%, 가운데 89%)이라 새 책은 적지만, 어느 도서관에 있는지가 필요함. 232곳 모두 IP 103.182.126.192 한 대 → 한 줄. `source=okbari-2026-10-03-rental`, 16:44 시작. 한 쪽(80권)에 약 8초(응답 약 7초 + 간격 1초), 약 52만 쪽이라 1건이면 약 50일 → **이 IP만 동시 4건, 요청 시작 사이 1초 이상** [확정] (사용자, 2026-10-04 canvas: "4건으로 해줘"). 평균 응답 14초 초과(요청 30건 이상 뒤) 또는 5번 연달아 실패면 자동으로 1건(로그 `SLOTS OFF`) [안]. 설정: `chaekcheck-data\host-slots.txt` 한 줄 `103.182.126.192=4` → `resume.cmd`가 `--host-slots`로 넘김. 17:03 시작, 예상 약 12일. 결정 페이지 plan `docs/research/ebook-catalog/easy/2026-10-04-kyobo-rental-speed-decisions.html` 한 서버라 1건·1초면 약 25일. 넣을 때는 `kyobo-institutions-probe-2026-10-03.tsv`에서 `model`이 대여·혼합인 줄만 골라 `import-probe` |
| 알라딘 | 서버가 안 겹치는 94곳 확인: 열림 25, 다른 화면 37, 오류 32 | 25곳 (약 11만) | 18:52 시작. 나머지 69곳(`aladin-later.tsv`)은 아직 확인 안 함 |

- 18:52부터 이 모든 도서관을 한 프로세스(`resume.cmd 60 1 1.0`)로 받음: 2,294곳, 서버 388개, 60개 병렬
- `resume.cmd`를 `Start-Process`로 띄울 때는 **전체 경로**로 불러야 함(작업 폴더만 주면 "명령이 아닙니다"로 실패)
- (2026-10-12 덧붙임) 교보 대여·혼합은 그 뒤 수집도 서비스도 하지 않기로 했고(8-2-1절 끝, plan ADR-0067), 사이클 13부터 그 서버에 요청을 보내지 않으며 `host-slots.txt`도 읽지 않습니다(0-1절).

### 8-2-1. 교보 구독형 서버가 우리 IP를 막음 (2026-10-06~07)

| 때 | 본 것 |
|---|---|
| 10-06 03:20 무렵 | 103.182.126.192(모든 `*.dkyobobook.co.kr`) 응답이 평소 4~6초 → 14초 넘게. 03:34 `SLOTS OFF`(4건 → 1건) |
| 10-06 11:50 | 응답이 약 5.5초로 돌아와 다시 4건으로 켬 (사용자 결정 "서버가 다시 빨라지면 … 4건으로 되돌린다", 2026-10-06) |
| 10-06 12:09 | 다시 `SLOTS OFF`. 그 뒤 23:32까지 요청이 거의 다 시간 초과(평균 약 58초), 교보 4곳에서 620쪽 넘어감 |
| 10-07 07:40~08:15 | 이 PC에서는 TCP 연결 자체가 대답 없이 끊김(21초). 같은 대역 `.193`, `ebook.kyobobook.co.kr`(`.2`)은 정상. 바깥 네트워크(WebFetch)에서는 "연결 거부" |
| 10-07 08:20 무렵 | **사용자 폰(모바일 데이터)에서는 열림** → 서버는 살아 있고 **이 집 IP는 막힘** [확정] (사용자: "폰은 들어가져", 2026-10-07) |
| 10-07 09:00 무렵 | 폰으로 다른 와이파이에서 열어도 응답이 아주 느림 (사용자: "폰으로 전혀 다른 와이파이로가도 어마어마하게 응답이 느리다", 2026-10-07) → 서버 자체도 누구에게나 느린 상태. 우리 IP 차단과 서버 과부하가 함께 있는 것으로 봄 [추정] |

- 원인 [추정]: 이틀 동안 이 서버에 요청 약 8만 건, 느려진 두 번 모두 동시 4건으로 보내던 때. 서버 쪽 차단 정책은 확인 못 함
- 정기 점검이 아님: 교보 정기 점검은 일요일 00:30~09:00, 교보문고 전체 중단(2025-11-23 https://www.kyobobook.co.kr/cscenter/notice/1006614 , 2026-02-22 https://www.kyobobook.co.kr/cscenter/notice/1007060 , 2026-10-07 확인). 10월 점검 공지는 없음
- 지킨 것: **우회하지 않음**(다른 IP·프록시·폰 테더링으로 받지 않음, 5-1절 "브라우저 흉내나 차단 우회는 하지 않습니다"). 10분마다 하던 접속 확인도 08:25에 멈춤
- 데이터: 넘어간 620쪽은 "여기까지 받음"(`last_page`)이 넘어간 첫 쪽 바로 앞에 머물러 있어, 다시 받을 때 그 쪽부터 읽음(빠지는 책 없음). 교보 대여·혼합 202곳(받는 중 6 + 아직 196, 약 42만 7천 쪽)이 남음
- 영향: 이 PC에서 서비스를 돌리면 같은 서버를 쓰는 등록 도서관 21곳(세종시립 구독, 강동구립 구독, 국립세종 구독형 등)의 대출 조회도 안 됨. 배포 서버는 IP가 달라 상관없음
- 어떻게 할지는 사용자에게 물음: 결정 페이지 plan `docs/research/ebook-catalog/easy/2026-10-07-kyobo-blocked-decisions.html` → **교보 대여·혼합은 아예 뺌** [확정] (사용자: "나는 \"교보 구독형 플랫폼 – 대여·혼합\"이걸 아예 제외하려고 해", 2026-10-10). 범위와 할 일은 plan `docs/cycles/08-polish-swap-memory/easy/2026-10-10-kyobo-rental-exclude.html`
- 같은 서버를 쓰는 도서관끼리 비교 (2026-10-07, 수집 DB):
  - 대여·혼합형 중 다 받은 28곳: 한 곳 23만 8천~24만 9천 권, 합쳐도 26만 권, 두 곳씩 겹침 가운데 96%
  - 등록 도서관 구독형 21곳의 책 25만 권 중 24만 7천 권이 대여 목록과 같음
  - 소장형 1,223곳: 한 곳 가운데 684권, 두 곳씩 겹침 가운데 0.5%
- 사용자 폰 확인 (2026-10-07 09:02, 모바일 데이터, 화면 캡처):
  - 한국국제교류재단(대여·혼합 목록에 있음, 580권): 목록에 "대출 0/2"처럼 보유 권수가 보임(소장형 모양). 첫 책 "11문자 살인사건"은 우리 데이터에서 소장형 61곳에 있음. 대여 목록의 책(`brcd=480D240341920`) 상세 화면에는 대출 버튼이 없음
  - LG인화원(30,663권): 보유 권수 없이 대출 버튼만(구독 모양). 첫 책 "#프롭테크", "(2022) 생태계서비스 평가지도"는 받은 대여 34곳 모두에 있음 → 큰 대여 목록 중 일부만 계약한 것으로 보임 [추정]
  - 결론: 대여·혼합형도 도서관마다 목록이 다름. 받은 28곳 목록으로 대신할 수 없고 도서관마다 받아야 함
- 대여·혼합형 230곳(받지 않음 2곳 제외) 종류(이름 기준, 데이터 폴더 `library-join/candidate-kinds-2026-10-05.tsv`, 지금은 `scripts/library/rules/library_kinds.tsv`):
  - 회사 131
  - 정부·공공기관 69
  - 단체·협회 15
  - 대학 7(성균관대·연세대 구독 등)
  - 초·중·고 5
  - 공공도서관 1(전북교육청 통합도서관 구독)
  - 모름 2
  - 소장형 1,227곳은 초·중·고 906이 대부분

### 8-3. 다음에 할 일

| 할 일 | 방법 | 태그 |
|---|---|---|
| 알라딘 수집기 | [platforms.md](platforms.md) 8절 방법으로 `parse`·`page_url` 추가. 화면이 다른 15곳은 따로 조사 | [안] |
| 오디오북만 있는 사이트 표시 | 예: "경북도서관 오디오북"(알라딘). 도서관 단위 표시 칸 필요 | [확인 필요] |
| 정기 재수집 (주 1회 전체 + 매일 신간) | plan `crawl-method.md` 2절, 최신순 정렬 값은 [platforms.md](platforms.md), 규모 문제는 plan `catalog-refresh.md` 변경 기록 | [확정] 주기 / 방법 [안] |
| 상세 URL 전체 다시 확인 | 수집이 모두 끝난 뒤 `verify_detail_urls.py`(화면 종류 제한 없이) | [안] |

## 9. 상세페이지 URL

사용자 요청 "각 도서관 도서의 상세페이지 url도 꼭 있어야 해 만약 없다면 다시 크롤링해서 채워" (2026-09-30).

- `holding.detail_url`: 상세페이지 전체 URL. 트리거가 `library.base_url`(포털은 `?` 앞까지) + `detail_path`로 채움. 모든 줄에 있어야 함 [확정]
- 확인: `python -X utf8 scripts\crawl\verify_detail_urls.py` → `logs\verify-detail-urls*.tsv`
- 확인 기록:
  - 2026-09-30 01:24 전체 363건: 359건 맞음. 틀린 2건으로 YES24 종류 섞임·영문 책 번호, 강원 포털 `menu_idx` 문제를 찾아 고치고 YES24·포털 77곳을 다시 받음
  - 2026-09-30 09:5x YES24·포털: 모두 맞음(사이트 응답 없음 2건 제외)
- 다시 받기에 실패한 곳에 남은 옛 방식 줄 12줄(강서 10, 영주 1, 경산 1)은 그 도서관을 다시 받으면 `removed_at`이 찍힘

## 10. 오디오북 표시 (DB)

사용자 요청 "db에 오디오북 표시좀 해줘라" (2026-09-30).

| 칸 | 값 |
|---|---|
| `content_type` | EBOOK, AUDIOBOOK, VIDEO_BOOK, KIDS_BOOK, PAPER, UNKNOWN |
| `is_audiobook` | 1 오디오북, 0 오디오북 아님, NULL 모름 |
| `kind_check` | `TITLE_SAYS_AUDIO` = 제목에 "오디오북"이 있는데 사이트는 오디오북으로 표시하지 않음 (사람 확인용). 2026-10-01 1,373줄 |
| `content_kind` | 사이트가 적은 종류 원문 (예: 오디오북, 오디오, 전자책, 동영상북, `book/epub`, `book/aud`) |
| `kind_source` | SITE_LABEL(줄에 적혀 있음) / SITE_SECTION(어느 목록에서 왔는지로 정함) |

- 오디오북으로 보는 값: 교보 "오디오"·"오디오북", YES24 `/audiobook/` 목록, 포털 `type=ADO`, 북큐브 형식 `aud`(2026-10-01 발견, 19,417줄) [안]

```sql
SELECT l.name, h.title, h.author_raw, h.detail_url FROM holding h JOIN library l ON l.id = h.library_id
WHERE h.is_audiobook = 1 AND h.removed_at IS NULL;
```

## 11. 갈아타기 뒤 진행 기록 (2026-10-12~)

새 실행 결과는 이 표에 한 줄씩 더합니다. 시각은 이 PC 기준입니다.

| 때 | 한 일 | 결과 |
|---|---|---|
| 2026-10-12 | 갈아타기: 옛 `scripts\`와 `resume.cmd`를 `chaekcheck-data\_old-scripts-2026-10-12\`로 옮김(지우지 않음, 0-1절) | 데이터 폴더 맨 위에 `resume.cmd` 없음 |
| 2026-10-12 19:29 | 여주대(수집 102084, YES24) 빈 곳 채우기를 dev 수집기로 시작: `refill-pubdt --only 102084` (plan 사이클 13 spec R1-5) | 22분 동안 약 980줄을 읽음. 10줄 한 쪽에 약 14초라 끝까지 약 30시간 걸릴 셈 → **사용자 결정으로 멈춤** ("여주대는 빼자", 2026-10-12). 102084의 줄 수는 83,756줄 그대로(빠진 책을 더하지 못함), 수집 DB `PRAGMA quick_check` = ok. 이 시험(AC-1)은 사이클 13에서 뺐습니다 |
| 2026-10-23 15:15 | 책 소개 `intro_raw` 저장(plan 요청, 사용자 결정 2026-10-23)으로 수집기를 고친 뒤 **전체 수집 다시 시작**(`full --audio-pass --workers 40 --per-host 1 --gap 1.0 --queue-by-ip`, `--retry-failed` 없이). 08:10 시작분은 plan이 15:07에 멈춤(성공 2,758·받는 중 47). 로그 `logs\crawl-full-2026-10-23_1515.log`. 끝나면 `fetch-covers`(12절) | 진행 중 |

## 12. 표지 받기 `fetch-covers` (2026-10-23, 사이클 밖)

plan 요청서 `../chaekcheck-plan/docs/research/ebook-catalog/to-dev-fetch-covers-2026-10-23.md`(근거 `crawl-method.md` 1절 "표지", `easy/2026-10-23-cover-collection-decisions.html`·`cover-source-decisions.html`). 코드 `scripts/crawl/fetch_covers.py`(`crawl_catalog.py fetch-covers`가 넘김), 규칙 파일 `scripts/crawl/lists/fetch_cover_sources.tsv`·`fetch_cover_hosts.tsv`, 설명 [`scripts/crawl/README.md`](../../scripts/crawl/README.md) "표지 받기". 규칙 값은 [rules-index.md](rules-index.md) 1절·4절. 첫 실행 보고는 `docs/plan-feedback.md` "# 표지 받기 (2026-10-23 요청)".

- **언제**: 목록 수집(`full`)이 모두 끝난 뒤. 수집이 돌고 있으면 시작하지 않습니다(같은 서버를 두 곳에서 치므로). 가져오기와는 상관없이 돌려도 되지만, 계획(plan)은 **내 PC 서비스 DB의 지금 책**을 읽으므로 가져오기 뒤에 `--plan-only`를 한 번 더 돌리면 새 책이 더해지고 번호가 갱신됩니다(받은 표지는 그대로).
- **순서**
  1. `python -X utf8 scripts\crawl\crawl_catalog.py fetch-covers --plan-only` — 책 134만 권의 소장 줄 1,800만을 읽어 후보를 정함(요청 없음, 서비스 DB SELECT만, 수집 DB 읽기 전용). 2026-10-23 측정: 1,340,875권 21분 21초, `covers.sqlite` 635MB.
  2. `… fetch-covers --fetch-only --workers 32` — 받기. 로그 `logs\covers-<날짜>.log`에 1분마다 `progress:` 줄. 멈추려면 Ctrl+C(진행 중 요청만 끝내고 멈춤). 다시 같은 명령으로 이어 받음.
  3. `… fetch-covers --status` — 출처별 장 수, 못 받은 책 수와 마지막 오류, 서버별 대기, 실행마다 걸린 시간, 디스크 크기. `--export`로 `covers\book-covers.tsv`.
- **저장 꼴**: `chaekcheck-data\covers\covers.sqlite`의 `book_cover`(한 책 한 줄; `book_key`=ISBN 있으면 `isbn:<ISBN>`, 없으면 첫 소장 줄 `item:<수집 도서관 번호>:<책 코드>`; `book_id`는 계획할 때의 서비스 책 번호; `candidates` 후보 주소 JSON, 맨 끝 `nlk`=국립중앙도서관 조회; `status` PENDING/DONE/NONE; `source`·`cover_url`·`file`·`bytes`·`tried`·`last_error`), `run`(실행마다 시작·끝·요청 수·장 수), `meta`(계획 시각과 그때의 `catalog_import` 번호). 파일은 `covers\<sha1 앞 2자리>\<sha1(주소)>.jpg`(같은 주소는 한 파일, 폴더 256개로 나눔).
- **문제별**: 한 서버가 50번 연달아 실패(404·빈 그림은 세지 않음)하면 로그 `host …: 50 failures in a row`를 내고 그 실행에서는 그 서버를 더 묻지 않음(책은 PENDING). 다음 실행에서 다시 시도. 못 받은 책(NONE)을 다시 물으려면 `--retry-none`. 새 "표지 없음" 그림을 보면 `scripts/crawl/lists/cover_placeholders.tsv`에 해시를 더하고(`recommended_lists.image_hash`) `--retry-none`.
