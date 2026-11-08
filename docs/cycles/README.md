# 사이클별 기록

사이클마다 dev가 남긴 기록이 어디 있는지 모은 색인입니다. 설계 원본(범위, 인계서, ADR)은 계획 저장소 [hodu-sketch/chaekcheck-plan](https://github.com/hodu-sketch/chaekcheck-plan)의 `docs/cycles/<번호-이름>/`에 있고(아래 표의 폴더 이름을 누르면 열림), dev는 여기서 그 결과를 plan에 넘깁니다.

- **[`plan-feedback.md`](plan-feedback.md)**: 사이클 01부터 2026-11-05까지 plan에 넘긴 날짜 순 원기록입니다(사용자 발언, 결정, 그날의 숫자). 사이클마다 `# 사이클 NN` 절이 있고, 사이클 밖 요청은 `# <주제> (날짜, …)` 절입니다. plan 문서가 이 파일을 행 번호로 가리키므로 **더 쓰지 않고 그대로 둡니다**(동결, plan 결정 2026-11-05).
- **`NN/plan-feedback.md`**: 2026-11-05부터 plan에 넘기는 기록은 사이클 번호 폴더에 씁니다(예: `24/plan-feedback.md`, `28/plan-feedback.md`). 열린 사이클(20·24·28)도 이어지는 기록은 여기에 씁니다. 지금 열린 사이클 목록과 규칙은 plan [`docs/current/handoff.md`](https://github.com/hodu-sketch/chaekcheck-plan/blob/main/docs/current/handoff.md).
- **번호 폴더(`08/`, `09/`, `11/`, `12/`)**: 그 사이클에만 쓰인 따로 된 문서.
- **기능 폴더(`../features/`)**: 사이클이 만든 기능의 정리된 문서(지금 동작, 측정, 이력). 날짜 순 원기록은 plan-feedback, 정리본은 기능 폴더입니다.

| 사이클 | 이름 (plan 폴더) | plan-feedback 절 | 따로 된 문서 |
|---|---|---|---|
| 01 | 전자책 대출 조회 검색 ([`01-ebook-availability-search`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/01-ebook-availability-search)) | `# 사이클 01` | |
| 02 | 도서관 늘리기 ([`02-library-expansion`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/02-library-expansion)) | `# 사이클 02` | [도서관 조사](../libraries/library-maintenance.md) |
| 03 | 검색 조건 ([`03-search-conditions`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/03-search-conditions)) | `# 사이클 03` | |
| 04 | AND 검색 ([`04-and-search`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/04-and-search)) | `# 사이클 04` | |
| 05 | 로그인·계정 ([`05-login-account`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/05-login-account)) | `# 사이클 05` | [로그인 보안 쉬운 설명](../design/easy/2026-10-04-login-security.html) |
| 06 | 내 도서관 ([`06-my-libraries`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/06-my-libraries)) | `# 사이클 06`, `# 사이클 06 마무리 확인`, `# 사이클 밖: 화면 디자인` | [화면 변경](../design/screen-changes-2026-10-05.md), [캡처](../design/screens/2026-10-05/) |
| 07 | 후보 도서관 ([`07-candidate-libraries`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/07-candidate-libraries)) | `# 사이클 07` | |
| 08 | 손질·바꿔 끼우기·메모리 ([`08-polish-swap-memory`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/08-polish-swap-memory)) | `# 사이클 08` | [중간 요약](08/interim-for-plan.md) |
| 09 | 도서관 이름 ([`09-library-naming`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/09-library-naming)) | `# 사이클 09` | [수집 세션에 전달](09/for-collection.md) |
| 10 | 인기 도서 ([`10-popular-books`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/10-popular-books)) | `# 사이클 10`, `# 사이클 10 덧붙임` | |
| 11 | 가져오기 따로 ([`11-separate-import`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/11-separate-import)) | `# 사이클 11` | [넣기 측정](11/import-measurement.html) |
| 12 | 묶기 규칙 ([`12-grouping-rules`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/12-grouping-rules)) | `# 사이클 12` | |
| 13 | 수집기를 dev로 ([`13-crawler-to-dev`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/13-crawler-to-dev)) | `# 사이클 13` | [수집 절차](../maintenance/2-crawl.md) |
| 14 | 사서 추천 ([`14-librarian-picks`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/14-librarian-picks)) | `# 사이클 14` | [추천 운영](../maintenance/4-picks.md) |
| 15 | 이용 기록 ([`15-usage-log`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/15-usage-log)) | `# 사이클 15` | [이용 기록 운영](../maintenance/5-usage-log.md) |
| 16 | 공지·정책 ([`16-notices-and-policies`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/16-notices-and-policies)) | `# 사이클 16` | [사이트 글 운영](../maintenance/5-sitecontent.md) |
| 17 | 첫 배포 ([`17-first-deploy`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/17-first-deploy)) | `# 사이클 17: EC2 시험 배포…` | [운영 문서](../maintenance/README.md) |
| 18 | 빈 소장 도서관 ([`18-empty-library-holdings`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/18-empty-library-holdings)) | `# 사이클 18` | |
| 19 | 저자 꺾쇠 ([`19-author-angle-brackets`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/19-author-angle-brackets)) | `# 사이클 19` | |
| 20 | 조회 상태·확장 ([`20-lookup-status-and-extension`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/20-lookup-status-and-extension)) | `# 사이클 20`, `# 사이클 webshare` | [플랫폼](../maintenance/platforms.md) |
| 21 | 추천 도서 ([`21-recommended-books`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/21-recommended-books)) | `# 사이클 21` | |
| 22 | 첫 화면 사용성 ([`22-first-screen-usability`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/22-first-screen-usability)) | `# 사이클 22`, `# 사이클 22 5-A단계` | |
| 23 | 책첵 추천 ([`23-for-you-picks`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/23-for-you-picks)) | `# 사이클 23`, `# 사이클 23 (이어서…)`, `# 머신러닝 보강`, `# 사이클 23 사전 시험`, `# 사이클 23 소개 시험…` | [기능 문서](../features/foryou/README.md), [세션 인계(그때 그대로)](../features/foryou/archive/2026-10-24-session-handoff.md) |
| 24 | AI 책 찾기 ([`24-ai-book-search`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/24-ai-book-search)) | `# 사이클 24`, `# AI 검색 …` 절들 | [기능 문서](../features/aisearch/README.md) |
| 26 | 책 설명 모으기 ([`26-book-descriptions`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/26-book-descriptions)) | `# 사이클 26`, `# 사이클 26 질의 속도`, `# 사이클 26 배포 1 전후…` | [기능 문서](../features/book-descriptions/README.md) |
| 27 | 무중단 배포 ([`27-zero-downtime-deploy`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/27-zero-downtime-deploy)) | `# 사이클 27` | [운영 문서](../maintenance/README.md) |
| 28 | 바쁠 때 책 목록 유지 ([`28-busy-keeps-book-list`](https://github.com/hodu-sketch/chaekcheck-plan/tree/main/docs/cycles/28-busy-keeps-book-list)) | [`28/plan-feedback.md`](28/plan-feedback.md) | |

사이클 25는 없습니다(plan 번호를 건너뜀). 이름은 plan 폴더 이름을 우리말로 옮긴 것이고, 정확한 범위는 plan 폴더의 `scope.md`에 있습니다.
