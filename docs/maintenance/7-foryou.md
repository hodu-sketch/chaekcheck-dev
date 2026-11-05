# 「나에게 맞는 책」 새벽 만들기와 넣기 (사이클 23)

- 기준: plan `docs/cycles/23-for-you-picks/handoff.md`, `design-delta.md`(ADR-0147·0148·0149). 설정 값은 [settings-index.md](settings-index.md) "사이클 23" 줄.
- 모듈 `foryou`(`backend/src/main/java/com/chaekcheck/foryou/`), 스키마 `foryou`, 꼬리표·사전은 `catalog.tag_dictionary`·`catalog.book_tag`(사이클 26부터).

## 0. 지금 운영 (2026-10-28)

- 탭 이름은 **「책첵 추천」**(`/picks/for-you`). 구조·숫자·결정의 모음은 [`docs/features/foryou/README.md`](../features/foryou/README.md)(사이클 23 문서 색인)
- **새벽 만들기는 운영에서 꺼져 있음**(`CHAEKCHECK_FORYOU_BUILD_ENABLED=false`): 기본 목록은 추천목록 책을 보유 도서관 많은 순으로(아래 1절은 켤 때의 설명)
- 개인 추천 순서: 이웃 순위대로 기준 책을 돌아가며(`SimilarOrder.byCloseness`, v1.15.1). 새벽 점수로 다시 줄 세우지 않음
- 자료(2026-10-26 10만 권 판, 가져오기 14번 export d09d649f): `book_neighbour` 81,080행 · `book_cover` 80,091 · `book_blurb` 68,414. 계산·넣기는 2절, PC 파일은 데이터 폴더 `ml/foryou-100k/`
- AI 검색도 같은 이웃 표를 씀(`BookNeighbours`, 「○○ 같은 책」·이어 묻기). 이웃 표 범위를 바꾸면 AI 검색 범위(`catalog.book_search`)와 맞출 것 — 안 맞추면 AI 검색이 쓸 수 있는 이웃이 줄어듦(2026-10-26: 19.85 → 9.47권)
- 사이클 26에서 요약·표지·꼬리표가 `catalog`로 옮겨 감(plan `docs/cycles/26-book-descriptions/`)

## 1. 새벽 만들기 (자동)

- 매일 03:30(Asia/Seoul)과 서버를 켤 때(오늘 succeeded가 없으면) 만듭니다. 켤 때는 먼저 남아 있던 `running`을 `failed`로 바꿉니다.
- 차례: 출처 읽기 → 줄을 우리 책에 맞춤(그 도서관 소장 줄 `(library_id, item_key)` → ISBN → 제목+첫 저자) → 사이트·출처마다 0줄이거나 맞춤 90% 미만이면 그날 그것만 뺌 → 같은 책 판 모으기(본제목 정리값 같고 저자 공유, 대표 = 소장 많은 판) → 점수 → 차례(R2-4) → 40권 저장 → 메모리 사본 바꿈 → 30일 지난 실행 지움(마지막 succeeded는 남김).
- 도서관 사이트는 `availability.RankedListings`로 읽습니다: 조회와 같은 나가는 길·길마다 호스트 1건·1초. `*.dkyobobook.co.kr`에는 보내지 않습니다. 조회 상태 불과 `library_response_daily`에는 들어가지 않고, 1분 요약 로그·`availability.lookup_sample`의 길 요청 수에는 들어갑니다.
- 볼 곳
  - `foryou.build_run`: `status`, `note`(줄마다 `kyobo_elib:31 rows=100 matched=99 kept` 또는 `… left_out (이유)`, 끝에 `base= popular= listed= scored=`)
  - 로그: `foryou build N succeeded: …`, 그 뒤 `foryou base position=1 book=… total=… demand=… recent=… boost=… sales=… top200=… klwave=… title="…"` 40줄(가중치 조정용, R2-3a)
  - 실패: `foryou build N failed, the list before stays` — 화면은 전날 목록 그대로
- 이틀 연속 한 출처가 0줄이면 그 출처를 끄고(`chaekcheck.foryou.sources.<출처>.enabled=false` 또는 사이트 번호 빼기) plan에 알립니다(`to-dev-popular-list-check.md` 1절 5번).

### 인기 사이트 번호

- 기본값: 교보 일반 79(서초구립반포), 99(연천군), 60(도봉문화정보), 73(부천시립) / YES24 V1 263(국립세종도서관(YES24)), 229(성남시도서관사업소). seed `library/004`·`005`와 사전 시험 표의 번호가 같습니다. 바뀌었는지 볼 때:

```sql
SELECT id, name, platform_type, base_url FROM library.library
WHERE platform_type IN ('KYOBO_V1', 'YES24_V1')
  AND (name LIKE '%반포%' OR name LIKE '%연천%' OR name LIKE '%도봉%' OR name LIKE '%부천%'
       OR name LIKE '%국립세종%' OR name LIKE '%성남%')
ORDER BY platform_type, name;
```

- 바꿀 때는 `CHAEKCHECK_FORYOU_SOURCES_KYOBOELIB_LIBRARYIDS`, `CHAEKCHECK_FORYOU_SOURCES_YES24ELIB_LIBRARYIDS`(쉼표)로 덮습니다.

### 체크포인트 B·C를 내 PC에서 돌리기

1. 로컬 `db`가 운영 꼴 사본(책 표 반영 뒤)인지 확인합니다.
2. `../.env`에 나가는 길(`CHAEKCHECK_AVAILABILITY_EGRESS_*`, PC는 Webshare 아이디·비밀번호 쌍)을 둡니다. 돌고 있는 책 수집이 쓰는 서버에는 보내지 않습니다: 수집 중이면 그 사이트를 잠시 뺍니다.
3. `backend/`에서 `./gradlew bootRun` → 켤 때 한 번 만듭니다(오늘 succeeded가 있으면 `DELETE FROM foryou.build_run WHERE built_on = current_date`로 지우고 다시 켬).
4. B: `build_run.note`의 사이트마다 `rows`·`matched`, 로그의 `ranked_list_failed`, 걸린 시간(`started_at`\~`finished_at`)을 plan-feedback `# 사이클 23`에 적습니다.
5. C: 로그의 `foryou base position=…` 40줄로 표(책, 출판 연도, 점수, 재료별 점수, ①인지 = `popular=true`이면서 추천목록)를 만들어 사용자에게 보입니다. 추천목록 책은 사이클 24가 main에 들어간 뒤에야 들어옵니다(그 전에는 인기 출처만).

## 2. 넣기 (수동, 수집 PC 결과)

- `collection` 넣기 명령(프로필 `collection-import`)의 모드로 넣습니다(사이클 23 인계 3-3, 사이클 26): 모드 `neighbours`·`tag-dictionary`·`book-profiles`(사이클 26), `book-texts`의 kind `for_you_blurb`·`tags`·`cover_file`. 코드: `collection/internal/foryou/`, `collection/internal/booktexts/`. 서버를 켤 필요 없이 명령이 끝나면 나갑니다.
- **사이클 26부터 이웃 말고는 모두 `catalog`에 씁니다**(ADR-0161): 요약(`for_you_blurb`) → `catalog.book_summary`, 표지(`cover_file`) → `catalog.book_cover`, 꼬리표·사전 → `catalog.book_tag`·`tag_dictionary`, 프로필(`book-profiles`) → `catalog.book_profile`. 이웃만 `foryou.book_neighbour`.
- **언제 보이나**: 요약·표지는 넣으면 바로(책첵 추천·AI 검색·책 상세는 요청마다 읽음, 추천목록 줄은 목록 캐시를 다시 읽을 때 10분 안), 꼬리표는 `picks` 캐시를 다시 읽을 때 10분 안, 프로필은 **서버를 다시 켜야** AI 검색 1차 호출이 읽음(시작 로그 `ai search first call: ml, N book profiles from catalog.book_profile`).
- **`book-texts`·`book-profiles`·`neighbours` 파일은 첫 줄이 `# export_id=<uuid>`**(그 파일의 책 번호를 만든 가져오기). 그 DB의 마지막 SUCCEEDED 가져오기와 다르면 아무것도 넣지 않고 거절합니다. 번호가 같다고 확실할 때만 `--chaekcheck.collection.import.force=true`. `book-texts`는 둘째 줄이 헤더입니다.
- 표지 그림은 넣기 전에 PC가 S3에 올려 둡니다: [8-covers.md](8-covers.md)
- 차례: **꼬리표 사전 → book-texts(꼬리표·글·표지) → 이웃**. 사전에 없는 꼬리표 코드는 건너뛰므로 사전을 먼저 넣습니다.
- 모든 모드: 파일 전체를 먼저 검사하고, 문제가 하나라도 있으면 줄 번호와 함께(처음 20개) 거부하고 아무것도 넣지 않습니다. UTF-8, BOM과 Windows 줄 끝(`\r\n`)은 받습니다.

`backend/`에서(운영은 운영 DB 환경 변수로, 사이클 17 방식, [6-aisearch.md](6-aisearch.md) 3절과 같음):

### 2-1. 꼬리표 사전 (`tag-dictionary`)

```
./gradlew bootRun --args='--spring.profiles.active=collection-import
  --chaekcheck.collection.import.mode=tag-dictionary
  --chaekcheck.collection.import.tag-dictionary-file=<TSV 경로>'
```

- 파일 [안]: 머리줄 `code<TAB>axis<TAB>name<TAB>thema_code`, 한 줄에 꼬리표 하나. `code`는 영문·숫자·`_` 1\~20자, `axis`는 `mood`·`subject`·`audience`, `name`은 비울 수 없음, `thema_code`는 비우면 없음(NULL). 같은 코드 두 줄은 거부
- 파일이 사전 전체입니다: 파일에 없는 코드는 `active=false`(지우지 않음, 이미 붙은 꼬리표는 남고 카드에는 안 나옴). **줄이 없는 파일은 거부**(모든 코드를 끄지 않게)
- 로그: `tag-dictionary: codes N, made inactive M`. M > 0이면 WARN 한 줄. 꺼진 코드 보기: `SELECT code, name FROM catalog.tag_dictionary WHERE NOT active ORDER BY code;`

### 2-2. 탭 글·꼬리표·표지 (`book-texts`의 kind)

```
./gradlew bootRun --args='--spring.profiles.active=collection-import
  --chaekcheck.collection.import.mode=book-texts
  --chaekcheck.collection.import.book-texts-file=<TSV 경로>'
```

- 파일은 [6-aisearch.md](6-aisearch.md) 2절과 같은 `kind<TAB>book_id<TAB>source<TAB>made_on<TAB>text`. 사이클 23 kind:
  - `for_you_blurb`: `text` 1\~300자(PC는 80\~150자, R8-4), `made_on` 날짜. 그 책의 글을 덮어씀(upsert)
  - `tags`: `text` = 코드 쉼표 목록(앞뒤 빈칸은 떼고 중복은 하나로, 비우면 그 책의 꼬리표를 모두 뗌), `made_on` 날짜. 그 책의 꼬리표를 통째로 바꿈. 사전에 없는 코드는 건너뛰고 수를 셈(WARN)
  - `cover_file`: `text` = 표지 파일 sha1(소문자 16진 40자), `made_on`은 읽지 않음(비워도 됨). 그림은 PC가 S3 `covers/<sha1>.jpg`에 먼저 올림(CloudFront `https://d1bdzw8sfjph5d.cloudfront.net/covers/<sha1>.jpg`, [8-covers.md](8-covers.md)). 운영 `/srv/chaekcheck/covers`는 쓰지 않음
  - 세 kind는 `source`를 읽지 않습니다(비워도 됨). 한 책에 kind마다 한 줄
- `search_line`은 사이클 24 그대로입니다. 단, **`search_line` 없이 사이클 23 kind만 있는 파일은 AI 찾기 표(`catalog.book_search`)를 건드리지 않고 목록 책도 읽지 않습니다**(사이클 24 규칙대로라면 한 줄이 든 표에서 거부되거나, 비어 있으면 목록 책만 다시 넣음). `search_line`이 한 줄이라도 있으면 사이클 24와 같이 목록 책 전부를 바꿔 넣습니다
- 한 파일의 모든 kind를 한 트랜잭션에서 넣습니다(하나가 실패하면 모두 그대로)
- 로그: 사이클 24 줄(또는 `no search line: the AI search's books were left as they are`) 다음에 `book-texts for you: blurbs N, books with tags replaced N, tag codes not in the dictionary N, covers N`

- 10만 권 AI 요약(2026-10-26, 새 43,227권): `chaekcheck-data\ml\foryou\book-texts-for_you_blurb-100k.tsv`. 만든 방법과 못 만든 책은 [6-aisearch.md](6-aisearch.md) 9절

### 2-3. 이웃 (`neighbours`)

```
./gradlew bootRun --args='--spring.profiles.active=collection-import
  --chaekcheck.collection.import.mode=neighbours
  --chaekcheck.collection.import.neighbours-load=full
  --chaekcheck.collection.import.neighbours-file=<파일 경로>'
```

- `neighbours-load`: `full`(새 표 `foryou.book_neighbour_next`에 COPY → 짧은 트랜잭션에서 바꿔 끼우기, 실패하면 앞 표 그대로) | `add`(파일의 책만 upsert, 한 트랜잭션). **기본값 없음**(빠뜨려서 표 전체를 바꾸는 일이 없게)
- 파일 [안]: 첫 줄 `# export_id=<UUID>`, 줄마다 `book_id<TAB>이웃1,이웃2,…`(가까운 차례, 1\~20개, 자기 자신·중복 없음, 번호 ≤ 2,147,483,647, 한 책 한 줄). 책 줄이 없으면 거부
- `export_id`는 PC가 이웃을 계산한 카탈로그의 내보내기 번호(UUID)입니다: 이 DB의 **마지막 SUCCEEDED** `collection.catalog_import.export_id`(`apply`·`finish`가 남김, `in-place`는 NULL)와 다르면 파일을 읽기 전에 거부합니다(책 번호가 다를 수 있음). 번호가 같다는 것을 알 때만 `--chaekcheck.collection.import.force=true`를 붙입니다(WARN 한 줄). `force`는 이 모드에서만 받습니다
  ```sql
  SELECT export_id, imported_at FROM collection.catalog_import
  WHERE status = 'SUCCEEDED' ORDER BY imported_at DESC, id DESC LIMIT 1;
  ```
- `computed_on`은 돌린 날(Asia/Seoul)입니다. 파일에는 날짜가 없습니다
- 약 134만 줄: 파일을 한 번 읽어 검사(줄을 메모리에 두지 않음)한 뒤 다시 읽으며 넣습니다
- 로그: `neighbours full: rows N, export <UUID>, computed_on YYYY-MM-DD`
- 크기·시간(2026-10-24, 클라우드 시험 DB, 가짜 이웃 1,340,875줄 × 20): COPY 약 4.6초, 표 219MB. 운영에서는 바꿔 끼우는 동안 잠깐 두 벌(약 0.44GB)
