# 정기 유지보수: 도서관 목록 → 책 목록 → 서비스 반영

- 주기적으로 하는 유지보수 작업의 **절차 원본**입니다(plan ADR-0047, 2026-10-04). 원칙: 절차의 원본은 dev, 조사 기록·근거·결정은 plan [확정] (사용자, 2026-10-01).
- 옮겨 온 곳: plan `docs/research/maintenance/periodic-tasks.md` 0~4절 (2026-10-04). 그 문서의 5절(웹러브 북스 기관을 서비스 도서관으로 옮기기)과 6절(확인 필요)은 plan에 남습니다.
- plan 저장소 위치: `C:\Users\yujun\Documents\Claude\Projects\chaekcheck-plan\` (아래 "plan `…`"은 이 폴더 기준 경로). 문서 색인은 plan `docs/reference-index.md`.
- 주기와 순서는 모두 [안]입니다. 정하는 곳은 주기 갱신 사이클입니다(별도 사이클 [확정], plan `docs/research/ebook-catalog/catalog-refresh.md` 머리말).

## 0. 한눈에

| 단계 | 하는 일 | 주기 | 절차 | 실행 도구 |
|---|---|---|---|---|
| 1 | 서비스 전자도서관 목록 확인 (새 곳, 바뀐 주소, 꺼진 곳, 가입 자격, 책 번호 체계) | [안] 월 1회 + 운영 로그 신호가 있을 때 | [1-libraries.md](1-libraries.md) | `scripts/library/` (`CheckKyoboSites.java`, `CheckYes24Sites.java`, `check_code_spaces.py` …) |
| 2 | 도서관마다 전자책 목록 받기 | 주 1회 전체 + 매일 신간 [확정] (plan `crawl-method.md` 2절). **신간 수집 명령은 아직 없음** → 지금은 전체만 | [2-crawl.md](2-crawl.md), 플랫폼별 방법 [platforms.md](platforms.md) | `chaekcheck-data\resume.cmd` (`crawl_catalog.py`) |
| 3 | 받은 목록을 서비스 DB로 가져오기 (정리·같은 책 묶기) | [안] 2단계 전체 수집이 끝날 때마다 | [3-import.md](3-import.md) | `backend/`에서 `./gradlew bootRun` 프로필 `catalog-import` |
| 4 | (자동) 사용자가 책을 찾으면 서비스 DB에서 찾고, 도서관 상세 페이지로 대출 상태를 실시간 조회 | 늘 | 아래 4절 | 서비스(`catalog`, `availability` 모듈) |

1 → 2 → 3 순서로 이어집니다. 1에서 서비스 도서관이 바뀌면 2에서 그 도서관을 받고, 3에서 다시 가져와야 서비스 검색에 반영됩니다. 4는 사람이 돌리지 않지만, 4에서 나는 오류(상세 페이지를 못 읽음)가 1을 할 신호가 됩니다.

## 1. 서비스 전자도서관 목록 확인

절차: [1-libraries.md](1-libraries.md) → [`docs/library-maintenance.md`](../library-maintenance.md). 볼 곳과 순서 [안]:

1. 목록 출처 다시 보기: 교보 공식 목록, 공공데이터포털 전국도서관표준데이터, 통합 포털 목록, 웹러브 북스 (4-0~4-5). 교보 공식 목록 재확인은 요청 약 400건·20분, plan `docs/cycles/02-library-expansion/data/kyobo-institutions.tsv`와 비교 (4-1, 4-9)
2. 주소 모양으로 화면 종류 나누기, 최종 주소 확정 (4-6, 4-7, 3절 도구)
3. 가입 자격·방문 가입 판정 (1-1, 1-2, 4-8, 4-10). 사람이 정한 값은 `scripts/library/join_overrides.tsv`가 마지막에 이김
4. 책 번호 체계 점검: `check_code_spaces.py` → 기준값과 비교. 크게 바뀌거나 새 화면 종류가 생기면 사용자에게 알림 [확정] (사용자, 2026-09-30) (4-11)
5. 날짜가 정해진 것 확인: 꺼 둔 도서관 id 59·131 재개 확인 2027-01 (6-2), 중간 인증서 만료 2027-05-21·2027-11-02 ([`docs/library-site-issues.md`](../library-site-issues.md) 3절)
6. 남은 확인 목록: `library-maintenance.md` 14절, plan `docs/reference-index.md` 6절 "도서관 목록·분류"

운영 중 신호(3xx, 읽기 실패, 시간 초과)별로 할 일은 `library-maintenance.md` 4-9절에 있습니다.

## 2. 도서관마다 전자책 목록 받기

절차: [2-crawl.md](2-crawl.md).

1. `chaekcheck-data\resume.cmd` 실행. 끝난 도서관은 넘어가고, 받던 도서관은 마지막으로 저장한 쪽 다음부터 받음 (2-crawl 3절)
2. 줄 세우기: IP 하나에 한 줄, 한 줄에 동시 1건·1초 간격. `chaekcheck-data\queue-by-ip.txt`가 있으면 IP 기준, 없으면 도메인 이름 기준 [확정] (사용자, 2026-10-04) (2-crawl 2절)
3. 상태 보기: `python -X utf8 scripts\crawl_catalog.py status`, 로그 `chaekcheck-data\logs\crawl-*.log` (2-crawl 3·5절)
4. 실패한 도서관: 2-crawl 6절 "문제별 대처". 막힌 사이트는 우회하지 않고 `crawl_skip`으로 표시 (2-crawl 5-1절)
5. 끝난 뒤: 상세 주소 확인 `scripts\verify_detail_urls.py` (2-crawl 9절)

화면 종류별 목록 주소와 예외는 [platforms.md](platforms.md), 원칙은 plan `docs/research/ebook-catalog/crawl-method.md`에 있습니다. 지금 진행 상태(어디까지 받았나)는 plan `crawl-runbook.md` 1·8절에 남습니다.

## 3. 받은 목록을 서비스 DB로 가져오기

절차: [3-import.md](3-import.md). 수집이 도는 중에도 돌려도 됩니다(근거는 3-import 1절).

1. 먼저 세기만(dry run) → 2. 요약 숫자를 지난번과 비교 → 3. 실제 가져오기 → 4. 검색 몇 가지로 확인

다시 가져오면 책 번호가 새로 매겨집니다(3-import 6절).

## 4. (자동) 서비스에서 책 찾기와 대출 조회

- 흐름: plan `docs/current/flows.md` 흐름 6(책 목록 검색과 바로 보기), 흐름 7(책 먼저 고르기 → 책 화면). 구현: `BookSearchController`, `BookAvailabilityStreamController`, `BookAvailabilityBatchStreamController` (사이클 04)
- 대출 상태는 저장하지 않고 사용자가 볼 때 도서관 상세 페이지를 읽음 [확정] (plan `crawl-method.md` 2절). 같은 호스트 1건·1초, 캐시 3분
- 유지보수에서 볼 것: 상세 페이지를 못 읽는 도서관이 늘면 화면이 바뀐 것 → 1단계(`library-maintenance.md` 4-9절, 12절 화면 종류별 상세 형식)

## 체크리스트 (한 바퀴)

- [ ] 1단계: `library-maintenance.md` 4절 순서대로 확인, 바뀐 도서관은 seed 추가·수정(5·6절), 결과를 13절 작업 기록과 `docs/plan-feedback.md`에 남김
- [ ] 2단계: `resume.cmd` → `status`로 모든 도서관이 끝났는지(실패·받지 않음은 이유 확인) → `verify_detail_urls.py`
- [ ] 3단계: dry run → 숫자 비교 → 실제 가져오기 → 검색 확인 → 결과 숫자를 `3-import.md` 5절 표에 더함
- [ ] 4단계: 서비스 로그에서 상세 페이지 읽기 실패가 늘었는지
