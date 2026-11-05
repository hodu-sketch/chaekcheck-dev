# IP 교체 체크리스트

Elastic IP를 새로 할당하거나 서버를 옮길 때 손봐야 할 곳.

## 운영 서버 IP

- 운영 서버 주소의 원본은 PC `.env`의 `CHAEKCHECK_SERVER`입니다. 문서에는 IP를 적지 않습니다.
- 마지막 교체: 2026-11-05. 그날 새 주소로 SSH 접속하고 백엔드를 바꿔 띄웠습니다(`bin/switch.sh backend 1.23.1`, [7-foryou.md](7-foryou.md) 0절). 아래 항목 가운데 그날 무엇을 했는지는 따로 기록이 없습니다.

| # | 곳 | 할 일 |
|---|---|---|
| 1 | EC2 인스턴스 | 새 Elastic IP 할당 → 운영 인스턴스에 연결 → 옛 Elastic IP 해제(남겨 두면 요금). 아래 2\~7은 새 IP가 있어야 하므로 이것을 먼저 합니다 |
| 2 | PC `.env` / `.secrets/` | `CHAEKCHECK_SERVER=ubuntu@<새 IP>` 갱신 |
| 3 | AWS 보안 그룹 `sg-0f424434c56c5d021` (운영 서버) | 서버 IP 교체만으로는 바꿀 것 없음(인바운드 규칙은 들어오는 쪽 IP를 담음). 관리 PC 공인 IP가 바뀌었을 때만 22번 규칙 갱신 |
| 4 | AWS 보안 그룹 `sg-01e9e764c2cf06b25` (임베딩 서버 `chaekcheck-embed`) | 8090 인바운드: 운영 서버 보안 그룹 참조 → 보안 그룹 기반이면 변경 불필요, IP 기반이면 갱신 |
| 5 | Cloudflare Tunnel (`cloudflared`) | 터널 설정이 서버에서 실행되므로 IP 변경 자체는 영향 없음. DNS가 터널을 가리키는지 확인 |
| 6 | Webshare | 운영은 아이디·비밀번호 인증(Parameter Store `WEBSHARE_PROXY_USERNAME`/`WEBSHARE_PROXY_PASSWORD`, `deploy/prod/bin/up.sh`)이라 IP 등록은 필요 없음. 대시보드 Authorized IPs에 옛 서버 IP가 있으면 지움(예비 방식) |
| 7 | 도서관/공공 API 등록 IP | 운영은 사서추천 받기가 꺼져 있어(`deploy/prod/compose.yaml` `CHAEKCHECK_PICKS_FETCH_ENABLED: "false"`) 국립중앙도서관 Open API는 할 것 없음. 켤 때 등록 IP가 필요한지 확인 |
| 8 | AWS Parameter Store | `/chaekcheck/prod/` 아래 IP 관련 파라미터가 있으면 갱신 |

## 임베딩 서버 IP

| # | 곳 | 할 일 |
|---|---|---|
| 1 | 임베딩 서버 EC2 | 사설 IP는 멈췄다 켜도 유지됨. 인스턴스를 새로 만들면 사설 IP가 바뀜 |
| 2 | AWS Parameter Store | `CHAEKCHECK_AISEARCH_EMBED_URL`을 새 사설 IP로 갱신 → `up.sh`가 `secrets.env`에 씀 |
| 3 | 서버 `secrets.env` | `up.sh`를 다시 돌리거나 직접 `CHAEKCHECK_AISEARCH_EMBED_URL=http://<새 사설 IP>:8090` 갱신 |
| 4 | 보안 그룹 `sg-01e9e764c2cf06b25` | 22번 포트 관리 PC IP 규칙 갱신 (공인 IP가 바뀌면) |
| 5 | PC `.env` / `.secrets/` | `CHAEKCHECK_EMBED_IP`, `CHAEKCHECK_EMBED_PUBLIC_IP` 갱신 |

## 확인

- 운영 서버: `ssh -i .secrets/chaekcheck-key.pem $CHAEKCHECK_SERVER 'cat /srv/chaekcheck/slots.env'`로 접속 확인, `curl https://chaekcheck.com/api/v1/version`이 지금 판을 돌려줌. `release.sh`는 새 판을 커밋·태그·배포·push하므로 확인용으로 돌리지 않습니다
- 임베딩 서버: 운영 서버에서 `curl http://<새 사설 IP>:8090/api/v1/health` → `{"ready":true}`
- Webshare: 대출 조회 프록시 정상 작동
