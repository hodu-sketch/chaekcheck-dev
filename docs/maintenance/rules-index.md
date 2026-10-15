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
| 저자 기호 떼기 | `Contributors.MARKS`, `stripMarks` | 저자 글에서 `< >`(모든 너비)와 `〈 〉 「 」 《 》`의 표시를 뗍니다. 안의 글자는 남기고 `[ ]`는 그대로 둡니다(ADR-0113). 저장 이름, 비교 이름, 저자 검색어, 책 표 저자 칸에 같이 씁니다 | `ContributorsTests`. 서비스 DB에서 `SELECT count(*) FROM catalog.book_contributor WHERE name_exact ~ '[<>]'` | 상수와 시험을 고칩니다. **검색어 다듬기 규칙** + 다시 가져오기 |
| 붙은 역할 낱말 | `Contributors.ATTACHED_ROLES`, `SEPARATE_ROLE` | 「A 저B 역」처럼 붙은 역할 낱말을 좁은 규칙으로 나눕니다. 첫 낱말은 저·저자·지음·지은이·글, 둘째는 역·옮김·옮긴이·번역·역자·그림, 셋째까지 나눕니다. 앞에 역할 낱말이 따로 있으면 자르지 않습니다(보호 장치, ADR-0113) | `ContributorsTests`. plan 사이클 19 research SQL 5·6 | 상수와 시험을 고칩니다. **검색어 다듬기 규칙** + 다시 가져오기 |
| `LEADING_NOTE_KEEP_WORDS` | `SameTextKey` | ④ 비교 제목에서 맨 앞 `( … )`를 뗄 때 남길 낱말(판, 원서·번역, 형태, 뒷권). 목록 밖이라도 "…판"으로 끝나거나 EDITION이 들어 있으면 남깁니다(ADR-0114) | `SameTextKeyTests`. 가져오기 로그 `leading note cut`, dry run 뒤 `compare_grouping.py regroup` 앞 괄호 표본 | 목록에 더하고 시험을 고칩니다. 다시 가져오기 |
| `EDITION_WORDS` | `SameTextKey` | 권 표시에서 읽는 판 낱말(개정, 증보, 특별판 …, 사이클 12 R3-2) | `SameTextKeyTests` | 다시 가져오기 |
| `TAILS` | `SameTextKey` | 제목 끝에서 떼는 이용 안내·시리즈 이름(사이클 12 R3-3) | `SameTextKeyTests` | 다시 가져오기 |
| `PLACEHOLDER_AUTHORS`, `PLACEHOLDER_PUBLISHERS` | `SameTextKey` | 비교에 쓰지 않는 자리 채움 저자·출판사(기타, 미상, unknown) | `SameTextKeyTests`. 로그 `no key for a placeholder` | 다시 가져오기 |
| 공급사 코드 칸 | `CollectedCatalog.SUPPLIER_CODE_COLUMN` | ② 공급사 코드 키에 쓰는 칸. `BOOKCUBE_FX`는 `alt_code`, 교보·교보 구독형·YES24는 `item_key`, 그 밖은 없음(ADR-0114) | `BookGrouperContractTests`(세 묶는 방식 같은 결과). 로그 `(2) supplier code joins` | 상수와 계약 시험을 고칩니다. 다시 가져오기 |
| `SHARED_CODE_KINDS` | `BookGrouper` | ①에서 상품 번호가 한 번호 공간인 화면 종류 | `scripts/library/check_code_spaces.py` ([library-maintenance.md](../library-maintenance.md) 4-11) | 다시 가져오기 |
| `EXCLUDED_KINDS`, `SOUND_VIDEO_KINDS` | `HoldingRowFilter` | 가져오지 않는 종류(오디오북·동영상북, 소리·영상 파일). 넣기 명령과 같이 씁니다 | 가져오기 기록 `skipped_by_reason` | 다시 가져오기(넣기 명령도 같이 바뀜) |
| `placeholder-date-libraries` | `application-collection-import.yml` | 1950년 앞 날짜를 자리 채움으로 보는 수집 도서관(명지대 100555, ADR-0054) | 로그 `placeholder dates cleared` | 설정을 고칩니다. 다시 가져오기 |
