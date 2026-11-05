# 운영 배포 (EC2) — 무중단 (cycle 27, ADR-0167/0168/0169)

| 파일 | 하는 일 |
|---|---|
| `compose.yaml` | db + backend-blue/green(두 자리) + web(nginx, 폴더 mount) + cloudflared. 포트는 서버 안(127.0.0.1)에만, 바깥은 Cloudflare Tunnel로만 |
| `prod.env` | 비밀이 아닌 설정(공개 주소, 로그인·이용 기록 켜기, AI 검색 스위치: 켜기·질문 벡터·1차 ML·이어 묻기·되묻기·생각 끄기·캐시, 「나에게 맞는 책」 만들기 `CHAEKCHECK_FORYOU_BUILD_ENABLED`). AI 검색 값은 `docs/maintenance/6-aisearch.md` 0절 |
| `bin/up.sh` | 서버에서 띄우기. Parameter Store `/chaekcheck/prod/`를 EC2 역할로 읽어 `secrets.env`(600)를 쓰고, `slots.env`의 일하는 자리(ACTIVE_SLOT)만 compose up. 손으로 둔 `secrets.env`가 있으면 그것을 씀 |
| `bin/switch.sh` | 무중단 전환. `switch.sh backend X.Y.Z`: 쉬는 자리에 새 판을 올리고, 준비되면 nginx를 돌리고, 옛 자리를 내림. `switch.sh web X.Y.Z`: 화면 파일을 풀고 symlink를 바꾸고 nginx reload |
| `bin/release.sh` | PC에서 한 버전을 빌드·전송·전환(아래) |
| `web.Dockerfile` | 화면 파일 운반용 이미지(nginx 설정은 서버 폴더, ADR-0168) |

서버: `$CHAEKCHECK_SERVER`(arm64), 폴더 `/srv/chaekcheck`, 키 `.secrets/chaekcheck-key.pem`(git에 없음). `.env`나 `.secrets/`에 `CHAEKCHECK_SERVER=ubuntu@<서버 IP>`를 넣으세요.

### 서버 폴더 구조

```
/srv/chaekcheck/
├── compose.yaml
├── prod.env
├── secrets.env          (up.sh가 만듦, 600)
├── slots.env            (ACTIVE_SLOT, BACKEND_BLUE_VERSION, BACKEND_GREEN_VERSION, WEB_VERSION)
├── bin/
│   ├── up.sh
│   └── switch.sh
├── nginx/
│   ├── conf.d/
│   │   └── default.conf     (= ec2-tunnel.conf)
│   ├── chaekcheck/
│   │   ├── active-backend.conf   (switch.sh가 씀: upstream → backend-blue 또는 backend-green)
│   │   ├── rate-limits.conf
│   │   ├── security-headers.conf
│   │   └── chaekcheck-locations.conf
│   └── incoming/             (release.sh가 보낸 새 nginx 설정; switch.sh가 자리에 놓음)
├── web/
│   ├── current -> releases/X.Y.Z   (symlink)
│   └── releases/
│       ├── X.Y.Z/           (지금 판)
│       └── X.Y-1.Z/         (앞 판, 되돌리기용)
├── aisearch-model/       (AI 검색 모델, 읽기만)
└── restore/              (DB 복원 파일, 읽기만)
```

## 주소와 www (Cloudflare 대시보드, git 밖)

대표 주소는 `chaekcheck.com`(www 없음). `www.chaekcheck.com`은 Cloudflare에서 바로 넘기고 서버까지 오지 않는다(사용자 요청 2026-10-20).

- DNS: `www` A `192.0.2.1`(자리표시 주소), Proxied
- Rules → Redirect Rules 「www to chaekcheck.com」: `*://www.chaekcheck.com/*` → `https://chaekcheck.com/${2}`, 301, 쿼리 문자열 유지
- 확인: `curl -sI "https://www.chaekcheck.com/search?q=a"`가 `301`과 `location: https://chaekcheck.com/search?q=a`

## 버전 (사용자 결정 2026-10-18)

- 유의적 버전 `vX.Y.Z`, 첫 공개가 `v1.0.0`. 바뀐 점은 저장소 맨 위 `CHANGELOG.md`
- 배포한 커밋에 git 태그 `vX.Y.Z`(annotated), GitHub 릴리스도 같은 이름
- 이미지 태그 두 개: `X.Y.Z`(사람이 읽는 버전), `sha-<커밋 7자>`(정확히 어느 코드인지). compose는 `slots.env`의 판을 씀
- 백엔드 jar의 `META-INF/build-info.properties`에 버전과 커밋 → `GET /api/v1/version` `{"version":"1.0.0","commit":"…"}`
- 버전 숫자는 `backend/build.gradle`의 `version`과 `web/package.json`의 `version`이 같음(`release.sh`가 맞춤)

## 배포하기 (무중단)

1. `CHANGELOG.md` 맨 위에 `## vX.Y.Z (날짜)` 절을 쓰고 커밋한다(변경 없이 깨끗한 `main`이어야 함)
2. Git Bash에서 저장소 안: `deploy/prod/bin/release.sh X.Y.Z` (서비스만 고를 때: `… X.Y.Z backend web`)
3. `switch.sh`의 결과 줄을 확인한다: `switch: OK backend X.Y.Z slot=green ready=23s (was …)`
4. 확인: `curl -s https://chaekcheck.com/api/v1/version`

새벽 03:25~05:10(Asia/Seoul)에는 거절된다(예약 작업, ADR-0171).

`release.sh`는 버전을 빌드 파일에 쓰고 「Release vX.Y.Z」 커밋과 태그를 만든 뒤 빌드 → 서버 전송·불러오기 → `switch.sh backend` → `switch.sh web` → 이미지 정리 → `git push`(main과 태그) → `gh release create` 순서로 한다.

## 되돌리기

```bash
deploy/prod/bin/release.sh --switch-only <앞 판> [backend] [web]
```

서버에는 앞 판 이미지와 화면 폴더까지 있다(두 판만 유지). DB 변경(Liquibase)이 든 판에서 되돌릴 때는 ADR-0170 규칙상 앞 판이 새 DB에서 돌 수 있다. nginx 설정은 되돌리지 않는다(바뀐 설정이 있으면 따로).

## 설정만 바꿀 때 (prod.env)

1. 서버에서 `prod.env`를 고침
2. `bin/switch.sh backend <지금 판>` — 같은 판을 다른 자리로 올려 새 설정을 적용(무중단)

## 점검 배포 (짧은 중단, 미리 알림)

대상: db 이미지 판 올리기, cloudflared 판 올리기, nginx 기본 이미지 판 바꾸기, compose의 `web`·`db`·`cloudflared` 정의 바꾸기, ADR-0170을 지킬 수 없는 DB 변경.

순서: `bin/up.sh --no-deps <서비스>`. DB 변경이면 옛 자리를 먼저 `stop` 뒤 새 판.

## 실패하면

- 1단계(검사)에서 멈추면 아무것도 바뀌지 않음
- 태그를 만든 뒤 빌드·전송에서 멈추면: 원인을 고친 뒤 같은 버전을 다시 내려면 `git tag -d vX.Y.Z`와 「Release」 커밋 되돌리기(`git reset --hard HEAD~1`, push 전이므로 안전) 뒤 다시 실행
- `switch.sh`가 실패하면: 옛 자리가 계속 서빙. 새 자리 컨테이너는 멈춰 있고 로그를 볼 수 있음(`docker logs chaekcheck-backend-<자리>-1`). 이미 돈 Liquibase 변경은 남음(ADR-0170). `release.sh`의 5/5(push, GitHub 릴리스)는 하지 않음. 태그·「Release」 커밋은 PC에만 남음

## 주의

- **맨몸 `docker compose up -d` 금지**(C5): 두 자리가 다 떠서 섞임. `bin/up.sh`를 쓸 것
- `bin/up.sh`를 서비스 이름 없이 돌리면 cloudflared도 떠서 **공개**된다. 비공개로 둘 때는 `bin/up.sh --no-deps db backend-blue web`, 터널만 내릴 때는 서버에서 `docker compose --env-file secrets.env --env-file prod.env --env-file slots.env stop cloudflared`
- 서버 디스크(29GB)에 이미지가 쌓이니 `release.sh`가 두 판만 남기고 지움

## 서버 이미지 정리

`release.sh` 4단계 끝에서 서버의 `chaekcheck/backend`·`chaekcheck/web` 이미지 중 **이번 판, 바로 앞 판, `slots.env`의 판**만 남기고 지운다(사용자 2026-10-25). 컨테이너(멈춘 것 포함)가 쓰는 이미지는 남기고, 태그를 하나씩 지운 뒤 매달린 층을 `docker image prune -f`로 지운다. 끝에 `df -h /`를 보여 준다.

## 처음 한 번 옮기기

기존 구조(backend 하나)에서 두 자리(blue/green)로 옮기는 과정. 한 번만 하면 된다. 자세한 순서는 사이클 27 핸드오프 8단계 참고.

1. `release.sh V` — `switch.sh`가 `reason=no-slots-env`로 멈춤(이미지 전송까지만)
2. 서버에서 폴더를 만듦: `nginx/{conf.d,chaekcheck,incoming}`, `web/releases`
3. `slots.env`를 씀: `ACTIVE_SLOT=blue`, `BACKEND_BLUE_VERSION=V`, `BACKEND_GREEN_VERSION=V`, `WEB_VERSION=V`
4. nginx 설정을 자리에 놓고 `active-backend.conf` → `backend-blue`
5. 화면 파일: `releases/V`와 `current` symlink
6. 옛 백엔드 옆에 blue 띄우기: `bin/up.sh --no-deps backend-blue`
7. web을 새 구조로 다시 만들기: `bin/up.sh --no-deps web` (짧은 끊김)
8. 옛 백엔드 정리: `docker stop -t 60 chaekcheck-backend-1 && docker rm chaekcheck-backend-1`
9. PC에서 5/5(push, GitHub 릴리스)를 손으로
