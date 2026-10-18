# 규칙 목록 (사람이 정한 값)

수집·가져오기가 쓰는 **사람이 정한 값**을 한곳에 모은 목록입니다(plan ADR-0117, 사이클 19). 다른 세션도 이 문서만 읽고 다음을 바로 찾을 수 있게 씁니다.

- 그 값이 어디에 있는지
- 무슨 뜻인지
- 데이터로 어떻게 확인하는지(스크립트 경로·입력·판정)
- 바꾼 뒤 무엇을 해야 하는지

## 이 문서를 쓰는 규칙

- 코드 상수와 규칙 파일에는 이 문서의 줄을 가리키는 주석을 답니다.
  - Java: `Rule: docs/maintenance/rules-index.md "<이름>"`
  - Python: `# rules-index.md: <이름>`
  - 규칙 파일: `scripts/library/rules/README.md` 표
- 규칙을 더하거나 바꾸면 이 표도 같은 커밋에서 고칩니다.
- **수집 전**([2-crawl.md](2-crawl.md) 0절)과 **가져오기 전**([3-import.md](3-import.md) 2절)에 이 문서를 한 번 훑습니다. 그때 "확인하는 법" 칸이 있는 줄은 그 방법으로 지금도 맞는지 봅니다.
- "그 뒤 할 일"의 뜻
  - **다음 수집부터**: 수집기 값이라 이미 받은 수집 DB는 그대로입니다.
  - **다시 가져오기**: 가져오기 값이라 다음 가져오기([3-import.md](3-import.md))부터 서비스 책에 반영됩니다. 사이클 11의 내 PC `export` → 운영 `apply` 절차(13절)를 씁니다.
  - **검색어 다듬기 규칙**: 저자 정리를 바꾸면 검색어 정리도 함께 바뀝니다. [3-import.md](3-import.md) 12절 표의 세 번째 줄 순서(옛 서비스를 켠 채 내보내기 → 반영 → 새 코드로 다시 켜기, ADR-0069)를 따릅니다.

## 1. 수집기 (`scripts/crawl/crawl_catalog.py` 맨 위 "규칙" 묶음)

| 이름 | 뜻 | 확인하는 법 | 바꾸는 법과 그 뒤 할 일 |
|---|---|---|---|
| `PUBLISHER_FIRST_PORTAL_HOSTS` | 통합 포털(`ELIB_PORTAL_V1`) 가운데 목록 줄 `div.info` 칸이 **출판사 → 저자 → 날짜** 순서인 호스트. 지금은 `lib.goe.go.kr`(경기도교육청), `library.daegu.go.kr`(대구), `lib.jbe.go.kr`(전북교육청)입니다(사이클 19, ADR-0115). 나머지 포털은 저자가 먼저입니다 | 수집 전에 포털마다 새 책 목록 한 쪽을 한 번 열어 칸 순서를 봅니다(`.../elib/module/elib/book/index.do?menu_idx=<번호>&menu=NEW&type=EBK&rowCount=10&viewPage=1`, 사람 손 속도). 이미 받은 사본은 `python scripts/library/check_author_publisher_swap.py <사본.sqlite>`가 "뒤바뀜"인 포털을 알려 줍니다. 시험: `python -m unittest scripts/crawl/test_parse_portal.py` | 상수에 호스트를 더하거나 빼고 저장 HTML 시험(`scripts/crawl/testdata/`)을 더합니다. **다음 수집부터.** 이미 받은 사본의 뒤바뀐 칸은 가져오기의 `author_publisher_swapped.tsv`가 바로잡습니다 |
| `DISABLED_IDS` | dev seed 도서관 가운데 `init`이 넣지 않는 번호(59, 131) | [library-maintenance.md](../library-maintenance.md) 6-2 "꺼 둔 도서관" 표 | 상수를 고칩니다. 다음 `init`부터 |
| `PAGE_SIZE` | 플랫폼마다 목록 한 쪽에 받는 줄 수 | [platforms.md](platforms.md) 플랫폼별 "한 쪽 크기" | 상수를 고칩니다. 다음 수집부터 |
| `PAGE_SIZE_BY_HOST` | 기본 한 쪽 크기에서 끊기거나 시간이 넘는 호스트의 한 쪽 크기(2026-09-30) | [2-crawl.md](2-crawl.md) 문제별 대처 | 상수를 고칩니다. 다음 수집부터 |
| `BIG_SUBSCRIPTION_MIN` | 교보 구독형 공용 묶음(약 20만 권)으로 보고 건너뛰는 사이트 권수 기준(`--include-big-subscription`이면 받음) | [2-crawl.md](2-crawl.md) | 상수를 고칩니다. 다음 수집부터 |
| `BLOCKED_HOST_SUFFIX` | 절대 요청하지 않는 호스트 끝(`dkyobobook.co.kr`, 교보 구독형 서버가 우리 IP를 막음, 사이클 13 R1-6) | 요청 직전에 매번 확인함(코드) | 바꾸지 않습니다. 풀려면 사용자 결정이 필요합니다 |
| `SHARED_HOSTS` | 여러 도서관을 한 서버로 서비스하는 호스팅(한 줄 세우기) | [2-crawl.md](2-crawl.md) 2-1절 | 상수를 고칩니다. 다음 수집부터 |
| `MIN_GAP`, `PER_HOST` | 한 서버에 요청 사이 최소 1초, 동시 1개(호출 예의, 사이클 01 spec 5.6) | plan `crawl-method.md` | 바꾸려면 사용자 결정이 필요합니다 |
| `MATCH_TOLERANCE` | 사이트 권수와 받은 줄 수의 허용 차(1%) | `python scripts/crawl/crawl_catalog.py status` | 상수를 고칩니다. 다음 수집부터 |

## 2. 규칙 파일 (`scripts/library/rules/`)

꼴과 칸은 [`scripts/library/rules/README.md`](../../scripts/library/rules/README.md)에 있습니다. 넣기 명령 `library-insert`가 읽는 파일(`name_overrides.tsv`, `final_origins.tsv`, `demo_or_closed.tsv`, `same_site.tsv`, `same_host_kept.tsv`, `gone_known.tsv`, `order_no_allowed.tsv`, `scope_overrides.tsv`, `public_join.tsv`, `library_kinds.tsv`, `kyobo_probe.tsv`, `bookcube_volume.tsv`, `join_surveyed_on.txt`)의 뜻·출처는 그 README 표에 있습니다. 바꾼 뒤에는 `library-insert`를 다시 돌립니다([1-libraries.md](1-libraries.md)). 아래 두 파일은 책 가져오기가 읽습니다.

| 이름 | 뜻 | 확인하는 법 | 바꾸는 법과 그 뒤 할 일 |
|---|---|---|---|
| `author_publisher_swapped.tsv` | 저자·출판사를 뒤바꿔 수집한 포털(수집 번호 11·12·15). 가져오기가 두 칸을 바꿔 읽습니다(ADR-0115). `swap_before`(고친 수집기를 main에 합친 날) 이후 수집한 사본이면 가져오기가 멈춥니다 | `python scripts/library/check_author_publisher_swap.py <사본.sqlite>`: 포털 도서관마다 "바름 / 뒤바뀜 / 판단 못 함"과 줄 수를 냅니다. 판정 기준은 스크립트 `--help` | 가져오기가 `swap_before`로 멈추면 [3-import.md](3-import.md)의 "뒤바뀜 규칙 줄 지우기" 절차를 따릅니다. 세 도서관이 "바름"일 때만 줄을 지우고, 아니면 사용자에게 묻습니다. **다시 가져오기** |
| `supplier_families.tsv` | 같은 공급사의 다른 이름 → 같은 묶음. 가져오기 ② 단계가 (묶음, 상품 코드)로 같은 책을 잇습니다(ADR-0114). 목록에 없는 이름은 그 이름 하나가 한 묶음입니다. 빈 공급사와 「기타」(`ImportRules.PLACEHOLDER_SUPPLIERS`)는 코드로 잇지 않습니다 | 사본의 공급사 이름 전체(플랫폼별 줄 수)를 뽑아 새 이름이 생겼는지 봅니다. 가져오기 로그의 `code-joined groups with 2+ ISBNs`가 0인지 봅니다(0이 아니면 잘못 이은 묶음이 있을 수 있음) | 줄을 더하고 `ImportRulesTests`를 돌립니다. **다시 가져오기** |

## 3. 가져오기 코드 상수 (`backend/.../collection/internal/importer/`, `catalog/normalize/`)

| 이름 | 어디에 | 뜻 | 확인하는 법 | 바꾸는 법과 그 뒤 할 일 |
|---|---|---|---|---|
| 저자 기호 떼기 | `Contributors.MARKS`, `BRACKET_ROLE`, `TRAILING_LONE_ROLE`, `stripMarks` | 저자 글에서 `< >`(모든 너비)와 `〈 〉 「 」 《 》 [ ]`의 표시를 뗍니다. 안의 글자는 남깁니다(ADR-0113, `[ ]`는 사이클 19 회고 결정 #5). `[ ]` 안이 역할 낱말이면 띄어 쓴 역할 낱말이 되고(「존 버거 [지음]」 → 「존 버거 지음」), 뒤에 글이 더 있으면 쉼표를 넣으며(「정도전[저]정병철[편]」 → 「정도전 저, 정병철 편」), 그 뒤에 홀로 남는 역할 낱말은 뗍니다(「김삼웅 [편] 저」 → 「김삼웅 편」). 저장 이름, 비교 이름, 저자 검색어, 책 표 저자 칸에 같이 씁니다 | `ContributorsTests`. 서비스 DB에서 `SELECT count(*) FROM catalog.book_contributor WHERE name_exact ~ '[<>\[\]]'` | 상수와 시험을 고칩니다. **검색어 다듬기 규칙** + 다시 가져오기 |
| 붙은 역할 낱말 | `Contributors.ATTACHED_ROLES`, `SEPARATE_ROLE` | 「A 저B 역」처럼 붙은 역할 낱말을 좁은 규칙으로 나눕니다. 첫 낱말은 저·저자·지음·지은이·글, 둘째는 역·옮김·옮긴이·번역·역자·그림, 셋째까지 나눕니다. 앞에 역할 낱말이 따로 있으면 자르지 않습니다(보호 장치, ADR-0113) | `ContributorsTests`. plan 사이클 19 research SQL 5·6 | 상수와 시험을 고칩니다. **검색어 다듬기 규칙** + 다시 가져오기 |
| `LEADING_NOTE_KEEP_WORDS` | `SameTextKey` | ④ 비교 제목에서 맨 앞 `( … )`를 뗄 때 남길 낱말(판, 원서·번역, 형태, 뒷권). 목록 밖이라도 "…판"으로 끝나거나 EDITION이 들어 있으면 남깁니다(ADR-0114) | `SameTextKeyTests`. 가져오기 로그 `leading note cut`, dry run 뒤 `compare_grouping.py regroup` 앞 괄호 표본 | 목록에 더하고 시험을 고칩니다. 다시 가져오기 |
| `EDITION_WORDS` | `SameTextKey` | 권 표시에서 읽는 판 낱말(개정, 증보, 특별판 …, 사이클 12 R3-2) | `SameTextKeyTests` | 다시 가져오기 |
| `TAILS` | `SameTextKey` | 제목 끝에서 떼는 이용 안내·시리즈 이름(사이클 12 R3-3) | `SameTextKeyTests` | 다시 가져오기 |
| `PLACEHOLDER_AUTHORS`, `PLACEHOLDER_PUBLISHERS` | `SameTextKey` | 비교에 쓰지 않는 자리 채움 저자·출판사(기타, 미상, unknown) | `SameTextKeyTests`. 로그 `no key for a placeholder` | 다시 가져오기 |
| 공급사 코드 칸 | `CollectedCatalog.SUPPLIER_CODE_COLUMN`, `supplierCodeKey` | ② 공급사 코드 키에 쓰는 칸. `BOOKCUBE_FX`는 `alt_code`, 교보·교보 구독형·YES24는 `item_key`, 그 밖은 없음(ADR-0114). 키 = (공급사 묶음, 코드, **줄의 ④ 비교 제목**, **다듬은 출판사**): 제목과 출판사가 같은 줄끼리만 이음. 사이클 19 dry run에서 703번 도서관 「우리전자책」 줄이 같은 번호를 다른 책에 붙여, 그리고 같은 제목의 다른 출판사 책이 같은 번호로 이어져 더함(사용자 결정 2026-10-16 두 번) | `BookGrouperContractTests`(세 묶는 방식 같은 결과). 로그 `(2) supplier code joins` | 상수와 계약 시험을 고칩니다. 다시 가져오기 |
| `SHARED_CODE_KINDS` | `BookGrouper` | ①에서 상품 번호가 한 번호 공간인 화면 종류 | `scripts/library/check_code_spaces.py` ([library-maintenance.md](../library-maintenance.md) 4-11) | 다시 가져오기 |
| `EXCLUDED_KINDS`, `SOUND_VIDEO_KINDS` | `HoldingRowFilter` | 가져오지 않는 종류(오디오북·동영상북, 소리·영상 파일). 넣기 명령과 같이 씁니다 | 가져오기 기록 `skipped_by_reason` | 다시 가져오기(넣기 명령도 같이 바뀜) |
| `placeholder-date-libraries` | `application-collection-import.yml` | 1950년 앞 날짜를 자리 채움으로 보는 수집 도서관(명지대 100555, ADR-0054) | 로그 `placeholder dates cleared` | 설정을 고칩니다. 다시 가져오기 |

## 4. 추천 목록 (사이클 21, `scripts/crawl/lists/`, `picks` 모듈)

수집기 `scripts/crawl/recommended_lists.py`와 화면 이름·판 고르기 코드가 쓰는 값입니다. 파일 칸은 [`scripts/crawl/lists/README.md`](../../scripts/crawl/lists/README.md), 절차는 [4-picks.md](4-picks.md) 6절.

| 이름 | 어디에 | 뜻 | 확인하는 법 | 바꾸는 법과 그 뒤 할 일 |
|---|---|---|---|---|
| 올해의 책 영상 낱말 | `collector_words.tsv` `year_book_title` | 업로드 재생목록에서 12월에 올라온 영상의 제목에 이 낱말(「올해의」, 「베스트북」)이 들면 올해의 책 영상 후보로 봅니다(사용자 승인 2026-10-16). 「올해의 영화」도 걸리지만 확인 필요 파일에서 사람이 거릅니다 | 수집 기록의 `new book in …` 줄과 `leedongjin_pending.tsv`. 시험 `test_a_december_upload_with_a_year_word_is_left_for_a_person` | 줄을 더하거나 고칩니다. **다음 수집부터** |
| 채널 표지 낱말 | `collector_words.tsv` `not_a_book` | 괄호 안에 있어도 책 이름이 아닌 채널 표지(「이달의 책」, 「베스트북」, 「최고의 책」, 채널·평론가 이름). 공백을 빼고 견줍니다 | pending 파일에 이런 이름이 남으면 낱말을 더합니다. 시험 `test_book_names_and_channel_words` | 줄을 더합니다. 다음 수집부터 |
| 판 거르기 낱말 | `collector_words.tsv` `excluded_title`·`excluded_publisher`·`excluded_name` | 고정 판으로 고르지 않는 판: 제목에 영어원서·원서·어린이·초등·청소년·만화·그림책·논술·「공부가 되는」·「100분 고전」, 출판사에 「어린이」, 기여자에 「번역AI」(spec R3-3·R3-4) | 수집 뒤 `snu100.tsv`의 `pinned_title`·`pinned_publisher`를 훑습니다. 시험 `EditionTests` | 줄을 더하고 수집기를 `--skip-youtube`로 다시 돌린 뒤 목록 파일을 커밋합니다. **다시 가져오기**(export에 실려야 화면이 바뀜) |
| 표지 출처 차례 | `cover_sources.tsv`(수집기 `COVER_SOURCES`가 아는 이름만) | 표지를 찾는 곳과 차례: ① `nlk_isbn` 국립중앙도서관 ISBN 서지(고정 판 ISBN) ② `collected_preferred` 수집 사본 표지 중 `cover_hosts.tsv` 호스트 ③ `collected_other` 수집 사본의 그 밖의 표지 ④ `nlk_holdings` 국립중앙도서관 소장자료 검색(`search.do`의 `imageUrl`, `cover.nl.go.kr`; ISBN, 없으면 제목 + 첫 저자의 성. 같은 제목이라도 저자가 안 보이면 쓰지 않음) ⑤ `nlk_title` ISBN 서지 제목 검색. 출처마다 5개까지 시도하고, 빈 표지 그림은 건너뜀. 모든 줄(우리 책을 못 맞춘 줄도)에 표지를 찾음(사용자 2026-10-16 "책 표지 무조건 다 보였으면 해 인터넷 수집해서(어디서 수집해야 하는지는 기록 다 해)"). `kakao_book`(카카오 책 검색)은 코드에 남아 있지만 차례에서 뺌: 카카오 썸네일은 오래 저장하면 안 됨(plan `docs/research/popular-books/cover-copyright.md` 5절), 사용자 2026-10-17 "도서관정보나루라던가 아니면 공공도서관 api라던가 출처 많자나 … 그걸로 다시 조사하고 반영해봐". 도서관 정보나루 `bookImageURL`은 저장 금지·비영리 화면 표시만이고 ADR-0072로 쓰지 않음 | 수집 기록 `GET <호스트>`·`cover: … from <출처>` 줄. 시험 `test_a_book_we_do_not_have_gets_its_cover_from_the_nlk_holdings_not_kakao`, `test_a_classic_whose_author_does_not_show_stays_a_title_card`, `test_a_missing_rules_file_stops_before_any_request` | 줄 차례를 고치거나 뺍니다. 새 출처는 수집기 코드가 먼저 있어야 함(모르는 이름이면 멈춤). 이미 받은 표지는 그대로이므로 그 책의 `cover_url`을 비우고 다시 돌립니다. 다시 가져오기 |
| 표지 호스트 차례 | `cover_hosts.tsv` | ② 안에서 먼저 시도할 호스트와 차례(YES24 → 교보, 결정 #6) | 수집 기록의 `GET <호스트>` 줄. 시험 `test_collected_covers_put_yes24_and_kyobo_first_as_https` | 줄을 고칩니다. 이미 받은 표지는 그대로이므로, 바꾸려면 그 책의 `cover_url`을 비우고 다시 돌립니다. 다시 가져오기 |
| 빈 표지 그림 | `cover_placeholders.tsv`, 수집기 `PLACEHOLDER_DISTANCE`(24) | "표지 없음" 그림(국립중앙도서관 로고, 교보 「제공된 상품이미지가 없습니다」)의 16x16 평균 해시. 받은 그림의 256비트 가운데 24비트 이하만 다르면 표지로 쓰지 않고 다음 후보로 갑니다. 이미 받은 표지가 이 그림이면 다시 찾음 | 수집 기록 `gave a "no image" picture`. 시험 `test_placeholder_picture_is_skipped_for_the_next_one` | 새 빈 그림을 보면 `image_hash`로 해시를 구해 줄을 더하고 수집기를 다시 돌립니다. 다시 가져오기 |
| 표지 검색 제목 | 목록 줄의 `cover_title` | 목록 제목이 한 책의 이름이 아닐 때 표지를 찾는 제목(서울대 「근현대사 4부작」 → 「혁명의 시대」). 화면 이름은 바뀌지 않음 | `snu100.tsv` | 칸을 채우고 그 줄의 `cover_url`을 비운 뒤 다시 돌립니다 |
| 표지 바꿔 받기 | 수집기 `OUTSIDE_COVER_HOSTS` | 고정 판이 바뀌어 지금 표지가 새 판의 수집 표지 목록에 없으면 다시 받음(「토지」 2권 → 1권). 국립중앙도서관·카카오(Daum CDN) 표지는 판과 묶이지 않아 그대로 둠 | 시험 `test_stale_catalog_cover_is_taken_again_when_the_edition_changed` | 호스트가 바뀌면 고칩니다 |
| 분야 제안표 | `subject_fields.tsv` | ISBN 서지 `SUBJECT` 한 자리 → 이동진 분야 제안(제안만, 사람이 정함, 결정 #1). `note`에 헷갈리는 경우를 적음 | pending 파일의 `field_suggestion` | 줄을 고칩니다. 다음 수집부터 |
| 목록 분야 | `fields.tsv` | 이동진·서울대 분야 코드·이름·순서(spec R5-1·R5-2) | 화면 [분야 ▾] 순서 | 고치고 커밋. 다시 가져오기. 코드를 바꾸면 그 분야를 쓰는 줄의 `field`도 같이 고칩니다 |
| 판 고르기 | `BookChoice`(`picks/internal/cache`), 수집기 `choose()` | 켜진 도서관만 세고 구독형은 몇 곳이든 1(ADR-0125). 여러 권짜리는 **같은 출판사의 같은 세트**(판 표시와 권 번호를 덜어 낸 본제목·출판사가 같음)를 한 판으로 보고, 보유량은 그 세트 권들 가운데 가장 큰 값, 쓰는 책은 그 세트의 1권(1·상, 없으면 가장 작은 권). 수집기는 같은 책을 찾을 때 저자를 하나 이상 같이 가진 판에 더해, 그 판과 같은 출판사의 같은 세트 권은 저자 표기가 달라도 넣음(더클래식 「카라마조프가의 형제들. 1」 「도스토옙스키」 / 「. 2」 「표도르 도스토옙스키」, 2026-10-17). 가장 많이 가진 판, 같으면 책 번호 작은 것(범위 결정 #7, 2026-10-16 바로잡음: 「토지」 1권 207·2권 209 → 1권) | 공용 시험 자료 `scripts/crawl/testdata/book_choice_cases.json`(`BookChoiceTests`와 `test_recommended_lists` `SameRuleAsJava`가 함께 읽음) | 두 코드를 **같이** 고치고 시험 자료에 경우를 더합니다. 사서는 다음 캐시 새로 고침, 이동진·서울대는 수집기 다시 돌리기 + 다시 가져오기 |
| 화면 책 이름 | `ShownTitle`(`picks/internal/cache`), 수집기 `read_title()` | 맞춘 책 제목에서 덜어 내는 것: 앞 `[시리즈]`, " - " 뒤 부제(「… 1 - 세계문학전집 157」), 끝 괄호·대괄호(판·언어 표시 「(Hamlet)」「[한글판]」, 안이 권이면 권 「(상)」「[1]」), 끝 낱말 「오리지널판」·「세트」, 끝 권 번호(「. 1」「 3」「2권」「제2권」, 1~2자리, 「화씨 451」은 권 아님), 끝 「N부 N권」(「토지 1부 1권」, 차례는 부×100+권), 한글 바로 뒤 숫자(「형제들1」, 「블랙홀1984」는 권 아님). 1권(1·상·1부 1권)만 지우고 다음 권부터는 남김(ADR-0129). 2026-10-17 보충(plan matching-guide.md 2절): 카탈로그 끝 권 표기 집계는 plan-feedback "# 사이클 21" | `ShownTitleTests`, `test_volumes_read_like_shown_title`, 공용 시험 자료 `book_choice_cases.json` | 두 코드를 같이 고칩니다. 사서는 다음 캐시, 다른 목록은 수집기 다시 돌리기 + 다시 가져오기(세트 묶기도 같이 바뀜) |
| 30일 확인 | `CuratedLists.CHECK_DAYS`, 수집기 `checked_on` | 이동진 줄을 YouTube에서 30일 넘게 확인 못 하면 export가 경고(YouTube 개발자 정책 III.E.4) | export 로그 WARN `not checked since` | 바꾸지 않습니다(약관) |
| 표지 크기 | 수집기 `COVER_WIDTH`·`COVER_QUALITY`·`COVER_MAX_BYTES` | 폭 200px, JPEG 품질 0.8, 원본 5MB 넘으면 받지 않음(ADR-0091과 같음) | `CoverTests` | 서버 `chaekcheck.picks.fetch.cover`와 같이 고칩니다 |
| 이달의 책 재생목록 | 수집기 `MONTHLY_PLAYLIST` | 「B tv 이동진의 파이아키아」 「이달의 책」 재생목록 ID. 업로드 재생목록은 그 채널에서 API로 얻음 | 수집 기록 `GET www.googleapis.com /youtube/v3/playlistItems` | 채널이 재생목록을 바꾸면 고칩니다 |
