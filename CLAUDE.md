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
- `fetch-service/` — small Spring Boot app that only GETs library pages for the main service. Separate deployment unit, not a Modulith module, no cache or host throttling (ADR-0011, ADR-0015). Production runs `FETCH_MODE=direct`, so it is not deployed. Own Gradle Wrapper build; commands (run in `fetch-service/`): `./gradlew build`, `./gradlew test`, `./gradlew bootRun`.
- `embed-service/` — small Spring Boot app (no DB) that turns AI-search questions into vectors (ONNX Runtime) on its own micro EC2; `docs/maintenance/6-aisearch.md` 7-1.
- `docker/` — local Docker images used by `compose.yaml` (`postgres-bigm/`: PostgreSQL 18 + pg_bigm + pg_search + pgvector for `db` and `db-test`, ADR-0032). Build with `wsl -d Ubuntu -- docker compose build`.
- `deploy/` — `prod/`: the production deployment (AWS EC2 + Cloudflare Tunnel, blue/green backend slots, `bin/release.sh`, `bin/switch.sh`; see `deploy/prod/README.md`); `nginx/`: web server config (production uses `ec2-tunnel.conf`); `test/`: the cycle 17 trial deployment record.
- `docs/` — documentation, index `docs/README.md`.
- `scripts/` — hand-run tools (crawl, library research, AI search data), index `scripts/README.md`.
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
- Production has been running since 2026-10-18. The pre-deployment exception (marker file `PRE_DEPLOYMENT`, which allowed editing existing changesets) ended; the marker was removed on 2026-11-05 and the guard hook (`.claude/hooks/guard.mjs`) now blocks edits to existing changelog files. Every schema or seed change is a new changeset file.
- Do not let Hibernate create or update the schema; every schema change goes through Liquibase.

## Docs Change With the Code (required)

Docs are read by people and by AI agents that act on them, so a stale doc does harm. A change is not done until the docs that describe it say the same thing, in the same commit or PR.

- Feature behavior (what a request does, ordering, limits, prompts, models, data it reads): the feature folder `docs/features/<feature>/` — its how-it-works doc and its version history (`aisearch/2-versions.md`, `foryou/4-history.md`; a feature without one gets a line in CHANGELOG only). Summaries (`0-summary.md`, `7-summary.md`, `1-summary.md`) when a headline number or statement changes.
- A setting added, renamed or with a new default: `docs/maintenance/settings-index.md`. If production must get it, also `deploy/prod/compose.yaml` (`x-backend.environment`, or the container never sees it) and `deploy/prod/prod.env` with the production value.
- How something is run or operated (deploy, import, crawl, covers, server): the matching `docs/maintenance/` file and, for deploy, `deploy/prod/README.md`.
- A production switch flipped on the server: the same value in the repository's `deploy/prod/prod.env` and the doc that says what production runs.
- A script added, renamed or removed: its own header (what it does, how to run it, which doc explains it), then `python scripts/index_scripts.py`.
- Library research: the record rules below.
- Every change that will ship: a line under `## 다음 판 (아직 배포 안 됨)` at the top of `CHANGELOG.md`; the release turns that part into `## vX.Y.Z`.
- Write the present state in the present tense and mark old numbers with their date or version ("2026-10-28 잰 값", "v1.22까지"). Dated reports and `docs/cycles/` records are history: do not rewrite them, but make their top say which version they describe and where the current state is.
- In markdown write `\~` for a range outside code (GitHub draws `~a~` as strikethrough). Never write a server IP or a secret in a doc.

## Web Rules

- Desktop layout first, then add responsive breakpoints for tablet and mobile.
- Commands (run in `web/`): `npm install`, dev `npm run dev`, build `npm run build`.

## Tools

- Use the Context7 MCP server to check current docs before writing code for Spring Boot 4, Jackson 3, React 19, Vite 8, or Liquibase; do not rely on older examples.
- Use the Playwright MCP server to check web layouts at desktop, tablet, and mobile widths.
- Dev servers for the app's browser preview live in `.claude/launch.json` (name, command, port; the preview starts them by name). The committed entries are the plain ones (`web`, `web-lan`, `backend`, …) with no PC paths.
  - A worktree or other folder gets its own entry only while that work lasts. Name it `<folder short name>-<web|backend>[-lan]-<port>` (e.g. `c27-web-5210` for `../chaekcheck-dev-c27`), point the command at that folder (`npm --prefix ../chaekcheck-dev-c27/web …`), and pick a port no other entry uses.
  - Such entries are not committed. Remove them when the work ends (merged, abandoned, or the folder removed), together with the worktree and its docker project.

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

The plan repository is public (github.com/hodu-sketch/chaekcheck-plan); clone it next to this repository as `chaekcheck-plan/` for the import below to work.

@../chaekcheck-plan/docs/current/handoff.md
