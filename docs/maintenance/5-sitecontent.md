# 공지·서비스 안내·약관 글 넣고 고치기 (운영 SQL)

- 사이클 16. 결정과 근거는 plan ADR-0098~0101과 `docs/cycles/16-notices-and-policies/`에 있고, 여기에는 운영 절차만 둡니다.
- 운영자 화면이 없습니다. 공지와 네 문서(서비스 안내 `ABOUT`, 이용약관 `TERMS_OF_SERVICE`, 개인정보 처리방침 `PRIVACY_POLICY`, 가입 동의문 `PRIVACY_COLLECTION`)는 Claude가 아래 SQL로 `sitecontent` schema에 넣습니다. 넣은 글은 새로고침하면 바로 보입니다(서버가 글을 메모리에 두지 않음).
- 아래 SQL은 plan `drafts/ops-sql.md` 초안을 2026-10-14 사이클 16 개발 DB(`chaekcheck-c16` `db`)에서 절마다 실제로 돌려 확인한 것입니다. 결과는 `docs/plan-feedback.md` `# 사이클 16`.

## 0. 지키는 것

- **사용자 확인 뒤에 넣습니다**: 글과 바뀐 점을 먼저 보여 주고, 사용자가 확인하면 넣습니다.
- **한 번에 한 트랜잭션**. 줄 수가 기대와 다르면 되돌립니다. psql에 파일을 한 번에 넣으면 `COMMIT;`까지 쉬지 않고 가므로, 넣은 줄 수를 보고 나서 정하려면 아래 "줄 수를 보고 확정하기"를 씁니다.
- **판은 고치지 않고 새로 넣습니다**: `notice_version`, `document_version`은 `UPDATE`·`DELETE`하지 않습니다. 바꾸는 것은 `notice`의 `pinned`·`hidden`뿐입니다.
- **날짜 순서**: 새 판의 날짜는 그 글의 마지막 판 날짜보다 이르면 안 됩니다. DB 검사 장치는 없고 아래 `HAVING` 조건으로 지킵니다(이르면 0줄).
- **"오늘"은 한국 날짜**: `(now() AT TIME ZONE 'Asia/Seoul')::date`. 서버도 같은 날짜로 지금 판을 고릅니다.
- **본문은 `$$ … $$`로 감쌉니다**: 글 안의 따옴표·세미콜론이 SQL을 깨지 않습니다(시험: `'따옴표'`, `;`가 든 본문 그대로 들어감).
- **글 모양**: 제목(`##`), 문단, 목록, 굵게, 링크만 그려집니다. **표는 그려지지 않습니다**(`|`가 글자로 보임, `remark-gfm` 없음). 표 대신 목록으로 씁니다(사용자 결정 2026-10-14). HTML은 그리지 않습니다. 이메일은 `<주소@예>` 꼴로 써야 메일 쓰기 링크가 됩니다.
- **다시 동의가 필요한 변경은 넣지 않습니다**: 중요하거나 불리한 이용약관 변경, 동의문의 항목·목적·기간 변경은 다시 동의 화면이 생기기 전까지 넣지 않습니다(ADR-0101).
- **약관·동의문·처리방침을 바꾸면 공지도 함께**: 같은 트랜잭션에 공지 하나(spec R7-1).

### 줄 수를 보고 확정하기

psql 한 번 실행 안에서 넣은 줄 수가 정확히 1일 때만 `COMMIT`하고, 아니면 `ROLLBACK`합니다. `RETURNING 1`을 세어 `\gset`으로 받고 `\if`로 가릅니다(시험: 이른 날짜로 고치기 → "rolled back").

```sql
\set ON_ERROR_STOP on
BEGIN;
WITH ins AS (
  -- 여기에 아래 절의 INSERT … 를 넣고 끝에 RETURNING 1
  INSERT INTO sitecontent.notice_version (notice_id, version_no, effective_on, title, body)
  SELECT v.notice_id, max(v.version_no) + 1, DATE '2026-11-04', $$제목$$, $$본문$$
  FROM sitecontent.notice_version v
  WHERE v.notice_id = 7
  GROUP BY v.notice_id
  HAVING DATE '2026-11-04' >= max(v.effective_on)
  RETURNING 1
)
SELECT count(*) = 1 AS ok FROM ins \gset
\if :ok
  COMMIT;
  \echo committed
\else
  ROLLBACK;
  \echo rolled back: not exactly one row
\endif
```

실행: `wsl -d Ubuntu -- docker compose -p <프로젝트> exec -T db psql -U <DB 사용자> -d <DB 이름> < 파일.sql`. 오류 문장을 영어로 보려면 맨 앞에 `SET lc_messages = 'C';`(이 PC의 WSL에서는 한국어 오류가 깨져 보였음).

## 1. 새 공지 올리기

```sql
BEGIN;
WITH n AS (
  INSERT INTO sitecontent.notice (pinned) VALUES (false)
  RETURNING id
)
INSERT INTO sitecontent.notice_version (notice_id, version_no, effective_on, title, body)
SELECT n.id, 1, DATE '2026-11-03', $$점검 안내$$, $$11월 3일 새벽 2시~4시에 점검해요.$$
FROM n
RETURNING notice_id;   -- 공지 번호(/notices/{번호}), 1줄
COMMIT;
```

- 게시일을 앞날로 넣으면 그날부터 목록에 나옵니다. 고정 공지는 `VALUES (true)`.
- 공지 번호는 `sitecontent.notice_id_seq`(identity 시퀀스)에서 나옵니다. 운영 DB 계정에는 `sitecontent` 표 셋의 SELECT·INSERT·UPDATE와 이 시퀀스의 USAGE가 필요합니다(사이클 17).

## 2. 공지 고치기 (새 판)

```sql
BEGIN;
INSERT INTO sitecontent.notice_version (notice_id, version_no, effective_on, title, body)
SELECT v.notice_id, max(v.version_no) + 1, DATE '2026-11-04', $$점검 안내(시간 바뀜)$$, $$새벽 3시~5시로 바뀌었어요.$$
FROM sitecontent.notice_version v
WHERE v.notice_id = 7
GROUP BY v.notice_id
HAVING DATE '2026-11-04' >= max(v.effective_on);
-- 1줄. 0줄이면 공지 번호가 틀렸거나 날짜가 마지막 판보다 이름 → ROLLBACK
COMMIT;
```

- 목록 제목은 지금 판 제목으로 바뀌고, 목록에는 고친 날이 나오지 않습니다. 공지 글에는 "고침 날짜"와 날짜 고르기 칸이 나옵니다.

## 3. 공지 숨기기·다시 보이기, 고정·풀기

```sql
UPDATE sitecontent.notice SET hidden = true  WHERE id = 7;   -- 숨김 (목록·주소에서 사라짐, DB에는 남음)
UPDATE sitecontent.notice SET hidden = false WHERE id = 7;   -- 다시 보이기
UPDATE sitecontent.notice SET pinned = true  WHERE id = 7;   -- 1쪽 맨 위에 고정
UPDATE sitecontent.notice SET pinned = false WHERE id = 7;   -- 고정 풀기
-- 각각 1줄
```

## 4. 서비스 안내·이용약관·처리방침 새 판

`document_key`: `ABOUT`, `TERMS_OF_SERVICE`, `PRIVACY_POLICY`. 바뀐 점(`change_summary`)은 이용약관·처리방침에만 넣고 서비스 안내는 `NULL`(DB가 검사). 첫 판은 seed(`sitecontent/002`)에 있습니다.

```sql
BEGIN;
INSERT INTO sitecontent.document_version (document_key, version_no, effective_on, body, change_summary)
SELECT 'PRIVACY_POLICY', max(d.version_no) + 1, DATE '2026-12-01',
       $$## 1. 수집 항목, 목적, 보유 기간
…(새 본문 전체)…$$,
       $$○○ 항목을 더했어요.$$
FROM sitecontent.document_version d
WHERE d.document_key = 'PRIVACY_POLICY'
HAVING DATE '2026-12-01' >= max(d.effective_on);
-- 1줄

-- 알리는 공지 (1번과 같은 방법, 게시일은 시행일보다 앞)
WITH n AS (INSERT INTO sitecontent.notice (pinned) VALUES (false) RETURNING id)
INSERT INTO sitecontent.notice_version (notice_id, version_no, effective_on, title, body)
SELECT n.id, 1, DATE '2026-11-24', $$개인정보 처리방침이 12월 1일에 바뀌어요$$,
       $$바뀐 점: ○○ 항목을 더했어요. 바뀌기 전 방침은 처리방침 화면의 날짜 고르기 칸에서 볼 수 있어요.$$
FROM n;
COMMIT;
```

- 시행일이 앞날인 판은 그날 전까지 화면·`versions`·`?version=`·가입 API 어디에도 나오지 않습니다(시험: 앞날 판은 `?version=2`가 404 `version_not_found`).
- 이용약관·동의문의 새 판이 시행되는 날부터 가입 화면이 새 판에 동의를 받습니다. 그 전에 화면을 연 사람은 가입할 때 "약관이 바뀌었어요" 안내를 한 번 봅니다(409).

## 5. 가입 동의문 새 판

동의문은 4항목(`consent_items`)이 반드시 있어야 합니다(DB가 검사, 3개면 `document_version_consent_items_shape_check` 위반). 항목·목적·기간을 바꾸는 변경은 다시 동의가 필요하므로 지금은 넣지 않습니다. 문장만 다듬는 변경에만 씁니다.

```sql
BEGIN;
INSERT INTO sitecontent.document_version (document_key, version_no, effective_on, body, change_summary, consent_items)
SELECT 'PRIVACY_COLLECTION', max(d.version_no) + 1, DATE '2026-12-01',
       $$위 정보만 받으며, 탈퇴하면 바로 지웁니다.$$,
       $$문장을 다듬었어요.$$,
       $$[{"label":"목적","text":"로그인 유지, 내 지역 도서관 찾기"},
          {"label":"항목","text":"카카오 회원 식별 번호, 거주·직장 지역, 직접 추가한 도서관"},
          {"label":"보유 기간","text":"탈퇴 또는 마지막 로그인 뒤 1년"},
          {"label":"거부할 권리","text":"동의하지 않으면 가입할 수 없지만, 검색은 그대로 쓸 수 있어요"}]$$::jsonb
FROM sitecontent.document_version d
WHERE d.document_key = 'PRIVACY_COLLECTION'
HAVING DATE '2026-12-01' >= max(d.effective_on);
COMMIT;
```

## 6. 확인하기

```sql
-- 문서마다 지금 판 (오늘 한국 날짜 기준) — 서버 GET /api/v1/documents/{key}의 version.number와 같음
SELECT DISTINCT ON (document_key) document_key, version_no, effective_on
FROM sitecontent.document_version
WHERE effective_on <= (now() AT TIME ZONE 'Asia/Seoul')::date
ORDER BY document_key, version_no DESC;

-- 한 문서의 모든 판과 적용 기간 (앞날 판 포함)
SELECT version_no, effective_on,
       lead(effective_on) OVER (ORDER BY version_no) AS next_effective_on
FROM sitecontent.document_version
WHERE document_key = 'TERMS_OF_SERVICE'
ORDER BY version_no;

-- 공지 목록 (숨김 포함, 마지막으로 넣은 판 — 앞날 판일 수 있음)
SELECT n.id, n.pinned, n.hidden, v.version_no, v.effective_on, v.title
FROM sitecontent.notice n
JOIN LATERAL (
  SELECT version_no, effective_on, title FROM sitecontent.notice_version
  WHERE notice_id = n.id ORDER BY version_no DESC LIMIT 1
) v ON true
ORDER BY n.id DESC;
```

## 7. 표 읽는 법

- `notice`: 공지 하나. `pinned`(1쪽 맨 위, 10개 셈·총 개수에서 빠짐), `hidden`(목록·주소 모두 404, DB에는 남음)
- `notice_version`: 공지의 판. 판 1의 `effective_on` = 게시일, 그 뒤 판 = 고친 날
- `document_version`: 문서의 판. `effective_on` = 시행일(서비스 안내는 게시일). `change_summary` = 화면의 "바뀐 점 보기". `consent_items` = 가입 화면 동의 표 네 줄
- 지금 판 = `effective_on <= 오늘(한국)`인 판 중 `version_no`가 가장 큰 것
- 가입 동의 기록(`auth.account_consent.document_version`)은 동의한 판의 번호 글자(`'1'`, `'2'` …)
