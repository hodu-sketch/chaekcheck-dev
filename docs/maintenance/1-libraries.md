# 1단계: 서비스 전자도서관 목록 확인

정기 유지보수 1단계([README.md](README.md))의 입구입니다. **절차 원본은 [`docs/library-maintenance.md`](../library-maintenance.md)** 이고, 이 파일은 그 문서를 가리키기만 합니다.

- 옮기지 않고 링크로 둔 이유(dev 판단, 2026-10-04): `CLAUDE.md` "Library Research Record" 규칙과 `docs/library-site-issues.md`, 지난 조사 보고서, plan 문서들이 그 경로와 절 번호를 가리킵니다. 파일을 옮기면 이 참조가 모두 끊깁니다.
- 도서관 조사 결과와 사용자 결정은 계속 `library-maintenance.md`에 적고, 뺀 사이트와 이유는 [`docs/library-site-issues.md`](../library-site-issues.md)에 적습니다(`CLAUDE.md` 규칙).

## 어디를 보나

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

1단계가 끝나 서비스 도서관이 바뀌었으면 2단계 [2-crawl.md](2-crawl.md)에서 `crawl_catalog.py init`으로 수집기 도서관 목록을 다시 읽습니다.
