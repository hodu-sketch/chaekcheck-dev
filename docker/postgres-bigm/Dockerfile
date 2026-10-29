# PostgreSQL 18 with the pg_bigm extension, for the local db and db-test services (ADR-0032).
# pg_bigm: https://github.com/pgbigm/pg_bigm (The PostgreSQL License). Install steps follow docs/pg_bigm_en.md:
# build with USE_PGXS=1, then preload the library (shared_preload_libraries) and CREATE EXTENSION per database.
# pg_search (ParadeDB, AGPL-3.0, used unmodified) for the AI search's BM25 keyword match (user 2026-10-30, measured in
# docs/aisearch/reports/2026-10-29-intro-text.md 10-11): the release .deb for this machine (amd64 on the PC, arm64 on
# the server) and pgvector, which pg_search needs since 0.25.0. Both libraries are preloaded:
# shared_preload_libraries=pg_bigm,pg_search.
FROM postgres:18

ARG PG_BIGM_TAG=v1.2-20250903
ARG PG_SEARCH=0.26.3

# Build packages are removed again after the install. libicu-dev: the PG 18 server headers include ICU headers.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl build-essential libicu-dev "postgresql-server-dev-${PG_MAJOR}"; \
    curl -fsSL "https://github.com/pgbigm/pg_bigm/archive/refs/tags/${PG_BIGM_TAG}.tar.gz" -o /tmp/pg_bigm.tar.gz; \
    mkdir /tmp/pg_bigm; \
    tar -xzf /tmp/pg_bigm.tar.gz -C /tmp/pg_bigm --strip-components=1; \
    make -C /tmp/pg_bigm USE_PGXS=1; \
    make -C /tmp/pg_bigm USE_PGXS=1 install; \
    rm -rf /tmp/pg_bigm /tmp/pg_bigm.tar.gz; \
    apt-get purge -y --auto-remove build-essential libicu-dev "postgresql-server-dev-${PG_MAJOR}"; \
    apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-pgvector"; \
    curl -fsSL -o /tmp/pg_search.deb "https://github.com/paradedb/paradedb/releases/download/v${PG_SEARCH}/postgresql-${PG_MAJOR}-pg-search_${PG_SEARCH}-1PARADEDB-trixie_$(dpkg --print-architecture).deb"; \
    apt-get install -y /tmp/pg_search.deb; \
    rm /tmp/pg_search.deb; \
    apt-get purge -y --auto-remove curl; \
    rm -rf /var/lib/apt/lists/*

CMD ["postgres", "-c", "shared_preload_libraries=pg_bigm,pg_search"]
