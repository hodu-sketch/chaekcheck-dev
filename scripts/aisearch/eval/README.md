# AI 검색 평가 스크립트

AI 검색 실험·평가 스크립트입니다. 결과와 방법은 [`docs/features/aisearch/reports/`](../../../docs/features/aisearch/reports/)에, 지금 운영 구조와 버전 표는 [`docs/features/aisearch/4-results.md`](../../../docs/features/aisearch/4-results.md)에 있습니다.

- **독스트링의 「production」·「운영」은 그 스크립트를 쓴 날짜 기준입니다.** 예: 2026-10-25 스크립트의 「production first call Haiku」, 「30,939 books as production」은 그때 이야기이고, 지금 운영은 1차 ML(v1.16.0\~), 대상 76,759권(v1.17.0\~)입니다.
- 운영 모델을 만드는 스크립트: `export_first_call2.py`(`ROUTE_PT=route5b-mlp.pt`) → `first-call-model.bin`, `export_follow_up.py` → `follow-up-model.bin`. `export_first_call.py`는 v1.16.0 판.
- 데이터: `chaekcheck-dev/data/aisearch-eval-2026-10-25/`(git 밖, README 있음), 모델 폴더: `chaekcheck-data/ml/`(README 있음).
- 자주 쓰는 것: `rules_run.py`(80문항 규칙 시험), `timing_compare.py`(단계별 시간 비교), `ttft_probe.py`·`ttft_probe2.py`(Haiku 첫 토큰).
- 이어 묻기(2026-10-29, [follow-ups 보고서](../../../docs/features/aisearch/reports/2026-10-29-follow-ups.md)): `intent_compare.py`(정규식·벡터를 시험 문장으로 채점, 정규식은 서버 Java에서 읽음, ML venv의 python으로), `intent_mixed.py`(섞기 채점), `follow_up_replay.py`(운영 대화를 로컬 서버에 턴마다, 로그 줄 함께). 상황별 정리는 `tail_compare.py`.
