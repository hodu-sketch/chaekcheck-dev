# 사이클 23 「책첵 추천」 문서 색인

사이클 23(처음 이름 「나에게 맞는 책」, 지금 「책첵 추천」, 화면 `/picks/for-you`)의 문서를 이 폴더에 모았습니다(2026-10-29). **여기서 시작하세요.** 문서마다 맨 위에 「이 문서가 답하는 것 / 답하지 않는 것」이 있습니다.

## 1. 문서 계층

```
사이클 23 책첵 추천
├─ 지금 (사실)                     ← 코드·DB와 맞아야 함. 바뀌면 여기부터 고침
│   ├─ 1-how-it-works.md           요청 한 번에 브라우저·서버가 무엇을 읽고 계산해 무엇을 내나
│   └─ 2-data.md                   서버가 읽는 표를 PC에서 어떻게 만들고 넣나
├─ 근거 (측정)
│   ├─ 3-evaluation.md             시험 정의·계산과 모든 결과(E1~E7), 믿을 수 있는 범위
│   └─ experiments/                개발 전 시험 보고서(원본 그대로)
│       ├─ 2026-10-24-pretest.md        인기순 받기·KURE-v1 임베딩 사전 시험
│       └─ 2026-10-25-intro-test.md     소개 글 유무·임베딩에 소개 넣기·탭 표지 범위
├─ 변화 (이력)
│   ├─ 4-history.md                부분별 판(O1·O2, N1~N4, W0~W2, B0~B3, C1·C2, F1·F2, S1~S4, K1·K2)과 바로잡은 설명
│   └─ archive/2026-10-24-session-handoff.md   클라우드 → 로컬 세션 인계(그때 그대로)
├─ 앞으로 (계획)
│   ├─ 5-improvements.md           개선안 I-01~I-18: 상태·근거·바꿀 곳·잴 것·먼저 할 것
│   └─ 6-ml-method.md              I-05·I-06·I-10~I-14의 자료·모델·시험 설계와 데이터 점검 숫자
└─ 보여 주기
    ├─ 7-portfolio.md              한 장 요약(숫자는 3에서)
    ├─ 8-methods-explained.md      원리·수학·코드·라이브러리 해설(학습용, 남에게 설명하기)
    ├─ portfolio-page.html         공개용 페이지 원본(https://claude.ai/artifact/HEoeTqicrZSPMh3qyVYmfp)
    └─ figures/                    그림 6장(scripts/foryou/eval/make_charts.py)
```

## 2. 이 폴더 밖의 관련 문서

| 문서 | 역할 | 이 폴더와의 관계 |
|---|---|---|
| [`docs/maintenance/7-foryou.md`](../../maintenance/7-foryou.md) | 운영 절차: 새벽 만들기(켤 때), 넣기 명령의 모든 선택지, 볼 곳 | 2-data.md 6절이 요약해 가리킴 |
| [`docs/cycles/plan-feedback.md`](../../cycles/plan-feedback.md) 「# 사이클 23」 「# 사이클 23 (이어서, 2026-10-26~27)」 「# 머신러닝 보강 (사이클 23 이어서)」 | plan에 넘기는 **날짜 순 기록**: 사용자 발언, 결정, 그날의 숫자 | 정리된 내용은 이 폴더. 날짜 순 원기록은 거기 |
| [`scripts/foryou/eval/README.md`](../../../scripts/foryou/eval/README.md) | 측정 도구 목록 | 3-evaluation.md의 숫자를 만든 스크립트 |
| `scripts/crawl/foryou_neighbours.py`, `cover_scope.py`, `foryou_blurbs.py` 머리말 | 자료 만드는 도구의 사용법 | 2-data.md가 그 계산을 풀어 씀 |
| [`CHANGELOG.md`](../../../CHANGELOG.md) | 버전별 사용자에게 보이는 변화 | 4-history.md가 부분별로 다시 묶음 |
| plan `docs/cycles/23-for-you-picks/`(`handoff.md`, `design-delta.md`, `spec.md`) | 설계 원본(plan 저장소, 여기서 고치지 않음) | 설계와 다르게 한 것은 4-history.md·plan-feedback |
| plan `docs/cycles/26-book-descriptions/to-dev23-24.md` | 사이클 26이 책첵 추천 표·API를 `catalog`로 옮기는 내용 | 1-how-it-works.md 11절, 5-improvements.md 3절 |
| plan `docs/research/ml-enrichment/`(브랜치 `claude/server-status-library-crawl-test-uv7s8b`) | plan이 맡긴 머신러닝 보강 요청 | 6-ml-method.md |
| `<data>/ml/foryou-100k/`, `<data>/ml/foryou/eval-*/` (git 밖) | 운영 이웃 표 원본, 측정 결과 원본 | 2-data.md, 3-evaluation.md |

## 3. 질문 → 문서

| 이런 게 궁금하면 | 여기 |
|---|---|
| 탭을 열면 서버에 무엇이 가나, 서버는 무엇을 돌려주나 | 1-how-it-works.md 2~6절 |
| 기준 책은 어떻게 고르나, 점수는 | 1-how-it-works.md 1절 |
| 개인 추천 순서는 정확히 어떻게 정해지나 | 1-how-it-works.md 4절, 8절 |
| 분야 칩은 왜 사람마다 다르고 「마음·자기계발」은 왜 안 보이나 | 1-how-it-works.md 7·9절 |
| 이웃 20권은 어떻게 계산하나, 같은 작품은 어떻게 빼나 | 2-data.md 2·3절 |
| 요약·표지는 누가 어떻게 만들었고 몇 권이 없나 | 2-data.md 4·5절 |
| 운영에 어떻게 넣나 | 2-data.md 6절 → 7-foryou.md 2절 |
| 유사도 순이 왜 더 낫나, 숫자는 | 3-evaluation.md E1·E2 |
| 「다 대출 중」은 어떻게 막나 | 3-evaluation.md E6·E7, 5-improvements.md I-01 |
| 언제 무엇이 바뀌었나, 지금은 어느 판인가 | 4-history.md 1절 |
| 앞으로 할 것과 그 상태 | 5-improvements.md 1절 |
| 머신러닝은 어떻게 쓸 건가 | 6-ml-method.md |
| 코사인·nDCG·로지스틱 회귀·HDBSCAN 같은 원리와 수식, 실제 코드, 남에게 설명하는 말 | 8-methods-explained.md |
| AI 검색 쪽의 지금 상태 | [`docs/features/aisearch/1-how-it-works.md`](../aisearch/1-how-it-works.md), 색인 [`docs/features/aisearch/README.md`](../aisearch/README.md) (이 폴더 밖, 사이클 24) |

## 4. 처음 읽는 차례
처음이면 7-portfolio.md(한 장) → 1-how-it-works.md 0절(그림) → 4-history.md 1절(지금 판) → 5-improvements.md 1절(할 일).

## 5. 말 뜻 (이 폴더에서 같은 뜻으로 씀)

| 말 | 뜻 |
|---|---|
| 본 책 기록 | 브라우저 `localStorage` `chaekcheck.viewedBooks.v1`의 책 목록과 점수 |
| 기준 책 | 본 책 기록에서 고른 최대 6권. 서버에 번호로 보냄 |
| 계획 도서관 | 사용자의 범위·지역·직접 더한 도서관으로 서버가 정한 도서관 집합(1-how-it-works.md 2절). 「내 도서관」과 같은 뜻 |
| 이웃 | 한 책과 KURE-v1 벡터 코사인이 큰 책 20권(`foryou.book_neighbour.neighbour_ids`) |
| 같은 작품 | 다른 판·권·번역판·연도판. 이웃에서는 PC 규칙(2-data.md 3-1), 서버에서는 `BookCards.workKeys` |
| 개인 추천 | `/similar-books`가 주는 이웃 기반 목록(최대 60) |
| 기본 목록 | `/books`가 주는 추천목록 책 목록 |
| 분야 묶음 | `literature, humanities, society, science, arts, other` 6개(`picks.FieldGroups`) |
| 바로 대출 | 그 책을 가진 계획 도서관 중 `lending_model IN ('SUBSCRIPTION','RENTAL')`인 곳이 있음 |
| 문턱 3 | 계획 도서관 3곳 이상이 갖거나 바로 대출이면 통과(v1.20.2부터 운영) |
| 판정 | 방식을 가린 Haiku 하위 에이전트의 1~5점(3-evaluation.md 1-4) |
| 판(O2, N4 …) | 4-history.md에서 부분마다 붙인 이름 |
