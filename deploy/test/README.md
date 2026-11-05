# 사이클 17 시험 배포 (EC2, 공개 전 확인용)

plan 17 요청(2026-10-18, 방식 A): 이미지는 PC에서 만들고 EC2는 실행만 합니다. 운영 포장(ADR-0103 전체: edge·app 네트워크, Parameter Store, 지표, 백업, 터널)이 아니라 **뜨는지 보는 최소**입니다.

| 파일 | 하는 일 |
|---|---|
| `../../backend/Dockerfile` | 백엔드 이미지. PC에서 `./gradlew bootJar`로 만든 jar를 복사만 함(RUN 없음 → arm64를 흉내 없이 만듦). Java 25 JRE(`eclipse-temurin:25-jre`, Gradle toolchain과 같음) |
| `web.Dockerfile` | nginx + `web/dist` + `deploy/nginx`의 `local-test.conf`(HTTP 8088, `/api` → `backend-host:8080`). 빌드 위치는 저장소 맨 위 |
| `compose.yaml` | `db` + `backend` + `web`. 포트는 모두 `127.0.0.1`에만 엶 |

## 서버 (2026-10-18)

- EC2 `$CHAEKCHECK_SERVER`(arm64, Graviton), 메모리 2GB + 스왑 2GB, Docker 29.9.1 · compose v5.6.0, 폴더 `/srv/chaekcheck`
- `secrets.env`: `DB_PASSWORD` 한 줄, 서버에서 `openssl rand -hex 24`로 만듦, `chmod 600`. Parameter Store는 쓰지 않음
- 끈 것: 로그인(`CHAEKCHECK_AUTH_ENABLED=false`), 사서추천 받기, 사용 기록. `FETCH_MODE=direct`. 힙 `-Xmx640m`
- 대출 조회는 **프록시만**(`proxy-only`, 운영 값). Webshare는 IP 인증(EC2 서버 IP)이라 아이디·비밀번호 없이 주소 목록만 씀. 목록(주소:포트 20개)은 `egress.env`(`chmod 600`)에 PC `.env`의 같은 줄을 옮김. 처음 띄울 때 이 설정을 빠뜨려 서버 자기 IP로 조회했던 것을 같은 날 고침. 진짜 배포는 `up.sh`가 Parameter Store에서 읽음(ADR-0103)

## 순서

1. PC: `backend/`에서 `./gradlew bootJar`, `web/`에서 `npm run build`
2. PC(WSL): arm64 이미지 두 개를 만들어 묶음

        docker buildx build --platform linux/arm64 -t chaekcheck/backend:test --load backend
        docker buildx build --platform linux/arm64 -t chaekcheck/web:test -f deploy/test/web.Dockerfile --load .
        docker save chaekcheck/backend:test chaekcheck/web:test --platform linux/arm64 | gzip -1 > images-test.tar.gz

3. DB 내용: PC `db`를 `pg_dump -Fc`로 뜸. **회원·기록 표는 데이터를 빼고 구조만**(`--exclude-table-data` `auth.*` `mylibrary.*` `usagelog.*` `collection.*` `modulith.*`). 책 목록·도서관·지역·사서추천·안내 문서와 Liquibase 기록(`databasechangelog`)은 들어감. 590MB
4. `scp`로 `compose.yaml`, `images-test.tar.gz`, 덤프(`restore/`), `docker/postgres-bigm/Dockerfile`(`postgres-bigm/`)을 옮김
5. 서버: `gunzip -c images-test.tar.gz | docker load`. DB 이미지는 PC에 arm64 흉내가 없어 **서버에서 직접 빌드**(`docker build -t chaekcheck/postgres-bigm:18 postgres-bigm`)
6. 서버: DB만 띄우고 복원. 백엔드를 먼저 띄우면 Liquibase가 빈 표를 만들어 복원과 부딪힘

        docker compose --env-file secrets.env up -d db
        docker compose --env-file secrets.env exec -T -e PGOPTIONS="-c maintenance_work_mem=256MB" db pg_restore -U chaekcheck -d chaekcheck -j 2 --exit-on-error /restore/chaekcheck-test.dump

7. 서버: `docker compose --env-file secrets.env --env-file egress.env up -d` → 백엔드 시작 때 Liquibase는 덤프의 기록과 같아 할 일이 없음
8. PC에서 보기: `ssh -i <키> -L 8088:127.0.0.1:8088 $CHAEKCHECK_SERVER` 뒤 `http://localhost:8088/search`

## 알아 둘 것

- 덤프는 PC DB의 Liquibase 기록을 그대로 가져갑니다. 이 jar와 다른 changelog로 고친 뒤(예: sitecontent seed 교체) 다시 올리면 checksum 오류가 날 수 있습니다. 그때는 DB를 새로 뜨거나 `docs/cycles/plan-feedback.md` `# 17 dev에게`의 지우고 다시 적용하는 방법을 씁니다
- 시험용이라 `restart: unless-stopped`, 메모리 한도(`mem_limit`)는 두지 않았습니다. 서버에서 재고 정합니다
