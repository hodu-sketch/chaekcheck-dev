# chaekcheck

웹(데스크톱 우선 + 반응형) + 폰 앱(Expo)을 갖춘 개인 프로젝트. 백엔드는 Java/Spring.

## Stack (versions verified 2026-09-24)

- Backend: Java 25 (LTS), Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7.1, Jackson 3), Gradle 9.7.1 via Gradle Wrapper
- DB: PostgreSQL 18, schema migrations with Liquibase
- Not used: Supabase or any other backend-as-a-service. Login, authorization and APIs are implemented in the Spring backend.
- Web: React 19.3.0 + Vite 8.3.1 + TypeScript, Node.js 24 LTS
- App (later): React Native with Expo (version decided when the app phase starts)

## Directory Layout

- `backend/` — the main service: one Spring Boot application (Spring Modulith modular monolith, Gradle)
- `fetch-service/` — small Spring Boot app that only GETs library pages for the main service. Separate deployment unit, not a Modulith module, no cache or host throttling (ADR-0011, ADR-0015). Own Gradle Wrapper build; commands (run in `fetch-service/`): `./gradlew build`, `./gradlew test`, `./gradlew bootRun`.
- `docker/` — local Docker images used by `compose.yaml` (`postgres-bigm/`: PostgreSQL 18 + pg_bigm for `db` and `db-test`, ADR-0032). Build with `wsl -d Ubuntu -- docker compose build`.
- `deploy/` — example production deployment config (`nginx/`: serves the web build, forwards `/api` to Spring, security headers and CSP). Product and location are decided at deployment.
- `data/` — local data files (collected SQLite copies, national bibliography TTL). Git-ignored; never commit.
- `web/` — React + Vite (desktop-first, then responsive for tablet/mobile)
- `app/` — Expo app (not created yet; add later)

## Architecture

- Modular monolith with Spring Modulith. MSA is the future direction (later: Kafka, Kubernetes), so keep module boundaries clean from day one.
- Organize `backend/` by domain module, not by technical layer at the top level.
- Modules talk to each other only through each module's public API or application events. Never access another module's internal packages, entities, or repositories.
- Keep it simple: do not add Kafka, service discovery, API gateway, or Kubernetes until explicitly asked.

## Backend Rules

- Build and dependencies: Gradle Wrapper only (`./gradlew`). Never use Maven or create `pom.xml`.
- Gradle DSL: Groovy (`build.gradle`, `settings.gradle`). Never create `.gradle.kts` files.
- Group ID and base package: `com.chaekcheck` (`backend/build.gradle`). Each module is a direct subpackage, e.g. `com.chaekcheck.catalog`.
- Spring Boot 4 / Jackson 3: use `tools.jackson.*` packages (only `com.fasterxml.jackson.annotation` keeps the old package). Do not write Spring Boot 3 / Jackson 2 style code.
- Commands (run in `backend/`): build `./gradlew build`, test `./gradlew test`, run `./gradlew bootRun`. Build output is `build/`.

## Database Migrations (Liquibase)

- Dependency: `spring-boot-starter-liquibase`. Master changelog: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (Spring Boot default). The master file only includes other changelog files; it holds no changesets itself.
- Default format is YAML: tables, columns, indexes, constraints, and data changes.
- Objects whose body is SQL anyway — functions, triggers, views — use Liquibase formatted SQL files (`.sql`):
  - First line exactly `--liquibase formatted sql`, then `--changeset <author>:<id>` per changeset.
  - Functions and triggers: add `splitStatements:false` so the body is not split at inner semicolons.
  - Write them as `CREATE OR REPLACE ...` with `runOnChange:true`, so the same file can be edited and re-applied.
- Never edit a changeset that may already have been applied (Liquibase stops with a checksum error). Add a new changeset instead. The only exception is a `runOnChange:true` formatted SQL file.
- Exception before the first deployment: while `backend/src/main/resources/db/changelog/PRE_DEPLOYMENT` exists, existing changesets may be edited to keep a clean structure. Then reset the local databases (drop the module schemas and the Liquibase tables in `db` and `db-test`) so they are applied again. Delete the marker at the first deployment.
- Do not let Hibernate create or update the schema; every schema change goes through Liquibase.

## Web Rules

- Desktop layout first, then add responsive breakpoints for tablet and mobile.
- Commands (run in `web/`): `npm install`, dev `npm run dev`, build `npm run build`.

## Tools

- Use the Context7 MCP server to check current docs before writing code for Spring Boot 4, Jackson 3, React 19, Vite 8, or Liquibase; do not rely on older examples.
- Use the Playwright MCP server to check web layouts at desktop, tablet, and mobile widths.

## Working Rules

- Do not hardcode config values or constants as if confirmed; mark unknowns as TODO and ask.
- Run the relevant tests/build before saying a task is done.

## Library Research Record (required)

Any work that researches libraries must go through `docs/libraries/library-maintenance.md`, so the same research is never done twice. This covers finding libraries or e-library sites, checking sites, eligibility (who may join), join methods and join pages, front pages, site quirks, and certificates.

- Before researching, read `docs/libraries/library-maintenance.md` and reuse what it already records (methods, sources, results, decisions). Do not research again what is recorded there unless the task is to re-check it.
- In the same task, record in `docs/libraries/library-maintenance.md` every new method, source, tool (`scripts/library/`), per-library finding with the original text and URL, and user decision. Sites left out and the reason go in `docs/libraries/library-site-issues.md`, which is linked from it.
- Eligibility is researched together with the library: whenever a library or e-library site is found, added, or moved, research its eligibility and join method in the same task, by the rules in `docs/libraries/library-maintenance.md` 4-8 and 4-10. Do not add a library now and leave its eligibility for later.
- A library research task is not done until these records are written.

## Current Handoff

Planning and design happen in the separate chaekcheck-plan project; this project implements them. The current handoff (what to build now) is imported below. Do not edit it from here. When the implementation has to differ from the handoff, tell the user; the next cycle in chaekcheck-plan records it.

@../chaekcheck-plan/docs/current/handoff.md
