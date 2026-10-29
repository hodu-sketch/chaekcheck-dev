#!/usr/bin/env bash
# Java funnel benchmark (docs/aisearch/reports/2026-10-30-vector-scaling.md, way A).
# Compiles CascadeBench.java + NearestBench.java with the real BookVectorFile source (copied, unchanged) in a scratch
# folder; nothing is added to the backend build. Tiled files (500k ~ 0.5GB, 1M ~ 1GB) go to WORK_DIR and are deleted.
# usage: run_cascade_bench.sh WORK_DIR MODEL_DIR QUERIES_F32 OUT_TXT [n1 n2]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
WORK="$1"; MODEL="$2"; QUERIES="$3"; OUT="$4"; N1="${5:-10000}"; N2="${6:-2000}"
JDK="${JDK:-$HOME/.jdks/jdk-25.0.4.1+1}"
PKG="$WORK/src/com/chaekcheck/aisearch/internal/vector"
mkdir -p "$PKG" "$WORK/classes"
cp "$REPO"/backend/src/main/java/com/chaekcheck/aisearch/internal/vector/BookVectorFile.java "$PKG/"
cp "$HERE/NearestBench.java" "$HERE/CascadeBench.java" "$PKG/"
# NearestBench also needs the encoder classes; only its queries()/stats() are used here, so compile without encode
sed -i -e '/case "encode"/d' -e '/static void encode/,/^\t}$/d' "$PKG/NearestBench.java"
"$JDK/bin/javac" -nowarn -d "$WORK/classes" "$PKG"/BookVectorFile.java "$PKG"/NearestBench.java "$PKG"/CascadeBench.java
CP="$(cygpath -w "$WORK/classes" 2>/dev/null || echo "$WORK/classes")"
W="$(cygpath -w "$WORK" 2>/dev/null || echo "$WORK")"
# sizes: real 81,078; x2.467 = 200k; x6.167 = 500k; x12.334 = 1M (SCALES to pick some). The 1M baseline is not run
# (reading it needs two 1 GB arrays; it was measured as 715.7 ms in docs/aisearch/reports/2026-10-29-hnsw.md)
"$JDK/bin/java" -Xmx1700m -Dbaseline.max=600000 -cp "$CP" com.chaekcheck.aisearch.internal.vector.CascadeBench bench "$MODEL" "$QUERIES" "$W" 3 "$N1" "$N2" ${SCALES:-1 2.467 6.167 12.334} | tee "$OUT"
