# ① 도서관 수집: 어느 전자도서관이 있나 (등록 도서관·수집 목록에서 찾은 도서관)

- plan 짝 문서: `../chaekcheck-plan/docs/research/collection/1-libraries.md` (받는 것·내놓는 것·원칙·결정). 여기에는 절차만 둡니다(ADR-0047).
- 순서: [수집 목록에서 도서관 찾기](#수집-목록에서-도서관-찾기)(찾기·확인) → 책 수집([2-crawl.md](2-crawl.md)) → [새 도서관 넣기](#새-도서관-넣기-넣기-명령-library-insert)(미리 보기 → 보고서 → 넣기, [같은 사이트·같은 호스트 거르기](#같은-사이트같은-호스트-거르기) 포함) → [구독 규칙·마감 확인](#구독-규칙마감-확인). 등록 도서관 확인은 [아래 자리표](#등록-도서관-확인-어디를-보나).

정기 유지보수 ① 갈래([README.md](README.md))의 입구입니다. **등록 도서관 확인 절차 원본은 [`docs/libraries/library-maintenance.md`](../libraries/library-maintenance.md)** 이고, 이 파일은 그 문서를 가리킵니다. 그 문서에 없는 "수집 목록에서 도서관 찾기", "새 도서관 넣기", "구독 규칙·마감 확인"은 이 파일 아래 절이 절차 원본입니다(plan ADR-0047 요청서 D, 2026-10-04, 넣기는 사이클 13 ADR-0084).

- 옮기지 않고 링크로 둔 이유(dev 판단, 2026-10-04): `CLAUDE.md` "Library Research Record" 규칙과 `docs/libraries/library-site-issues.md`, 지난 조사 보고서, plan 문서들이 그 경로와 절 번호를 가리킵니다. 파일을 옮기면 이 참조가 모두 끊깁니다.
- 도서관 조사 결과와 사용자 결정은 계속 `library-maintenance.md`에 적고, 뺀 사이트와 이유는 [`docs/libraries/library-site-issues.md`](../libraries/library-site-issues.md)에 적습니다(`CLAUDE.md` 규칙). 넣기 명령이 뺀 곳 전체 목록은 [`docs/libraries/library-left-out.md`](../libraries/library-left-out.md)입니다.
- 수집 일 나눔 [확정] (사용자, 2026-10-04, plan `docs/research/maintenance/collection-areas.md`): ① 도서관 정보(어느 도서관이 있나, 화면 종류, 가입 자격, 구독 규칙, 마감 확인) = 이 1단계 / ② 도서관-책 = [2-crawl.md](2-crawl.md) / ③ 가져오기 = [3-import.md](3-import.md).

## 도서관 목록 두 가지 [확정] (사용자, 2026-10-04)

| 이름 | 어디에 있나 | 수 (2026-10-04) | 검색에 나오나 |
|---|---|---|---|
| **등록 도서관** | 서비스 DB `library` (dev seed, `library-maintenance.md` 1절) | 278곳 | 나옴 |
| **수집 목록에서 찾은 도서관** | 수집 DB(`chaekcheck-data\ebook-catalog.sqlite`) `library` 번호 100001\~. 웹러브 북스 기관 목록에서 찾음. 사이클 07부터 전체 수집이 끝난 곳은 서비스 DB에도 새 번호로 들어감(사이클 07은 seed, 사이클 13부터 아래 "새 도서관 넣기") | 2,738곳 (2026-10-05 서비스 DB 2,394곳) | 가입 조사된 곳은 나옴. 학교·회사 등 구성원용과 종류 모름은 직접 추가했을 때만(ADR-0053) |

- 이전에 쓰던 이름 "서비스 도서관"은 등록 도서관, "웹러브 북스 기관"·"수집 전용 기관"·"후보 도서관"(사이클 13 전 이름, 지난 기록에 남아 있음)은 수집 목록에서 찾은 도서관과 같은 뜻입니다.
- 수집 번호와 서비스 번호를 잇는 짝 표는 서비스 `collection.collection_link`입니다(등록 도서관은 같은 번호). 가져오기는 이 짝 표로 수집 번호를 서비스 번호로 바꿉니다(ADR-0052).

## 등록 도서관 확인: 어디를 보나

| 하려는 일 | `library-maintenance.md` |
|---|---|
| 시작 전 확인, 데이터 구조(표·칸, 화면 종류 `platform_type`, seed 파일) | 0·1절 |
| 호출 예절 | 2절 |
| 확인 도구 (`scripts/library/`) | 3절 |
| 전국 전자도서관 목록 찾기 (출처, 주소 모양, 주소 하나씩 확정) | 4-0\~4-7절 |
| 가입 자격, 가입 방법·가입 페이지 | 4-8, 4-10절 |
| 운영 중 바뀐 것을 알아차리는 신호 (서비스 로그) | 4-9절 |
| 책 번호 규칙 점검 | 4-11절 |
| 도서관 추가 / 수정·끄기·삭제 | 5(새 도서관은 이 문서 "새 도서관 넣기") / 6절 |
| Liquibase와 로컬 DB, 확인 | 7·8절 |
| 사람에게 물을 것, 결과 기록 | 9·10절 |
| 확인 도구 결과별 처리, 알려진 사이트 특이점 | 11·12절 |
| 지금까지의 작업 기록, 사용자가 직접 확인할 목록 | 13·14절 |

1단계가 끝나 등록 도서관이 바뀌었으면 2단계 [2-crawl.md](2-crawl.md)에서 `crawl_catalog.py init`으로 수집기 도서관 목록을 다시 읽습니다.

## 수집 목록에서 도서관 찾기

웹러브 북스 기관 목록(교보는 교보 공식 기관 목록)의 사이트를 한 번씩 열어 보고(열리는지, 화면 종류, 권수), 열린 곳을 수집 DB `library`에 넣는 절차입니다. 원문: plan `crawl-runbook.md` 8-1절 (2026-10-03, 지금은 [2-crawl.md](2-crawl.md) 8-1절), 같은 문서 4절 "dev에 반영할 것" (2026-10-04).

- **도구 위치** (2026-10-05 복사, ADR-0047 B·C, 폴더는 2026-10-12에 `discover/`로 이름 바꿈): 확인 스크립트 `probe_*.py`는 `scripts/library/discover/`, 수집 DB에 넣는 `import-probe`·`import-bookcube`는 수집기 `scripts/crawl/crawl_catalog.py` 안. 결과·입력 TSV는 git 밖 데이터 폴더 `other-platforms\`에 둡니다(아래). 저장소 뿌리에서 `python -X utf8 scripts\library\discover\probe_yes24.py 입력.tsv 결과.tsv`처럼 부르고, TSV 경로는 데이터 폴더 기준으로 줍니다.
- 웹러브 북스 목록 https://ebook.okbari.net/library 는 제3자 자료(수집 기준일 2026-02-06으로 보임)라 그대로 믿지 않고 2번에서 한 번씩 확인합니다. 모으는 방법은 `library-maintenance.md` 4-4절.

| 단계 | 교보 | YES24 | 북큐브 | 알라딘 |
|---|---|---|---|---|
| 1. 기관 목록 | plan `docs/cycles/02-library-expansion/data/kyobo-institutions.tsv` (교보 공식 목록, 2,000줄 → 사이트 1,937곳) | 웹러브 북스 기관 탭을 브라우저로 모음 → `chaekcheck-data/other-platforms/okbari-2026-10-03/part1~3.tsv`, `yes24-candidates.tsv`(시험·개발용 이름 제외 775곳) | 같은 방법 → `bookcube-candidates.tsv`(495곳) | 같은 방법 → `okbari-2026-10-03/aladin-candidates.tsv`(시험용 2곳 제외 163곳) |
| 2. 한 번씩 확인 (열리는지, 화면 종류, 권수) | `scripts/library/discover/probe_kyobo_institutions.py` → `other-platforms/kyobo-institutions-probe-2026-10-03.tsv`. 첫 화면 `library_model`(소장·대여·혼합)도 적음 | `scripts/library/discover/probe_yes24.py` → `okbari-2026-10-03/yes24-probe.tsv`. V1·V2·V3를 가림 | `scripts/library/discover/probe_other_platforms.py` → `okbari-2026-10-03/bookcube-probe.tsv` | `scripts/library/discover/probe_aladin.py` |
| 3. 수집 DB 도서관 목록에 넣기 | `scripts/crawl/crawl_catalog.py import-probe --probe … --source okbari-2026-10-03` | 같음 | `crawl_catalog.py import-bookcube --probe … --source okbari-2026-10-03` | `import-probe`. 사이트마다 다른 전자책 메뉴 코드는 `base_url` 뒤 `?nav_code=…`로 저장 |
| 4. 받기 | [2-crawl.md](2-crawl.md): `full --retry-failed --platforms …` 또는 `resume.cmd` | 같음 | 같음 | 같음 |

- 입력·결과 TSV 이름의 `candidates`는 데이터 폴더에 이미 있는 파일 이름이라 그대로 둡니다.
- **2단계 "확인"에서 주소는 리다이렉트 뒤 최종 주소(호스트)로 넣습니다** (plan 사이클 08 인계서 10-3, 2026-10-11). 목록에 있는 주소가 다른 호스트로 넘어가면 넘어간 뒤의 주소를 수집 DB `library.base_url`에 넣습니다. 서비스는 리다이렉트를 따라가지 않고, 가져오기는 주소가 서비스 도서관과 다른 줄을 빼기 때문입니다(예: 411 GS건설 자이가 `ebook2.ezville.net` → `gsenc.bookcube.biz`로 넘어가 사이클 07 가져오기에서 소장 64,461줄이 빠짐. 2026-10-11에 수집 DB 주소를 고치고 다시 받음, [2-crawl.md](2-crawl.md) 5-1절 아래).
- 넣은 곳은 `library.source = okbari-2026-10-03`, id 100001부터. 이미 있는 주소는 3번에서 건너뜁니다. 2026-09-30에 먼저 넣은 북큐브 134곳은 `source=okbari-2026-09-30`([2-crawl.md](2-crawl.md) 5절).
- 교보 대여·혼합형(2026-10-04 넣음, 232곳, 11곳은 이미 있음): `kyobo-institutions-probe-2026-10-03.tsv`에서 `model`이 대여·혼합인 줄만 골라 `import-probe`, `source=okbari-2026-10-03-rental`.
- **교보 대여·혼합형은 수집도 서비스도 하지 않습니다** (plan ADR-0067, 2026-10-11). 232곳 중 230곳에 `crawl_skip` "교보 대여·혼합 제외 (plan ADR-0067, 2026-10-11)"를 표시했고(나머지 2곳은 이미 "같은 사이트"로 표시됨), 넣기 명령은 `rules/kyobo_probe.tsv`의 운영 방식으로 뺍니다. 등록 도서관 11곳과 공공도서관 구독은 그대로입니다. 사이클 13부터 수집기는 교보 구독형 서버(`*.dkyobobook.co.kr`)에 요청을 보내지 않습니다([2-crawl.md](2-crawl.md) 0-1절).
- 확인 스크립트는 모두 결과를 한 줄씩 바로 저장하고, 다시 돌리면 이미 확인한 곳을 건너뜁니다. 도구의 백그라운드 작업은 30분쯤에 끊기므로 긴 작업은 PowerShell `Start-Process`로 따로 띄웁니다(2026-10-03 교보 확인이 끊겨 결과를 잃은 뒤 고침).
- 확인 스크립트는 서버마다 1건·1초 고정이고 `--workers`·`--host-slots` 값을 받지 않습니다. 수집과 같은 서버를 동시에 치지 않게 띄우기 전에 서버가 겹치는지 봅니다([2-crawl.md](2-crawl.md) 2절).
- 결과 TSV(데이터 폴더 `other-platforms\*.tsv`)는 git에 넣지 않습니다 (dev 판단, 2026-10-05). 내용은 공개된 기관 이름·주소·권수·공지 제목뿐이고 크기도 작지만(TSV 9개 약 0.75MB, 하위 폴더 0.4MB), 실행 결과라 데이터 폴더에서 계속 바뀌고(`kyobo-limits-log.tsv`는 매일 더해짐) git에 복사하면 두 벌이 어긋나며, 팝업 그림(1.7MB)은 기관이 만든 그림입니다. 남길 사실(넣은 곳 수, 결정)은 이 문서와 [2-crawl.md](2-crawl.md) 8-2절에 적습니다. 넣기 명령이 읽는 값은 git 안 규칙 폴더 `scripts/library/rules/`에 옮겨 두었습니다(2026-10-12).
- 진행 숫자(플랫폼별 열림·오류·넣은 곳)는 [2-crawl.md](2-crawl.md) 8-2절(2026-10-12 plan `crawl-runbook.md`에서 옮김).
- 가입 자격: 수집 목록에서 찾은 공공도서관은 넣기 전에 조사합니다(`library-maintenance.md` 4-8·4-10절, 결과는 `scripts/library/rules/public_join.tsv`, `decision` 칸이 사용자 결정. 옛 원본은 데이터 폴더 `library-join\public-join-*.tsv`). 학교·대학·회사·기관·단체(구성원용)와 종류 모름은 조사하지 않고 조사 상태(`join_survey`)로 넣습니다. 이것은 `CLAUDE.md` 규칙의 예외입니다(ADR-0053, 2026-10-05, `library-maintenance.md` 13절).

## 새 도서관 넣기 (넣기 명령 `library-insert`)

수집 DB에서 찾고 전체 수집까지 끝난 도서관을 서비스 DB에 넣는 단계입니다. 사이클 13부터(2026-10-12) **seed가 아니라 넣기 명령**이 넣습니다(plan ADR-0084, spec R2). 명령은 `collection` 모듈에 있고 한 번 돌고 끝납니다(프로필 `library-insert`, 웹 서버 없음). 서비스가 같은 DB에서 돌고 있어도 됩니다.

- **seed로 도서관을 다시 넣지 않습니다** [확정] (ADR-0084, plan spec R2-10). 이미 적용된 seed(`library/008` 등)는 그대로 두고 새 seed 파일을 만들지 않습니다. 옛 스크립트 `scripts/library/build_library_seed.py`는 같은 사본으로 결과가 같은지 대조하는 비교용 출력만 합니다(`library-maintenance.md` 3절).
- 도서관과 가입 규칙 줄은 `library` 모듈의 공개 기능 `LibraryRegistry.add`(번호는 부르는 쪽이 정함, ADR-0083), 짝 줄(`collection.collection_link`)과 실행 기록(`collection.library_insert_run`)은 `collection`이 넣습니다.

### 규칙 폴더 (`scripts/library/rules/`)

사람이 정한 도서관 규칙과 조사 결과를 규칙마다 TSV 하나로 둡니다(ADR-0085). 파일·칸·출처는 [`scripts/library/rules/README.md`](../../scripts/library/rules/README.md). 넣기 명령(설정 `rules-dir`)과 `build_library_seed.py`가 같은 폴더를 읽고, 보고서에 파일마다 SHA-256이 남습니다.

- 고친 뒤: `python -m unittest scripts/library/test_build_library_seed.py`. `public_join.tsv`를 고치면 먼저 `python scripts/library/resolve_regions.py`(지역 이름 → 번호)
- **새로 넣는 곳은 `final_origins.tsv`에 반드시 한 줄** [확정] (사용자 선택 "빠진 곳은 확인해 채움", 2026-10-12): 수집 DB의 목록 주소를 `java scripts/library/CheckFinalOrigins.java <in.tsv>`로 확인해 최종 주소를 적습니다(`library-maintenance.md` 4-7 1번). 리다이렉트가 없는 곳(`SAME`)도 같은 주소로 적습니다. 줄이 없는 새 도서관이 있으면 명령이 규칙 오류(종료 1)로 멈춥니다.
- 남은 곳에 종류(`library_kinds.tsv`)가 없으면 멈춥니다(spec R2-5). 공공도서관은 가입 조사 줄(`public_join.tsv`)도 있어야 조사 상태가 정해집니다.
- 가입 조건 조사 기준일 `join_surveyed_on.txt`(지금 `2026-10-05`)는 실행 기록에 남고 `/about` 공지에 쓰입니다(ADR-0087).

### 1. 수집 DB 사본

가져오기와 같은 배포용 사본(`chaekcheck-data\snapshots\ebook-catalog-…sqlite`)을 씁니다([3-import.md](3-import.md) 2절). 명령은 사본을 읽기 전용으로 엽니다. 넣을 곳은 수집 DB에서 전체 수집이 한 번 끝나 있어야 합니다(아니면 "전체 수집 성공 없음"으로 빠짐).

### 2. 미리 보기 (DB에 쓰지 않음)

`backend/`에서 (명령 줄 원본: `LibraryInsertRunner.java` 주석, 설정 `application-library-insert.yml`):

```
./gradlew bootRun --args="--spring.profiles.active=library-insert
  --chaekcheck.collection.library-insert.mode=preview
  --chaekcheck.collection.library-insert.sqlite-path=../../chaekcheck-data/snapshots/ebook-catalog-2026-10-12-0135.sqlite
  --chaekcheck.collection.library-insert.rules-dir=../scripts/library/rules
  --chaekcheck.collection.library-insert.report-dir=../../chaekcheck-data/library-insert"
```

- 보고서는 데이터 폴더 `chaekcheck-data\library-insert\library-insert-<보고서 번호>.json`에 씁니다(git 밖). 보고서 번호는 내용(사본 파일 이름, 규칙 파일 해시, 조사 기준일, 계산 결과)의 SHA-256 앞 12자리라 입력이 같으면 번호도 같습니다. 파일에는 번호와 만든 시각이 더 붙습니다.
- 경로는 인자로만 주고 저장소에 적어 두지 않습니다. 사본 이름은 그때의 사본으로 바꿉니다.

### 3. 보고서 읽기

| 키 | 뜻 | 할 일 |
|---|---|---|
| `firstId` | 첫 새 도서관이 받을 서비스 번호(`LibraryRegistry.nextId()`). 쓴 번호는 다시 쓰지 않음 | 확인만 |
| `newLibraries` | 새로 넣을 곳: 수집 번호와 서비스 번호·이름·업체·대출 방식·순번·가입 조사 상태·가입 규칙·주소 | Claude가 읽고 종류·이름이 이상하면 사용자에게 물음 |
| `changed` | 짝이 있는 서비스 도서관 중 수집 DB 값이 다른 곳(사이트 주소, 기관 이름, 화면 종류·대출 방식, 받지 않음·실패) | **사용자에게 물음**. 명령은 보고만 하고 고치지 않음. 고치기는 지금처럼 SQL·changeset(spec R2-9, `library-maintenance.md` 6절) |
| `gone` | 짝이 있는데 사본에서 없어졌거나 받지 않음이 된 곳 | **사용자에게 물음**. 확인하고 서비스에 그대로 둘 곳은 `rules/gone_known.tsv`에 적음 |
| `goneKnown` | `gone` 중 `gone_known.tsv`에 있어 다시 묻지 않는 수집 번호(2026-10-12에 44·261·269를 적었으나 사이클 18에서 셋을 끄고 지움(2026-10-14, `library/016`). 지금 0줄) | 없음 |
| `suspects` | 사람이 먼저 읽을 새 곳: `SAME_HOST`(아래 "같은 사이트·같은 호스트 거르기"), `ORDER_NO`(같은 이름·업체·대출 방식에 순번 2 이상) | **사용자에게 물음**. 다른 도서관이면 `same_host_kept.tsv`, 같은 사이트면 `same_site.tsv`, 순번 2를 그대로 두면 `order_no_allowed.tsv`에 적고 다시 미리 보기 |
| `leftOut` | 이유별로 뺀 수집 번호(교보 대여·혼합, `crawl_skip`, 전체 수집 성공 없음, 체험용·운영 중단, 같은 사이트, 종류 TEST, spec R2-5. 사이클 18부터 맨 끝에 `no_ebooks` "전자책 0권": 가져오기와 같은 거르기 규칙으로 들어갈 책 줄이 0인 곳, 묻지 않음, ADR-0110) | [`docs/libraries/library-left-out.md`](../libraries/library-left-out.md)를 `python scripts/library/list_left_out.py --report …`로 다시 만듦 |

- **전자책 0권** (사이클 18, plan 설계 결정 #6): 보고서의 `no_ebooks` 목록에서 넣을 주소(`final_origins.tsv`)가 수집 주소와 다른 곳은 사이트를 열어 확인합니다. 책 주소의 호스트가 넣을 주소와 달라 0권이 된 것일 수 있습니다.
- **묻는 범위** [확정] (사용자 선택 "Claude가 보고, 의심·바뀐 곳만 내게", 2026-10-12, spec R2-8): 보고서는 Claude(수집 세션)가 먼저 읽습니다. `suspects`, `changed`, `gone`이 있을 때만 사용자에게 묻고, 없으면 넣기까지 하고 결과를 알립니다.
- 첫 미리 보기 (2026-10-12, 사본 `ebook-catalog-2026-10-12-0135.sqlite`, 보고서 `1af0721e4a3e`): 새 곳 6(서비스 2675\~2680), 바뀐 곳 0, 사라진 곳 0(+알려진 3), 의심 0.

### 4. 넣기 (`mode=insert`)

미리 보기와 같은 명령에서 `mode=insert`로 바꾸고 보고서 번호를 줍니다.

```
  --chaekcheck.collection.library-insert.mode=insert
  --chaekcheck.collection.library-insert.report-id=<보고서 번호>
```

- 명령이 보고서를 다시 계산해 파일과 같을 때만 넣습니다(spec R2-2). 사본이나 규칙 파일, 서비스 DB의 다음 번호가 바뀌었으면 종료 4로 멈추고, 미리 보기부터 다시 합니다.
- 한 트랜잭션: 잠금(`pg_advisory_xact_lock`, 운영 반영과 같은 잠금) → 도서관·가입 규칙 → 짝 줄 → 실행 기록 한 줄(보고서 번호, 사본, 넣은 수, 조사 기준일). 넣을 곳이 없으면 아무것도 쓰지 않고 0으로 끝남.

### 5. 종료 코드

| 코드 | 뜻 | 할 일 |
|---|---|---|
| 0 | 끝남 (미리 보기: 보고서 씀, 넣기: 넣음 또는 넣을 곳 없음) | 결과 기록 |
| 1 | 규칙 오류(규칙 파일 꼴, `final_origins.tsv` 줄 없음, 종류 없음 등). 보고서를 쓰지 않음 | 로그 메시지대로 규칙 폴더를 고치고 다시 |
| 2 | 같은 호스트 의심이 남음 | 사용자에게 묻고 `same_host_kept.tsv`/`same_site.tsv` |
| 3 | 순번 2 이상이 남음 | 사용자에게 묻고 `order_no_allowed.tsv` |
| 4 | 보고서가 없거나 다시 계산한 내용과 다름 (넣기) | 미리 보기부터 다시 |
| 5 | DB가 줄을 거절함(제약 위반 등). 모두 롤백하고 `FAILED` 실행 기록 줄만 남김 | 로그를 보고 원인을 고친 뒤 다시 |

미리 보기도 의심이 남으면 보고서를 쓴 뒤 2나 3으로 끝납니다.

### 6. 넣은 뒤

- 결과 셈(넣은 곳, 번호, 조사 상태)을 `library-maintenance.md` 13절과 `docs/cycles/plan-feedback.md`에 적습니다.
- [3-import.md](3-import.md)로 그 도서관 책을 가져옵니다.
- **운영 DB에는 넣기 명령을 따로 돌리지 않습니다**: 반영 파일 판 2가 내 PC의 성공한 넣기 실행 기록을 옮깁니다([3-import.md](3-import.md) 13-5절).
- **로컬 DB를 새로 만들면**(Liquibase seed부터) seed에는 넣기 명령으로 넣은 도서관이 없으므로, 미리 보기 → 넣기를 다시 돌립니다(spec R2-10, `library-maintenance.md` 7절).

## 같은 사이트·같은 호스트 거르기

같은 사이트가 서비스에 두 번 들어가 같은 책이 두 번 나오지 않게 합니다 [확정] (사용자, 2026-10-05, plan spec R8). 사이클 13부터 넣기 명령이 미리 보기에서 거릅니다.

1. 수집 세션이 이미 표시한 곳: 수집 DB `library.crawl_skip`(예 "same site as registered library 41 …"). `leftOut`으로 빠집니다.
2. 사람이 정한 같은 사이트: `rules/same_site.tsv`(서비스 도서관과 포트만 다른 같은 교보 사이트 8곳, 2026-10-05). `leftOut`으로 빠집니다.
3. **같은 호스트 의심** (spec R2-7): http/https와 포트를 무시한 호스트가 같고 책 번호(`item_key`)가 겹치면 `SAME_HOST` 의심입니다. 새 곳끼리, 그리고 **서비스 전체**와 봅니다(사이클 07 스크립트는 처음 278곳과만 봄). 사람이 "다른 도서관"으로 정한 곳(`rules/same_host_kept.tsv`: 2026-10-05 22곳 + 여주대 100584·102084, 2026-10-12 "둘 다 넣음")은 빼고 봅니다.
4. 사람이 읽고 정함. 2026-10-05 결정: 양쪽 책 번호 목록이 같고 상세 주소가 포트만 다른 8곳은 뺌, 같은 기관의 다른 사이트(북큐브 등)와 한 서버의 여러 도서관은 넣음. 기록: [`docs/libraries/library-site-issues.md`](../libraries/library-site-issues.md) 8절.
- 겹침 기준 값(몇 %부터 같은 사이트인가)은 정하지 않았습니다. 쌍마다 사람이 읽습니다 [확인 필요].
- 수집기 `scripts/crawl/crawl_catalog.py import-probe`에는 이 비교를 넣지 않았습니다(dev 판단, 2026-10-05): 넣기 단계에서 반드시 거르고, 수집기는 받기만 합니다.

## 로고 받기

- 로고 원본과 목록은 데이터 폴더 `logos\`(`registered-logos-*.tsv`, `logos/<번호>.<확장자>`, `logos.tsv`)에 있습니다. 받는 스크립트는 2026-10-12에 데이터 폴더에서 dev로 옮겼습니다: `python scripts/library/probe_logos.py [--data-dir DIR] [--only ID,ID]`(전자도서관 사이트 로고), `python scripts/library/probe_home_logos.py [--data-dir DIR] <ids>`(사이트에 로고가 없을 때 도서관 본 홈페이지 로고, `logos/home/`). 결과는 데이터 폴더에 씁니다.
- 서비스 파일 만들기: `java scripts/library/BuildLogos.java ../chaekcheck-data/logos/registered-logos-2026-10-05.tsv` → `web/public/library-logos/`(높이 약 48px, 형식 그대로)와 `library/009-library-logos.csv`. 그림이 아닌 파일은 빼고 출력합니다(2026-10-05: 238번).

## 구독 규칙·마감 확인

교보 구독형 사이트(대여·혼합형)가 1인 월 권수, 월·연 예산 마감, 다시 열리는 날을 공지했는지 보는 절차입니다. 원문: plan `crawl-runbook.md` 4절 "dev에 반영할 것" (2026-10-04), 조사 기록과 결과는 plan `docs/research/library-limits/research.md` 3-1절, 알리는 방법 결정은 같은 문서 4절.

- **주의 (2026-10-12)**: 읽는 곳이 모두 교보 구독형 서버(`*.dkyobobook.co.kr`)입니다. 그 서버는 2026-10-07부터 이 집 IP를 막았고, 수집기는 사이클 13부터 그 서버에 요청을 보내지 않습니다(plan spec R1-6, [2-crawl.md](2-crawl.md) 0-1절). 이 도구에는 그 거르기가 없으므로 돌리기 전에 사용자에게 묻습니다 [확인 필요: 사용자 결정].
- **도구** (2026-10-05 복사, ADR-0047 C): `scripts/library/probe_kyobo_limits.py`. 수집 DB에서 교보 구독형 중 대여·혼합형(`library_model`) 도서관을 읽기 전용으로 읽습니다.
  - 한 번 조사: `python -X utf8 scripts\library\probe_kyobo_limits.py 결과.tsv` (다시 돌리면 결과에 있는 도서관은 건너뜀)
  - 매일 확인: `scripts\library\limits-daily.cmd` = `probe_kyobo_limits.py --log other-platforms\kyobo-limits-log.tsv other-platforms\kyobo-limits-images`. 처음 보는 팝업·공지만 로그에 더하고(`first_seen`), 새 팝업 그림을 내려받아 둠. 그림은 사람이 나중에 읽음. 데이터 폴더에서 돌고 로그는 데이터 폴더 `logs\limits-daily-{날짜}.log`
- 읽는 곳: 교보 구독형 사이트의 `/popup/popupMain.json`(첫 화면 팝업), `/main/firstBoardBlbrSrmb.json`, `/board/listBoard.json`(공지 게시판). **로그인 없이, 3초 간격**.
- 팝업·공지 본문은 대개 그림이라 제목과 날짜를 읽고, 필요한 그림은 내려받아 읽습니다(예: 기장 `https://ss21.kr:442/images1/gijanglib_20261001.jpg`, 서구 `https://ss21.kr:442/images1/seogulib_20260923.jpg`).
- 결과: 데이터 폴더 `other-platforms\kyobo-limits-2026-10-04.tsv`. 2026-10-04에 등록 도서관 교보 구독형 16곳 + 수집 목록에서 찾은 교보 대여·혼합형 232곳 = 248곳을 확인했고, 마감 관련 제목이 있던 곳은 강동구립·서구 두 곳(plan `library-limits/research.md` 3-1절).
- **주의: "공지 없음"은 "이용 가능"이 아닙니다.** 월 예산 소진은 로그인하고 대출을 눌러야 보입니다("이용기관의 월간 대여 횟수가 초과되었습니다"). 그래서 이 확인은 마감 공지가 있는지만 봅니다.
- 교보 구독형 사이트에는 매달 말 "구독형 디지털도서관 ○월 뉴스레터"가 올라옵니다. 뉴스레터가 끊긴 것(제주한라)이 마감 신호인지는 확인 못 함 [확인 필요].
- 언제: 1단계를 할 때(도서관별 1인 월 권수와 한 해 운영 기간은 이용안내에서 읽음), 마감 공지는 정기 점검 때 사람이(나중에는 수집기가) 확인 [확정] (사용자 승인, 2026-10-04, plan `library-limits/research.md` 4절). 값을 어디에 둘지(도서관 정보 칸 / 따로 "구독 규칙")는 사이클에서 정함 [확인 필요: 사이클에서 결정].
- 등록 도서관에서 마감으로 끈 곳의 재개 확인 날짜는 `library-maintenance.md` 6-2절(예: id 131 서구 구독 2027-01).
