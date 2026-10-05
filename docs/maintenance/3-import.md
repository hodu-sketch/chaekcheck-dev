# 가져오기: 받은 목록을 서비스 DB로

- 정기 유지보수의 **가져오기 절차 원본**입니다(plan ADR-0047, 2026-10-04). 순서 전체는 [README.md](README.md). plan 짝 문서: `../chaekcheck-plan/docs/research/collection/2-books.md` 6절.
- 옮겨 온 곳: `docs/plan-feedback.md` `# 사이클 04` "가져오기 실행 방법"·"실제 가져오기 결과 (2026-10-03 18:05)", plan `docs/research/maintenance/periodic-tasks.md` 3절. 빠지는 줄의 기준과 실행 조건은 코드(`backend/src/main/java/com/chaekcheck/collection/internal/importer/`)를 보고 적었습니다.
- 가져오기는 수집 SQLite의 원문을 정리하고 같은 책을 묶어 서비스 DB의 `catalog` 표(책, 사람, 소장)를 **지우고 새로 씁니다**(plan ADR-0031). 사이클 07부터 `collection` 모듈이 맡고(ADR-0060), 짝 표(`collection.collection_link`)에 있는 **모든 서비스 도서관**(등록 + 후보)의 책을 가져옵니다(ADR-0054). 책마다 가진 도서관 번호(`book.library_ids`)도 이때 계산합니다(ADR-0055). 정리·묶기 규칙의 근거는 plan `docs/research/ebook-catalog/search-and-normalization.md` 4절, `same-book-isbn.md` 5절, 주기 갱신 순서는 `catalog-refresh.md` 2절.

## 1. 언제 돌리나 (실행 조건)

**수집이 도는 중에도 돌려도 됩니다** (dev 판단, 2026-10-04. 2026-10-03에 수집 중 실행해 문제없었음).

- 수집기는 수집 DB를 WAL 방식으로 엽니다(`crawl_catalog.py`: `PRAGMA journal_mode=WAL`). 이 방식에서는 읽는 쪽과 쓰는 쪽이 서로를 막지 않습니다.
- 가져오기는 수집 DB를 읽기 전용으로 열고(`CollectedCatalog.java`: `open_mode=1`), 처음부터 끝까지 한 트랜잭션 안에서 **두 번** 읽습니다(첫 번째: 줄을 쌓지 않고 같은 책 묶음마다 값별 줄 수만 셈, 두 번째: 소장 줄을 바로 서비스 DB로 COPY, ADR-0054). 그래서 두 번 모두 **읽기 시작한 순간의 내용**을 보고, 그 뒤에 수집기가 쓴 줄은 들어가지 않습니다. 수집 DB에는 아무것도 쓰지 않습니다.
- 원본은 **배포용 사본**을 권합니다(사이클 07, 2026-10-05: `chaekcheck-data\snapshots\ebook-catalog-2026-10-05-0745.sqlite`). 사본은 바뀌지 않아 같은 사본으로 다시 가져오면 같은 결과가 나옵니다(AC-12).
- 주의할 것
  - 수집 중인 도서관은 그 순간까지 받은 줄로 들어갑니다. 다시 받는 중인 도서관은 지난번 줄이 아직 남아 있어(빠짐 표시는 전체 수집이 성공한 뒤에만 찍힘) 크게 달라지지 않습니다.
  - **새로 넣은 등록 도서관**은 첫 수집이 끝난 뒤에 가져옵니다. 덜 받은 채로 가져오면 그 도서관 책이 일부만 들어갑니다. 끝났는지: 수집 DB `SELECT id, name FROM library WHERE id IN (…) AND last_full_success_at IS NULL`이 비어 있어야 함.
  - 읽는 동안(2026-10-03 기준 약 1분 40초) 수집 DB의 `-wal` 파일이 평소보다 커질 수 있습니다. 끝나면 수집기가 다시 정리합니다.
- 서비스 DB 쪽: 쓰기는 한 트랜잭션(`catalog`의 `CatalogLoad.replaceAll` = `JdbcCatalogLoad.java`: `TRUNCATE` → COPY → 커밋, 그 뒤 `VACUUM (ANALYZE)`)이라 실패하면 이전 내용이 그대로 남습니다. 다만 `TRUNCATE`가 표 전체를 잠그므로, **쓰는 동안 서비스의 책 검색은 기다립니다**(배포 전이라 받아들임 [확정], spec R7-5). 사용자가 있는 서비스라면 사용이 적은 시간에 돌립니다 [안].

## 2. 준비

| 준비할 것 | 위치 | 비고 |
|---|---|---|
| 수집 SQLite | 배포용 사본 `chaekcheck-data\snapshots\…sqlite` (또는 원본 `chaekcheck-data\ebook-catalog.sqlite`) | git 밖. 읽기만 함 |
| 국가서지 TTL 폴더 | `data/nlk/ttl` (저장소 안, git에 넣지 않음) | ISBN을 붙이는 데 씀 |
| 서비스 DB | `.env`의 개발 DB (compose 서비스 `db`) | 도서관 seed와 짝 표(`collection/002`)가 최신이어야 함. 가져오기는 짝 표로 수집 번호를 서비스 번호로 바꾸고, 그 서비스 도서관이 켜져 있고 주소(`base_url`)가 같은 줄만 받음 |

## 3. 먼저 세기만 (dry run)

`backend/`에서 명령 끝에 `--chaekcheck.collection.import.dry-run=true`를 더해 실행합니다. 읽고 묶어서 숫자만 로그에 내고, DB에는 쓰지 않습니다.

    ./gradlew bootRun --args='--spring.profiles.active=collection-import --chaekcheck.collection.import.sqlite-path=../../chaekcheck-data/snapshots/ebook-catalog-2026-10-05-0745.sqlite --chaekcheck.collection.import.ttl-path=../data/nlk/ttl --chaekcheck.collection.import.dry-run=true'

- 프로필 `collection-import`는 웹 서버 없이 한 번 돌고 끝납니다(`application-collection-import.yml`). 로그인 기능은 이 프로필에서 늘 꺼집니다(웹 서버가 없어 켜면 시작하지 못함, 2026-10-05).
- 설정 `chaekcheck.collection.import.*`: `sqlite-path`, `ttl-path`(비우면 국가서지 단계를 건너뜀), `dry-run`, `placeholder-date-libraries`(1950년 이전 날짜를 자리채움으로 보고 버릴 도서관의 **수집 번호**, 기본 `100555` 명지대).
- 사이클 04의 프로필 `catalog-import`, 설정 `chaekcheck.catalog.import.*`는 없어졌습니다.
- 로그 끝의 요약(읽은 줄, 이유별 건너뜀, 등록·후보 도서관 수, 묶기 단계별 책 수, 국가서지로 ISBN이 붙은 수, 시간, 힙 최고 사용량)을 5절 표의 지난 값과 비교합니다. 크게 다르면 사용자에게 알리고 멈춥니다 [안].

## 4. 실제 가져오기

1. 3절 명령을 `--chaekcheck.catalog.import.dry-run=true` 없이 실행합니다.
2. 확인: 서버를 띄우고 `/api/v1/books` 검색 몇 가지("행동"+"새폴스키", "작별하지 않는다", "사랑")가 나오는지 봅니다.
3. 결과 숫자를 5절 표에 한 줄 더하고, `docs/plan-feedback.md`에도 남깁니다. 가져오기마다 `collection.catalog_import`에 한 줄이 쌓입니다(책·소장·건너뜀 수, 등록·후보 도서관 수, 이유별 건너뜀 `skipped_by_reason`).

## 5. 확인할 숫자 (지난 값)

| 날짜 | 읽은 줄 / 건너뜀 | 도서관 | 책 수 (묶기 ①→②→③→④) | 쓴 것 | 시간 |
|---|---|---|---|---|---|
| 2026-10-03 18:05 | 9,077,458 / 4,123,098 (등록 도서관 아님 4,088,037, 오디오북 32,412, 동영상북 2,522, 빠진 책 119, http가 아닌 주소 8) | 275곳 (전체 성공 없는 곳 1) | 887,439 → 887,439 → 840,605 → 680,689 (④가 합친 묶음 159,916, 출판일 2년 넘게 차이 11,579권) | 책 680,689, 사람 746,721, 소장 4,954,360 | 읽기 1분 39초, 묶기 8초, 국가서지 3분 17초, 쓰기(COPY) 2분 27초 |
| 2026-10-05 18:53 (사이클 07, 사본 `ebook-catalog-2026-10-05-0745.sqlite`, 등록 + 후보) | 22,051,238 / 1,568,743 (서비스에 없음 1,467,650, 오디오북 93,537, 동영상북 5,400, 소리·영상 파일 1,747, http가 아닌 주소 283, 빠진 책 119, 제목 없음 7) | 등록 275 + 후보 2,394 = 2,669곳 (서비스에 없는 수집 도서관 344, 주소 다름 0) | 7,668,819 → 7,348,253 → 6,668,680 → 1,622,023 (④가 합친 묶음 5,046,657, 출판일 2년 넘게 차이 53,572권) | 책 1,622,023, 사람 1,817,529, 소장 20,482,495 | 읽기 8분 46초, 묶기 5초, 국가서지 8분 52초, 쓰기 17분 51초(두 번째 읽기 포함), 합계 35분 45초. dry run은 14분 15초 |

- 같은 날 국가서지: ISBN 붙음 60,901, 모호한 키 5,484. 교보 코드: ISBN 1,952,464줄, 한국 ISBN 아님 46,705, 확인 숫자 안 맞음 10,126.
- 2026-10-05 국가서지: 찾은 키 1,355,337, ISBN 붙음 707,518, 모호한 키 11,662. 교보 코드: ISBN 5,886,961줄, 한국 ISBN 아님 415,603, 확인 숫자 안 맞음 28,306. 명지대(100555) 1950년 이전 날짜 지움 17,311건.
- 2026-10-05 힙: JVM 기본 최대 8,096MB(PC 메모리 32GB의 1/4)로 끝남. 로그의 "heap used up to 11,200MB"는 힙 구역별 최고치의 합이라 실제보다 큼(실제 최고치는 8GB 이하). 더 작은 최대 힙으로 되는지는 재지 않음.
- 2026-10-05 DB 크기(가져온 뒤, `VACUUM (ANALYZE)` 뒤): DB 전체 9,812MB. `holding` 8,353MB(표 6,035MB + 인덱스 `holding_library_item_key` 1,075MB, `holding_book_id_library_id` 803MB, `holding_pkey` 439MB), `book` 1,123MB(표 550MB, `book_library_ids_gin` 48MB, `book_title_norm_bigm` 110MB 등), `book_contributor` 324MB.
- 2026-10-05 첫 검색 응답(`scripts/catalog/measure_books.py`, 9개 검색어 × 지역 없음·지역 1개·가입 가능): 첫 쪽 82~393ms. 상세와 실행 계획은 `docs/plan-feedback.md` `# 사이클 07` 15단계.
- 같은 날 첫 검색 응답(웜업 전 1회): "행동"+"새폴스키" 22ms, "작별하지 않는다" 40ms, "사랑" 224ms(8,433권).

## 6. 빠지는 줄의 기준 (`collection/internal/importer/CollectedCatalog.java`)

| 로그의 이유 | 빠지는 줄 | 태그 |
|---|---|---|
| `library_not_in_service` | 짝 표에 없거나, 짝의 서비스 도서관이 꺼진 곳(같은 사이트로 뺀 후보, 전체 수집이 안 끝난 후보 등) | [확정] |
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
