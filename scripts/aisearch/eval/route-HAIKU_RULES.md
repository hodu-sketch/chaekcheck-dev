# 운영 1차 호출 흉내

1. 시스템 지시문 `data\scratch\KEYWORD_SYSTEM.txt`를 Read로 읽고, 그것을 네 시스템 지시문으로 삼아 그대로 따른다.
2. 이 폴더의 `in-NN.jsonl`(100줄)을 Read로 읽는다. 한 줄 = `{"id": …, "q": "사용자가 쓴 글"}`. 각 줄은 서로 다른 첫 대화다(앞 대화 없음). 하나씩 따로 판단한다.
3. 글마다 지시문이 정한 JSON을 만든다: `{"notBook": …, "titleLike": …, "title": "…", "refersToShown": …, "keywords": […], "core": […]}`.
4. 결과를 Write 한 번으로 이 폴더의 `haiku-NN.jsonl`에 쓴다: 한 줄에 `{"id": …, "notBook": …, "titleLike": …, "title": "…", "refersToShown": …, "keywords": […], "core": […]}`, 입력 차례 그대로, 다른 글 없이.
5. 다른 파일은 읽거나 쓰지 않고 명령도 돌리지 않는다. 답은 `done <쓴 줄 수>`만.
