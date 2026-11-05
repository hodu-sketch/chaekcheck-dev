# 책첵 (chaekcheck)

전국 공공도서관·전자도서관의 도서 소장 여부를 한 번에 검색하고, AI가 다음에 읽을 책을 추천해 주는 서비스입니다.

🔗 **https://chaekcheck.com**

## 주요 기능

- **통합 소장 검색** — 도서관 여러 곳의 소장·대출 가능 여부를 한 화면에서 확인
- **AI 책 검색** — 자연어로 책을 찾고, 대화하듯 추천을 받을 수 있는 검색
- **책첵 추천(나에게 맞는 책)** — 최근 본 책과 내용이 비슷한 책을 골라 주는 추천. 로그인 없이, 본 책 기록은 브라우저에만 남음
- **카카오 로그인** — 관심 도서관을 저장하고 어디서든 이어서 사용

## 기술 스택

| 영역 | 기술 |
|---|---|
| 백엔드 | Java 25, Spring Boot 4 (Spring Modulith), Hibernate 7, Gradle |
| 데이터베이스 | PostgreSQL 18, Liquibase 마이그레이션 |
| 프론트엔드 | React 19, TypeScript, Vite 8 |
| AI 검색 | Anthropic Claude (Haiku), 자체 MLP 분류 모델, 임베딩 벡터 검색 |
| 인프라 | AWS EC2, S3, Cloudflare Tunnel, Nginx, Docker Compose |

## 프로젝트 구조

```
backend/        Spring Boot 메인 서비스 (모듈러 모놀리스)
web/            React + Vite 프론트엔드
fetch-service/  도서관 페이지 조회 전용 경량 서비스
embed-service/  텍스트 임베딩 서버 (ONNX Runtime)
deploy/         배포 설정 (Nginx, Docker Compose)
docker/         로컬 개발용 Docker 이미지
scripts/        데이터 수집·분석 스크립트
docs/           기능·운영·도서관 조사·사이클 기록 (목차: docs/README.md)
```

## 문서

처음 보신다면 이 세 편부터 읽어 주세요. 무엇을 만들었고, 어떻게 재서, 무엇을 고쳤는지를 숫자와 함께 정리했습니다.

- [AI 책 찾기](docs/features/aisearch/0-summary.md) — 말로 물으면 책을 골라 주는 대화형 검색: 질문 해석 ML, 낱말·뜻 검색, Claude Haiku 고르기, 재 보고 고친 것
- [책첵 추천](docs/features/foryou/7-summary.md) — 이웃 책 계산, 소개 글 임베딩, 시험 설계와 결과
- [책 설명 모으기·표지 S3](docs/features/book-descriptions/1-summary.md) — 데이터 이전과 질의 속도

전체 목차는 [docs/README.md](docs/README.md)에 있습니다. 운영 절차는 [docs/maintenance/README.md](docs/maintenance/README.md), 기능별 동작·측정은 [docs/features/](docs/features/), 손으로 돌리는 도구는 [scripts/README.md](scripts/README.md)에 있습니다.

운영은 AWS EC2(시드니) 한 대에서 백엔드를 blue/green 두 자리로 번갈아 띄워 무중단으로 배포합니다. 배포·되돌리기: [deploy/prod/README.md](deploy/prod/README.md). 지금 운영 버전은 `https://chaekcheck.com/api/v1/version`, 버전마다 바뀐 점은 [CHANGELOG.md](CHANGELOG.md).

### 개발 방식

설계(범위, ADR, 인계서)는 계획 저장소 [hodu-sketch/chaekcheck-plan](https://github.com/hodu-sketch/chaekcheck-plan)에서 하고, 이 저장소는 그 설계를 구현합니다. 문서에 「plan `docs/...`」라고 적힌 경로는 그 저장소 안의 파일입니다. 개발은 Claude Code와 함께 했고, 사이클마다 구현 결과·결정·측정값을 계획 쪽에 넘긴 날짜 순 원기록이 [`docs/cycles/plan-feedback.md`](docs/cycles/plan-feedback.md)(2026-11-05까지)와 사이클 폴더의 `docs/cycles/NN/plan-feedback.md`입니다(정리된 문서가 아니라 작업 일지입니다).

## 로컬 개발 환경

### 필요 사항

- Java 25+, Node.js 24+
- Docker. DB 이미지 `docker/postgres-bigm`은 PostgreSQL 18 + pg_bigm + pg_search(BM25) + pgvector입니다(2026-10-30 전에 만든 이미지는 `docker compose build`로 다시 빌드).
- Windows에서는 WSL(Ubuntu) 안의 Docker를 `wsl -d Ubuntu -- docker compose …`로 부릅니다. Linux·macOS에서는 앞의 `wsl -d Ubuntu --`를 빼고 실행합니다.

### 실행

먼저 저장소 맨 위에서 `.env.example`을 `.env`로 복사하고 `DB_PASSWORD`, `TEST_DB_PASSWORD`를 채웁니다. DB와 백엔드가 모두 이 파일을 읽습니다.

```bash
# 데이터베이스 시작 (db: 개발용 5432, db-test: 테스트용 5433)
wsl -d Ubuntu -- docker compose up -d db db-test

# 백엔드
cd backend
./gradlew bootRun

# 프론트엔드
cd web
npm install
npm run dev
```

- 처음 띄운 DB에는 도서관·지역 seed만 있고 **책은 없습니다.** 책 목록은 수집 DB 사본(git 밖 `chaekcheck-data`)을 가져오기 모드로 넣습니다: [docs/maintenance/3-import.md](docs/maintenance/3-import.md).
- `fetch-service/`(8091)와 `embed-service/`(8090)는 로컬 개발에 필요 없습니다. fetch-service는 `FETCH_MODE=fetch-service`일 때만 쓰고 운영에서도 쓰지 않습니다. embed-service는 운영 AI 책 찾기가 질문을 벡터로 바꾸는 별도 서버입니다([docs/maintenance/6-aisearch.md](docs/maintenance/6-aisearch.md) 7-1절).

### 테스트

- 백엔드: `db-test`를 띄운 뒤 `backend/`에서 `./gradlew test`
- 웹: `web/`에서 `npm test`(Vitest), `npm run lint`, `npm run build`

## 아키텍처

Spring Modulith 기반의 모듈러 모놀리스 구조입니다. 도메인별 모듈(catalog, availability, auth, foryou, aisearch 등)이 공개 API와 이벤트로만 소통하며, 향후 MSA 전환을 염두에 둔 모듈 경계를 유지합니다.

도서관 소장 조회는 백엔드가 직접 하며, 서버 자체 IP와 Webshare 고정 프록시를 나눠 씁니다. `fetch-service/`는 조회만 따로 떼는 선택 구성이고(`FETCH_MODE=fetch-service`) 지금 운영에서는 쓰지 않습니다.

API 문서(OpenAPI)는 따로 없습니다. 주소는 `/api/v1/...`이고 각 모듈의 `*Controller.java`가 원본입니다. 경로별 nginx 한도는 [deploy/nginx/README.md](deploy/nginx/README.md).

## 라이선스

이 프로젝트는 개인 프로젝트입니다. 코드는 공개되어 있으나, 별도의 라이선스가 명시되기 전까지 All Rights Reserved입니다.
