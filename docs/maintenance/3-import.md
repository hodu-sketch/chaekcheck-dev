# 가져오기: 받은 목록을 서비스 DB로

- 정기 유지보수의 **가져오기 절차 원본**입니다(plan ADR-0047, 2026-10-04). 순서 전체는 [README.md](README.md). plan 짝 문서: `../chaekcheck-plan/docs/research/collection/2-books.md` 6절.
- 옮겨 온 곳: `docs/plan-feedback.md` `# 사이클 04` "가져오기 실행 방법"·"실제 가져오기 결과 (2026-10-03 18:05)", plan `docs/research/maintenance/periodic-tasks.md` 3절. 빠지는 줄의 기준과 실행 조건은 코드(`backend/src/main/java/com/chaekcheck/collection/internal/importer/`)를 보고 적었습니다.
- 가져오기는 수집 SQLite의 원문을 정리하고 같은 책을 묶어 서비스 DB의 `catalog` 표(책, 사람, 소장)를 **지우고 새로 씁니다**(plan ADR-0031). 사이클 07부터 `collection` 모듈이 맡고(ADR-0060), 짝 표(`collection.collection_link`)에 있는 **모든 서비스 도서관**(등록 + 수집 목록에서 찾아 넣은 곳)의 책을 가져옵니다(ADR-0054). 책마다 가진 도서관 번호(`book.library_ids`)도 이때 계산합니다(ADR-0055). 정리·묶기 규칙의 근거는 plan `docs/research/ebook-catalog/search-and-normalization.md` 4절, `same-book-isbn.md` 5절, 주기 갱신 순서는 `catalog-refresh.md` 2절.

## 1. 언제 돌리나 (실행 조건)

**수집이 도는 중에도 돌려도 됩니다** (dev 판단, 2026-10-04. 2026-10-03에 수집 중 실행해 문제없었음).

- 수집기는 수집 DB를 WAL 방식으로 엽니다(`crawl_catalog.py`: `PRAGMA journal_mode=WAL`). 이 방식에서는 읽는 쪽과 쓰는 쪽이 서로를 막지 않습니다.
- 가져오기는 수집 DB를 읽기 전용으로 열고(`CollectedCatalog.java`: `open_mode=1`), 처음부터 끝까지 한 트랜잭션 안에서 **두 번** 읽습니다(첫 번째: 줄을 쌓지 않고 같은 책 묶음마다 값별 줄 수만 셈, 두 번째: 소장 줄을 바로 서비스 DB로 COPY, ADR-0054). 그래서 두 번 모두 **읽기 시작한 순간의 내용**을 보고, 그 뒤에 수집기가 쓴 줄은 들어가지 않습니다. 수집 DB에는 아무것도 쓰지 않습니다.
- 원본은 **배포용 사본**을 권합니다(사이클 07, 2026-10-05: `chaekcheck-data\snapshots\ebook-catalog-2026-10-05-0745.sqlite`). 사본은 바뀌지 않아 같은 사본으로 다시 가져오면 같은 결과가 나옵니다(AC-12).
- 주의할 것
  - 수집 중인 도서관은 그 순간까지 받은 줄로 들어갑니다. 다시 받는 중인 도서관은 지난번 줄이 아직 남아 있어(빠짐 표시는 전체 수집이 성공한 뒤에만 찍힘) 크게 달라지지 않습니다.
  - **새로 넣은 등록 도서관**은 첫 수집이 끝난 뒤에 가져옵니다. 덜 받은 채로 가져오면 그 도서관 책이 일부만 들어갑니다. 끝났는지: 수집 DB `SELECT id, name FROM library WHERE id IN (…) AND last_full_success_at IS NULL`이 비어 있어야 함.
  - 읽는 동안(2026-10-03 기준 약 1분 40초) 수집 DB의 `-wal` 파일이 평소보다 커질 수 있습니다. 끝나면 수집기가 다시 정리합니다.
- 서비스 DB 쪽 (사이클 08부터, plan ADR-0065): 새 목록은 `catalog_next` 스키마의 새 표에 쓰고, 색인·통계까지 마친 뒤 **짧은 트랜잭션 하나로 옛 표와 바꿔 끼웁니다**(8절). 그동안 서비스의 검색은 옛 표를 그대로 읽고, 기다리는 것은 바꿔 끼우는 순간(잠금 대기 포함, `step_seconds.swap`)뿐입니다. 그래서 **서비스를 켠 채로** 돌립니다. 바꿔 끼우기 전에 실패하면 옛 표가 그대로 남습니다(9절). 사이클 07까지는 `TRUNCATE` → 옮겨 쓰기 동안(2026-10-11 측정 9분 37초) 검색이 기다렸습니다.

## 2. 준비

| 준비할 것 | 위치 | 비고 |
|---|---|---|
| 수집 SQLite | 배포용 사본 `chaekcheck-data\snapshots\…sqlite` (또는 원본 `chaekcheck-data\ebook-catalog.sqlite`) | git 밖. 읽기만 함 |
| 국가서지 TTL 폴더 | `data/nlk/ttl` (저장소 안, git에 넣지 않음). 지금 파일: `data/nlk/ttl/book_ttl_20260807/book_0.ttl … book_19.ttl`(2026-08-07판 20개, 7.7GB) — 2026-10-15에 지워진 것을 2026-10-18 국립중앙도서관에서 다시 받아 풂(plan 사이클 19 회고 결정 #3, 사용자 "국가서지는 받아"). 원본 zip `C:\Users\yujun\Downloads\nlk-ttl-20260807\book_ttl_20260807.zip`(1,102,432,587바이트) | ISBN을 붙이는 데 씀. `ttl-path`로 읽을지는 plan 결정 뒤(지금은 `stored-bibliography=true` 그대로) |
| (TTL이 없을 때) 저장된 국가서지 표 | 설정 `chaekcheck.collection.import.stored-bibliography=true`(`ttl-path`는 비움) | 사이클 19: TTL 파일이 2026-10-15에 지워져, 마지막으로 풀어 둔 원문 표(`collection.national_bibliography_record`, 2026-10-11, `book_ttl_20260807`)를 그대로 읽음(사용자 결정 2026-10-16). 표가 비어 있으면 멈춤. 새 TTL을 받으면 다시 `ttl-path`를 씀 |
| 서비스 DB | `.env`의 개발 DB (compose 서비스 `db`) | 도서관 seed와 짝 표(`collection/002`)가 최신이어야 함. 가져오기는 짝 표로 수집 번호를 서비스 번호로 바꾸고, 그 서비스 도서관이 켜져 있고 주소(`base_url`)가 같은 줄만 받음 |
| 규칙 파일 두 개 | `scripts/library/rules/author_publisher_swapped.tsv`, `supplier_families.tsv` (설정 `chaekcheck.collection.import.rules-dir`, 기본 `../scripts/library/rules`) | 사이클 19. 수집 사본을 열기 전에 읽고, 없거나 틀리면 멈춤. **시작 전 [rules-index.md](rules-index.md)를 훑고** "확인하는 법"이 있는 줄(특히 공급사 이름, 뒤바뀐 포털)을 봄 |

## 3. 먼저 세기만 (dry run)

`backend/`에서 명령 끝에 `--chaekcheck.collection.import.dry-run=true`를 더해 실행합니다. 읽고 묶어서 숫자만 로그에 내고, DB에는 쓰지 않습니다.

    ./gradlew bootRun -PbootRunXmx=2g --args='--spring.profiles.active=collection-import --chaekcheck.collection.import.sqlite-path=../../chaekcheck-data/snapshots/ebook-catalog-2026-10-05-0745.sqlite --chaekcheck.collection.import.ttl-path=../data/nlk/ttl --chaekcheck.collection.import.dry-run=true'

- **힙**: `bootRun`의 기본 `-Xmx`는 서비스용 512MB라(사이클 08), 가져오기는 힙을 따로 줍니다. 기본 묶는 방식 `compact`는 **`-PbootRunXmx=2g`** 로 끝납니다(사이클 18·19 확인, plan ADR-0105). **6GB(`-PbootRunXmx=6g`)는 `objects` 방식만 필요합니다**: 2026-10-11 `objects`에 `-Xmx4g`는 첫 읽기 중 살아 있는 데이터가 4,066MB라 전체 GC만 반복하다 멈춤(못 씀). 6GB 결과는 5절 표.
- 프로필 `collection-import`는 웹 서버 없이 한 번 돌고 끝납니다(`application-collection-import.yml`). 로그인 기능은 이 프로필에서 늘 꺼집니다(웹 서버가 없어 켜면 시작하지 못함, 2026-10-05).
- 바꿔 끼우기 설정 `chaekcheck.catalog.load.*`(8절): `lock-timeout`(기본 2s), `swap-attempts`(5), `swap-retry-wait`(10s), 가져오기 세션에만 `maintenance-work-mem`·`max-parallel-maintenance-workers`·`work-mem`(기본 = 서버 값. 2026-10-11 가져오기는 `1GB`·`4`로 돌림).
- 설정 `chaekcheck.collection.import.*`: `sqlite-path`, `ttl-path`(비우면 국가서지 단계를 건너뜀), `dry-run`, `placeholder-date-libraries`(1950년 이전 날짜를 자리채움으로 보고 버릴 도서관의 **수집 번호**, 기본 `100555` 명지대).
  - 사이클 11: `mode`(`in-place` 기본·`export`·`apply`·`finish`·`discard`, 13절), `file`(내보낸 파일, `export`·`apply`·`finish`), `grouping`(책 묶는 방식 `compact` 기본(2026-10-12 사용자 결정)·`objects`(옛 방식, 2026-10-12까지 이름 `current`)·`sql`, ADR-0079. 세 방식은 책·번호까지 같고 힙과 시간이 다름: `compact` 힙 2GB·약 21분, `objects` 6GB·약 26분(사이클 12 새 규칙 뒤로는 살아 있는 데이터가 약 5.8GB라 6GB에서 전체 GC가 잦음, `-PbootRunXmx=8g` 권장), `sql` 1GB·약 34분이지만 DB 임시 쓰기 약 61GB), `dry-run-output`(dry run이 `books.tsv.gz`·`rows.tsv.gz`를 쓸 폴더. `scripts/catalog/compare_grouping.py dryrun`으로 두 방식을 비교).
  - 사이클 12: 묶음이 어떻게 바뀌었는지는 `python scripts/catalog/compare_grouping.py regroup <A> <B> [N]`으로 봅니다. A·B는 dry run 폴더나 `ids` 폴더(`compare_grouping.py ids`로 쓴 것)이고, 파일 이름으로 알아봅니다. 두 쪽에 다 있는 (도서관, 책 코드)로 맞대어 한쪽에만 있는 줄 수, 합쳐짐(B 한 책 ← A 여러 책)·나뉨(A 한 책 → B 여러 책)의 수와 앞 N개(기본 20, 제목·저자·출판사·ISBN·양쪽 책 번호)를 냅니다.
  - 둘 다 `ids`면 나뉨마다 A 번호를 이어 받은 B 책이 겹치는 줄이 가장 많은 책인지(ADR-0062) `yes`/`NO`로 붙습니다. 메모리는 A 줄 1,700만이면 약 0.5GB입니다. 테스트: `python -m unittest scripts/catalog/test_compare_grouping.py`.
  - 사이클 12: `exclude-added-later-libraries`(기본 `false`, 사이클 18까지 이름 `exclude-candidate-libraries`). 켜면 사이클 07에 나중에 더한 도서관(수집 번호 100001부터)을 빼고 셉니다. 그 줄은 건너뜀 이유 `library_excluded_added_later`(사이클 18까지 `library_excluded_candidate`, 지난 `catalog_import` 기록은 옛 문자열 그대로)로 세고, 나중에 더한 도서관 수에는 넣지 않으며, 로그에 `added-later libraries excluded: N`을 남깁니다. 다른 이유로 이미 빠지는 도서관은 그 이유 그대로입니다. **dry run 전용**: `dry-run=true`가 아니면 시작하지 않습니다(진짜 가져오기에서 켜면 약 2,370곳이 서비스에서 빠지기 때문, 가져오기 기록 11번의 나중에 더한 도서관 수).
  - 사이클 11: `chaekcheck.catalog.load.pg-command`(PostgreSQL 18 도구를 부르는 명령 앞부분, 쉼표로 나눔, 13-1). 커밋되는 파일에는 넣지 않고 명령 인자나 `.env`로 줍니다.
  - 사이클 11 측정: `-PbootRunJvmArgs="…"`로 Java 옵션을 더합니다(공백으로 나눔, `-PbootRunXmx`는 그대로).
- 사이클 04의 프로필 `catalog-import`, 설정 `chaekcheck.catalog.import.*`는 없어졌습니다.
- 로그 끝의 요약(읽은 줄, 이유별 건너뜀, 등록 도서관과 그 밖의 도서관 수, 묶기 단계별 책 수, 국가서지로 ISBN이 붙은 수, 시간, 힙 최고 사용량)을 5절 표의 지난 값과 비교합니다. 크게 다르면 사용자에게 알리고 멈춥니다 [안].

## 4. 실제 가져오기

1. 3절 명령을 `--chaekcheck.catalog.import.dry-run=true` 없이 실행합니다.
2. 확인: 서버를 띄우고 `/api/v1/books` 검색 몇 가지("행동"+"새폴스키", "작별하지 않는다", "사랑")가 나오는지 봅니다.
3. 결과 숫자를 5절 표에 한 줄 더하고, `docs/plan-feedback.md`에도 남깁니다. 가져오기마다 `collection.catalog_import`에 한 줄이 쌓입니다(책·소장·건너뜀 수, 등록 도서관 수 `registered_library_count`와 그 밖의 도서관 수 `other_library_count`, 이유별 건너뜀 `skipped_by_reason`).

## 5. 확인할 숫자 (지난 값)

> **용어 주의 (2026-10-24 덧붙임):** 아래 표의 "후보"(예: "등록 275 + 후보 2,381")는 옛 표현이라 기록에만 남아 있습니다. 뜻은 **사이클 07에서 수집 목록에서 찾아 새로 넣은 도서관**(처음 2,394곳)입니다. 이 표현을 옮겨 쓰지 말고, 어떤 도서관인지 구체적으로 씁니다(예: "도서관 2,656곳(처음부터 있던 275곳 + 사이클 07에서 새로 넣은 2,381곳)"). 근거: 사용자 "후보 도서관 표현 쓰지 말라고"(plan 사이클 13 회고 결정 #13, 2026-10-14). 지난 기록의 글자는 그대로 둡니다.

| 날짜 | 읽은 줄 / 건너뜀 | 도서관 | 책 수 (묶기 ①→②→③→④) | 쓴 것 | 시간 |
|---|---|---|---|---|---|
| 2026-10-03 18:05 | 9,077,458 / 4,123,098 (등록 도서관 아님 4,088,037, 오디오북 32,412, 동영상북 2,522, 빠진 책 119, http가 아닌 주소 8) | 275곳 (전체 성공 없는 곳 1) | 887,439 → 887,439 → 840,605 → 680,689 (④가 합친 묶음 159,916, 출판일 2년 넘게 차이 11,579권) | 책 680,689, 사람 746,721, 소장 4,954,360 | 읽기 1분 39초, 묶기 8초, 국가서지 3분 17초, 쓰기(COPY) 2분 27초 |
| 2026-10-05 18:53 (사이클 07, 사본 `ebook-catalog-2026-10-05-0745.sqlite`, 등록 + 후보) | 22,051,238 / 1,568,743 (서비스에 없음 1,467,650, 오디오북 93,537, 동영상북 5,400, 소리·영상 파일 1,747, http가 아닌 주소 283, 빠진 책 119, 제목 없음 7) | 등록 275 + 후보 2,394 = 2,669곳 (서비스에 없는 수집 도서관 344, 주소 다름 0) | 7,668,819 → 7,348,253 → 6,668,680 → 1,622,023 (④가 합친 묶음 5,046,657, 출판일 2년 넘게 차이 53,572권) | 책 1,622,023, 사람 1,817,529, 소장 20,482,495 | 읽기 8분 46초, 묶기 5초, 국가서지 8분 52초, 쓰기 17분 51초(두 번째 읽기 포함), 합계 35분 45초. dry run은 14분 15초 |
| 2026-10-05 21:15 (17단계 합친 뒤, 빈 DB, 같은 사본) | 22,051,238 / 1,667,356 (서비스에 없음 1,502,159, 주소 다름 64,461 = 도서관 411, 오디오북 93,213, 동영상북 5,368, 소리·영상 1,746, http 아님 283, 빠진 책 119, 제목 없음 7) | 등록 275 + 후보 2,391 (꺼짐 3: 135·2194·2198) | 7,604,518 → 7,283,952 → 6,615,840 → 1,617,445 | 책 1,617,445, 사람 1,812,646, 소장 20,383,882. 책 번호 kept 0 / new 1,617,445 / dropped 0 | 읽기 6분 57초, 국가서지 6분 45초, 쓰기 22분 43초(책 번호 잇기로 임시 표를 거침), 합계 36분 43초 |
| 2026-10-05 22:34 (2회차, `db` 복사본 포트 5434, 같은 사본) | 같음 | 같음 | 같음 | 같음. **책 번호 kept 1,617,445 / new 0 / dropped 0**, 도서관 2,646곳의 (책 코드 → 책 번호) 지문이 1회차와 모두 같음 | 읽기 9분 9초, 국가서지 6분 28초, 쓰기 26분 55초, 합계 40분 36초 |
| 2026-10-11 21:25 (사이클 08 #1, 지금 쓰기 방식, 교보 대여·혼합 11곳 뺀 뒤 빈 DB, 같은 사본) | 22,051,238 / 4,336,573 (서비스에 없음 4,171,376, 주소 다름 64,461, 오디오북 93,213, 동영상북 5,368, 소리·영상 1,746, http 아님 283, 빠진 책 119, 제목 없음 7) | 등록 275 + 후보 2,380 | 7,601,958 → 7,281,398 → 6,613,296 → 1,615,125 | 책 1,615,125, 사람 1,810,906, 소장 17,714,665. kept 0 / new 1,615,125 | 약 47분: 읽기 13분 6초, 국가서지 8분 15초(TTL), 같은 글 2분 1초, 쓰기 23분 30초(그중 **검색 멈춤 `move` 9분 37초**). 다른 작업과 CPU를 나눠 씀 |
| 2026-10-11 22:11 (사이클 08 #2, **새 표에 쓰고 바꿔 끼우기**, 서비스 켠 채, 같은 사본) | 같음 | 같음 | 같음 | 같음. **kept 1,615,125 / new 0 / dropped 0**, `holding.book_id` 상관도 1.0 | 약 41분: 읽기 13분 46초, 국가서지 7분 55초(TTL 풀며 원문 표를 처음 씀) + 키 46초, 같은 글 2분 6초, `load` 16분 50초(임시 표 COPY 9분 40초, 번호 잇기 2분 19초, `write` 2분 27초, `index` 1분 39초, `analyze` 43초, **`swap` 0.33초**). 가져오는 동안 "사랑" 첫 쪽 p95 560ms, 5xx 0. 디스크 최대 19.1GiB(시작 8.5GiB). 세션 `maintenance_work_mem` 1GB, 병렬 4 |
| 2026-10-11 23:49 (사이클 08 #3c, 바꿔 끼우기, `-Xmx6g`, DB 64MB / 768MB / 4MB) | 같음 | 같음 | 같음 | 같음. kept 1,615,125 / 0 / 0 | **약 33분**: 읽기 9분 44초, **국가서지 2분 46초(원문 표에서)** + 키 24초, 같은 글 1분 26초, `load` 18분 35초(`swap` 0.13초). 힙 6GB에서 전체 GC 2번, 살아 있는 데이터 최대 약 4.8GB |
| 2026-10-12 05:13 (사이클 11 7단계, **새 사본** `ebook-catalog-2026-10-12-0135.sqlite`, 내 PC `export`, `current`, `-Xmx6g`) | 26,144,897 / 8,366,096 (서비스에 없음 8,265,035, 오디오북 93,537, 동영상북 5,368, 소리·영상 1,747, http 아님 283, 빠진 책 119, 제목 없음 7) | 등록 275 + 후보 2,381 (주소 다름 0: 도서관 411 새 주소 들어옴) | 7,666,094 → 7,345,534 → 6,665,999 → 1,619,668 | 책 1,619,668, 사람 1,815,697, 소장 17,778,801. kept 1,615,124 / new 4,544 / dropped 1. 파일 606MB | 약 27분(읽기 8분 32초, 국가서지 2분 14초, 같은 글 1분 15초, `load` 12분 51초, 덤프 약 1분 22초). 운영 역할 `db-ops`(상한 1GB, 256MB) 반영 4분 35초(`restore` 65초, `index` 2분 30초, `analyze` 52초, `swap` 0.08초), 내 PC `finish` 1분 28초 |
| 2026-10-12 (사이클 11 11단계, 새 사본, 내보내기, 방식별) | 같음 | 같음 | 같음(세 방식 모두 책·번호까지 같음) | 같음 | `current`(지금 이름 `objects`) 약 26분·힙 6GB에서만 끝까지, `current`+`-XX:+UseCompactObjectHeaders -XX:+UseStringDeduplication` 약 25분·4GB, `compact` 약 21분·2GB, `sql` 약 34분·1GB(임시 쓰기 약 61GB, DB 최대 약 27GB). 표는 `docs/plan-feedback.md` `# 사이클 11` "11단계 최종" |

- 같은 날 국가서지: ISBN 붙음 60,901, 모호한 키 5,484. 교보 코드: ISBN 1,952,464줄, 한국 ISBN 아님 46,705, 확인 숫자 안 맞음 10,126.
- 2026-10-05 국가서지: 찾은 키 1,355,337, ISBN 붙음 707,518, 모호한 키 11,662. 교보 코드: ISBN 5,886,961줄, 한국 ISBN 아님 415,603, 확인 숫자 안 맞음 28,306. 명지대(100555) 1950년 이전 날짜 지움 17,311건.
- 2026-10-11 국가서지(#2): 원문 표 3,999,211줄(ISBN별), ISBN 붙음 695,892, 모호한 키 21,314(ISBN별 줄로 바뀐 뒤의 셈. 결과(붙은 수, 묶음 수)는 #1과 같음).
- 2026-10-11 DB 크기(#2 뒤): 8,550MB. `holding` 6,714MB, `book` 1,000MB, `book_contributor` 312MB.
- 2026-10-11 힙: `-Xmx4g`는 첫 읽기에서 멈춤(살아 있는 데이터 4,066MB, 전체 GC만 반복). 6GB(#3c)는 끝까지 됨(전체 GC 2번, 살아 있는 데이터 최대 약 4.8GB).
- 2026-10-05 힙: JVM 기본 최대 8,096MB(PC 메모리 32GB의 1/4)로 끝남. 로그의 "heap used up to 11,200MB"는 힙 구역별 최고치의 합이라 실제보다 큼(실제 최고치는 8GB 이하). 더 작은 최대 힙으로 되는지는 재지 않음.
- 2026-10-05 DB 크기(가져온 뒤, `VACUUM (ANALYZE)` 뒤): DB 전체 9,812MB. `holding` 8,353MB(표 6,035MB + 인덱스 `holding_library_item_key` 1,075MB, `holding_book_id_library_id` 803MB, `holding_pkey` 439MB), `book` 1,123MB(표 550MB, `book_library_ids_gin` 48MB, `book_title_norm_bigm` 110MB 등), `book_contributor` 324MB.
- 2026-10-05 첫 검색 응답(`scripts/catalog/measure_books.py`, 9개 검색어 × 지역 없음·지역 1개·가입 가능): 첫 쪽 82~393ms. 상세와 실행 계획은 `docs/plan-feedback.md` `# 사이클 07` 15단계.
- 같은 날 첫 검색 응답(웜업 전 1회): "행동"+"새폴스키" 22ms, "작별하지 않는다" 40ms, "사랑" 224ms(8,433권).

## 6. 빠지는 줄의 기준 (`collection/internal/importer/CollectedCatalog.java`)

| 로그의 이유 | 빠지는 줄 | 태그 |
|---|---|---|
| `library_not_in_service` | 짝 표에 없거나, 짝의 서비스 도서관이 꺼진 곳(같은 사이트로 뺀 곳, 전체 수집이 안 끝난 곳 등) | [확정] |
| `library_base_url_mismatch` | 짝은 있지만 주소가 서비스 도서관과 다른 곳(안전장치) | [확정] (사이클 07) |
| `kind_sound_video` | 북큐브 `content_kind` `book/wma`·`book/vod`·`book/wmv`, 교보 `동영상` | [확정] (사이클 07, ADR-0054) |
| `removed` | 수집기가 빠진 책으로 표시한 줄(`removed_at`) | [확정] |
| `kind_audiobook`, `kind_video_book`, `kind_null` | 오디오북, 동영상북, 종류를 모르는 줄 | [확정] 오디오북·동영상북 |
| `no_detail_url`, `detail_url_not_http`, `detail_host_mismatch` | 상세 주소가 없거나, http(s)가 아니거나, 호스트가 그 도서관 주소와 다른 줄 | [확정] |
| `no_title` | 제목이 빈 줄 | — |

## 7. 다시 가져와도 책 번호를 이어 씀 (2026-10-05)

- 정책: plan `catalog-refresh.md` 5.2절(2026-10-04). 구현: 사용자 요청 "이것도 같이 구현해 줘"(2026-10-05), 모듈은 사용자 결정 "catalog 해줘". handoff 범위 밖이라 plan-feedback `# 사이클 07`에 적음.
- 2026-10-05 이전에는 다시 가져오면 책 번호가 1부터 새로 매겨졌습니다.
- 방법 (`catalog/internal/JdbcCatalogLoad.java`의 `BookIds`):
  1. 새 책·사람·소장을 임시 표(`load_book`, `load_book_contributor`, `load_holding`, 커밋 때 사라짐)에 COPY합니다. 이때 옛 `catalog` 표는 그대로 있습니다.
  2. 새 소장과 옛 소장을 **(도서관 번호, 도서관의 책 코드 `item_key`)**로 맞대어, 새 책과 옛 책이 소장 몇 줄을 함께 갖는지 셉니다.
  3. 옛 책마다 가장 많이 겹치는 새 책을 고릅니다(나뉜 경우: 가장 많이 겹치는 쪽이 옛 번호를 가짐, 같으면 앞 책). 새 책 하나를 옛 책 여럿이 고르면(합쳐진 경우) **가장 작은 옛 번호**를 남깁니다.
  4. 고른 옛 번호가 없는 새 책은 지금까지 준 가장 큰 번호 다음부터 새 번호를 받습니다. 번호는 다시 쓰지 않습니다(합쳐져 없어진 번호도).
  5. 그다음 `catalog` 표를 비우고 임시 표에서 정한 번호로 옮깁니다. 모두 한 트랜잭션이라 실패하면 이전 내용이 남습니다.
- 안내표는 두지 않습니다. 합쳐져 없어진 번호로 들어오면 "책을 찾을 수 없어요"(5.2절).
- 기록: 로그 `book ids: kept N, new N, dropped N`, `collection.catalog_import`의 `kept_book_id_count`, `new_book_id_count`, `dropped_book_id_count`.
- 비용: 소장을 임시 표에 한 번 더 씁니다(디스크 임시 공간이 소장 표만큼 필요). 시간은 5절 표에 적습니다.
- 회원 데이터에는 아직 책 번호가 없어 고쳐 쓸 것이 없습니다. 책을 저장하는 기능이 생기면 3번에서 만든 옛 번호 → 새 번호 짝으로 같이 고쳐 써야 합니다(5.2절).

## 8. 새 표에 쓰고 바꿔 끼우기 (2026-10-11, 사이클 08, plan ADR-0065·0070)

코드: `catalog/internal/JdbcCatalogLoad.java`. 순서와 각 단계의 `step_seconds` 키:

| 순서 | 하는 일 | 키 |
|---|---|---|
| 1 | 남은 `catalog_next`가 있으면 지우고 새로 만듦. 가져오기 세션에만 메모리 설정 | — |
| 2 | 책·사람·소장을 임시 표(`load_*`)에 COPY (소장은 수집 DB 두 번째 읽기와 함께) | `books_copy`, `holdings_read`, `holdings_copy` |
| 3 | 좁은 맞대기 표 `load_match` 한 번으로 책 번호(7절)와 소장 번호를 정함. 같은 (도서관, `item_key`)는 옛 소장 번호, 새 소장은 지금까지 준 가장 큰 번호 다음부터 | `match` |
| 4 | `catalog_next`에 네 표를 색인·제약 없이 만들고(`LIKE … INCLUDING DEFAULTS IDENTITY …`) 책 번호 순으로 씀. 소장은 `book_id` 순으로 정렬해 씀(R8: 한 책의 줄이 같은 쪽에 모임) | `write` |
| 5 | 운영 책 목록 표 넷의 제약·색인을 정의 그대로(`pg_get_constraintdef`·`pg_get_indexdef`, 같은 이름) 만듦. 로그에 문장별 시간. 사이클 26부터 `catalog`의 책 설명 표는 건너뜀(`t.relname = ANY(TABLES)`) | `index` |
| 6 | `VACUUM (ANALYZE)` `catalog_next` 네 표 | `analyze` |
| 7 | 바깥 외래 키 확인 → 한 트랜잭션: `SET LOCAL lock_timeout` → **책 설명 정리**(아래) → 옛 네 표 `DROP` → `catalog_next` 네 표 `SET SCHEMA catalog` → `DROP SCHEMA catalog_next` → 커밋 | `swap` |

- 잠금: `DROP`은 표를 읽는 검색이 끝나기를 기다립니다. 그동안 **뒤에 온 검색도 그 뒤에 줄을 섭니다**(2026-10-11 `db-test` 시험: 긴 검색이 잠금을 잡은 동안 바꿔 끼우기가 기다리면, 뒤의 짧은 검색은 바꿔 끼우기가 `lock_timeout` 2초에 포기할 때까지 기다림). 그래서 한 번에 2초까지만 기다리고, 못 얻으면 10초 쉬었다가 다시(최대 5번) 합니다. 검색이 한 번에 기다리는 시간은 최대 2초입니다.
- 디스크: 바꿔 끼우기 직전에는 옛 표 + 임시 표 + 새 표가 함께 있어 DB가 크게 늘어납니다(10절 표의 최대값). 끝나면 임시 표와 옛 표가 지워집니다.
- **책 설명 정리 (사이클 26, plan ADR-0162)**: `catalog`의 책 설명 표 다섯(`book_summary`, `book_cover`, `book_profile`, `book_search`, `book_tag`)은 책 번호로만 잇고 외래 키가 없습니다. 바꿔 끼우는 트랜잭션 안, `DROP` 앞에서 `DELETE … WHERE NOT EXISTS (SELECT 1 FROM catalog_next.book b WHERE b.id = <표>.book_id)`로 새 목록에 없는 번호(합쳐져 버려진 번호, 사라진 책)의 설명을 지웁니다. 잠금을 못 얻어 되돌리면 지운 것도 되돌아갑니다. 표별 수는 로그 `catalog swap: book descriptions of dropped book ids deleted {…}`, 합은 `collection.catalog_import.description_deleted_count`(성공한 줄만, 실패·내보내기 줄은 NULL). 실행 계획은 `Hash Anti Join`, 표지 134만 줄에서 0.6초(시험 DB, 2026-10-28). 이 DELETE는 줄 잠금만 잡아 검색을 막지 않습니다. 그래도 남은 설명(합쳐진 책의 새 번호에 붙일 것)은 PC가 새 번호로 다시 만들어 `book-texts`·`book-profiles`로 넣습니다(`# export_id=` 첫 줄, [7-foryou.md](7-foryou.md) 2절)
- 번호: 책 번호(7절)와 **소장 번호**가 다시 가져와도 이어집니다(ADR-0070). 그래서 바꿔 끼우기 전에 연 책 화면에서 다음 묶음을 불러도 같은 소장을 가리킵니다. 목록에서 빠진 소장은 `holding_not_found`("이 도서관 목록에서 빠졌어요"), 빠진 책은 `book_not_found`("도서관 목록을 새로 받으면서 빠진 책일 수 있어요").

## 9. 실패하면

- 바꿔 끼우기 전 어디서 실패해도(읽기·쓰기·색인 실패, 프로세스 종료, 잠금 5번 실패) **옛 표가 그대로** 남고 `catalog_next`와 임시 표는 지워집니다. 프로세스가 죽어 못 지운 `catalog_next`는 다음 가져오기가 처음에 지웁니다.
- 기록: `collection.catalog_import`에 `status = 'FAILED'`, `failure_reason`(`lock_not_acquired` 잠금을 끝내 못 얻음, `outside_foreign_key` 다른 모듈 표가 catalog 표를 가리킴, `mismatch` 내보낸 파일과 이 DB가 다름(사이클 11, 13절), `error` 그 밖), `failure_detail`(예외 한 줄. `mismatch`는 무엇이 다른지와 양쪽 값). 숫자 칸은 멈출 때까지 센 값.
- 할 일: `lock_not_acquired`면 긴 요청이 없는 때 다시 돌림. `outside_foreign_key`면 그 외래 키를 만든 changeset을 확인하고 사용자에게 알림(모듈 경계 위반, handoff C2). 프로세스가 중간에 죽으면 그대로 다시 돌림.

## 10. `step_seconds` 읽는 법

`SELECT id, imported_at, status, step_seconds FROM collection.catalog_import ORDER BY id DESC LIMIT 3;` 값은 초입니다.

- `read`: 수집 DB 첫 읽기(거르기 + 묶을 값 세기). `group`: 교보 코드·ISBN으로 묶기. `nb`: 국가서지 원문 읽기(지문이 같으면 표에서, 다르면 TTL을 풀며 표를 다시 씀). `normalize`: 원문에서 키 만들기. `same_text`: 같은 글 묶기.
- `books_copy` … `swap`: 8절 표. `load`: 2~7 전체(첫 책부터 바꿔 끼우기 끝까지).
- 검색이 기다린 시간은 `swap`입니다(사이클 07 방식의 기록에는 `move`).
- 사이클 11(13절): 운영 `apply` 줄은 내 PC 키(`read`…`write`, `load`) 뒤에 이 DB 키 `compare`(파일과 비교), `restore`(`pg_restore`), `index`, `analyze`, `swap`, `apply`(반영 전체). 내 PC `finish` 줄은 내 PC 키 뒤에 `index`, `analyze`, `swap`, `finish`. 내보내기는 줄을 남기지 않고 로그에 `dump`(덤프와 되읽기)·`export`(전체)를 냅니다. 두 줄은 `export_id`로 이어집니다. dry run에 `dry-run-output`을 주면 `dump`(결과 파일 쓰기)가 더해집니다.
- 국가서지를 표에서 읽었는지는 로그 `national bibliography: rows … (from the stored table | from the TTL files)`로 봅니다.

## 11. 국가서지 원문 (2026-10-11, plan ADR-0069)

- 가져오기는 국가서지 TTL에서 뽑은 원문(ISBN, 제목, 저자, 출판사)을 `collection.national_bibliography_record`에 남기고, TTL 파일들의 지문(파일마다 `경로|크기|고친 시각`)을 `national_bibliography_source`에 한 줄 둡니다.
- 다음 가져오기에서 지문이 같으면 TTL을 열지 않고 표를 읽습니다. TTL 파일을 새로 받으면(크기나 시각이 바뀜) 다시 풉니다. 순서: 지문 줄 지우고 커밋 → 원문 표 다시 쓰기 → 맨 마지막에 지문 줄. 중간에 멈추면 지문이 없어 다음에 처음부터 풉니다.
- 키(제목·저자·출판사 다듬기)는 매번 지금 `catalog` 정리 규칙으로 원문에서 만듭니다. 규칙을 고쳐도 원문 표는 다시 쓸 필요가 없습니다.
- 한 기록에 ISBN이 여럿이면 ISBN마다 한 줄입니다(같은 키에 ISBN이 둘이면 그 키는 애매해져 ISBN을 붙이지 않음, 전과 같은 규칙).

## 12. 정리 규칙을 바꿀 때 [확정] (plan ADR-0069, 사용자 2026-10-11)

| 상황 | 할 일 |
|---|---|
| 새 사본으로 갱신 (규칙 그대로) | 가져오기 → 바꿔 끼우기. 서비스는 그대로 |
| 묶기·대표값·날짜 규칙을 고침 | 새 코드로 가져오기 → 바꿔 끼우기. 서비스는 그대로 |
| 검색어 다듬기 규칙을 고침 | 옛 서비스 켜 둔 채 새 코드로 가져오기 → 바꿔 끼우자마자 서비스를 새 코드로 다시 켬 |

- 저자 정리(`catalog.normalize`의 `Contributors`: 기호 떼기, 붙은 역할 낱말, 사이클 19)를 고치면 세 번째 줄입니다. 저장 이름과 저자 검색어가 같은 규칙을 씁니다.

- 세 번째 줄의 이유: 저장된 `title_norm` 같은 다듬은 값과 서비스가 검색어를 다듬는 방법이 같아야 찾힙니다. 바꿔 끼운 뒤 옛 서비스가 새 규칙의 표를 읽는 시간을 짧게 합니다.
- 예: 사이클 12의 ④ 같은 글자 규칙 바꿈(plan ADR-0082, 비교 제목 + 권 표시 + 첫 저자 + 출판사, 같은 키 안에서 ISBN이 둘 이상이면 판마다 나눔, 자리 채움 값은 값 없음)은 두 번째 줄입니다. `catalog`의 다듬기 함수와 검색은 그대로라 새 코드로 가져와 바꿔 끼우고, 서비스는 다시 켜지 않습니다. 묶음이 얼마나 바뀌었는지는 가져오기 전 `compare_grouping.py ids db <before> catalog`로 지금 번호를 받아 두고, 뒤에 `regroup <before> <after>`로 봅니다(3절). 바꿔 끼운 뒤에는 옛 번호 파일을 다시 만들 수 없습니다.

### 12-1. 뒤바뀜 규칙 줄 지우기 (사이클 19, plan ADR-0115)

`author_publisher_swapped.tsv`의 포털은 수집기가 저자·출판사를 바꿔 넣던 곳입니다. 고친 수집기(`PUBLISHER_FIRST_PORTAL_HOSTS`)를 main에 합친 날이 `swap_before`입니다. 그날 이후에 수집한 사본을 가져오면 다음처럼 멈춥니다.

    collection library 11 was collected at …, on or after swap_before … of author_publisher_swapped.tsv: before removing its row, check the copy with scripts/library/check_author_publisher_swap.py

1. 줄을 그냥 지우지 않습니다.
2. 그 사본으로 `python scripts/library/check_author_publisher_swap.py <사본.sqlite>`를 돌립니다. 포털 도서관마다 "바름 / 뒤바뀜 / 판단 못 함"과 줄 수를 냅니다.
3. 파일에 있는 도서관이 모두 **"바름"** 이면 그 줄들을 지우고(머리 줄은 남김) 커밋한 뒤 다시 가져옵니다.
4. "바름"이 아닌 곳이 있으면 지우지 않고 사용자에게 묻습니다(수집기 상수나 사이트 칸 순서를 다시 봐야 함, [rules-index.md](rules-index.md) 1절).

## 13. 따로 하기: 내 PC에서 가져오고 운영 DB에는 파일로 반영 (사이클 11, plan ADR-0078)

가져오기(읽기·묶기·번호 잇기·새 표 쓰기)는 내 PC에서 끝내고, 그 새 표 네 개를 `pg_dump -Fc` 파일 하나로 옮겨 운영 DB에서 바꿔 끼웁니다. 운영 DB에서는 무거운 읽기·묶기가 돌지 않습니다.

**같은 앱 판으로 돌립니다 [확정] (handoff C3).** `apply`는 운영 DB에 붙어 시작하므로 그 판의 Liquibase가 먼저 적용됩니다. 운영 서비스와 같은 커밋으로 돌립니다.

- **도서관 끄기 변경 파일이 있을 때** (사이클 18, plan ADR-0109, 예: `library/016-library-disabled-cycle18.yaml`): 끄기 변경 파일이 든 판으로 내 PC를 띄운 뒤 내보내기. 끄기 전 판으로 내보내면 반영 비교가 mismatch로 멈춤.

### 13-1. 도구 명령 (`pg-command`)

- pg 도구는 DB 컨테이너 이미지 안의 PostgreSQL 18판을 씁니다(새 의존성 없음). 앱이 이 명령 앞부분 뒤에 `pg_dump`/`pg_restore`와 인자를 붙여 실행합니다. 도구는 컨테이너 안 로컬 소켓으로 서비스와 같은 DB 사용자로 붙고 암호가 필요 없습니다(이미지의 `pg_hba` `local all all trust`, 사이클 11 프로토타입 3. 운영 DB가 다르게 구성되면 배포 때 다시 확인).
- 이 PC(Windows + WSL): `--chaekcheck.catalog.load.pg-command=wsl,-d,Ubuntu,--exec,docker,compose,-p,chaekcheck,exec,-T,<서비스>` (`<서비스>` = `db` 또는 `db-ops`). 앱이 붙은 DB와 같은 컨테이너여야 합니다. 다르면 덤프·복원 뒤 `export_id` 확인에서 멈춥니다.
- 파일은 표준 입출력으로 주고받습니다(컨테이너에 공유 폴더 없음). `pg_restore` 입력은 Java가 직접 씁니다. 그래서 Java가 죽으면 입력이 끊겨 복원이 롤백됩니다(파일을 자식 프로세스에 넘기면 Java가 죽은 뒤에도 끝까지 돌아 커밋됨, 프로토타입 4).
- `load_manifest`는 표의 한 줄이면서 같은 내용이 표의 COMMENT로도 들어 있습니다. 파이프로 쓴 `-Fc` 파일은 데이터 위치가 없고 `load_manifest` 데이터가 맨 뒤라, 표만 읽으면 파일 전체를 훑어야 합니다. COMMENT는 파일 앞의 목차에 있어 `pg_restore -s -t load_manifest`가 목차만 읽고 1초 안에 끝납니다.

### 13-2. 운영자 절차

내 PC는 `db`(5432), 운영 역할은 같은 PC의 `db-ops`(5434, `mem_limit` 1GB) 또는 진짜 운영 DB입니다. `backend/`에서 실행합니다.

1. **내 PC 내보내기** (`export`): 수집 사본으로 가져오기를 돌려 `catalog_next`를 채우고 파일 하나를 만듭니다. 내 PC 검색은 그대로입니다. `catalog_next`는 남아 "기다림"이 됩니다. 기록 줄은 남기지 않고 로그에 파일 경로·크기·`export_id`를 냅니다.

        ./gradlew bootRun -PbootRunXmx=2g --args='--spring.profiles.active=collection-import --chaekcheck.collection.import.mode=export --chaekcheck.collection.import.sqlite-path=../../chaekcheck-data/snapshots/<사본>.sqlite --chaekcheck.collection.import.ttl-path=../data/nlk/ttl --chaekcheck.collection.import.file=../data/cycle11/<이름>.dump --chaekcheck.catalog.load.pg-command=wsl,-d,Ubuntu,--exec,docker,compose,-p,chaekcheck,exec,-T,db'

2. **파일을 운영 쪽으로 옮김** (손으로. 인터넷으로 보내는 기능은 없음).
3. **운영 반영** (`apply`): 운영 DB에 붙여 돌립니다. 반영 연결의 `maintenance_work_mem`은 256MB입니다 [확정] (handoff C4. 1GB면 상한 512MB 컨테이너가 `OOMKilled`된 사례가 있음, 사이클 08).

        DB_PORT=5434 ./gradlew bootRun --args='--spring.profiles.active=collection-import --chaekcheck.collection.import.mode=apply --chaekcheck.collection.import.file=../data/cycle11/<이름>.dump --chaekcheck.catalog.load.maintenance-work-mem=256MB --chaekcheck.catalog.load.pg-command=wsl,-d,Ubuntu,--exec,docker,compose,-p,chaekcheck,exec,-T,db-ops'

   - 먼저 비교합니다: 도서관 해시(켜진 도서관의 `id`·`base_url`·`platform_type`), 연결표 해시(`collection_link`), `catalog`의 기준값 6개(`book`·`holding`의 줄 수·가장 큰 번호·시퀀스 값), 네 표 칸 구조 해시. 하나라도 다르면 아무것도 하지 않고 `FAILED mismatch` 한 줄(무엇이 다른지)을 남깁니다.
   - 같으면: 남은 `catalog_next`(와 남은 복원 세션) 지움 → `pg_restore --single-transaction --exit-on-error` → `export_id` 확인 → `load_manifest` 지움 → 운영 `catalog` 정의로 색인·제약 → `VACUUM (ANALYZE)` → 바꿔 끼우기(8절과 같음: 2초 잠금, 10초 쉬고, 최대 5번). 검색은 바꿔 끼우는 순간만 기다립니다.
   - 성공하면 `SUCCEEDED` 한 줄: 내 PC의 숫자·단계 + 이 DB 단계 + `export_id`.
4. **내 PC 마저 바꿔 끼우기** (`finish`, 운영 반영이 성공한 뒤): 같은 파일로 기다리던 `catalog_next`를 내 PC `catalog`로 바꿔 끼웁니다. 그래야 두 DB의 기준값이 다시 같아져 다음 파일을 반영할 수 있습니다. 세션 메모리는 내 PC 값(1GB)입니다.

        ./gradlew bootRun --args='--spring.profiles.active=collection-import --chaekcheck.collection.import.mode=finish --chaekcheck.collection.import.file=../data/cycle11/<이름>.dump --chaekcheck.catalog.load.maintenance-work-mem=1GB --chaekcheck.catalog.load.pg-command=wsl,-d,Ubuntu,--exec,docker,compose,-p,chaekcheck,exec,-T,db'

### 13-3. 상태와 할 일

| 상태 | 뜻 | 할 일 |
|---|---|---|
| `mismatch` | 파일을 만든 DB와 운영 DB가 다름(도서관·연결표·책 표·표 구조) | `failure_detail`을 보고 맞춤. 도서관·연결표는 두 DB의 seed·Liquibase 판을 같게, 책 표가 다르면 마지막 `finish`를 했는지 봄. 맞출 수 없으면 운영 역할 DB를 내 PC에서 다시 복사(13-4) |
| 기다림 | 내 PC `catalog_next`에 `load_manifest`가 있음(내보냄, 아직 `finish` 전) | `in-place`·`export`는 아무것도 지우지 않고 멈춤("first finish them … or discard them"). 운영 반영이 성공했으면 `finish`, 실패해서 이 파일을 버릴 때만 `discard` |
| 죽고 남은 표 | `load_manifest` 없는 `catalog_next`(도중에 죽음) | 다음 `in-place`·`export`·`apply`가 처음에 지움. `discard`로 지워도 됨 |
| `finish` 실패 | 색인·통계 단계에서 멈춤, 또는 잠금 5번 실패 | 기다림이 그대로 남음. 원인을 고치고 `finish`를 다시 돌리면 만들다 만 색인·제약부터 지우고 끝까지 감. 실패한 `finish`는 기록 줄을 남기지 않음 |
| `finish`가 "No new tables are waiting" | 내 PC에 그 파일의 기다리는 표가 없음 | 운영 반영이 성공했다면 같은 파일을 내 PC에 `apply`로 되살림(내 PC 기준값이 파일과 같아야 함) |

- `discard`: `--chaekcheck.collection.import.mode=discard`. 어느 DB에서든 `catalog_next`를 지웁니다. 기록 없음. **기다리던 표는 운영 반영이 실패했을 때만 지웁니다**(지우면 그 파일은 `finish`할 수 없음).
- 동시에 두 번 반영되지 않게 막는 장치는 없습니다(backlog). 한 번에 하나만 돌립니다.

### 13-4. 운영 역할 DB `db-ops` 만들기 (이 PC의 시험용)

- `compose.yaml`의 `db-ops`(프로필 `ops`, 포트 `OPS_DB_PORT` 기본 5434, 볼륨 `db-ops-data`, `mem_limit: 1g`). 평소에는 띄우지 않습니다.
- 처음에는 `db`를 통째로 복사해 기준을 같게 시작합니다 [확정]. 방법(2026-10-12): 두 컨테이너의 로컬 소켓끼리 파이프로 잇습니다(암호 없음).

        wsl -d Ubuntu -- docker compose --profile ops up -d db-ops
        wsl -d Ubuntu --exec bash -c 'set -o pipefail; docker compose -p chaekcheck exec -T db pg_dump -U <DB_USERNAME> -Fc <DB_NAME> | docker compose -p chaekcheck exec -T db-ops pg_restore -U <DB_USERNAME> -d <DB_NAME> --exit-on-error'

- 복사한 뒤 두 DB의 기준값·해시가 같은지는 첫 `apply`의 비교가 봅니다(다르면 `mismatch`).
- `db-ops`에 서비스를 띄우려면 `DB_PORT=5434 ./gradlew bootRun --args='--server.port=8081'`. `.env`의 `CHAEKCHECK_PICKS_FETCH_ENABLED=false`를 그대로 둡니다(켜지면 시작 받기가 진짜 국립중앙도서관 API를 부름).
- 측정 스크립트: `CHAEKCHECK_BASE=http://localhost:8081 CHAEKCHECK_DB_SERVICE=db-ops python scripts/catalog/probe_during_import.py <폴더>`.

### 13-5. 반영 파일 판 2: 도서관 부분 (사이클 13, plan ADR-0086·0093)

새 도서관은 Liquibase seed가 아니라 넣기 명령 `library-insert`로 내 PC `db`에 들어갑니다([1-libraries.md](1-libraries.md)). 운영 DB에는 반영 파일이 함께 옮깁니다.

- **판 2**: `load_manifest.format_version`이 2입니다. 판 1 파일은 판 2 앱이 읽지 않고(`manifest format 1 … reads 2`), 판 1 앱도 판 2 파일을 읽지 않습니다.
- **export**: `notes.libraryPart`에 내 PC의 **성공한 넣기 실행 기록 전부**(`collection.library_insert_run`의 `SUCCEEDED` 줄: 보고서 번호, 실행 시각, 사본, 넣은 수, 가입 조사 기준일, 넣은 도서관·가입 규칙·짝 줄 `payload`)를 싣습니다. 수백 곳이어도 수십 KB라 목차(COMMENT)로 1초 안에 읽힙니다.
- **apply 순서**: 한 트랜잭션에서 ① 넣기 명령과 같은 잠금(`pg_advisory_xact_lock`) ② 실행 기록마다(시각 순) 같은 보고서 번호의 성공 줄이 이미 있으면 건너뜀 ③ 없으면 없는 번호의 도서관·가입 규칙(`LibraryRegistry.add`), 없는 짝 줄, 실행 기록 줄(원래 시각) ④ 13-2의 비교. 비교가 다르면 도서관 부분도 롤백하고 `mismatch`. 같으면 커밋하고 책 쪽(복원·색인·바꿔 끼우기)을 시작합니다. `step_seconds`에 `library part`가 더해집니다.
- **도서관 부분이 실패하면**(제약 위반 등) 롤백하고 책 쪽을 시작하지 않습니다. 기록 줄은 `FAILED error`, `failure_detail`이 `library part: …`로 시작합니다.
- **도서관 부분 커밋 뒤 책 쪽이 실패하면**(`lock_not_acquired` 등) 도서관은 남습니다. 같은 파일을 다시 `apply`하면 도서관 부분은 건너뛰고 책 쪽을 다시 합니다.
- **판 2 앱을 배포하기 전에**: 내 PC에 판 1 내보내기가 기다리고 있으면(`catalog_next.load_manifest`) 판 1 앱으로 먼저 `finish`하거나 `discard`합니다. 판 2 앱은 판 1 파일을 `finish`할 수 없습니다.
- **로컬 DB를 새로 만들면**(Liquibase seed부터) seed 뒤에 `library-insert`를 다시 돌려야 넣었던 도서관이 돌아옵니다(seed에는 넣지 않음, ADR-0084). 운영 쪽은 반영 파일이 옮기므로 따로 돌리지 않습니다.

#### 로컬 DB 이름 맞추기 (사이클 13, `PRE_DEPLOYMENT` 동안 한 번)

사이클 13에서 `collection/001`의 `catalog_import.candidate_library_count`를 `other_library_count`로 그 자리에서 고쳤습니다(AC-11). 체크섬이 바뀌어 이미 만든 DB는 그대로 켜지지 않습니다. 데이터(가져온 책, 국가서지, 측정 결과)를 지키려고 `db`·`db-ops`에는 아래를 한 번 돌립니다(`db-test`는 컨테이너를 다시 띄움, tmpfs).

```sql
BEGIN;
ALTER TABLE collection.catalog_import RENAME COLUMN candidate_library_count TO other_library_count;
ALTER TABLE collection.catalog_import RENAME CONSTRAINT catalog_import_candidate_library_count_check TO catalog_import_other_library_count_check;
UPDATE databasechangelog SET md5sum = NULL WHERE id = 'collection-001-catalog-import' AND filename LIKE '%collection/001-collection-schema.yaml';
COMMIT;
```

`md5sum`이 NULL이면 다음 Liquibase 실행이 새 값으로 채웁니다.

## 14. 추천 목록만 넣기: 모드 `lists` (사용자 2026-10-19)

책 가져오기 없이 추천 목록(이동진·서울대·KAIST, 목록 파일 `scripts/crawl/lists/`)만 DB에 넣습니다. 목록 파일을 고쳤거나 새 목록(예: `kaist.tsv`)을 넣을 때, 다음 가져오기를 기다리지 않고 씁니다.

- 하는 일: 목록 폴더 검사(`fields.tsv`·`leedongjin.tsv`·`snu100.tsv`·`kaist.tsv`·`nobel.tsv`(사용자 2026-10-20), 없거나 틀리면 파일·줄·칸을 대고 멈춤) → 그 DB에 지금 있는 책 표(`catalog`)에 맞추기(export와 같은 규칙: 고정 판의 ISBN → 제목·첫 저자·출판사, 고정 판 없으면 그 줄의 ISBN → 제목·저자) → 트랜잭션 하나로 세 목록의 분야·책 줄을 통째로 바꾸고 표지를 더하고 안 쓰는 표지를 지움. 사서 추천 줄은 건드리지 않습니다
- 건드리지 않는 것: 책 표(`catalog`), `catalog_next`, 가져오기 기록 줄(`collection.catalog_import`). 줄을 남기지 않고 로그에 냅니다: `recommended lists matched: {leedongjin=Counts[rows=…, matched=…, unmatched=…], …}`, 경고(30일 넘게 확인 안 한 영상, 표지 파일 없음), `recommended lists put in: Replaced[fields=…, books=…, coversAdded=…, coversDeleted=…]`
- 폴더의 목록이 전부입니다: 세 목록 모두 폴더 내용으로 바뀝니다. 한 목록만 고쳤어도 폴더 전체(커밋한 판)를 씁니다
- 실패하면(파일이 틀림, 잠금·DB 오류) 오류를 내고 끝나며(종료 코드 1) 전 목록이 그대로 남습니다
- 화면: 떠 있는 서비스의 추천 캐시는 10분마다 다시 읽으므로(`chaekcheck.picks.cache.refresh-interval`) 10분 안에 바뀝니다. 새 목록 키(`kaist`)는 그 키를 아는 판(이 변경이 든 판)이 떠 있어야 보입니다

### 14-1. 내 PC

`backend/`에서. DB는 `../.env`(`DB_PORT` 기본 5432), 목록 폴더는 `chaekcheck.collection.import.lists-dir`(기본 `../scripts/crawl/lists`, `application-collection-import.yml`). 웹 서버 없이 끝나면 저절로 나갑니다.

    ./gradlew bootRun --args='--spring.profiles.active=collection-import --chaekcheck.collection.import.mode=lists'

- 다른 DB: `DB_PORT=5434 ./gradlew bootRun --args='…'`(`db-ops`), 다른 폴더: `--chaekcheck.collection.import.lists-dir=<폴더>`

### 14-2. 운영 서버 (EC2)

떠 있는 백엔드와 **같은 판의 이미지**를 한 번만 도는 컨테이너로 돌립니다(그 판의 Liquibase가 먼저 돌지만 이미 적용돼 할 일 없음). 이미지의 jar에는 SQLite 드라이버가 없지만 이 모드는 수집 사본을 열지 않습니다(14-3에서 jar로 확인).

1. 내 PC(Git Bash, 저장소 맨 위): 목록 폴더를 서버로 옮김

        ssh -i .secrets/chaekcheck-key.pem ubuntu@52.64.117.112 'rm -rf /srv/chaekcheck/lists'
        scp -i .secrets/chaekcheck-key.pem -r scripts/crawl/lists ubuntu@52.64.117.112:/srv/chaekcheck/lists

   목록 그림(`scripts/crawl/lists/covers/`)은 서버로 복사하지 않습니다. 새 그림은 PC가 S3에 `covers/<sha1(cover_source)>.jpg`로 올립니다([8-covers.md](8-covers.md) 2절). 서버의 `/srv/chaekcheck/covers` 복사와 nginx `/covers/`는 사이클 26 배포 2(v1.20.6)부터 없음.

2. 서버(`/srv/chaekcheck`): `secrets.env`의 `DB_PASSWORD`를 괄호 안 셸에서만 읽어 넘김. `secrets.env`는 값이 작은따옴표로 싸여 있어(`up.sh`) `docker run --env-file`로 주면 따옴표까지 값이 되므로 쓰지 않습니다. `prod.env`(로그인·이용 기록 켜기)는 이 모드에 필요 없습니다(`collection-import`가 로그인을 끔). DB 주소·이름·사용자는 `compose.yaml`의 `backend`와 같습니다. `<판>` = `compose.yaml`의 `backend` `image:` 판

        cd /srv/chaekcheck
        ( set -a; . ./secrets.env; set +a
          docker run --rm --network chaekcheck_default \
            -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/chaekcheck \
            -e DB_NAME=chaekcheck -e DB_USERNAME=chaekcheck -e DB_PASSWORD \
            -e TZ=Asia/Seoul -e JAVA_TOOL_OPTIONS='-Xmx384m -XX:+ExitOnOutOfMemoryError' \
            -v /srv/chaekcheck/lists:/lists:ro \
            chaekcheck/backend:<판> \
            --spring.profiles.active=collection-import \
            --chaekcheck.collection.import.mode=lists \
            --chaekcheck.collection.import.lists-dir=/lists )

- [안] 네트워크 이름 `chaekcheck_default`: compose 프로젝트 이름 `chaekcheck`(`name:`)의 기본 네트워크. 서버에서 `docker network ls`로 확인
- [안] 힙 `-Xmx384m`: 2GB 서버에서 백엔드(`-Xmx640m`)와 함께 돌 때의 값. 목록 80~수백 줄과 표지 몇십 장이라 충분할 것으로 봄, 서버에서 처음 돌릴 때 확인
- [안] 컨테이너 사용자(uid 10001)가 `/srv/chaekcheck/lists`를 읽을 수 있어야 함(scp 기본 권한 644/755면 됨)
- `docker compose run backend …`는 쓰지 않습니다: 서비스의 네트워크 별칭 `backend-host`가 붙으면 nginx의 `/api`가 이 컨테이너로 갈 수 있음 [안]

### 14-3. 확인한 것 (2026-10-19, 내 PC `db`)

- 운영 이미지와 같은 jar(`./gradlew bootJar`, SQLite 드라이버 없음)로 `backend/`에서 `java -jar build/libs/chaekcheck.jar --spring.profiles.active=collection-import --chaekcheck.collection.import.mode=lists` → 종료 코드 0, 약 10초. Liquibase `picks-003-*`·`usagelog-006-*` 적용 뒤 `leedongjin` 43줄 중 36 맞춤, `snu100` 100줄 중 87, `kaist` 80줄 중 29(자연 10·AI 9·문화 10·AI대학 0, AI대학은 영문 원서). `Replaced[fields=18, books=223, coversAdded=53, coversDeleted=0]`
- 시험: `CatalogImportModesTests`(`listsModePutsInOnlyTheListsMatchedToTheLiveCatalog`, `listsModeWithAWrongFileStopsAndKeepsTheListsBefore`), `CuratedListTransferTests`(KAIST 파일 검사·맞추기)
