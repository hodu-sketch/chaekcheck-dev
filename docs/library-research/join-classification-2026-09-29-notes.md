# Join-eligibility classification — notes (2026-09-29)

Output: `join-classification.tsv` (162 rows, 13 columns, `NULL` for empty).
Built from `dec/b*.py` by `build_tsv.py`. Fetches: curl `-A chaekcheck/0.0.1`, ≥1.2 s per host, no cert bypass; cache in `fx/`.

## Counts

| verdict | rows |
|---|---|
| REGION | 122 |
| OPEN | 13 (17, 43, 44, 45, 60, 61, 65, 80, 84, 94, 139, 140, 148) |
| OPEN_CAUTION | 1 (2 고흥) |
| UNVERIFIED | 26 |

## Disagreements with current rules (seed-sources.tsv `rules`)

| id | name | current | new | why |
|---|---|---|---|---|
| 17 | 강북문화정보도서관 | R/W/S 11 | OPEN | "『웹회원』등급은 홈페이지 로그인, 전자책서비스 ... 이용만 가능" + "웹회원은 누구나 가입 가능(지역 구분 없음)" |
| 93 | 양산도서관 | NATIONWIDE | R/W/S 4808 (+책이음 extra) | site is 양산시립 (Kyobo footer "양산시 전자도서관"), not 경남교육청 양산도서관; 양산시립 "회원가입 대상 주민등록상 양산시 거주 시민, 양산시 관내의 직장인과 학생" |
| 29,33,51,64,72,145,147 | 경남교육청 group | R 48 | R/W/S 48 | original also lists "경상남도 내 직장 또는 학교에 재직/재학 중인 사람" |
| 38 | 과천 | R/W/S 41 + R/W/S 4111 | R 41, W/S 4111 | "주민등록상 경기도 내 거주자, 과천시 소재 학생 또는 직장인" |
| 48 | 금천 | R/W/S 11 + R/W/S 4106 | R/W/S 11 + R 4106 | 광명시 is residence only |
| 110 | 울릉 | R/W/S 47 (kept) | same | same site says 경상북도 소재 (규정) vs 울릉군 소재 (대출회원가입) — human check |

Rows with a summary but no rule: 1 강서 now has R/W/S 11 (from the SPA bundle text "회원가입대상을 현 주소지가 서울특별시..." and e-book "강서구립도서관 회원카드 소지자").

## Plan findings corrected by the originals
- 99 연천, 137 정읍, 156 하남, 42 광진: plan said "가입 지역 없음" from the e-book line only; the loan-card pages are regional.
- 117 의왕: plan "방문 정회원 누구나" — true for book loans, but "전자책(오디오북) 서비스: ... 회원가입자는 경기도민만 가능" → R 41 (current kept).
- 104 예산: plan "전자책은 충남도민 인증으로도" — that notice is about 충남도서관's e-books, not this site → UNVERIFIED.
- 68 밀양: plan "재직 (경남)" — original is 밀양시 only.
- 35 고창공공: 교육청 e-library page says 전북 R/W/S, but 고창도서관's own 자격 page says 고창군 → used 고창군 (5213); human check. Other 전북교육청 libraries kept 전북 (their own pages have no 자격 text). The plan's gecc sentence ("책이음 통합 시스템 가입이 가능한 국민") is 김제학생교육문화관's own page, not used.
- 58 대구광역시교육청 도서관: dge.dkyobobook.co.kr is "대구광역시교육청 전자도서관(본청)", not 대구전자도서관 구독형 (daegu.dkyobobook.co.kr) → UNVERIFIED.
- 151 충북중앙도서관: Kyobo footer "충청북도교육도서관"; the plan's source lib.chungbuk.go.kr is the 도청 사이버도서관 → used cbelib.go.kr (R 43, W 43).

## Needs a human decision
1. 전남광주통합특별시 = SIDO 12. 75/131 광주 서구 "전남광주통합특별시에 거주 또는 재직" recorded as R/W 12. Confirm the merged SIDO is meant (not old 광주광역시 = 1206–1210).
2. 11 대구: "그밖에 대구통합도서관 회원가입 자격을 갖춘 사람(경산시, 칠곡군, 의성군 주민 및 재학생)" → added R 4710/4718/4711; "재학생" meaning unclear.
3. 120 익산 소장형: 준회원(웹회원) can borrow e-books, but the 회원가입 "가입대상" is 익산 R/W/S → kept REGION. If web sign-up is not checked, it is OPEN_CAUTION.
4. 28 경북도서관: 경북도민인증 route is R 47; the visit loan-card page has no region text → only R 47 recorded.
5. 40 광산, 62 동작, 130 광주 북구, 133 광주교육청(소장형): loan-card pages list ID documents only, no region and no "누구나". Could be OPEN (visit); left UNVERIFIED.
6. 152 통영: "자격요건 : 모든 시민" — 통영시민 or anyone?
7. 18 강진: web 회원 "누구나", 도서관 회원 강진군; e-book page silent → OPEN_CAUTION or REGION.
8. 116 음성: "충청북도나 인접 시·군" — 인접 시·군 not named; put in extra_condition_text.
9. 83 성동: 대출증발급 page contains 무주군 text (copy error on the site).
10. 127 인천시 통합전자도서관: used 서해·검단구립 page (인천 공공도서관 회원, 대출자번호) → R/W/S 28.
11. 19 거제시청 전자책도서관: Kyobo name/address is 거제시청 (city hall) — maybe staff only.
12. 59 대구학생전자도서관 (service suspended since 2025-07-22, login impossible) and 131 서구 구독형 (suspended 2026-09 to 2027-01): consider `enabled=false`.
13. 140 제주한라: seed host hallalibjeju.dkyobobook.co.kr differs from the one the 제주 e-library page links (hallalib.dkyobobook.co.kr).

## OPEN rows and why
- explicit web-member e-book: 17 강북, 45 군산 ("홈페이지 회원가입자"), 80 석남/서해검단 ("준회원 이상").
- loan card open to all: 60 도봉 ("대한민국 국민"), 61 동대문 ("가입 대상 제한없음"), 65 목포 ("대한민국 국민, 등록외국인"), 84 성북 ("제한없음(누구나)"), 94 양주 ("대한민국내에 주민등록이 되어 있는 사람"), 148 창원 ("도서관 회원가입을 희망하는 개인"), 139/140 제주 ("가입대상 : 누구나").
- national: 43 국립세종 (대출증, visit), 44 국회도서관 (장기열람증, 18세 이상/대학생 in extra, visit).

## Could not open / no text (UNVERIFIED reasons)
| id | reason |
|---|---|
| 6 부산남구 | library.bsnamgu.go.kr certificate expired (SEC_E_CERT_EXPIRED) |
| 46 군포, 55 노원, 129 장성 | JavaScript apps; content not in HTML/bundle (강서·서초·안산 bundles did contain text and were used) |
| 54 남해화전 | homepage has no 자격 text; its e-book link is another platform |
| 56 노형꿈틀, 138 서부방음 | not listed on 제주 e-library page; operator unknown |
| 58, 59 대구 | see above |
| 67 문수 작은도서관 | Kyobo footer name only; homepage not found |
| 69 박정희대통령기념도서관 | not a public library; no join text |
| 90 아산 | join steps only, no 자격 (join_method/url filled) |
| 104 예산 | menu content loaded dynamically |
| 132 광주도서관 | citylib.gwangju.kr http 000, lib.gwangju.kr no DNS |
| 134 전남도서관 | moved to jnlib.jeonnam-gwangju.go.kr; join page text not readable |
| 158 함양 | only a local-news article ("군민이면 누구나") |
| 160 해밀 | ebook.hmlib.or.kr http 000; source was an app-store link |
| 18, 19, 40, 52, 62, 83, 130, 133, 152 | pages open but not decisive (see notes column) |

Other access notes: 92 안성 (www.anseong.go.kr connect timeout this run; 자격 text taken from the plan's 2026-09-28 original quote), 4 부산대표 (ebook root http 000; used 부산도서관 회원가입 page), 57 단양 (seed URL "사이트 정보 없음"; used www.danyang.go.kr/lib), 155 포항 (www.phlib.or.kr no DNS; used phlib.pohang.go.kr).
Pages read from SPA bundles (not rendered HTML): 1 강서, 79 서초, 91 안산.
