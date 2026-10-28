# 보고서 목록

## AI 책 찾기 (2026-10-25~27)

- **[AI 책 찾기 개선 기록 모음 (포트폴리오용)](ai-search-portfolio.md)** — 모든 전후 비교 숫자를 한곳에, 지금 운영 구조도 여기. 여기서 시작
- [AI 책 찾기를 어떻게 개선했나: 단계별 숫자와 버린 안](ai-search-improvement-2026-10-25.md) — 단계별(1 → 1+2+3) 숫자, 더 해 본 9안, 책 쪽 데이터, 분류 사전 비교
- [AI 책 찾기 강화 실험: 무엇이 얼마나 나아지나](ai-search-eval-2026-10-25.md) — 재는 방법과 지표 뜻, 섞기·「○○ 같은 책」·지시문·흔들림
- [1차 AI 호출을 작은 모델로 바꿀 수 있나](ai-search-first-call-models-2026-10-25.md) — TF-IDF · KURE+분류기 · Cross-Encoder · Haiku, 질문 3,000개 — v1.16.0부터 운영 1차는 Haiku가 아니라 질문 벡터 위 작은 신경망(5갈래 + 칸 11개)
- [1차 AI 호출을 ML로: 넣은 것과 뺀 것](ai-search-ml-first-call-decisions-2026-10-26.md) — 시도마다 전 → 후 숫자와 결정(넣음/뺌), 길 나누기·이어 묻는 질문
- [AI 검색 질문 임베딩 시험](ai-search-embedding-2026-10-25.md) — 임베딩 모델 2.2GB → 385MB, 서버 메모리·시간
- [2차를 에이전트로: 만들고 재 보니](ai-search-agent-2026-10-26.md) — 도구(다시 찾기·되묻기)를 쓰는 Haiku vs 한 번의 호출, 질문 72개 두 번씩, 기준선이 바뀌어 다시 잰 이야기 (켜지 않음)
- [2차 단계 규칙 점검](ai-search-rules-2026-10-27.md) — 지시문·코드 규칙 29개를 한 표에, 80문항 규칙 시험, 되묻기 v2(v1.18.0)
- [첫 글 시간 줄이기](ai-search-latency-2026-10-27.md) — 단계별 시간, Haiku 첫 토큰을 정하는 것, 낱말 검색·표지 읽기를 겹치고 되묻기 칸을 짧은 질문에만(v1.19.0, 운영 전후 포함)
- [ML 모델 설명](ai-search-ml-models.md) — 분류에 쓴 KURE-v1 임베딩 + MLP 헤드 13개를 처음부터(문턱, 학습 데이터, 기준선, 클래스 불균형)
- [대화 점검과 다음 계획](ai-search-talk-review-2026-10-28.md) — 대화 37개(57턴)로 찾은 어색한 곳과 고칠 계획(사이클 26 뒤)
- 그림·결과 파일: [`ai-search-eval/`](ai-search-eval/), 도구: [`scripts/aisearch/eval/`](../../scripts/aisearch/eval/)

## 사이클 23 (책첵 추천)

- 사이클 23 문서는 모두 [`docs/cycle23-foryou/`](../cycle23-foryou/README.md)로 옮겼습니다(2026-10-29). 색인부터 보세요

## 그 밖

- [사이클 11 넣기 측정](cycle11-import-measurement.html)
