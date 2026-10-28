# 2. 책첵 추천 자료는 어떻게 만들고 넣나

- **이 문서가 답하는 것**: 서버가 읽는 표 4가지(`foryou.book_neighbour`, `foryou.book_blurb`, `foryou.book_cover`, 분야)의 **원재료가 어느 파일·표의 어느 칸이고, 어떤 계산을 거쳐, 어떤 형식으로 나와, 어떤 명령으로 운영에 들어가나**. 지금 운영에 든 판(2026-10-26 「10만 권 판」)의 수.
- **답하지 않는 것**: 서버가 이 표를 요청마다 어떻게 쓰나 → [1-how-it-works.md](1-how-it-works.md). 넣기 명령의 모든 선택지 → [`docs/maintenance/7-foryou.md`](../maintenance/7-foryou.md) 2절.
- 경로 표기: `<data>` = 데이터 폴더 `C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data` (git 밖). 운영 = `ubuntu@52.64.117.112`의 `/srv/chaekcheck`.

## 0. 지금 운영에 든 것 (2026-10-26 넣음, 가져오기 14번 `export_id = d09d649f-feb6-44ad-8f92-687dcee85243`)

| 운영 표 | 행 | 만든 곳 | 1절 |
|---|---|---|---|
| `foryou.book_neighbour` (`book_id`, `neighbour_ids integer[]` ≤ 20, `computed_on`) | 81,080 | 이 PC, `scripts/crawl/foryou_neighbours.py` | 2·3 |
| `foryou.book_blurb` (`book_id`, `blurb` 1~300자, `made_on`) | 68,414 | Haiku(2묶음) | 4 |
| `foryou.book_cover` (`book_id`, `file_key` = sha1 40자) | 80,091 | 수집 PC 표지 받기 결과 재사용 | 5 |
| 운영 파일 `/srv/chaekcheck/covers/<sha1>.jpg` | 81,451개 | 같음 | 5 |
| 분야 묶음 | 표 없음, 요청마다 계산 | [1-how-it-works.md](1-how-it-works.md) 7절 | — |
| `picks.tag_dictionary`, `picks.book_tag` | 0 | 만들지 않음 | — |

## 1. 대상 책

- **보유 많은 순 10만 권**: 서비스 DB `catalog.book`을 `cardinality(library_ids) DESC, id`로 줄 세워 앞 100,000권(수집 PC 세션 `scripts/aisearch/build_100k.py`, 목록 `<data>/aisearch/100k/targets.tsv`). 10만 번째 책은 36곳이 가짐, 3만 번째는 73곳
- **소개 글**: 책마다 소장 줄(`catalog.holding`의 `(library_id, item_key)`)을 `collection.collection_link`로 수집 DB 도서관 번호로 바꿔, 수집 DB `<data>/ebook-catalog.sqlite`의 `holding.intro_raw` 중 **가장 긴 것**(`<data>/ml/intro-books-100k.tsv`)
- 소개가 50자 미만이거나 없는 23,026권은 벡터·요약·AI 검색에서 모두 뺌(`<data>/aisearch/100k/left-out-no-intro.tsv`)
- **추천목록 책**: `picks.list_book.book_id`가 있는 책 2,010권(소개 유무 상관없이 넣음)

## 2. 책 벡터

| 항목 | 값 |
|---|---|
| 모델 | `nlpai-lab/KURE-v1`(sentence-transformers), 이 PC CPU, `<data>/ml/venv` |
| 입력 글 | 첫 줄 `제목 · 저자 · 출판사 · 출간연도(4자)`(`catalog.book.title`, `author_display`, `publisher`, `published_on`; 빈 칸은 빼고 ` · `로 이음, `text_of`), 둘째 줄 소개 앞 300자. 소개가 없으면 첫 줄만 |
| 출력 | 1,024차원 `float32`, `normalize_embeddings=True` → 길이 1(그래서 두 벡터의 내적 = 코사인 유사도) |
| 파일 | 목록 밖 책: `<data>/ml/pool-100000-intro.npy` + `.ids.json`(80,549권 = 앞서 만든 3만 권 판 30,000 + 새 50,549권; 수집 PC가 만듦, 2026-10-26 19:31). 추천목록 책: `<data>/ml/foryou-100k/vectors.npy` + `vectors.ids.json`(`{ids, texts: 글 sha1}`, 2,010권, 이 PC가 2026-10-26에 다시 만듦) |
| 같은 책이 두 파일에 있으면 | 추천목록 쪽 벡터를 씀 |

AI 검색은 같은 규칙의 벡터를 따로 int8로 바꾼 `book-vectors.bin`(81,078권, 운영 모델 폴더)을 씀. 추천목록 책은 그 파일이 10-25 벡터라 두 벌이 약 2천 권에서 조금 다름([5-improvements.md](5-improvements.md) I-10).

## 3. 이웃 표 계산 (`scripts/crawl/foryou_neighbours.py --with-pool --pool pool-100000-intro --out <data>/ml/foryou-100k`)

1. 2절 두 벡터 묶음을 이어 붙여 행렬 `V`(81,080 × 1,024)
2. 1,000행씩: `S = V[i:i+1000] @ V.T` (행마다 모든 책과의 코사인)
3. 행마다 `numpy.argpartition`으로 값이 큰 200개(`SHORTLIST`)를 뽑아 큰 순으로 정렬 → 차례로 보며
   - 자기 자신, `catalog.book.main_title_norm`이 같은 책, 3-1의 같은 작품이면 건너뜀
   - 이미 고른 이웃과 같은 작품이면 건너뜀(작품마다 한 판)
   - 20개(`NEIGHBOURS`)가 차면 멈춤. 200개로 모자라면 그 행 전체를 정렬해 이어 봄
4. 출력
   - `neighbours.tsv`: 첫 줄 `# export_id=<collection.catalog_import에서 마지막 SUCCEEDED의 export_id>`, 이어서 줄마다 `book_id<TAB>이웃1,이웃2,…`(가까운 순)
   - `neighbours-review.tsv`: `book_id, title, rank, neighbour_id, neighbour_title, neighbour_author, cosine`(사람이 읽는 용)
   - `summary.json`: 모델, export_id, 책 수, 행 수, 걸린 시간
5. 시간: 추천목록 2,010권 다시 인코딩 약 19분(글이 같으면 저장된 벡터를 다시 씀), 이웃 계산 약 10분(이 PC)

### 3-1. 같은 작품 판정 (`same_work`, `work_key`, `first_author`)

- `work_key(제목)`: ① 괄호 `()[]〈〉<>《》「」『』【】{}`와 그 안 글자를 지움 ② `:` 앞, ` - `·`-` 앞(한쪽에 빈칸이 붙은 대시)만 남김 ③ 앞뒤의 연도(`19xx`·`20xx`, 뒤의 `년`·`년판` 포함)를 지움 ④ 끝의 권 번호(`2`, `제2권`, `2부`, `volume 1.`, `vol. 3`, 로마 숫자 `iii`)를 두 번까지 지움 ⑤ 글자·숫자만 남기고 소문자. 결과가 비면 원래 제목의 글자·숫자
- `first_author(저자 칸)`: `,;/|` 앞 첫 이름 → 괄호와 안 글자 지움 → 끝의 역할(`지음, 저, 글, 그림, 엮음, 편저, 편, 옮김, 역, 번역, 감수, 원작`) 지움 → 글자·숫자만, 소문자
- 두 책이 **같은 작품**인 경우
  1. `work_key`가 같고, 첫 저자가 한쪽에 다른 쪽이 들어 있거나 한쪽이 비었을 때
  2. (2026-10-26 더함) 두 책 모두 첫 저자가 있고 서로 들어 있으며, 짧은 `work_key`(3자 이상)가 긴 쪽에 들어 있을 때 — 예 「명상록」 ⊂ 「초역명상록」
  3. (2026-10-26 더함) 같은 조건에서 두 `work_key`의 **끝 4자 이상과 앞 2자 이상**이 같을 때 — 예 「라만차돈키호테」·「라만차의비범한이달고돈키호테」. 「그리스신화이야기」·「로마신화이야기」는 앞이 달라 다른 작품
- 시험: `scripts/crawl/test_foryou_neighbours.py`(`python -m unittest`)
- 못 잡는 것: 제목이 완전히 다른 번역판(「나를 아프게 한 말들이 모두 진실은 아니었다」 = 「명상록」), 저자 칸에 옮긴이만 있는 판

## 4. 요약(해요체 한두 문장)

| 묶음 | 대상 | 만든 방법 | 검사 | 결과 |
|---|---|---|---|---|
| 1차 (2026-10-25) | 추천목록 1,859권 | `scripts/crawl/foryou_blurbs.py` prepare → submit(Message Batches) → collect | 아래 R8-4 | **보내지 않음**(그때 이 PC에 API 키가 없었음). 추천목록 책은 `aiSummary`(빌드 파일)를 보임 |
| 3만 권 (2026-10-25) | `aisearch.search_book.line`(AI 검색 찾기용 한 줄, 해라체)이 있는 책 | 이 세션의 Haiku 하위 에이전트 26개가 1,000권씩, 한 줄을 해요체 한두 문장으로 다시 씀(API 키 없음) | 끝이 `요` 또는 `요.`·`요!`, 40~200자, `최고의·감동적·꼭 읽어야·베스트셀러·필독` 없음, 칸 나눔 문자 없음. 걸린 9권은 직접 씀 | 25,187권, `source = haiku-rewrite` |
| 10만 권 새 책 (2026-10-26) | 1절 대상 중 `foryou.book_blurb`가 없는 책 | 수집 PC가 `build_100k.py`로 `claude-haiku-5-5` Message Batches. 입력 = 서지 + 소개 앞 1,500자. 지시문 = `foryou_blurbs.py`의 `SYSTEM`(아래), 걸린 글은 `BLURB_RETRY_RULE`를 붙여 두 번 다시 | R8-4 | 43,227권. 탈락 8,571권(원문 8자 연속 베낌 5,557 · NONE 2,153 · 길이 596 · 금지어 244 · 모양·해요체 아님 20), 목록 `<data>/aisearch/100k/dropped-for_you_blurb.tsv` |

- **R8-4 지시문 요지**(`foryou_blurbs.py` `SYSTEM`): 해요체 한두 문장 80~150자, 무엇에 관한 책이고 어떤 독자에게 맞는지, 칭찬 말·결말·「AI·인공지능·요약·소개 원문」 같은 말 금지, 원문과 8자 이상 같은 글자가 이어지지 않게, 알 수 없으면 `NONE`
- **R8-4 검사**(`check`): `NONE`이면 탈락, 80~150자, 줄바꿈·따옴표 시작 없음, 금지어 `AI|인공지능|최고의|감동적|꼭 읽어야|베스트셀러|필독`, 끝이 `요`·`죠`(+`.!?`), 원문과 빈칸 뺀 8자 연속 일치 없음
- 3만 권 묶음은 다른 검사(40~200자 등)를 썼음. 두 묶음 모두 이 세션이 넣기 전 다시 셈: 68,414권 모두 해요체 끝

## 5. 표지

| 단계 | 어디 | 형식 |
|---|---|---|
| 받기 | 수집 PC `fetch-covers`(표지 받기). 도서관 목록 줄의 표지 주소 → 실패하면 다른 후보·국립중앙도서관 | `<data>/covers/covers.sqlite` 표 `book_cover`(책 하나 한 줄: `book_key`(ISBN 또는 첫 소장 줄), `book_id`(가져오기 14번), `candidates` JSON, `status` PENDING/DONE/NONE, `source`, `cover_url`, `file`). 파일 `<data>/covers/<sha1 앞 2자>/<sha1(표지 주소)>.jpg` |
| 범위 정하기 | `scripts/crawl/cover_scope.py --pool pool-100000-intro` | 추천목록 책 ∪ `<data>/ml/pool-100000-intro.ids.json` ∪ 사전 시험 인기 목록 상위 100 |
| 내보내기 | `cover_scope.py export` | `<data>/covers-export/<sha1>.jpg`(같은 주소 = 같은 파일) + `book-texts-cover_file.tsv`(줄마다 `cover_file<TAB>book_id<TAB>fetch-covers:<source><TAB>날짜<TAB>sha1`) |
| 운영에 올림 | 이 PC → 운영 | 운영에 없는 파일만 tar로 묶어 `/srv/chaekcheck/`에 올린 뒤 `/srv/chaekcheck/covers/`에 풂(운영 `/tmp`는 메모리라 쓰지 않음). 2026-10-26 새 50,490개 |

10만 권 범위 82,914권 중 DONE 80,078, PENDING 435, 표지 없음 681(2026-10-26 20시대).

## 6. 운영에 넣기 (자세한 선택지는 [`7-foryou.md`](../maintenance/7-foryou.md) 2절)

- 공통: 운영 `docker compose --env-file secrets.env --env-file prod.env run --rm --no-deps -v /srv/chaekcheck/book-texts:/book-texts:ro -e JAVA_TOOL_OPTIONS=-Xmx384m -e CHAEKCHECK_AUTH_ENABLED=false -e CHAEKCHECK_USAGELOG_ENABLED=false -e CHAEKCHECK_AISEARCH_ENABLED=false -e CHAEKCHECK_FORYOU_BUILD_ENABLED=false backend --spring.profiles.active=collection-import …` (파일은 먼저 `/srv/chaekcheck/book-texts/`에)
- **이웃**: `--chaekcheck.collection.import.mode=neighbours --chaekcheck.collection.import.neighbours-load=full --chaekcheck.collection.import.neighbours-file=/book-texts/<파일>`. 파일 첫 줄 `export_id`가 운영 `collection.catalog_import`의 마지막 SUCCEEDED와 다르면 거부. `full`은 `foryou.book_neighbour_next`에 COPY한 뒤 짧은 트랜잭션에서 표를 바꿔 끼움
- **요약·표지**: `--chaekcheck.collection.import.mode=book-texts --chaekcheck.collection.import.book-texts-file=/book-texts/<파일>`. 파일: 첫 줄 `kind<TAB>book_id<TAB>source<TAB>made_on<TAB>text`, `for_you_blurb` → `foryou.book_blurb` upsert, `cover_file` → `foryou.book_cover` upsert, `tags` → `picks.book_tag`. `search_line` 줄이 없는 파일은 AI 검색 표(`aisearch.search_book`)를 건드리지 않음. 한 트랜잭션
- 2026-10-26 넣은 파일: `neighbours-100k-2026-10-26.tsv`, `book-texts-cover_file-100k.tsv`(80,091줄), `book-texts-for_you_blurb-100k.tsv`(43,227줄)

## 7. 다시 만들 때 주의

1. **책 번호가 바뀌면 전부 다시**: 책 목록을 다시 가져오면(`catalog_import` 새 번호) `book_id`가 다시 매겨짐 → 이웃·요약·표지 연결을 새 번호로 다시 만들어야 함. 이웃 넣기는 `export_id`가 다르면 거부함
2. **AI 검색 범위와 맞추기**: AI 검색은 같은 이웃 표를 쓰되 `aisearch.search_book`에 있는 책만 씀. 이웃 표의 범위만 넓히면 AI 검색이 쓸 수 있는 이웃이 줄어듦(2026-10-26: 책마다 19.85 → 9.47, AI 검색도 10만 권으로 맞춘 뒤 19.82)
3. 이웃 계산 전에 같은 작품 시험(`test_foryou_neighbours.py`)을 돌림
4. 사이클 26 뒤에는 요약·표지 넣기가 `catalog` 표로 바뀜(plan `docs/cycles/26-book-descriptions/`)
