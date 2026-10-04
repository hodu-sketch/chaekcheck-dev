# 전자도서관 플랫폼별 검색·목록 방법

- 정기 유지보수 2단계([2-crawl.md](2-crawl.md))에서 쓰는 플랫폼별 방법의 **원본**입니다(plan ADR-0047, 2026-10-04). 옮겨 온 곳: plan `docs/research/ebook-catalog/platforms.md` 전체(내용은 그대로, 이 머리말만 고침).
- 처음 작성: 2026-09-30. 값은 그날(일부는 2026-10-01~03) 표본 사이트에 직접 호출해 확인한 것입니다. 표본에서 본 것이므로 같은 플랫폼이라도 사이트마다 다를 수 있습니다.
- 목적: 전자도서관 사이트에서 **검색**(제목·저자·출판사)과 **전체 목록**(소장 전자책 전부)을 가져올 때 참고합니다.
- 지금 서비스에 있는 adapter의 정확한 선택자는 `backend/src/main/java/com/chaekcheck/availability/internal/adapter/*.java` 주석이 원본입니다. adapter 설계는 사이클에서 합니다.
- 화면 종류 값과 판별: [`docs/library-maintenance.md`](../library-maintenance.md) 1-3·4-6절, plan ADR-0016·ADR-0023. 수집 원칙(호출 간격·저장 범위·날짜 기록)은 plan `docs/research/ebook-catalog/crawl-method.md`, 조사 배경은 같은 폴더 `research.md`.
- 실행 도구: `C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\scripts\crawl_catalog.py`(전체 목록 수집), `probe_other_platforms.py`(북큐브·알라딘 후보 1회 확인). 수집이 끝나면 dev `scripts/crawl/`로 옮김(ADR-0047 B).
- 새 플랫폼이나 사이트 예외를 알게 되면 이 문서에 더합니다(2-crawl 7절).

## 0. 공통 규칙

| 항목 | 값 | 태그 |
|---|---|---|
| 같은 호스트 동시 요청 | 1건, 요청 사이 1초 이상 | [확정] (사이클 01 spec 5.6) |
| 같은 서버를 쓰는 여러 사이트 | 공용 호스팅 `*.dkyobobook.co.kr`, `*.bookcube.biz`, `*.dasangng.co.kr`, `*.aladinebook.co.kr`, `*.yes24library.com`, `*.libworks.co.kr`, `*.hongjicns.co.kr`은 각각 한 서버로 보고 한 줄로 세움. `ebook.jne.go.kr`·`ebook.jbe.go.kr`·`elib.gyeongnam.go.kr`처럼 포트만 다른 사이트도 같은 호스트. 서버가 다르면 병렬 | [확정] (사용자, 2026-10-03) |
| User-Agent | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) chaekcheck-crawler/0.1 (catalog survey)`. 개인 연락처는 넣지 않음. 앞의 `Mozilla/5.0 (Windows…)`가 없으면 교보 구독형이 모바일 화면(전체 건수 없음)을 보냄 | 문자열 [안], 모바일 화면 [확정] |
| TLS | 인증서 체인이 불완전하거나 옛 RSA 키 교환만 되는 도서관이 많음(dev ADR-0022). 공개 목록 조회에 한해 검증을 끄고 `SECLEVEL=0`으로 호출 | [확정] (현상), 처리 방법 [안] |
| 문자 인코딩 | UTF-8과 EUC-KR이 섞여 있음. 검색어도 그 사이트 인코딩으로 보내야 하는 곳이 있음(YES24 V1·V3, 강남구) | [확정] |
| 종류 표시 | 사이트가 줄마다 종류를 적으면 `SITE_LABEL`, 어느 목록에서 왔는지로 정하면 `SITE_SECTION`으로 구분해 저장 | [안] |

## 1. 한눈에 보기

| 플랫폼 | 알아보는 법 | 검색 | 전체 목록 | 한 쪽 최대 | 전체 건수 | 오디오북 구분 |
|---|---|---|---|---|---|---|
| 교보 (일반) `KYOBO_V1` | `…/elibrary-front/…ink` | `search/searchList.ink` | 빈 검색 `schClst=all&schTxt=%20` | 500 시험함 | "총 N개" | 줄마다 표시(전자책·오디오북·오디오·동영상북·키즈북·학술논문) |
| 교보 구독형 `KYOBO_SUBSCRIPTION_V1` | `*.dkyobobook.co.kr`, 경로에 `elibrary-front` 없음 | `search/searchList.ink` | `content/contentList.ink?contentAll=Y&ctgrId=` | 99 (80 사용) | "총 N 개" | `cttsDvsnCode` 001=전자책, 002=오디오북으로 보임 |
| YES24 새 화면 `YES24_V2`(UTF-8)·`V3`(EUC-KR) | `/search/?src_key=`, `/ebook/detail/?goods_id=` | `srch_order=title\|author\|publisher` | 빈 검색 | 10 고정 | "전체 N건" | 오디오북은 `/audiobook/` 목록에 따로 |
| YES24 옛 화면 `YES24_V1` | EUC-KR, `#booklist ul.list > li.line` | 위와 같음 + `view=` | 빈 검색 | 50 | "전체 N건" | 오디오북 메뉴 못 봄 |
| 통합 포털 `ELIB_PORTAL_V1` | `…/elib/module/elib/…do` | `search/index.do`·`table.do` | `book/index.do?menu=NEW&type=EBK\|ADO` | 100 | 없음 (빈 쪽까지) | `type=EBK`/`ADO` 목록으로 |
| 강남구 `GANGNAM_V1` | `ebook.gangnam.go.kr/elibbook/*.asp` | `book_info.asp?search=title\|author\|publisher` | `book_category.asp` | 20 고정 | "N 건" | 줄마다 "eBook" 표시. 오디오북은 로그인 전용 별도 서비스 |
| 북큐브 `FxLibrary` (+다산 `*.dasangng.co.kr`, `BookLive`) | 경로 `/FxLibrary…/` | `xml/productSearch/?keyword=&keyoption2=1\|2\|3` | `xml/productList/` (XML) | 100 | `<totalItem>` | `<format>`·`<category_type>` (오디오북 값은 못 봄) |
| 알라딘 | `sub/sub.htm?nav_code=…`, `*.aladinebook.co.kr` | `code=search&search_item=title\|author` | `sub.htm?nav_code=…&view_level=1` | 100 | "총 N 건의 도서" | 사이트 단위(예: "경북도서관 오디오북"은 사이트 전체가 오디오북) |

## 2. 교보 일반 `KYOBO_V1` [확정] (표본: 고흥 `https://www.ghlib.go.kr:446`)

- 검색: `{base}/elibrary-front/search/searchList.ink?schClst={ctts|autr|pbcm|all}&schDvsn=000&schTxt={검색어}&recordCount={N}&pageIndex={쪽}`
  - `ctts`=제목, `autr`=저자, `pbcm`=출판사, `all`=통합. UTF-8
  - 결과 안 재검색(`reSch`)으로 느슨한 AND 가능 (사이클 03 조사)
- 전체 목록: 위 주소에 `schClst=all&schTxt=%20`(공백 하나). 빈 문자열이면 결과 화면이 아님
- 한 쪽 줄 수: `recordCount` 500까지 시험함(고흥 3.3MB, 0.9초). 작은 서버(`ebook.jbe.go.kr` 교육문화회관들)는 500에서 응답이 끊겨(IncompleteRead) 100으로 받음
- 목록 줄 `ul.book_resultList > li`:
  - `span.small` 종류(전자책·오디오북·오디오·동영상북·키즈북·학술논문), `span.store` 공급사
  - `li.tit a` 제목. `onclick="…fnContentClick(this, '{cttsDvsnCode}', '{brcd}', '{ctgrId}', '{sntnAuthCode}', …)"`
  - `li.writer` = `저자<span>출판사</span>출간일`
  - `li.txt` 소개글(저장하지 않음), `p.use` "대출 : X/Y 예약 : Z"(X 대출 중, Y 보유)
  - 표지 `div.img img[src]`(프로토콜 없는 `//…`)
- 상세: `{base}/elibrary-front/content/contentView.ink?cttsDvsnCode=&brcd=&ctgrId=&sntnAuthCode=`. ISBN 없음. 파일 크기·형식(EPUB 등) 있음
- 예외: 국회도서관(`ebook.nanet.go.kr:8089`)은 브라우저가 아닌 요청에는 머리글과 관계없이 빈 쪽(19바이트)을 줌. 브라우저로는 17,162권 확인. 자동 접근 차단으로 보고 우회하지 않음 (2026-10-01)
- 예외: 전북교육청 통합 교보(`ebook.jbe.go.kr:10111`)는 브라우저에도 "L7 Secure connect fail". 같은 교육청 책은 통합 포털(`lib.jbe.go.kr/elib`, 도서관 15)에 교보 47,868줄로 있음
- 작은 서버(칠곡, 전북 교육문화회관)는 500줄 쪽에서 끊김 → 100줄
- 분야별 목록: `{base}/elibrary-front/content/contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId={분야 코드 앞 두 자리}&orderByKey=cttsHnglName&pageIndex=N&recordCount=N`. 분야마다 "총 N 개"가 있음. 정렬 값은 구독형과 같음(`publDate`, `cttsHnglName` …). 칠곡은 특정 책이 든 쪽이 정렬과 관계없이 늘 시간 초과 → 분야별로 나누고, 끊긴 쪽은 10권·1권 쪽으로 다시 읽어 채움. 1권 쪽은 모두 열려 9,347/9,348권 [확정] (2026-10-03)

## 3. 교보 구독형 `KYOBO_SUBSCRIPTION_V1` [확정] (표본: 세종 구독 `https://sejonglib.dkyobobook.co.kr` 외 20곳)

- 검색: `{base}/search/searchList.ink?…` (일반과 같은 값, 경로 앞 `elibrary-front` 없음). 빈 검색은 0건
- 전체 목록: `{base}/content/contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId=&orderByKey={정렬}&pageIndex={쪽}&recordCount={N}`
  - `ctgrId=`와 `contentAll=Y`가 없으면 500 오류
  - `recordCount` 최대 99("조회 건수 최대값[99]" 안내). 80을 씀. 큰 목록은 한 쪽 5~7초
  - `orderByKey`: `publDate`(최신순), `loanNmvl`(인기순), `cttsHnglName`(제목순), `sntnAuthName`(저자순). **최신순이 있어 매일 신간 수집에 쓸 수 있음**
  - `cttsDvsnCode`: 001 전자책. 002는 오디오북으로 보임 [확인 필요]
- 목록 줄 모양은 교보 일반과 같음(`contentList.fnContentClick`). 줄에 "대출 : X/Y"도 있음
- 목록 크기가 사이트마다 다름 (2026-09-30):
  - 큰 목록 16곳: 180,392~244,765권(세종 208,968, 동해 244,765 등). 같은 서버라 한 곳씩 받으면 각 4시간 안팎
  - 작은 목록 5곳: 9~804권(거제시청, 노형꿈틀, 대구교육청, 박정희대통령기념, 울릉)
- 모바일 화면: User-Agent에 데스크톱 브라우저 표시가 없으면 전체 건수가 없는 모바일 화면이 옴
- 교보 기관 사이트(`*.dkyobobook.co.kr`)는 첫 화면 스크립트에 `library_model: '대여'|'소장'|'혼합'`이 있음 [확정] (2026-10-03). 대여·혼합은 교보 구독 목록이 붙어 20만 권 안팎(신용보증기금 238,118), 소장은 대개 수백~수천 권. 경로가 `/elibrary-front` 없이 `/main.ink`, `/content/contentList.ink`이면 구독형과 같은 방법으로 받음
- `contentList.ink`의 `recordCount`는 20·40·60·80만 받음(1이나 99면 HTTP 500) [확정] (2026-10-03). "총" 다음에 `&nbsp;`가 있어 HTML 엔티티를 풀고 읽어야 함

## 4. YES24 [확정] (표본: 화성 `hsg.yes24library.com`(V2), 성동 `ebook.sdlib.or.kr:8092`(V1), 춘천 `elib.chuncheon.go.kr:447`(V2))

- 검색: `{base}/search/?src_key={검색어}&srch_order={title|author|publisher|total}&sort={title|pubdt|lending|reserve|now}&page_num={쪽}`
  - `sort=now`는 정렬이 아니라 "지금 대출 가능"만 거르는 값(dev adapter 주석). `sort=pubdt`는 출간일순 → 신간 수집 후보 [확인 필요: 등록일순인지]
  - V1·V3는 검색어를 EUC-KR로 보내야 함(UTF-8이면 0건)
  - `srch_order=total` + 띄어쓰기로 느슨한 AND (사이클 03 조사)
- 전체 목록: 빈 검색 `src_key=`
- 한 쪽: V2·V3 10 고정(`view=100`·`view=50` 무시). V1은 `view=5|10|20|50`, 50 사용
- 목록 줄:
  - V2·V3 `div.ebook-list div.bx`: `p.tit a`(제목, `goods_id`), `p.tit strong`(`[분야]` 또는 시리즈명), `p.writer`, `p.detail span`(출판사, 출간일, 공급사), `div.stat li`(보유·대출·예약), 표지 `a.thumb img`
  - V1 `#content div#booklist ul.list > li.line`: `span.book_kind`(분야), `span.book_tit > a`, `ul.book_txt li[title=저자|출판사|출간일|공급사]`, `div.rentinfo`. `#content` 밖 옆 칸에도 `li.line`이 있어 범위를 좁혀야 함
- `goods_id`는 YES24 판매 상품 번호와 같아 보임(표지 `image.yes24.com/goods/{goods_id}`) [확인 필요]. **교보가 공급한 책은 `480D231030630`처럼 영문이 섞인 번호** [확정] (송파, 2026-09-30) → 숫자만 읽으면 서로 다른 책이 한 줄로 합쳐짐
- **검색 목록에 다른 종류가 섞임** [확정] (마포나루, 2026-09-30): 줄의 링크가 `/ebook/detail?goods_id=`(전자책), `/booklearning/detail?goods_id=`(동영상북, 메뉴 이름 "동영상북"), `/audiobook/detail?goods_id=`(오디오북). 상세 URL과 종류는 링크 경로 그대로 써야 함
- 제목 앞 `[…]`: 분야일 때도 있고 시리즈명(예: `[100분 고전 013]`)일 때도 있음. 떼어 분야로 쓰면 안 됨 (다른 세션 점검, 2026-09-30)
- **오디오북**: 전자책 검색과 따로 `{base}/audiobook/?mode=total&sort=title&cate_id=&page_num={쪽}` 목록. 모양은 V2와 같고 상세는 `/audiobook/detail/?goods_id=`. 첫 화면에 `href="/audiobook/"` 링크가 있는 사이트만 있음(춘천 975건, 청주·김제·한밭 0건)
- 상세(V2): 파일포맷(EPUB 등), 파일크기, 등록일, 출판일. ISBN 없음
- 줄마다 책 코드가 없는 경우가 있음(경산·광주·속초·포천·금산 등) → 그 줄만 건너뜀
- 예외 (2026-10-01 확인):
  - 영천·영주: 수집 중 한 번씩 목록 대신 오류 화면이 옴 → 같은 쪽을 다시 읽으면 정상
  - 아산 131쪽, 금산 20쪽: 제목순 그 쪽만 늘 서버 오류(500). 출판일순(`sort=pubdt`)이면 같은 쪽 번호도 정상 → 깨진 쪽은 건너뛰고 출판일순으로 한 번 더 읽어 채움
  - 군포 YES24 주소는 교보(`ebook.gunpolib.go.kr:444`, 이미 있는 도서관 46)로 넘어감 → 따로 받지 않음
  - 김천: 2026-09-30부터 스크립트 요청은 연결을 끊고 브라우저만 열림 → 우회하지 않음

## 5. 통합 포털 `ELIB_PORTAL_V1` [확정] (표본: 부산 `library.busan.go.kr/elib` 외 6곳)

- 검색: dev `ElibPortalV1Adapter` 주석 참고(첫 쪽 `search/index.do`, 다음 쪽 `search/table.do`, 저자·출판사는 `author_name`·`book_pubname` 거르기). 빈 검색은 0건
- 전체 목록: `{root}/module/elib/book/index.do?menu_idx={메뉴}&menu=NEW&type={EBK|ADO}&rowCount=100&viewPage={쪽}` ("새로 들어온 책")
  - `menu_idx`는 포털마다 다름(부산 14/19, 강원 126/129, 인천 11/23) → 첫 화면에서 `book/index.do?menu_idx=N&menu=NEW&type=EBK` 링크를 찾아 씀
  - 전체 건수가 없음 → 빈 쪽이 나올 때까지. 최신순이라 도중에 책이 들어와도 빠지지 않고 겹치기만 함
  - 인천교육청은 이 목록에 로그인이 필요함("로그인 후 이용가능합니다"). 대신 **검색어를 공백 하나로 넣은 검색**(`search/table.do?menu_idx=10&…&search_text=%20&rowCount=100&viewPage=N`)이 로그인 없이 전체(9,972건, 전자책·오디오북 함께)를 보여 줌 [확정] (2026-10-01). 줄은 `div.row a.name.goDetail[data-book_idx][data-type]`, 그 아래 `p`가 저자 / "출판사, 날짜" / 소속도서관. 상세 메뉴 번호는 쪽 스크립트에 있음(인천 전자책 11, 오디오북 23)
- 목록 줄 `ul.bbs_webzine > li`: `a.book_link[data-book_idx]`, `div.info span`(저자, 출판사, 출간일), `div.meta`(소속도서관, 공급사, 대출 가능 여부, "대출 : X/Y", "예약 : Z/W"), 표지 주소(파일 이름이 ISBN인 경우 있음, 예: `9791194267676.jpg` [확인 필요])
- 한 쪽에 같은 책이 두 번 나오는 경우 있음(경북 100줄 중 96개) → 책 번호로 겹침 제거
- 상세: `{root}/module/elib/book/view.do?menu_idx={목록 메뉴 번호}&book_idx=&type=`. 강원은 `menu_idx`가 없으면 빈 화면, 목록 메뉴 번호(126)나 검색 메뉴 번호(2)를 붙이면 열림 [확정] (2026-09-30)
- 여러 업체(교보·YES24·알라딘·웅진 등) 책이 한 목록에 섞이고, 소속도서관(분관)도 줄마다 있음

## 6. 강남구 `GANGNAM_V1` [확정] (`https://ebook.gangnam.go.kr`, EUC-KR ASP)

- 검색: `/elibbook/book_info.asp?search={title|author|publisher}&strSearch={EUC-KR 검색어}&page_num=` (빈 검색은 0건)
- 전체 목록: `/elibbook/book_category.asp?page_num={쪽}` — 6,240건, 한 쪽 20 고정(`list_num` 무시)
- 줄 `div.book_info`: `div.book_title a`(`book_num=`, `KB…`/`B…`/ISBN 모양 섞임), `div.writer`, `div.publish_date`("출판사 · 날짜"), `div.publish_ebook`("eBook · 날짜" → 종류), `div.current strong`(공급사), 보유·대출·예약
- 오디오북은 로그인해야 쓰는 별도 서비스("오디오북 전용 서비스입니다")

## 7. 북큐브 `FxLibrary` [확정] (표본: 울산동부 `https://ebook.usdl.or.kr/FxLibrary`, 사상, 주왕산숲속(다산))

사이트 안에 **XML 목록·검색 주소**가 있어 가장 다루기 쉽습니다.

- 기본 경로: 주소의 `/FxLibrary`, `/FxLibrary_xx`(교육청·경남처럼 한 서버에 여러 도서관), `/BookLive`까지
- 전체 목록: `{base}/xml/productList/?itemCount={N}&page={쪽}&sort={1|2|3}` — `application/xml; charset=UTF-8`
  - `itemCount` 100 동작(대소문자 구분: `itemcount`는 무시). `sort` 1=제목, 2=출판일, 3=구매일(기본). **구매일순으로 신간 수집 가능** [안]
  - `<ProductListParam>`에 `<totalItem>`(종 수), `<totalVolume>`(권 수), `<page>`, `<itemCount>`
- 검색: `{base}/xml/productSearch/?keyword={검색어}&keyoption2={0|1|2|3}` — 0 전체, 1 서명, 2 저자, 3 출판사(울산동부 "김" 저자 830건, "민음사" 출판사 10건 확인)
  - HTML 상세검색 폼에는 `name`(서명)·저자·출판사 칸이 따로 있음(`searchType=detail`) → 필드별 AND 후보 [확인 필요]
- `<Product>` 항목: `num`(북큐브 번호, 예 `F0003004`), `legacyCode`(교보식 `480…` 코드), `name`, `publisher`, `author`, `supplier`(예 "교보 전자책"), `category`, `category_type`, `publisherDate`, `buyDate`(도서관 구매일), 표지 4종, `longExplain`·`shortExplain`·`contents`(저장하지 않음), `volume`(보유 권수), `nowLendCount`, `nowReserveCount`, `format`(epub·pdf·xml·flash 확인), `isReservable`
- HTML 화면: `/FxLibrary/product/list/`, 상세 `/FxLibrary/product/view/?num=`
- 오디오북: `category_type`은 모두 `book`이고, **`format`이 `aud`이면 오디오북** [확정] (2026-10-01, 134곳 수집 결과 19,417줄). 다른 형식 값: epub, pdf, xml, `pdf/epub`, flash, image, kml, xdf, zip
- 다산(`*.dasangng.co.kr`)은 북큐브네트웍스가 운영하는 같은 프로그램. XML도 같음(주왕산숲속 100건)
- 후보 확인 결과(2026-09-30, 웹러브 북스 목록에서 공공도서관으로 보이는 174곳): 열림·XML 확인 134곳, 오류 40곳(주소 없어짐·접속 거부). 134곳 합계 1,187,834종 [확정] (`totalItem` 합)

## 8. 알라딘 [확정·일부] (표본: 울산과학대 `https://uc.aladinebook.co.kr`, 고령 다산도서관 `ebook.goryeong.go.kr:8888`)

- 첫 화면 `/`가 `main/main.htm`로 넘어감(meta refresh). 선언은 EUC-KR인데 본문은 UTF-8인 사이트가 있음 → 검색어는 UTF-8
- 전체 목록: `{root}/sub/sub.htm?nav_code={전자책 메뉴 코드}&view_level=1&list_scale={N}&startPage={쪽}` — 전자책 메뉴 코드는 첫 화면의 `sub.htm?nav_code=…&view_level=1&scroll=0` 링크에서 찾음(울산과학대 `ebo1655356114`). "총 2,048 건의 도서가 있습니다". `list_scale` 100 동작
- 검색: `sub.htm?nav_code={코드}&code=search&search_item={title|author}&search_order={검색어}&view_level=1` (제목 "사랑" 63건, 저자 "김" 579건). 출판사 값은 못 찾음 [확인 필요]
- 목록 줄 `li.line`: 제목 `h2.book_tit a`, `span.writer`, `span.pub`, `span.volumecnt`(보유)·`uselendingcnt`(대출)·`usereservecnt`(예약), 상세 `sub.htm?mode=view&mv_data={base64}&goods_id={알라딘 상품 번호}`, 공급사 표시 `div.companyALADIN`
- 수집기 `ALADIN_V1` (2026-10-03): 목록 `{root}/sub/sub.htm?nav_code={전자책 메뉴}&order_list=2&list_scale=100&view_level=1&startPage=N` — `order_list` 0 최다대출, 1 출판일, 2 제목, 3 최다예약, 4 대출가능. 줄 `ul.list > li.line`의 `h2.book_tit a`(`goods_id`), `span.writer`, `span.pub`, 표지 `div.book-img1` 배경 주소, 공급사 `div.com div.company…`. 상세는 짧은 주소 `{root}/sub/sub.htm?mode=view&nav_code=…&goods_id=…`도 열림(긴 `mv_data`는 필요 없음). 상세에 파일포맷(epub 등) 있음. 목록에는 출간일이 없음 [확정] (울산과학대 표본)
- 사이트마다 차이가 큼(2026-09-30, 공공으로 보이는 28곳): 위 방법으로 센 곳 4곳(경북도서관 오디오북 2,610, 다산도서관 4,092, 테라테크 체험 57,027, 하용조기념 137), 다른 화면 15곳, 오류 9곳. 경남·경북·경기교육청 등은 통합 포털이나 다른 화면으로 옮김
- **오디오북**: "경북도서관 오디오북"(`www.gb.go.kr:8089`)처럼 사이트 전체가 오디오북인 곳이 있음 → 도서관(사이트) 단위로 종류를 기록해야 함

## 9. 오디오북 구분 요약 (사용자 요청 "오디오북 있으면 표시", 2026-09-30)

| 플랫폼 | 방법 | 저장 값 |
|---|---|---|
| 교보 일반 | 줄의 `span.small` 표시 | `content_kind` 원문, `kind_source=SITE_LABEL` |
| 교보 구독형 | 목록 종류 `cttsDvsnCode`(001만 받음) | `전자책`, `SITE_SECTION` |
| YES24 | `/search/` = 전자책, `/audiobook/` = 오디오북 | `section=MAIN|AUDIO`, `SITE_SECTION` |
| 통합 포털 | `type=EBK` / `ADO` 목록 | `section=EBK|ADO`, `SITE_SECTION` |
| 강남구 | `div.publish_ebook` 표시 | `SITE_LABEL` |
| 북큐브 | `format`이 `aud` | `content_kind`=`book/aud`, `SITE_LABEL` |
| 알라딘 | 사이트 단위 | — |

- 정리된 종류는 SQLite 뷰 `holding_typed.content_type`(EBOOK, AUDIOBOOK, VIDEO_BOOK, KIDS_BOOK, PAPER, UNKNOWN). 교보의 "오디오"와 "오디오북"은 둘 다 AUDIOBOOK으로 봄 [안]
- 사이트가 전자책이라 했지만 제목에 "오디오북"이 있는 줄(예: "(본문 포함 e오디오북)", "영어 원서+오디오북")은 뷰 `kind_check_needed`에 모음(2026-09-30 기준 452줄). 전자책과 오디오가 함께 든 상품으로 보이며, 어떻게 보여 줄지는 사용자 결정 [확인 필요: 사용자에게 질문]

## 10. 확인 필요

| 항목 | 확인 방법 |
|---|---|
| 교보 구독형 `cttsDvsnCode=002`가 오디오북 목록인지 | API 호출 시험 |
| YES24 `sort=pubdt`가 등록일순인지 출간일순인지 | API 호출 시험 |
| 북큐브 상세검색으로 필드별 AND가 되는지 | API 호출 시험 |
| 알라딘 출판사 검색 값, 알라딘 화면 종류별 목록 방법 | API 호출 시험 |
| 국회도서관(교보) 화면, 영천·영주 YES24 화면 | API 호출 시험 |
| 인천교육청 포털 전체 목록 | 로그인 없이 가능한 목록 찾기 |
| 포털 표지 파일 이름이 ISBN인지 | 표본 대조 |

## 출처

- 모두 2026-09-30 직접 호출. 표본 주소는 각 절 제목에 적음
- 기관 후보 목록: 웹러브 북스 https://ebook.okbari.net/library (제3자 자료, 수집 기준일 2026-02-06으로 보임)
- 다산 운영사: 다산 사이트 개인정보처리방침 https://hec.dasangng.co.kr/FxLibrary/user/privacy/ (검색 요약으로 확인, 원문 미확인 [확인 필요])
