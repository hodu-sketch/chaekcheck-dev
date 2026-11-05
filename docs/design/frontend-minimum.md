# chaekcheck 프론트엔드 최소 지식

> 작성일: 2026-09-24 · 대상: Java/Spring 백엔드 개발자, JS·Node.js 기초 경험 있음
> 목적: chaekcheck(데스크톱 우선 반응형 웹 + 폰 앱)를 **완성하는 데 필요한 최소한**만 정리
> 근거: 각 항목 끝의 `[번호]`는 문서 맨 아래 **공식 출처** 번호. 출처 없는 내용은 `안(案)` 또는 `확인 필요`로 표시

---

## 0. 전제 스택

| 영역 | 스택 | 비고 |
|---|---|---|
| 백엔드 | Java 25, Spring Boot 4.1.1, Gradle 9.7.1, PostgreSQL | 이 문서 범위 밖 |
| 웹 | React 19.3.0 + Vite 8.3.1 + TypeScript, Node.js 24 LTS | Vite는 Node 20.19+ / 22.12+ 필요 [4] |
| 앱 | React Native + Expo | 웹 완성 후 착수 |

---

## 1. 전체 그림 — 프론트는 "API를 부르는 별도 프로그램"

```
[브라우저: React 웹] ──┐
                       ├── HTTP/JSON ──▶ [Spring Boot backend]
[폰: Expo 앱]      ────┘
```

- 백엔드는 JSON API만 제공. 화면은 전부 프론트가 그림
- **개발 중**: Vite 개발 서버의 `server.proxy`로 `/api` 요청을 Spring으로 넘김. 이 설정은 **개발 서버에만** 적용 [6]
- **배포 후**: 웹과 API의 origin(도메인·포트)이 다르면 브라우저가 막음 → Spring에서 CORS 허용 필요 [16][17]

---

## 2. React 핵심 (웹·앱 공통으로 그대로 쓰임)

React는 스스로를 **"web과 native UI를 위한 라이브러리"**로 소개하며, 같은 기술로 웹 앱과 네이티브 앱을 모두 만든다고 설명한다 [1].

### 2.1 반드시 알아야 할 9가지

| # | 개념 | 한 줄 요약 | Java로 치면 | 근거 |
|---|---|---|---|---|
| 1 | 컴포넌트 | **마크업을 반환하는 JS 함수**. 이름은 대문자로 시작 | View 클래스 | [2] |
| 2 | JSX 규칙 | 루트는 하나(`<>...</>`로 감싸기), `class` 대신 `className`, 태그는 반드시 닫기(`<br />`) | 템플릿 문법 | [2] |
| 3 | `{ }` | JSX 안에서 JS 값·식을 넣는 통로. 속성도 `src={url}` | `${}` | [2] |
| 4 | props | 부모가 자식에게 넘기는 값 | 생성자 인자 | [2] |
| 5 | state (`useState`) | `const [값, set함수] = useState(초기값)`. set 함수로 바꾸면 화면이 다시 그려짐 | 필드 + 변경 시 리렌더 | [2] |
| 6 | 불변성 | state 안의 객체는 **읽기 전용**으로 취급. 직접 수정하면 화면이 안 바뀜 → 새 객체를 만들어 set | 불변 객체 교체 | [11] |
| 7 | 순수성 | 같은 입력이면 같은 JSX. 개발 모드(Strict Mode)에선 **일부러 두 번 호출**해서 검사함 | 부수효과 없는 메서드 | [12] |
| 8 | 조건·목록 | `if` / `? :` / `&&`로 조건 렌더링, `map()`으로 목록. **목록 항목엔 고유한 `key` 필수** | for 루프 출력 | [2] |
| 9 | Hook 규칙 | `use`로 시작하는 함수 = Hook. **컴포넌트 최상단에서만** 호출(조건문·반복문 안 금지) | — | [2] |

**state를 여러 컴포넌트가 공유해야 하면** → 공통 부모로 state를 올리고(lifting state up) props로 내려준다 [2].

```tsx
// 1~8을 한 번에 보는 예시 (웹 기준)
import { useState } from 'react';

export default function BookList({ books }: { books: { id: number; title: string }[] }) {
  const [filter, setFilter] = useState({ keyword: '' });           // 5. state
  const shown = books.filter(b => b.title.includes(filter.keyword)); // 7. 렌더 중엔 계산만
  return (
    <>                                                               {/* 2. 루트 하나 */}
      <input className="search" value={filter.keyword}
        onChange={e => setFilter({ ...filter, keyword: e.target.value })} /> {/* 6. 새 객체 */}
      {shown.length === 0 && <p>결과 없음</p>}                        {/* 8. 조건 */}
      <ul>{shown.map(b => <li key={b.id}>{b.title}</li>)}</ul>        {/* 8. 목록 + key */}
    </>
  );
}
```

### 2.2 `useEffect` — "외부 시스템과 동기화"할 때만

- 정의: **컴포넌트를 외부 시스템과 동기화**하기 위한 Hook [3]
- 의존성 배열: `[a, b]` → 값이 바뀔 때마다 / `[]` → 처음 한 번 / 생략 → 매 렌더마다 [3]
- 반환하는 함수는 **cleanup**(재실행 전·화면에서 사라질 때 실행) [3]
- 개발 모드에선 setup → cleanup → setup으로 **한 번 더 실행**됨. 이걸 버그로 착각하지 말 것 [3]

### 2.3 서버 데이터 가져오기 — `useEffect`로 직접 하지 않기

React 공식 문서는 Effect에서 직접 fetch하면 **네트워크 워터폴, 캐싱 없음, 경쟁 상태 처리 보일러플레이트** 등의 단점이 있다고 명시하고, **TanStack Query 같은 클라이언트 캐시 라이브러리**를 대안으로 권장한다 [3][5].
TanStack Query는 서버 상태의 **fetch·캐싱·동기화·갱신**을 담당하는 라이브러리 [13].

> 안(案): Spring API 호출은 TanStack Query로 통일. (웹·앱 모두 사용 가능 여부는 앱 단계에서 확인 필요)

---

## 3. TypeScript — Java 개발자가 헷갈리는 3가지

| 차이 | 내용 | 근거 |
|---|---|---|
| 구조적 타입 | Java처럼 `implements`로 관계를 맺지 않음. **속성 모양이 맞으면 같은 타입으로 인정** | [14] |
| 타입은 런타임에 없음 | 컴파일 후 타입 정보가 사라짐. 리플렉션처럼 런타임에 타입 확인 불가 → `typeof`, `instanceof` 사용 | [14] |
| 클래스가 기본 단위가 아님 | 함수 + 데이터(객체)로 충분. React 컴포넌트도 함수 | [14] |

> 실무 최소: `interface` / `type`으로 **API 응답 모양을 정의**하는 것부터. Spring DTO와 필드명을 맞추면 됨.

---

## 4. 웹 전용 (React + Vite)

### 4.1 Vite

| 항목 | 내용 | 근거 |
|---|---|---|
| 정체 | 개발 서버 + 프로덕션 빌드 도구 | [4] |
| 생성 | `npm create vite@latest web -- --template react-ts` (React 공식 문서의 명령과 동일 형식) | [5] |
| 진입점 | `index.html`이 소스의 중심(진입점) | [4] |
| 환경변수 | `import.meta.env`로 읽음. **`VITE_` 접두사만** 브라우저 코드에 노출 | [7] |
| ⚠️ 보안 | `VITE_*` 값은 빌드 시 **소스에 박힘** → API 키·비밀값 절대 금지 | [7] |
| 프록시 | `server.proxy`로 `/api` → Spring 전달 (**개발 서버 전용**) | [6] |

```ts
// web/vite.config.ts — 개발 중 /api 요청을 Spring으로 (포트 8080은 안(案), 확인 필요)
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
});
```

### 4.2 라우팅 (URL ↔ 화면)

- React 자체는 라우팅·데이터 fetch 방식을 정하지 않는 **라이브러리** [1]
- 공식 문서가 Vite로 직접 만들 때 제시하는 라우터: **React Router**, TanStack Router [5]
- React Router는 3가지 모드: **Declarative**(기본 라우팅) / **Data**(loader·action 추가) / **Framework**(Vite 플러그인 기반 풀 기능) [15]

> 안(案): Declarative 모드로 시작 → 필요하면 Data 모드. Framework 모드는 사실상 프레임워크 도입이라 초기엔 제외.

### 4.3 CORS (배포 시)

- 브라우저의 `fetch()`는 **동일 출처 정책**을 따름. 다른 origin의 응답은 올바른 CORS 헤더가 있어야 받을 수 있음 [17]
- Spring: `WebMvcConfigurer.addCorsMappings()`로 전역 설정 또는 `@CrossOrigin` [16]

```java
// backend — 허용 origin 값은 배포 도메인 확정 후 채울 것 (확인 필요)
@Configuration
class CorsConfig implements WebMvcConfigurer {
    @Override public void addCorsMappings(CorsRegistry r) {
        r.addMapping("/api/**").allowedOrigins("TODO").allowedMethods("GET","POST","PUT","DELETE");
    }
}
```

---

## 5. 반응형 (데스크톱 → 태블릿 → 모바일)

반응형 웹 = **모든 화면 크기·해상도에서 잘 보이고 쓰기 좋게** 만드는 설계 방식 [8]

| 필수 요소 | 내용 | 근거 |
|---|---|---|
| viewport 메타 | `<meta name="viewport" content="width=device-width" />` — **항상 넣을 것**. 없으면 모바일 브라우저가 폭을 980px 등으로 가정해서 미디어쿼리가 제대로 안 먹음 | [8] |
| 미디어쿼리 | `@media (width >= 80rem) { ... }` 처럼 조건부 CSS. 레이아웃이 바뀌는 지점 = **breakpoint** | [8] |
| 유연한 레이아웃 | Flexbox(`display:flex`, `flex:1`), Grid(`grid-template-columns: 1fr 1fr 1fr`) — 현대 CSS 레이아웃은 **본질적으로 반응형** | [8] |
| 이미지 | `img, picture, video { max-width: 100%; }` | [8] |
| 글자 크기 | `font-size: calc(1.5rem + 4vw)` — `vw`만 쓰면 확대가 안 되므로 `rem`과 섞기 | [8] |

### 데스크톱 우선 vs 모바일 우선

- MDN이 소개하는 **일반적인 방식은 모바일 우선**: 좁은 화면용 1단 레이아웃을 먼저 만들고, `min-width`(`width >=`) 쿼리로 넓은 화면을 추가 [8]
- chaekcheck는 **데스크톱 화면을 먼저 설계**하기로 함

> 안(案): **디자인·개발 순서는 데스크톱 먼저**, CSS 작성 방식은 두 가지 중 택1 (확인 필요)
> - A. 데스크톱 기본 + `width <=` 쿼리로 좁은 화면 대응 (생각 순서와 코드가 일치)
> - B. 데스크톱을 먼저 디자인하되 CSS는 MDN 방식(모바일 기본 + `width >=`)으로 작성
>
> breakpoint 값(예: 태블릿·모바일 경계)은 **아직 미정 — 확인 필요**

---

## 6. 앱 전용 (React Native + Expo)

React는 React Native와 Expo로 **Android·iOS 앱**을 만들 수 있고, UI가 **실제 네이티브**라고 설명한다 [1].

### 6.1 웹과 무엇이 같고 무엇이 다른가

| 구분 | 웹 (React) | 앱 (React Native) | 근거 |
|---|---|---|---|
| 개념 | 컴포넌트, props, state, Hook | **동일** | [1] |
| 화면 요소 | `div`, `p`, `img`, `input` | `View`, `Text`, `Image`, `TextInput` (각각 Android/iOS 네이티브 뷰로 그려짐) | [9] |
| 스크롤 | 브라우저가 처리 | `ScrollView`를 명시적으로 사용 | [9] |
| 스타일 | CSS | **CSS 없음**. `style` prop에 JS 객체, 속성명은 camelCase(`backgroundColor`), `StyleSheet.create` | [10] |
| Flexbox 기본값 | `flex-direction: row` | **`flexDirection: column`**, `flexShrink: 0` 등 기본값이 다름 | [18] |
| 라우팅 | React Router | **Expo Router** — `app/` 폴더의 파일이 곧 경로(파일 기반), Android·iOS·웹 지원 | [19] |
| 반응형 | 미디어쿼리 | `useWindowDimensions()` — 화면 크기·글자 배율이 바뀌면 값이 자동 갱신 | [20] |
| 생성 | `npm create vite@latest` | `npx create-expo-app@latest` | [5][21] |

### 6.2 웹 코드 중 앱에서 재사용되는 것 / 안 되는 것

| 재사용 가능 | 다시 작성 |
|---|---|
| API 호출 함수, TypeScript 타입, 커스텀 Hook, 비즈니스 로직 | 화면 마크업(`div` → `View`), 스타일(CSS → StyleSheet), 라우팅 |

> 안(案): 웹 개발 시 **API 호출·타입을 컴포넌트에서 분리**해 두면 앱 단계의 재작성 범위가 줄어듦 (재사용 방식은 앱 착수 시 확정)

---

## 7. React의 특징과 다른 선택지와의 차이

### 7.1 React만의 특징 (공식 문서 기준)

| 특징 | 내용 | 근거 |
|---|---|---|
| **라이브러리** | 컴포넌트 조립만 담당. 라우팅·데이터 fetch 방식은 **스스로 고름** | [1] |
| **JS 그대로 UI 작성** | 조건은 `if`, 목록은 `map()` — "React를 배우는 것은 프로그래밍을 배우는 것" | [1] |
| **웹 + 네이티브** | 같은 기술로 웹과 Android·iOS 앱 (React Native, Expo) | [1] |
| **단방향 데이터 흐름** | 데이터는 props로 위→아래, 공유 state는 공통 부모로 올림 | [2] |

### 7.2 다른 것들과 아주 간단한 비교

| 이름 | 정체 (공식 한 줄) | React와 가장 큰 차이 | 근거 |
|---|---|---|---|
| **Vue** | UI를 만드는 JavaScript **프레임워크** | HTML 템플릿 + `.vue` 파일 하나에 template·script·style. 상태 변경을 **자동 추적**(React는 set 함수 호출) | [22] |
| **Angular** | 웹 **프레임워크** | 라우터·폼·HTTP 클라이언트·DI·TypeScript를 **기본 포함**(Spring처럼 "다 갖춘" 쪽). React는 직접 골라 붙임 | [23] |
| **Svelte** | **컴파일러**로 컴포넌트를 최적화된 JS로 변환하는 프레임워크 | 빌드 시점에 컴파일 → 런타임 라이브러리 부담이 작음 | [24] |
| **Next.js** | 풀스택 웹앱을 위한 **React 프레임워크** | React **위에** 라우팅·서버 렌더링·최적화를 얹은 것. 경쟁자가 아니라 확장판 | [25] |
| **Flutter** | Dart로 **단일 코드베이스** 멀티플랫폼 앱을 만드는 UI 툴킷 | 언어가 Dart, 웹·앱 한 벌. JS/React 생태계와 별개 | [26] |

> chaekcheck에서 React + Vite(웹) + Expo(앱)를 고른 이유: JS 경험 활용, 한국 채용 수요(사람인 키워드 검색 2026-09-24: react 984건 / flutter 159건), 데스크톱 우선 웹, 스토어 앱

---

## 8. 학습 순서 체크리스트

- [ ] **1단계 — React 기초**: 2.1의 9가지 + 2.2 `useEffect`의 의미 (react.dev Quick Start [2])
- [ ] **2단계 — TypeScript 최소**: 3장의 3가지 + API 응답 타입 정의
- [ ] **3단계 — Vite 프로젝트**: 생성, `index.html`, 환경변수 규칙, `/api` 프록시 연결 (4.1)
- [ ] **4단계 — 라우팅**: React Router로 페이지 2\~3개 연결 (4.2)
- [ ] **5단계 — 데이터**: TanStack Query로 Spring API 조회·등록 (2.3)
- [ ] **6단계 — 반응형**: viewport 메타, Flex/Grid, 미디어쿼리 (5장)
- [ ] **7단계 — 배포 준비**: CORS (4.3)
- [ ] **8단계 — 앱**: Expo 생성, View/Text/StyleSheet, Expo Router, `useWindowDimensions` (6장)

## 9. 지금은 몰라도 되는 것

- 전역 상태 라이브러리(Redux 등) — 서버 데이터는 TanStack Query, 나머지는 `useState` + props로 충분 [2][13]
- Next.js·서버 렌더링 — 이번 스택은 Vite SPA [5]
- React Router Framework 모드 [15]
- CSS를 처음부터 직접 설계하는 일 — UI 컴포넌트 라이브러리 사용 안(案) (라이브러리 선택은 확인 필요)

## 10. 확인 필요 항목

| 항목 | 상태 |
|---|---|
| 반응형 CSS 작성 방식 (5장 A/B) | 미정 |
| breakpoint 값 | 미정 |
| UI 컴포넌트 라이브러리 | 미정 |
| Spring 개발 서버 포트 (4.1 프록시 target) | 미정 (예시 8080은 안) |
| 배포 도메인 (4.3 CORS origin) | 미정 |
| TanStack Query를 Expo 앱에서도 동일하게 쓰는 방식 | 앱 단계에서 확인 |

---

## 출처 (공식 문서, 2026-09-24 확인)

1. React 홈 — https://react.dev/
2. React Quick Start — https://react.dev/learn
3. React `useEffect` — https://react.dev/reference/react/useEffect
4. Vite Getting Started — https://vite.dev/guide/
5. React: Build a React app from Scratch — https://react.dev/learn/build-a-react-app-from-scratch
6. Vite Server Options (`server.proxy`) — https://vite.dev/config/server-options
7. Vite Env Variables and Modes — https://vite.dev/guide/env-and-mode
8. MDN Responsive web design — https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/CSS_layout/Responsive_Design
9. React Native Core Components — https://reactnative.dev/docs/intro-react-native-components
10. React Native Style — https://reactnative.dev/docs/style
11. React: Updating Objects in State — https://react.dev/learn/updating-objects-in-state
12. React: Keeping Components Pure — https://react.dev/learn/keeping-components-pure
13. TanStack Query Overview — https://tanstack.com/query/latest/docs/framework/react/overview
14. TypeScript for Java/C# Programmers — https://www.typescriptlang.org/docs/handbook/typescript-in-5-minutes-oop.html
15. React Router Modes — https://reactrouter.com/start/modes
16. Spring Framework CORS — https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html
17. MDN CORS — https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS
18. React Native Flexbox — https://reactnative.dev/docs/flexbox
19. Expo Router Introduction — https://docs.expo.dev/router/introduction/
20. React Native `useWindowDimensions` — https://reactnative.dev/docs/usewindowdimensions
21. Expo Create a project — https://docs.expo.dev/get-started/create-a-project/
22. Vue Introduction — https://vuejs.org/guide/introduction.html
23. Angular Overview — https://angular.dev/overview
24. Svelte Overview — https://svelte.dev/docs/svelte/overview
25. Next.js Docs — https://nextjs.org/docs
26. Flutter Docs — https://docs.flutter.dev/
