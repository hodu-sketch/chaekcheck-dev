# 사용자 행동 기록 (usagelog) 운영 메모

사이클 15에서 만든 사용 기록의 운영 절차입니다. 결정과 근거는 plan `docs/cycles/15-usage-log/`(spec, design-delta)와 ADR-0096·0097·0102에 있고, 여기에는 절차만 둡니다.

## 1. 켜고 끄기

- 설정: `chaekcheck.usagelog.enabled`(환경 변수 `CHAEKCHECK_USAGELOG_ENABLED`). 기본값은 꺼짐(false)입니다.
- 첫 배포 때는 꺼 둡니다. 처리방침에 수집 항목이 실린 공개일에 켭니다(spec R3-6).
- 켜고 끄려면 `.env`(또는 배포 비밀값)의 값을 바꾸고 서비스를 다시 시작합니다.
- 꺼져 있을 때
  - 아무것도 기록하지 않고, 로그인 상태 판별도 하지 않습니다.
  - `POST /api/v1/usage-events`는 그대로 204입니다.
  - 04:30 정리와 요청마다 남는 서버 로그 줄은 그대로입니다.
- 정상 종료(`SIGTERM`)하면 메모리에 남은 기록을 쓰고 끝납니다. 강제 종료하면 최대 5초 분량이 사라질 수 있습니다.

## 2. 표 아홉 (schema `usagelog`)

| 표 | 한 줄 | 주요 칸 |
|---|---|---|
| `search_event` | 검색 하나 | `kind`, 검색어 셋, 검색 방식, `scope`·`visit`, 지역·고른 도서관, `result_count` |
| `book_view` | 책 페이지 하나(404는 없음) | `book_id`, 지역·고른 도서관, `account_id`·`visit_key` |
| `search_more` | 더 보기 하나 | `kind`, `seq`, `library_id`, 검색 조건 칸 전부 |
| `library_click` | 도서관 링크 누름 | `library_id`, `place`, `screen`, `book_id`, `account_id`·`visit_key` |
| `screen_event` | 화면 행동 | `kind`와 그 종류의 칸만, `account_id`·`visit_key` |
| `library_response_daily` | (서울 날짜, 도서관, 출처)마다 하루 한 줄 | 결과별 수, `ok_ms_total`, `ok_ms_max` |
| `rejected_event` | 받아들이지 않은 사용 알림 | `reason`, `raw_values`(2KB까지) |
| `dropped_batch` | 버린 묶음 | `row_count`, `reason` |
| `ai_search_event` | AI 책 찾기 하나(사이클 24, ADR-0152). **질문 글·IP·`visit_key`·`account_id` 없음** | `received_at`, `elapsed_ms`, `turn_no`(1~20, 웹이 셈), `keyword_result`(ok·failed·timeout), `title_like`, `pick_result`(ok·no_match·no_text·partial·skipped), `candidate_count`, `picked_book_ids`(최대 8), `input_tokens`·`output_tokens`(1·2차 합) |

### `search_event`

- `kind`
  - `catalog`: 수집 목록 검색 1쪽입니다.
  - `direct`: 직접 찾기의 첫 묶음입니다.
- `result_count`
  - 수집 목록 검색은 결과 수(`totalCount`)입니다.
  - 직접 찾기는 첫 묶음에서 센 줄 수입니다. 첫 묶음 전에 화면을 닫으면 NULL입니다.
- 검색 방식 칸
  - 수집 목록 검색: `exact`(정확히 찾은 칸 목록), `roles`(더 고른 역할), `sort`
  - 직접 찾기: `filter`

### `search_more`

- `kind`와 `seq`
  - `catalog_next_page`: `seq`는 쪽 번호입니다.
  - `direct_next_batch`: `seq`는 묶음 번호입니다(2부터).
  - `library_more`: 도서관별 더 보기입니다. `seq`는 쪽 번호이고, `library_id`가 있습니다.
- 직접 찾기의 더 보기에서는 `scope`·`visit`·지역·고른 도서관을 화면이 기록용으로 붙여 보냅니다. 그래서 빠질 수 있습니다(NULL 또는 빈 배열).

### 공통 칸

- `login_status`
  - `signed_in`: 우리 서명이 있는 로그인 쿠키입니다(만료됐어도 해당).
  - `signed_out`: 쿠키가 없거나 틀렸습니다.
  - `login_off`: 로그인 기능이 꺼져 있습니다.
  - 권한과는 관계없습니다.
- `client_ip`
  - nginx가 넣은 `X-Real-IP`이고, 30일 뒤 비웁니다.
  - 배포에서 사용자 IP를 되살리는 설정(`real_ip_header`)은 사이클 17이 정합니다.
- `account_id`·`visit_key` (ADR-0134, `usagelog/005`; `book_view`·`library_click`·`screen_event`에만, 검색 표 둘에는 없음)
  - `account_id`: 로그인한 회원의 번호입니다(로그인 쿠키의 서명이 맞으면 만료됐어도). 비로그인·로그인 꺼짐이면 NULL, FK 없음, 색인 없음.
  - `visit_key`: 브라우저 탭이 열릴 때 만든 32자 난수(`sessionStorage`, 쿠키 아님, 탭마다 다름)입니다. 책 상세 GET의 헤더 `X-Visit-Key`, 사용 알림 본문의 `visitKey`로 옵니다. 형식(영숫자 16~64자)이 틀리면 NULL로 두고 거절하지 않습니다.
  - 탈퇴(`AccountDeleted`)하면 `usagelog`가 세 표의 그 회원 `account_id`를 NULL로 비웁니다(줄은 남김, `UsageLogAccountDeletedCleanup`, 로그인 켜짐일 때만 있는 listener). "맞춤 추천 끄기" 설정은 AI 추천 3단계에서 만듭니다.
  - 운영 통계 SQL은 `account_id`를 읽지 않습니다. 같이 본 책 계산은 `visit_key`만 쓰고 `client_ip`를 읽지 않습니다.

### `library_click`

- `place`
  - `result_card`: 결과 카드
  - `join_link`: 가입 링크
  - `join_guide`: 가입 안내 보기
  - `home_page`: 도서관 첫 화면
  - `logo`: 로고
  - `summary_bar`: 직접 찾기 요약 막대의 도서관 이름
- `screen`
  - `direct_results`: 직접 찾기(책 번호 없음)
  - `catalog_results`: 수집 목록 검색
  - `book_page`: 책 페이지
  - `picks`: 사서 추천 창
  - `my_libraries`: 내 도서관(로고만, 책 번호 없음)
- 가운데 버튼 클릭도 셉니다. 길게 눌러 연 메뉴는 세지 않습니다.

### `screen_event`

- `picks_list_view`: `picks_category`. 화면 가운데 분야가 바뀔 때마다 하나 남습니다(처음 연 분야 포함).
- `picks_book_view`: `picks_category`(그 책의 분야), `book_id`
- `region_change`: `residence_region_ids`, `work_region_ids`. 저장에 성공했을 때만 남습니다.
- `my_library_add`, `my_library_remove`: `library_id`. 저장에 성공했을 때만 남습니다.

### `library_response_daily`

- `source`
  - `direct_search`: 직접 찾기의 첫 묶음·다음 묶음·도서관별 더 보기입니다.
  - `holding_fill`: 책 페이지·수집 목록 결과·사서 추천 창의 대출 수 채우기입니다.
  - `extension`·`server_for_extension`: 확장 모드(사이클 20 2·3단계)의 값입니다. `usagelog/004`가 미리 허용해 두었고, 1단계 코드는 아직 쓰지 않습니다.
- 결과
  - `ok`: 성공
  - `failed`: 오류
  - `timeout`: 요청 자체의 시간 초과이거나, 묶음 마감 뒤에 온 답
  - `skipped`: 시작 마감까지 보내지 못한 후보
  - `body_too_large_count`(사이클 20, `usagelog/004`): 도서관 응답이 본문 상한(1MB, 압축본·풀었을 때 모두)을 넘어 실패한 수입니다. 같은 건이 `failed_count`에도 들어갑니다. 상한은 `chaekcheck.availability.max-body-size`([settings-index.md](settings-index.md)). 이 수가 쌓이는 도서관은 상한을 올릴지 판단하는 근거이고, 서버 로그에는 `body_too_large library=… url=… receivedBytes=… at=…` 한 줄이 같이 남습니다.
- 셈 규칙
  - 조회 서버를 바꿔 다시 보내도 한 번으로 셉니다. 시간은 처음 보낸 때부터 마지막 답까지입니다.
  - 3분 캐시에서 나온 답은 도서관에 보낸 요청이 아니라서 세지 않습니다.
  - 평균은 볼 때 `ok_ms_total / ok_count`로 계산합니다.

### `rejected_event.reason`

| 값 | 뜻 |
|---|---|
| `malformed` | JSON이 아님, 비었음, 8KB 넘음 |
| `unknown_type` | `type`이 없거나 모르는 값 |
| `invalid_place` | `place`가 여섯 값 밖 |
| `invalid_screen` | `screen`이 다섯 값 밖 |
| `invalid_book_id` | 책 번호가 양의 정수가 아님 |
| `invalid_value` | 그 밖의 틀린 값이나 빠진 칸 |
| `unknown_library` | 없거나 꺼진 도서관 번호 |
| `invalid_plan`, `invalid_client_key` | 확장 모드 계획·브라우저 번호 거절(사이클 20 2·3단계). `usagelog/004`가 미리 허용, 1단계 코드는 아직 쓰지 않음 |

### `dropped_batch.reason`

- `db_failure`: DB 쓰기 실패로 버린 묶음입니다.
- `buffer_full`: 메모리 1만 줄이 넘쳐 버린 기록입니다.
- 버린 순간에 서버 로그에 WARN `Usage log write failed, N rows dropped`가 남습니다.

## 3. 04:30 정리 (Asia/Seoul, 매일)

- 순서는 다음과 같습니다.
  1. 다섯 기록 표에서 30일 지난 줄의 `client_ip`를 비웁니다.
  2. `rejected_event`에서 30일 지난 줄을 지웁니다.
  3. 다섯 기록 표, `ai_search_event`(IP 칸이 없어 1번은 해당 없음, 사이클 24)와 `dropped_batch`에서 1년 지난 줄을 지웁니다(받은 시각 기준).
  4. `library_response_daily`에서 1년 지난 날을 지웁니다.
- 한 번에 1만 줄씩 처리합니다(`chaekcheck.usagelog.cleanup.batch-size`).
- 기록이 꺼져 있어도 정리는 돕니다.
- 끝나면 INFO `usage log clean-up: ip cleared N, rejected deleted N, rows deleted N`이 남습니다.
- 다른 정해진 작업과 시각이 겹치지 않습니다. 04:00 회원 정리, 05:00 사서 추천 받기, 03:00 DB 백업(사이클 17)입니다.

## 4. 요청마다 서버 로그 한 줄

```
library=<도서관 번호> source=direct_search|holding_fill result=ok|failed|timeout|skipped|body_too_large ms=<ms>[ error=<메시지>]
```

- IP는 남기지 않습니다.
- 실패한 요청에는 묶음 쪽 실패 줄(`batch=… library=… failed: …`)이 하나 더 남습니다.
- 서비스 로그는 14일 보관합니다(사이클 17 배포 설정).

## 5. 자주 쓰는 SQL

```sql
-- 오늘(서울) 도서관별 응답: 실패·시간 초과가 많은 곳
SELECT library_id, source, ok_count, failed_count, timeout_count, skipped_count,
       ok_ms_total / NULLIF(ok_count, 0) AS ok_ms_avg, ok_ms_max
FROM usagelog.library_response_daily
WHERE day = (now() AT TIME ZONE 'Asia/Seoul')::date
ORDER BY failed_count + timeout_count DESC;

-- 최근 7일 검색 수(종류별)
SELECT kind, count(*) FROM usagelog.search_event
WHERE received_at > now() - interval '7 days' GROUP BY kind;

-- 버린 묶음
SELECT * FROM usagelog.dropped_batch ORDER BY dropped_at DESC LIMIT 20;
```

## 6. 지인 기간 시험 뒤 그 기간 기록 지우기 [안]

시험 기간을 정하고 한 트랜잭션에서 지웁니다. 지우기 전에 사용자 확인을 받습니다.

```sql
BEGIN;
DELETE FROM usagelog.search_event   WHERE received_at <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.book_view      WHERE received_at <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.search_more    WHERE received_at <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.library_click  WHERE received_at <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.screen_event   WHERE received_at <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.rejected_event WHERE received_at <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.dropped_batch  WHERE dropped_at  <  TIMESTAMPTZ '2026-11-01 00:00+09';
DELETE FROM usagelog.library_response_daily WHERE day < DATE '2026-11-01';
COMMIT;
```

위 날짜는 예시입니다. 실제 공개일로 바꿔서 씁니다.
