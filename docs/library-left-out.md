# 서비스에 넣지 않은 도서관 목록

- 작성일: 2026-10-12
- 목적: 수집 DB에는 있지만 서비스에 넣지 않는 도서관(A)과, 서비스에 있지만 문제가 있는 도서관(B)을 한곳에 모읍니다(사용자 요청 2026-10-12, 두 절 모두).
- **서비스 DB에 이미 있는 도서관은 기본으로 서비스에 그대로 둡니다**(사용자 결정 2026-10-12). B절은 알림용이며, 끄거나 빼는 결정이 아닙니다.
- 출처: 넣기 명령 `library-insert` 미리보기 보고서 `1af0721e4a3e`(만든 때 2026-10-12T11:05:35.270353200Z), 수집 DB 사본 `ebook-catalog-2026-10-12-0135.sqlite`(읽기 전용), 규칙 폴더 `scripts/library/rules/`(`kyobo_probe.tsv`, `demo_or_closed.tsv`, `same_site.tsv`, `library_kinds.tsv`, `gone_known.tsv`), 서비스 `library.library`·`collection.collection_link` 내보내기, 끈 이유 `scripts/library/disabled_reasons.tsv`.
- 다시 만들기: `python scripts/library/list_left_out.py`(사용법은 스크립트 머리말). 손으로 고치지 않습니다.
- 관련: [`library-site-issues.md`](library-site-issues.md)(빠진 사이트와 이유의 옛 기록), [`library-maintenance.md`](library-maintenance.md) 6-2(끄기)·13절(작업 기록).

## A. 수집 DB에 있지만 넣지 않는 곳 (349곳)

넣기 명령은 서비스와 짝이 없는 수집 도서관마다 아래 규칙을 차례로 보고, **처음 맞는 규칙 하나**로 뺍니다(`LibraryInsertPlanner`, plan R2-5). 그래서 한 곳은 한 표에만 나옵니다. 같은 보고서로 새로 넣는 곳은 6곳입니다: 100584 여주대학교, 102029 한국선원복지고용센터, 102084 여주대학교, 102092 김천대학교, 102101 경운대학교, 102171 천상중학교.

| 절 | 이유 | 곳 | 뜻 |
|---|---|---|---|
| A-1 | 교보 구독형 대여·혼합 (`kyobo_rental_mixed`) | 232 | 교보 구독형(`KYOBO_SUBSCRIPTION_V1`) 중 probe(`kyobo_probe.tsv`, `base_url` 끝 `/`를 떼고 맞춤) 운영 방식이 대여(`RENTAL`)나 혼합(`MIXED`)인 곳. 수집도 서비스도 하지 않음(plan ADR-0067) |
| A-2 | 수집 DB가 받지 않음 (`crawl_skip`) | 105 | 수집 DB `library.crawl_skip`에 이유가 적힌 곳. 이유는 그 칸 글 그대로 |
| A-3 | 전체 수집이 끝나지 않음 (`no_full_success`) | 2 | 수집 DB `library.last_full_success_at`이 비어 있는 곳. 수집이 한 번 끝나면 다음 넣기에서 들어감 |
| A-4 | 체험용·운영 중단 (`demo_or_closed`) | 4 | `demo_or_closed.tsv`에 있는 곳(사람이 정함) |
| A-5 | 서비스 도서관과 같은 사이트 (다른 포트) (`same_site`) | 0 | `same_site.tsv`에 있는 곳(사람이 정함) |
| A-6 | 시험용 (종류 `TEST`) (`test`) | 6 | `library_kinds.tsv` 종류가 `TEST`인 곳 |

### A-1. 교보 구독형 대여·혼합 (232곳)

교보 구독형(`KYOBO_SUBSCRIPTION_V1`) 중 probe(`kyobo_probe.tsv`, `base_url` 끝 `/`를 떼고 맞춤) 운영 방식이 대여(`RENTAL`)나 혼합(`MIXED`)인 곳. 수집도 서비스도 하지 않음(plan ADR-0067).

운영 방식별: MIXED 194곳, RENTAL 38곳.

| 수집 번호 | 이름 | 화면 종류 | 주소 | 자세히 |
|---|---|---|---|---|
| 102507 | 덕성여자대학교 | `KYOBO_SUBSCRIPTION_V1` | `https://duksunguniv.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102508 | 부산가톨릭대학교 | `KYOBO_SUBSCRIPTION_V1` | `https://cupl.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102509 | 부산외국어대학교 | `KYOBO_SUBSCRIPTION_V1` | `https://bufs.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102510 | 성균관대학교 | `KYOBO_SUBSCRIPTION_V1` | `https://skkuebook.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102511 | 연세대학교 | `KYOBO_SUBSCRIPTION_V1` | `https://yonseilib.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102512 | 원광대학교 | `KYOBO_SUBSCRIPTION_V1` | `https://wku.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102513 | 한국에너지공과대학교(혼합형) | `KYOBO_SUBSCRIPTION_V1` | `https://kentech.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102514 | KOFICE | `KYOBO_SUBSCRIPTION_V1` | `https://kofice.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102515 | 건설근로자공제회 | `KYOBO_SUBSCRIPTION_V1` | `https://cw.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102516 | 경기도치과의사회 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://ggda.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102517 | 경남 코디세이 | `KYOBO_SUBSCRIPTION_V1` | `https://codyssey.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102518 | 교보문고 전자책(국방부도서관) | `KYOBO_SUBSCRIPTION_V1` | `https://mnd.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102519 | 국립농산물품질관리원 | `KYOBO_SUBSCRIPTION_V1` | `https://naqs.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102520 | 국립종자원 | `KYOBO_SUBSCRIPTION_V1` | `https://seed.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102521 | 국립해양박물관(임직원) | `KYOBO_SUBSCRIPTION_V1` | `https://mmk2.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102522 | 국민권익위원회 | `KYOBO_SUBSCRIPTION_V1` | `https://acrc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102523 | 국방기술품질원 | `KYOBO_SUBSCRIPTION_V1` | `https://dtaqlib.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102524 | 금융보안원 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://fsec.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102525 | 김해도시개발공사 | `KYOBO_SUBSCRIPTION_V1` | `https://ghdc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102526 | 농림축산검역본부 | `KYOBO_SUBSCRIPTION_V1` | `https://qia.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102527 | 부산관광공사 | `KYOBO_SUBSCRIPTION_V1` | `https://bto.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102528 | 부산환경공단 | `KYOBO_SUBSCRIPTION_V1` | `https://beco.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102529 | 산림청 | `KYOBO_SUBSCRIPTION_V1` | `https://forest.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102530 | 서울시의회 | `KYOBO_SUBSCRIPTION_V1` | `https://smcl.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102531 | 서울연구원 전자책도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://si.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102532 | 성남시육아종합지원센터 | `KYOBO_SUBSCRIPTION_V1` | `https://sscc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102533 | 신용보증기금 | `KYOBO_SUBSCRIPTION_V1` | `https://kodit.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102534 | 아동권리보장원 | `KYOBO_SUBSCRIPTION_V1` | `https://ncrc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102535 | 전라북도교육청 통합도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://jbe.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102536 | 정보통신산업진흥원 | `KYOBO_SUBSCRIPTION_V1` | `https://nipa.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102537 | 제우스 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://zeus.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102538 | 조달청 | `KYOBO_SUBSCRIPTION_V1` | `https://pps.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102539 | 주택도시보증공사 | `KYOBO_SUBSCRIPTION_V1` | `https://khug.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102540 | 중소벤처기업부(직원용) | `KYOBO_SUBSCRIPTION_V1` | `https://mss.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102541 | 지식재산처 | `KYOBO_SUBSCRIPTION_V1` | `https://moip.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102542 | 코렌스 | `KYOBO_SUBSCRIPTION_V1` | `https://korens.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102543 | 한국공예디자인문화진흥원 | `KYOBO_SUBSCRIPTION_V1` | `https://kcdf.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102544 | 한국공인회계사회 | `KYOBO_SUBSCRIPTION_V1` | `https://kicpa.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102545 | 한국과학기술기획평가원 | `KYOBO_SUBSCRIPTION_V1` | `https://kistep.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102546 | 한국과학기술정보연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kisti.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102547 | 한국관세무역개발원도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://kctdi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102548 | 한국교육개발원 e북도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://askkedi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102549 | 한국교육과정평가원 | `KYOBO_SUBSCRIPTION_V1` | `https://kice.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102550 | 한국교육학술정보원 | `KYOBO_SUBSCRIPTION_V1` | `https://keris.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102551 | 한국교직원공제회(임직원용) | `KYOBO_SUBSCRIPTION_V1` | `https://ktcu.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102552 | 한국국제교류재단 | `KYOBO_SUBSCRIPTION_V1` | `https://kf.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102553 | 한국보건복지인재원 | `KYOBO_SUBSCRIPTION_V1` | `https://kohi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102554 | 한국생명공학연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kribb.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102555 | 한국석유관리원 | `KYOBO_SUBSCRIPTION_V1` | `https://kpetro.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102556 | 한국세무사회 | `KYOBO_SUBSCRIPTION_V1` | `https://kacpta.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102557 | 한국에너지공단 | `KYOBO_SUBSCRIPTION_V1` | `https://kemcoebook.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102558 | 한국원자력안전기술원 | `KYOBO_SUBSCRIPTION_V1` | `https://kins.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102559 | 한국재정정보원 | `KYOBO_SUBSCRIPTION_V1` | `https://fis.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102560 | 한국청소년상담복지개발원 | `KYOBO_SUBSCRIPTION_V1` | `https://kyci.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102561 | 한국특허기술진흥원 | `KYOBO_SUBSCRIPTION_V1` | `https://kipro.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102562 | 한국해양과학기술원 | `KYOBO_SUBSCRIPTION_V1` | `https://kiost.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102563 | 한국해양수산개발원 | `KYOBO_SUBSCRIPTION_V1` | `https://kmi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102564 | 현대미술관 e북도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://mmca.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102565 | 국토안전관리원 | `KYOBO_SUBSCRIPTION_V1` | `https://kalis.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102566 | 부산교통공사 VL | `KYOBO_SUBSCRIPTION_V1` | `https://busanmetro.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102567 | 부산김해경전철 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://bglrt.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102568 | 여수광양항만공사(YGPA)전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://ygpa.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102569 | 중소기업기술정보진흥원 | `KYOBO_SUBSCRIPTION_V1` | `https://tipa.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102570 | 한국가스안전공사 | `KYOBO_SUBSCRIPTION_V1` | `https://kgs.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102571 | 한국거래소 | `KYOBO_SUBSCRIPTION_V1` | `https://krx.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102572 | 한국관광공사 | `KYOBO_SUBSCRIPTION_V1` | `https://kto.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102573 | 한국산업인력공단 | `KYOBO_SUBSCRIPTION_V1` | `https://hrdkorea.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102574 | 한국자산관리공사 | `KYOBO_SUBSCRIPTION_V1` | `https://kamco.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102575 | 대구학생전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://dgelib-r.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102576 | 전남광주통합특별시 서구 통합도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://seogulib.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102577 | ABL생명보험 | `KYOBO_SUBSCRIPTION_V1` | `https://abl.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102578 | ASML코리아 e북도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://asml.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102579 | BNK경남은행 | `KYOBO_SUBSCRIPTION_V1` | `https://knbank.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102580 | BNK금융지주 | `KYOBO_SUBSCRIPTION_V1` | `https://bnkfg.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102581 | BNK시스템 | `KYOBO_SUBSCRIPTION_V1` | `https://bnksys.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102582 | DB Inc | `KYOBO_SUBSCRIPTION_V1` | `https://inc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102583 | DL건설 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://dlcon.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102584 | DL리더 | `KYOBO_SUBSCRIPTION_V1` | `https://daelim.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102585 | DSR | `KYOBO_SUBSCRIPTION_V1` | `https://dsr.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102586 | FESTO | `KYOBO_SUBSCRIPTION_V1` | `https://festo.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102587 | HSAD | `KYOBO_SUBSCRIPTION_V1` | `https://hsad.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102588 | KB국민은행(임직원) | `KYOBO_SUBSCRIPTION_V1` | `https://kbstar.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102589 | KB손해보험 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://kbinsure2.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102590 | KN노조도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://knlibrary.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102591 | LG 경영연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://lgbr.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102592 | LGSP 도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://lgsp.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102593 | LG인화원 | `KYOBO_SUBSCRIPTION_V1` | `https://lgacademy.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102594 | LG화학 대전 기술연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://lgchemdj.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102595 | LX세미콘 | `KYOBO_SUBSCRIPTION_V1` | `https://lxsemicon.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102596 | NHN 라이브러리 딥 | `KYOBO_SUBSCRIPTION_V1` | `https://nhn.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102597 | SC제일은행 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://scbk.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102598 | SDT | `KYOBO_SUBSCRIPTION_V1` | `https://sdt.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102599 | SK에코플랜트 | `KYOBO_SUBSCRIPTION_V1` | `https://skecoplant.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102600 | SK하이스텍 | `KYOBO_SUBSCRIPTION_V1` | `https://skhystec.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102601 | miraeasset | `KYOBO_SUBSCRIPTION_V1` | `https://miraeasset.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102602 | 강동성심병원 | `KYOBO_SUBSCRIPTION_V1` | `https://kangdong.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102603 | 고대구로병원 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://kumcguro.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102604 | 고려아연 | `KYOBO_SUBSCRIPTION_V1` | `https://koreazinc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102605 | 고영테크놀러지 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://kohyoung.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102606 | 광주환경공단 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://eco-g.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102607 | 교보생명 FP | `KYOBO_SUBSCRIPTION_V1` | `https://kyobofp.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102608 | 교보생명(퇴직연금마케팅) | `KYOBO_SUBSCRIPTION_V1` | `https://klib.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102609 | 그레이트북스 도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://greatbooks.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102610 | 그루도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://gridwiz.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102611 | 네패스 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://nepes.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102612 | 농협정보시스템전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://nonghyupit.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102613 | 다쏘시스템코리아 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://3ds.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102614 | 더존그룹 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://douzone.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102615 | 동국제강그룹 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://dongkuk.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102616 | 동국제약 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://dkpharm.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102617 | 동양생명 | `KYOBO_SUBSCRIPTION_V1` | `https://tongyang.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102618 | 롯데카드 e북도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://lottecard.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102619 | 르노코리아 | `KYOBO_SUBSCRIPTION_V1` | `https://rsm.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102620 | 멀티캠퍼스_SSAFY(서울_1학기) | `KYOBO_SUBSCRIPTION_V1` | `https://ssafy5.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102621 | 멀티캠퍼스_SSAFY(서울_2학기) | `KYOBO_SUBSCRIPTION_V1` | `https://ssafy.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102622 | 멀티캠퍼스_SSAFY(지역_1학기) | `KYOBO_SUBSCRIPTION_V1` | `https://ssafy4.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102623 | 멀티캠퍼스_SSAFY(지역_2학기) | `KYOBO_SUBSCRIPTION_V1` | `https://ssafy2.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102624 | 바툼낭 | `KYOBO_SUBSCRIPTION_V1` | `https://batumnang.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102625 | 버들도서관(유한양행) | `KYOBO_SUBSCRIPTION_V1` | `https://yuhan.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102626 | 부산도시가스 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://bscg.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102627 | 비즈테크아이 | `KYOBO_SUBSCRIPTION_V1` | `https://biztech.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102628 | 삼성SDI | `KYOBO_SUBSCRIPTION_V1` | `https://sdi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102629 | 삼성SDS | `KYOBO_SUBSCRIPTION_V1` | `https://sds.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102630 | 삼성글로벌리서치 | `KYOBO_SUBSCRIPTION_V1` | `https://seri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102631 | 삼성디스플레이 | `KYOBO_SUBSCRIPTION_V1` | `https://sdc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102632 | 삼성디자인학교(SADI) | `KYOBO_SUBSCRIPTION_V1` | `https://sadi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102633 | 삼성물산(건설부문) | `KYOBO_SUBSCRIPTION_V1` | `https://secc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102634 | 삼성물산(상사부문) | `KYOBO_SUBSCRIPTION_V1` | `https://samsungcnt.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102635 | 삼성물산(패션부문) | `KYOBO_SUBSCRIPTION_V1` | `https://cheil.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102636 | 삼성바이오로직스 | `KYOBO_SUBSCRIPTION_V1` | `https://biologics.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102637 | 삼성웰스토리 | `KYOBO_SUBSCRIPTION_V1` | `https://welstory.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102638 | 삼성전자 SAIT | `KYOBO_SUBSCRIPTION_V1` | `https://sait.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102639 | 삼성전자서비스 | `KYOBO_SUBSCRIPTION_V1` | `https://samsungsvc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102640 | 삼성증권(임직원) | `KYOBO_SUBSCRIPTION_V1` | `https://samsungpop.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102641 | 삼성카드 | `KYOBO_SUBSCRIPTION_V1` | `https://samsungcard.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102642 | 삼성카드고객서비스 | `KYOBO_SUBSCRIPTION_V1` | `https://samsungcardcs.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102643 | 삼성코닝어드밴스드글라스 | `KYOBO_SUBSCRIPTION_V1` | `https://scg.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102644 | 삼천리 | `KYOBO_SUBSCRIPTION_V1` | `https://sam.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102645 | 새마을금고중앙회 | `KYOBO_SUBSCRIPTION_V1` | `https://kfcc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102646 | 생명보험협회 | `KYOBO_SUBSCRIPTION_V1` | `https://klia.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102647 | 서울대학교 병원 | `KYOBO_SUBSCRIPTION_V1` | `https://snuh.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102648 | 서울아산병원 | `KYOBO_SUBSCRIPTION_V1` | `https://aml.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102649 | 세아제강 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://seah.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102650 | 세이브더칠드런전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://savethechildren.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102651 | 슈피겐코리아 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://spigen.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102652 | 스마일페이먼츠 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://smilepayments.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102653 | 스트라이커 | `KYOBO_SUBSCRIPTION_V1` | `https://stryker.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102654 | 신한은행 | `KYOBO_SUBSCRIPTION_V1` | `https://shinhanbank.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102655 | 신한카드 전자책도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://slibrary.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102656 | 아름다운가게 e북도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://beautifulstore.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102657 | 아방데코 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://avantdeco.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102658 | 에스트래픽 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://straffic.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102659 | 에어부산 | `KYOBO_SUBSCRIPTION_V1` | `https://airbusan.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102660 | 에코프로이엠 | `KYOBO_SUBSCRIPTION_V1` | `https://ecoproem.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102661 | 용인공원그룹 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://yongin-park.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102662 | 우리글로벌자산운용 | `KYOBO_SUBSCRIPTION_V1` | `https://wooriglobalam.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102663 | 우리금융경영연구소 | `KYOBO_SUBSCRIPTION_V1` | `https://wfri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102664 | 우리금융에프앤아이 | `KYOBO_SUBSCRIPTION_V1` | `https://woorifni.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102665 | 우리금융지주 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://woorifg.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102666 | 우리신용정보 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wooricredit.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102667 | 우리에프아이에스 | `KYOBO_SUBSCRIPTION_V1` | `https://woorifis.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102668 | 우리은행 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wooribank.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102669 | 우리자산신탁 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wooriat.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102670 | 우리자산운용 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wooriam.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102671 | 우리카드 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wooricard.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102672 | 우리투자증권 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wooriib.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102673 | 우리펀드서비스 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://woorifs.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102674 | 우리프라이빗에퀴티자산운용 | `KYOBO_SUBSCRIPTION_V1` | `https://wooripe.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102675 | 원익큐브 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://wonikcube.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102676 | 원테크스탑전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://otsk.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102677 | 웰컴금융그룹 | `KYOBO_SUBSCRIPTION_V1` | `https://welcome.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102678 | 이투마스 | `KYOBO_SUBSCRIPTION_V1` | `https://etomars.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102679 | 전북은행 | `KYOBO_SUBSCRIPTION_V1` | `https://jbbank.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102680 | 제로투세븐 | `KYOBO_SUBSCRIPTION_V1` | `https://0to7.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102681 | 조광페인트 | `KYOBO_SUBSCRIPTION_V1` | `https://ckpc.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102682 | 청호나이스 | `KYOBO_SUBSCRIPTION_V1` | `https://chungho.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102683 | 카카오 | `KYOBO_SUBSCRIPTION_V1` | `https://kakaocorp.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102684 | 카카오모빌리티 | `KYOBO_SUBSCRIPTION_V1` | `https://kakaomob.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102685 | 케이더블유이코리아 | `KYOBO_SUBSCRIPTION_V1` | `https://kwe.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102686 | 케이티넥스알 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://ktnexr.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102687 | 코닝정밀소재 | `KYOBO_SUBSCRIPTION_V1` | `https://corning.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102688 | 코엑스 | `KYOBO_SUBSCRIPTION_V1` | `https://coex.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102689 | 코오롱오토모티브 | `KYOBO_SUBSCRIPTION_V1` | `https://kam.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102690 | 트레이딩웍스 | `KYOBO_SUBSCRIPTION_V1` | `https://tradingworksgroup.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102691 | 팜한농 VIP 북카페 | `KYOBO_SUBSCRIPTION_V1` | `https://farmhannong-cp-vip.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102692 | 팜한농 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://farmhannong.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102693 | 팬오션 | `KYOBO_SUBSCRIPTION_V1` | `https://panocean.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102694 | 포스에스엠 | `KYOBO_SUBSCRIPTION_V1` | `https://possm.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102695 | 하나금융지주 | `KYOBO_SUBSCRIPTION_V1` | `https://hana.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102696 | 하나은행 | `KYOBO_SUBSCRIPTION_V1` | `https://hanabk.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102697 | 한국베링거인겔하임 | `KYOBO_SUBSCRIPTION_V1` | `https://bi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102698 | 한국엠에스씨소프트웨어 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://mscsoftware.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102699 | 한국투자증권 | `KYOBO_SUBSCRIPTION_V1` | `https://truefriend.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102700 | 한국필립모리스 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://pmk.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102701 | 한림대학교 강남성심병원 | `KYOBO_SUBSCRIPTION_V1` | `https://hks.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102702 | 한진인천컨테이너터미널 | `KYOBO_SUBSCRIPTION_V1` | `https://hjit.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102703 | 한화생명(韓國) | `KYOBO_SUBSCRIPTION_V1` | `https://hanwhalife.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102704 | 한화큐셀(구매형) | `KYOBO_SUBSCRIPTION_V1` | `https://q.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102705 | 한화토탈에너지스 | `KYOBO_SUBSCRIPTION_V1` | `https://htpchem.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102706 | 한화투자증권 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://hanwhawm.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102707 | 한화파워 | `KYOBO_SUBSCRIPTION_V1` | `https://hanwhapower.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102708 | 현대IHL | `KYOBO_SUBSCRIPTION_V1` | `https://hyundaiihl.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102709 | 현대건설 e북도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://hdec.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102710 | 현대아울렛 가든파이브점 | `KYOBO_SUBSCRIPTION_V1` | `https://gdhyundai.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102711 | 현대엔지니어링 | `KYOBO_SUBSCRIPTION_V1` | `https://hec.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102712 | 호반그룹 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://ihoban.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102713 | 호전실업 전자책도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://hojeon.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102714 | 휴온스그룹 | `KYOBO_SUBSCRIPTION_V1` | `https://hg.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102715 | 대전해든학교 | `KYOBO_SUBSCRIPTION_V1` | `https://haedeunsc.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102716 | 부산수정초등학교 | `KYOBO_SUBSCRIPTION_V1` | `https://bsujeong-e.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102717 | 연천백의초등학교 | `KYOBO_SUBSCRIPTION_V1` | `https://baekuyi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102718 | 인천성리초등학교 | `KYOBO_SUBSCRIPTION_V1` | `https://sungri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102719 | 인천소래초등학교 | `KYOBO_SUBSCRIPTION_V1` | `https://soraecho.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102720 | 경기연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://gri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102721 | 과학기술정책연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://stepi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102722 | 국가안보전략연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://inss.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102723 | 극지연구소 | `KYOBO_SUBSCRIPTION_V1` | `https://kopri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102724 | 에너지경제연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://keei.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102725 | 정보통신정책연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kisdi.dkyobobook.co.kr` | probe 대여 (RENTAL), 2026-10-03 |
| 102726 | 충북연구원 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://cri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102727 | 한국노동연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kli.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102728 | 한국보건사회연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kihasa.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102729 | 한국여성정책연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kwdi.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102730 | 한국조세재정연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kipf.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102731 | 한국지방행정연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://krila.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102732 | 한국지질자원연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kigam.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102733 | 한국철도기술연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://krri.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102734 | 한국학중앙연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://aks.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102735 | 한국한의학연구원 전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://kiom.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102736 | 한국항공우주연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kari.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102737 | 한국행정연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kipa.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |
| 102738 | 한국환경연구원 | `KYOBO_SUBSCRIPTION_V1` | `https://kei.dkyobobook.co.kr` | probe 혼합 (MIXED), 2026-10-03 |

### A-2. 수집 DB가 받지 않음 (105곳)

수집 DB `library.crawl_skip`에 이유가 적힌 곳. 이유는 그 칸 글 그대로.

| 수집 번호 | 이름 | 화면 종류 | 주소 | 자세히 |
|---|---|---|---|---|
| 100011 | 경산시립도서관 | `BOOKCUBE_FX` | `http://elib.gbgs.go.kr:8080/FxLibrary` | same site as registered library 265 (http://elib.gbgs.go.kr); same host, other platform (BOOKCUBE_FX vs YES24_V2) / holdings mostly same; overlap check 2026-10-05 |
| 100019 | 광양시립도서관 | `BOOKCUBE_FX` | `http://ebook.gwangyang.go.kr:9080/FxLibrary` | same site as registered library 41 (https://ebook.gwangyang.go.kr:444); same host, other platform (BOOKCUBE_FX vs KYOBO_V1) / holdings mostly same; overlap check 2026-10-05 |
| 100066 | 부천시립도서관 전자도서관 | `BOOKCUBE_FX` | `https://ebook.bcl.go.kr:8084/FxLibrary` | same site as registered library 73 (https://ebook.bcl.go.kr:444); same host, other platform (BOOKCUBE_FX vs KYOBO_V1) / holdings mostly same; overlap check 2026-10-05 |
| 100080 | 안동시립도서관 전자도서관 | `BOOKCUBE_FX` | `http://ebook.andong.go.kr:8080/FxLibrary` | same site as registered library 192 (https://ebook.andong.go.kr); same host, other platform (BOOKCUBE_FX vs KYOBO_V1) / holdings mostly same; overlap check 2026-10-05 |
| 100119 | 청주시립도서관 | `BOOKCUBE_FX` | `http://cjelib.cheongju.go.kr:9090/FxLibrary` | same site as registered library 150 (https://cjelib.cheongju.go.kr); same host, other platform (BOOKCUBE_FX vs KYOBO_V1) / holdings mostly same; overlap check 2026-10-05 |
| 100124 | 통영시립도서관 | `BOOKCUBE_FX` | `https://elib.gyeongnam.go.kr:8001/FxLibrary_ty` | same site as registered library 152 (https://elib.gyeongnam.go.kr:8123); same host, other platform (BOOKCUBE_FX vs KYOBO_V1) / holdings mostly same; overlap check 2026-10-05 |
| 100128 | 한밭도서관 | `BOOKCUBE_FX` | `https://ebook.u-library.kr:448/FxLibrary` | same site as registered library 157 (https://ebook.u-library.kr); same host, other platform (BOOKCUBE_FX vs KYOBO_V1) / holdings mostly same; overlap check 2026-10-05 |
| 100627 | 나주시립도서관 | `KYOBO_V1` | `http://ebooks.ss21.kr:8005` | same site as registered library 52 (https://ebooks.ss21.kr:449); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100641 | 익산시립도서관 | `KYOBO_V1` | `http://www.ss21.kr:8010` | same site as registered library 120 (https://www.ss21.kr:453); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100648 | 제주서부방음도서관 | `KYOBO_V1` | `http://ebooks.ss21.kr:8011` | same site as registered library 138 (https://ebooks.ss21.kr:455); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100668 | 전라북도인재개발원 | `KYOBO_V1` | `http://hrdebook.jeonbuk.go.kr:88` | same site as registered library 279 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 279와 같은 사이트 |
| 100675 | 강진군립도서관 | `KYOBO_V1` | `http://www.gjlib.go.kr:88` | same site as registered library 18 (https://www.gjlib.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100677 | 경상남도교육청 통합전자도서관 | `KYOBO_V1` | `http://ebook.gne.go.kr:9029` | same site as registered library 29 (https://ebook.gne.go.kr:9049); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100679 | 계룡도서관 | `KYOBO_V1` | `http://e-lib.gyeryong.go.kr:8003` | same site as registered library 31 (https://e-lib.gyeryong.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100680 | 고창군 도서관 | `KYOBO_V1` | `http://ebook.gochang.go.kr:88` | same site as registered library 36 (https://ebook.gochang.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100681 | 고흥군립도서관 | `KYOBO_V1` | `http://www.ghlib.go.kr:88` | same site as registered library 2 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 2와 같은 사이트 |
| 100682 | 광명시중앙도서관 | `KYOBO_V1` | `http://210.117.242.66:8091` | same site as registered library 39 (https://210.117.242.66:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100683 | 곡성교육문화회관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8301` | same site as registered library 182 (https://ebook.jne.go.kr:8401); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100684 | 광양시립도서관 | `KYOBO_V1` | `http://ebook.gwangyang.go.kr:88` | same site as registered library 41 (https://ebook.gwangyang.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100685 | 경기과천교육도서관 | `KYOBO_V1` | `http://elib.goe.go.kr:8104` | same site as registered library 20 (https://elib.goe.go.kr:8204); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100687 | 광진구립도서관 | `KYOBO_V1` | `http://ebook.gwangjinlib.seoul.kr:8090` | same site as registered library 42 (https://ebook.gwangjinlib.seoul.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100688 | 광주시립도서관(경기) | `KYOBO_V1` | `http://lib.gjcity.go.kr:8081` | same site as registered library 5 (https://lib.gjcity.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100689 | 구리시립도서관 | `KYOBO_V1` | `http://ebook.gurilib.go.kr:8091` | same site as registered library 187 (https://ebook.gurilib.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100690 | 경북도서관 | `KYOBO_V1` | `http://www.gb.go.kr:8085` | same site as registered library 28 (https://www.gb.go.kr:8088); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100691 | 고성도서관 | `KYOBO_V1` | `http://ebook.gne.go.kr:9021` | same site as registered library 33 (https://ebook.gne.go.kr:9041); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100692 | 군포시중앙도서관 | `KYOBO_V1` | `http://ebook.gunpolib.go.kr:8091` | same site as registered library 46 (https://ebook.gunpolib.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100693 | 금천구립도서관 | `KYOBO_V1` | `http://elib.geumcheonlib.seoul.kr:8091` | same site as registered library 48 (https://elib.geumcheonlib.seoul.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100694 | 금정도서관 | `KYOBO_V1` | `http://ebook.geumjeong.go.kr:88` | same site as registered library 47 (https://ebook.geumjeong.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100695 | 김제시립도서관 | `KYOBO_V1` | `http://ebook-gjl.gimje.go.kr:88` | same site as registered library 49 (https://ebook-gjl.gimje.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100696 | 광양공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8302` | same site as registered library 184 (https://ebook.jne.go.kr:8402); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100697 | 당진시립도서관 | `KYOBO_V1` | `http://lib.dangjin.go.kr:8080` | same site as registered library 189 (https://lib.dangjin.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100699 | 군산시립도서관 | `KYOBO_V1` | `http://ebook.gunsan.go.kr:8001` | same site as registered library 45 (https://ebook.gunsan.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100701 | 마포구청 | `KYOBO_V1` | `http://ebook.mapo.go.kr:8088` | same site as registered library 172 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 172와 같은 사이트 |
| 100702 | 레인보우영동도서관 | `KYOBO_V1` | `http://ebook.rainbowlib.go.kr:8091` | same site as registered library 190 (https://ebook.rainbowlib.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100703 | 김해도서관 | `KYOBO_V1` | `http://ebook.gne.go.kr:9022` | same site as registered library 51 (https://ebook.gne.go.kr:9042); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100704 | 목포시통합도서관 | `KYOBO_V1` | `http://ebook.mokpolib.or.kr:88` | same site as registered library 65 (https://ebook.mokpolib.or.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100706 | 부산 북구 구립도서관 | `KYOBO_V1` | `http://ebook.bsbukgu.go.kr:8091` | same site as registered library 70 (https://ebook.bsbukgu.go.kr:8092); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100707 | 부산광역시립시민도서관 | `KYOBO_V1` | `http://contents.siminlib.go.kr:8094` | same site as registered library 71 (https://contents.siminlib.go.kr:8093); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100708 | 부산남구도서관 | `KYOBO_V1` | `http://ebook.bsnamgu.go.kr:88` | same site as registered library 6 (https://ebook.bsnamgu.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100709 | 광양평생교육관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8303` | same site as registered library 185 (https://ebook.jne.go.kr:8403); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100710 | 부천시립도서관 | `KYOBO_V1` | `http://ebook.bcl.go.kr:8080` | same site as registered library 73 (https://ebook.bcl.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100712 | 부평구립도서관 | `KYOBO_V1` | `http://ebook.bppl.or.kr:8090` | same site as registered library 74 (https://ebook.bppl.or.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100713 | 경기도교육청전자도서관 | `KYOBO_V1` | `http://elib.goe.go.kr:8101` | same site as registered library 21 (https://elib.goe.go.kr:8201); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100714 | 서울시교육청 | `KYOBO_V1` | `http://e-lib.sen.go.kr:8090` | same site as registered library 3 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 3와 같은 사이트 |
| 100715 | 서산어린이도서관 | `KYOBO_V1` | `http://ebclib.seosan.go.kr:8082` | same site as registered library 78 (https://ebclib.seosan.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100716 | 상록도서관 | `KYOBO_V1` | `http://ebooks.seogu.gwangju.kr:88` | same site as registered library 75 (https://ebooks.seogu.gwangju.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100717 | 서산시립도서관 | `KYOBO_V1` | `http://eblib.seosan.go.kr:8070` | same site as registered library 77 (https://eblib.seosan.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100718 | 부산대표도서관 | `KYOBO_V1` | `http://ebook.busan.go.kr:8093` | same site as registered library 4 (https://ebook.busan.go.kr:8094); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100719 | 석남어린이도서관 | `KYOBO_V1` | `http://ebook.issl.go.kr:8080` | same site as registered library 80 (https://ebook.issl.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100720 | 마산도서관(교육청산하) | `KYOBO_V1` | `http://ebook.gne.go.kr:9023` | same site as registered library 64 (https://ebook.gne.go.kr:9043); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100721 | 성동구립도서관 | `KYOBO_V1` | `http://ebook.sdlib.or.kr:8091` | same site as registered library 83 (https://ebook.sdlib.or.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100722 | 세종시교육청 | `KYOBO_V1` | `http://ebook.sje.go.kr:8087` | same site as registered library 176 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 176와 같은 사이트 |
| 100724 | 송파구 통합도서관 | `KYOBO_V1` | `http://ebook.splib.or.kr:8090` | same site as registered library 85 (https://ebook.splib.or.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100725 | 수영도서관 | `KYOBO_V1` | `http://ebook.suyeong.go.kr:88` | same site as registered library 86 (https://ebook.suyeong.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100727 | 안산시도서관 | `KYOBO_V1` | `http://ebook.ansan.go.kr:8099` | same site as registered library 91 (https://ebook.ansan.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100728 | 나주공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8310` | same site as registered library 188 (https://ebook.jne.go.kr:8410); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100729 | 아산시립도서관 | `KYOBO_V1` | `http://elib.asan.go.kr:8090` | same site as registered library 90 (https://elib.asan.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100730 | 안양시립석수도서관 | `KYOBO_V1` | `http://elib.anyang.go.kr:8091` | same site as registered library 193 (https://elib.anyang.go.kr:8092); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100731 | 양주시립도서관 | `KYOBO_V1` | `http://ebook.yangju.go.kr:8089` | same site as registered library 94 (https://ebook.yangju.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100732 | 여의샛강마을도서관 | `KYOBO_V1` | `http://ebook.ydplib.or.kr:9940` | same site as registered library 96 (https://ebook.ydplib.or.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100733 | 부산남지도서관 | `KYOBO_V1` | `http://ebook.gne.go.kr:9028` | same site as registered library 72 (https://ebook.gne.go.kr:9048); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100734 | 여수시립도서관 | `KYOBO_V1` | `http://yslibeb.yeosu.go.kr:88` | same site as registered library 95 (https://yslibeb.yeosu.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100735 | 연수구립도서관 | `KYOBO_V1` | `http://ebook.yspubliclib.go.kr:8092` | same site as registered library 97 (https://ebook.yspubliclib.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100736 | 예산군립도서관 | `KYOBO_V1` | `http://www.ysplib.go.kr:9444` | same site as registered library 104 (https://www.ysplib.go.kr:9445); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100737 | 영광군립도서관 | `KYOBO_V1` | `http://www.yggunlib.go.kr:88` | same site as registered library 100 (https://www.yggunlib.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100738 | 경기도립중앙도서관 평택분관 | `KYOBO_V1` | `http://elib.goe.go.kr:8103` | same site as registered library 22 (https://elib.goe.go.kr:8203); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100739 | 영도도서관 | `KYOBO_V1` | `http://ebook.yeongdo.go.kr:88` | same site as registered library 101 (https://ebook.yeongdo.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100740 | 경기도서관 | `KYOBO_V1` | `http://ebookdrm.library.kr:8091` | same site as registered library 23 (https://ebookdrm.library.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100742 | 목포공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8309` | same site as registered library 191 (https://ebook.jne.go.kr:8409); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100743 | 순천시립도서관 | `KYOBO_V1` | `http://libebook.suncheon.go.kr:88` | same site as registered library 88 (https://libebook.suncheon.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100744 | 음성군립도서관 | `KYOBO_V1` | `http://e-lib.eumseong.go.kr:8091` | same site as registered library 116 (https://e-lib.eumseong.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100745 | 의왕시중앙도서관 | `KYOBO_V1` | `http://cyber.uwlib.or.kr:81` | same site as registered library 117 (https://cyber.uwlib.or.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100746 | 울산도서관 | `KYOBO_V1` | `http://elibrary.ulsan.go.kr:8091` | same site as registered library 111 (https://elibrary.ulsan.go.kr:8093); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100748 | 이천시립도서관 | `KYOBO_V1` | `http://elib.icheonlib.go.kr:8080` | same site as registered library 119 (https://elib.icheonlib.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100749 | 인천송림도서관 | `KYOBO_V1` | `http://ebook.jemulpo.go.kr:8091` | same site as registered library 126 (https://ebook.jemulpo.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100750 | 전남광주통합특별시교육청 광주통합도서관 | `KYOBO_V1` | `http://libebook.gen.go.kr:88` | same site as registered library 133 (https://libebook.gen.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100752 | 전남광주통합특별시광주도서관 | `KYOBO_V1` | `http://gjebook.jeonnam-gwangju.go.kr:88` | same site as registered library 132 (https://gjebook.jeonnam-gwangju.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100754 | 정읍시립도서관 | `KYOBO_V1` | `http://ebook.jeongeup.go.kr:88` | same site as registered library 137 (https://ebook.jeongeup.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100755 | 전주시립도서관 | `KYOBO_V1` | `http://ebook.jeonju.go.kr:88` | same site as registered library 136 (https://ebook.jeonju.go.kr:8445); same host, port 88 vs 8445; user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 136와 같은 사이트 |
| 100756 | 중구구립도서관 | `KYOBO_V1` | `http://ebook.junggulib.or.kr:8080` | same site as registered library 142 (https://ebook.junggulib.or.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100757 | 진천군립도서관 | `KYOBO_V1` | `http://ebook.jincheon.go.kr:8090` | same site as registered library 200 (https://ebook.jincheon.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100758 | 천안시중앙도서관 | `KYOBO_V1` | `http://elib.cheonan.go.kr:8091` | same site as registered library 149 (https://elib.cheonan.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100759 | 영광공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8311` | same site as registered library 195 (https://ebook.jne.go.kr:8411); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100760 | 청주시립도서관 | `KYOBO_V1` | `http://cjelib.cheongju.go.kr:8080` | same site as registered library 150 (https://cjelib.cheongju.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100761 | 경기도립포천분관 | `KYOBO_V1` | `http://elib.goe.go.kr:8109` | same site as registered library 165 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 165와 같은 사이트 |
| 100762 | 충남도서관 | `KYOBO_V1` | `http://elibrary.chungnam.go.kr:8091` | same site as registered library 164 (https://elibrary.chungnam.go.kr:446); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100763 | 중랑구립정보도서관 | `KYOBO_V1` | `http://ebook.jungnanglib.seoul.kr:8080` | same site as registered library 143 (https://ebook.jungnanglib.seoul.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100765 | 제주우당도서관 | `KYOBO_V1` | `http://libebook.jejusi.go.kr:8083` | same site as registered library 139 (https://libebook.jejusi.go.kr:442); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100766 | 진영도서관 VL | `KYOBO_V1` | `http://ebook.gne.go.kr:9025` | same site as registered library 145 (https://ebook.gne.go.kr:9045); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100767 | 충북중앙도서관 | `KYOBO_V1` | `http://e-lib.cbe.go.kr:8092` | same site as registered library 151 (https://e-lib.cbe.go.kr:444); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100768 | 충주시립도서관 | `KYOBO_V1` | `http://e-lib.chungju.go.kr:8090` | same site as registered library 202 (https://e-lib.chungju.go.kr); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100769 | 포항시립도서관 | `KYOBO_V1` | `http://lebook.pohang.go.kr:8085` | same site as registered library 155 (https://lebook.pohang.go.kr:8087); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100776 | 장성공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8308` | same site as registered library 196 (https://ebook.jne.go.kr:8408); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100777 | 경기성남교육도서관 | `KYOBO_V1` | `http://elib.goe.go.kr:8105` | same site as registered library 24 (https://elib.goe.go.kr:8205); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100778 | 창녕도서관 | `KYOBO_V1` | `http://ebook.gne.go.kr:9026` | same site as registered library 146 (https://ebook.gne.go.kr:9046); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100780 | 경기의정부교육도서관 | `KYOBO_V1` | `http://elib.goe.go.kr:8107` | same site as registered library 25 (https://elib.goe.go.kr:8207); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100781 | 창원도서관 | `KYOBO_V1` | `http://ebook.gne.go.kr:9027` | same site as registered library 147 (https://ebook.gne.go.kr:9047); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100783 | 전남광주통합특별시교육청 전남도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8300` | same site as registered library 197 (https://ebook.jne.go.kr:8400); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100785 | 경기중앙교육도서관 | `KYOBO_V1` | `http://elib.goe.go.kr:8102` | same site as registered library 26 (https://elib.goe.go.kr:8202); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100786 | 전라남도학생교육문화회관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8307` | same site as registered library 198 (https://ebook.jne.go.kr:8407); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100788 | 경기평생학습관 | `KYOBO_V1` | `http://elib.goe.go.kr:8108` | same site as registered library 166 (same host, other port); user decision 2026-10-05 in dev build_candidate_seed.py SAME_SITE; marked 2026-10-11 / `same_site.tsv`: 서비스 166와 같은 사이트 |
| 100789 | 진도공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8306` | same site as registered library 199 (https://ebook.jne.go.kr:8406); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100791 | 경기화성교육도서관 | `KYOBO_V1` | `http://elib.goe.go.kr:8106` | same site as registered library 27 (https://elib.goe.go.kr:8206); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100793 | 해남공공도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8304` | same site as registered library 204 (https://ebook.jne.go.kr:8404); same host, same platform / holdings mostly same; overlap check 2026-10-05 |
| 100795 | 화순도서관 | `KYOBO_V1` | `http://ebook.jne.go.kr:8305` | same site as registered library 205 (https://ebook.jne.go.kr:8405); same host, same platform / holdings mostly same; overlap check 2026-10-05 |

### A-3. 전체 수집이 끝나지 않음 (2곳)

수집 DB `library.last_full_success_at`이 비어 있는 곳. 수집이 한 번 끝나면 다음 넣기에서 들어감.

| 수집 번호 | 이름 | 화면 종류 | 주소 | 자세히 |
|---|---|---|---|---|
| 100252 | 무주초등학교 전자책 도서관 | `BOOKCUBE_FX` | `http://muju.bookcube.biz/FxLibrary` | `last_full_success_at` 없음 |
| 102020 | 창원문성대학교 | `YES24_V1` | `http://ebook.cmu.ac.kr:8004` | `last_full_success_at` 없음 |

### A-4. 체험용·운영 중단 (4곳)

`demo_or_closed.tsv`에 있는 곳(사람이 정함).

| 수집 번호 | 이름 | 화면 종류 | 주소 | 자세히 |
|---|---|---|---|---|
| 100048 | 대구동구안심도서관 | `BOOKCUBE_FX` | `http://donggu.bookcube.biz/FxLibrary` | plan easy/2026-10-05-candidate-join-decisions.html 6번 (2026-10-05) |
| 100068 | 삼각산어린이도서관 | `BOOKCUBE_FX` | `http://samgaksan.bookcube.biz:8090/BookLive` | plan easy/2026-10-05-candidate-join-decisions.html 6번 (2026-10-05) |
| 100083 | 양평군립도서관 전자도서관 | `BOOKCUBE_FX` | `http://211.253.88.130:8000/FxLibrary` | plan easy/2026-10-05-candidate-join-decisions.html 6번 (2026-10-05) |
| 100301 | 순창군립도서관 북라이브 | `BOOKCUBE_FX` | `http://sunchang.bookcube.biz:8090/BookLive` | plan easy/2026-10-05-candidate-join-decisions.html 6번 (2026-10-05) |

### A-5. 서비스 도서관과 같은 사이트 (다른 포트) (0곳)

`same_site.tsv`에 있는 곳(사람이 정함).

`same_site.tsv` 8줄 중 8곳은 앞 규칙(대개 `crawl_skip`)에서 이미 빠져 여기에는 나오지 않습니다: 100668, 100681, 100701, 100714, 100722, 100755, 100761, 100788.

없음.

### A-6. 시험용 (종류 `TEST`) (6곳)

`library_kinds.tsv` 종류가 `TEST`인 곳.

| 수집 번호 | 이름 | 화면 종류 | 주소 | 자세히 |
|---|---|---|---|---|
| 100079 | 아이티컴퍼니 홍보용 전자도서관 | `BOOKCUBE_FX` | `http://itcom.bookcube.biz/FxLibrary` | 이름에 '홍보용' |
| 100217 | 대여형 테스트 도서관 | `BOOKCUBE_FX` | `https://rttest.bookcube.biz/FxLibrary` | 이름 또는 주소에 '테스트' |
| 100310 | 시연 전자도서관 | `BOOKCUBE_FX` | `http://ebooktest.dasangng.co.kr/FxLibrary` | 이름에 '시연' |
| 100400 | 초중고 테스트도서관 | `BOOKCUBE_FX` | `http://school.bookcube.biz/FxLibrary` | 이름에 '테스트' |
| 100412 | 테스트도서관 | `BOOKCUBE_FX` | `https://gsenc.bookcube.biz/FxLibrary` | 이름에 '테스트' |
| 102463 | 서울대학교데모버전 | `YES24_V2` | `http://snu.yes24library.com` | 이름에 '데모버전' |

## B. 서비스에 있지만 문제가 있는 곳 (8곳, 알림용)

서비스 도서관 2663곳 중 꺼 둔 곳과, 켜져 있지만 수집 DB가 더 받지 않는 곳입니다. **모두 서비스에 그대로 둡니다**(사용자 결정 2026-10-12). 넣기 명령도 이들을 바꾸거나 지우지 않습니다.

### B-1. 꺼 둔 곳 (`enabled = false`, 5곳)

검색에서 빠져 있고 데이터는 남아 있습니다. 다시 켜는 법은 `library-maintenance.md` 6-2.

| 서비스 번호 | 이름 | 화면 종류 | 주소 | 끈 날 | 이유 | 출처 | 수집 번호: 수집 DB 상태 |
|---|---|---|---|---|---|---|---|
| 59 | 대구학생전자도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://dgelib-r.dkyobobook.co.kr` | 2026-09-29 | 서비스 중지: "대구학생전자도서관 서비스가 중지됨을 알립니다", "홈페이지 접속은 가능하나 로그인 불가"(2025-07-22 공지). 대구교육청 공지에서 재개 소식이 보일 때 다시 확인 | library/004-library-seed-kyobo.yaml, library-maintenance.md 6-2 (사용자 결정 2026-09-29) | 59: 수집 DB에 없음 |
| 131 | 전남광주통합특별시 서구 통합도서관 | `KYOBO_SUBSCRIPTION_V1` | `https://seogulib.dkyobobook.co.kr` | 2026-09-29 | 구독형 전자책 2026년 예산 소진으로 9월 중 중단, 2027년 1월 재개 예정(서구 공지 2026-09-14). 2027-01에 다시 확인 | library/004-library-seed-kyobo.yaml, library-maintenance.md 6-2 (사용자 결정 2026-09-29) | 131: 수집 DB에 없음 |
| 135 | 전라북도교육청통합도서관 | `KYOBO_V1` | `https://ebook.jbe.go.kr:10111` | 2026-10-05 | 서비스 검색이 서버 오류. 사이트가 브라우저에도 'L7 Secure connect fail'(2026-10-01). 같은 교육청 책은 포털 도서관 15에 있음 | library/010-library-disabled.yaml, library-site-issues.md 9절 (사용자 결정 2026-10-05) | 135: `crawl_skip`: site returns 'L7 Secure connect fail' to browsers too; same office's books are in portal library 15 (47,868 KYOBO rows) (2026-10-01) / 마지막 전체 수집 없음 |
| 2194 | 장안대학교 | `YES24_V2` | `http://e-lib.jangan.ac.kr` | 2026-10-05 | 최종 주소를 끝내 못 받음: 연결 뒤 응답 없음(header parser received no bytes) 두 번 | library/010-library-disabled.yaml, library-site-issues.md 9절 (사용자 결정 2026-10-05) | 102032: 마지막 전체 수집 2026-10-04T03:34:40+09:00 |
| 2198 | 성균관대학교 | `YES24_V2` | `http://ebook.skku.edu` | 2026-10-05 | 최종 주소를 끝내 못 받음: 시간 초과(5초) 세 번. 같은 호스트의 북큐브(558)는 응답 | library/010-library-disabled.yaml, library-site-issues.md 9절 (사용자 결정 2026-10-05) | 102036: 마지막 전체 수집 2026-10-03T21:47:23+09:00 |

### B-2. 켜져 있지만 수집하지 않음, 사람이 확인하고 둠 (`gone_known.tsv`, 3곳)

넣기 보고서의 `goneKnown` [44, 261, 269]. 다시 묻지 않습니다(`rules/README.md`).

| 수집 번호 | 서비스 번호 | 이름 | 화면 종류 | 주소 | 확인한 날 | 확인한 상태 | 수집 DB `crawl_skip` |
|---|---|---|---|---|---|---|---|
| 44 | 44 | 국회도서관 | `KYOBO_V1` | `https://ebook.nanet.go.kr:8089` | 2026-10-12 | https://ebook.nanet.go.kr:8089/ answers 200 with a 19-byte empty page to a script (crawl_skip since 2026-10-01) | site answers non-browser clients with an empty page (any headers); not bypassed. 17,162 titles seen in a browser (2026-10-01) |
| 261 | 261 | 군포시도서관 | `YES24_V2` | `http://ebook.gunpolib.go.kr:8080` | 2026-10-12 | http://ebook.gunpolib.go.kr:8080/ redirects (302) to https://ebook.gunpolib.go.kr:444/elibrary-front/ = library 46 (crawl_skip since 2026-10-01) | moved: http://ebook.gunpolib.go.kr:8080 now redirects to the KYOBO site https://ebook.gunpolib.go.kr:444 = library 46 (2026-10-01) |
| 269 | 269 | 김천시립도서관 | `YES24_V3` | `http://ebook.gcl.go.kr` | 2026-10-12 | http://ebook.gcl.go.kr/ resets a scripted connection (curl exit 56; crawl_skip since 2026-09-30) | since 2026-09-30 the site resets scripted connections (browser OK; 7,921 titles on 2026-09-29); not bypassed |

### B-3. 그 밖에 켜져 있지만 수집 DB에 없거나 받지 않는 곳 (0곳)

없음. 켜진 서비스 도서관은 모두 수집 DB에 있고, `crawl_skip`이 없고, 전체 수집이 끝났습니다.
