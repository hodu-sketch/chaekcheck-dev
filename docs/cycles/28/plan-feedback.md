# 사이클 28 자리가 다 차도 책 목록은 보여 주기 (번외, 화면만)

plan에 넘기는 dev 기록입니다(사이클별 규칙, 2026-11-05). 기준 문서는 plan `docs/cycles/28-busy-keeps-book-list/handoff.md`(design-delta, spec AC-1\~AC-7, flow, 결정 페이지 `easy/2026-11-01-decisions.html`), ADR-0173.

## 시작 상태 (2026-11-07)

- worktree: `.claude/worktrees/cycle-28-busy-keeps-book-list-744bc0`, 브랜치 `claude/cycle-28-busy-keeps-book-list-744bc0`
- 시작 커밋: `69e7a19d` (main 끝, "docs: clean up remaining link text and quoted punctuation")
- 판: web `1.23.1`, backend `1.23.1` (인계서와 같음)
- 인계서 "plan이 다시 확인한 dev 위치"의 줄 번호: 모두 시작 커밋과 같았음(`InstantResults.tsx` 131\~141, `BookList.tsx` 107\~117, `BookPage.tsx` 208\~232, `ResultList.tsx` 184\~188, `ResultList.module.css` 220, `useHoldings.ts` 72\~74·87\~89, `holdingPlan.ts` 54\~68, `SearchPage.tsx` 473\~476·642·648, `application.yml` 257)
- 포트: web 5173(`web` 항목), backend 8080. 시험용 백엔드는 `.claude/launch.json`에 `c28-backend-8080` 항목(자리 1, 요약 로그 15초, 로그인 끔)을 **커밋하지 않고** 두었다가 일이 끝나면 지움(CLAUDE.md 규칙)
- worktree에는 `.env`가 없어 백엔드(`spring.config.import: ../.env`)가 테스트 DB에 붙지 못함 → 메인 체크아웃의 `.env`를 복사(git 무시). 다른 worktree 세션도 같은 일을 겪으니 알아 둘 것

## 정한 것 ([안]을 코드에 맞춰 정함)

| 인계서 [안] | 정한 것 |
|---|---|
| 책 줄 조각 이름 | `web/src/features/search/BookRow.tsx`, `BookRow({ book, bookLinkSearch })`. CSS는 `BookList.module.css` 그대로 import, 옮기지 않음 |
| 안내 줄 + 단추 조각 | `web/src/features/search/BusyRetryNotice.tsx`, `BusyRetryNotice({ text, checking, onRetry })`, `role="alert"`인 `<div>`. 문구 상수 `BUSY_KEEP_LIST_TEXT`, `BUSY_BOOK_TEXT`, `BUSY_CHECKING_TEXT`, 단추 이름 `BUSY_RETRY_LABEL`을 같은 파일에 둠 |
| 단추 스타일 | `ResultList.module.css`에 `.busyRetry`(안내 줄을 flex로, 글은 접히고 단추는 안 접힘)와 `.retryButton`(결정 페이지 그림과 같은 작은 알약: 브랜드 테두리, 흰 속, 작은 글씨; `moreButton`의 색을 따름)을 더함. 640px 이하에서 `.busyRetry`는 세로로 바뀌어 단추가 안내 줄 아래로 감(결정 페이지 폰 그림) |
| `openRefused`·`reopen` 자리 | reducer가 아니라 **hook 상태**(`useHoldings.ts`의 `useState` + `lastOpen` ref). reducer(`holdingPlan.ts`)와 그 시험은 그대로. `openRefused`는 `open`이 503으로 실패하면 켜지고, 다음 스트림의 `targets`가 오거나, 다른 이유로 실패하거나, `startBatch`(「결과 더 보기」)가 시작하거나, `reset`이면 꺼짐. **다시 확인 중에도 켜져 있음**(그래야 `batch-start`가 `blocked`를 지운 뒤에도 책 줄이 남음) |
| `ResultList` prop 이름 | `onRetryBlocked?: () => void`, `retrying?: boolean`. `onRetryBlocked`가 있고 (`blocked === 'busy'` 또는 `retrying`)이면 `BusyRetryNotice`(문구 `BLOCKED_TEXT.busy`), 아니면 지금의 `<p className="blocked">` |
| "책 목록만" 상태 | `InstantResults`: `openRefused && order.length === 0 && (다시 확인 중 || (noHoldings && blocked === 'busy'))`. 이 상태에서는 정렬·거르기 도구줄과 「다음 책 N권 보기」를 **그리지 않음**(결정 페이지 그림에도 없음) |
| 책 화면 | `BookPage`: 같은 조건(`busyOnly`)이면 `ResultList` 대신 `BusyRetryNotice(BUSY_BOOK_TEXT)`만. 책 머리·조건 카드 그대로 |
| 시험 이름 | `blocked.test.tsx`에 describe `busy first stream keeps the book list (cycle 28)` 안에 (a)\~(f), 바뀐 기존 시험 이름은 `바로 보기: a refused first holding stream keeps the book list under the notice and its button (cycle 28 AC-1)`. `BookPage.test.tsx`에 describe `busy first stream (cycle 28)` |

## 설계와 달라진 점

- 「다음 책 N권 보기」 뒤 503에서 단추를 눌러 다시 확인하는 동안: 인계서는 "지금 동작 + 단추만"이라고만 했고, `batch-start`가 `blocked`를 지우므로 그대로 두면 안내 줄이 통째로 사라졌다가 503이면 다시 나타남. 인계서 4단계 2의 `retrying` prop 뜻대로 **다시 확인 중에도 안내 줄을 남기고 "대출 정보 확인 중…" + 잠긴 단추**로 보이게 함(`ResultList` 조건에 `|| retrying`). 그 아래 목록 끝에는 지금처럼 "도서관 0곳 조회 중" 스피너도 함께 보임(바꾸지 않음)
- 그 밖에 인계서와 다른 것은 없음. 문구·단추 이름은 [확정] 그대로

## 새로 알게 된 제약

- **같은 책을 다시 열 때 빈 묶음**: `reopen` → `open`이 `batch-start`를 보내므로 거절된 묶음(rows 0, status done)이 `batches`에 남고, 다시 확인마다 하나씩 늘어남. `holdingBatchViews`는 묶음마다 view를 만들지만 `Groups`(grouped 모드)는 rows만 모으므로 화면에 빈 묶음은 보이지 않음(시험 (c)에서 확인). 한 가지 작은 영향: `Groups`의 `firstDone = batches[0].status === 'done'`이 거절된 첫 묶음 때문에 다시 확인 **스트림 중에** 이미 참이 되어, 지역·도서관 조건이 있는 검색(`hasMine`)이면 결과가 오기 전 잠깐 "내 도서관에는 없어요 (0)"가 보일 수 있음. 자리 1개 손 확인(전국 검색)에서는 해당 없음. 고치려면 거절된 묶음을 `batches`에서 빼는 reducer 변경이 필요해 이번 범위 밖으로 둠
- **「다음 책 N권 보기」 거절 뒤 아래 단추가 페이지를 건너뜀**(인계서 4단계 2 "알아 둘 점"): 코드로 확인함. `loadMore`가 `setBooks({ …, last: page })`를 스트림을 열기 **전에** 하므로 거절돼도 `books.last`는 거절된 페이지이고, 아래 단추는 `holdings.remaining`이 0이라 「다음 책 N권 보기」로 보이며 누르면 `books.last.page + 1`을 받아 옴. 즉 거절된 페이지 책들은 「대출 정보 다시 확인」으로만 조회할 수 있고, 아래 단추로는 건너뛰어짐. 바꾸지 않음(인계서)
- **추천도서 책 창**(`PicksBookDialog.tsx`): `useHoldings`의 반환에 `reopen`·`openRefused`가 더해졌을 뿐 쓰는 곳이 없어 동작 변화 없음. 222\~226행 문구 "대출 정보를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요."는 그대로(`state.batchFailed` 기준이라 이번 hook 변경과 무관). 코드로 확인
- `vitest` 전체(720개)를 첫 번 돌릴 때 1개가 실패했는데 어느 시험인지 잡히기 전에 끝났고, 이어서 세 번은 모두 통과함. 첫 실행(node_modules 새로 설치 직후, jsdom 79회 생성) 시간 초과로 보임. 이번 변경과 무관할 가능성이 크지만 적어 둠

## AC 확인표

| AC | 결과 | 확인 방법 |
|---|---|---|
| AC-1 | **통과** | 브라우저: MAXCONCURRENTSEARCHES=1, curl로 슬롯 점유 후 "사랑의 기술" 검색 → 안내 줄("지금 찾는 사람이 많아 대출 정보는 알 수 없어요. 책 목록만 먼저 보여 드려요.") + 「대출 정보 다시 확인」 단추 + 책 줄 4개(제목/저자/출판사/연도/보유 도서관 수). 정렬·거르기 도구줄과 「다음 책 N권 보기」 없음 |
| AC-2 | **통과** | 브라우저: 슬롯 풀린 뒤 「대출 정보 다시 확인」 클릭 → "지금 빌릴 수 있는 책만/전체" 탭, "기본 순서" 정렬, 광주남구문화정보도서관 대여 정보(보유 1, 대출 중 0, 예약 0, 「대여하기」 단추) 정상 표시 |
| AC-3 | **통과** | 브라우저(fetch mock 503): busy 상태 페이지에서 fetch를 가로채 503 반환하고 「대출 정보 다시 확인」 클릭 → 같은 화면 유지(안내 줄 + 단추 활성화 + 책 줄 10개). 단위 시험 `blocked.test.tsx` (a)\~(f)에서도 검증 |
| AC-4 | **통과** | 브라우저: 슬롯 점유 중 책 페이지(`/books/1236?visit=none`) 이동 → 책 머리(표지·제목·저자·출판사·연도·보유 도서관)·AI 요약 표시, 안내 줄("지금 찾는 사람이 많아 대출 정보는 알 수 없어요.") + 「대출 정보 다시 확인」 단추. "모두 조회했습니다" 숨겨짐. `BookPage.test.tsx` describe `busy first stream (cycle 28)`에서도 검증 |
| AC-5 | **통과** | 코드 확인: 직접 검색(`SearchPage`)과 「결과 더 보기」(`loadMore`) 경로에 변경 없음. AC-2에서 정상 대출 결과 표시 확인 |
| AC-6 | **통과** | 백엔드 로그: WARN `search refused: all 1 search slots are taken` 기록, 15초 ops summary에 `refused=1` 카운트, 다음 구간 `refused=0` 리셋 확인 |
| AC-7 | **통과** | `git diff --stat main...HEAD -- backend/` 비어 있음. `./gradlew build` BUILD SUCCESSFUL(5m 16s, 2026-11-07) |

## ADR-0170

해당 없음: Liquibase changelog·DB 변경 없음(`backend/` 변경 0, `git diff --stat main...HEAD -- backend/` 비어 있음). API도 그대로라 블루·그린 전환 중 옛 판·새 판이 섞여도 문제 없음.

## 사용자 실제 폰 확인

- 기기: Samsung Galaxy (Samsung Internet), `http://192.168.45.24:5174`
- 워크트리 웹(c28-web-lan-5174, 포트 5174 --host) + 메인 백엔드(backend-no-login, 포트 8080)
- 결과: **통과** (2026-11-08). 검색 페이지 레이아웃·반응형 정상, AI/직접 탭 정상 표시, 사이클 28 변경(busy 안내 줄·단추·책 줄)은 슬롯 기본값이라 busy 상태 재현 불가하여 레이아웃 위주 확인

## 배포

TODO_DEPLOY
