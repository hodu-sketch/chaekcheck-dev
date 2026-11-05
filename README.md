# 책첵 (chaekcheck)

전국 공공도서관·전자도서관의 도서 소장 여부를 한 번에 검색하고, AI가 다음에 읽을 책을 추천해 주는 서비스입니다.

🔗 **https://chaekcheck.com**

## 주요 기능

- **통합 소장 검색** — 도서관 여러 곳의 소장·대출 가능 여부를 한 화면에서 확인
- **AI 책 검색** — 자연어로 책을 찾고, 대화하듯 추천을 받을 수 있는 검색
- **나에게 맞는 책** — 이용 도서관의 인기 도서를 기반으로 한 개인 맞춤 추천
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

- [AI 책 찾기 개선 기록](docs/features/aisearch/4-portfolio.md) — 질문 해석 ML, 벡터 후보 찾기, Haiku 고르기를 단계마다 재 보고 넣거나 버린 기록
- [책첵 추천](docs/features/foryou/7-portfolio.md) — 이웃 책 계산, 소개 글 임베딩, 시험 설계와 결과
- [책 설명 모으기·표지 S3](docs/features/book-descriptions/1-portfolio.md) — 데이터 이전과 질의 속도

전체 목차는 [docs/README.md](docs/README.md)에 있습니다. 운영 절차는 `docs/maintenance/`, 기능별 동작·측정은 `docs/features/`에 있습니다.

### 개발 방식

설계(범위, ADR, 인계서)는 비공개 계획 저장소에서 하고, 이 저장소는 그 설계를 구현합니다. 그래서 문서에 「plan `docs/...`」로 계획 저장소 파일을 가리키는 곳이 있습니다. 개발은 Claude Code와 함께 했고, 사이클마다 구현 결과·결정·측정값을 계획 쪽에 넘긴 날짜 순 원기록이 [`docs/cycles/plan-feedback.md`](docs/cycles/plan-feedback.md)(2026-11-05까지)와 사이클 폴더의 `docs/cycles/NN/plan-feedback.md`입니다(정리된 문서가 아니라 작업 일지입니다).

## 로컬 개발 환경

### 필요 사항

- Java 25+, Node.js 24+
- Docker (PostgreSQL 18 + pg_bigm)
- WSL 또는 Linux (Docker 빌드 시)

### 실행

```bash
# 데이터베이스 시작
wsl -d Ubuntu -- docker compose up -d db db-test

# 백엔드
cd backend
./gradlew bootRun

# 프론트엔드
cd web
npm install
npm run dev
```

`.env.example`을 `.env`로 복사한 뒤 필요한 값을 채워 넣으세요.

## 아키텍처

Spring Modulith 기반의 모듈러 모놀리스 구조입니다. 도메인별 모듈(catalog, availability, auth, foryou, aisearch 등)이 공개 API와 이벤트로만 소통하며, 향후 MSA 전환을 염두에 둔 모듈 경계를 유지합니다.

도서관 소장 조회는 Webshare 프록시를 경유하며, fetch-service가 실제 페이지 요청을 분리 처리합니다.

## 라이선스

이 프로젝트는 개인 포트폴리오 프로젝트입니다. 코드는 공개되어 있으나, 별도의 라이선스가 명시되기 전까지 All Rights Reserved입니다.
