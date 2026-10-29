#!/usr/bin/env bash
# Brute-force nearest benchmark (docs/aisearch/reports/2026-10-29-hnsw.md 2절).
# Compiles NearestBench.java with the real BookVectorFile / QuestionEncoder / OnnxQuestionEncoder sources (copied,
# unchanged) in a scratch folder; nothing is added to the backend build.
# usage: run_nearest_bench.sh WORK_DIR MODEL_DIR OUT_DIR
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
WORK="$1"; MODEL="$2"; OUT="$3"
JDK="${JDK:-$HOME/.jdks/jdk-25.0.4.1+1}"
G="$HOME/.gradle/caches/modules-2/files-2.1"
CP="$(ls $G/com.microsoft.onnxruntime/onnxruntime/1.31.0/*/onnxruntime-1.31.0.jar):$(ls $G/ai.djl.huggingface/tokenizers/0.38.0/*/tokenizers-0.38.0.jar):$(ls $G/ai.djl/api/0.38.0/*/api-0.38.0.jar):$(ls $G/com.google.code.gson/gson/2.13.2/*/gson-2.13.2.jar):$(ls $G/org.slf4j/slf4j-api/2.0.18/*/slf4j-api-2.0.18.jar)"
CP="$(cygpath -wp "$CP" 2>/dev/null || echo "$CP")"
PKG="$WORK/src/com/chaekcheck/aisearch/internal/vector"
mkdir -p "$PKG" "$WORK/classes" "$OUT"
cp "$REPO"/backend/src/main/java/com/chaekcheck/aisearch/internal/vector/{BookVectorFile,QuestionEncoder,OnnxQuestionEncoder}.java "$PKG/"
cp "$HERE/NearestBench.java" "$PKG/"
"$JDK/bin/javac" -nowarn -d "$WORK/classes" -cp "$CP" "$PKG"/*.java
SEP=";"; [ "$(uname -s)" = Linux ] && SEP=":"
RCP="$(cygpath -w "$WORK/classes" 2>/dev/null || echo "$WORK/classes")$SEP$CP"
# 1) question vectors (KURE-v1 int8 ONNX, as encoder=local)
"$JDK/bin/java" -cp "$RCP" com.chaekcheck.aisearch.internal.vector.NearestBench encode "$MODEL" "$OUT/questions.txt" "$OUT/queries.f32"
# 2) brute force: real 81,078 books, then tiled to 300k (x3.7) and 1M (x12.33)
"$JDK/bin/java" -Xmx1600m -cp "$RCP" com.chaekcheck.aisearch.internal.vector.NearestBench bench "$MODEL" "$OUT/queries.f32" "$OUT" 5 3.7 12.334 | tee "$OUT/brute.txt"
