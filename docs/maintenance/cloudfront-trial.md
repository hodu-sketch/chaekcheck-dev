# CloudFront 시험 (서울 거점으로 받기)

2026-11-26 사용자 결정 「C로 시험 배포 준비」. 운영 주소 `chaekcheck.com`은 그대로 Cloudflare로 두고, 시험 이름 `cf-test.chaekcheck.com`만 Amazon CloudFront로 받아 두 길을 나란히 잽니다. 바꿔 끼울지는 숫자를 보고 정합니다.

## 0. 왜

- 한국에서 `chaekcheck.com`에 접속하면 Cloudflare 로스앤젤레스 거점이 받음(`/cdn-cgi/trace`의 `colo=LAX`, `loc=KR`, 2026-11-26). Cloudflare 무료 요금제는 한국 통신사 망 비용 문제로 한국을 미국·일본 거점에서 받는 일이 많다고 알려져 있음(공식 문서가 아니라 커뮤니티 글, 서울을 보장하는 것은 Enterprise 계약뿐이라는 글이 여럿)
- 운영 서버는 시드니(AWS `ap-southeast-2`). 지금 길: 한국 → 로스앤젤레스(Cloudflare) → 시드니(터널) → 되돌아옴
- 이미 표지 그림에 쓰는 CloudFront(`d1bdzw8sfjph5d.cloudfront.net`)는 한국에서 서울 거점(`x-amz-cf-pop: ICN57-P3`)으로 받음(2026-11-26 확인)
- CloudFront 길: 한국 → 서울(CloudFront) → AWS 내부망 → 시드니 서버(VPC 오리진, 서버는 바깥에 포트를 열지 않음)

## 1. 무엇이 바뀌나

| 곳 | 바뀜 |
|---|---|
| `deploy/nginx/ec2-tunnel.conf` | 둘째 server 블록 `listen 8089`: CloudFront가 붙는 곳. 방문자 IP는 `X-Forwarded-For`의 마지막(CloudFront가 붙임), 사설 대역만 믿음(`real_ip_recursive on`). 나머지 location은 같은 `chaekcheck-locations.conf` |
| `deploy/prod/compose.yaml` | `web`에 `8089:8089`. 서버 밖으로도 열리므로 **보안 그룹이 지킴**: 8089는 CloudFront의 VPC 오리진 그룹만. 새 포트는 `web` 컨테이너를 다시 만들 때(`bin/up.sh web`) 열림 |
| AWS(스크립트 `deploy/prod/cloudfront/setup-trial.sh`) | ACM 인증서(us-east-1, `chaekcheck.com` + `*.chaekcheck.com`), VPC 오리진 `chaekcheck-web-8089`(운영 인스턴스 8089, VPC 안 http), 운영 보안 그룹 `sg-0f424434c56c5d021`에 8089 ← `CloudFront-VPCOrigins-Service-SG`, 배포(이름 `cf-test.chaekcheck.com`) |
| Cloudflare DNS | 기록 2개 더함(둘 다 「DNS only」 회색 구름): 인증서 확인용 CNAME, `cf-test` → CloudFront 주소. `chaekcheck.com`과 터널은 그대로 |

배포 설정: 기본은 캐시 안 함(`CachingDisabled`) + 보는 사람의 머리·쿠키·쿼리 모두 넘김(`AllViewer`), `/assets/*`만 캐시(`CachingOptimized`). http는 https로 돌림, 오리진 응답 대기·연결 유지 60초, `PriceClass_200`(한국 포함).

## 2. 순서

1. **배포(집에서)**: 이 변경이 든 판을 `release.sh`로 냅니다. nginx 설정은 `switch.sh`가 바꿔 끼움. 그다음 서버에서 `cd /srv/chaekcheck && bin/up.sh web`(web 컨테이너를 새 포트로 다시 만듦, 몇 초 끊김). 확인: 서버에서 `curl -s localhost:8089/api/v1/version`
2. **AWS CloudShell**(콘솔 위 `>_`): `setup-trial.sh`를 올리고(Actions > Upload file) `bash setup-trial.sh`
   - 처음에는 인증서 확인 기록을 출력하고 멈춤 → Cloudflare 대시보드 > chaekcheck.com > DNS에 그대로 넣음(DNS only) → 몇 분 뒤 다시 `bash setup-trial.sh`
   - VPC 오리진 만들기는 10\~15분 기다림(스크립트가 기다림, 끊기면 다시 돌리면 이어감)
   - 끝에 `cf-test` CNAME 대상(`d….cloudfront.net`)을 출력 → Cloudflare DNS에 넣음(DNS only)
   - 상태만 보기: `bash setup-trial.sh --status`
3. **확인(PC)**:
   - `curl -sI https://cf-test.chaekcheck.com/api/v1/version` → 200, `x-amz-cf-pop: ICN…`
   - 화면: `https://cf-test.chaekcheck.com` 열기, 검색·AI 검색 한 번
   - 방문자 IP: 서버 `docker logs chaekcheck-web-1 --since 5m`에서 cf-test 요청의 주소가 PC 공인 IP인지(사설 172.x면 IP 제한이 모두를 한 사람으로 셈 → 시험 멈춤)
4. **재기**: `scripts/aisearch/eval/first_search_timing.py`로 같은 질문을 두 이름에 번갈아 보냄
   - `python scripts/aisearch/eval/first_search_timing.py "<질문>" 1114 cf-test.chaekcheck.com`
   - `python scripts/aisearch/eval/first_search_timing.py "<질문>" 1114 chaekcheck.com`
   - 바로 이어서 5번씩, 그리고 30분 넘게 쉰 뒤 한 번씩. `edge`(LAX / ICN…), `connect+tls`, `status GET`, 첫 `head-delta`를 비교
   - 집 인터넷에서 잼(카페·휴대폰 망은 망 자체가 0.1\~0.25초라 섞임, 2026-11-26)
5. **결과**: 이 문서 4절에 숫자를 적고, 바꿔 끼울지 사용자가 정함

## 3. 되돌리기

- Cloudflare DNS에서 `cf-test` 기록을 지우면 시험 길이 닫힘(운영 주소는 처음부터 그대로)
- 다 걷을 때: 배포 끄기·지우기(콘솔 CloudFront > Disable 뒤 Delete), 보안 그룹의 8089 규칙 지우기, VPC 오리진 지우기, `compose.yaml`의 `8089:8089`와 nginx 둘째 블록 지우기
- 인증서는 남겨도 비용 없음

## 4. 비용과 알아 둘 것

- CloudFront 상시 무료 한도: 월 전송 1TB, 요청 1,000만 건(제3자 정리 글 기준, AWS 가격 페이지로 확인 필요). 지금 사이트 규모로는 0원으로 봄 [추론]. 한국 전송은 한도를 넘으면 GB당 약 $0.12
- VPC 오리진은 따로 돈을 받지 않는다고 AWS가 발표(2024-11) [확인 필요]
- 시험 이름에서는 Cloudflare의 봇 막기·DDoS 보호가 없음(CloudFront 기본 보호 AWS Shield Standard만). nginx의 IP별 제한은 같게 걸림(3단계에서 IP 확인)
- 카카오 로그인은 돌아오는 주소가 `chaekcheck.com`이라 시험 이름에서는 로그인 뒤 운영 주소로 넘어감(시험 대상 아님)
- 운영 주소를 바꿔 끼우려면: 배포에 `chaekcheck.com` 이름 더하기, Cloudflare의 `chaekcheck.com` 기록을 CloudFront로(DNS only, 루트 이름은 Cloudflare의 CNAME 평탄화), 카카오·`AUTH_PUBLIC_BASE_URL`은 같은 주소라 그대로, 터널은 한동안 남겼다가 내림. 이건 시험 결과를 본 뒤 따로 정함

## 5. 결과 (2026-11-27 23:3x, 집 인터넷)

설정: 2026-11-27 22:5x\~23:1x에 이 문서 2절대로 함. 인증서 `chaekcheck.com` + `*.chaekcheck.com`(us-east-1), VPC 오리진 `vo_0QLVgseWF0XE63WpW7Db4l`, CloudFront 그룹 `sg-06f87142c084f4f3e` → 운영 보안 그룹 8089, 배포 `EOK9HHF65GSW3`(`d1def6gjpe87eo.cloudfront.net`), Cloudflare DNS `cf-test` CNAME(DNS only).

연결만(`/api/v1/version`, 새 연결 3번씩): chaekcheck.com 첫 바이트 659\~863ms(TCP 214\~227ms, 거점 HKG), cf-test 186\~246ms(TCP 14\~58ms, 거점 ICN80).

AI 검색(`first_search_timing.py`, 질문 5개를 두 이름에 번갈아, 7초 간격, 데워진 상태, 지역 1114):

| 질문 | chaekcheck.com 거점 | 연결 | status GET | 첫 글 | cf-test 거점 | 연결 | status GET | 첫 글 |
|---|---|---|---|---|---|---|---|---|
| 우주 배경 따뜻한 소설 | LAX | 411 | 673 | 2,692 | ICN80 | 96 | 383 | 1,736 |
| 조선 시대 역사 | NRT | 136 | 406 | 2,259 | ICN80 | 69 | 159 | 1,751 |
| 비 오는 날 에세이 | NRT | 163 | 375 | 1,978 | ICN80 | 59 | 154 | 1,805 |
| 경제 첫 공부 | NRT | 150 | 404 | 1,982 | ICN80 | 140 | 408 | 1,720 |
| 고양이 그림책 | HKG | 167 | 519 | 3,184 | ICN80 | 84 | 157 | 2,084 |
| **가운데값** | | **163** | **406** | **2,259** | | **84** | **159** | **1,751** |

(ms. 「첫 글」은 POST를 보낸 때부터 첫 `head-delta`까지. 「연결」은 TCP+TLS, 「status GET」은 페이지를 열 때 하는 첫 요청 하나.)

읽은 것:
- 첫 글 가운데값 2.26 → 1.75초(약 0.5초 빠름), 5번 모두 cf-test가 빠르거나 같음
- 페이지를 열 때의 요청 하나(status GET)도 406 → 159ms. 화면을 처음 열 때 요청 여러 개가 같은 차이를 받음
- chaekcheck.com의 Cloudflare 거점은 같은 시간대에도 LAX·NRT·HKG로 바뀜. cf-test는 5번 모두 ICN80
- 서버 안 시간(오래 쉰 뒤 지연)은 이 비교와 따로임: 길을 바꿔도 그 몫은 남음

운영 주소를 바꿔 끼울지는 사용자가 정함(4절 마지막 줄의 순서).

## 6. 운영 주소 바꿔 끼우기 (준비, 2026-11-27)

사용자 2026-11-27 「바꿔 끼우기 준비」. 아직 바꾸지 않음. 아래는 바꿀 때의 차례입니다.

### 6-1. 바꾸기 전에 끝낼 것

| 할 것 | 상태 |
|---|---|
| nginx 8089: 방문자 IP를 `X-Forwarded-For`의 마지막 값으로(CloudFront는 공인 주소 54.x에서 붙음). 전에는 방문자 IP가 CloudFront 주소로 잡혀 IP별 제한이 모두를 한 사람으로 셀 뻔함 | v1.24.2(2026-11-27 배포) |
| CloudFront `/assets/*` 자동 압축(`Compress`). 전에는 JS가 압축 없이 875KB(Cloudflare는 brotli 248KB) | 2026-11-27 켬, 캐시 비움(`/assets/*` 무효화), `setup-trial.sh`에도 반영. 확인: JS brotli 221KB |
| cf-test로 도서관 대출 조회 스트림, AI 검색 스트림 | 2026-11-27 둘 다 정상 |
| cf-test 로그에 방문자 PC IP가 찍히는지 | 2026-11-27 v1.24.2 뒤 확인(PC IP) |

### 6-2. 바꾸는 차례 (몇 분, 끊김 없음)

1. **CloudFront에 운영 이름 더하기** (CloudShell): 배포 `EOK9HHF65GSW3`의 `Aliases`에 `chaekcheck.com`을 더함(`cf-test.chaekcheck.com`은 남김). 인증서가 이미 `chaekcheck.com`을 담고 있음
   ```bash
   D=EOK9HHF65GSW3; aws cloudfront get-distribution-config --id $D > dc.json
   E=$(jq -r .ETag dc.json)
   jq '.DistributionConfig | .Aliases.Items += ["chaekcheck.com"] | .Aliases.Quantity = (.Aliases.Items|length)' dc.json > dc-new.json
   aws cloudfront update-distribution --id $D --if-match $E --distribution-config file://dc-new.json --query Distribution.Status --output text
   ```
2. **되돌릴 값 적어 두기**: Cloudflare DNS의 `chaekcheck.com` 기록(지금 종류 `Tunnel`, 이름 `chaekcheck`)을 열어 터널 ID를 적음. 되돌릴 때 `CNAME chaekcheck.com → <터널 ID>.cfargotunnel.com`, Proxied
3. **DNS 바꾸기** (Cloudflare 대시보드 > chaekcheck.com > DNS): `chaekcheck.com` 기록을 `CNAME chaekcheck.com → d1def6gjpe87eo.cloudfront.net`, **DNS only**로 바꿈(루트 이름의 CNAME은 Cloudflare가 펼쳐 줌). `www.chaekcheck.com`(Cloudflare 넘겨주기)과 TXT는 그대로
4. 바뀌는 동안 두 길이 같은 서버로 가므로 끊김이 없음. 캐시된 DNS가 바뀌는 데 몇 분

### 6-3. 바꾼 뒤 확인

- `curl -sI https://chaekcheck.com/api/v1/version` → 200, `x-amz-cf-pop: ICN…`
- 화면: 첫 화면, 직접 검색(대출 조회), AI 검색, **카카오 로그인**(돌아오는 주소 `AUTH_PUBLIC_BASE_URL`은 같은 `https://chaekcheck.com`이라 바뀌지 않음, cf-test에서는 시험할 수 없었음)
- 서버 `docker logs chaekcheck-web-1`: 방문자 주소가 사용자 공인 IP(CloudFront 54.x가 아님)
- 사용 기록의 지역 추정(`GET /api/v1/regions/guess`)이 맞는 시·도를 냄

### 6-4. 되돌리기

Cloudflare DNS의 `chaekcheck.com` 기록을 6-2의 2번에서 적은 터널 기록으로 되돌림. 터널(`cloudflared`)은 바꾼 뒤 한동안(1\~2주 [안]) 켜 둠. 내리는 것과 문서 정리(`deploy/prod/README.md`, `compose.yaml` 주석, `README.md`·`CLAUDE.md`의 「Cloudflare Tunnel」)는 그 뒤 따로.

### 6-5. 바뀌면 없어지는 것

- Cloudflare의 봇 막기·DDoS 보호·통계는 chaekcheck.com에서 빠짐. CloudFront 기본 보호(AWS Shield Standard)와 nginx의 IP별 제한(`rate-limits.conf`)은 남음
- 비용: CloudFront 상시 무료 한도(월 1TB, 요청 1,000만) 안으로 봄 [추론]
