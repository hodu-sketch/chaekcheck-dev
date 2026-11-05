# web — 책첵 화면

React 19 + Vite 8 + TypeScript. 데스크톱 먼저, 태블릿·폰 폭은 그다음에 맞춥니다(CLAUDE.md 「Web Rules」).

## 실행과 확인 (이 폴더에서)

- `npm install`
- `npm run dev`: 개발 서버 5173. `/api`는 `API_PROXY_TARGET`(기본 `http://localhost:8080`, `vite.config.ts`)으로 넘깁니다. 백엔드를 먼저 띄웁니다(저장소 맨 위 README 「로컬 개발 환경」).
- `npm test`(Vitest), `npm run lint`(oxlint), `npm run build`(타입 검사 + 빌드, 결과 `dist/`)

## 구조

- `src/features/<기능>/`: 화면마다 폴더(검색, 책, 추천도서, AI 책 찾기 등)
- `src/api/`: 백엔드 API 호출
- 운영에서는 `dist/`를 nginx가 내보냅니다([`deploy/nginx/README.md`](../deploy/nginx/README.md)).
