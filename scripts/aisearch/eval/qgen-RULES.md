# 책 찾기 질문과 정답 만들기 (분류기 학습용)

1. 먼저 사전 `data\scratch\taxonomy2.json`을 Read로 읽는다. 칸 값은 이 사전의 글자를 그대로 쓴다(사전에 없는 값은 쓰지 않는다).
2. 맡은 씨앗 파일 `seed-NN.jsonl`(이 폴더, 100줄)을 Read로 읽는다. 한 줄 = 씨앗 하나: `type`(질문 유형)과 바라는 칸(field, subfield, genre, country, audience, level, form, mood, exclude_hint).
3. 씨앗마다 **도서관 AI 책 찾기에 사람이 실제로 칠 법한 한국어 질문 하나**를 쓴다(5~60자, 반말·존댓말·짧은 말 섞어서, 맞춤법이 조금 틀려도 됨). 칸 이름을 그대로 쓰지 말고 사람 말로 쓴다(「재테크·투자」 → 「주식 처음 시작하는 사람한테 맞는 책」). 씨앗의 칸이 서로 어울리지 않으면 어울리는 것만 쓴다.
   - single: 조건 하나 / multi: 조건 둘 이상 / exclude: 빼 달라는 조건이 중심 / include_exclude: 원하는 것과 빼 달라는 것 함께 / negation: 「~말고」「~아닌」「~없는」 같은 부정 표현 / indirect: 분야·장르 낱말 없이 상황·기분으로만 말함(「요즘 회사 사람들 때문에 지쳤어」) / conflict: 서로 부딪치는 조건(「짧은데 깊이 있는 대하소설」) / vague: 정보가 거의 없음(「재밌는 거」) / similar_book: 실제 있는 책 제목을 들어 「○○ 같은 책」 / not_book: 책 찾기가 아닌 말(날씨, 인사, 잡담, 계산) / title_like: 책 제목이나 저자 이름만(실제 있는 것) / refers_shown: 앞에서 본 책들을 가리키는 말(「이 중에 소설만」, 「두 번째 거랑 비슷한 거」)
4. 그다음 **질문만 보고** 정답 JSON을 단다(씨앗이 아니라 질문에 실제로 적힌 뜻대로). 알 수 없는 칸은 비운다.
   - `notBook`, `titleLike`, `refersToShown`: true/false
   - `field`: 사전 `field` 키 0~2개 / `subfield`: 그 큰 칸 안의 값 0~2개
   - `fiction`: 소설·이야기를 원하면 true, 소설이 아닌 책을 원하면(「소설 말고」 포함) false, 알 수 없으면 null
   - `genre` 0~2, `country` "" 또는 하나, `audience` "" 또는 하나, `level` "" 또는 하나, `form` 0~2, `mood` 0~2
   - `exclude`: 빼 달라는 것 `{"field": [], "subfield": [], "genre": [], "mood": [], "country": []}` (사전 값만)
5. 결과를 Write 한 번으로 이 폴더의 `q-NN.jsonl`에 쓴다: 씨앗마다 한 줄 `{"seed": …, "type": …, "q": "질문", "notBook": …, "titleLike": …, "refersToShown": …, "field": […], "subfield": […], "fiction": …, "genre": […], "country": "", "audience": "", "level": "", "form": […], "mood": […], "exclude": {…}}`, 씨앗 차례 그대로, 100줄, 다른 글 없이.
6. 다른 파일은 읽거나 쓰지 않고, 명령도 돌리지 않는다. 답은 `done <쓴 줄 수>`만.
