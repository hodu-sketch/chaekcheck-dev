# 옛 YES24·전자책 주소가 안 되는 도서관의 지금 주소 (2026-09-29)

범위: yes24-report.md "확인했지만 넣을 수 없는 곳" + yes24-search.tsv의 OK가 아닌 줄 전부(49줄). 도서관 본 홈페이지의 전자책·전자도서관 메뉴에서 출발(4-2). 저장소 파일은 고치지 않음.

- 가져오기: `mv.py`(이 폴더, Windows curl, UA `chaekcheck/0.0.1`, 인증서 확인 켬, 호스트당 1.2초, 캐시 `mvcache/`). 경남교육청 검색 1건만 curl POST.
- 판정: 서비스 JDK 25 + `CheckKyoboSites.java`(교보 8곳) / `CheckYes24Sites.java`(YES24 3곳), 검색 → 보유(빈 검색).
- 웹 검색은 공식 주소를 찾는 데만 씀(경남통합, 경남교육청, 인천통합, 성동영어, 광주동구, 강원교육청 회원 안내).
- 결과 파일: `moved-sites.tsv`(53줄 = 옛 줄 49 + 새로 찾은 구독형 4), `moved-classification.tsv`(10줄, `join_overrides.tsv`와 같은 칸), 조사 메모 `moved-notes.txt`, 도구 출력 `moved-kyobo-search.tsv`, `moved-kyobo-holdings.tsv`, `moved-yes24-search.tsv`, `moved-yes24-holdings.tsv`.

## 수

| 상태 | 줄 | 뜻 |
|---|---|---|
| OK | 9 | 우리 도구로 읽힘, seed에 없음 |
| NEEDS_ADAPTER_CHANGE | 1 | 강원교육청: 포털 화면인데 검색 menu_idx가 다름 |
| NO_ADAPTER | 22 | 다른 업체·자체 시스템·로그인 필요(사이트로 치면 12곳, 아래) |
| IN_SEED | 6 | 지금 전자책 링크가 이미 seed에 있는 사이트 |
| ALREADY_FOUND | 1 | 전남교육청 미디어북 → yes24-report에서 이미 찾은 ebook.jne.go.kr:889x |
| NOT_FOUND | 8 | 홈페이지 전자책 메뉴가 로그인 뒤에만 주소를 보여 줌 |
| ERROR | 3 | 인천통합(연결 실패), 유성 북드림(도메인 없음), 울주 교육청(시간 초과) |
| OTHER | 1 | 성동구립영어도서관(교보 화면이 "도서관 정보 조회에 실패") |
| STOPPED | 1 | 대구학생전자도서관(서비스 중지, seed 59 끈 이유와 같음) |
| NOT_EBOOK | 1 | 양산 :8088은 열람실 현황 페이지 |

**찾았고 읽힘(OK 9, seed에 없음)**

| 이름 | 화면 | 주소 | 사랑 | 보유 | 가입 판정 |
|---|---|---|---|---|---|
| 연제만화도서관 | KYOBO_V1 | https://emanhwa.yeonje.go.kr | 20 | 1224 | REGION 부산 거주·재직·재학 |
| 연제만화도서관(YES24) | YES24_V2 | http://emanhwa.yes24library.com | 0 | 394 | REGION 부산(같은 원문) |
| 송파구전자도서관 | YES24_V2 | https://ebook.splib.or.kr | 159 | 12053 | REGION 서울·경기 (seed 85 재사용) |
| 송파구통합도서관(구독) | KYOBO_SUB | https://splib.dkyobobook.co.kr | 2235 | NULL | REGION 서울·경기 (seed 85 재사용) |
| 경남통합전자도서관(구독) | KYOBO_SUB | https://gnlib.dkyobobook.co.kr | 2326 | NULL | REGION 경남 (seed 30 재사용, 결정 필요) |
| 제주특별자치도한라도서관(구독) | KYOBO_SUB | https://hallalib.dkyobobook.co.kr | 2323 | NULL | OPEN (seed 140 재사용) |
| 광주통합도서관(구독) | KYOBO_SUB | https://libgen.dkyobobook.co.kr | 2253 | NULL | OPEN_CAUTION (override 133 옮김) |
| 기장군도서관(구독) | KYOBO_SUB | https://gijanglib.dkyobobook.co.kr | 2235 | NULL | REGION 부산 거주 (새로 조사) |
| 전북특별자치도 인재개발원 | KYOBO_V1 | https://hrdebook.jeonbuk.go.kr:444 | 114 | 6411 | UNVERIFIED (공공도서관인지 의문) |

- 구독형 5곳은 빈 검색 전체 건수가 0이라 `holdings_count` NULL(12절 규칙).
- 강원교육청 `https://ebook.gwe.go.kr/elib`: 화면·판정 글이 ELIB_PORTAL_V1과 같음("총 222 건", "대출 가능 여부: 대출 가능 / 대출 : 1 / 예약 : 0"), JDK로 TLS 연결됨. 단 검색이 `menu_idx=2`이고 adapter의 고정값 `menu_idx=10`은 500 오류 화면. 가입 판정 REGION 강원 거주·재직(새로 조사).

**다른 업체·자체 시스템(NO_ADAPTER, 사이트 12곳)**

| 사이트 | 옛 줄 | 종류 |
|---|---|---|
| 경남통합전자도서관 `elib.gyeongnam.go.kr` | 통영, 양산, 밀양, 경남대표 | 자체 시스템(`.es`), 목록은 열리지만 **검색은 로그인 필요** |
| 경남교육청 전자도서관 `ebook.gne.go.kr` | GNE, 함양, 남지 | 자체 PHP 포털. POST 검색이 로그인 없이 됨("전자책 ( 567 )"), 줄마다 공급사·보유·대출·예약·소속도서관 |
| 전남도서관 `jnlib.jeonnam-gwangju.go.kr/ebook` | 옥과(곡성), 전남도립 | 자체 시스템, 결과를 스크립트로 그림 |
| 충남교육청 통합전자도서관 `ebook.cne.go.kr` | 충남학생교육문화원, 충남서부평생학습관 | 자체 시스템(전남도서관과 같은 제품 `chnFunc.js`), 크레마클럽·밀리 등 연결 |
| 울산 전자도서관 포털 `library.ulsan.go.kr/lib/elib` | 울산도서관 | 검색이 로그인 없이 됨("총 287 건"), 대출 수량 없음, YES24 쪽 `elibrary.ulsan.go.kr`은 옛 ASP 로그인 화면 |
| 대구 동구 `digital.donggu-lib.kr` | 동구 통합 도서관 | YES24 **옛 ASP 화면**(EUC-KR, `/ebook/search_list.asp`, "62건", 보유 수량 있음) |
| 서울도서관 `elib.seoul.go.kr` | 서울도서관 | 4-3에 이미 기록 |
| 북큐브 `/FxLibrary` 4곳 | 제주시통합, 한라(소장형), 완주, 사상 | 북큐브 |
| YES24_V3(EUC-KR, V2 화면) 4곳 | 마포나루, 김천, 경주, 구미 | yes24-report 그대로(다시 부르지 않음) |

**못 찾음(NOT_FOUND 8)과 이유**: 포항·고령다산·레인보우영동·광주동구·서귀포·정읍·함평나비·창원 jrebook. 모두 홈페이지 전자책 버튼이 "로그인 후 이용"이라 로그인 전에는 연결 주소가 안 보입니다(주소를 추측해 부르지 않음). 포항·영동·정읍은 교보 사이트가 이미 seed에 있습니다(155, 190, 137). 창원은 홈페이지 "어린이전자책" 링크가 아직 없어진 도메인을 가리킵니다.

**이미 seed에 있음(IN_SEED 6)**: 칠곡(203), 충남도서관(164), 경북도서관(28), 수영구(86), 구수산→대구전자도서관 포털(11), 광주학생교육문화회관→광주통합 소장형(133). 옛 YES24 사이트는 없어지고 교보·포털만 남았습니다.

## 가입 판정(10곳, `moved-classification.tsv`)

- REGION 7, OPEN 1, OPEN_CAUTION 1, UNVERIFIED 1.
- 재사용: 송파 2줄(seed 85), 경남통합(구독)(seed 30), 한라(구독)(seed 140), 광주통합(구독)(`join_overrides.tsv` 133 사용자 결정). 재사용한 곳도 이번에 전자책 페이지 원문을 다시 열어 확인.
- 새로 조사: 연제만화 2줄("이용 대상 연제구 소속 책이음 회원" + 책이음 가입대상 "부산시에 주민등록이 되어 있는 분", "부산시 내 재직 중이거나, 학교에 재학 중인 분"), 기장(구독)("책이음 회원가입 가입대상 : 부산광역시 거주자(주민등록상 소재지)"), 강원교육청("이용대상 : 강원특별자치도교육청 통합도서관 정회원", "강원특별자치도민", "강원특별자치도 소재 직장 재직 중인 사람"), 인재개발원(UNVERIFIED).

## 사람이 정할 것

1. **새 adapter / 개발**
   - 강원교육청: `ElibPortalV1Adapter`의 `menu_idx`를 도서관마다 설정하게 할지(강원 = 2). 바꾸면 바로 넣을 수 있음.
   - 경남교육청 `ebook.gne.go.kr`(POST 검색, 로그인 없이 됨, 경남교육청 25개 도서관 YES24·북큐브·알라딘 책이 한곳에): 새 adapter를 만들지. 이미 seed에 있는 교보 사이트와 책이 겹침.
   - 울산 포털(검색 됨, 대출 수량 없음), 대구 동구 YES24 옛 ASP, 전남도서관·충남교육청 자체 시스템(같은 제품), 북큐브 4곳, YES24_V3 4곳.
   - 경남통합전자도서관은 검색에 로그인이 필요해 adapter로도 못 읽음(통영·양산·밀양·경남대표의 YES24 책).
2. **이름·중복**
   - 한라 구독형: 제주 안내가 지금 가리키는 곳은 `hallalib.dkyobobook.co.kr`("제주특별자치도한라도서관"), seed 140은 `hallalibjeju.dkyobobook.co.kr`. 둘 다 검색 OK. seed 주소를 바꿀지, 둘 다 둘지.
   - 연제만화도서관 YES24 `emanhwa.yes24library.com`: 살아 있지만(보유 394) 본 홈페이지에는 교보 링크만 있음. 넣을지.
   - 송파 YES24(`ebook.splib.or.kr`)·송파 구독, 광주 구독, 경남통합 구독, 기장 구독의 이름(seed 이름과 겹치지 않게, 예 "(구독)", "(YES24)").
3. **공공도서관인지**: 전북특별자치도 인재개발원(`hrdebook.jeonbuk.go.kr:444`, 옛 이름 "전북도청도서관") — 공무원 교육기관으로 보임. 넣지 않는 쪽 권고.
4. **가입 판정 애매**
   - 경남통합(구독): 원문 "이용 대상 : 경남대표도서관 이용자" — 정회원만인지, 준회원도 되는지 불명. seed 30 규칙(경남)을 그대로 씀.
   - 기장(구독): 전자책은 "도서관 아이디, 비밀번호로 로그인"만 말함. 홈페이지 가입이 누구나 되는지 원문을 못 찾아 책이음 가입대상(부산 거주)으로 둠. 누구나+주의(4-8 규칙 3)로 볼지.
   - 광주통합(구독): 사용자 결정(133: 누구나+방문+주의)을 옮겼지만 구독형은 광주시민 비대면 경로가 있어 `visit_required=false`로 둠.
5. **다른 날 다시 볼 곳**: 인천 통합전자도서관 새 주소 `www.michuhollib.go.kr/ebook/`(443 연결 실패), 울주 교육청 전자책 `14.43.5.182:8089`(시간 초과, 홈페이지에 아직 링크), 성동구립영어도서관 교보(`sedl.sdlib.or.kr:8091`, 오류 화면).
6. **홈페이지 링크가 낡음**: 유성 `bookdreamlotte.yes24library.com`, 창원 `jrebook.cwcl.or.kr`은 도메인이 없는데 홈페이지가 아직 링크함(도서관에 알릴지는 사람이).
7. 로그인 뒤에만 주소가 보이는 8곳: 사람이 로그인해 볼지, 이대로 둘지.

## 기록할 곳(이 작업에서는 저장소를 고치지 않음)

- `docs/libraries/library-site-issues.md` 1·2절: 위 49줄의 새 상태로 바꿈(OK 줄은 seed에 넣을 때 지움).
- `docs/libraries/library-maintenance.md` 4-3: 포털 목록에 강원교육청 `ebook.gwe.go.kr/elib`(menu_idx=2), 경남교육청 `ebook.gne.go.kr`(POST, 검색 됨), 경남통합 `elib.gyeongnam.go.kr`(검색 로그인), 울산 포털(검색 됨) 추가. 4-6 표: 대구 동구 YES24 옛 ASP(`/ebook/search_list.asp`), 전남·충남 자체 시스템(`chnFunc.js`). 12절: 강원 menu_idx.
- `docs/libraries/research/`: `moved-classification.tsv`를 날짜 붙여 옮김.
