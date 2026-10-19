# 누구나 가입 도서관 재검증 (2026-10-19~20)

- 요청(사용자 원문, 2026-10-19): "동대문구립도서관말이야 이거 누구나 가입 도서관이 아닌 것 같은데 / 누리집으로 가입하려면 방문해야 하고 / 서울온은 서울시민이어야 하고 / 누구나 가입 도서관들 싹 다 재검증해줘", "다른 누구나 도서관들도 싹 다 검증하고", "\"지역 외는 방문 가입\" 이 조건 추가". 2026-10-20: "최대한 안 물어보고 니가 검증 꼼꼼히해서 …".
- 대상: 운영 DB에서 RESIDENCE × NATIONWIDE 규칙이 있는 켜진 도서관 39줄(기관 약 27곳).
- 방법: 기관마다 공식 페이지를 직접 열어 원문 인용(4-8, 4-10). User-Agent `chaekcheck/0.0.1`, 호스트당 1건·1.1초 이상 간격. 네 묶음을 나눠 조사(아래 1~4절은 각 묶음의 기록 그대로).
- 판정: OPEN_ONLINE(누구나·온라인), OPEN_VISIT(누구나·방문), MIXED(지역 사람은 온라인, 그 밖은 방문), REGION(지역만), UNKNOWN.

## 반영한 것 (사용자가 판단을 맡김, 2026-10-20)

| 도서관 (id) | 바꾼 것 | 근거 |
|---|---|---|
| 동대문구립(61, 230), 도봉문화정보(60), 은평구립(223) | 「지역 외는 방문 가입」(서울 거주 = 서울온 온라인, 그 밖 = 방문) | 서울온 "주민등록상 서울거주시민", "주소지가 서울이 아닌 경우 서울온을 통한 비대면 가입 불가" |
| 광주통합도서관(133, 277) | 「지역 외는 방문 가입」(광주 5개 구 = 비대면 인증, 그 밖 = 방문), 133 방문 표시 끔 | "준회원은 비대면인증 후 이용 가능"(광주시민 인증) |
| 울산동부(361) | 누구나 → 울산 거주·재직·재학, 방문 가입 | 전자도서관 페이지 "자료대출회원 가입 후 이용"(4-8 규칙 2). FAQ "대출자격: 도서관 홈페이지 회원"과 어긋남 → 14절 확인 목록 |
| 양주시(94) | 방문 가입 켬 | "홈페이지 회원가입 후 도서관 방문", 전자책 등급 원문 없음(4-10 규칙 4) |
| 창원 의창(148, 387) | 두 줄 모두 방문 가입 | 회원증 발급처가 자료실뿐, 온라인 신청만으로 끝난다는 문장 없음 |
| 제주 한라(1109, 2267) | 방문 가입 끔, 가입 방법에 Lib-Homepass | "도서관 방문 없이 어디서나 도서대출 정회원 가입이 가능" |
| 도봉(60) | 가입 방법 글: 서울시민카드 → 서울온(서울 거주자만) | 비대면 안내 페이지 |
| 은평(223) | join_url → 회원구분 페이지 | 회원 등급·가입대상 원문이 있는 곳 |

그대로 둔 곳: 고흥·강북·군산·목포·석남어린이·제주 우당/한라(139, 140) = 누구나 온라인. 국립세종·김해·통영·성북·의정부·포천·광주 북구 = 누구나 방문. 광산구(40, 264, 온라인 회원증 신청이 로그인 뒤라 대상 미확인)·남구(233, 298)·김천(313)·금산(267, 구비서류에 "거주" 문구)·동작(62, "누구나" 문구 없음)은 지금 값 유지, 확인 목록에 적음.

---

# 그룹 1 재검증: 서울·고흥 (2026-10-20 확인)

All pages were opened directly with `User-Agent: chaekcheck/0.0.1`, one request at a time per host, 1.2 s or more apart. I did not fetch any login-only or JS-only pages. One file I skipped: 동작's "도서관운영내부규정" is a file download (`/board/boardFile/download/...`).

Common fact about 서울온 (the old 서울시민카드 app), quoted from the official library pages that describe it:
- 동대문 https://www.l4d.or.kr/intro/menu/11599/contents/41462/contents.do : "서비스 이용대상 : 주민등록상 서울거주시민", "※주소지가 서울이 아닌 경우 서울온을 통한 비대면 가입 불가"
- 은평 https://www.eplib.or.kr/intro/menu/10100/contents/40006/contents.do : "이용대상 서울거주 시민(주민등록상 서울거주 시민)", "주소지가 서울이 아닌 경우 서울온을 통한 비대면 가입 불가"

So any "서울온/서울시민카드로 방문 없이" path works only for people registered as living in Seoul.

---

## 고흥군립도서관 전자도서관 (id 2, KYOBO)

**Verdict: OPEN_ONLINE** (this follows the user's decision of 2026-09-29. One caveat below.)

Paths
- 누구나: 홈페이지 회원가입(약관 동의 → 휴대폰·아이핀 본인인증 → 정보입력)으로 "도서관회원" → 홈페이지 로그인 → 전자책.
- The 회원증 (paper-book 관외대출) is only for "주민등록상 고흥군 거주자 및 고흥군소재 직장인", issued by a visit. The e-book page does not ask for a 회원증.

Quotes
- 전자책 https://www.ghlib.go.kr/EBook : "대상 고흥군립도서관 회원", "PC: 고흥군립도서관 홈페이지 로그인 → 전자책 → 전자책 홈페이지 로그인"
- 회원가입 https://www.ghlib.go.kr/MemberGuide : "휴대폰 본인인증 또는 아이핀 인증으로 본인확인", "회원가입완료 도서관회원으로 가입완료" (no region text)
- 회원증 https://www.ghlib.go.kr/JoongAngUserGuide : under the heading "도서대출안내 및 회원증 발급": "회원가입 대상 주민등록상 고흥군 거주자 및 고흥군소재 직장인", "이용자가 직접 도서관에 방문하셔야 합니다."
- 교보 로그인 https://www.ghlib.go.kr:446/elibrary-front/member/memberLogin.ink : default text only ("이용하시는 도서관 담당자에게 문의").

Caveat: "고흥군립도서관 회원" is not defined as web member or card holder anywhere. The online signup ends with the words "도서관회원으로 가입완료", which supports reading it as the web member.

Differs from stored: nothing. visit_required=f and join_method "도서관 홈페이지 가입 후 로그인" match the source text.

---

## 강북문화정보도서관 (id 17, KYOBO)

**Verdict: OPEN_ONLINE** (on PC. The mobile-app e-book is limited to card members.)

Paths
- 누구나: 홈페이지 웹회원 가입(아이핀·휴대폰 인증) → 전자책도서관 이용(PC).
- 정회원 (도서대출회원, 회원증): 서울 거주 / 서울 소재 직장·학교. These people can join online through the "서울시민카드" app (비대면 정회원 가입); anyone else in that group visits.
- Mobile app e-book: card members (도서대출회원) only.

Quotes (https://www.gblib.or.kr/gangbuk/contents/contents.do?gidx=150300)
- "웹회원은 누구나 가입 가능(지역 구분 없음)"
- 웹회원 서비스 범위 (강북문화정보도서관): "- 전자책도서관 이용", "- 스마트폰 모바일 도서관 이용 불가 (전자책 기능 포함) (도서대출회원만 이용)"
- 정회원 발급대상: "서울시에 주민등록이 되어 있는 시민", "서울시 소재 직장 또는 학교에 재직하거나 재학 중인 시민"
- "비대면 정회원 가입 : "서울시민카드" 애플리케이션을 이용"
- 교보 로그인 https://ebook.gblib.or.kr/elibrary-front/member/memberLogin.ink : default text, no member-level limit.

Differs from stored: visit_required=f and join_method match. Not recorded anywhere: a web member cannot read e-books in the mobile app ("전자책 기능 포함 … 도서대출회원만"). This could go into join_caution.

---

## 도봉문화정보도서관 (id 60, KYOBO)

**Verdict: MIXED** (Seoul residents can join online. Everyone else must visit.)

Paths
- 서울 거주자: 서울온 앱 → 도봉구통합도서관 시설카드 추가 → 회원증 without a visit. The e-book row in the 비대면 service table is "O".
- 그 밖 (대한민국 국민·재외동포·거소신고자·외국인등록자 who do not live in Seoul): 홈페이지 가입 → 도서관 방문(신분증·증빙서류) → 회원증.
- e-books need a 회원증 ("회원증 소지자에 한하여").

Quotes
- 전자책 https://www.unilib.dobong.kr/contents.do?idx=301 : "이용대상 도봉구통합도서관 회원증 소지자에 한하여 이용 가능"
- 회원가입 https://www.unilib.dobong.kr/contents.do?idx=174 : "홈페이지 회원 가입 후 회원증 발급 대상 및 증빙서류 를 반드시 확인하고 우리 도서관에 방문(신분증 , 증빙서류 지참)하여 신분 확인 후 회원증을 발급 받을 수 있습니다."
- 회원자격 https://www.unilib.dobong.kr/contents.do?idx=90 : "제3조 (회원자격) … 1. 대한민국 국민 2. … 재외동포 3. … 국내거소신고자 4. … 외국인등록자"
- 비대면 https://www.unilib.dobong.kr/contents.do?idx=306 : "서울온(구 서울시민카드) 앱을 통해 도서관에 방문하지 않고 회원증을 발급받아 바로 사용이 가능한 서비스입니다." The page itself does not state who may use 서울온; the Seoul-residence rule is quoted in the common section at the top.
- 교보 로그인 https://elib.dobong.kr/elibrary-front/member/memberLogin.ink : default text.

Differs from stored
- visit_required=f was set by rule 2 because an online path exists. A non-Seoul person always has to visit.
- join_method says "또는 서울시민카드(앱) 온라인 회원증". It does not say the app is for Seoul residents only, and the app is now called 서울온. Suggested wording: "서울 거주자: 서울온 앱으로 방문 없이 회원증 / 그 밖: 홈페이지 가입 → 도서관 방문(신분증·증빙서류) 회원증 발급".

---

## 동대문구립도서관 (ids 61 KYOBO, 230 YES24): the case the user raised

**Verdict: MIXED** (Seoul residents can join online. Everyone else must visit.) The user is right: this is not "누구나 온라인".

Paths
- 서울 거주자 (주민등록상): 서울온 앱 → 동대문구립도서관 시설카드 추가 → 온라인 회원카드, no visit.
- 그 밖 (가입 대상 "제한없음"): 누리집 온라인 회원가입 → 신분증 들고 본인이 직접 도서관 방문 → 회원카드.
- e-books need a 정회원, meaning a card holder. A website account alone is not enough.

Quotes
- 전자책 https://www.l4d.or.kr/intro/40015/contents.do : "도서관 회원가입 후 이용가능 (정회원만 이용가능)", "전자책 서비스는 정회원 로그인 후 이용하실 수 있습니다"
- YES24 사이트 http://e-book.l4d.or.kr:8088/ : "도서관대출회원가입 후 이용가능"
- 회원가입안내 https://www.l4d.or.kr/intro/menu/10084/contents/40007/contents.do : "가입 순서 누리집 → 온라인 회원가입 → 도서관에서 회원카드 발급 수령 (본인이 직접 도서관에 방문하여 회원카드를 발급받아야 합니다.)", "가입 대상 제한없음", "※ 서울시민인 경우 서울온 앱을 통한 가입 시 도서관 방문없이 온라인 회원카드 발급"
- 서울온 https://www.l4d.or.kr/intro/menu/11599/contents/41462/contents.do : "서울 거주 여부 모바일 확인으로 도서관 방문없이 즉시 모바일 도서대출회원증을 발급", "서비스 이용대상 : 주민등록상 서울거주시민", "※주소지가 서울이 아닌 경우 서울온을 통한 비대면 가입 불가"
- 교보 로그인 https://e-book.l4d.or.kr/elibrary-front/member/memberLogin.ink : no div.toogle_con panel (same as 2026-09-29).

Differs from stored: join_method already says this correctly. visit_required=f comes from rule 2, so the screen shows no "방문 가입" tag and the site appears under "방문 없이 가입", even though anyone outside Seoul has to visit. The 누구나 label is correct for eligibility ("제한없음"). The online part, though, is Seoul-only.

---

## 동작구통합도서관 (id 62, KYOBO)

**Verdict: OPEN_VISIT** (weak: the source has no region condition but also never says "누구나", and the e-book member level is not stated.)

Paths
- 모두: 홈페이지 가입(아이핀·휴대폰 인증) → 발급 필요서류 들고 동작구립도서관 방문 → 정회원 승인 + 회원증. No 서울온 or other online path is mentioned anywhere.
- e-book member level: not in the source. The homepage 전자도서관 page shows "페이지 준비중 입니다."

Quotes
- 회원가입안내 https://lib.dongjak.go.kr/dj/html.do?menu_idx=8 : "홈페이지 회원가입 완료 후 발급 필요서류를 지참하여 동작구립도서관에 방문하여 도서관회원증 발급", "※ 14세 이상의 경우 본인이 직접 방문 (가족 간 대리발급 불가)". The 필요서류 table lists "외국인 국내거소신고증 / 외국인등록증" and "직장인 및 재학생 재직증명서 / 학생증 / 재학증명서". Nothing about region or "누구나". The 직장인·재학생 row hints at a residence/work rule, but no rule is written.
- 전자도서관 https://lib.dongjak.go.kr/dj/html.do?menu_idx=15 : "이용에 불편을 드려 죄송합니다. 페이지 준비중 입니다."
- 대출 https://lib.dongjak.go.kr/dj/html.do?menu_idx=9 : "도서관 회원증을 발급받은 회원 본인에 한하며" (paper books)
- 교보 로그인 https://ebook.dongjak.go.kr/elibrary-front/member/memberLogin.ink : default text.

Differs from stored: visit_required=t, join_method and the join_caution "원문에 지역 조건은 없음…" match. On 2026-09-29 this site was classified UNVERIFIED. If the rule is "누구나 needs an explicit 누구나/제한없음", it should go back to UNKNOWN.

---

## 성북도서관 / 성북정보전자도서관 (ids 84 KYOBO, 349 BOOKCUBE)

**Verdict: OPEN_VISIT**

Paths
- 누구나 (가입대상 제한없음): 홈페이지 회원등록 → 신분증 들고 도서관 방문 → 대출회원증. e-books are for card holders only.
- No 서울온 or other online card path on the pages I checked: 회원가입, 모바일서비스 (only the 성북u-도서관 app), 전자도서관.

Quotes
- 교보 로그인 https://elibrary.sblib.seoul.kr/elibrary-front/member/memberLogin.ink : "1. 아이디/비밀번호는 성북구립도서관 아이디/비밀번호 와 동일합니다. 2. 전자책 이용은 도서관 정회원(회원카드 발급자)만 이용가능 합니다."
- 회원가입 https://www.sblib.seoul.kr/library/menu/10026/contents/40035/contents.do : "홈페이지 회원등록 → 도서관 방문 → 구비서류 제시 → 대출회원증 발급", "가입대상 - 제한없음(누구나 가입하실 수 있습니다.)", "홈페이지 회원만 가입하신 이용자님께서는 도서관 방문 후 회원증을 발급받으셔야 도서대출 및 도서관의 모든 서비스를 이용하실 수 있습니다."
- 전자도서관 https://www.sblib.seoul.kr/library/menu/10016/contents/40008/contents.do : "성북구립도서관 회원이면 누구나 다양한 전자도서관 서비스를 … 이용하실 수 있습니다."
- BOOKCUBE http://elibrary.sblib.seoul.kr:8080/FxLibrary/ : no member text.

Differs from stored: nothing. visit_required=t and join_method match.

---

## 은평구립도서관 (id 223, YES24)

**Verdict: MIXED** (Seoul residents can join online. Everyone else must visit.)

Paths
- 서울 거주자: 서울온 앱 시설간편가입 → 모바일 도서대출회원증 without a visit (도서대출, 전자책).
- 그 밖 (정회원 가입대상 "거주지에 관계없이 누구나"): 홈페이지 가입(준회원) → 구비서류 들고 공공도서관 방문 → 실물회원증 (정회원).
- e-books are for 도서대출 회원 (정회원) only.
- The "온라인정회원 신청" button that the 2026-09-29 record used is now commented out in the page HTML (`<!-- <button … onclick="fnUntact();">온라인정회원 신청</button> -->`). That online path no longer exists.

Quotes
- 전자책 https://www.eplib.or.kr/intro/menu/10031/contents/40001/contents.do : "이용자격 : 은평구 공공도서관 도서대출 회원"
- 회원구분 https://www.eplib.or.kr/intro/menu/10099/contents/40005/contents.do : 준회원 "회원정의 : 홈페이지에만 가입한 회원 … 이용가능 서비스 : 게시글 작성, 열람실 이용, 디지털자료실 이용, 견학 신청, 문화행사 신청"; 정회원 "회원정의 : 도서관에 방문하여 도서관 회원증을 발급 받은 회원", "가입대상 : 거주지에 관계없이 누구나 가입 가능", "이용가능 서비스 : 준회원이 사용가능한 모든 서비스, 도서대출 서비스, 디지털 서비스 이용"; "구비서류 지참 → 가까운 공공도서관 사서데스크 방문 → 실물회원증 발급"; "서울온 모바일 회원증 : 별도의 도서관 회원증 발급 없이 이용 가능"
- 서울온 https://www.eplib.or.kr/intro/menu/10100/contents/40006/contents.do : "방문없이(비대면) 서울거주확인으로 즉시 모바일 도서대출 회원증을 발급받아 도서대출, 전자책 등을 사용할 수 있는 서비스", "이용대상 서울거주 시민(주민등록상 서울거주 시민)", "주소지가 서울이 아닌 경우 서울온을 통한 비대면 가입 불가"

Differs from stored: join_method "서울 주민은 서울온 모바일 회원증으로 방문 없이" matches. join_url (10031/40001) is the e-book page; the membership text is on 10099/40005. visit_required=f again comes from rule 2, and a non-Seoul person must visit.

---

## Summary

| institution | ids | verdict | reason |
|---|---|---|---|
| 고흥군립도서관 | 2 | OPEN_ONLINE | 전자책 "고흥군립도서관 회원" = 홈페이지 가입(본인인증). 회원증 고흥 거주 조건 is only for paper loans (user decision 2026-09-29) |
| 강북문화정보도서관 | 17 | OPEN_ONLINE | "웹회원은 누구나 가입 가능(지역 구분 없음)", 웹회원 = 전자책도서관 이용. The mobile-app e-book is for card members only (not recorded) |
| 도봉문화정보도서관 | 60 | MIXED (서울) | 전자책 "회원증 소지자" only. 서울 거주자 via 서울온, others visit. join_method does not say the app is Seoul-only |
| 동대문구립도서관 | 61, 230 | MIXED (서울) | "정회원만", 회원카드 by visit. 서울온 "주민등록상 서울거주시민" only. visit_required=f hides the visit for non-Seoul people |
| 동작구통합도서관 | 62 | OPEN_VISIT (weak) | Visit for 회원증, no online path. Source has no region and no "누구나". Homepage e-book page "준비중" |
| 성북도서관 / 성북정보전자도서관 | 84, 349 | OPEN_VISIT | "정회원(회원카드 발급자)만", "제한없음(누구나)", visit needed. Stored values are right |
| 은평구립도서관 | 223 | MIXED (서울) | 전자책 "도서대출 회원", 정회원 누구나 but by visit, 서울온 Seoul-only. The 온라인정회원 button is now removed |


---

# 그룹 2 재검증: 광주·전남, 목포 (2026-10-19)

방법: 공식 페이지를 직접 가져옴(User-Agent `chaekcheck/0.0.1`, 호스트당 1건, 1.2초 이상 간격). 모든 페이지 UTF-8. 기관 5곳, id 8개.

| 기관 | ids | 판정 | 한 줄 |
|---|---|---|---|
| 광산구통합도서관 | 40, 264 | OPEN_ONLINE (주의) | 정회원만 전자책. 지역 조건 없음. "회원증 온라인 발급 신청"(로그인 뒤, 조건 미확인) 또는 방문 발급 |
| 목포시통합도서관 | 65 | OPEN_ONLINE | 전자책 대상 "정회원(홈페이지 가입자)", "홈페이지 회원가입 후 바로 이용가능", 가입 대상 "대한민국 국민, 등록외국인" |
| 전남광주통합특별시 북구 통합도서관 | 130 | OPEN_VISIT | 정회원만 전자책. 정회원 = 홈페이지 가입 → 본인 방문(신분증) → 회원증. 지역 조건 없음 |
| 전남광주통합특별시교육청 광주통합도서관 | 133, 277 | MIXED (광주시민) | 정회원(방문) 또는 준회원 + 광주시민 비대면 인증. 비광주시민은 방문만 |
| 전남광주통합특별시 남구통합도서관 | 233, 298 | OPEN_ONLINE (주의) | 도서대출회원만 전자책. 온라인 회원증(방문 없이 회원번호) 이용자격 "홈페이지 회원 가입을 완료한 자" |

---

## 1. 광산구통합도서관 (id 40 KYOBO, id 264 YES24 "광주장덕도서관")

**판정: OPEN_ONLINE (주의: 온라인 발급 조건은 로그인 뒤라 확인 못 함)**

경로
- 누구나(지역 조건 원문에 없음): 홈페이지 가입(본인인증) → 로그인 → "회원증 온라인 발급 신청" → 모바일 회원증. 단, 온라인 발급에 따로 조건이 있는지는 로그인 뒤 화면이라 볼 수 없음.
- 누구나: 홈페이지 가입 → 신분증·본인 명의 휴대폰 들고 자료실 방문 → 도서대출회원증 발급.

원문
- 전자책 https://lib.gwangsan.go.kr/main/elibBook : 구독형·소장형 모두 "이용대상 : 광산구통합도서관 정회원". 소장형 모바일은 "‘예스24 전자도서관’ 앱 설치 후 ‘광주장덕도서관’ 선택" (264가 같은 기관임을 확인).
- 회원증발급 https://lib.gwangsan.go.kr/main/memberShip : "온라인신청 / 회원증 온라인 발급 신청"(버튼 주소 `/main/login?rtn=A`, 로그인 필요), "회원증 온라인 발급 완료 후 모바일 회원증 확인 방법". "방문신청 / 도서대출회원증 발급 … 구비서류 : 신분증(주민등록증, 운전면허증, 여권, 주민등록번호가 기재된 학생증), 본인명의 핸드폰 또는 아이핀", "발급장소 : 도서관 어린이자료실 및 종합자료실".
- 이용안내 https://lib.gwangsan.go.kr/main/contents/info : "대출회원에 가입하시려면 신분증(…)과 본인명의 휴대폰 또는 아이핀(마이핀) 아이디를 지참하고 자료실의 도서검색대PC에서 회원가입하시면 회원증 발급 후 대출이 가능합니다." "정회원"의 정의나 지역 조건 문장은 이 페이지와 회원가입 페이지(https://lib.gwangsan.go.kr/main/memberJoin : 약관동의→본인인증→정보입력, 일반/어린이 회원) 어디에도 없음.
- 교보 로그인 https://ebk.gwangsan.go.kr/elibrary-front/member/memberLogin.ink : 펼침 글은 교보 기본 문구("아이디/비밀번호는 이용하시는 도서관 담당자에게 문의"). YES24 로그인 http://ebk.gwangsan.go.kr:8890/member/?mode=login : "도서관 홈페이지에서 로그인 후 다시 접속하여 이용하시기 바랍니다."
- 약관 단계(`/main/memberJoin/step1`)는 POST+CSRF라 열지 않음. 공지 게시판 검색은 걸러지지 않아 그만둠.

저장값과 다른 점
- 40, 264 모두 `visit_required = t`, `join_method`는 방문 발급만 적힘. 원문에 방문 없는 길("회원증 온라인 발급 신청" → 모바일 회원증)이 있으므로 4-10 규칙 2에 따르면 `visit_required = false`, `join_method`에 온라인 발급을 먼저 써야 함. 단 온라인 발급이 정회원이 되는지, 누구나 신청 가능한지는 원문에 직접 없음(로그인 뒤) → 주의 유지 권장.
- 264 `join_method` "자료실 도서검색대 PC에서 가입 → 회원증 발급"은 원문과 같음(방문 경로로서).

## 2. 목포시통합도서관 (id 65, KYOBO)

**판정: OPEN_ONLINE**

경로
- 누구나(대한민국 국민, 등록외국인): 홈페이지 가입(본인인증)만으로 전자책. 종이책 회원증은 방문 발급.

원문
- 전자도서관 https://www.mokpolib.or.kr/cms/bbs/dk_content.php?ht_id=search_05 : "대상 : 목포시통합도서관 정회원(홈페이지 가입자)", "아이디 : 홈페이지 회원가입시 아이디", "홈페이지 회원가입 후 바로 이용가능".
- 도서대출안내 및 회원증 발급 https://www.mokpolib.or.kr/cms/bbs/dk_content.php?ht_id=intro_04 : "회원가입 대상 / 대한민국 국민, 등록외국인, 목포시 시관 및 단체", "구비서류 (홈페이지 가입으로 본인인증 후 발급 가능)", "이용자가 직접 도서관에 방문하셔야 합니다."(회원증 발급 항목).
- 교보 로그인 https://ebook.mokpolib.or.kr:444/elibrary-front/member/memberLogin.ink : 기본 문구(이전 기록과 같음).

저장값과 다른 점: 없음(`visit_required = f`, `join_method` 원문과 일치). 다만 `join_url`은 회원증 페이지(intro_04)이고, 전자책 대상 원문은 search_05에 있음.

## 3. 전남광주통합특별시 북구 통합도서관 (id 130, KYOBO)

**판정: OPEN_VISIT**

경로
- 누구나(지역 조건 원문에 없음): 홈페이지 가입(준회원) → 본인 방문, 신분증 → 회원증 발급(정회원). 온라인 길은 원문에 없음.

원문
- 전자책 https://lib.bukgu.gwangju.kr/main/contents.do?PID=0709 : 구독형·소장형 "이용대상 : 전남광주통합특별시 북구통합도서관 정회원".
- 회원증 발급 https://lib.bukgu.gwangju.kr/main/contents.do?PID=010502 : "STEP1 홈페이지 회원가입 (준회원) / STEP2 도서관 방문 ※ 본인 방문, 신분증 지참 필수 / STEP3 회원증 발급 (정회원)". 필요서류 "신분증·전자신분증(주민등록증, 운전면허증, 여권 등)", "※ 사진으로 찍은 서류는 사용 불가".
- 회원가입 https://lib.bukgu.gwangju.kr/main/memberJoin.do?PID=9903 : 약관동의→본인인증→정보입력, 일반/어린이. 지역 조건 없음.
- 교보 로그인 https://ebook.bukgu.gwangju.kr/elibrary-front/member/memberLogin.ink : 펼침 글 없음(이전 기록과 같음).

저장값과 다른 점: 없음(`visit_required = t`, `join_method` 원문과 일치).

## 4. 전남광주통합특별시교육청 광주통합도서관 (id 133 소장형 KYOBO, id 277 구독형 KYOBO)

**판정: MIXED (광주시민은 온라인, 그 밖은 방문)**

경로
- 광주시민: 통합도서관 누리집 가입(준회원) → "광주시민인증 (비대면자격확인)" → 전자책·전자잡지. 방문 없음.
- 그 밖(지역 조건 없음, 대상 "홈페이지 회원가입자"): 누리집 가입 → 각 도서관 자료실 방문, 신분증 → 회원증 발급(정회원) → 전자책.
- "광주시민인증"이 누구를 말하는지(옛 광주광역시 주민만인지, 통합특별시 전체인지) 정의 문장은 찾지 못함. 이름대로면 광주 시민.

원문
- 전자책 이용안내 https://lib.jge.go.kr/main/contents.do?idx=2854 : 전자책 "이용대상 / 전남광주통합특별시교육청 통합도서관 정회원 ※ 통합도서관 홈페이지 가입(준회원) 후 도서관을 방문하여 정회원 인증한 회원 / 전남광주통합특별시교육청 통합도서관 준회원 (광주시민 인증 필요) ※ 통합도서관 홈페이지 가입(준회원) 후 광주시민(비대면 자격확인) 인증한 회원". 구독형·소장형을 나누지 않고 "전자책" 하나로 적음.
- 회원가입 https://lib.jge.go.kr/main/contents.do?idx=2853 : "STEP 2-1 광주시민인증 (비대면자격확인)", "STEP 3 … 통합도서관 방문 / STEP 4 자료실에서 본인확인 후 회원증 발급", "광주시민인증(비대면자격확인): 구독형 전자책, 전자잡지 이용 가능" (여기서는 구독형만 적음).
- 회원증 발급안내 https://lib.jge.go.kr/main/contents.do?idx=1737 : "대상 / 전남광주통합특별시교육청 광주통합도서관 홈페이지 회원가입자", "통합홈페이지 가입 후 운영시간 내 각 도서관 자료실에 방문하여 발급", 준회원 칸 "전자책, 전자잡지 이용 등 (광주시민 인증 후 이용 가능)". 외국인 "재외국민 국내거소신고증 또는 외국인등록증"도 받음 → 회원증 자체는 지역 조건 없음.
- 소장형 교보 로그인 https://libebook.gen.go.kr/elibrary-front/member/memberLogin.ink : 펼침 글은 기본 문구. 로그인 칸 옆 "★ 도서관 홈페이지 로그인 방식과 동일 단 준회원은 비대면인증 후 이용 가능" → 소장형(133)도 준회원+비대면인증으로 됨.
- 구독형 https://libgen.dkyobobook.co.kr (main.ink, member/memberLogin.ink 두 번) : 연결 시간 초과(WinError 10060), 열지 못함.

저장값과 다른 점
- 133: `visit_required = t`, `join_method`에 비대면 인증 없음. 원문(idx=2854, 소장형 로그인 화면)에 광주시민 비대면 길이 있으므로 4-10 규칙 2로 `visit_required = false`, `join_method`에 "광주시민 비대면 인증" 추가가 맞음(277과 같게). 단 idx=2853은 비대면 인증을 "구독형"만 적어 원문끼리 조금 다름.
- 277: 값은 원문과 맞음. 다만 "누구나 가입"이 아니라 광주시민만 온라인이라는 점에서 MIXED.
- 둘 다 `join_caution`의 "원문에 지역 조건 없음"은 회원증에 대해서는 맞음.

## 5. 전남광주통합특별시 남구통합도서관 (id 233 YES24 "광주남구문화정보도서관", id 298 BOOKCUBE)

**판정: OPEN_ONLINE (주의: 온라인 회원증이 '도서대출회원'인지 한 문장으로 적혀 있지는 않음)**

경로
- 누구나(이용자격 "홈페이지 회원 가입을 완료한 자", 지역 조건 없음): 홈페이지 가입(본인인증) → 온라인 회원증 신청(로그인 뒤) → 회원번호 발급 → 모바일 회원증. 방문 없음.
- 누구나: 홈페이지 가입 → 도서관 방문(신분증) → 도서대출카드.

원문
- 소장형 https://lib.namgu.gwangju.kr/main/cpPage01, 구독형 https://lib.namgu.gwangju.kr/main/cpPage04 : "이용대상 / 남구통합도서관 홈페이지에 가입한 구립도서관 도서대출회원", 구독형에 "※ 남구통합도서관 홈페이지만 가입한 경우 이용불가", 바로 아래 "온라인회원증 바로가기", "※ 구립도서관 도서대출회원만 등록한 경우 이용불가".
- 온라인 회원증 https://lib.namgu.gwangju.kr/main/memberShip : "홈페이지 회원 가입(본인인증) 후 도서관 방문 없이 회원번호를 발급받아 모바일 회원증을 사용하실 수 있습니다.", "이용자격 / 남구통합도서관 홈페이지 회원 가입을 완료한 자".
- 도서대출 회원 https://lib.namgu.gwangju.kr/main/contents/loanMember : "Step.1 홈페이지 회원가입 / Step.2 도서관 방문 (신분증 지참) / Step.3 회원증 발급", 제공 서비스 "… 전자책 등", 유의사항 "도서대출카드는 도서관에 직접 방문하신 후 발급", "‘온라인회원증’은 도서관 방문 없이 홈페이지에서 신청 후 이용하실 수 있습니다.(참여신청→온라인회원증)".
- 홈페이지 회원 https://lib.namgu.gwangju.kr/main/contents/homeMember : "프로그램 신청, 도서 검색, 게시물 조회, 글쓰기 등 일부 서비스로 제한", "회원가입을 위해 본인인증이 필요합니다."
- YES24 로그인 http://ebook.namgu.gwangju.kr:8080/member/?mode=login : "도서관 홈페이지에서 로그인 후 다시 접속하여 이용하시기 바랍니다." BOOKCUBE http://namgu.bookcube.biz:9080/FxLibrary/ : 가입 안내 글 없음.

저장값과 다른 점: 없음(233, 298 모두 `visit_required = f`, `join_method` 원문과 일치). 298의 `join_caution`("온라인 회원증이 도서대출회원에 해당하는지 원문에 직접 적혀 있지 않음")은 이번에도 그대로 맞음. 233에는 같은 주의가 없음 — 같은 기관이므로 맞추는 것을 고려.


---

# 그룹 3 재검증 결과 (2026-10-19~20)

가져오기: User-Agent `chaekcheck/0.0.1`, 호스트당 한 번에 1건, 1.2초 간격, 인증서 검사 그대로. 아래 인용은 모두 직접 연 공식 페이지 원문.
참고: 전달받은 그룹 설명에는 "포천"이 있었지만 g3.tsv에는 포천 줄이 없습니다(국립세종, 군산, 금산, 김천, 김해, 울산동부, 통영, 창원 의창만 있음). 포천은 조사하지 않았습니다.

---

## 1. 국립세종도서관 — ids 43 (KYOBO), 263 (YES24), 302 (BOOKCUBE)

**판정: OPEN_VISIT** (누구나 되지만 모두 처음 한 번은 방문해야 함)

경로
- 누구나(대한민국 국민, 외국인등록자, 재외동포 거소신고자): 국립중앙도서관 통합회원 가입(온라인) → 국립세종도서관에 한 번 방문해 신분증 내고 대출증 발급 → 그 뒤 전자책은 어디서나 홈페이지 로그인으로 이용.
- 무인 대출증 발급기도 도서관 정문에 있어서 이것도 방문입니다. 모바일 대출증은 카드형 대출증이 이미 있어야 받을 수 있습니다.

원문
- 전자책 이용 대상(공지 「2026년 구독형 전자책 서비스 안내」): "○ 이용 대상 : 국립세종도서관 대출회원 ( 대출증 소지자 )", "※ 대출회원이 아닌 경우 , 대출증 발급 후 이용 가능", "도서관 내 / 외부 어디에서나 홈페이지 로그인 후 이용 가능" — https://sejong.nl.go.kr/brd/NttView.do?bbsSe=BBST001&menuId=O121&upperMenuId=O100&selectedIdx=106340
- 회원가입: "국립세종도서관은 별도 회원가입 과정 없이 국립중앙도서관과 통합회원으로 관리하고 있습니다.", "단, 도서 및 온라인(전자책 포함)자료 대출을 위해서는 최초 1번 직접 국립세종도서관을 방문하여 대출증을 발급받아서 서비스를 이용할 수 있습니다." — https://sejong.nl.go.kr/html/c7/c711.jsp?menuId=O717&upperMenuId=O700
- 대출증 발급(자료실 이용안내): "발급 방법 홈페이지 회원 가입 후 도서관 방문", 구비서류 "대한민국 국민 성인 신분증(주민등록증, 운전면허증, 여권, 모바일 신분증 등)", "외국인 외국인등록증 또는 외국인등록 사실증명", "재외동포 국내거소신고증 또는 국내거소신고 사실증명". 모바일 대출증: "대출증 미발급자의 경우, 최초 1회 대출증 발급 절차 필요 (신분증 지참 후 도서관 직접 방문)". 지역 조건은 없음. — https://sejong.nl.go.kr/html/c1/c101.jsp?menuId=O111&upperMenuId=O100
- YES24 사이트(http://ebook.nls.go.kr/) 로그인 칸: "도서관 홈페이지에서 로그인 후 이용하실 수 있습니다."
- 교보 로그인 화면(https://sejongnl.dkyobobook.co.kr/elibrary-front/member/memberLogin.ink)은 연결 시간 초과로 열지 못함.

저장값과 비교: visit_required=true, join_method("통합회원 가입 → 최초 1회 방문(신분증) 대출증 발급") 모두 원문과 같음. 바꿀 것 없음. 302의 이름 "국립세종 도서관"은 띄어쓰기만 다름.

---

## 2. 군산시립도서관 — ids 45 (KYOBO), 262 (YES24), 306 (BOOKCUBE)

**판정: OPEN_ONLINE**

경로
- 누구나: 도서관 홈페이지 회원가입(약관 동의 + 휴대폰/아이핀 본인인증) = 준회원 → 전자책 이용. 방문하지 않아도 됨.
- 종이책 회원증(정회원)만 지역 조건이 있음: 군산시 주민등록자는 온라인 정회원 인증, 군산 소재 직장인·재학생은 방문.

원문
- 전자책 페이지: "홈페이지 회원가입자는 언제 어디서나 자유롭게 전자책을 이용할 수 있습니다.", "이용대상 군산시립도서관 홈페이지 회원가입자" — https://lib.gunsan.go.kr/web/menu/10017/contents/40001/contents.do
- 회원유형: "준회원 도서관 홈페이지 가입 회원 … 문화행사 신청, 게시판 글쓰기, 전자책, 오디오북 등", 준회원 가입 "① 개인정보 수집, 이용약관 동의 ② 본인 인증 온라인(인터넷)", 정회원 "온라인 정회원 인증 ※ 주민등록상 군산시 거주자", "방문 발급 대상 : 군산시 소재 직장인, 재학생을 포함한…". 준회원에는 지역 조건 없음. — https://lib.gunsan.go.kr/web/menu/10030/contents/40007/contents.do
- 교보 로그인 펼침 글(https://ebook.gunsan.go.kr:446/elibrary-front/member/memberLogin.ink): 기본 문구("아이디/비밀번호는 이용하시는 도서관 담당자에게 문의…"). 알 수 있는 것 없음.

저장값과 비교: visit_required=false와 join_method 모두 맞음. 바꿀 것 없음.

---

## 3. 금산군공공도서관 — id 267 (YES24)

**판정: OPEN_VISIT** (주의: 지역 조건일 가능성이 있음)

경로
- 원문상 누구나(중고생, 성인): 홈페이지 가입 신청 → 본인이 직접 방문해 신분증 내고 회원카드 발급 → 전자책은 "이름 + 도서대출회원번호"로 로그인. 온라인만으로 끝나는 길은 없음.

원문
- 이용안내: "회원가입 자격 : 중고생, 성인", "회원증 발급 : 본인 직접 방문 신분증 제시후 회원카드 발급" — https://library.geumsan.go.kr/0204/content/guide/?tab=0000000017
- 전자도서관: "회원 본인 이름과 도서대출회원번호 입력 후 사용가능합니다." — https://library.geumsan.go.kr/0106/content/ebook/
- 회원가입 화면(iframe, https://library.geumsan.go.kr:444/member/index.php): "도서관 회원증이 없는 사람은 이 페이지에서 가입신청 후 도서관 대출반납데스크에서 … 도서관 회원증을 발급 받을 수 있습니다.", 구비서류 "일반/학생 : 거주 및 본인확인이 가능한 신분증 또는 재직(재학) 증명서".
- 머리 메뉴 "거주지 확인"은 회원가입 페이지(`/user/signup/`)로 가는 링크일 뿐이고 설명 글은 없음.

주의: 구비서류에 "거주 … 확인", "재직(재학) 증명서"가 있어 금산군 거주·재직·재학 조건을 뜻할 수 있지만, 지역 이름은 원문 어디에도 없습니다. 원문대로면 OPEN_VISIT입니다.
저장값과 비교: visit_required=true와 join_method 모두 맞음. 지금 join_caution은 "거주지 확인" 메뉴를 말하고 있는데, 근거로는 구비서류 문구("거주 및 본인확인이 가능한 신분증 또는 재직(재학) 증명서")가 더 낫습니다.

---

## 4. 김천시립도서관 — id 313 (BOOKCUBE)

**판정: OPEN_ONLINE** (주의: 원문이 모호함)

경로
- 누구나(만 14세 이상): 홈페이지 회원가입(일반회원, 본인확인) → 전자책 앱에서 김천시립도서관을 고르고 홈페이지 계정으로 로그인. 원문은 전자책 대상을 그냥 "김천시립도서관 회원"이라고만 함.
- 종이책 회원증은 방문해서 받으며, 구비서류 표에 김천시민, 김천 소재 직장인, 김천 소재 대학교 이상 재학생, 외국인등록자만 있음.

원문
- 전자자료: "이용대상 : 김천시립도서관 회원", "앱에서 김천시립도서관 선택하여 로그인", "아이디, 비밀번호 분실 시에는 김천시립도서관 홈…" — https://www.gcl.go.kr/page/10080/10094.tc
- 회원가입안내: "회원가입 절차 및 회원증 발급 홈페이지 회원가입 후 도서관 방문 (하단 구비서류 지참)", 구비서류 구분 "김천시민(성인) … 김천시 소재 직장인 … 김천시 소재 대학교 이상 재학생 … 외국인등록자" — https://www.gcl.go.kr/page/10049/10066.tc
- 홈페이지 회원가입: "만 14세 이상 일반 고객님을 위한 회원가입 절차입니다. 일반회원 가입하기"(회원유형확인 → 약관동의 → 본인확인). 지역 조건 없음. — https://www.gcl.go.kr/page/10122/59.tc
- 북큐브 사이트(http://ebook.gcl.go.kr:8081/FxLibrary/): "도서관에 로그인 후 이용해주세요."

주의: "김천시립도서관 회원"이 회원증 소지자를 뜻한다면 김천 지역 제한(REGION, 방문)이 됩니다. 원문은 이 점을 말하지 않습니다. 4-8 규칙 3을 따르면 누구나 + 주의입니다.
저장값과 비교: visit_required=false, join_method, join_caution 모두 원문과 맞음. 바꿀 것 없음.

---

## 5. 김해시 통합도서관 — id 315 (BOOKCUBE)

**판정: OPEN_VISIT**

경로
- 누구나("전 국민"): 김해시립도서관에 방문해 신분증 내고 신청서 작성 → 회원증 발급 → 그 회원으로 전자책 로그인. 원문상 온라인으로 가입하는 길은 없음(작은도서관은 가입만 받고 회원증은 시립도서관에서 발급).

원문
- 회원가입안내: "회원가입 절차 step 1 도서관 방문 (신분증 및 필요서류 지참) step 2 본인확인 및 신청서 작성 step 3 회원가입 완료 회원증 발급", "회원가입 자격 : 전 국민", "작은도서관 : 회원가입만 가능(회원증은 시립도서관에서 발급)" — https://lib.gimhae.go.kr/00076/00077.web
- 전자도서관 서비스 페이지(https://lib.gimhae.go.kr/01561/01696/01697.web)에는 링크만 있고 자격 글은 없음. 구독형 오디오북 안내에는 "이용대상 김해통합도서관 회원"이라고 되어 있음(https://lib.gimhae.go.kr/01561/01696/01699.web).
- 북큐브 사이트(http://elib.gyeongnam.go.kr:8000/FxLibrary_gh/): "김해시립도서관 도서관에 로그인 후 이용해주세요."

저장값과 비교: visit_required=true와 join_method 모두 맞음. 바꿀 것 없음.

---

## 6. 울산동부도서관 전자책도서관 — id 361 (BOOKCUBE)

**판정: REGION (울산 거주·재직·재학) — 단, 원문끼리 서로 다름**

경로
- 전자도서관 페이지 기준: 자료대출회원이어야 함. 홈페이지 가입 후 자료실에 방문해 대출회원증 발급. 대출회원증 대상은 울산시민, 그리고 울산 소재 학교 학생과 직장인(재직·재학증명서 필요).
- FAQ 기준: 전자책 "대출자격: 도서관 홈페이지 회원". 이 문구대로면 홈페이지 가입(본인인증)만으로 누구나 됩니다.

원문
- 전자 도서관 페이지: "자료대출회원 가입 후 이용 가능합니다. 전자책은 1인 3권 대출 가능합니다." — https://www.usdl.or.kr/pageCont.do?menuNo=5020000
- 회원가입 1단계 화면: "회원가입 후 회원증을 발급받아야 도서 대출이 가능합니다." — http://kcms.usdl.or.kr:9090/kcms/Membership/step_01/MA
- FAQ(1쪽): "[대출/반납] 전자책은 어떻게 이용하나요? ▶대출자격: 도서관 홈페이지 회원 … 홈페이지에서 아이디/패스워드를 입력하여 로그인" — https://www.usdl.or.kr/pageCont.do?menuNo=6020000
- FAQ(2쪽): "[회원가입] 울산시민이 아니어도 자료 대출회원증을 만들 수 있나요? 주민등록상 주소지가 지방일 경우 울산 소재 학교에 다니는 학생, 울산 소재 직장인 의 경우 대출회원증을 발급 받아 이용할 수 있습니다." / "울산동부도서관 홈페이지 … 회원가입을 하신후 해당자료실 안내데스크에서 회원증을 발급받으시면 됩니다." — https://www.usdl.or.kr/pageCont.do?menuNo=6020000&pageIndex=2

저장값과 다른 점: 지금은 누구나, visit_required=false, join_method "홈페이지 신규회원 가입(본인인증) → 로그인 후 [전자책도서관]에서 대출"입니다. 전자도서관 페이지("자료대출회원")를 따르면 울산 지역 제한 + 방문(visit_required=true)으로 바뀌어야 합니다. FAQ("홈페이지 회원")를 따르면 지금 값이 맞습니다. **사람이 정해야 함.** 이 그룹에서 지금 값이 틀렸을 가능성이 가장 높은 곳입니다.

---

## 7. 통영시립도서관 — id 152 (KYOBO)

**판정: OPEN_VISIT**

경로
- 누구나(대한민국 국민): 홈페이지 가입(휴대폰/아이핀 본인인증) → 도서관에 방문해 신분증 내고 회원증 발급 → 홈페이지 로그인 → 경남통합전자도서관에서 전자책. 전자책에 회원증이 꼭 필요한지는 원문에 없고, 원문에 있는 가입 단계는 방문뿐임(4-10 규칙 4).

원문
- 관외대출 회원가입: "도서관 회원증 발급 자격요건 : 모든 시민 발급방법 : 도서관 방문 시 자격 요건을 증명할 수 있는 신분증을 가지고 발급처 방문", "회원가입시 개인정보보호를 위한 본인인증(휴대폰, 아이핀) 절차 필요" — https://www.tongyeonglib.or.kr/library/index.php?g_page=library&m_page=library02_02
- 자주하는 질문: "책을 빌려가기 위해서는 도서관 회원증을 발급받으셔야 합니다 . 회원증을 발급받을 수 있는 자격은 대한민국 국민이면 가능합니다 . 신분증을 가지고 도서관에 방문하시면 즉시 회원증을 발급받으실수 있으며" — https://www.tongyeonglib.or.kr/library/index.php?g_page=community&m_page=community05
  → 9/29에 "모든 시민"이 통영시민인지 누구나인지 모른다고 남겼던 물음은 이 FAQ로 풀립니다: 대한민국 국민.
- 전자도서관 이용안내: "통영시립도서관 홈페이지 로그인 → 경남통합전자도서관(전자책) 접속 → 대출하기" — https://www.tongyeonglib.or.kr/library/index.php?g_page=ebook&m_page=ebook02
- 리브로피아 인증: "도서관 회원번호(독서 회원카드에 있는 숫자 14자리)를 입력하여 인증" — https://www.tongyeonglib.or.kr/library/index.php?g_page=ebook&m_page=ebook01
- 9/29에 적었던 "이용대상 : 통영시립도서관 통합회원(회원증 또는 모바일 회원증 이용)" 문구는 이번에 연 페이지들에서는 다시 찾지 못함.
- 교보 로그인 펼침 글(https://elib.gyeongnam.go.kr:8123/…/memberLogin.ink): 기본 문구.

저장값과 비교: visit_required=true와 join_method 모두 맞음. join_caution "원문에 지역 조건은 없음"도 맞고, FAQ의 "대한민국 국민이면 가능"을 근거로 덧붙일 수 있습니다.

---

## 8. 창원시립 의창도서관 — ids 148 (KYOBO), 387 (BOOKCUBE)

**판정: OPEN_VISIT 쪽으로 봄** (누구나는 확실, 온라인만으로 되는지는 원문으로 확정 못 함)

참고: 홈페이지 `lib.changwon.go.kr/uc/`는 지금 제목이 "창원중앙도서관"입니다. 페이지 소스에 `lib_name.value = "의창도서관"`(lib_id 148035)이 있어 같은 도서관으로 보입니다(이름이 바뀐 것으로 보임).

경로
- 누구나("도서관 회원가입을 희망하는 개인"): (가) 누리집 회원가입, (나) 도서대출회원가입 신청(온라인, 휴대폰/아이핀 본인인증, "만 14세 이상 내국인, 외국인"), (다) 자료실에서 신분증과 회원가입신청서로 회원증 발급.
- 전자책은 "도서관에 로그인 후 이용"이라고만 되어 있고, 필요한 회원 등급은 원문에 없음. 온라인 도서대출회원 "신청"만으로 끝나는지, 회원증을 받으러 방문해야 하는지도 원문에 없음.

원문
- 회원가입안내: "개인회원 가입대상 도서관 회원가입을 희망하는 개인", 회원증 발급 구비서류 "일반인 신분증 회원가입신청서 개인정보수집 및 이용동의서", 발급처 "종합자료실(2층)" — https://lib.changwon.go.kr/uc/use/memberguide.html
- 누리집 회원가입: "도서대출회원가입 을 원하시는 분들은 오른쪽 상단에 있는 도서대출회원가입 을 이용 하시기 바랍니다.", "도서대출회원가입 : 하나의 도서관 회원증으로 전국 도서관 도서대출 서비스를 제공받는 서비스" — https://lib.changwon.go.kr/cl/mypage/join.html
- 도서대출회원가입(통합회원서비스): "도서회원가입신청", "일반회원 (만 14세 이상) 만 14세 이상 내국인, 외국인", I-PIN·휴대폰 본인인증 — https://searchlib.changwon.go.kr/dls_lu/member/
- 홈페이지 전자책 메뉴: "로그인 후 사용해 주세요." 경고만 뜨고 이동(pop_ebook.html에는 "전자책 바로가기" 링크만 있음). 북큐브(https://elib.gyeongnam.go.kr:8001/FxLibrary_cw/): "창원시립도서관 도서관에 로그인 후 이용해주세요." 교보 펼침 글(https://elib.gyeongnam.go.kr:8122/…/memberLogin.ink): 기본 문구, 화면 아래 도서관명 "창원시립 의창도서관".

저장값과 다른 점: 같은 도서관인데 두 줄의 값이 서로 다릅니다. 148은 visit_required=true("책이음회원가입 신청(본인인증) → 구비서류 지참해 도서관 방문")이고, 387은 false("도서대출회원가입(온라인 본인인증) 또는 도서관 자료실 방문")입니다. 이번에 연 원문에는 "온라인 신청 후 방문"이라는 문장도, "온라인으로 끝"이라는 문장도 없습니다. 회원증 발급처가 자료실로만 적혀 있어 방문으로 보는 쪽이 원문에 더 가깝습니다. 두 줄을 한 값으로 맞춰야 합니다(사람 판단 필요). 자격(누구나)은 두 줄 모두 맞습니다.

---

## 요약

| 기관 | ids | 판정 | 저장값과 차이 |
|---|---|---|---|
| 국립세종도서관 | 43, 263, 302 | OPEN_VISIT | 없음 |
| 군산시립도서관 | 45, 262, 306 | OPEN_ONLINE | 없음 |
| 금산군공공도서관 | 267 | OPEN_VISIT (지역 가능성) | 없음. 주의 근거로 구비서류 문구가 더 나음 |
| 김천시립도서관 | 313 | OPEN_ONLINE (모호) | 없음 |
| 김해시 통합도서관 | 315 | OPEN_VISIT | 없음 |
| 울산동부도서관 | 361 | REGION 울산 (원문끼리 다름) | 누구나/방문 없음 → 지역 제한 + 방문일 수 있음. 사람 판단 필요 |
| 통영시립도서관 | 152 | OPEN_VISIT | 없음(FAQ로 "대한민국 국민" 확인) |
| 창원시립 의창도서관 | 148, 387 | OPEN_VISIT 쪽으로 봄 | 148(방문)과 387(방문 없음)이 서로 다름. 맞춰야 함 |


---

# g4 재검증 결과 (2026-10-19)

조사 방법: 모든 페이지를 직접 열어 읽음(User-Agent `chaekcheck/0.0.1`, 호스트당 1건, 1.2초 간격). 검색 요약은 쓰지 않음. 이전 기록(`docs/library-research/join-classification-2026-09-29.tsv`, `join-classification-new42-2026-09-29.tsv`, `yes24-classification-2026-09-29.tsv`)과 대조함.

| 기관 | ids | 판정 | 한 줄 이유 |
|---|---|---|---|
| 서해·검단구립도서관(석남어린이) | 80 | OPEN_ONLINE | 전자책 "준회원 이상", 준회원 = 홈페이지 가입(휴대폰·아이핀)만, 지역 제한 없음 |
| 양주시도서관 | 94 | OPEN_VISIT | 자격 전국(대한민국 주민등록자), 절차 "홈페이지 가입 후 방문"; 온라인정회원신청은 로그인 뒤라 대상 확인 불가 |
| 제주 공공도서관 통합(우당·한라) | 139, 140, 1109, 2267 | OPEN_ONLINE | "가입대상 : 누구나" + Lib-Homepass "도서관 방문 없이 어디서나 도서대출 정회원 가입" |
| 의정부시도서관 | 179, 222 | OPEN_VISIT | 가입대상 "대한민국 국민과 외국인등록자", 전자책은 정회원(회원증)만, 회원증은 방문 발급 |
| 포천시도서관 | 211 | OPEN_VISIT | "지역에 제한 없이 누구나", 전자책 "대출회원 (홈페이지 회원 이용불가)", 절차는 방문 |

---

## 1. 서해·검단구립도서관 (석남어린이도서관) — id 80 (KYOBO)

**판정: OPEN_ONLINE**

경로
- 누구나: 도서관 홈페이지 회원가입(휴대폰 또는 아이핀 본인인증) = 준회원 → 전자책 이용 가능. 방문 없음.
- (참고, 종이책) 정회원(회원증)은 인천 거주·재직·재학자만. 인천 주민등록자는 비대면 자격확인으로 온라인, 그 밖의 인천 직장·학교는 방문.

원문
- 전자도서관 로그인 화면 `https://ebook.issl.go.kr/elibrary-front/member/memberLogin.ink`: 펼침 글은 교보 기본 문구("아이디/비밀번호는 이용하시는 도서관 담당자에게 문의…"). 도서관명 "인천 서해검단구립도서관 전자도서관".
- 전자책 안내 `https://www.issl.go.kr/conts/view.do?mnidx=1572`: 구독형·소장형 모두 "이용 대상 / 준회원 이상", "(로그인 : 도서관홈페이지 아이디/비밀번호 )".
- 회원유형 및 가입절차 `https://www.issl.go.kr/conts/view.do?mnidx=1581`:
  - 준회원 "회원정의 : 홈페이지만 가입한 회원", "가입방법 : 홈페이지에서 회원가입(휴대폰 또는 아이핀 인증 필요)", "제공 서비스 : 전자책, 오디오북, …"
  - 정회원 "가입대상 : 인천에 거주하는 시민 또는 인천 소재 직장 및 학교 재직(학)자" (종이책 대출 등)
  - 준회원에는 지역 문구 없음.

저장값과 차이: 없음(visit_required=f, join_method 맞음). 단, 교보 사이트 이름은 지금 "서해검단구립도서관"(기관 이름 변경은 별건).

## 2. 양주시도서관 — id 94 (KYOBO)

**판정: OPEN_VISIT** (자격은 누구나, 확인된 경로는 방문뿐)

경로
- 누구나(대한민국 주민등록자, 경기 거주 재외동포, 거소신고자·외국인등록자): 홈페이지 회원가입 → 구비서류(사진 있는 신분증) 지참 도서관 방문 → 회원증.
- 온라인정회원신청 메뉴(`/ojlake/menu/11250/program/30072/untact.do`)가 있으나 로그인해야 열림("로그인 후 이용이 가능합니다." 알림 후 로그인 화면으로 이동). 누가 쓸 수 있는지(경기도민만인지 등) 원문 확인 못 함.
- 전자책에 필요한 회원 등급: 원문 없음. 공개 페이지에 전자책 이용안내 글이 없음(머리 "전자책" 버튼은 `mmbrLnkg.ink` SSO로 바로 감).

원문
- 로그인 화면 `https://ebook.yangju.go.kr:444/elibrary-front/member/memberLogin.ink`: 교보 기본 문구("…도서관 홈페이지 에서 확인하시거나…"), 도서관명 "양주시도서관".
- 회원가입 `https://www.libyj.go.kr/ojlake/menu/10043/contents/40011/contents.do`: "회원가입 자격 / 대한민국내에 주민등록이 되어 있는 사람 / 경기도에 거주 중인 재외동포 / 국내거소 신고자 및 외국인 등록자", "회원가입 절차 / 홈페이지 회원가입 후 도서관 방문(구비서류 지참)".
- 시행규칙 `https://www.libyj.go.kr/ojlake/menu/10030/contents/40008/contents.do` 제4조: "회원으로 가입하고자 하는 자는 시립도서관 홈페이지에 회원 가입 후 다음 각 호의 서류를 제시하여야 한다."
- 대출/반납 `…/menu/10044/contents/40012/contents.do`: "대상 / 양주시립도서관 대출회원" (종이책).

저장값과 차이: visit_required=f. 4-10 규칙 4(전자책 등급 원문 없음 + 방문 단계만)로 보면 true. f로 둔 근거는 "온라인정회원신청 메뉴 있음"뿐인데, 그 메뉴는 로그인 뒤라 대상·방법을 원문으로 확인할 수 없음 → 판단 필요(사람이 로그인해 보거나 도서관 문의). join_method 문구 자체는 원문과 맞음.

## 3. 제주특별자치도 공공도서관 통합회원 (우당·한라) — ids 139, 140, 1109, 2267

**판정: OPEN_ONLINE**

경로
- 누구나: 제주도 홈페이지 통합아이디(웹회원) → Lib-Homepass(`https://hallalib.jeju.go.kr:9443/`)에서 본인명의 휴대폰 또는 I-PIN 인증으로 "도서관 방문 없이" 정회원 가입·대출증번호 발급 → 나만의도서관에서 대출회원 인증 → 전자책.
- 또는 도서관 회원가입 PC에서 본인확인 후 통합도서회원증 발급(방문).

원문
- 회원가입안내 `https://www.jeju.go.kr/lib/intro/member.htm`: "제주특별자치도 소속 15개 공공도서관은 통합회원으로 관리됩니다.", "가입대상 : 누구나", "대출회원 : 15개 공공도서관에서 통합도서회원증을 발급 받은 회원", "웹/대출회원 : 웹회원이 대출회원인증을 통해 웹회원과 대출회원이 동일인임을 확인받은 회원". 머리 "비대면 회원가입" 링크 → `https://hallalib.jeju.go.kr:9443/`.
- Lib-Homepass `https://hallalib.jeju.go.kr:9443/`: "제주특별자치도 공공도서관은 도서관 방문 없이 어디서나 도서대출 정회원 가입이 가능한 [Lib-Homepass] 서비스 를 제공합니다.", "※ 준비사항: 본인명의휴대폰 또는 I-PIN인증".
- 전자도서관 안내 `https://www.jeju.go.kr/lib/elib.htm`: "* 전자책 이용순서 : 홈페이지 나만의도서관에서 대출회원 인증(최초 이용 시만 인증)-전자도서관-전자책-…". 전자책 링크: 한라(구독형) `https://hallalib.dkyobobook.co.kr/frontapi/mmbrLnkg.ink`, 한라(소장형) `https://hallalib.jeju.go.kr:8083/FxLibrary/…`(북큐브), 제주시통합 `https://libebook.jejusi.go.kr/FxLibrary`.
- 로그인 화면: 139 `https://libebook.jejusi.go.kr:442/elibrary-front/member/memberLogin.ink` 펼침 글 없음, 도서관명 "제주우당도서관". 2267 `http://hallalib.jeju.go.kr:8081/` YES24 화면 열림(로그인/회원가입 버튼만, 안내 글 없음). 140 `hallalib.dkyobobook.co.kr`, 1109 `hallalibjeju.dkyobobook.co.kr`은 이번에 연결 시간 초과(WinError 10060)로 열지 못함.

저장값과 차이
- 1109, 2267: visit_required=t → 원문상 비대면 길(Lib-Homepass)이 있으므로 f여야 함. join_method에 Lib-Homepass(비대면) 경로가 빠져 있음(139/140 문구처럼 넣어야 함).
- 139, 140: 저장값(f, Lib-Homepass 포함)과 맞음.
- 참고: 1109(`hallalibjeju`)는 140과 같은 한라 구독형의 옛 주소로 보임. 제주 안내 페이지는 지금 `hallalib.dkyobobook.co.kr`만 연결(`library-site-issues.md` 7절 결정과 같은 상황). 2267 YES24 `:8081`도 안내 페이지에 링크 없음(소장형은 북큐브 `:8083`). 중복·옛 주소 여부 확인 필요.

## 4. 의정부시도서관 — ids 179 (KYOBO), 222 (YES24)

**판정: OPEN_VISIT**

경로
- 누구나(대한민국 국민과 외국인등록자): 도서관 홈페이지 회원가입(준회원) → 신분증 지참 도서관 1층 안내데스크 방문 → 회원증(정회원) → 전자책. 온라인·비대면 정회원 경로 원문 없음.

원문
- 로그인 화면 `https://ebook.uilib.go.kr/elibrary-front/member/memberLogin.ink`: 교보 기본 문구, 도서관명 "의정부시도서관".
- 회원가입 `https://www.uilib.go.kr/main/html.do?menu_idx=33`: "가입대상 / - 대한민국 국민과 외국인등록자", "회원증 발급 / - 도서관 홈페이지에 회원가입 후 도서관 1층 안내데스크에 방문하여 구비서류 제출".
- 전자책 `https://www.uilib.go.kr/main/html/ebook.do?menu_idx=50`: "이용대상 / 의정부시 도서관 회원 및(정회원, 회원증 발급자)", "준회원은 신분증 지참 후 도서관에 방문하여 회원증을 발급하시기 바랍니다."
- 222 `http://ebook.uilib.go.kr:8091/`: YES24 화면 열림, 안내 글 없음.

저장값과 차이: 없음(visit_required=t, join_method 맞음). "누구나 가입"이지만 방문 필수.

## 5. 포천시도서관 — id 211 (YES24)

**판정: OPEN_VISIT**

경로
- 누구나: 홈페이지 회원가입(휴대폰/아이핀 인증) → 구비서류(신분증) 지참 도서관 방문 → 회원카드(대출회원) → 전자책. 홈페이지 회원만으로는 전자책 불가.

원문
- 회원가입 `https://lib.pocheon.go.kr/center/menu/11263/contents/40001/contents.do`: "회원가입 자격 / 연령 및 지역에 제한 없이 누구나 가입 가능", "회원가입 절차 / 홈페이지 회원 가입 후 도서관 방문 (구비서류 지참)".
- 전자책 `https://lib.pocheon.go.kr/center/menu/10120/contents/40121/contents.do`: "이용대상 / 대출회원 (홈페이지 회원 이용불가)", "회원증을 발급받으신 포천시도서관 회원은 언제 어디서나 자유롭게 전자책을 이용할 수 있습니다."

저장값과 차이: 없음(visit_required=t, join_method 맞음).


---

