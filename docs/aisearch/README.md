# AI 책 찾기 문서 색인 (사이클 24)

AI 책 찾기(주소 `/ai`, API `POST /api/v1/ai-searches/stream`)는 사이클 24에서 만들고 v1.10.0(2026-10-25)부터 v1.19.0(2026-10-28)까지 고쳐 온 기능입니다. 이 폴더는 그 문서를 한곳에 모은 것이고, 이 파일은 **어느 문서가 무엇을 맡는지**를 층으로 나눈 색인입니다. 2026-10-29에 `docs/reports/ai-search-*.md`와 `docs/cycle24-session-handoff.md`를 여기로 옮겼습니다.

## 1. 처음 읽는 순서

1. [1-how-it-works.md](1-how-it-works.md): 지금 질문 하나가 어떤 표·칸·파일을 어떤 계산으로 다뤄 어떤 결과(SSE 이벤트)를 내는지
2. [2-versions.md](2-versions.md): 버전마다 무엇을 왜 바꿨고 숫자가 어떻게 바뀌었는지, 헷갈리기 쉬운 것
3. [4-portfolio.md](4-portfolio.md): 잰 결과를 문제 → 원인 → 고친 방법 → 결과로 모은 것
4. 궁금한 실험이 있으면 아래 4절의 날짜별 보고서

## 2. 층으로 나눈 문서

| 층 | 문서 | 맡는 것 | 맡지 않는 것 |
|---|---|---|---|
| **지금 (정본)** | [1-how-it-works.md](1-how-it-works.md) | 운영 v1.19.0의 동작 전부: 요청 검사, 계획 도서관, 데이터 위치(표·칸·파일 형식), 단계마다의 계산(SQL, 벡터 내적, MLP, RRF), 2차 Haiku 요청 모양, SSE 이벤트, 웹 표시, 실패 길, 로그, 설정 값 | 왜 그렇게 됐는지(→ 2-versions), 서버에서 돌리는 명령(→ 운영) |
| 지금 · 운영 | [../maintenance/6-aisearch.md](../maintenance/6-aisearch.md) | 서버에서 하는 일: `book-texts` 넣기 명령, 모델 폴더 올리기, 임베딩 서버, 확인할 로그, 되돌리기 | 동작 설명(→ 1-how-it-works) |
| 지금 · 설정 | [../maintenance/settings-index.md](../maintenance/settings-index.md) 1-1절 | AI 검색 설정·코드 상수의 이름, 기본값, 운영 값, 근거 | — |
| **변화** | [2-versions.md](2-versions.md) | v1.10.0~v1.19.0과 데이터 넣기(3만 권, 10만 권)마다 바꾼 것·이유·전후 숫자·그 뒤, 재 보고 내지 않은 것 | 실험 방법의 자세한 내용(→ 보고서) |
| **결과·설명** | [4-portfolio.md](4-portfolio.md) | 모든 전후 비교 숫자를 한곳에(포트폴리오용) | 동작 설명(→ 1-how-it-works) |
| 결과·설명 | [5-ml-study.md](5-ml-study.md) | ML을 남에게 설명할 수 있게 처음부터: 벡터·코사인, Transformer 임베딩, int8 양자화, 로지스틱 회귀, MLP, softmax·sigmoid, 교차 엔트로피, 역전파·AdamW, 드롭아웃, F1·문턱, TF-IDF, RRF를 수식과 **실제 코드**로, 라이브러리 표, 설명용 답 | 점수 표 전부(→ 3-ml-models) |
| 결과·설명 | [3-ml-models.md](3-ml-models.md) | 1차 단계의 ML: KURE-v1 임베딩과 MLP 헤드 13개, 학습 데이터, 문턱, 기준선, 클래스 불균형 | 운영에서 그 출력을 쓰는 곳(→ 1-how-it-works 4-3·4-9) |
| **근거 (날짜별 실험)** | [reports/](reports/) | 그날 무엇을 왜 어떻게 쟀고 결과가 무엇이었는지. 그때 기록이라 지금과 다른 곳은 각 문서 맨 위 상자에 적음 | 지금 동작(→ 1-how-it-works) |
| **원본 숫자** | [../reports/ai-search-eval/](../reports/ai-search-eval/) | 보고서 표의 원본 파일(JSON·표·그림). 스크립트가 이 경로를 읽어 옮기지 않음 | — |

## 3. 이런 질문은 이 문서

| 궁금한 것 | 볼 곳 |
|---|---|
| 질문이 1,024개 숫자가 되는 과정, 어디서 도나 | [1-how-it-works 4-2](1-how-it-works.md#4-2-질문-벡터-encode), 운영은 [6-aisearch 7-1](../maintenance/6-aisearch.md) |
| ML 원리를 공부하고 남에게 설명하고 싶다 | [5-ml-study](5-ml-study.md) |
| 「책 찾기 아님」·「제목만」 같은 길은 누가 어떻게 정하나 | [1-how-it-works 4-3](1-how-it-works.md#4-3-ml-1차-ml), 모델 학습은 [3-ml-models](3-ml-models.md) |
| 낱말 검색 SQL과 점수 | [1-how-it-works 4-7](1-how-it-works.md#4-7-낱말-후보-keyword) |
| 벡터 검색과 낱말 검색을 한 목록으로 만드는 식(RRF) | [1-how-it-works 4-8·4-9](1-how-it-works.md#4-9-합치기-mix) |
| Haiku에 정확히 무엇을 보내나 | [1-how-it-works 4-11](1-how-it-works.md#4-11-2차-호출-llm-first-llm-done) |
| 화면에 보이는 고정 문구는 어디서 | [1-how-it-works 4-12·5](1-how-it-works.md#5-웹-화면) |
| 되묻기 v1·v2·「짧은 질문에만」의 차이 | [2-versions 「헷갈리기 쉬운 것」](2-versions.md#헷갈리기-쉬운-것) |
| 3만 권과 10만 권은 무엇이 다르나 | [2-versions](2-versions.md#헷갈리기-쉬운-것), [6-aisearch 6·9절](../maintenance/6-aisearch.md) |
| 첫 글이 빨라진 두 번(생각 끄기, 겹쳐 돌리기) | [2-versions](2-versions.md#헷갈리기-쉬운-것), [reports/2026-10-27-latency](reports/2026-10-27-latency.md) |
| 해 봤지만 넣지 않은 것(에이전트 등) | [2-versions 「재 보고 내지 않은 것」](2-versions.md#재-보고-내지-않은-것) |
| 운영에서 무엇을 지켜보나(로그) | [1-how-it-works 7](1-how-it-works.md#7-로그-한-줄), [6-aisearch 4절](../maintenance/6-aisearch.md) |
| 이어 묻기의 「최신」·「아까」·「다 같이」·「A나 B」는 어떻게 알아보나, 왜 정규식만·LLM이 아닌가 | [1-how-it-works 4-0-2](1-how-it-works.md#4-0-2-이어지는-질문의-조건과-주제-v1221부터), [reports/2026-10-29-follow-ups](reports/2026-10-29-follow-ups.md) |
| 다음에 고칠 것 | [reports/2026-10-28-talk-review](reports/2026-10-28-talk-review.md) |

## 4. 날짜별 보고서 (`reports/`)

| 날짜 | 문서 | 무엇을 쟀나 | 이어진 버전 |
|---|---|---|---|
| 2026-10-24 | [세션 넘김 기록](reports/2026-10-24-session-handoff.md) | 그날까지 만든 것과 남은 것(옛 기록) | v1.10.0 |
| 2026-10-25 | [첫 실험](reports/2026-10-25-eval.md) | 질문 57개로 후보 방식(낱말 수, 이웃, 질문 벡터 섞기)과 2차 지시문을 비교하는 방법과 결과 | v1.15.0 |
| 2026-10-25 | [개선 보고서](reports/2026-10-25-improvement.md) | 질문 50개로 단계별(1 → 1+2 → 1+3 → 1+2+3) 숫자, 더 해 본 안, 책 쪽 데이터, 분류 사전 비교 | v1.15.0, v1.16.0 |
| 2026-10-25 | [임베딩 시험](reports/2026-10-25-embedding.md) | KURE-v1을 2.2GB → 385MB로 줄여 서버에 올리기 | v1.15.0 |
| 2026-10-25 | [1차 호출 모델](reports/2026-10-25-first-call-models.md) | 1차 Haiku 호출을 TF-IDF·KURE+분류기·Cross-Encoder로 바꿀 수 있나 | v1.16.0 |
| 2026-10-26 | [넣은 것과 뺀 것](reports/2026-10-26-ml-first-call-decisions.md) | 1차를 ML로 바꾸며 시도한 것마다 전후 숫자와 결정, 관계 분류기, 합치기 | v1.16.0~v1.16.3 |
| 2026-10-26 | [에이전트](reports/2026-10-26-agent.md) | 2차를 도구 루프 에이전트로 바꾸는 안(넣지 않음)과 되묻기·생각 끄기·캐시 | v1.17.0 |
| 2026-10-27 | [규칙 점검](reports/2026-10-27-rules.md) | 2차 규칙 29개를 한 표에, 80문항 규칙 시험, 되묻기 v2 | v1.18.0 |
| 2026-10-27 | [첫 글 시간](reports/2026-10-27-latency.md) | 단계별 시간, Haiku 첫 토큰을 정하는 것, 겹쳐 돌리기, 운영 전후 | v1.19.0 |
| 2026-10-28 | [대화 점검과 다음 계획](reports/2026-10-28-talk-review.md) | 대화 37개(57턴)로 찾은 어색한 곳과 고칠 계획 | (사이클 26 뒤) |
| 2026-10-29 | [대화를 가리키는 말 고치기](reports/2026-10-29-talk-refs.md) | 「모르겠어」·「그거 말고」·「아까 이야기한 거」를 알아보는 규칙과 대화 27개 전후(통과 23 → 40 / 40) | v1.20.5 |
| 2026-10-29 | [대화 점검 고치기](reports/2026-10-29-talk-review-fix.md) | 상황별 정리, 대화 기억 10, 책 밖 말에 모델 답, 한 낱말은 바로 답, 「기타」, 제목 질문, 1차 신경망 다시 학습(v2). 대화 점검 57턴: 0권 5 → 1, 고정 되묻기 6 → 0 | 배포 전 |
| 2026-10-29 | [이어지는 질문](reports/2026-10-29-follow-ups.md) | 운영 대화(「최신책」 → 「아까 세계역사나」 → 「다 같이」)의 원인 5가지, 정규식·벡터·섞기를 불친절한 시험 문장 230개로 비교(다 같이 51/54/**55**·최신 55/57/**63**·아까 4/26/**35**·A나 B 47/30/**57**), 섞기를 넣은 것, 같은 대화 로컬 재연, 플로이드-워셜 「목록에 없어서」 | 배포 전(v1.22.1) |
| 2026-10-29 | [HNSW와 전수 계산](reports/2026-10-29-hnsw.md) | 질문 벡터의 가까운 책 찾기: 지금 Java 전수 계산(81,078권, 운영 97ms)과 pgvector HNSW의 시간·재현율·크기·메모리를 로컬에서 잼. 결론: 지금은 전수 유지, 15만~20만 권부터 다시 봄(추정) | 조사만 |

## 5. 원본 숫자 파일 (`docs/reports/ai-search-eval/`)

| 파일 | 쓰는 보고서 |
|---|---|
| `candidates-metrics.json`, `cards-run1.json`, `cards-run2.json`, `cards-prompt-v2.json`, `cards-similar-book.json`, `pick-prompt-v2.txt`, `1-…4-*.svg` | [2026-10-25-eval](reports/2026-10-25-eval.md) |
| `more-variants.json`, `facet-scores.json`, `dict-compare.txt`, `taxonomy.json`, `5-…7-*.svg` | [2026-10-25-improvement](reports/2026-10-25-improvement.md) |
| `taxonomy2.json`(상세 사전, `scripts/aisearch/build_100k.py`가 읽음) | improvement, first-call-models, [3-ml-models](3-ml-models.md) |
| `first-call/` | [2026-10-25-first-call-models](reports/2026-10-25-first-call-models.md), [2026-10-26-ml-first-call-decisions](reports/2026-10-26-ml-first-call-decisions.md) |
| `agent-2026-10-26-table.md`, `ask-back-final-2026-10-26.md`, `ask-back-*.jpg` | [2026-10-26-agent](reports/2026-10-26-agent.md) |
| `rules-2026-10-27-table.md` | [2026-10-27-rules](reports/2026-10-27-rules.md) |
| `latency-*` | [2026-10-27-latency](reports/2026-10-27-latency.md) |
| `search-book-*-2026-10-27.json` | [6-aisearch 11절](../maintenance/6-aisearch.md) (표 줄이기, v1.18.0) |
| `talk-probe-2026-10-28.*` | [2026-10-28-talk-review](reports/2026-10-28-talk-review.md) |
| `candidate-join-2026-10-28.json` | [plan-feedback 「# 사이클 26 질의 속도」](../plan-feedback.md) |
| `intent-2026-10-29/` (시험 문장 `intent-sets.json`, 채점 `intent-compare.json`, 로컬 재연 `follow-up-mixed*.json`, 운영 로그 줄) | [2026-10-29-follow-ups](reports/2026-10-29-follow-ups.md) |

## 6. 이 폴더 밖

- 스크립트: [`scripts/aisearch/`](../../scripts/aisearch/)(책 데이터·벡터 만들기), [`scripts/aisearch/eval/`](../../scripts/aisearch/eval/)(실험·평가, README 있음)
- git 밖 데이터: `chaekcheck-data/ml/`(모델 폴더, `README.md` 색인: 운영은 `aisearch-model-100k`), `chaekcheck-dev/data/aisearch-eval-2026-10-25/`(실험 데이터, README 있음)
- 바뀐 점 목록: [`CHANGELOG.md`](../../CHANGELOG.md), plan에 넘긴 기록: [`plan-feedback.md`](../plan-feedback.md)의 사이클 24·AI 검색 절
- 2026-10-29에 옮기면서 옛 경로(`docs/reports/ai-search-*.md`, `docs/cycle24-session-handoff.md`)에 남겼던 안내 파일은 사이클 26 배포 1(v1.20.0)로 코드 주석이 새 경로로 바뀐 뒤 지웠습니다.
