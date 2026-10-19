# 추천 목록 파일 (사이클 21)

이동진 추천도서, 서울대 100선, KAIST 사서추천도서(2026·2025·2024, 사용자 2026-10-20), 노벨연구소 선정 최고의 책(Bokklubben World Library, 사용자 2026-10-20)의 목록입니다. 수집기 `scripts/crawl/recommended_lists.py`가 고치고, 사람이 확인 필요 줄을 옮기며, 가져오기 export가 반영 파일(`notes.listPart`)에 실어 운영 DB로 보냅니다. 절차는 [docs/maintenance/4-picks.md](../../../docs/maintenance/4-picks.md) 6절, 규칙 값은 [rules-index.md](../../../docs/maintenance/rules-index.md) 4절.

- 모두 UTF-8, 탭으로 나눈 칸, 첫 줄은 칸 이름입니다. 빈 칸은 값 없음입니다. 칸 순서는 바꿔도 되고, 읽는 쪽이 모르는 칸(`note` 등)은 무시합니다
- **영상 제목·설명은 어느 파일에도 두지 않습니다**(YouTube 약관, spec R4-5). 영상은 ID로만 적습니다
- export와 `lists` 모드가 읽는 파일: `fields.tsv`, `leedongjin.tsv`, `snu100.tsv`, `kaist.tsv`, `nobel.tsv`, `covers/`(없으면 멈춤). 값 검사는 DB와 같아서, 틀린 값은 "파일 line N: 칸 무엇"으로 멈춥니다
- 수집기만 읽는 파일: `subject_fields.tsv`, `collector_words.tsv`, `cover_sources.tsv`, `cover_hosts.tsv`, `cover_placeholders.tsv`, `leedongjin_pending.tsv`

## `fields.tsv` (export에 실림)

| 칸 | 뜻 |
|---|---|
| `list` | `leedongjin`, `snu100`, `kaist` 또는 `nobel` (사서 추천도서 분야는 서버가 받으므로 여기 없음) |
| `code` | 분야 코드, 1~20자, 목록 안에서 하나. 줄의 `field`가 이 값을 씀 |
| `name` | 화면 분야 이름 |
| `position` | 화면 순서(1부터, 목록 안에서 하나). 「전체」는 늘 맨 앞이라 여기 없음 |

## `leedongjin.tsv` (export에 실림, `pinned_by`·`checked_on`·`note` 빼고)

| 칸 | 뜻 |
|---|---|
| `month` | 추천 연월 `YYYY-MM`. 화면 순서는 최근 먼저(표지에 날짜를 붙이지 않음, 사용자 2026-10-16). 이달의 책이 그해 올해의 책이면 그해 12월(spec R3-5) |
| `position` | 같은 달 안 소개 순서(1부터, 달 안에서 하나) |
| `year_book` | 올해의 책이면 그해(예 2025), 아니면 빈 값. 책 창에 " · 2025 올해의 책" |
| `video_id` | 그 추천을 한 영상의 YouTube ID. 책 창 [영상 보기]는 이 ID로만 주소를 만듦 |
| `field` | 분야 코드(`fields.tsv`의 `leedongjin` 줄). **사람이 정함**(수집기는 제안만) |
| `title`·`author`·`publisher`·`isbn13` | 목록의 책(영상이 말한 책). 우리 책을 못 맞추면 화면은 이 제목을 씀 |
| `cover_url` | 표지를 받은 주소(https). 파일은 `covers/<sha1(cover_url)>.jpg`. 수집기가 채움 |
| `pinned_title`·`pinned_author`·`pinned_publisher`·`pinned_isbn13` | 고정 판: 수집기가 고른(또는 사람이 정한) 우리 책의 판. export가 이 판을 우리 책 번호로 맞춤 |
| `pinned_by` | `collector`(수집기가 매번 다시 고름) / `manual`(사람이 정함, 수집기가 덮어쓰지 않음) / 빈 값 |
| `checked_on` | 수집기가 그 영상이 아직 공개임을 마지막으로 확인한 날. 30일 넘으면 export가 경고 |
| `note` | 사람의 메모(실리지 않음) |

## `snu100.tsv` (export에 실림, `pinned_by`·`note` 빼고)

| 칸 | 뜻 |
|---|---|
| `toc_no` | 『권장도서 해제집』 목차 번호 1~100(하나씩). 화면 순서(표지에 번호를 붙이지 않음, 사용자 2026-10-16) |
| `field` | 분야 코드(`fields.tsv`의 `snu100` 줄) |
| `title` | 목차 제목. 우리 책을 못 맞추면 화면은 이 제목을 씀. 맞추면 책 창에 "목록 이름 「…」"(화면 이름과 다를 때) |
| `author` | 첫 저자(같은 책 찾기와 표지 검색의 실마리). 목차에 저자가 없던 12권은 일반 지식으로 채우고 `note`에 "저자: 일반 지식(2026-10-16) [안]" |
| `cover_title` | 표지를 찾을 제목. 목록 제목이 한 책의 이름이 아닐 때만(「근현대사 4부작 (…)」 → 「혁명의 시대」). 빈 값이면 `title`. 실리지 않음 |
| `cover_url`, `pinned_*`, `pinned_by` | `leedongjin.tsv`와 같음. 「미국의 민주주의」·「페더랄리스트」는 `manual`(사용자 결정 2026-10-16) |
| `note` | 메모. "조사 판(2026-10-15)에서 시작" = 처음 시작 판을 조사 문서의 판으로 넣었다는 뜻 |

## `kaist.tsv` (실림, `pinned_by`·`note` 빼고)

| 칸 | 뜻 |
|---|---|
| `year` | 추천 해(2026·2025·2024). 화면은 최근 해부터 |
| `field` | 분류 코드(`fields.tsv`의 `kaist` 줄): `literature`·`humanities`·`society`·`science`·`technology`·`arts`(주제 분류로 책첵이 정함, 4-picks.md 7-2) |
| `position` | 그 해 KAIST 목록 순서(1부터, 해 안에서 하나) |
| `title`·`author`·`publisher`·`isbn13` | KAIST 목록의 책(부제를 뗀 서명, 첫 저자). 수집 원본은 `data/recommended-lists/kaist/kaist-librarian.tsv`(git 밖) |
| `cover_url`, `pinned_*`, `pinned_by` | `snu100.tsv`와 같음 |
| `note` | KAIST 추천 해와 추천번호 |

## `nobel.tsv` (실림, `pinned_by`·`note` 빼고; 사용자 2026-10-20)

Bokklubben World Library(2002, 노르웨이 북클럽·노르웨이 노벨연구소) 100권. 출처와 만든 방법은 4-picks.md 9절.

| 칸 | 뜻 |
|---|---|
| `field` | 시대 코드(`fields.tsv`의 `nobel` 줄): `ancient`(고대·중세)·`early_modern`(근대)·`modern`(20세기) |
| `position` | 분야 안 목록 순서(1부터, 분야 안에서 하나; 위키 표 순서). 화면은 분야 순서, 그 안에서 이 순서 |
| `title`·`author` | 한국어판에서 흔히 쓰는 제목과 첫 지은이. 지은이를 모르면 「작자 미상」 |
| `publisher`·`isbn13` | 보통 빈 값(수집기가 제목+첫 저자로 판을 고름) |
| `cover_url`, `pinned_*`, `pinned_by` | `snu100.tsv`와 같음 |
| `note` | 위키 표의 영어 제목 / 지은이 / 해 / 나라 |

수집기에서 이 목록만 다시 고르기: `python scripts/crawl/recommended_lists.py --skip-youtube --lists nobel --sqlite <사본>`.

## `covers/` (export에 실림)

`<sha1(cover_url)>.jpg`: 수집기가 받아 폭 200px, JPEG 품질 0.8로 줄인 표지. 줄이 쓰지 않는 파일은 수집기가 지웁니다. 손으로 넣지 않습니다.

## `leedongjin_pending.tsv` (확인 필요, 수집기만)

새 영상의 책. 사람이 분야를 정해 `leedongjin.tsv`로 옮깁니다(4-picks.md 6-4).

| 칸 | 뜻 |
|---|---|
| `video_id`·`month` | 영상과 그 추천 연월 |
| `reason` | 빈 값(책 하나를 찾음) / `many_books` / `not_found` / `no_book_name` / `not_a_book`(사람이 책이 아니라고 본 줄, 남겨 두면 다시 들어오지 않음) |
| `title`·`author`·`isbn13` | 설명란 괄호에서 읽은 책 이름과, ISBN 서지에서 찾은 저자·ISBN |
| `field_suggestion` | `SUBJECT` 한 자리로 고른 분야 제안(`subject_fields.tsv`) |

## 규칙 파일 (수집기만)

| 파일 | 칸 | 뜻 |
|---|---|---|
| `subject_fields.tsv` | `subject`, `field_suggestion`, `note` | ISBN 서지 `SUBJECT`(0~9) → 이동진 분야 제안 |
| `collector_words.tsv` | `kind`, `word`, `note` | `year_book_title`(12월 업로드 제목 → 올해의 책 후보), `not_a_book`(책 이름이 아닌 채널 표지), `excluded_title`·`excluded_publisher`·`excluded_name`(고르지 않는 판). 모르는 `kind`면 수집기가 멈춤 |
| `cover_sources.tsv` | `order`, `source`, `note` | 표지를 찾는 곳과 차례: `nlk_isbn`, `collected_preferred`, `collected_other`, `nlk_holdings`, `nlk_title`(뜻은 `note`와 4-picks.md 6-3). 수집기는 `kakao_book`도 알지만 차례에 넣지 않음(카카오 썸네일은 오래 저장하면 안 됨, 2026-10-17). 모르는 이름이면 수집기가 멈춤 |
| `cover_hosts.tsv` | `order`, `host`, `note` | 수집 사본 표지 주소 가운데 먼저 시도할 호스트와 차례 |
| `cover_placeholders.tsv` | `hash`, `source`, `note` | "표지 없음" 그림의 16x16 평균 해시(16진 64자리, 수집기 `image_hash`). 이 그림과 거의 같으면 표지로 쓰지 않음 |
