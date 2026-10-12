# scripts/crawl — 전자책 목록 수집기

chaekcheck 서비스의 여러 조건 검색(제목 + 저자 + 출판사)을 위해 전자도서관 소장 목록을 미리 모으는 도구입니다. 2026-10-05에 `chaekcheck-data`(git 밖)에서 이 저장소로 **코드만** 옮겼습니다(plan ADR-0047 B). 수집한 데이터는 계속 git 밖의 **데이터 폴더**에 둡니다.

- 데이터 폴더: `--data-dir` 인자 > 환경 변수 `CHAEKCHECK_DATA` > 저장소 옆 `chaekcheck-data` 폴더(기본값)
- 절차(이어 받기, 받는 순서, 병렬·IP 줄, 문제별 대처): [`docs/maintenance/2-crawl.md`](../../docs/maintenance/2-crawl.md), 플랫폼별 방법: [`docs/maintenance/platforms.md`](../../docs/maintenance/platforms.md)
- 근거 문서(plan, 읽기만): `chaekcheck-plan/docs/research/ebook-catalog/`의 `research.md`(왜·법적 쟁점), `crawl-method.md`(수집 원칙·항목), `catalog-refresh.md`(주기 갱신), `same-book-isbn.md`(같은 책 묶기), `search-and-normalization.md`(정리 규칙. 수집 DB는 **원문 그대로** 둠)

## 실행 환경

Python 3.11 이상, `pip install -r scripts/crawl/requirements.txt`(`beautifulsoup4`, `lxml`). 나머지는 표준 라이브러리.

## 갈아타기 (2026-10-12, 사이클 13, plan ADR-0088)

- **2026-10-12부터 이 저장소의 수집기만 돌립니다.** 데이터 폴더의 옛 코드(`chaekcheck-data\scripts\`, 맨 위 `resume.cmd`)는 `chaekcheck-data\_old-scripts-2026-10-12\`로 **옮겼고 지우지 않았습니다**. 옛 코드는 다시 돌리지 않습니다.
- 옛 `refill_pubdt.py`는 하위 명령 `refill-pubdt --only ID`가 대신합니다(아래 "실행").
- 데이터 폴더 도구 `fetch.py`, `probe_logos.py`, `probe_home_logos.py`는 `scripts/library/`로 복사했습니다(데이터 폴더 규칙은 위와 같음).
- 절차와 진행 기록: [`docs/maintenance/2-crawl.md`](../../docs/maintenance/2-crawl.md) 0-1절·11절.

## 이어 받기

`scripts\crawl\resume.cmd [WORKERS] [PER_HOST] [GAP]`. 여러 번 실행해도 안전합니다(끝난 곳은 건너뛰고 멈춘 곳은 이어 받음). 한 번에 하나만 돕니다: `crawl_catalog.py`가 `full`이나 `refill-pubdt`로 돌고 있거나 옛 `refill_pubdt.py`가 돌고 있으면(옛 `chaekcheck-data\scripts` 복사본에서 띄운 것 포함) 시작하지 않습니다. 데이터 폴더의 `queue-by-ip.txt`(있으면 IP마다 줄 하나)를 옮기기 전과 같은 뜻으로 읽고, 로그는 데이터 폴더 `logs\`에 씁니다.

- `host-slots.txt`는 사이클 13부터 읽지 않습니다. 그 파일의 유일한 줄이 교보 구독형 서버(아래 "실행")였기 때문입니다. 파일이 있으면 "host-slots.txt is ignored since cycle 13" 한 줄만 냅니다.
- `--include-big-subscription`은 넘기지 않습니다(아래, 효과 없음).

## 파일

코드(이 저장소):

| 경로 | 내용 |
|---|---|
| `scripts/crawl/crawl_catalog.py` | 전체 목록 수집기. 수집 목록(웹러브 북스 기관 등)에서 찾은 도서관을 수집 DB에 넣는 `import-probe`·`import-bookcube`, 빈 곳 채우기 `refill-pubdt` 포함 |
| `scripts/crawl/verify_detail_urls.py` | 도서관·종류마다 상세 URL 하나를 열어 그 책이 맞는지 확인 (수집과 동시에 돌리지 않음) |
| `scripts/crawl/resume.cmd` | 수집 이어 받기 (한 번에 하나만 돎) |
| `scripts/crawl/test_crawl_migrate.py` | `migrate`·교보 구독형 서버 거르기·막힌 사이트 표시 시험(네트워크 없음): `python -m unittest scripts/crawl/test_crawl_migrate.py` |
| `scripts/library/discover/probe_kyobo_institutions.py` | 수집 목록에서 도서관 찾기: 교보 기관 사이트 한 번씩 확인(열리는지, 일반·구독형, 운영 방식, 권수) |
| `scripts/library/discover/probe_yes24.py` | 수집 목록에서 도서관 찾기: YES24 기관 사이트 한 번씩 확인(화면 종류 V1·V2·V3, 권수) |
| `scripts/library/discover/probe_other_platforms.py`, `probe_aladin.py` | 수집 목록에서 도서관 찾기: 북큐브·알라딘 기관 사이트 한 번씩 확인 |
| `scripts/library/probe_kyobo_limits.py`, `limits-daily.cmd` | 구독 규칙·마감 확인: 교보 구독형 사이트의 팝업·공지 제목과 날짜(로그인 없이) |

데이터(데이터 폴더, git에 넣지 않음):

| 경로 | 내용 |
|---|---|
| `ebook-catalog.sqlite` | 수집 DB (SQLite, WAL) |
| `queue-by-ip.txt` | 실행 설정(위 "이어 받기"). `host-slots.txt`는 남아 있어도 읽지 않음 |
| `_old-scripts-2026-10-12/` | 갈아타기 전 옛 수집기·도구와 `resume.cmd` 보관(위 "갈아타기") |
| `logs/crawl-*.log`, `crawl-*.log` | 수집 실행 로그 |
| `other-platforms/` | 수집 목록에서 도서관 찾기·마감 확인 결과 TSV와 팝업 그림(웹러브 북스 기관 목록, 확인 결과, `kyobo-limits-log.tsv`) |
| `secrets/` | 국립중앙도서관 인증키(열거나 출력하지 않음) |

## DB 구조

- `library`: 수집 대상 도서관. `source` = `dev-seed`(id 1~280) 또는 `okbari-2026-09-30`(id 100001부터, 웹러브 북스 목록에서 확인해 넣은 북큐브 134곳, `platform_type=BOOKCUBE_FX`). dev seed CSV(`backend/src/main/resources/db/changelog/library/*.csv`)의 id·이름·화면 종류·주소를 그대로 씀. `host`는 호출 간격을 지키는 단위(`*.dkyobobook.co.kr`은 하나로 묶음). `last_full_success_at` = 이 도서관 목록의 기준 시각. `crawl_skip` = 받지 않는 이유(NULL이면 받음). `vendor` = 사이트 시스템 업체(`KYOBO`·`YES24`·`BOOKCUBE`·`ALADIN`·`PORTAL`·`IN_HOUSE`, CHECK). 화면 종류 대응표와 다를 때만 수집 세션이 적고 넣기 명령 `library-insert`가 읽음(2026-10-12, plan 사이클 13 spec R1-2)
- `crawl_run`: 도서관 한 곳 × 한 번 실행. `started_at`, `finished_at`, `status`(RUNNING·SUCCEEDED·FAILED·MISMATCH), `site_total`(사이트가 말한 건수), `distinct_items`(받은 수), `sections`, `error`(실패 이유 또는 건너뛴 줄 수)
- `holding`: 도서관 × 책 한 줄
  - 키: `(library_id, item_key)`. `item_key` = 교보 `brcd`, YES24 `goods_id`(오디오북은 `AUDIO:` 붙임), 포털 `EBK:`/`ADO:` + `book_idx`, 강남구 `book_num`
  - 상세페이지: `detail_url`(전체 URL, 트리거가 `library.base_url` + `detail_path`로 채움). 모든 줄에 있어야 함(사용자 결정 2026-09-30)
  - 사실 정보: `title`, `author_raw`(원문), `publisher`, `published_on`(날짜로 읽힌 것만), `published_raw`(원문), `supplier`, `category_code`/`category_name`, `cover_url`(주소만), `detail_path`, `branch_name`(포털의 소속도서관)
  - 종류: `content_kind`(사이트 표시 원문), `kind_source`(SITE_LABEL = 줄에 적혀 있음, SITE_SECTION = 어느 목록에서 왔는지로 정함), `section`(MAIN, AUDIO, EBK, ADO)
  - 날짜: `first_seen_at`(처음 본 시각), `last_seen_at`(마지막으로 본 시각), `removed_at`(성공한 전체 수집에서 안 보여 빠졌다고 본 시각. 지우지 않음), `last_run_id`
  - 저장하지 않는 것: 책 소개·저자 소개·목차·서평, 보유·대출·예약 수 (사용자 결정 2026-09-30)
  - 오디오북 표시(트리거가 자동으로 채움): `content_type`(EBOOK/AUDIOBOOK/VIDEO_BOOK/KIDS_BOOK/PAPER/UNKNOWN), `is_audiobook`(1/0/NULL), `kind_check`(TITLE_SAYS_AUDIO). 북큐브는 `content_kind`에 `category_type/format`(예 `book/epub`), `alt_code`에 교보식 `legacyCode`
- 뷰 `holding_typed`(예전 뷰, 칸과 같은 값): `content_type` = EBOOK / AUDIOBOOK / VIDEO_BOOK / KIDS_BOOK / PAPER / UNKNOWN, `kind_check` = 제목에 "오디오북"이 있는데 사이트가 오디오북으로 표시하지 않은 줄
- 뷰 `kind_check_needed`: 위 `kind_check` 줄 목록 (사람이 확인할 것)

오디오북만 보기:

```sql
SELECT l.name, t.title, t.author_raw, t.content_kind, t.kind_source
FROM holding t JOIN library l ON l.id = t.library_id
WHERE t.is_audiobook = 1 AND t.removed_at IS NULL;
```

## 실행

저장소 뿌리에서 실행합니다. 데이터 폴더가 기본값과 다르면 `--data-dir 경로`를 더합니다.

```
python -X utf8 scripts/crawl/crawl_catalog.py status                          # 수집 DB 읽기만 (수집 중에도 됨)
python -X utf8 scripts/crawl/crawl_catalog.py init                            # dev seed에서 도서관 목록 다시 읽기
python -X utf8 scripts/crawl/crawl_catalog.py full --retry-failed --shard 0/4 # 성공하지 않은 곳만, 호스트 4묶음 중 0번
python -X utf8 scripts/crawl/crawl_catalog.py full --only 9                   # 도서관 한 곳
python -X utf8 scripts/crawl/crawl_catalog.py full --sections AUDIO           # YES24 오디오북 목록만
python -X utf8 scripts/crawl/crawl_catalog.py refill-pubdt --only 102084        # 빈 곳 채우기: 출판일순으로 다시 읽어 빠진 책만 더함
```

- `migrate`(표 구조 맞추기)는 따로 부르는 명령이 아닙니다. `status`를 뺀 모든 명령이 DB를 열 때 돌고, 없는 칸만 더합니다(`library.vendor`는 `ALTER TABLE library ADD COLUMN vendor TEXT CHECK (…)`, 이미 있으면 건너뜀). 두 번 돌아도 됩니다(`test_crawl_migrate.py`)
- `refill-pubdt --only ID[,ID…]`: 데이터 폴더 옛 `refill_pubdt.py`를 대신함. 출판일순 목록이 있는 YES24·북큐브·알라딘만. 지우지 않고 더하기만 하고 그 도서관의 마지막 `crawl_run`에 붙음. 로그 시작 `… rows before`, 끝 `refill done, … rows after (+N)`. 받지 않는 곳·한 번도 받지 않은 곳은 `not refilled (…)`로 건너뜀
- **교보 구독형 서버 `*.dkyobobook.co.kr`에는 요청을 보내지 않습니다** [확정] (plan 사이클 13 spec R1-6: 우리 IP를 막았고 교보 대여·혼합은 서비스에서 뺌). 받을 목록에서 빼고(로그 `not crawled: N libraries on *.dkyobobook.co.kr`), 요청 직전에도 다시 걸러 보내지 않음. 그래서 `--include-big-subscription`은 남아 있지만 효과가 없음
- **사이트가 막으면 그 도서관만 받지 않음** [확정] (spec R1-7): 다시 시도를 모두 해도 HTTP 403이거나 시간 초과면 그 도서관 `crawl_skip`에 `blocked {날짜}: HTTP 403 (clear: --skip {id} '')`(또는 `time-out`)을 적고 나머지는 계속 받음. `full`·`refill-pubdt` 모두. 풀 때는 `status --skip {id} ""`
- **robots.txt는 기록만 하고 수집은 함** [확정] (사용자 선택 2026-10-12, spec R1-7). 수집기는 robots.txt를 읽지 않음. 실제로 막히면 위처럼 그 도서관만 받지 않음

- `status`는 수집 DB를 읽기 전용으로 열어 표 구조를 고치지 않습니다(옮길 때 바꿈. 다른 명령은 전처럼 열면서 표 구조를 맞춤)
- dev seed CSV는 이 저장소의 `backend/src/main/resources/db/changelog/library/`에서 읽습니다(옮기기 전에는 절대 경로)
- 호출 예의: 같은 호스트는 한 번에 1건, 1초 이상 간격. 서버가 다르면 병렬(사용자 결정 2026-10-03). 공용 호스팅은 하위 주소가 달라도 한 서버(`host_key`). `--shard`는 호스트를 나누므로 여러 프로세스를 동시에 돌려도 한 호스트에 몰리지 않음
- User-Agent: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) chaekcheck-crawler/0.1 (catalog survey)`. 개인 연락처를 넣지 않음. 연락처는 서비스 주소나 공개 문의 주소가 생기면 넣음 [확정] (spec R1-8, `crawl_catalog.py`의 `USER_AGENT`)
- 이어 받기: 목록이 하나인 도서관은 멈춘 쪽 다음부터 이어 받음. 오디오북이 있는 YES24는 읽던 목록과 쪽부터, 포털은 처음부터([`2-crawl.md`](../../docs/maintenance/2-crawl.md) 4절)
- 성공 조건: 받은 서로 다른 책 수가 사이트 총 건수와 1% 안(건너뛴 줄 제외). 포털은 총 건수가 없어 빈 쪽까지 읽으면 성공

## 범위와 상태 (2026-09-30)

- 순서: ① dev seed 278곳 ② 큰 교보 구독형 16곳 ③ 북큐브 134곳 ④ 웹러브 북스 나머지 기관(아직 도서관 목록에 없음) — 사용자 결정 2026-09-30
- 알라딘은 방법만 조사함(`platforms.md` 8절)
- 2026-09-30 00:53 이전 요청의 User-Agent에는 사용자 이메일이 들어 있었음(수집 첫 실행과 북큐브·알라딘 기관 사이트 확인). 이후 제거함
