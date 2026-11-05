# 책 찾기 질문에 칸 달기 (비교 시험용)

1. 먼저 사전 `data\scratch\taxonomy2.json`을 Read로 읽는다. 칸 값은 이 사전의 글자를 그대로 쓴다(사전에 없는 값은 쓰지 않는다).
2. 맡은 파일 `in-NN.jsonl`(이 폴더, 100줄)을 Read로 읽는다. 한 줄 = `{"id": …, "q": "질문"}`. 도서관 AI 책 찾기에 사람이 친 질문이다.
3. 질문마다 **질문에 실제로 적힌 뜻대로** 칸을 단다. 알 수 없는 칸은 비운다.
   - `notBook`: 책 찾기가 아닌 말이면 true / `titleLike`: 책 제목이나 저자 이름만 친 것이면 true / `refersToShown`: 앞에서 보여 준 책을 가리키는 말이면 true
   - `field`: 사전 `field` 키 0~2개 / `subfield`: 그 큰 칸 안의 값 0~2개
   - `fiction`: 소설·이야기를 원하면 true, 소설이 아닌 책을 원하면(「소설 말고」 포함) false, 알 수 없으면 null
   - `genre` 0~2, `country` "" 또는 하나, `audience` "" 또는 하나, `level` "" 또는 하나, `form` 0~2, `mood` 0~2
   - `exclude`: 빼 달라는 것 `{"field": [], "subfield": [], "genre": [], "mood": [], "country": []}` (사전 값만)
4. 결과를 Write 한 번으로 이 폴더의 `OUTNAME-NN.jsonl`에 쓴다(OUTNAME은 맡긴 사람이 알려 줌): 질문마다 한 줄 `{"id": …, "notBook": …, "titleLike": …, "refersToShown": …, "field": […], "subfield": […], "fiction": …, "genre": […], "country": "", "audience": "", "level": "", "form": […], "mood": […], "exclude": {…}}`, 입력 차례 그대로, 다른 글 없이.
5. 다른 파일은 읽거나 쓰지 않고, 명령도 돌리지 않는다. 답은 `done <쓴 줄 수>`만.
