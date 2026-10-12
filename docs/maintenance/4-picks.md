# 사서추천도서 받기 (국립중앙도서관 Open API)

- 사이클 14(사이클 10 인기 도서를 대체). 결정과 근거는 plan ADR-0089~0092와 `docs/cycles/14-librarian-picks/`에 있고, 여기에는 운영 절차만 둡니다.
- 하는 일: 국립중앙도서관 사서추천도서 Open API(`saseoApi.do`)에서 최근 3년 등록분을 요청 1건으로 받아, 추천 연월 최근 12개 달(12번 추천)의 책만 `picks` 스키마에 저장합니다. 새 표지만 받아 폭 200px JPEG로 줄여 저장합니다. 화면은 `/picks`, API는 `GET /api/v1/picks`.

## 1. 켜고 끄기, 인증키

- 설정 `chaekcheck.picks.fetch.enabled`, 환경 변수 `CHAEKCHECK_PICKS_FETCH_ENABLED`. 기본값 true(운영)
- 꺼 두는 곳: 로컬 `.env`(`false`), 테스트(`application-test.yml`), `collection-import` 프로필
- 인증키: 환경 변수 `CHAEKCHECK_PICKS_NLK_API_KEY`(설정 `chaekcheck.picks.fetch.api-key`). **비밀값**입니다. 로컬은 `.env`(git 밖)에 사람이 직접 넣고, 운영은 배포 비밀값으로 넣습니다. 코드·설정 파일·`.env.example`·문서·커밋·로그에 값을 적지 않습니다
  - 키가 비어 있으면 요청을 보내지 않고 `fetch_run`에 `FAILED`/`key_missing` 한 줄과 WARN 로그 한 줄을 남깁니다
  - 키는 요청 주소(`key=`)에 들어가므로 로그에는 `key=***`로만 찍힙니다
- 로컬에서 받기를 시험할 때만 켭니다(사용자 승인, 하루 최대 1번). 한 번 받으면 목록 요청 1건 + 새 표지(처음엔 약 108장, 그 뒤엔 새 추천분만), 표지는 1초 이상 간격
- 꺼도 공용 캐시 10분 새로 고침은 돕니다(저장된 목록을 화면에 보여 줌)

## 2. 언제 받나 (ADR-0092)

- 서버가 켜질 때 한 번, 그리고 매일 05:00(Asia/Seoul)에 "받을지"를 따집니다(`FetchDecision`). 하나라도 맞으면 받습니다
  1. 오늘 이미 성공했으면 받지 않음
  2. 저장된 목록이 없음 → 시작 때 `STARTUP`, 05:00이면 `CATCH_UP`
  3. 05:00이고 월요일 → `SCHEDULED`
  4. 05:00이고 마지막 받기가 `FAILED`(그 뒤 성공 없음) → `RETRY`. 성공할 때까지 매일
  5. 마지막 성공이 7일보다 오래됨(서버가 꺼져 월요일을 놓침) → `CATCH_UP`(시작 때나 05:00)
- 서버 시작 때는 2·5만 봅니다(실패 뒤 다시 받기는 05:00에만). 관리자 다시 받기 명령은 없습니다(이번 범위 밖)

## 3. 받기 기록 읽기 (`picks.fetch_run`)

```sql
SELECT id, trigger, status, failure_reason, failure_detail, changed, started_at, finished_at, request_count,
       received_count, kept_count, missing_isbn_count, new_category_codes, cover_fetched_count, cover_failed_count
FROM picks.fetch_run ORDER BY started_at DESC LIMIT 10;
```

| `trigger` | 뜻 |
|---|---|
| `STARTUP` | 저장된 목록 없이 서버가 켜짐 |
| `SCHEDULED` | 월요일 05:00 |
| `RETRY` | 앞 받기가 실패해서 다음 날 05:00에 다시 |
| `CATCH_UP` | 마지막 성공이 7일보다 오래됨, 또는 05:00에 목록이 없음 |

| `failure_reason` | 뜻 | 할 일 |
|---|---|---|
| `key_missing` | 인증키가 설정되지 않음(요청 안 보냄) | 환경 변수 `CHAEKCHECK_PICKS_NLK_API_KEY` 확인 |
| `network_error` | 연결 실패, 시간 초과(연결 5초, 읽기 10초) | 계속되면 국립중앙도서관 사이트 상태 확인 |
| `request_rejected` | 200이 아님(3xx 포함, redirect 안 따름), 또는 오류 코드 요소가 옴(`failure_detail` `API error <코드>`) | 키가 거절됐는지, 주소가 바뀌었는지 확인 |
| `format_changed` | `<channel>`·`<totalCount>` 없음, `recomNo`·제목·분야 코드·추천 연월이 틀림, 본문 10MB 넘음 | 응답 모양이 바뀌었는지 확인(`SaseoXmlParser`) |
| `incomplete` | `totalCount`가 받은 수보다 큼 | `end-row`(1000)보다 많아졌는지 확인 |
| `no_books` | 최근 12개 달에 책이 0권 | 사이트 쪽 문제일 수 있음. 다음 날 다시 받음 |

- `FAILED`면 목록은 바뀌지 않고 앞 목록이 그대로 보입니다. `failure_detail`에는 HTTP 상태나 오류 코드만 있고 키·주소·본문은 없습니다
- `SUCCEEDED`이고 `changed=false`면 받은 목록이 저장된 것과 같아서 아무것도 바꾸지 않았다는 뜻입니다(표지도 받지 않음)
- `new_category_codes`에 값이 있으면 모르는 분야 코드가 왔다는 뜻입니다. 화면에는 "전체"와 맨 뒤 새 분류(이름은 API의 `drCodeName`, 없으면 "기타")로 나옵니다
- 요청마다 INFO 로그 `NLK GET <경로, key=***> -> <상태> in <ms>ms, <바이트> bytes, <간격>ms after the request before`. 실패는 `log.error`

## 4. 표지

- `recomfilepath`를 https로 바꾼 뒤 호스트가 `www.nl.go.kr`이고 경로가 `/afile/`로 시작하는 것만 받습니다. redirect는 따르지 않습니다
- 받는 크기 상한 5MB, 픽셀 수 상한 1,600만, `Content-Type`이 `image/`여야 함(아니면 실패로 셈). 받자마자 폭 200px JPEG(품질 0.8)로 줄여 `picks.cover`에 저장
- 목록이 바뀐 받기(`changed=true`)에서만 받고, 저장 안 된 표지만 받습니다. 끝나면 지금 목록이 쓰지 않는 표지를 지웁니다
- 실패한 표지는 다음에 목록이 바뀔 때 다시 받습니다(목록이 같은 주에는 다시 받지 않음)
- TODO(확인 필요): 배포 전 국립중앙도서관 표지 이용 조건 확인(공식 문서 / 문의 정보기술기반과 02-590-0727)

### 권리자가 표지를 내려 달라고 할 때

화면 `/about`의 연락처로 요청이 오면 그 책의 표지만 지웁니다. 그 책은 제목 글자 카드로 보입니다.

```sql
-- 1) 그 책의 표지 주소 찾기 (ISBN 또는 제목)
SELECT recom_no, isbn13, title, cover_source FROM picks.pick WHERE isbn13 = '<ISBN>';
-- 2) 목록에서 주소를 비우고 표지를 지움 (한 트랜잭션)
BEGIN;
UPDATE picks.pick SET cover_source = NULL WHERE cover_source = '<cover_source>';
DELETE FROM picks.cover WHERE source_url = '<cover_source>';
COMMIT;
```

- 공용 캐시가 10분 안에 새로 고쳐져 화면에서 사라집니다. 브라우저는 표지를 30일 저장(`immutable`)하므로 이미 본 사람에게는 남아 있을 수 있습니다
- 다음에 목록이 바뀌면 같은 책 표지를 다시 받을 수 있으므로, 요청받은 표지는 따로 적어 두고 받기 뒤에 다시 지웁니다 [안: 막는 목록은 아직 없음]
- 요청과 처리 날짜는 `docs/plan-feedback.md`에 남깁니다

## 5. 맞추기 (우리 책과 잇기)

- 저장하지 않고 공용 캐시를 만들 때 맞춥니다(ADR-0090): ① ISBN(`BookLookup.byIsbn13`) ② ①로 못 맞은 책만 본제목 + 첫 저자(`BookLookup.byTitleAndAuthor`). 후보가 여럿이면 가진 도서관이 가장 많은 책, 같으면 책 번호가 작은 책
- 가져오기(3-import)로 책 번호가 바뀌어도 다음 캐시 새로 고침(10분 안)에 다시 맞춰집니다
