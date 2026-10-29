#!/usr/bin/env bash
# pgvector HNSW benchmark in a THROWAWAY container (docs/aisearch/reports/2026-10-29-hnsw.md 3절).
# Never touches the project's compose db / db-test: own name (chaekcheck-hnsw-bench), own port 55432, no volume
# (data dies with the container). Postgres settings as deploy/prod/compose.yaml (shared_buffers 64MB, work_mem 4MB,
# effective_cache_size 768MB); 2 CPUs like the t4g.small server. Removed at the end.
# The image declares a volume for /var/lib/postgresql: always remove with -v so no anonymous volume is left.
# usage: run_pg_bench.sh DATA_DIR(with book_vec.copy, queries.f32, brute-gt.tsv) [--quick] [--keep]
set -euo pipefail
export MSYS_NO_PATHCONV=1  # Git Bash: keep /data:ro and /mnt/... as written for wsl
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$1"; shift
NAME=chaekcheck-hnsw-bench
WSL_DATA="$(wsl -d Ubuntu -- wslpath -a "$(cygpath -m "$DATA")" | tr -d '\r')"
wsl -d Ubuntu -- docker rm -f -v "$NAME" >/dev/null 2>&1 || true
wsl -d Ubuntu -- docker run -d --name "$NAME" -p 55432:5432 --cpus 2 --memory 3g \
  -e POSTGRES_PASSWORD=bench -v "$WSL_DATA:/data:ro" pgvector/pgvector:pg18 \
  postgres -c shared_buffers=64MB -c effective_cache_size=768MB -c work_mem=4MB -c max_parallel_maintenance_workers=1
for i in $(seq 1 30); do
  wsl -d Ubuntu -- docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1
done
sleep 2
python "$(cygpath -m "$HERE/pg_bench.py")" "host=localhost port=55432 user=postgres password=bench dbname=postgres" "$(cygpath -m "$DATA")" /data/book_vec.copy "$@" \
  | tee "$DATA/pg-bench.log"
case " $* " in *" --keep "*) ;; *) wsl -d Ubuntu -- docker rm -f -v "$NAME" >/dev/null ;; esac
