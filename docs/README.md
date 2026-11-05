# dev 문서 목차

dev 저장소의 문서를 **종류별로** 나눴습니다(2026-11-05). 무엇을 알고 싶은지에 따라 아래 표에서 폴더를 고르고, 폴더마다 있는 `README.md`에서 시작하세요.

| 알고 싶은 것 | 폴더 | 시작 문서 |
|---|---|---|
| 기능이 지금 어떻게 도나, 왜 그렇게 만들었나, 잰 숫자 | [`features/`](features/) | 기능마다 아래 |
| 운영 서버·정기 작업을 어떻게 하나(절차, 설정, 규칙) | [`maintenance/`](maintenance/) | [`maintenance/README.md`](maintenance/README.md) |
| 도서관을 찾고·넣고·빼는 조사 기록 | [`libraries/`](libraries/) | [`libraries/library-maintenance.md`](libraries/library-maintenance.md) |
| 사이클마다 무엇을 했나, plan에 무엇을 넘겼나 | [`cycles/`](cycles/) | [`cycles/README.md`](cycles/README.md) |
| 화면 설계·화면 바뀐 기록·쉬운 설명 페이지 | [`design/`](design/) | 아래 |

## features/ — 기능별 문서

| 기능 | 폴더 | 만든 사이클 | 운영 절차 |
|---|---|---|---|
| AI 책 찾기 (`/ai-search`) | [`features/aisearch/`](features/aisearch/README.md) — 지금 동작, 버전, ML 모델, 보고서(`reports/`), 원본 숫자 파일(`eval-data/`) | 24 | [`maintenance/6-aisearch.md`](maintenance/6-aisearch.md) |
| 책첵 추천 (`/picks/for-you`) | [`features/foryou/`](features/foryou/README.md) — 지금 동작, 데이터, 평가, 이력, 개선안, 시험(`experiments/`), 그림 | 23 | [`maintenance/7-foryou.md`](maintenance/7-foryou.md) |
| 책 설명 모으기·표지 S3 | [`features/book-descriptions/`](features/book-descriptions/README.md) — 요약, 개발 기록, 질의 속도 | 26 | [`maintenance/8-covers.md`](maintenance/8-covers.md) |

`features/aisearch/eval-data/`는 스크립트가 읽고 쓰는 경로입니다(예: `scripts/aisearch/build_100k.py`가 `taxonomy2.json`을 읽음). 옮길 때는 스크립트도 함께 고칩니다.

## libraries/ — 도서관 조사

- [`library-maintenance.md`](libraries/library-maintenance.md): 도서관 조사 방법·출처·결정의 원본. **도서관을 조사하기 전에 반드시 읽고, 같은 작업에서 새로 안 것을 적습니다**(CLAUDE.md 「Library Research Record」).
- [`library-site-issues.md`](libraries/library-site-issues.md): 넣지 않은 사이트와 까닭.
- [`library-left-out.md`](libraries/library-left-out.md): 서비스에 넣지 않은 도서관 목록.
- [`research/`](libraries/research/): 날짜별 조사 원자료(가입 분류 TSV, YES24·옮긴 사이트 보고서, 다시 확인 기록).

## design/ — 화면

- [`frontend-minimum.md`](design/frontend-minimum.md): 프론트엔드 최소 지식(백엔드 개발자용, 2026-09-24).
- [`screen-changes-2026-10-05.md`](design/screen-changes-2026-10-05.md)와 [`screens/2026-10-05/`](design/screens/2026-10-05/): 사이클 06 뒤 화면 변경과 캡처.
- [`seo-picks-options-2026-10-26.html`](design/seo-picks-options-2026-10-26.html): 추천도서 검색 노출 안.
- [`easy/`](design/easy/): 쉬운 말 설명 페이지(로그인 보안, 2026-10-04).

## 다른 곳

- 버전별 바뀐 것: 저장소 맨 위 [`CHANGELOG.md`](../CHANGELOG.md).
- 설계 원본(ADR, 사이클 인계서): plan 저장소 `chaekcheck-plan/docs/` — 여기서 고치지 않습니다.
- 스크립트 사용법: 각 `scripts/<폴더>/README.md`와 파일 머리말.

## 옮긴 경로 (2026-11-05)

| 예전 | 지금 |
|---|---|
| `docs/aisearch/` | `docs/features/aisearch/` |
| `docs/reports/ai-search-eval/` | `docs/features/aisearch/eval-data/` |
| `docs/cycle23-foryou/` | `docs/features/foryou/` |
| `docs/cycle26-book-descriptions/` | `docs/features/book-descriptions/` |
| `docs/library-maintenance.md`, `library-site-issues.md`, `library-left-out.md` | `docs/libraries/` 아래 같은 이름 |
| `docs/library-research/` | `docs/libraries/research/` |
| `docs/plan-feedback.md` | `docs/cycles/plan-feedback.md` |
| `docs/cycle08-interim-for-plan.md` | `docs/cycles/08/interim-for-plan.md` |
| `docs/cycle09-for-collection.md` | `docs/cycles/09/for-collection.md` |
| `docs/reports/cycle11-import-measurement.html` | `docs/cycles/11/import-measurement.html` |
| `docs/frontend-minimum.md`, `docs/screen-changes-2026-10-05.md`, `docs/screens/`, `docs/easy/`, `docs/reports/seo-picks-options-2026-10-26.html` | `docs/design/` 아래 |
| `docs/reports/README.md`와 「옮김」 안내 파일 5개(`docs/cycle23-session-handoff.md`, `docs/reports/cycle23-*.md`, `docs/reports/foryou-*.md`) | 지움. 이 목차와 각 폴더 README가 대신함 |
