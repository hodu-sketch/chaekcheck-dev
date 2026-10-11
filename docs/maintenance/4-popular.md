# 인기 도서 받기 (출판유통통합전산망 월간 순위)

- 사이클 10. 결정과 근거는 plan ADR-0072~0075, 0077과 `docs/cycles/10-popular-books/`에 있고, 여기에는 운영 절차만 둡니다.
- 하는 일: 매달 지난달 분야별 판매 순위("전체" + 14분류, 분류마다 50권)와 분류별 판매 부수를 `bnk.kpipa.or.kr`에서 받고, 표지를 받아 폭 200px JPEG로 줄여 `popular` 스키마에 저장합니다. 화면은 `/popular`, API는 `GET /api/v1/popular-books`.

## 1. 켜고 끄기

- 설정 `chaekcheck.popular.fetch.enabled`, 환경 변수 `CHAEKCHECK_POPULAR_FETCH_ENABLED`. 기본값 true(운영)
- 꺼 두는 곳: 로컬 `.env`(`false`), 테스트(`application-test.yml`), `collection-import` 프로필
- 로컬에서 받기를 시험할 때만 켭니다. 한 번 받으면 진짜 사이트에 요청 약 30건 + 표지 최대 750장, 모두 2초 이상 간격(표지까지 최대 약 25분)
- 꺼도 공용 캐시 10분 새로 고침은 돕니다(저장된 순위를 화면에 보여 줌)

## 2. 언제 받나

- 서버가 켜질 때 한 번, 그리고 매일 05:00(Asia/Seoul)에 "받을지"를 따집니다(`FetchDecision`)
  1. 저장된 달이 없으면 받음
  2. 대상은 지난달
  3. 그 달이 다음 달 3일 00:00 뒤에 저장됐으면 안 받음
  4. 3일 이후 그 달 `FAILED`가 2번이면 안 받음
  5. 오늘이 3일 이후면 받음
- 실패하면 다음 날 05:00에 다시 받습니다. 관리자 다시 받기 명령은 없습니다(이번 범위 밖)

## 3. 받기 기록 읽기 (`popular.fetch_run`)

```sql
SELECT id, target_month, trigger, status, failure_reason, failure_detail, started_at, finished_at,
       request_count, book_count, missing_isbn_count, cover_fetched_count, cover_failed_count
FROM popular.fetch_run ORDER BY started_at DESC LIMIT 10;
```

| `failure_reason` | 뜻 | 할 일 |
|---|---|---|
| `session_failed` | 첫 화면 GET이 200이 아니거나 CSRF 토큰(`m_sCsrf`)이 없음 | 사이트 화면이 바뀌었는지 브라우저로 봄 |
| `request_rejected` | 순위·판매량 POST가 200이 아님(302 → accessDenied, 403 등) | 쿠키·토큰 방식이 바뀌었는지 봄 |
| `format_changed` | 목록(`.book-grid-item`)이 없음, 제목이 빔, `dataX1` 없음, 응답이 1MB 넘음 | 응답 HTML을 받아 선택자 비교(`RankPageParser`, `SalesCountParser`) |
| `too_few_books` | 한 분류가 50권 미만 | 사이트 쪽 일시 문제일 수 있음. 다음 날 다시 받음 |
| `network_error` | 연결 실패, 시간 초과(연결 5초, 읽기 10초) | 계속되면 시간 제한 조정 검토("전체" 순위 POST가 약 7초 걸림) |

- `FAILED`면 그 달 순위는 저장되지 않고 앞 달 순위가 그대로 보입니다
- 요청마다 INFO 로그 `kpipa <방법> <경로> list=<분류> status=<코드> took=<ms> gap=<ms>`. 실패는 `log.error`
- 표지가 몇 장 실패해도 그 달 순위는 성공입니다(`cover_failed_count`만 늘어남). 다음 받기 때 없는 표지만 다시 받습니다

## 4. 표지

- 사이트의 작은 표지(`s_` 파일)만 받고, 받자마자 폭 200px JPEG(품질 0.8)로 줄여 `popular.cover`에 저장합니다. 큰 그림·확대 보기는 만들지 않습니다(plan C10)
- 받는 크기 상한 50MB, 픽셀 수 상한 1,600만(넘으면 실패로 셈)
- 최근 두 달 순위에 쓰이지 않는 표지는 받기가 끝날 때 지웁니다

### 권리자가 표지를 내려 달라고 할 때

화면 `/about`의 연락처로 요청이 오면(plan C10) 그 책의 표지만 지웁니다. 그 책은 제목 글자 카드로 보입니다.

```sql
-- 1) 그 책의 표지 경로 찾기 (ISBN 또는 제목)
SELECT DISTINCT target_month, isbn13, title, cover_path FROM popular.ranking_entry WHERE isbn13 = '<ISBN>';
-- 2) 순위에서 경로를 비우고 표지를 지움 (한 트랜잭션)
BEGIN;
UPDATE popular.ranking_entry SET cover_path = NULL WHERE cover_path = '<cover_path>';
DELETE FROM popular.cover WHERE source_path = '<cover_path>';
COMMIT;
```

- 공용 캐시가 10분 안에 새로 고쳐져 화면에서 사라집니다. 브라우저는 표지를 30일 저장(`immutable`)하므로 이미 본 사람에게는 남아 있을 수 있습니다
- 다음 달 순위에 같은 책이 또 오면 표지를 다시 받으므로, 요청받은 표지는 따로 적어 두고 다음 받기 뒤에 다시 지웁니다 [안: 막는 목록은 아직 없음]
- 요청과 처리 날짜는 `docs/plan-feedback.md`에 남깁니다

## 5. 로컬에서 표지만 다시 줄이기

받기 시험 전에 원래 크기로 받은 표지가 남아 있을 때(개발용, 사이트를 부르지 않음):

```bash
CHAEKCHECK_RESIZE_LOCAL_COVERS=true ./gradlew test --tests '*LocalCoverResizeRunner' --rerun
```

`backend/`에서 돌립니다. `../.env`의 로컬 `db`에만 씁니다. 이미 200px 이하 JPEG인 줄은 건너뜁니다.
