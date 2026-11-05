# 사이클 26 dev 기록: 단계, 위치, 운영에서 생긴 일

## 이 문서가 답하는 것 / 답하지 않는 것

- 답하는 것: dev가 사이클 26을 이어 받을 때 알아야 할 것. 단계마다 상태, 코드·표·파일이 어디 있는지, 운영에서 생긴 일과 되돌린 방법·명령.
- 답하지 않는 것: 포트폴리오용 이야기 → [1-portfolio.md](1-portfolio.md). 날짜순 원본 숫자 → [plan-feedback](../../cycles/plan-feedback.md) 「# 사이클 26」. 운영 절차 일반 → [../maintenance/8-covers.md](../../maintenance/8-covers.md), [../maintenance/7-foryou.md](../../maintenance/7-foryou.md).

## 1. 단계 상태 (2026-10-29 09:20)

| 단계 | 내용 | 상태 |
|---|---|---|
| 0~9 | 설계 확인, AWS(버킷·CloudFront·IAM), `catalog/002` 표·복사, 읽는 곳 바꾸기, 화면, 연습 | 끝 |
| 10 | 배포 1 = **v1.20.0**(커밋 `7ae1aa83`, 2026-10-29 08:29) | 끝 |
| AC-1 | 네 곳 요약 같음 | 끝(운영) |
| v1.20.1 | 낱말 검색 custom plan 고정 + 프로필 NULL 고침(4·5절) | 배포 끝(2026-10-29 09:2x, 커밋 `14ae43d9`) |
| 11 | 표지 113만 장 S3 올리기 → `cover_file` 연결 → AC-11 | 끝: 10:26 올리기 끝(90~105분, 실패 0), S3 대조 빠진 것 0, 10:49~10:51 운영 넣기 1,145,754줄, AC-11 통과 |
| v1.20.3 | 낱말 찾기 intarray 함수 + strpos(V7) | 배포 끝(11:00, `b32c757c`). 운영에서 큰 계획·여러 낱말 6경우가 느려져 v1.20.4로 고침 |
| v1.20.4 | 한 낱말이거나 계획 500곳 이하만 함수(V8) | 배포 끝(`99b1a638`). 운영 24경우 합 2,773 → 2,006ms, 느려진 경우 0 ([4-keyword-query-speed.md](4-keyword-query-speed.md) 7절) |
| 12 | `book-profiles` 운영 넣기 + 재시작 | 끝: 09:06 넣음 → NPE로 되돌림(5절) → v1.20.1에서 09:26 다시 넣음, `from catalog.book_profile`, AI 검색 카드 5장 정상 |
| 13 | 배포 2: 옛 표·옛 표지 경로 지우기 | 끝: v1.20.6(12:00, `48ede47a`, 사용자 "배포 2 진행"), 서버 옛 표지 폴더도 지움(6절) |

## 2. 어디에 무엇이 있나

| 무엇 | 위치 |
|---|---|
| 새 표 | `catalog.book_summary`, `book_cover`, `book_profile`, `book_search`, `book_tag`, `tag_dictionary` — changelog `backend/src/main/resources/db/changelog/catalog/002-book-descriptions.yaml` |
| 공개 API | `com.chaekcheck.catalog`의 `BookTexts`, `CoverUrls`, `BookProfiles`, `AiSearchIndex`, `BookTextsTransfer` |
| 구현 | `catalog/internal/texts/Jdbc*.java`, `SettingsCoverUrls`(`chaekcheck.covers.base-url`) |
| 넣기 명령 | `collection/internal/booktexts/BookTextsImport`, `BookProfilesImport`, `ExportIds`(첫 줄 `# export_id=`) |
| 표지 스크립트 | `scripts/covers/upload-collected.ps1`(폴더별 올리기, `-Only` 정규식으로 나눠 동시에, done-*.txt로 이어 하기), `cover_file_rows.py`(올린 폴더의 그림만 `cover_file` 줄로), `extract_picks_covers.py`, `setup-aws*.{ps1,sh}` |
| AWS | 버킷 `chaekcheck-covers-135274608857`(시드니), CloudFront `E1RZWZ6VBX38DX` = `https://d1bdzw8sfjph5d.cloudfront.net`, PC 프로필 `chaekcheck-covers`(키는 `%USERPROFILE%\.aws\credentials`에만) |
| 표지 원본 | 수집 PC `chaekcheck-data/covers/covers.sqlite`(`book_cover`, status DONE 1,145,754줄, 그림 1,128,687장), 그림 `covers/<xx>/<sha1>.jpg` |
| 프로필 파일 | `chaekcheck-data/aisearch/100k/book-profiles-export.tsv`(77,551권, md5 `af7d5289…`), 운영 사본 `/srv/chaekcheck/book-texts/c26-book-profiles-export.tsv` |
| 기준 가져오기 | 운영·PC 모두 가져오기 14번, export_id `d09d649f-feb6-44ad-8f92-687dcee85243` |

## 3. 11단계: 표지 올리기

- **끝(2026-10-29)**: 8개 모두 90~105분에 끝남, 256폴더, `FAILED` 0. S3 목록(`s3api list-objects-v2`, 495초) 1,130,143개 vs 연결할 그림 1,128,687장 → **빠진 것 0**(S3에만 있는 1,456개는 추천목록 그림 등). 운영의 기존 80,091권 연결은 새 파일과 모두 같음. `c26-cover_file-1~5.tsv`(25만 줄씩, 첫 줄 export_id d09d649f) → 운영 `book-texts`로 넣음, 파일마다 24~30초, `covers 250000` ×4 + 145,754. 결과 `catalog.book_cover` 1,145,754줄, 서로 다른 그림 1,128,687, 책 없는 줄 0, 136MB. 새로 이어진 책 11·12·18번 표지가 CloudFront에서 200 image/jpeg. 서버 재시작 없음.

- 2026-10-29 08:41부터 `upload-collected.ps1` 8개 동시(`-Only '^[01]'` … `'^[ef]'`), 로그 dev `data/c26/upload-collected/worker-N.out`. 09:16에 256폴더 중 85폴더, 362,076장 → 약 170장/초.
- 끝나면: `aws s3 ls`로 객체 수, `cover_file_rows.py`로 연결 파일(+ `.keys.txt`)을 만들어 S3 목록과 비교(빠진 것 0), 운영 `book-texts` 넣기(사용자 승인 2026-10-29 "둘 다 승인"), AC-11 `S3 covers/ 객체 수 ≥ count(DISTINCT file_key)`.
- 시험 실행(85폴더 전, 16폴더): 71,189줄, 그림 70,611장 — 한 그림을 여러 책이 씀.

## 4. 낱말 검색 느려짐 (v1.20.1에서 고침)

- 알림: dev 24의 배포 전후 시간 재기(r3·r4에서 여러 낱말 질문 낱말 대기 3~5.4초).
- PostgreSQL 해설(용어·계산·재는 SQL): [3-postgres-generic-plan.md](3-postgres-generic-plan.md)
- 원인·숫자·고침: [1-portfolio.md 1절](1-portfolio.md#1-ai-검색-같은-질의가-쌓이면-11초가-되던-실행-계획-v1201). 코드 `JdbcAiSearchIndex.find`(읽기 전용 `TransactionTemplate` + `SET LOCAL plan_cache_mode = force_custom_plan`), 시험 `AiSearchIndexTests.repeatedSearchesKeepCustomPlans`.
- 운영 재현 SQL: `PREPARE q(text,…) AS <find의 SQL>` 뒤 `SET plan_cache_mode = force_custom_plan` / `force_generic_plan`으로 `EXPLAIN (ANALYZE) EXECUTE q(…)`. 운영 DB는 `ssh … 'docker exec -i chaekcheck-db-1 psql -U chaekcheck -d chaekcheck' < 파일.sql`(SQL은 파일로 넘김, 따옴표 문제).
- 배포 뒤(dev 24, 24개 × 4회): 1초 넘은 낱말 검색 0번, 첫 글 느린 10% 2.76~3.05초(v1.20.0 r3·r4 5.2~5.8초). 내 확인: v1.20.1 뒤 운영 로그 검색 106번, 낱말 대기 최대 241ms, 오류 0. 고침 확인.

## 5. 12단계 사고: 프로필을 표에서 읽자 AI 검색이 멈춤 (2026-10-29 09:06~09:12)

| 시각 | 일 |
|---|---|
| 09:06:13 | 운영 `book-profiles` 넣기 `stored 77551`(2.4초) |
| 09:06:50 | 재시작, 로그 `77551 book profiles from catalog.book_profile` |
| 09:07:11 | 시험 질문 → `internal_error`, 로그 `ai search failed: java.lang.NullPointerException` |
| 09:12:26 | 사용자 "표 비우기 먼저" → `DELETE FROM catalog.book_profile`(77,551) + 재시작 → 로그 `from book-profiles.tsv (catalog.book_profile is empty)`, 시험 질문 카드 7장 정상 |

- 원인: 표는 빈 칸을 NULL로 두고, 파일은 `""`로 읽는다. aisearch의 서가 맞추기(`Shelves`)가 그 글을 그대로 비교해 NPE.
- 왜 시험이 못 잡았나: 표 읽기(`JdbcBookProfiles`)와 서가 맞추기(`FirstCallTests`)를 따로만 시험했고, 표에서 읽은 값을 서가 맞추기에 넣는 시험이 없었다.
- 고침(v1.20.1): `aisearch/internal/firstcall/BookProfiles.of`에서 NULL → `""`. 시험 `FirstCallTests.profilesFromTheTableMatchShelvesLikeTheFile`.
- 다음에: 운영에 넣는 데이터가 읽는 길을 바꾸면(파일 → 표), 넣은 뒤 재시작하고 **그 기능을 한 번 실제로 부른 뒤** 끝으로 본다(이번에는 그렇게 해서 6분 만에 찾음).

## 6. 13단계: 배포 2 (v1.20.6, 2026-10-29)

- 지운 표: `foryou.book_blurb`(24MB)·`book_cover`(12MB), `picks.book_tag`·`tag_dictionary`(각 16kB)·`cover`(2.2MB, 108장), `aisearch.search_book`(115MB). changeset `foryou/002-drop-book-blurb-cover`, `picks/007-drop-moved-tables`, `aisearch/005-drop-search-book`(`DROP TABLE`, `IF EXISTS` 없음). 운영에서 셋 합 약 0.2초(12:00:12.29~12:00:12.48), 서버 시작 22.6초. DB 8,431 → 8,277MB.
- 지운 코드·설정: `GET /api/v1/picks/covers/{coverId}`, 서버의 사서추천 그림 받기(`FetchJob` 그림 단계, `CoverResizer`, `NlkClient.fetchCover`, `picks.fetch.cover-*` 설정), `PicksStore`의 `picks.cover` 읽기·쓰기, 웹 `safeCoverUrl`의 옛 상대 경로 두 모양, vite 개발 서버 `/covers/`, nginx `location /covers/`, compose의 `/srv/chaekcheck/covers` 볼륨. `collection`의 `allowedDependencies`에서 `aisearch` 뺌(`foryou`는 이웃 넣기로 남음). 남긴 것: `picks.fetch_run.cover_fetched_count`·`cover_failed_count` 칸(기본 0, 지우려면 새 changeset).
- 연습(PC 운영 꼴 복사본 5502): 드롭 셋 각 약 15ms, 서버 시작 10초, API(진열·KAIST·책첵 추천·책 상세) 200, 옛 표지 API 404, KAIST 표지 4,548개 모두 CloudFront.
- 시험: 백엔드 1,429개(0 실패), 웹 vitest 709개. `BookDescriptionSchemaTests.theOldTablesAreDropped`(여섯 표 `to_regclass` NULL).
- 운영 확인: 여섯 표 `to_regclass` 모두 NULL, 진열·KAIST·책첵 추천·책 상세 200, `/api/v1/picks/covers/1`·`/covers/<sha1>.jpg` 404, KAIST 표지 4,548개 모두 CloudFront, 브라우저 KAIST 화면 그림 20장 모두 열림(오류는 전부터 있던 cloudflareinsights CSP와 비로그인 401뿐), AI 검색 카드 4장 정상.
- 운영 `/srv/chaekcheck/covers`(1.1GB, 81,451장): 지우기 전에 81,451장 모두 S3에 있음을 대조(없는 것 0). 처음 시도는 권한 확인에서 막혔고, 사용자 "너가 지워" 뒤 지움. 디스크 남은 공간 9.1 → 11GB.
