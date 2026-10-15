# 도서관 규칙 폴더 (`scripts/library/rules/`)

사람이 정한 도서관 규칙과 조사 결과를 규칙마다 파일 하나로 둡니다 (plan ADR-0085, 사이클 13 4단계). 넣기 명령 `library-insert`(설정 `rules-dir`)와 비교용 옛 스크립트 `build_library_seed.py`가 같은 파일을 읽습니다. 책 가져오기 `collection-import`(설정 `chaekcheck.collection.import.rules-dir`, 기본 `../scripts/library/rules`)도 아래 두 파일(`author_publisher_swapped.tsv`, `supplier_families.tsv`)을 수집 사본을 열기 전에 읽고, 파일·칸이 없거나 값이 틀리면 멈춥니다(사이클 19, plan ADR-0114·0115). 모든 규칙의 목록: [`docs/maintenance/rules-index.md`](../../../docs/maintenance/rules-index.md)

- 꼴: UTF-8, 탭 구분, 첫 줄 머리, 줄 끝 `\n`. **따옴표 처리 없음**(칸 안에 `"`가 있어도 글자 그대로, 칸에 탭·줄바꿈은 없음). 빈 칸은 값 없음
- `collection_id`는 수집 DB `library.id`, `library_id`는 서비스 `library.library.id`
- 고칠 때: 줄을 더하거나 고치고 `python -m unittest scripts/library/test_build_library_seed.py`. `public_join.tsv`를 고치면 먼저 `python scripts/library/resolve_regions.py`
- 처음 만든 날: 2026-10-12. 출처의 데이터 폴더 파일(`chaekcheck-data\…`)은 git 밖이라 그대로 두고, 여기에는 필요한 칸만 옮김

| 파일 | 칸 | 뜻 | 출처 |
|---|---|---|---|
| `name_overrides.tsv` | `library_id, old_name, new_name, changed_on, source_url, checked_on` | 서비스 도서관 이름 바꾸기(사이클 08·09, `library-maintenance.md` 4-12·4-13). 옛 이름이 지금 이름(또는 이미 새 이름)과 다르면 멈춤 | `scripts/library/name_overrides.tsv`를 옮김(내용 그대로) |
| `final_origins.tsv` | `listed_base_url, base_url, checked_on, service_id` | 목록 주소 → 리다이렉트를 따라간 최종 주소(`CheckFinalOrigins.java`의 `MOVED_TO`, 4-7 1번). `base_url`·`homepage_url`에 씀. 2026-10-12부터 **새로 넣는 곳은 반드시 줄이 있어야 함**: 바뀌지 않은 곳(`SAME`)도 같은 주소로 적고, 줄이 없으면 넣기 명령이 규칙 오류로 멈춤(사용자 선택 "빠진 곳은 확인해 채움", 2026-10-12). `service_id`는 넣기 전이면 빈 칸 | `scripts/library/final-origins.tsv`를 옮김(내용 그대로) + 2026-10-12 새 6곳(`CheckFinalOrigins.java`, 시간 초과 2곳은 `curl -m 90`으로 다시 봄: 리다이렉트 없음, 약 5.2초) |
| `demo_or_closed.tsv` | `collection_id, name, decided_on, source` | 체험용·운영 중단이라 넣지 않는 곳 | 옛 스크립트 상수 `DEMO_OR_CLOSED` (사용자 결정 2026-10-05, plan `easy/2026-10-05-candidate-join-decisions.html` 6번) |
| `same_site.tsv` | `collection_id, name, library_id, decided_on, note` | 서비스 도서관과 포트만 다른 같은 교보 사이트라 넣지 않는 곳. `library_id`는 같은 사이트인 서비스 도서관 | 상수 `SAME_SITE` (사용자 결정 2026-10-05). 수집 DB에도 같은 날 `crawl_skip`으로 표시됨 |
| `same_host_kept.tsv` | `collection_id, name, decided_on, note` | 호스트가 같고 책 번호가 겹치지만 사람이 읽고 "다른 도서관"으로 남긴 곳. 같은 호스트 검사에서 뺌 | 상수 `SAME_HOST_KEPT` 22곳(2026-10-05) + 여주대 100584·102084 (사용자 선택 "둘 다 넣음", plan spec R2-7-1, 2026-10-12) |
| `gone_known.tsv` | `collection_id, name, decided_on, checked_on, state` | 서비스에 켜져 있지만 수집 DB에서 받지 않음이 된 곳 중 사람이 확인하고 서비스에 그대로 두기로 한 곳. 넣기 보고서의 "사라진 곳"에서 빼고 `goneKnown`에 번호만 남김(새로 생긴 곳만 묻기) | 새 파일(사용자 선택 "확인하고 빼줘 파일로 적어줘", 2026-10-12). 44·261·269를 2026-10-12에 한 번씩 열어 10-01 기록과 같은 상태임을 확인. 2026-10-14 사이클 18에서 셋을 끄고 지움(머리 줄만 남음) |
| `order_no_allowed.tsv` | `collection_id, order_no, decided_on, note` | 순번 2 이상을 사람이 그대로 두기로 한 곳. 여기 없는 순번 2 이상은 멈춤 | 새 파일, 지금은 비어 있음 |
| `scope_overrides.tsv` | `collection_id, residence, work_school, quote` | 거주 지역과 재직·재학 지역이 다른 공공도서관의 가입 범위. 지역 이름을 `,`로 이음. `resolve_regions.py`만 읽음 | 상수 `SCOPE_OVERRIDES` (원문 인용은 `quote`). 면·혁신도시처럼 시·군·구보다 작은 곳은 규칙이 될 수 없어 뺌 |
| `public_join.tsv` | `collection_id, name, is_public, eligibility, basis, scope, visit_required, join_method, extra_condition, join_caution, quote, source_url, checked, memo, decision, rules, eligibility_summary` | 공공도서관 가입 조사(120곳). `rules`·`eligibility_summary`는 `resolve_regions.py`가 채움: `rules`는 `기준:지역번호`를 `;`로 이음(`RESIDENCE:41;WORK:41;SCHOOL:41`, 누구나는 `RESIDENCE:NATIONWIDE`, UNVERIFIED·AFFILIATION은 빈 칸), `eligibility_summary`는 REGION 줄의 화면 문구(`경기 거주 / 수원시 재직·재학`) | 데이터 폴더 `library-join\public-join-2026-10-05.tsv` (`id` → `collection_id`, 두 칸 더함) |
| `library_kinds.tsv` | `collection_id, name, kind, sure, note` | 도서관 종류 `PUBLIC, SCHOOL, UNIV, COMPANY, GOV, ORG, UNKNOWN, TEST`. TEST는 넣지 않음, 소속 전용 종류는 `MEMBERS_ONLY`, UNKNOWN은 `NOT_SURVEYED` | 데이터 폴더 `library-join\candidate-kinds-2026-10-05.tsv` (`id` → `collection_id`) |
| `kyobo_probe.tsv` | `site_url, name, category, model, lending_model, checked_at` | 교보 구독형 사이트의 운영 방식. `lending_model` `OWNED`·`MIXED`·`RENTAL`(MIXED·RENTAL은 넣지 않음, ADR-0067). `site_url`은 끝 `/`를 뗀 주소로, 수집 `base_url`의 끝 `/`를 떼고 맞춤 | 데이터 폴더 `other-platforms\kyobo-institutions-probe-2026-10-03.tsv`에서 `platform` = `KYOBO_SUBSCRIPTION_V1`이고 `model`이 소장·혼합·대여인 1,430줄. `site_url` = `base_url`(없으면 `url`), `model`(소장·혼합·대여) → `lending_model` |
| `bookcube_volume.tsv` | `collection_id, name, base_url, total_item, total_volume, error, checked_on` | 북큐브 사이트의 종 수·권수. 권수 0이고 종 수가 있으면 구독, 값이 없으면(오류) 소장 | `scripts/library/bookcube-volume-2026-10-11.tsv`(그대로 남김, 적용된 `library/014` 주석이 가리킴) + 데이터 폴더 `library-naming\bookcube-volume-recheck-2026-10-12.tsv` 5줄을 덮어씀(`checked_on` 2026-10-12). 바뀌는 대출 방식 없음(plan 사이클 13 research 6절) |
| `join_surveyed_on.txt` | 한 줄 `YYYY-MM-DD` | 가입 조건 조사 기준일. 넣기 실행 기록에 남고 `/about` 공지에 쓰임(ADR-0087) | `2026-10-05` (plan 사이클 13 handoff [확정]) |
| `author_publisher_swapped.tsv` | `collection_id, name, decided_on, swap_before, note` | 저자·출판사 칸을 뒤바꿔 수집한 통합 포털(수집 11 대구, 12 경기도교육청, 15 전북교육청). 가져오기가 두 칸을 바꿔 읽음(서비스 `holding.author_raw`·`publisher_raw`도 바꾼 값). `swap_before`(고친 수집기를 main에 합친 날, 한국 날짜) 이후에 수집한 사본이면 가져오기가 멈춤 → `scripts/library/check_author_publisher_swap.py`로 그 사본을 확인해 "바름"이면 줄을 지우고 다시 가져옴([3-import.md](../../../docs/maintenance/3-import.md)) | 사이클 19(plan ADR-0115), 사이트 확인 2026-10-14·16, 수집기 상수 `PUBLISHER_FIRST_PORTAL_HOSTS` |
| `supplier_families.tsv` | `supplier, family, decided_on, note` | 같은 공급사의 다른 이름 → 같은 묶음(`family`). 가져오기 ② 단계가 (묶음, 상품 코드)로 같은 책을 이음. 이름은 NFKC·소문자·`(주)` 같은 법인 표시·공백을 뗀 뒤 견줌. 목록에 없는 이름은 그 이름 하나가 한 묶음, 빈 공급사는 코드로 잇지 않음. 한 이름은 한 묶음에만 | 사이클 19(plan ADR-0114). 2026-10-12 사본의 공급사 이름 133가지(플랫폼별)를 보고 만듦(`plan-feedback.md` # 사이클 19) |

## 지역 번호 (`resolve_regions.py`)

`collection` 모듈은 `region` 모듈을 모르므로(ADR-0085) 지역 이름은 여기서 미리 번호로 풉니다.

- `python scripts/library/resolve_regions.py` : `public_join.tsv`의 `rules`·`eligibility_summary`를 다시 채움
- `python scripts/library/resolve_regions.py <조사파일.tsv>` : 새 가입 조사 파일(첫 칸 `id`)을 이 꼴로 옮겨 씀
- `python scripts/library/resolve_regions.py --check` : 쓰지 않고, 파일이 최신이 아니면 종료 코드 1 (테스트가 부름)
- 이름 → 번호는 `eligibility_rules.region`(`backend/.../region/002-region-seed.csv`), 문구는 `apply_join_classification.summary_of`로, 옛 seed 스크립트와 같은 방법. 못 푸는 이름(없거나 같은 이름이 둘 이상, 예: 중구)이 있으면 멈춤 → `scope_overrides.tsv`나 조사 파일의 `scope`를 고침
