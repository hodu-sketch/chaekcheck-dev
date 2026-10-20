# 운영 배포 (EC2)

| 파일 | 하는 일 |
|---|---|
| `compose.yaml` | db + backend + web(nginx, `deploy/nginx/ec2-tunnel.conf`) + cloudflared. 포트는 서버 안(127.0.0.1)에만, 바깥은 Cloudflare Tunnel로만 |
| `prod.env` | 비밀이 아닌 설정(공개 주소, 로그인·이용 기록 켜기) |
| `bin/up.sh` | 서버에서 띄우기. Parameter Store `/chaekcheck/prod/`를 EC2 역할로 읽어 `secrets.env`(600)를 쓰고 compose up. 손으로 둔 `secrets.env`가 있으면 그것을 씀 |
| `bin/release.sh` | PC에서 한 버전을 배포(아래) |
| `web.Dockerfile` | 화면 이미지 |

서버: `ubuntu@52.64.117.112`(arm64), 폴더 `/srv/chaekcheck`, 키 `.secrets/chaekcheck-key.pem`(git에 없음).

## 주소와 www (Cloudflare 대시보드, git 밖)

대표 주소는 `chaekcheck.com`(www 없음). `www.chaekcheck.com`은 Cloudflare에서 바로 넘기고 서버까지 오지 않는다(사용자 요청 2026-10-20).

- DNS: `www` A `192.0.2.1`(자리표시 주소), Proxied
- Rules → Redirect Rules 「www to chaekcheck.com」: `*://www.chaekcheck.com/*` → `https://chaekcheck.com/${2}`, 301, 쿼리 문자열 유지
- 확인: `curl -sI "https://www.chaekcheck.com/search?q=a"`가 `301`과 `location: https://chaekcheck.com/search?q=a`

## 버전 (사용자 결정 2026-10-18)

- 유의적 버전 `vX.Y.Z`, 첫 공개가 `v1.0.0`. 바뀐 점은 저장소 맨 위 `CHANGELOG.md`
- 배포한 커밋에 git 태그 `vX.Y.Z`(annotated), GitHub 릴리스도 같은 이름
- 이미지 태그 두 개: `X.Y.Z`(사람이 읽는 버전), `sha-<커밋 7자>`(정확히 어느 코드인지). compose는 `X.Y.Z`를 씀
- 백엔드 jar의 `META-INF/build-info.properties`에 버전과 커밋 → `GET /api/v1/version` `{"version":"1.0.0","commit":"…"}`
- 버전 숫자는 `backend/build.gradle`의 `version`과 `web/package.json`의 `version`이 같음(`release.sh`가 맞춤)

## 배포하기

1. `CHANGELOG.md` 맨 위에 `## vX.Y.Z (날짜)` 절을 쓰고 커밋한다(변경 없이 깨끗한 `main`이어야 함)
2. Git Bash에서 저장소 안: `deploy/prod/bin/release.sh X.Y.Z` (서비스만 고를 때: `… X.Y.Z backend web`)
3. 확인: `curl -s https://chaekcheck.com/api/v1/version`

`release.sh`는 버전을 빌드 파일과 compose에 쓰고 「Release vX.Y.Z」 커밋과 태그를 만든 뒤 빌드 → 서버 전송·불러오기 → `bin/up.sh` → `git push`(main과 태그) → `gh release create` 순서로 한다.

## 되돌리기

서버에는 전에 불러온 이미지가 남아 있다. `compose.yaml`의 두 `image:` 줄을 이전 버전으로 바꿔 서버에 올리고 `bin/up.sh backend web`. DB 변경(Liquibase)이 든 버전은 되돌리기 전에 그 변경을 확인한다.

## 실패하면

- 1단계(검사)에서 멈추면 아무것도 바뀌지 않음
- 태그를 만든 뒤 빌드·전송에서 멈추면: 원인을 고친 뒤 같은 버전을 다시 내려면 `git tag -d vX.Y.Z`와 「Release」 커밋 되돌리기(`git reset --hard HEAD~1`, push 전이므로 안전) 뒤 다시 실행
- 서버에서 `up.sh`가 멈추면 떠 있던 컨테이너는 그대로(`up.sh` 설명)

## 주의

- `bin/up.sh`를 서비스 이름 없이 돌리면 cloudflared도 떠서 **공개**된다. 비공개로 둘 때는 `bin/up.sh db backend web`, 터널만 내릴 때는 서버에서 `docker compose --env-file secrets.env --env-file prod.env stop cloudflared`
- 서버 디스크(29GB)에 이미지가 쌓이니 가끔 `docker image prune`으로 쓰지 않는 이미지를 지운다
