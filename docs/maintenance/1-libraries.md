# 1단계: 도서관 목록 확인 (등록 도서관·후보 도서관)

정기 유지보수 1단계([README.md](README.md))의 입구입니다. **등록 도서관 확인 절차 원본은 [`docs/library-maintenance.md`](../library-maintenance.md)** 이고, 이 파일은 그 문서를 가리킵니다. 그 문서에 없는 "후보 도서관 찾기"와 "구독 규칙·마감 확인"은 이 파일 아래 절이 절차 원본입니다(plan ADR-0047 요청서 D, 2026-10-04).

- 옮기지 않고 링크로 둔 이유(dev 판단, 2026-10-04): `CLAUDE.md` "Library Research Record" 규칙과 `docs/library-site-issues.md`, 지난 조사 보고서, plan 문서들이 그 경로와 절 번호를 가리킵니다. 파일을 옮기면 이 참조가 모두 끊깁니다.
- 도서관 조사 결과와 사용자 결정은 계속 `library-maintenance.md`에 적고, 뺀 사이트와 이유는 [`docs/library-site-issues.md`](../library-site-issues.md)에 적습니다(`CLAUDE.md` 규칙).
- 수집 일 나눔 [확정] (사용자, 2026-10-04, plan `docs/research/maintenance/collection-areas.md`): ① 도서관 정보(어느 도서관이 있나, 화면 종류, 가입 자격, 구독 규칙, 마감 확인) = 이 1단계 / ② 도서관-책 = [2-crawl.md](2-crawl.md) / ③ 가져오기 = [3-import.md](3-import.md).

## 도서관 목록 두 가지 [확정] (사용자, 2026-10-04)

| 이름 | 어디에 있나 | 수 (2026-10-04) | 검색에 나오나 |
|---|---|---|---|
| **등록 도서관** | 서비스 DB `library` (dev seed, `library-maintenance.md` 1절) | 278곳 | 나옴 |
| **후보 도서관** | 수집 DB(`chaekcheck-data\ebook-catalog.sqlite`) `library` 번호 100001~. 웹러브 북스 기관 목록에서 찾음 | 2,738곳 | 안 나옴. 책은 모음 |

- 이전에 쓰던 이름 "서비스 도서관"은 등록 도서관, "웹러브 북스 기관"·"수집 전용 기관"은 후보 도서관과 같은 뜻입니다.
- 후보 도서관을 등록 도서관으로 옮기는 일은 backlog 다음 사이클 후보입니다(plan `collection-areas.md` 2절 3번, `periodic-tasks.md` 5절).

## 등록 도서관 확인: 어디를 보나

| 하려는 일 | `library-maintenance.md` |
|---|---|
| 시작 전 확인, 데이터 구조(표·칸, 화면 종류 `platform_type`, seed 파일) | 0·1절 |
| 호출 예절 | 2절 |
| 확인 도구 (`scripts/library/`) | 3절 |
| 전국 전자도서관 목록 찾기 (출처, 주소 모양, 후보 확정) | 4-0~4-7절 |
| 가입 자격, 가입 방법·가입 페이지 | 4-8, 4-10절 |
| 운영 중 바뀐 것을 알아차리는 신호 (서비스 로그) | 4-9절 |
| 책 번호 규칙 점검 | 4-11절 |
| 도서관 추가 / 수정·끄기·삭제 | 5 / 6절 |
| Liquibase와 로컬 DB, 확인 | 7·8절 |
| 사람에게 물을 것, 결과 기록 | 9·10절 |
| 확인 도구 결과별 처리, 알려진 사이트 특이점 | 11·12절 |
| 지금까지의 작업 기록, 사용자가 직접 확인할 목록 | 13·14절 |

1단계가 끝나 등록 도서관이 바뀌었으면 2단계 [2-crawl.md](2-crawl.md)에서 `crawl_catalog.py init`으로 수집기 도서관 목록을 다시 읽습니다.

## 후보 도서관 찾기

웹러브 북스 기관 목록(교보는 교보 공식 기관 목록)의 사이트를 한 번씩 열어 보고(열리는지, 화면 종류, 권수), 열린 곳을 수집 DB `library`에 후보 도서관으로 넣는 절차입니다. 원문: plan `docs/research/ebook-catalog/crawl-runbook.md` 8-1절 (2026-10-03), 같은 문서 4절 "dev에 반영할 것" (2026-10-04).

- **도구 위치**: 아직 `C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\scripts\`에 있습니다(git 밖). 3단계(ADR-0047 요청서 B·C, 사용자가 수집기를 멈춘 뒤)에 `probe_*.py`는 dev `scripts/library/`(예: `scripts/library/candidates/`) [안], `import-probe`·`import-bookcube`는 수집기와 함께 `scripts/crawl/` [안]로 옮깁니다. 옮긴 뒤 이 절의 경로를 고칩니다.
- 웹러브 북스 목록 https://ebook.okbari.net/library 는 제3자 자료(수집 기준일 2026-02-06으로 보임)라 그대로 믿지 않고 2번에서 한 번씩 확인합니다. 모으는 방법은 `library-maintenance.md` 4-4절.

| 단계 | 교보 | YES24 | 북큐브 | 알라딘 |
|---|---|---|---|---|
| 1. 후보 목록 | plan `docs/cycles/02-library-expansion/data/kyobo-institutions.tsv` (교보 공식 목록, 2,000줄 → 사이트 1,937곳) | 웹러브 북스 기관 탭을 브라우저로 모음 → `chaekcheck-data/other-platforms/okbari-2026-10-03/part1~3.tsv`, `yes24-candidates.tsv`(시험·개발용 이름 제외 775곳) | 같은 방법 → `bookcube-candidates.tsv`(495곳) | 같은 방법 → `okbari-2026-10-03/aladin-candidates.tsv`(시험용 2곳 제외 163곳) |
| 2. 한 번씩 확인 (열리는지, 화면 종류, 권수) | `scripts/probe_kyobo_institutions.py` → `other-platforms/kyobo-institutions-probe-2026-10-03.tsv`. 첫 화면 `library_model`(소장·대여·혼합)도 적음 | `scripts/probe_yes24.py` → `okbari-2026-10-03/yes24-probe.tsv`. V1·V2·V3를 가림 | `scripts/probe_other_platforms.py` → `okbari-2026-10-03/bookcube-probe.tsv` | `scripts/probe_aladin.py` |
| 3. 수집 DB 도서관 목록에 넣기 | `crawl_catalog.py import-probe --probe … --source okbari-2026-10-03` | 같음 | `crawl_catalog.py import-bookcube --probe … --source okbari-2026-10-03` | `import-probe`. 사이트마다 다른 전자책 메뉴 코드는 `base_url` 뒤 `?nav_code=…`로 저장 |
| 4. 받기 | [2-crawl.md](2-crawl.md): `full --retry-failed --platforms …` 또는 `resume.cmd` | 같음 | 같음 | 같음 |

- 넣은 후보 도서관은 `library.source = okbari-2026-10-03`, id 100001부터. 이미 있는 주소는 3번에서 건너뜁니다. 2026-09-30에 먼저 넣은 북큐브 134곳은 `source=okbari-2026-09-30`([2-crawl.md](2-crawl.md) 5절).
- 교보 대여·혼합형(2026-10-04 넣음, 232곳, 11곳은 이미 있음): `kyobo-institutions-probe-2026-10-03.tsv`에서 `model`이 대여·혼합인 줄만 골라 `import-probe`, `source=okbari-2026-10-03-rental`. 모두 IP 103.182.126.192 한 대라 받는 속도는 [2-crawl.md](2-crawl.md) 2-1절 "서버(줄)별 동시 요청 수".
- 확인 스크립트는 모두 결과를 한 줄씩 바로 저장하고, 다시 돌리면 이미 확인한 곳을 건너뜁니다. 도구의 백그라운드 작업은 30분쯤에 끊기므로 긴 작업은 PowerShell `Start-Process`로 따로 띄웁니다(2026-10-03 교보 확인이 끊겨 결과를 잃은 뒤 고침).
- 확인 스크립트는 서버마다 1건·1초 고정이고 `--workers`·`--host-slots` 값을 받지 않습니다. 수집과 같은 서버를 동시에 치지 않게 띄우기 전에 서버가 겹치는지 봅니다([2-crawl.md](2-crawl.md) 2절).
- 결과 TSV(`chaekcheck-data\other-platforms\*.tsv`, 기관 이름·주소·권수)를 git에 넣을지는 3단계에서 dev가 크기와 내용을 보고 정합니다 [확인 필요: 크기와 내용 확인].
- 진행 숫자(플랫폼별 열림·오류·넣은 곳)는 plan `crawl-runbook.md` 8-2절에 남습니다.
- 가입 자격: 후보 도서관은 찾을 때 가입 자격을 조사하지 않았습니다. `CLAUDE.md`는 도서관을 찾거나 더할 때 가입 자격을 같은 작업에서 조사하라고 합니다(`library-maintenance.md` 4-8·4-10절). 후보 도서관에 이 규칙을 언제 적용할지(지금 / 등록 도서관으로 옮길 때) [확인 필요: 사용자 결정].

## 구독 규칙·마감 확인

교보 구독형 사이트(대여·혼합형)가 1인 월 권수, 월·연 예산 마감, 다시 열리는 날을 공지했는지 보는 절차입니다. 원문: plan `crawl-runbook.md` 4절 "dev에 반영할 것" (2026-10-04), 조사 기록과 결과는 plan `docs/research/library-limits/research.md` 3-1절, 알리는 방법 결정은 같은 문서 4절.

- **도구**: `probe_kyobo_limits.py`. 아직 `chaekcheck-data\scripts\`에 있고(git 밖), 3단계에 dev `scripts/library/`로 옮깁니다 [안]. 실행 인자는 옮길 때 적습니다 [확인 필요].
- 읽는 곳: 교보 구독형 사이트의 `/popup/popupMain.json`(첫 화면 팝업), `/main/firstBoardBlbrSrmb.json`, `/board/listBoard.json`(공지 게시판). **로그인 없이, 3초 간격**.
- 팝업·공지 본문은 대개 그림이라 제목과 날짜를 읽고, 필요한 그림은 내려받아 읽습니다(예: 기장 `https://ss21.kr:442/images1/gijanglib_20261001.jpg`, 서구 `https://ss21.kr:442/images1/seogulib_20260923.jpg`).
- 결과: `chaekcheck-data\other-platforms\kyobo-limits-2026-10-04.tsv`. 2026-10-04에 등록 도서관 교보 구독형 16곳 + 후보 도서관 교보 대여·혼합형 232곳 = 248곳을 확인했고, 마감 관련 제목이 있던 곳은 강동구립·서구 두 곳(plan `library-limits/research.md` 3-1절).
- **주의: "공지 없음"은 "이용 가능"이 아닙니다.** 월 예산 소진은 로그인하고 대출을 눌러야 보입니다("이용기관의 월간 대여 횟수가 초과되었습니다"). 그래서 이 확인은 마감 공지가 있는지만 봅니다.
- 교보 구독형 사이트에는 매달 말 "구독형 디지털도서관 ○월 뉴스레터"가 올라옵니다. 뉴스레터가 끊긴 것(제주한라)이 마감 신호인지는 확인 못 함 [확인 필요].
- 언제: 1단계를 할 때(도서관별 1인 월 권수와 한 해 운영 기간은 이용안내에서 읽음), 마감 공지는 정기 점검 때 사람이(나중에는 수집기가) 확인 [확정] (사용자 승인, 2026-10-04, plan `library-limits/research.md` 4절). 값을 어디에 둘지(도서관 정보 칸 / 따로 "구독 규칙")는 사이클에서 정함 [확인 필요: 사이클에서 결정].
- 등록 도서관에서 마감으로 끈 곳의 재개 확인 날짜는 `library-maintenance.md` 6-2절(예: id 131 서구 구독 2027-01).
