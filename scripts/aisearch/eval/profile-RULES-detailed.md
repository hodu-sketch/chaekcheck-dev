# 책 프로필 만들기 — 상세 사전 (AI 책 찾기용)

1. 먼저 사전 `C:\Users\yujun\AppData\Local\Temp\claude\C--Users-yujun-Documents-Claude-Projects-chaekcheck-dev\3ff794b0-2434-4d6f-8ddc-596ca170869f\scratchpad\taxonomy2.json`을 Read로 읽는다. 모든 값은 이 사전의 글자를 그대로 쓴다(사전에 없는 값은 쓰지 않는다).
2. 맡은 입력 파일 `in-NNN.tsv`(이 폴더)를 Read 도구로 25줄씩 나눠 읽는다(offset 1, 26, 51, 76, 101, 126, limit 25). 한 줄 = book_id<TAB>제목<TAB>저자<TAB>KDC<TAB>대상(국가서지)<TAB>소개(앞 600자).
   - KDC·대상은 국립중앙도서관 국가서지 값이고 비어 있을 수 있다. 참고만 하고, 소개와 다르면 소개를 따른다.
3. **모든** 책마다 아래 JSON 하나. 소개·제목·저자·KDC에서 알 수 있는 것만 쓰고 지어내지 않는다.
   - `field`: 사전 `field`의 키 하나(큰 칸)
   - `subfield`: 그 큰 칸 목록 안의 값 하나(작은 칸). 큰 칸 목록이 비어 있으면(소설·기타) 빈 문자열
   - `fiction`: 지어낸 이야기(소설·시·희곡·동화·만화 이야기)이면 true
   - `genre`: fiction이 true일 때만 사전 `genre`에서 1~2개, 아니면 빈 배열
   - `country`: 문학(소설·시·희곡·에세이·어린이/청소년 소설)일 때만 지은이의 나라를 사전 `country`에서 하나, 아니면 빈 문자열. KDC 81x=한국, 82x=중국, 83x=일본, 84x=영미, 85x=독일, 86x=프랑스, 89x 중 러시아 문학=러시아
   - `audience`: 사전 `audience`에서 하나(ISBN 부가기호의 독자 대상과 같은 뜻: 일반 성인 교양서=교양, 생활·실용서=실용, 청소년=청소년, 중고생 참고서=중고 학습, 초등 참고서=초등 학습, 어린이책=아동, 전공·전문서=전문)
   - `level`: 사전 `level`에서 하나(모르면 교양)
   - `form`: 사전 `form`에서 0~2개(해당할 때만)
   - `topics`: 1~5개, 사람들이 찾을 때 쓸 한국어 낱말(사전 없음, 자유)
   - `mood`: 사전 `mood`에서 0~2개
   - 소개에 내용이 없으면 제목·저자·KDC만으로 `field`·`fiction`·`audience`를 정하고 나머지는 비운다
4. 결과를 Write 한 번으로 이 폴더의 `out-NNN.jsonl`에 쓴다: 책마다 한 줄 `{"id": book_id, "field": …, "subfield": …, "fiction": …, "genre": […], "country": …, "audience": …, "level": …, "form": […], "topics": […], "mood": […]}`, 입력 차례 그대로, 다른 글 없이.
5. 다른 파일은 읽거나 쓰지 않고, 명령도 돌리지 않는다. 답은 `done <쓴 줄 수>`만.
