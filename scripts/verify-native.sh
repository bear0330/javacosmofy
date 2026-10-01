#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BIN=${1:-"$ROOT/dist/javacosmofy.com"}
SUPER=${JAVACOSMOFY_SUPERCONFIGURE:-"$ROOT/../superconfigure"}
JAVAC=${JAVACOSMOFY_JAVAC:-"$SUPER/build-tools/openjdk25-boot/bin/javac"}
JAR=${JAVACOSMOFY_JAR:-"$(dirname "$JAVAC")/jar"}
TMP=$(mktemp -d "${TMPDIR:-/tmp}/javacosmofy-verify.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

test -x "$BIN" || { echo "missing javacosmofy APE: $BIN" >&2; exit 1; }
"$BIN" --version | grep -q '^javacosmofy '
mkdir -p "$TMP/hello"
"$JAVAC" -d "$TMP/hello" "$ROOT/examples/hello/src/example/Hello.java"
"$BIN" bundle "$TMP/hello" --main example.Hello --output "$TMP/hello.com"
[ "$("$TMP/hello.com" 'arg with spaces')" = 'Hello from embedded Java: arg with spaces' ]
"$JAR" --create --file "$TMP/hello.jar" --main-class example.Hello -C "$TMP/hello" .
"$BIN" bundle "$TMP/hello.jar" --jar --output "$TMP/hello-jar.com"
[ "$("$TMP/hello-jar.com" 'jar argument')" = 'Hello from embedded Java: jar argument' ]
"$BIN" bundle "$ROOT/.build/classes" --main dev.javacosmofy.Main --output "$TMP/self.com"
"$TMP/self.com" --version | grep -q '^javacosmofy '
actual=$(unzip -p "$TMP/hello.com" .args)
expected='-cp
/zip/app/classes
example.Hello
...'
[ "$actual" = "$expected" ]
jar_actual=$(unzip -p "$TMP/hello-jar.com" .args)
jar_expected='-jar
/zip/app/hello.jar
...'
[ "$jar_actual" = "$jar_expected" ]
if [ -f "$ROOT/modules/jdk25-cosmo.zip" ]; then
  mkdir -p "$TMP/modules"
  "$JAVAC" -d "$TMP/modules" "$ROOT/tests/Modules.java"
  "$BIN" bundle "$TMP/modules" --main Modules --modules java.desktop --output "$TMP/modules.com"
  [ "$("$TMP/modules.com")" = 'name' ]
  unzip -l "$TMP/modules.com" | grep -q 'modules/java.desktop/module-info.class'

  if unzip -l "$TMP/modules.com" | grep -q 'modules/java.desktop/_the\.'; then
    echo 'FAIL: module build metadata was bundled' >&2
    exit 1
  fi

  echo 'PASS: matching optional module repository'
else
  echo 'SKIP: optional module repository is not installed'
fi
echo 'PASS: native .args Java runtime, example, and self-bundling'
