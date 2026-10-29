# 4. AI 검색 낱말 찾기 더 빠르게: 여러 판을 여러 경우로 재고 고른 기록

## 이 문서가 답하는 것 / 답하지 않는 것

- 답하는 것: v1.20.1 뒤에도 남은 「흔한 낱말 질문의 낱말 찾기 0.2~0.4초」를 줄이려고 질의를 여러 판(V0~V7)으로 만들어 경우 18가지에서 잰 숫자, 답이 바뀌지 않는지 확인한 방법, 판마다 빠르고 느린 까닭(실행 계획), 고른 판과 그 구현, 운영 결과.
- 답하지 않는 것: v1.20.1의 generic plan 문제 → [3-postgres-generic-plan.md](3-postgres-generic-plan.md). 포트폴리오용 요약 → [1-portfolio.md](1-portfolio.md) 5절. AI 검색 전체 흐름 → [../aisearch/1-how-it-works.md](../aisearch/1-how-it-works.md) 4-7절.

## 1. 무엇을 줄이려 했나

낱말 찾기 질의(`catalog.internal.texts.JdbcAiSearchIndex.find`, SQL은 [3-postgres-generic-plan.md 3-1절](3-postgres-generic-plan.md))는 맞는 책을 찾는 데는 수십 ms면 되는데, 그 뒤 맞은 책마다 두 가지를 계산한다.

1. `plan_count`: 책의 도서관(평균 79곳)이 계획 도서관(최대 2,455곳)에 몇 곳 드는지. `(SELECT count(*) FROM unnest(library_ids) l WHERE l = ANY(plan))` — 상관 서브쿼리(SubPlan)로 책마다 배열을 펼쳐 하나씩 비교.
2. `score`: 낱말마다 `search_text LIKE '%낱말%'`를 SELECT에서 한 번 더 계산(WHERE에서 이미 확인한 것).

「소설」처럼 흔한 한 낱말 질문은 맞은 1만 2천 권이 모두 점수 1로 같아, 순서를 정하려면 모든 책의 `plan_count`를 세야 한다. 운영에서 이런 질문이 257~357ms(아래 3절), 가장 느린 경우였다.

## 2. 판

| 판 | `plan_count` | `score` |
|---|---|---|
| V0 (v1.20.1 운영) | `unnest` + `= ANY` SubPlan | `LIKE` 다시 |
| V1 | `intarray.icount(intarray._int_inter(library_ids, plan))` 식 그대로 | `LIKE` |
| V2 | V1 | `strpos(search_text, 낱말) > 0` |
| V3 | V0 | `strpos` (확장 없이 되는 판) |
| V4 | `(SELECT intarray.icount(…))` 스칼라 서브쿼리 | `LIKE` |
| V5 | V4 | `strpos` |
| V6 | SQL 함수 `catalog.plan_library_count` (COST 1000, `SET` 절로 인라인 막음) | `strpos` |
| **V7** | **PL/pgSQL 함수 `catalog.plan_library_count` (COST 1000)** | **`strpos`** |

- intarray: PostgreSQL 기본 확장(contrib). `_int_inter`는 두 정수 배열을 정렬해 교집합을 만들고(C 함수), `icount`는 원소 수. 우리 이미지(postgres-bigm)에 들어 있음.
- `strpos(글, 낱말) > 0`은 `LIKE '%낱말%'`(낱말 안 `%` `_` `\`를 이스케이프한 것)과 같은 책을 고른다: 둘 다 「글 안에 이 글자들이 그대로 있나」이고, `search_text`는 `COLLATE "C"`라 바이트 비교.

## 3. 잰 방법

- 도구: [`scripts/aisearch/eval/bench_keyword_query.py`](../../scripts/aisearch/eval/bench_keyword_query.py). psql로 판마다 한 번 데운 뒤 5번 재어 중앙값(psql `\timing`), 모두 `plan_cache_mode = force_custom_plan`(서버와 같음).
- 경우 18가지 = 낱말 6가지 × 계획 도서관 3가지.
  - 낱말: 흔한 한 낱말(「소설」, 12,200권), 드문 한 낱말(「커피」), 두 낱말(소설·위로), 세 낱말(경제·투자·초보), 다섯 낱말(다이어트·식단·자세히·다룬·건강), 두 낱말 + 핵심어(에세이·여행, 핵심어 여행).
  - 계획 도서관: 표에 나오는 모든 도서관 2,455곳 / 앞 200곳 / 앞 30곳.
- **답이 같은지**: 경우마다 결과 30줄(`book_id:score:plan_count`, 순서대로)의 md5를 판끼리 비교. **18경우 × 8판 모두 같았다.**
- 어디서: 내 PC의 연습 DB(c26 `db`, 운영과 같은 가져오기 14번, `catalog.book_search` 76,759줄)에서 V0~V7을 3번(V0·V3·V5·V7), 운영 DB에서 읽기만으로 V0·V3(확장이 없어 V7은 배포 뒤).

## 4. 결과

### 4-1. PC, 3번 잰 중앙값 (ms)

| 계획 도서관 / 낱말 | V0 | V3 | V5 | **V7** |
|---|---|---|---|---|
| 2,455곳 / 흔한 한 낱말 | 178 | 174 | 85 | **99** |
| 2,455곳 / 드문 한 낱말 | 5 | 5 | 3 | 3 |
| 2,455곳 / 두 낱말 | 62 | 63 | 140 | 77 |
| 2,455곳 / 세 낱말 | 41 | 32 | 63 | 45 |
| 2,455곳 / 다섯 낱말 | 68 | 56 | 55 | 78 |
| 2,455곳 / 두 낱말 + 핵심어 | 66 | 61 | 112 | 66 |
| 200곳 / 흔한 한 낱말 | 168 | 161 | 27 | **69** |
| 200곳 / 드문 한 낱말 | 3 | 3 | 1 | 1 |
| 200곳 / 두 낱말 | 44 | 44 | 46 | 40 |
| 200곳 / 세 낱말 | 32 | 27 | 30 | 28 |
| 200곳 / 다섯 낱말 | 54 | 42 | 40 | 50 |
| 200곳 / 두 낱말 + 핵심어 | 66 | 54 | 51 | 42 |
| 30곳 / 흔한 한 낱말 | 158 | 157 | 28 | **73** |
| 30곳 / 드문 한 낱말 | 3 | 3 | 1 | 1 |
| 30곳 / 두 낱말 | 47 | 40 | 35 | 35 |
| 30곳 / 세 낱말 | 30 | 26 | 22 | 30 |
| 30곳 / 다섯 낱말 | 54 | 42 | 41 | 42 |
| 30곳 / 두 낱말 + 핵심어 | 55 | 47 | 45 | 37 |
| **합** | 1,133 | 1,038 | 824 | **818** |
| **가장 느린 경우** | 178 | 174 | 140 | **99** |

첫 번째 판(V0~V7 모두) 합: V0 1,114 · V1 1,093 · V2 880 · V3 1,116 · V4 1,201 · V5 848 · V6 1,094 · V7 814 ms. 원본: dev `data/c26/bench-keyword-pc*.tsv`(git 밖).

### 4-2. 운영, 배포 전 (ms, 읽기만)

V0 합 2,090 · 가장 느린 경우 357(2,455곳 / 흔한 한 낱말), V3 합 1,994 · 357. 운영(t4g.small, 2코어)은 PC의 약 2배. 원본 `data/c26/bench-keyword-prod-before.tsv`.

## 5. 왜 판마다 다른가 (실행 계획)

- **V1·V2(intarray 식 그대로)가 여러 낱말에서 느려진 까닭**: 플래너는 함수 비용을 선언된 `COST`(intarray C 함수는 1)로 본다. `plan_count`가 싸 보이면 맞은 줄 모두(두 낱말 16,418줄)에서 계산하는 직렬 계획(Bitmap Heap Scan → Sort)을 고른다(2,455곳 / 두 낱말 114ms). V0은 SubPlan이 비싸 보여서 병렬 계획(Parallel Bitmap Heap Scan → 작업자별 Sort(점수) → Gather Merge → Incremental Sort)을 고르고, `plan_count`는 `LIMIT`에 필요한 묶음의 606줄만 계산(62ms). 즉 「한 번이 싸지만 모두 계산」 대 「한 번이 비싸지만 조금만 계산」.
- **흔한 한 낱말에서 V0이 느린 까닭**: 점수가 모두 같아 Incremental Sort도 모든 줄의 `plan_count`가 필요하다(12,200번 SubPlan).
- **V7**: 함수를 `COST 1000`으로 선언해 플래너가 V0처럼 병렬 + 「필요한 줄만」 계획을 고르게 하고, 한 번 계산은 intarray로 싸게. 흔한 한 낱말(모든 줄 필요)도 V0의 약 절반. SQL 함수(V6)는 `SET` 절로 인라인을 막았지만 함수 호출마다 설정을 바꿨다 되돌리는 비용이 붙어 V7보다 느렸다.
- **strpos**: 점수 계산에서 `LIKE` 패턴 매칭 대신 부분 문자열 찾기. 다섯 낱말·핵심어처럼 낱말이 많을수록 차이가 남.

## 6. 고른 판: V7

- 이유: 합이 가장 작고(818 vs V0 1,133, −28%) **가장 느린 경우가 가장 작다**(99 vs 178, −44%). V5는 흔한 한 낱말에서 더 빠르지만(27~85) 2,455곳 / 두 낱말에서 140으로 V0보다 2배 느려지는 경우가 있다. V7이 V0보다 10ms 넘게 느린 경우는 3번 중 1~2경우(2,455곳 / 두·다섯 낱말, 15ms 안팎).
- 구현(v1.20.3):
  - `catalog/003-intarray.yaml`: `CREATE SCHEMA intarray` + `CREATE EXTENSION intarray WITH SCHEMA intarray`. **public이 아닌 따로 된 스키마**: intarray는 `integer[]`용 `&&` `@>` `<@` 연산자를 새로 만드는데, 검색 경로에 있으면 다른 질의의 기본 배열 연산자 자리를 차지할 수 있어서. 이름을 붙여서만 부른다.
  - `catalog/004-plan-library-count.sql`(formatted SQL, `runOnChange`): `catalog.plan_library_count(integer[], integer[])` PL/pgSQL, `IMMUTABLE STRICT PARALLEL SAFE COST 1000`.
  - `JdbcAiSearchIndex`: `PLAN_COUNT` = 그 함수, 점수와 핵심어 점수는 `strpos(search_text, :wN) > 0`(WHERE는 그대로 `LIKE`, pg_bigm 색인).
  - 시험: `AiSearchIndexTests.planLibraryCountIsTheIntersectionOfTheTwoArrays`(순서 섞인 배열, 빈 배열, public에 intarray `&&`가 없음) + 기존 순서·`%` `_`·plan 시험 모두 통과.
- 배열에 같은 도서관이 두 번 들어 있으면 intarray는 한 번, V0은 두 번 센다. `book_search.library_ids` 76,759줄 중 중복 있는 줄 0(연습 DB 확인, `catalog.book.library_ids`에서 복사).

## 7. 운영 배포 뒤

(배포 뒤 같은 도구로 운영에서 V0·V7을 재어 여기 적음)
