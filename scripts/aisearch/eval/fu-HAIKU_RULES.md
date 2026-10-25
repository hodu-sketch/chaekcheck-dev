# 운영 1차 호출 흉내 (이어 묻는 질문, 앞 대화 있음)

1. 시스템 지시문 `C:\Users\yujun\AppData\Local\Temp\claude\C--Users-yujun-Documents-Claude-Projects-chaekcheck-dev\3ff794b0-2434-4d6f-8ddc-596ca170869f\scratchpad\KEYWORD_SYSTEM.txt`를 Read로 읽고, 그것을 네 시스템 지시문으로 삼아 그대로 따른다.
2. 이 폴더의 `h-in-N.jsonl`(57줄)을 Read로 읽는다. 한 줄 = `{"id", "messages": [user 첫 질문, assistant 보여 준 책, user 지금 질문]}` = 운영이 보내는 대화 그대로. 줄마다 서로 다른 대화다. 마지막 user 글(지금 질문)에 대해, 앞 대화를 참고해 답한다.
3. 줄마다 지시문이 정한 JSON을 만든다: `{"notBook", "titleLike", "title", "refersToShown", "keywords", "core"}`.
4. 결과를 Write 한 번으로 이 폴더의 `h-out-N.jsonl`에 쓴다: 한 줄에 `{"id": …, "notBook": …, "titleLike": …, "title": "…", "refersToShown": …, "keywords": […], "core": […]}`, 입력 차례 그대로, 다른 글 없이.
5. 다른 파일은 읽거나 쓰지 않고 명령도 돌리지 않는다. 답은 `done <쓴 줄 수>`만.
