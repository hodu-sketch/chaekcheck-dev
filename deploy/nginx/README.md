# 운영 웹 서버 설정 예시 (nginx)

사이클 05 16단계. 화면 파일과 `/api`를 한 출처에서 내려 주는 설정 예시입니다. 웹 서버 제품과 위치는 배포 때 정합니다. 여기서는 읽기 쉬운 nginx로 썼습니다.

| 파일 | 하는 일 |
|---|---|
| `chaekcheck.conf` | 운영 예시. 80 → 443 이동, TLS, `root`(빌드 결과), 백엔드 주소. `TODO(확인 필요)`는 배포 때 채움 |
| `chaekcheck-locations.conf` | 운영과 로컬 확인이 같이 쓰는 경로 규칙 |
| `security-headers.conf` | 화면 파일 응답의 보안 헤더(CSP 포함). 고른 까닭은 파일 주석에 |
| `rate-limits.conf` | 도서관을 부르는 API의 IP별 한도 영역(`limit_req_zone`, `limit_conn_zone`). http 단계에 둠 |
| `local-test.conf` | 로컬 확인용. HTTP 8088 |

운영에서는 `chaekcheck.conf`를 nginx `conf.d`에, 나머지 세 파일을 `/etc/nginx/chaekcheck/`에 둡니다.

`rate-limits.conf`의 `limit_req_zone`·`limit_conn_zone`은 http 단계에서만 쓸 수 있습니다. 그래서 `server` 블록 안이 아니라 `chaekcheck.conf`(로컬은 `local-test.conf`) 맨 위, `upstream`보다 앞에서 `include /etc/nginx/chaekcheck/rate-limits.conf;`로 읽습니다. 기본 `nginx.conf`는 `conf.d/*.conf`를 http 블록 안에서 읽으므로 이 자리가 http 단계입니다. `conf.d`를 쓰지 않는 배치라면 `nginx.conf`의 `http { }` 안에 같은 include를 둡니다. 백엔드의 `AUTH_PUBLIC_BASE_URL`은 `https://<server_name>`이어야 합니다.

## 경로 규칙

- `/api/` → Spring 백엔드(같은 출처). API 응답 헤더는 Spring Security가 붙입니다(로그인 켜짐일 때).
- `/api/v1/.../stream` → 같은 백엔드. 검색 스트림이 바로바로 오게 버퍼를 끕니다.
- 도서관을 부르는 API에는 IP별 한도를 둡니다(2026-10-05 사용자 요청). 넘으면 429와 Problem Details(`code: too_many_requests`, `Retry-After: 2`).
  - 대상: `/api/v1/ebook-searches/stream`, `/api/v1/ebook-searches/batches/stream`, `/api/v1/book-availabilities/stream`, `/api/v1/book-availabilities/batches/stream`, `/api/v1/availability/...`(도서관별 "더 보기")
  - 요청 수 [안]: IP마다 평균 2초에 1번(30r/m), 한 번에 20번까지(`burst=20 nodelay`). 다섯 경로가 한 통을 같이 씁니다.
  - 동시 연결 [안]: IP마다 6개. 스트림은 30초 넘게 열려 있고 검색 화면은 스트림을 한두 개 엽니다.
  - 백엔드도 모든 사용자를 합쳐 동시에 도는 검색 수를 막습니다(`chaekcheck.availability.max-concurrent-searches`, 503 `server_busy`).
  - TODO(확인 필요): 배포 때 nginx 앞에 부하 분산기나 CDN이 있으면 `real_ip_header`·`set_real_ip_from`을 먼저 맞춰야 합니다. 안 그러면 모든 사용자가 한 주소로 보여 한도를 같이 씁니다.
- `/`, `/search`, `/books/{id}`, `/signup`, `/auth/complete`, `/terms`, `/privacy` → `index.html`
- `/assets/` → 이름에 해시가 든 빌드 파일. 1년 캐시
- 그 밖 → `dist`에 있는 파일만, 없으면 404

## 로컬에서 확인하기

1. 웹 빌드: `web/`에서 `npm run build`
2. 백엔드를 Windows에서 띄웁니다(8080). 로그인을 켜서 볼 때는 `AUTH_PUBLIC_BASE_URL=http://localhost:8088`.
3. WSL에서 nginx를 띄웁니다. `backend-host`는 WSL에서 본 Windows 주소입니다(`ip route`의 default via 값).

```bash
wsl -d Ubuntu -- bash -c 'R=/mnt/c/Users/yujun/Documents/Claude/Projects/chaekcheck-dev; docker run -d --name chaekcheck-nginx-check -p 8088:8088 --add-host backend-host:$(ip route | awk "/default/{print \$3}") -v $R/web/dist:/usr/share/nginx/html:ro -v $R/deploy/nginx:/etc/nginx/chaekcheck:ro -v $R/deploy/nginx/local-test.conf:/etc/nginx/conf.d/default.conf:ro nginx:stable-alpine'
```

4. 브라우저로 `http://localhost:8088/search`를 엽니다. 다 보면 지웁니다.

```bash
wsl -d Ubuntu -- docker rm -f chaekcheck-nginx-check
```

## 시험 결과 (2026-10-04, nginx 1.30.5, Chromium)

로그인 켜짐(네이버만, 가짜 클라이언트 값)으로 백엔드를 띄우고 확인했습니다.

- 화면 경로 7개 200, 없는 경로(`/nope`, `/books/a/b`) 404. 다섯 헤더가 모두 붙음
- `/api/v1/me` 401과 Spring Security 헤더(`X-Frame-Options`, `X-Content-Type-Options`, `Cache-Control`), `XSRF-TOKEN` 쿠키
- 로그인 창: 글꼴 3개(400·700·800), 네이버 버튼 이미지가 뜨고, 뒤 화면 스크롤이 잠김. 네이버 버튼 → `nid.naver.com`으로 이동(가짜 값이라 네이버가 거절하는 것까지)
- 검색: 책 목록과 대출 현황 스트림이 nginx를 지나 옴
- CSP 위반 0건

`style-src 'self'`(엄격)로도 띄워 봤습니다. 로그인 창을 열 때 Radix가 넣는 `<style>`이 막혀 뒤 화면 스크롤이 잠기지 않았습니다. 그래서 `style-src`에만 `'unsafe-inline'`을 둡니다. `script-src`는 `'self'`만 둡니다.

## 시험 결과 (2026-10-05, 사이클 07: 압축·로고·화면 경로)

위 "로컬에서 확인하기" 3번 명령과 같은 방법으로 확인했습니다. 백엔드는 사이클 06 코드(도서관 280곳).

| 확인 | 결과 |
|---|---|
| `/about`, `/my-libraries` 직접 열기 | 200 `text/html` (전에는 `/my-libraries`가 404) |
| `/library-logos/1.gif` | 200, `Cache-Control: public, max-age=604800` (파일 이름에 해시가 없어 `immutable`은 쓰지 않음) |
| `GET /api/v1/libraries` | 압축 전 48,123바이트 → `Accept-Encoding: gzip`이면 5,404바이트, `Content-Encoding: gzip`, `Vary: Accept-Encoding` |

- 프록시 응답이라 `gzip_proxied any`가 있어야 압축됩니다. 스트림(`text/event-stream`)은 `gzip_types`에 없어 압축되지 않습니다.
- 후보 도서관을 넣은 뒤의 크기는 15단계에서 다시 잽니다(`docs/plan-feedback.md` `# 사이클 07`).

## 시험 결과 (2026-10-05, IP별 한도)

nginx 1.30.5(`nginx:stable-alpine`), `local-test.conf`로 띄우고 백엔드는 띄우지 않았습니다(통과한 요청은 502).

- `nginx -t`: 문법 이상 없음
- `/api/v1/ebook-searches/stream`을 연달아 24번: 21번 통과(502), 22번째부터 429. 한도는 평균 비율 1번 + `burst` 20번
- 이어서 `/api/v1/availability/libraries/1/ebooks` 24번: 모두 429(같은 한도 영역을 씀)
- `/api/v1/books`(도서관을 부르지 않음) 24번: 모두 통과(502). 한도 없음
- 429 응답: `Content-Type: application/problem+json`, `Retry-After: 2`, 본문 `code: too_many_requests`
- 동시 연결 한도(`limit_conn`)는 오래 열린 스트림을 내 줄 백엔드가 있어야 해서 따로 재 보지 않았습니다.
