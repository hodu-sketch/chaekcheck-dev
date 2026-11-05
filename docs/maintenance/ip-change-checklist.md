# IP 교체 체크리스트

Elastic IP를 새로 할당하거나 서버를 옮길 때 손봐야 할 곳.

## 운영 서버 IP (현재 Elastic IP)

| # | 곳 | 할 일 |
|---|---|---|
| 1 | AWS 보안 그룹 `sg-0f424434c56c5d021` (운영 서버) | 인바운드 규칙에 새 IP 반영 (22번 포트 등) |
| 2 | AWS 보안 그룹 `sg-01e9e764c2cf06b25` (임베딩 서버 `chaekcheck-embed`) | 8090 인바운드: 운영 서버 보안 그룹 참조 → 보안 그룹 기반이면 변경 불필요, IP 기반이면 갱신 |
| 3 | Cloudflare Tunnel (`cloudflared`) | 터널 설정이 서버에서 실행되므로 IP 변경 자체는 영향 없음. DNS가 터널을 가리키는지 확인 |
| 4 | Webshare IP 인증 | Webshare 대시보드에서 Authorized IPs에 새 운영 서버 IP 등록, 옛 IP 삭제 |
| 5 | 도서관/공공 API 등록 IP | 국립중앙도서관 Open API 등록 IP 갱신 (있다면) |
| 6 | AWS Parameter Store | `/chaekcheck/prod/` 아래 IP 관련 파라미터가 있으면 갱신 |
| 7 | PC `.env` / `.secrets/` | `CHAEKCHECK_SERVER=ubuntu@<새 IP>` 갱신 |
| 8 | EC2 인스턴스 | Elastic IP 연결 해제 → 새 Elastic IP 할당 → 연결 |

## 임베딩 서버 IP

| # | 곳 | 할 일 |
|---|---|---|
| 1 | 임베딩 서버 EC2 | 사설 IP는 멈췄다 켜도 유지됨. 인스턴스를 새로 만들면 사설 IP가 바뀜 |
| 2 | AWS Parameter Store | `CHAEKCHECK_AISEARCH_EMBED_URL`을 새 사설 IP로 갱신 → `up.sh`가 `secrets.env`에 씀 |
| 3 | 서버 `secrets.env` | `up.sh`를 다시 돌리거나 직접 `CHAEKCHECK_AISEARCH_EMBED_URL=http://<새 사설 IP>:8090` 갱신 |
| 4 | 보안 그룹 `sg-01e9e764c2cf06b25` | 22번 포트 관리 PC IP 규칙 갱신 (공인 IP가 바뀌면) |
| 5 | PC `.env` / `.secrets/` | `CHAEKCHECK_EMBED_IP`, `CHAEKCHECK_EMBED_PUBLIC_IP` 갱신 |

## 확인

- 운영 서버: `release.sh` 실행 가능, SSH 접속, 사이트 정상 응답
- 임베딩 서버: 운영 서버에서 `curl http://<새 사설 IP>:8090/api/v1/health` → `{"ready":true}`
- Webshare: 대출 조회 프록시 정상 작동
