# PostgreSQL 18 with the pg_bigm extension, for the local db and db-test services (ADR-0032).
# pg_bigm: https://github.com/pgbigm/pg_bigm (The PostgreSQL License). Install steps follow docs/pg_bigm_en.md:
# build with USE_PGXS=1, then preload the library (shared_preload_libraries) and CREATE EXTENSION per database.
FROM postgres:18

ARG PG_BIGM_TAG=v1.2-20250903

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
    apt-get purge -y --auto-remove curl build-essential libicu-dev "postgresql-server-dev-${PG_MAJOR}"; \
    rm -rf /var/lib/apt/lists/*

CMD ["postgres", "-c", "shared_preload_libraries=pg_bigm"]
