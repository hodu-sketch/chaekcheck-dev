# 검색 대상에서 빠진 전자도서관과 이유

- 작성일: 2026-09-29
- 목적: seed에 넣지 못한 사이트를 이유별로 모아 두고, 다시 볼 때 무엇을 확인하면 되는지 적습니다.
- 찾고 확인하는 방법 자체는 [`library-maintenance.md`](library-maintenance.md)에 있습니다. 이 문서의 "다시 볼 때"는 그 문서의 절 번호를 가리킵니다.
  - 목록 찾기: 4-1 ~ 4-6, 사이트 하나 확정: 4-7, 가입 자격: 4-8, 가입 방법: 4-10
  - 확인 도구 결과 상태(`OK`, `HTTP_404`, `MOVED`, `OTHER`, `ERROR`)의 뜻과 처리: 11절
  - 호출 예절(우리 User-Agent만, 호스트당 1건, 1초 간격): 2절
- 처음 조사: 2026-09-29 교보 공식 목록 216곳 확인(`scripts/library/CheckKyoboSites.java`). 결과 요약은 `docs/plan-feedback.md` "11단계 seed 결과".
- **지금 서비스에 넣지 않은 곳 전체 목록(2026-10-12)**: [`library-left-out.md`](library-left-out.md). 넣기 명령 `library-insert` 미리보기 보고서 `1af0721e4a3e`와 사본 `ebook-catalog-2026-10-12-0135.sqlite`로 만든 표입니다. A절은 수집 DB에 있지만 넣지 않는 349곳을 이유별로(교보 대여·혼합 232, `crawl_skip` 105, 체험용·운영 중단 4, 시험용 6, 전체 수집 안 끝남 2, 같은 사이트 0), B절은 서비스에 있지만 꺼 둔 5곳(59·131·135·2194·2198)과 켜져 있지만 수집하지 않는 3곳(`gone_known.tsv` 44·261·269)입니다. 서비스 DB에 이미 있는 곳은 기본으로 그대로 둡니다(사용자 결정 2026-10-12, B절은 알림용). 다시 만들기: `scripts/library/list_left_out.py`. 아래 절들은 그때그때의 조사 기록이고, 지금 빠진 곳의 기준은 그 문서입니다. **2026-10-14 다시 만듦(사이클 18)**: 0권 22곳을 끄고(11절) B절은 27곳(꺼 둔 곳 27, `gone_known.tsv` 0). 보고서 `b2174dfd426d`(같은 사본, 사이클 13 실제 넣기 뒤 미리 보기).

## 1. 주소가 없어졌거나 다른 화면 (11곳)

처음 확인 때 검색 화면이 열리지 않거나(404), 다른 곳으로 옮겨졌거나(`MOVED`), 열려도 검색 결과 목록이 없던(`OTHER`) 곳입니다. 2026-09-29에 같은 도구로 한 번 더 확인했습니다.

| 사이트 | 처음 확인 때 주소 | 처음 결과 | 다시 확인 (2026-09-29) | 할 일 |
|---|---|---|---|---|
| 충남도서관 | `http://elibrary.chungnam.go.kr:8091` | `HTTP_400` | **`OK`**: `https://elibrary.chungnam.go.kr:446`로 옮김, `사랑` 76건, 보유 4,900 | **seed `004`에 넣음(id 164)**. 자격 원문(plan `eligibility-by-library.md`): "충청남도 도민인 성인", "충청남도 소재 직장인", "충청남도 소재 대학교이상 재학생" 등 |
| 강원도교육청 | `http://ebook.gwe.go.kr` | `OTHER` | `ERROR` 인증서 체인 불완전(PKIX) | 3절 인증서 묶음으로 옮겨 같이 처리 |
| 경산시립도서관 | `http://elib.gbgs.go.kr` | `OTHER` | `OTHER` (첫 화면은 200) | 사람이 브라우저로 검색 화면을 열어 봄. 교보 화면이 아니면 새 adapter 필요 여부 판단 |
| 광주남구도서관 | `http://ebook.namgu.gwangju.kr:8000` | `OTHER` | `OTHER` (첫 화면은 200) | 위와 같음. plan 조사 때 robots.txt가 막았던 곳(research.md 141행) |
| 국립세종도서관(교보 일반) | `http://ebook.nls.go.kr` | `OTHER` | `OTHER` (첫 화면은 200) | 위와 같음. 구독형(`sejongnl.dkyobobook.co.kr`)은 seed에 있음(id 43) |
| 대전학생교육문화원 | `http://e.dsecc.djsch.kr` | `MOVED` | 오류 화면(`/Kyobo_T3/ErrorPage.asp`)으로 이동 | 기관 홈페이지에서 새 전자도서관 주소 찾기(4-2) |
| 서울문화재단 예술교육정보자료관 | `http://esac-ebook.sfac.or.kr` | `HTTP_404` | 404 | 없어졌거나 옮김. 기관 홈페이지 확인(4-2). 공공도서관인지도 확인 |
| 시흥시중앙도서관 | `http://ebook.shcitylib.or.kr` | `HTTP_404` | 404 | 시흥시 도서관 홈페이지의 전자책 메뉴에서 새 주소 찾기(4-2) |
| 울산동구청 | `http://ebook.usdl.or.kr` | `HTTP_404` | 404 | 교보가 아닌 북큐브로 옮긴 것으로 보임: `https://ebook.usdl.or.kr/FxLibrary/` (plan research.md 12절). 북큐브 adapter가 생기면 추가 |
| Cankyobo library | `http://cankyobo.dkyobobook.co.kr` | `HTTP_404` | 404 (검색 경로) | 공공도서관이 아닌 기관으로 보임(plan 조사). 넣지 않음 |
| 한국도서재단 | `http://hspls-klf.dkyobobook.co.kr` | `HTTP_404` | 404 (검색 경로) | 공공도서관인지부터 확인 |

## 2. 연결이 안 됨 (4곳)

| 사이트 | 주소 | 처음 결과 | 다시 확인 (2026-09-29) | 할 일 |
|---|---|---|---|---|
| 구미시립도서관 | `http://ebook.gumilib.or.kr` | `ConnectException` | 도메인 없음(curl 6) | YES24 `http://gumilib.yes24library.com`로 옮김(plan research.md 12절). YES24 목록을 모을 때 추가(4-2, 4-4) |
| 춘천시립도서관 | `http://ebook.chuncheon.go.kr` | `ConnectException` | 도메인 없음(curl 6) | YES24 `https://elib.chuncheon.go.kr:447`로 옮김(같은 곳). robots.txt가 모든 봇을 막음(research.md 118행). 사용자 결정대로 조회 대상에 넣을 곳 |
| 국립어린이청소년도서관 | `http://ebook.nlcy.go.kr` | 연결 시간 초과 | 시간 초과(curl 28) | 다른 날 다시 확인. 기관 홈페이지에서 전자책 주소 확인 |
| 장성군립도서관 | `http://ebook.jangseong.go.kr:88` | 연결 시간 초과 | 시간 초과(curl 28) | 구독형(`jslib.dkyobobook.co.kr`)은 seed에 있음(id 129). 일반 사이트는 다른 날 다시 확인 |

## 3. 인증서가 불완전 (24곳 + 강원도교육청) → 2026-09-29 해결, 24곳 넣음

- 증상: 서비스 JDK가 `PKIX path building failed`로 연결을 끊습니다. 브라우저는 대개 열립니다(브라우저는 빠진 중간 인증서를 스스로 받아 옴).
- 조사 결과(2026-09-29): 24곳 모두 인증서 자체는 유효합니다. **사이트가 중간 인증서를 보내지 않는 것**이 원인입니다. 인증서 안의 AIA 주소(발급 기관이 중간 인증서를 올려 두는 주소)에서 받은 중간 인증서 6개(Sectigo RSA Domain Validation Secure Server CA, GlobalSign GCC R6 AlphaSSL CA 2025, Thawte TLS RSA CA G1, Sectigo Public Server Authentication CA DV R36, GlobalSign RSA OV SSL CA 2018, Sectigo Public Server Authentication CA OV R36)를 넣은 시험용 신뢰 저장소로 23곳이 연결됩니다. 충주시립도서관만 연결 시간 초과가 남습니다.
- 사용자 결정(2026-09-29): "넣되 내가 확인할 수 있게 나중에 문서로 좀". 인증서 확인은 끄지 않습니다.
- **어떻게 넣었나 (확인하실 곳)**
  - 코드: 본 서버 `backend/src/main/java/com/chaekcheck/availability/internal/fetch/LibrarySiteTls.java`, 조회 서버 `fetch-service/src/main/java/com/chaekcheck/fetch/LibrarySiteTls.java`(같은 내용). 도서관 사이트를 부르는 HTTP 연결 두 곳(`AvailabilityConfiguration`의 직접 호출, fetch-service `LibraryClient`)에만 씁니다. 조회 서버와 본 서버 사이 연결에는 쓰지 않습니다.
  - 중간 인증서 파일: `backend/src/main/resources/tls/library-intermediates/*.pem`, `fetch-service/src/main/resources/tls/library-intermediates/*.pem` (6개, 같은 파일).
  - 믿는 기관은 **JDK에 원래 들어 있는 최상위 인증 기관뿐**입니다. 중간 인증서 6개는 믿는 기관으로 올리지 않고, 사이트가 빠뜨린 연결 고리를 채우는 자료로만 줍니다(`PKIXBuilderParameters.addCertStore`). 그래서 사슬은 여전히 JDK 최상위 기관에서 끝나야 하고, 주소 이름 확인도 HTTP 클라이언트가 그대로 합니다. 폐기 확인은 JDK 기본과 같이 하지 않습니다.
  - 6개의 발급 기관(모두 JDK에 있음): Sectigo DV R36·OV R36 ← Sectigo Public Server Authentication Root R46, Sectigo RSA DV Secure Server CA ← USERTrust RSA, Thawte TLS RSA CA G1 ← DigiCert Global Root G2, GlobalSign GCC R6 AlphaSSL CA 2025 ← GlobalSign Root R6, GlobalSign RSA OV SSL CA 2018 ← GlobalSign Root R3.
  - 만료일: Thawte TLS RSA CA G1 2027-11-02, GlobalSign GCC R6 AlphaSSL CA 2025 2027-05-21, GlobalSign RSA OV SSL CA 2018 2028-11-21, Sectigo RSA DV 2030-12-31, Sectigo R36 두 개 2036-03-22. 만료 전에 사이트가 새 중간 인증서로 바꾸면 그 사이트는 다시 PKIX 오류가 납니다 → 11절 신호로 알아차리고, 새 인증서의 AIA 주소에서 받아 이 폴더에 더합니다.
  - 테스트: `LibrarySiteTlsTests`(두 앱) — 6개를 읽는지, 6개 중 어느 것도 믿는 기관이 아닌지, 각각의 발급 기관이 JDK 최상위 기관인지.
  - 확인 도구 `CheckKyoboSites.java`도 같은 방식으로 바꿨습니다.
- 이름: 교보 목록 이름 "구로구청"(id 186)은 전자도서관 화면 이름 "구로구청 전자도서관"으로 바꿈(사용자 결정 2026-09-29).
- 결과(2026-09-29, 같은 방식으로 다시 확인): 24곳 모두 검색 OK(충주도 이번엔 응답) → seed `004` id 182–205로 넣음. 강원도교육청은 연결은 되지만 검색 화면이 다름(`OTHER`)이라 1절 "다른 화면"으로 남김.
- 사이트 목록(https 최종 주소): 곡성교육문화회관 `ebook.jne.go.kr:8401`, 공주시시립도서관 `ebook.gongjulib.go.kr`, 광양공공도서관 `ebook.jne.go.kr:8402`, 광양평생교육관 `ebook.jne.go.kr:8403`, 구로구청 `ebook.guro.go.kr`, 구리시립도서관 `ebook.gurilib.go.kr:446`, 나주공공도서관 `ebook.jne.go.kr:8410`, 당진시립도서관 `lib.dangjin.go.kr:444`, 레인보우영동도서관 `ebook.rainbowlib.go.kr`, 목포공공도서관 `ebook.jne.go.kr:8409`, 안동시립도서관 `ebook.andong.go.kr`, 안양시립석수도서관 `elib.anyang.go.kr:8092`, 양평군도서관 `ebook.yplib.go.kr`, 영광공공도서관 `ebook.jne.go.kr:8411`, 장성공공도서관 `ebook.jne.go.kr:8408`, 전남광주통합특별시교육청 전남도서관 `ebook.jne.go.kr:8400`, 전라남도학생교육문화회관 `ebook.jne.go.kr:8407`, 진도공공도서관 `ebook.jne.go.kr:8406`, 진천군립도서관 `ebook.jincheon.go.kr:446`, 청도어린이도서관 `ebook.cheongdo.go.kr`, 충주시립도서관 `e-lib.chungju.go.kr`, 칠곡도서관 `ebook.chilgok.go.kr`, 해남공공도서관 `ebook.jne.go.kr:8404`, 화순도서관 `ebook.jne.go.kr:8405`. 2026-09-29에 강원도교육청 `ebook.gwe.go.kr`도 같은 오류.
- 주의: 11곳이 전남교육청 서버 `ebook.jne.go.kr` 한 곳(포트만 다름)에 있습니다. 넣으면 같은 서버 몰림이 더 커집니다(plan-feedback "같은 호스트 병목").

### 3-2. 수집 목록에서 찾은 도서관의 인증서 (2026-10-05, 사이클 07)

- 수집 목록에서 찾은 도서관을 https 최종 주소로 바꾸면서(`library-maintenance.md` 13절) 15곳이 PKIX 오류. 모두 http → https 302 뒤의 https 서버.
- 중간 인증서 3개를 더함(받은 곳 = 인증서 AIA 주소, 발급 기관은 모두 JDK 최상위 기관):
  - Sectigo RSA Organization Validation Secure Server CA ← USERTrust RSA (`http://crt.sectigo.com/SectigoRSAOrganizationValidationSecureServerCA.crt`, 만료 2030-12-31)
  - GeoTrust TLS RSA CA G1 ← DigiCert Global Root G2 (`http://cacerts.geotrust.com/GeoTrustTLSRSACAG1.crt`, 만료 2027-11-02)
  - Sectigo Public Server Authentication Root R46 ← USERTrust RSA, 교차 인증서 (`http://crt.sectigo.com/SectigoPublicServerAuthenticationRootR46_USERTrust.crt`, 만료 2038-01-18). 이 JDK 25에는 R46 최상위 인증서가 없어(`keytool -list -cacerts`), R36 두 개의 사슬이 이 교차 인증서를 거쳐 USERTrust에서 끝납니다. 위 3절 "6개의 발급 기관(모두 JDK에 있음)"의 R46 부분은 이 JDK에서는 맞지 않습니다.
- 12곳 해결: 강동대 교보 `elib.gangdong.ac.kr:446`, 서울교대 `ebook.snue.ac.kr`, 금오공대 `ebook.kit.ac.kr:444`, 경성대 `ebook2.ks.ac.kr:444`, 계명대 `ebook.kmu.ac.kr`, 동아대 `ebook.donga.ac.kr:444`, 부산외대 `elib.bufs.ac.kr:447`, 중부대 `elib.jeiu.ac.kr:444`, 전주교대 `lib.jnue.kr:446`, 진주교대 `ebooklib.cue.ac.kr:444`, 한양대 교보 `kyobo.ebook.hanyang.ac.kr:444`, 혜전대 `ebook.hj.ac.kr`.
- 남은 3곳은 발급 기관 TuringSign RSA Secure CA 2 ← **OISTE WISeKey Global Root GB CA**(JDK에 없음): 세계사이버대 `e-book.world.ac.kr`, 서울신학대 `ebook.sau.ac.kr:444`, 충북 전자도서관 `elib.chungbuk.go.kr`.
  - **사용자 결정(2026-10-05): "이거 신뢰해줘"** → 이 최상위 인증서를 믿는 기관으로 더함. 새 폴더 `tls/library-roots/*.pem`(두 앱, 같은 파일). 지금 1개뿐.
  - 받은 곳: TuringSign 인증서의 AIA `http://public.wisekey.com/crt/owgrgbca.cer`. SHA-256 지문 `6B:9C:08:E8:6E:B0:F7:67:CF:AD:65:CD:98:B6:21:49:E5:49:4A:67:F5:84:5E:7B:D1:ED:01:9F:27:B8:6B:D6`, 만료 2039-12-01. `openssl verify`로 TuringSign 중간 인증서가 이 루트에 이어짐을 확인.
  - 이 폴더의 인증서는 **도서관 사이트 연결에만** 믿습니다(`LibrarySiteTls`). 다른 루트를 더하려면 사용자에게 먼저 묻습니다.
- 테스트 `LibrarySiteTlsTests`(두 앱): 중간 인증서 9개·자체 서명 없음, 더한 루트는 승인된 1개, 중간 인증서마다 발급 기관이 JDK 루트나 다른 중간 인증서.
- 확인 도구 `CheckKyoboSites.java`(그리고 그 TLS를 쓰는 `CheckFinalOrigins.java`)도 `library-roots`를 읽습니다.

## 4. 이름에 "도서관"이 없음 (23곳)

처음 기록(plan-feedback "11단계 seed 결과")은 24곳이었지만 목록을 다시 세니 23곳입니다(교보 목록 216곳 = 검색 OK 177 + 실패 39). `build_seed.py`는 이름에 "도서관"이 없으면 공공도서관이 아닐 수 있어 건너뜁니다. 2026-09-29에 사이트마다 제목, 화면 아래 기관명, 로그인 안내 글을 읽어 나눴습니다.

| 분류 | 사이트 (교보 목록 이름 → 사이트에 적힌 이름) | 판단 |
|---|---|---|
| 이름만 다른 공공도서관 (17) | 경기도립포천분관 → 경기포천교육도서관, 경기평생학습관 → 경기평생교육학습관 (경기도교육청 소속), 전북교육청 소속 10곳(`ebook.jbe.go.kr`의 포트만 다름): 군산교육문화회관, 군산교육문화회관 대야분관, 김제교육문화회관, 김제교육문화회관 금산분관, 남원교육문화회관, 마한교육문화회관, 마한교육문화회관 함열분관, 부안교육문화회관, 전북교육문화회관, 정읍학생복지회관, 마포구청 → 마포구 전자도서관, 양천구청 → 양천중앙도서관(로그인 안내: "이용대상: 양천구립도서관 회원카드 소지자"), 여주시평생학습센터 → 여주시립도서관("전자책 서비스는 여주시립도서관 대출회원 만 이용가능"), 의정부지식정보센터 → 의정부시도서관, 세종시교육청 → 세종북자람전자도서관 | **넣음**(사용자 결정 "사이트 이름으로 넣기", 2026-09-29): seed `004` id 165–181, 사이트에 적힌 이름으로 |
| 이미 seed에 있는 곳과 같음 (2) | 강남구청 → 강남구 전자도서관의 교보 사이트(`ebook.gangnam.go.kr:9002`, seed id 9는 같은 도서관의 다른 화면), 서울시교육청 → seed id 3과 같은 주소 | 넣지 않음. 강남: 자체 화면(id 9) "사랑" 검색 424건 안에 교보 책("교보전자책")과 YES24 책이 함께 있고 교보 사이트는 119건 → 같은 책이 두 번 나올 수 있어 따로 넣지 않음(2026-09-29 확인, 사용자에게 설명) |
| 주민이 쓰는 공공도서관이 아님 (2) | 송파책박물관, 주독일한국문화원(베를린) | 넣지 않음 |
| 판단 못 함 (2) | 김해시청전자도서관(`elib.gyeongnam.go.kr:8115`), 충북도청 전자도서관(`elib.chungbuk.go.kr`) | 시·도청 직원용인지 확인 못 함. 기관에 확인 전까지 넣지 않음 |

## 5. 이름이 겹침 (1곳)

- 인천광역시교육청 통합전자도서관의 교보 사이트(`http://e-lib.ice.go.kr:8091`)가 포털 줄(id 13)과 이름이 같아 빠졌습니다.
- 사용자 결정(2026-09-29): "인천광역시교육청 통합전자도서관(교보)"로 넣음 → seed `004` id 163, 보유 4,250(2026-09-29). 가입 자격 원문은 아직 없음(자격 미확인).

## 6. YES24 (2026-09-29)

- 확인할 곳 110곳(plan 조사 때 목록 122곳 중 이미 seed·포털이 다루는 18곳 제외 + 새로 찾은 1곳)을 `scripts/library/CheckYes24Sites.java`로 확인. 전체 표와 이유: [`library-research/yes24-report-2026-09-29.md`](library-research/yes24-report-2026-09-29.md), 원자료 `library-research/yes24-search-2026-09-29.tsv`.
- 넣음: 66곳(seed `005`, id 206–271) = `YES24_V2` 54 + `YES24_V1` 8 + `YES24_V3` 4(마포나루·김천·경주·구미, EUC-KR 지금 화면. 새 화면 종류로 만듦, 사용자 결정).
- 같은 도서관이 이미 다른 사이트로 있는 26곳은 이름에 "(YES24)"(사용자 결정). 전남교육청 8곳은 모두 넣음(사용자 결정). 대구점자도서관·북나들e는 넣고 이용 대상은 "안내"(`join_notice`)로(사용자 결정).
- 빠진 곳 44곳: 주소 없음(404) 12, 결과 목록 없는 옛 `/YES24` 화면 10, 연결 오류 17, HTTP 400/500·다른 곳으로 이동 5. 새 주소 찾기는 진행 중(결과는 이 절에 더함).
- 1절의 국립세종도서관(교보 일반, `OTHER`)은 같은 주소 `ebook.nls.go.kr`가 YES24 한 세대 전 화면이었음 → id 263 "국립세종도서관(YES24)"로 넣음. 1절 경산시립도서관(`elib.gbgs.go.kr`, 교보 `OTHER`)도 YES24 지금 화면이었음 → id 265로 넣음.

## 7. 옛 주소가 없어진 도서관의 새 주소 (2026-09-29)

- 6절의 실패 49곳에서 출발해 도서관 본 홈페이지의 전자책 메뉴로 새 주소를 찾았습니다(가입 자격도 같은 작업에서 조사). 전체 표: [`library-research/moved-report-2026-09-29.md`](library-research/moved-report-2026-09-29.md), `library-research/moved-sites-2026-09-29.tsv`, 판정 `library-research/moved-classification-2026-09-29.tsv`.
- **넣음(seed `006`, id 272–280)**: 연제만화도서관(교보), 연제만화도서관(YES24), 송파구전자도서관(YES24), 송파구통합도서관(구독), 경남통합전자도서관(구독), 전남광주통합특별시교육청 광주통합도서관(구독), 기장군도서관(구독), 전북특별자치도 인재개발원 전자도서관(공무원 교육기관으로 보이나 사용자 결정 "둘 다 넣기"), 강원특별자치도교육청 전자도서관(포털 화면. 검색 메뉴 번호 2를 주소에 넣음 `https://ebook.gwe.go.kr/elib?menu_idx=2`, 사용자 결정 "검색 메뉴 번호가 url이야 그러면 바꾸고 넣기").
- **주소를 바꾼 곳: 제주한라도서관 구독형(id 140)** (사용자: "안내가 연결하는 주소로 바꾸되 이것도 md등에 기록해줘 나도 확인 가능하게")
  - 전: `https://hallalibjeju.dkyobobook.co.kr` → 후: `https://hallalib.dkyobobook.co.kr` (첫 화면 `/main.ink`도 같이).
  - 이유: 제주 공공도서관 전자도서관 안내(`https://www.jeju.go.kr/lib/elib.htm`)의 "한라도서관 (구독형)" 링크가 `hallalib`입니다. 2026-09-29에 두 주소 모두 검색은 됐고(둘 다 구독형이라 보유 수 0), 같은 책이 두 번 나오지 않게 안내가 연결하는 쪽 하나만 둡니다.
  - 확인 방법: 위 안내 페이지에서 "한라도서관 (구독형)" 링크 주소를 봅니다. 다시 `hallalibjeju`로 바뀌면 `004-library.csv`의 140줄 `base_url`·`homepage_url`을 되돌립니다.
- **읽는 코드가 없는 화면(나중 과제, 사용자 결정 "나중 과제로 기록")**
  - 경남교육청 자체 포털 `ebook.gne.go.kr`(GNE·함양·남지): POST 검색, 로그인 없이 됨("사랑" 567건).
  - 경남통합전자도서관 `elib.gyeongnam.go.kr`(통영·양산·밀양·경남대표 소장형): 검색에 로그인이 필요해 읽을 수 없음.
  - 전남도서관 자체 `jnlib.jeonnam-gwangju.go.kr/ebook`(옥과·전남도립), 충남교육청 `ebook.cne.go.kr`(같은 제품).
  - 울산 포털 `library.ulsan.go.kr/lib/elib`: 검색은 되지만 결과에 대출 수가 없음.
  - 대구 동구 `digital.donggu-lib.kr`: YES24 옛 ASP 화면(EUC-KR).
  - 서울도서관 `elib.seoul.go.kr`. 2026-10-20 다시 확인: 자체 통합 화면, 검색은 로그인 없이 JSON API(`/api/contents/search`)로 되고 소장형은 보유·대출·예약 수가 있음(전자책 28,232·오디오북 1,453). 기존 adapter로는 못 읽어 새 화면 종류가 필요(개발). 사용자: 이번 릴리스 뒤에 배포, 지금은 조사·기록만(2026-10-20). 가입 자격(정회원 = 서울 거주·재직·재학, 온라인 비대면 자격확인)도 조사함. **같은 날 새 화면 종류 `SEOUL_ELIB_V1`(adapter·수집기)을 만듦**(브랜치 `claude/funny-hodgkin-043487`). 서비스에는 v1.7.0 뒤 수집·넣기 명령으로 들어감(4-14 "남은 일"). 들어가면 이 줄을 지움. 원문·방법·다시 확인 도구: [`library-maintenance.md`](library-maintenance.md) 4-14, [`maintenance/platforms.md`](maintenance/platforms.md) 11절.
  - 북큐브 4곳: 제주시통합, 한라 소장형, 완주, 사상.
- **로그인해야 전자책 주소가 보이는 곳 8곳**: 포항, 고령, 레인보우영동(YES24), 광주동구, 서귀포, 정읍, 함평, 창원 jrebook. AI는 비밀번호를 넣지 않으므로 사람이 로그인해 주소를 알려 주면 4-7로 확인합니다.
- **다른 날 다시 볼 곳**: 인천통합(`www.michuhollib.go.kr/ebook/` 연결 실패), 울주 교육청(`14.43.5.182:8089` 시간 초과), 성동구립영어도서관(교보 화면 "도서관 정보 조회에 실패").
- **이미 목록에 있는 사이트로 옮긴 곳**: 칠곡(203), 충남도서관(164), 경북도서관(28), 수영(86), 구수산(대구 포털 11), 광주학생교육문화회관(133), 전남 미디어북(전남교육청 YES24 사이트들).
- 서비스 중단: 대구학생전자도서관(6-2절 꺼 둔 도서관과 같은 곳). 전자책 페이지가 아님: 양산 `:8088`(열람실 좌석 화면).

## 8. 수집 목록에서 찾은 도서관을 넣을 때 뺀 곳 (2026-10-05, 사이클 07)

수집 DB 배포용 사본(`chaekcheck-data/snapshots/ebook-catalog-2026-10-05-0745.sqlite`)의 수집 목록에서 찾은 도서관(수집 번호 100001 이상)을 `scripts/library/build_candidate_seed.py`(지금 `build_library_seed.py`)로 넣을 때 뺀 곳입니다. 사이클 13부터 넣기 명령이 같은 규칙으로 빼고, 지금 목록은 [`library-left-out.md`](library-left-out.md)입니다. 절차와 셈은 `library-maintenance.md` 13절 2026-10-05 줄.

- **같은 사이트 (`crawl_skip`) 99곳**: 수집 세션이 "same site as registered library N"으로 표시한 곳. 사본 `library.crawl_skip` 칸에 이유가 있음.
- **같은 사이트를 다른 포트로 연 곳 8곳** (사용자 결정 2026-10-05: "① 8곳만 빼기"): 호스트가 등록 도서관과 같고 책 번호 목록이 서로 100% 같으며, 책 상세 주소도 포트만 다름.

  | 수집 번호 | 이름 | 주소 | 같은 등록 도서관 |
  |---|---|---|---|
  | 100668 | 전라북도인재개발원 | `http://hrdebook.jeonbuk.go.kr:88` | 279 `https://hrdebook.jeonbuk.go.kr:444` |
  | 100681 | 고흥군립도서관 | `http://www.ghlib.go.kr:88` | 2 `https://www.ghlib.go.kr:446` |
  | 100701 | 마포구청 | `http://ebook.mapo.go.kr:8088` | 172 `https://ebook.mapo.go.kr` |
  | 100714 | 서울시교육청 | `http://e-lib.sen.go.kr:8090` | 3 `https://e-lib.sen.go.kr:8095` |
  | 100722 | 세종시교육청 | `http://ebook.sje.go.kr:8087` | 176 `https://ebook.sje.go.kr:446` |
  | 100755 | 전주시립도서관 | `http://ebook.jeonju.go.kr:88` | 136 `https://ebook.jeonju.go.kr:8445` |
  | 100761 | 경기도립포천분관 | `http://elib.goe.go.kr:8109` | 165 `https://elib.goe.go.kr:8209` |
  | 100788 | 경기평생학습관 | `http://elib.goe.go.kr:8108` | 166 `https://elib.goe.go.kr:8208` |

  - 넣은 곳(같은 호스트, 책 번호 일부만 겹침): 북큐브 6곳(구미시립중앙 100022, 국립세종 100024, 양천e도서관 100082, 선경 100070, 성북정보 100074, 여의샛강 100085)은 같은 기관의 다른 사이트입니다. 2026-10-05에 첫 화면을 한 번씩 열어 모두 열렸습니다(제목 "구미시립중앙도서관 전자책", "국립세종도서관 전자책도서관", "양천e도서관 전자도서관", "수원시 전자도서관", "성북정보도서관 전자책도서관", "여의샛강마을도서관 전자책 도서관"). 나머지 겹침은 26% 이하(한 서버에 여러 도서관).
- **체험용·운영 중단 의심 4곳** (사용자 결정 2026-10-05, plan `easy/2026-10-05-candidate-join-decisions.html` 6번): 100068 삼각산어린이도서관(북라이브), 100048 대구동구안심도서관(북큐브), 100301 순창군립도서관 북라이브, 100083 양평군립도서관 전자도서관(북큐브).
- **시험용 6곳** (종류 표 `TEST`, plan 설계 검토 2026-10-05): 100079 아이티컴퍼니 홍보용 전자도서관, 100217 대여형 테스트 도서관, 100310 시연 전자도서관, 100400 초중고 테스트도서관, 100412 테스트도서관, 102463 서울대학교데모버전.
- **전체 수집이 끝나지 않은 227곳**: 사본에 `last_full_success_at`이 없어 이번에는 넣지 않음(다음 seed에서 수집이 끝나면 들어감).

- **교보 대여·혼합 11곳 (2026-10-11, 사이클 08)**: seed에 들어 있던 서비스 번호 2664~2674(수집 번호 102542·102571·102574·102598·102603·102613·102655·102681·102692·102698·102708)를 뺌. 규칙 `kyobo_rental_mixed`(plan ADR-0067: 교보 대여·혼합은 수집도 서비스도 하지 않음). 번호는 다시 쓰지 않음(`RESTART WITH 2675` 그대로). 수집 DB에는 같은 이유로 230곳 `crawl_skip`([maintenance/2-crawl.md](maintenance/2-crawl.md) 5-1절).

## 9. 최종 주소를 끝내 못 받은 곳 (2026-10-05)

`CheckFinalOrigins.java`를 세 번(시간을 두고) 돌려도 실패. plan 인계서 17단계 끄기 제안 목록에 올렸고, **사용자 결정(2026-10-05)으로 끔**(`enabled = false`, `library/010-library-disabled.yaml`). 같은 결정으로 등록 도서관 전라북도교육청통합도서관(135)도 끔: 서비스 검색이 서버 오류, 사이트가 브라우저에도 'L7 Secure connect fail'(2026-10-01 수집 기록), 같은 교육청 책은 포털 도서관 15에 있음.

| 서비스 번호 | 이름 | 주소 | 결과 |
|---|---|---|---|
| 2198 | 성균관대 (YES24) | `http://ebook.skku.edu` | 시간 초과(5초) 세 번. 같은 호스트의 북큐브(558, `:9080/FxLibrary`)는 응답 |
| 2194 | 장안대 (YES24) | `http://e-lib.jangan.ac.kr` | 연결 뒤 응답 없음(`header parser received no bytes`) 두 번 |

## 10. 첫 화면에 다른 이름이 나오는 곳 (2026-10-05, PC·폰 검사)

도구: `scripts/library/check_front_page_names.py`(PC 기본, `--mobile` 폰). 켜진 2,669곳. 결과 파일 `data/cycle07/front-out.tsv`, `front-out-mobile.tsv`(git 밖).

### 10-1. 폰에서만 문제 (YES24 모바일판 `/m/`, 13곳)

YES24 사이트는 폰 브라우저를 첫 화면·목록·**검색 결과까지** 모바일판 `/m/`으로 보냅니다(`/`, `/default.asp`, `/ebook/`, `/search/?…` 모두 확인). **책 상세 `/ebook/detail/?goods_id=…`만 PC판 그대로**라 책 링크는 맞게 열립니다. 아래 사이트의 모바일판은 설정이 안 되어 있습니다.

| 폰에서 보이는 것 | 도서관 (서비스 번호) |
|---|---|
| 제목·로고 "경동대학교" | 원주시립도서관(YES24) 224 `http://ebook.wonju.go.kr:81/m/`, 동대문구립도서관(YES24) 230 `http://e-book.l4d.or.kr:8088/m/`, 대전광역시 서구 전자도서관 260 `https://seogu.yes24library.com/m/`, 국립한밭대학교 2586 `https://hanbatu.yes24library.com/m/` |
| "요청하신 페이지를 찾을 수 없습니다 - YES24" | 고양시도서관센터(YES24) 238, PARAMUS 2190, 현대오토에버 동반성장협약사 2277 |
| 빈 화면("New Document" 또는 제목 없음) | 성남시도서관사업소 229, 한국체육대학교 (YES24) 2164, 명지대학교 (YES24) 2533, 세명대학교 (YES24) 2195, 목원대학교 (YES24) 2222 |
| 빈 화면(교보, 폰에서 다른 주소로 감) | 용인대지초등학교 1824 |

- 원문(동대문, 폰): `<title>경동대학교</title>`, 로고 `alt="경동대학교"`. PC판 바닥: "동대문구립도서관 서울특별시 서대문구 거북골로 34", 회원가입 링크 `https://www.gdlibrary.or.kr/intro/member/join.do`(사이트가 다른 곳 틀을 쓴 흔적).
- 서비스·수집 데이터는 맞음: 서비스 책 화면은 계획의 도서관만 보여 줌(구성원용 경동대 같은 곳 없음). 서비스에 "경동대학교" 도서관은 없음.
- 처리: 아직 없음(plan이 정함. plan-feedback `# 사이클 07` "plan에 넘길 요약").

### 10-2. PC 첫 화면 이름 (2,669곳)

- 이름을 글에서 찾음 2,098, 못 찾음 571(응답 200: 566, 그 밖 5). 못 찾은 곳 대부분은 제목이 일반적인 것("전자도서관" 146, "ebook_library" 68, "eBook Library" 30, "Insert title here" 26, "INDEX" 25 …)이라 이름이 그림·스크립트 안에 있음. 사람이 볼 일은 아님.
- 제목에 기관 이름이 있는데 우리 이름과 겹치는 낱말이 없는 곳 67곳. 대부분 통합 포털(예: 상록도서관 → "전남광주통합특별시 서구 통합전자도서관") 또는 기관 이름이 바뀐 곳(서라벌대학교 → 신경주대학교, 포스코이앤씨 ← 포스코건설, 경상대학교 → 경상국립대학교, 국립안동대학교 → 국립경국대학교 등).
- **다른 기관으로 보이는 곳(사람 확인 필요)**:

| 서비스 번호 | 우리 이름 | 사이트 제목 | 주소 |
|---|---|---|---|
| 1427 | 대전신흥고등학교 | 대전신흥초등학교 전자책도서관 | `https://sinhung.dkyobobook.co.kr` |
| 1575 | 부여대왕초등학교 (교보, jb-dongsang…) | 완주동상초등학교 | `https://jb-dongsang.dkyobobook.co.kr` |
| 106 | 옥천군공공도서관 | 가양도서관 | `https://ebook.oc.go.kr` |
| 980 | 경찰청 | 경찰대학 김구도서관 | `https://police.dkyobobook.co.kr` |
| 1198 | 테라몬스자산운용 | 에스티자산운용 | `https://terramons.dkyobobook.co.kr` |
| 1138 | 누리나래선교협회 전자도서관 | 미코커뮤니케이션 | `https://miko.dkyobobook.co.kr` |
| 2139 | 동서IMS | APT도서관 | `https://its.dkyobobook.co.kr` |
| 2148 | 대한고시연구원 | (주)뉴엠 전자도서관입니다. | `https://gosischool.dkyobobook.co.kr` |
| 2611 | 강서공업고등학교 | 서울디지털콘텐츠고등학교 전자책도서관 | `http://gangseo-th.yes24library.com` |
| 2563 | 대검 도서관 | 공소청 | `http://spo.yes24library.com` |

- 이름 출처: 수집 목록에서 찾은 도서관의 이름은 수집 DB(교보 구축 기관 목록 등)에서 왔음(`build_candidate_seed.py`). 바뀐 이름을 쓸지, 사이트 제목을 따를지는 사용자 결정.

## 11. 전자책이 0권이라 끈 곳 (2026-10-14, 사이클 18)

서비스에 켜져 있는데 서비스 책(`catalog.holding`)이 0권인 곳을 plan 사이클 18이 조사했고(plan `docs/cycles/18-empty-library-holdings/research.md` 2절), **사용자 결정(2026-10-14, plan ADR-0109)으로 22곳을 끔**(`enabled = false`, `library/016-library-disabled-cycle18.yaml`). 끈 이유는 `scripts/library/disabled_reasons.tsv`, 다시 켜는 법은 `library-maintenance.md` 6-2.

- 기준: 내 PC 서비스 DB(2026-10-14)에서 `SELECT l.id FROM library.library l WHERE l.enabled AND NOT EXISTS (SELECT 1 FROM catalog.holding h WHERE h.library_id = l.id)` = 22줄(아래 21곳 + 현대제철 2624). 261은 옛 수집분 80권이 있어 이 SQL에는 나오지 않지만 사이트를 옮겨 같이 끔. 2624는 끄지 않음(호스트 밑줄 `hs_partners.yes24library.com` 때문에 가져오기가 전자책 241줄을 못 읽음, 사이클 18 B에서 고침)
- 수집 DB 근거: `chaekcheck-data/ebook-catalog.sqlite`(읽기 전용, 2026-10-14 조회)의 `library`·`holding`. "모은 줄이 모두 오디오북" = `holding.content_type`이 모두 `AUDIOBOOK`이고 가져오기는 오디오북을 뺌(`kind_audiobook`, [maintenance/3-import.md](maintenance/3-import.md) 6절)
- 원문 출처: research.md 2절(plan 세션이 앱 안 브라우저로 첫 화면을 한 번씩 엶, 2026-10-14). research.md에 원문이 없던 북큐브 8곳은 이 작업(dev)에서 첫 화면을 한 번씩 받음: 우리 User-Agent `chaekcheck/0.0.1`, 한 곳에 1건, 5초 간격(2절). 북큐브 첫 화면 HTML의 "분야별 eBook" 메뉴(`class="category_all"`)에는 권수 없이 분류 이름만 있음
- 교보 구독형 서버(`*.dkyobobook.co.kr`)는 열지 않음(2026-10-07부터 집 IP를 막아 사이클 13에서 요청하지 않기로 함). 수집 기록으로만 판단
- 305와 168은 같은 기관(군산교육문화회관 대야분관)의 북큐브·교보 사이트. 261은 46번 군포시중앙도서관과 같은 사이트

| 서비스 번호 | 이름 | 주소 | 원인 | 사이트 원문 / 근거 | 확인일 |
|---|---|---|---|---|---|
| 112 | 울주도서관 | `https://usul.mhebook.co.kr` | 오디오북만 있음 | 첫 화면(`/`)이 스크립트로 `/elibrary-front/`로 보내는 빈 쪽이라 메뉴를 못 읽음(그 주소는 302, 더 따라가지 않음). 수집 DB: 2026-09-30 전체 수집 성공, 사이트 권수 167 = 모은 167줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 168 | 군산교육문화회관 대야분관 | `https://ebook.jbe.go.kr:10114` | 오디오북만 있음 (305와 같은 기관) | 112와 같음(첫 화면이 `/elibrary-front/`로 보내는 빈 쪽, 그 주소는 302). 수집 DB: 2026-09-30 성공, 사이트 권수 179 = 모은 179줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 305 | 군산교육문화회관대야분관 | `http://ebook.jbe.go.kr:10036/FxLibrary_dea` | 오디오북만 있음 (168과 같은 기관) | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 사이트 권수 292 = 모은 292줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 318 | 남평공공도서관 전자도서관 | `http://ebook.jne.go.kr:8084/FxLibrary_np` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 101줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 323 | 느티나무도서관 전자도서관 | `http://neutinamulib.bookcube.biz/FxLibrary` | 사이트에 책이 없음 | 첫 화면 "전체 (0종)". 수집 DB: 2026-09-30 수집 성공, 사이트 권수 0 | 2026-10-14 |
| 378 | 정관도서관 | `http://jglib.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "전체 (2,982종) / 오디오북 (2,982종)". 수집 DB: 2,982줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 425 | 강원대학교 전자책 도서관 | `http://ebook.kangwon.ac.kr:8090/FxLibrary` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 100줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 455 | 국립아시아문화전당 북라이브 | `http://acc.bookcube.biz:8090/BookLive` | 오디오북만 있음 | 북큐브 오디오북 전용 화면 BookLive, "10월의 오디오북". 수집 DB: 183줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 514 | 명현중학교 전자책 도서관 | `http://mh.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 100줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 537 | 상관중학교 전자 도서관 | `https://sanggwan.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "전체 (463종) / 오디오북 (463종)". 수집 DB: 463줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 622 | 인천연송고등학교 | `http://iys.icehs.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 100줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 623 | 인천예술고등학교 전자책도서관 | `http://inart.icehs.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 100줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 625 | 인천청량중학교 전자책 도서관 | `http://cheongryang.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 100줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 626 | 인천효성고등학교 전자도서관 | `http://in-hyoseong.bookcube.biz/FxLibrary` | 오디오북만 있음 | 첫 화면 "분야별 eBook" 메뉴가 "오디오북" 하나뿐. 수집 DB: 100줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 721 | 휴맥 오디오북 라이브 | `http://humacaudiolive.bookcube.biz:8090/BookLive` | 오디오북만 있음 | 북큐브 오디오북 전용 화면 BookLive, "10월의 오디오북". 수집 DB: 5줄 모두 `AUDIOBOOK` | 2026-10-14 |
| 1045 | 성남문화재단 전자도서관 | `https://snart.dkyobobook.co.kr` | 교보 구독형, 수집 때 0권 | 수집 기록: 2026-10-04 전체 수집 성공, 사이트 권수 0(막힘 2026-10-07보다 앞). 사이트는 열지 않음. 다른 IP에서 다시 받을 때 책이 있으면 다시 켬 | 2026-10-14 (수집 기록) |
| 1131 | SSAFY(자율PJT) | `https://ssafy3.dkyobobook.co.kr` | 교보 구독형, 수집 때 0권 | 1045와 같음(2026-10-04 성공, 사이트 권수 0) | 2026-10-14 (수집 기록) |
| 1620 | 서울고은초등학교 | `https://goeun.dkyobobook.co.kr` | 교보 구독형, 수집 때 0권 | 1045와 같음(2026-10-04 성공, 사이트 권수 0) | 2026-10-14 (수집 기록) |
| 2023 | 청원대길초등학교 | `https://daegil.dkyobobook.co.kr` | 교보 구독형, 수집 때 0권 | 1045와 같음(2026-10-04 성공, 사이트 권수 0) | 2026-10-14 (수집 기록) |
| 44 | 국회도서관 | `https://ebook.nanet.go.kr:8089` | 수집 안 함으로 알려짐 | 수집 DB `crawl_skip`: "site answers non-browser clients with an empty page (any headers); not bypassed. 17,162 titles seen in a browser (2026-10-01)". 전에 `gone_known.tsv`(2026-10-12 확인)에 두고 켜 두었던 곳 | 2026-10-14 (수집 기록) |
| 269 | 김천시립도서관 | `http://ebook.gcl.go.kr` | 수집 안 함으로 알려짐 | 수집 DB `crawl_skip`: "since 2026-09-30 the site resets scripted connections (browser OK; 7,921 titles on 2026-09-29); not bypassed". 전에 `gone_known.tsv`에 두었던 곳 | 2026-10-14 (수집 기록) |
| 261 | 군포시도서관 | `http://ebook.gunpolib.go.kr:8080` | 다른 사이트로 옮김 (= 46번) | 수집 DB `crawl_skip`: "moved: http://ebook.gunpolib.go.kr:8080 now redirects to the KYOBO site https://ebook.gunpolib.go.kr:444 = library 46 (2026-10-01)". 서비스 46번 군포시중앙도서관(교보 소장형, 10,396권)이 그 주소. 서비스에 남은 80권은 옮기기 전 수집분. 전에 `gone_known.tsv`에 두었던 곳 | 2026-10-14 (수집 기록) |

- 원인 묶음: 오디오북만 있음 14, 사이트에 책이 없음 1, 교보 구독형 수집 때 0권 4, 수집 안 함으로 알려짐 2, 다른 사이트로 옮김 1
- 44·261·269는 `scripts/library/rules/gone_known.tsv`에서 지움(꺼진 곳은 넣기 명령이 비교하지 않으므로 "사라진 곳"에 다시 나오지 않음)
- 다시 볼 때: 교보 구독형 4곳은 다른 IP에서 교보 구독형 서버를 다시 받을 때(backlog). 오디오북만 있는 곳은 사이트 분류 메뉴에 오디오북 말고 다른 분류가 생겼을 때 6-2대로 켬

## 다시 볼 때 순서

1. 이 문서의 표에서 "할 일"이 있는 곳을 `CheckKyoboSites.java`로 다시 확인합니다(`library-maintenance.md` 11절).
2. `OK`가 되면 5절(추가) 순서대로 seed에 넣고, 이 문서에서 그 줄을 지우고 `plan-feedback.md`에 적습니다(10절).
3. 주소가 바뀐 곳은 4-2(도서관 홈페이지에서 출발)로 새 주소를 찾습니다.
