# 사이클 26 책 설명 모으기 — 문서 목차

## 이 문서가 답하는 것 / 답하지 않는 것

- 답하는 것: 사이클 26(책 설명을 `catalog` 한 곳에 모으고, 표지를 S3 + CloudFront로 옮김)에서 dev가 남긴 문서가 어디 있고 각각 무엇을 답하는지.
- 답하지 않는 것: 사이클의 요구·결정(왜 하나, 무엇을 받아들이나) → plan 프로젝트 `chaekcheck-plan/docs/cycles/26-book-descriptions/`(handoff.md, spec.md, ADR-0161~0163).

## 문서

| 문서 | 누가 읽나 | 답하는 것 |
|---|---|---|
| [1-portfolio.md](1-portfolio.md) | 포트폴리오 | 이 사이클에서 내세울 만한 일만: 「문제 → 원인 → 고친 것 → 전후 숫자」 |
| [2-dev-record.md](2-dev-record.md) | dev | 단계마다 한 것과 상태, 코드·표·파일 위치, 운영에서 생긴 일과 되돌린 방법 |
| [../plan-feedback.md](../plan-feedback.md) 「# 사이클 26」 | dev·plan | 날짜순 원본 기록(모든 숫자와 명령의 출처) |

## 운영 절차 (사이클 26이 바꾼 것)

| 문서 | 무엇 |
|---|---|
| [../maintenance/8-covers.md](../maintenance/8-covers.md) | 표지를 S3에 올리고 `cover_file`로 책과 잇는 차례 |
| [../maintenance/7-foryou.md](../maintenance/7-foryou.md) 2절 | `book-texts`·`book-profiles`·`neighbours` 넣기, 첫 줄 `# export_id=` |
| [../maintenance/3-import.md](../maintenance/3-import.md) 「책 설명 정리」 | 책 목록을 다시 가져올 때 없어진 책 번호의 설명을 지우는 것 |
| [../aisearch/1-how-it-works.md](../aisearch/1-how-it-works.md) | AI 검색이 읽는 `catalog` 표(`book_search`, `book_profile`) |
