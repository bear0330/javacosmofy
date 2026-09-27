#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEFAULT_SUPER=${JAVACOSMOFY_SUPERCONFIGURE:-"$ROOT/../superconfigure"}
RUNTIME=${1:-"$DEFAULT_SUPER/results/bin/java.com"}
OUT=${2:-"$ROOT/dist/javacosmofy.com"}
RUNTIME_ROOT=$(CDPATH= cd -- "$(dirname -- "$RUNTIME")/../.." 2>/dev/null && pwd || true)
JAVAC=${JAVACOSMOFY_JAVAC:-"$RUNTIME_ROOT/build-tools/openjdk25-boot/bin/javac"}
CLASSES="$ROOT/.build/classes"

test -x "$RUNTIME" || { echo "missing Java APE: $RUNTIME" >&2; exit 1; }
test -x "$JAVAC" || { echo "missing bootstrap javac: $JAVAC" >&2; exit 1; }
mkdir -p "$CLASSES" "$(dirname -- "$OUT")"
"$JAVAC" -d "$CLASSES" "$ROOT/src/dev/javacosmofy/Main.java"
"$RUNTIME" -cp "$CLASSES" dev.javacosmofy.Main bundle "$CLASSES" \
  --main dev.javacosmofy.Main --runtime "$RUNTIME" --output "$OUT"
printf 'Output: %s\n' "$OUT"
