# 책첵 추천 오프라인 평가 도구

[`docs/reports/foryou-portfolio.md`](../../../docs/reports/foryou-portfolio.md)의 숫자를 만든 스크립트입니다. 서비스 DB는 읽기만 하고, 도서관 사이트에는 요청하지 않습니다.

| 파일 | 하는 일 |
|---|---|
| `offline_eval.py` | 같은 폴더의 TSV(로컬 `db`에서 내보낸 `books`·`neighbours`·`listed`·`blurb_ids`·`cover_ids`·`tag_ids`)로 맞힘 시험(추천목록 한 분야에서 3권 → 나머지가 20권 안에, 220번)과 가상 사용자 1,000명 지표, 이웃 표 지표를 냄 → `metrics.json`, Haiku 판정 쌍 `judge-pairs.tsv` |
| `order_eval.py` | 순서 방식 9가지(보유 순·유사도 순·RRF·겹침 줄이기·분야 비율·벡터 MMR·무작위 …)를 같은 시험으로 → `order-metrics.json`. 세 번째 인자로 벡터 묶음(기본 10만 판) |
| `order_judge.py`, `judge_input.py` | 방식을 가린 판정 쌍을 250줄씩 나눔(Haiku 하위 에이전트가 1~5점·같은 작품 0/1을 씀) |
| `judge_score.py` | 판정 결과를 방식별 평균·4~5점·1~2점 비율로 |
| `make_charts.py` | 보고서 그림 `docs/reports/foryou-eval/*.svg` |

TSV 내보내기 질의와 실행 결과는 데이터 폴더 `ml/foryou/eval-2026-10-25/`, `eval-2026-10-26-100k/`에 있습니다(git 밖).
