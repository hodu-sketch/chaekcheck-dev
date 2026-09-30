# dev 스킬 변경안: api-design → ProblemDetail

- 대상 파일: `chaekcheck-dev/.claude/skills/api-design/SKILL.md`
- 요청: 사용자, 2026-09-27 ("스킬을 ProblemDetail에 맞춰서 고쳐줘")
- 적용 방법: plan 프로젝트는 dev 폴더에 쓸 수 없습니다 (설정의 deny 규칙과 guard 훅). **dev 세션에서 아래 교체 내용을 적용합니다.** 인계서(`handoff.md`)에도 이 작업을 넣습니다.
- 근거: RFC 9457 (https://www.rfc-editor.org/rfc/rfc9457.html, 2026-09-27 확인) — 미디어 타입 `application/problem+json`, 표준 멤버 `type`(없으면 `about:blank`), `title`, `status`, `detail`, `instance`, 확장 멤버 허용, RFC 7807을 대체
- 결정: ADR-0010 (응답·오류 형식)
- Spring Boot 4.1에서 ProblemDetail을 켜는 설정 이름과 기본값은 [확인 필요] (dev에서 Context7로 공식 문서 확인)

## 바꾸는 부분 (스킬 파일 안의 영어 원문을 아래로 교체)

### 1. `### Common Mistakes`의 404 예시

```
# GOOD: Use HTTP status codes semantically
HTTP/1.1 404 Not Found
Content-Type: application/problem+json
{ "type": "about:blank", "title": "Not Found", "status": 404,
  "detail": "Library 99 does not exist", "code": "library_not_found" }
```

### 2. `## Response Format` 전체

````markdown
## Response Format

This project uses **flat success responses** and **RFC 9457 Problem Details for errors** (see the plan project's ADR on response and error format). Do not wrap success bodies in `data`/`meta`, and do not use the `{ "error": { ... } }` shape.

### Success Response

Return the resource or result object directly. Use camelCase field names.

```json
{
  "id": 2,
  "name": "Goheung County Library e-library",
  "createdAt": "2026-09-27T10:30:00Z"
}
```

### Collection / Page Response

Return an object that holds the items and the paging fields together.

```json
{
  "items": [ { "id": 1, "name": "..." } ],
  "page": 1,
  "pageSize": 20,
  "totalCount": 142,
  "hasMore": true
}
```

### Error Response (RFC 9457 Problem Details)

- Media type: `application/problem+json`
- Standard members: `type` (URI; `about:blank` when the status code says enough), `title`, `status`, `detail`, `instance`
- Extension members used in this project:
  - `code`: stable machine-readable error code in snake_case (e.g. `validation_error`, `upstream_timeout`). Clients branch on `code`, not on `title` or `detail`.
  - `errors`: field-level validation details
- Never put stack traces, SQL, or upstream response bodies in `detail`.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/regions/resolutions",
  "code": "validation_error",
  "errors": [
    { "field": "ids", "message": "At most 20 ids are allowed", "code": "too_many" }
  ]
}
```

### Spring implementation notes

- Use Spring's `ProblemDetail` / `ErrorResponse` (for example from a `@RestControllerAdvice`) and set `code` with `setProperty("code", ...)`.
- How to enable Problem Details globally in Spring Boot 4.1 and its default: check the current Spring Boot docs with Context7 before writing code.
````

### 3. `### Rate Limiting` → `### Headers`의 429 예시

```
# When exceeded
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/problem+json
{ "type": "about:blank", "title": "Too Many Requests", "status": 429,
  "detail": "Rate limit exceeded. Try again in 60 seconds.", "code": "rate_limit_exceeded" }
```

### 4. `### Authorization Patterns`의 코드 예시

- `res.status(404).json({ error: { code: "not_found" } })` → Problem Details 본문(`status`, `title`, `code`)으로 바꿈
- `res.json({ data: order })` → `res.json(order)`

### 5. `### Pagination`의 JSON 예시와 `## Implementation Patterns`의 `{"data": ...}`

- `data`/`meta` 래퍼를 빼고 2번의 Collection / Page Response 모양으로 바꿈

### 6. `## API Design Checklist`

- "Error responses follow standard format with codes and messages" → "Error responses are RFC 9457 Problem Details (`application/problem+json`) with a `code` extension member"
- 항목 추가: "Success responses are flat (no `data`/`meta` wrapper) and use camelCase"
