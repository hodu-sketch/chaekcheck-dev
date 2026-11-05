# 3. PostgreSQL generic plan 해설: AI 검색 낱말 찾기가 5초가 된 이유

## 이 문서가 답하는 것 / 답하지 않는 것

- 답하는 것: v1.20.1에서 고친 「같은 질의가 쌓이면 낱말 찾기가 20배 느려짐」을 PostgreSQL과 JDBC 드라이버 동작으로 처음부터 끝까지. 용어 정의, 플래너가 줄 수를 어떻게 추정하는지(계산식과 실제 값), 왜 틀렸는지, 왜 느렸는지, 무엇을 어떻게 재었는지, 고친 방법과 고르지 않은 방법.
- 답하지 않는 것: 요약 → [1-summary.md 1절](1-summary.md). 날짜·배포·dev 24 측정 → [2-dev-record.md 4절](2-dev-record.md), [plan-feedback](../../cycles/plan-feedback.md) 「# 사이클 26」. AI 검색 전체 흐름 → [../aisearch/1-how-it-works.md](../aisearch/1-how-it-works.md) 4-7절.
- 숫자는 모두 2026-10-29 운영 DB(PostgreSQL 18, `catalog.book_search` 76,759줄)에서 잰 값. PostgreSQL 내부 상수·함수 이름은 PostgreSQL 소스(`selfuncs.h`, `array_selfuncs.c`, `plancache.c`)의 이름이고, 운영 `EXPLAIN`의 추정 줄 수가 그 상수로 계산한 값과 정확히 같아 맞음을 확인했다.

## 1. 무엇이 일어났나 (요약)

1. pgjdbc가 같은 SQL을 한 연결에서 5번 쓰자 서버 쪽 준비문으로 바꿨다.
2. PostgreSQL이 그 준비문에 값을 모르는 **generic plan**을 만들었다.
3. generic plan은 낱말 패턴을 몰라 기본 선택도로 맞는 줄을 **8줄**로 추정했다(실제 16,418줄).
4. 8줄이면 싸 보여서(예상 비용 2,376 < custom plan 13,120) 그 계획이 뽑혔다.
5. 그 계획은 16,418줄 모두에 비싼 서브플랜을 돌려 **5.17초**가 걸렸다(custom plan **0.24초**).

## 2. 용어

| 용어 | 뜻 |
|---|---|
| 플래너(planner) / 옵티마이저 | SQL을 실제로 어떻게 실행할지(어느 색인, 어느 순서, 어느 정렬) 고르는 PostgreSQL 부분. 후보 계획마다 **예상 비용**을 계산해 가장 싼 것을 고름 |
| 예상 비용(estimated cost) | 플래너가 쓰는 단위 없는 숫자(디스크 쪽 읽기 1 = `seq_page_cost` 1.0 기준). `EXPLAIN`의 `cost=시작..전체`. 계획 비교에는 **전체(total) 비용**을 씀 |
| 선택도(selectivity) | 조건을 통과할 줄의 비율(0~1). 추정 줄 수 = 표 줄 수 × 선택도. 비용은 거의 이 추정 줄 수로 정해짐 |
| `pg_stats` | `ANALYZE`가 칸마다 저장하는 통계. 빈 값 비율(`null_frac`), 서로 다른 값 수(`n_distinct`), 자주 나오는 값과 빈도(MCV: `most_common_vals`/`freqs`), 값 분포 표본(`histogram_bounds`) |
| 히스토그램(`histogram_bounds`) | MCV에 들지 않는 값을 크기 순으로 줄 세워 같은 간격으로 뽑은 표본. 개수는 `default_statistics_target`(운영 100) + 1 = 101개 |
| 준비문(prepared statement) | SQL을 한 번 파싱해 두고 값만 바꿔 여러 번 실행하는 것. SQL로는 `PREPARE`/`EXECUTE`, 드라이버는 확장 쿼리 프로토콜(Parse/Bind/Execute)의 **이름 있는 문장(named statement)** |
| 이름 없는 문장(unnamed statement) | 확장 쿼리 프로토콜에서 한 번 쓰고 버리는 문장. 실행 때마다 실제 값으로 계획을 세움 |
| `prepareThreshold` | pgjdbc 설정(기본 5). 같은 `PreparedStatement`를 이 횟수만큼 쓰면 이름 있는 서버 쪽 준비문으로 바꿈 |
| custom plan | 준비문 실행 때 **실제 파라미터 값을 넣고** 새로 세우는 계획 |
| generic plan | 파라미터를 `$1`, `$2`(Param 노드)로 둔 채 **값과 상관없이** 한 번 세워 두고 다시 쓰는 계획 |
| 계획 캐시(plan cache) | 준비문의 generic plan과 custom plan 비용 기록을 두는 곳(`plancache.c`) |
| `plan_cache_mode` | PG12부터의 설정. `auto`(기본: 아래 3-3의 규칙), `force_custom_plan`, `force_generic_plan` |
| SubPlan | `SELECT` 목록이나 `WHERE` 안의 상관 서브쿼리. 바깥 줄마다 한 번씩 돌 수 있음 |
| ScalarArrayOpExpr(SAOP) | `x = ANY(배열)` 꼴 식. PG14부터 배열이 **상수**이고 9개 이상이면 해시 테이블을 만들어 비교(hashed SAOP), 상수가 아니면 배열을 처음부터 훑음 |
| Bitmap Index Scan / BitmapOr / Bitmap Heap Scan | 색인으로 맞는 줄 위치를 비트맵으로 모으고(OR이면 비트맵을 합침), 그 위치의 표 페이지를 읽어 조건을 다시 확인하는 실행 방식 |
| Incremental Sort | 앞쪽 정렬 키로 이미 정렬된 입력을 받아 뒤쪽 키만 묶음별로 정렬. `LIMIT`이 있으면 필요한 묶음까지만 읽음 |

## 3. 그 질의

### 3-1. 서버가 보내는 SQL

AI 검색 첫 질문의 낱말 찾기(`catalog.internal.texts.JdbcAiSearchIndex.find`). 낱말 `k0, k1, …`마다 `LIKE` 하나:

```sql
SELECT book_id, score, plan_count FROM (
  SELECT s.book_id,
         (s.search_text LIKE :k0)::int + (s.search_text LIKE :k1)::int AS score,
         (SELECT count(*) FROM unnest(s.library_ids) AS l(id)
           WHERE l.id = ANY(CAST(:plan AS integer[])))::int AS plan_count,
         0 AS core_score
  FROM catalog.book_search s
  WHERE (s.search_text LIKE :k0 OR s.search_text LIKE :k1)
    AND s.library_ids && CAST(:plan AS integer[])
    AND NOT (s.book_id = ANY(CAST(:excluded AS bigint[])))
) m
ORDER BY core_score DESC, score DESC, plan_count DESC, book_id
LIMIT :limit
```

- `search_text`: 제목·저자·한 줄 소개를 소문자로, 빈칸 없이 이은 글(`text COLLATE "C"`), pg_bigm GIN 색인 `book_search_search_text_bigm`.
- `library_ids`: 그 책을 가진 도서관 번호 배열(평균 79.2개).
- `plan_count`: 그 책을 가진 도서관 중 사용자의 계획 도서관 수.

### 3-2. 낱말은 몇 개인가

`QuestionWords.of`: 빈칸·문장부호로 자르고, 끝말(가·는·을 …)을 한 번 떼고, 한 글자와 요청 말(책·말고·추천 …)을 뺌. 「소설 말고 위로가 되는 책」 → 소설(그대로), 말고(요청 말, 뺌), 위로가 → **위로**, 되는 → 되(한 글자, 뺌), 책(요청 말, 뺌) → **「소설」·「위로」 두 개**. 낱말 수·핵심어 수가 같은 질문은 SQL 글이 같다(값만 다름). 이 점 때문에 아래 3-3의 횟수가 쌓인다.

### 3-3. 같은 SQL이 generic plan으로 가는 길

1. 한 DB 연결에서 같은 SQL 글의 1~4번째: pgjdbc가 이름 없는 문장으로 보냄 → 매번 실제 값으로 계획.
2. 5번째부터(`prepareThreshold=5`): 이름 있는 서버 쪽 준비문 → 계획 캐시를 씀.
3. 계획 캐시(`plan_cache_mode = auto`, `choose_custom_plan`): 준비문의 처음 5번은 custom plan으로 실행하며 그 **전체 예상 비용의 평균**(계획 세우는 비용 추정 포함)을 기록. 그 뒤 generic plan을 만들어, **generic plan 예상 비용 < custom plan 평균**이면 generic plan을 쓴다. 이 비교는 실행마다 다시 하지만, 비용 추정이 그대로면 결과도 그대로.
4. 연결은 Hikari 풀에 여러 개라 횟수는 연결마다 따로 쌓이고, 서버를 다시 켜면 0부터. 그래서 「배포 직후 2회 정상, 3회째부터 느림」이 됐다.

## 4. 줄 수 추정: custom plan과 generic plan의 계산

### 4-1. custom plan: 실제 패턴을 히스토그램에 대 봄

- `search_text`는 책마다 다른 글이라 `n_distinct = -1`, MCV 없음. `histogram_bounds`에 표본 101개.
- `LIKE`의 선택도 함수 `likesel` → `patternsel`은 패턴이 상수(`'%소설%'`)이면 `histogram_selectivity`로 표본마다 패턴을 실제로 대 본다. 양 끝 1개씩을 빼고 99개.
- 「소설」: 99개 중 16개가 품음 → 16/99 = 0.1616 → 76,759 × 0.1616 = **12,405줄**(`EXPLAIN` 추정 12,405, 실제 12,200).
- 「위로」: 10/99 → **7,753줄**(추정 7,753, 실제 4,823).
- 두 조건과 계획 도서관 조건을 합친 추정 18,906줄(실제 16,418줄). 실제와 같은 크기다.

### 4-2. generic plan: 패턴을 모르니 고정 기본값

패턴 자리가 `$1`(Param)이라 표본에 대 볼 수 없다. 선택도 함수는 소스에 정해진 기본값을 돌려준다.

| 조건 | 쓰는 기본값 | 값 | 추정 |
|---|---|---|---|
| `search_text LIKE $1` | `DEFAULT_MATCH_SEL`(`selfuncs.h`) | 0.005 | 76,759 × 0.005 = **384줄**(`EXPLAIN` 색인 스캔마다 `rows=384`) |
| `search_text LIKE $2` | 같음 | 0.005 | 384줄 |
| 두 LIKE의 OR | 독립 가정: s1 + s2 − s1·s2 | 0.009975 | 766줄(BitmapOr 노드는 자식 추정을 더해 384 + 384 = 768) |
| `library_ids && $3` | `DEFAULT_OVERLAP_SEL`(`array_selfuncs.c`) | 0.01 | |
| `NOT (book_id = ANY($4))` | 거의 1(뺄 책 목록) | ≈ 1 | |
| **모두 AND** | 곱 | 0.009975 × 0.01 | 76,759 × 0.0000998 ≈ 7.7 → **8줄**(`EXPLAIN` `rows=8`) |

### 4-3. 실제와 비교

| 조건 | generic 가정 | 실제 |
|---|---|---|
| 「소설」 포함 | 0.5% | **15.9%**(12,200줄) |
| 「위로」 포함 | 0.5% | **6.3%**(4,823줄) |
| 계획 도서관과 겹침 | 1% | **100%**(계획 도서관 = 표에 나오는 2,455곳 전부라 모든 책이 통과) |
| 결과 | **8줄** | **16,418줄**(약 2,000배) |

기본값 0.5%는 「드문 낱말」쯤을 가정한 숫자인데, 책 소개 글에서 「소설」 같은 낱말은 흔하다. 계획 도서관 조건도 도서관을 많이 고른 사용자에게는 거의 모든 책이 통과한다.

## 5. 비용 비교와 실제 실행

### 5-1. 계획 고르기

| 계획 | 추정 줄 | 예상 전체 비용(`Limit` 노드) |
|---|---|---|
| custom plan | 18,906 | 13,120 |
| generic plan | 8 | **2,376** |

2,376 < 13,120 → 계획 캐시는 generic plan을 고른다.

### 5-2. 두 계획이 실제로 한 일 (`EXPLAIN ANALYZE`)

| | custom plan | generic plan |
|---|---|---|
| 줄 읽기 | Parallel Bitmap Heap Scan(리더 + 작업자 2, 16,418줄) | Bitmap Heap Scan(16,418줄) |
| 정렬 | 작업자마다 Sort → Gather Merge → Incremental Sort(앞 키 `core_score`·`score`로 정렬된 입력에서 `plan_count`가 필요한 묶음만) | Sort(모든 줄의 `plan_count`를 먼저 계산) |
| `plan_count` 서브플랜 실행 횟수 | **606번** | **16,418번** |
| 서브플랜 한 번 | 0.021ms(`= ANY`가 상수 배열 → hashed SAOP) | 0.303ms(`= ANY($3)`이 Param → 2,455개 배열을 처음부터 훑음, 책마다 도서관 약 72개) |
| 전체 | **0.24초** | **5.17초** |

느린 까닭은 두 가지가 겹친 것: 8줄이라 믿고 모든 줄에 서브플랜을 돌린 것(606 → 16,418번), 그리고 파라미터 배열이라 해시 비교를 못 한 것(0.021 → 0.303ms).

## 6. 어떻게 재었나 (다시 해 보는 법)

운영 DB에 읽기만(`ssh … 'docker exec -i chaekcheck-db-1 psql -U chaekcheck -d chaekcheck' < 파일.sql`):

```sql
\timing on
-- 계획 도서관 = 표에 나오는 모든 도서관
SELECT array_agg(DISTINCT l)::integer[] AS plan FROM catalog.book_search, unnest(library_ids) l \gset
PREPARE q(text, text, integer[], bigint[], int) AS
  <3-1의 SQL, :k0 → $1, :k1 → $2, :plan → $3, :excluded → $4, :limit → $5>;
SET plan_cache_mode = force_custom_plan;
EXPLAIN (ANALYZE) EXECUTE q('%소설%', '%위로%', :'plan', '{}', 30);
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE) EXECUTE q('%소설%', '%위로%', :'plan', '{}', 30);
```

- 확인한 것: 배포 뒤 `ANALYZE`가 없었음(`pg_stat_user_tables.last_analyze`·`last_autoanalyze` = 배포 시각), `histogram_bounds` 101개, `default_statistics_target` 100, 계획 도서관 2,455곳, 책당 도서관 평균 79.2.
- 옛 표 `aisearch.search_book`에도 같은 꼴(아래 7절의 다섯 낱말)을 돌려 0.44초 / 11.1초 → 배포 1이 만든 문제가 아니라 전부터 있던 것.

## 7. 다섯 낱말로 잰 값 (처음 재현, 참고)

처음에는 질문을 빈칸으로만 잘라 다섯 낱말(소설·말고·위로가·되는·책)로 재현했다. 서버가 쓰는 낱말이 아니라(3-2절) 참고로만 둔다: 추정 19줄 → 실제 35,731줄, 예상 비용 generic 4,815 < custom 15,624, 시간 generic **11.2초** / custom **0.64초**. 맞는 줄이 많을수록 차이가 커진다.

## 8. 고친 방법과 고르지 않은 방법

| 방법 | 결과 | 고름? |
|---|---|---|
| **이 질의만 `SET LOCAL plan_cache_mode = force_custom_plan`** (읽기 전용 트랜잭션 안) | 늘 실제 값으로 계획. `SET LOCAL`은 트랜잭션이 끝나면 사라져 다른 질의에 영향 없음. 비용은 실행마다 계획을 세우는 시간(이 질의 약 2~5ms) | **고름**(v1.20.1) |
| DB·역할 전체 `plan_cache_mode = force_custom_plan` | 모든 준비문이 매번 계획 → 다른 질의의 generic plan 이득을 잃음 | 안 고름 |
| JDBC `prepareThreshold=0` | 서버 쪽 준비문을 안 씀 → 모든 질의에 영향 | 안 고름 |
| 질의를 고쳐 generic plan도 빠르게(예: `plan_count`를 미리 계산한 칸, intarray 확장) | 표·확장 변경이 필요하고 추정이 틀리는 근본은 남음 | 이번엔 안 함 |

시험: `AiSearchIndexTests.repeatedSearchesKeepCustomPlans` — 같은 검색 12번 뒤 같은 연결의 `pg_prepared_statements`에서 이 SQL의 `generic_plans` = 0, `custom_plans` > 0(PG14부터 있는 칸). 고침을 빼면 실패함을 먼저 확인.

## 9. 한 줄로 설명하기

"값을 모르는 generic plan은 LIKE 선택도를 0.5%라는 고정값으로 잡아 맞는 줄을 8줄로 추정했는데 실제는 16,418줄이었고, 그 잘못된 추정 때문에 싸 보인 계획이 모든 줄에 서브플랜을 돌려 5초가 걸렸다. 이 질의만 늘 실제 값으로 계획을 세우게 해 0.24초로 고쳤다."
