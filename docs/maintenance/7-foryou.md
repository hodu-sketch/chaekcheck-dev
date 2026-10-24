# 「나에게 맞는 책」 새벽 만들기와 넣기 (사이클 23)

- 기준: plan `docs/cycles/23-for-you-picks/handoff.md`, `design-delta.md`(ADR-0147·0148·0149). 설정 값은 [settings-index.md](settings-index.md) "사이클 23" 줄.
- 모듈 `foryou`(`backend/src/main/java/com/chaekcheck/foryou/`), 스키마 `foryou`, 꼬리표·사전은 `picks.tag_dictionary`·`picks.book_tag`.

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
4. B: `build_run.note`의 사이트마다 `rows`·`matched`, 로그의 `ranked_list_failed`, 걸린 시간(`started_at`~`finished_at`)을 plan-feedback `# 사이클 23`에 적습니다.
5. C: 로그의 `foryou base position=…` 40줄로 표(책, 출판 연도, 점수, 재료별 점수, ①인지 = `popular=true`이면서 추천목록)를 만들어 사용자에게 보입니다. 추천목록 책은 사이클 24가 main에 들어간 뒤에야 들어옵니다(그 전에는 인기 출처만).

## 2. 넣기 (수동, 수집 PC 결과)

- 사이클 24가 main에 들어간 뒤 `collection` 넣기 모드로 붙입니다(`neighbours`·`tag-dictionary` 새 모드, `book-texts`에 kind `for_you_blurb`·`tags`·`cover_file`). 그 전에는 `foryou.ForYouTransfer`·`picks.BookTagTransfer`만 있습니다.
- 파일 모양 [안] (design-delta "넣는 명령")
  - `neighbours.tsv`: 첫 줄 `# export_id=<서비스 내보내기 번호>`, 줄마다 `book_id<TAB>이웃1,이웃2,…`(가까운 차례, 1~20개, 자기 자신·중복 없음, 번호 ≤ 2,147,483,647). `full`은 새 표에 COPY 뒤 바꿔 끼우기, `add`는 upsert
  - `tag-dictionary.tsv`: `code<TAB>axis<TAB>name<TAB>thema_code`. 파일에 없는 코드는 `active=false`
  - `book-texts.tsv`: `kind<TAB>book_id<TAB>source<TAB>made_on<TAB>text`. `for_you_blurb`(1~300자), `tags`(코드 쉼표 목록, 그 책의 꼬리표를 통째로 바꿈), `cover_file`(sha1 40자, 파일은 운영 `/srv/chaekcheck/covers/<sha1>.jpg`, nginx `^/covers/[0-9a-f]{40}\.jpg$`가 이미 보냄)
- 크기·시간(2026-10-24, 클라우드 시험 DB, 가짜 이웃 1,340,875줄 × 20): COPY 약 4.6초, 표 219MB. 운영에서는 바꿔 끼우는 동안 잠깐 두 벌(약 0.44GB)
