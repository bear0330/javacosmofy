#!/bin/sh
set -eu

[ "$#" -eq 1 ] || { echo "Usage: $0 /path/to/java-modules.zip" >&2; exit 2; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")
test -f "$SOURCE" || { echo "missing module repository: $SOURCE" >&2; exit 1; }
unzip -p "$SOURCE" .__javacosmofy__/module-repository.properties >/dev/null
mkdir -p "$ROOT/modules"
cp "$SOURCE" "$ROOT/modules/jdk25-cosmo.zip"
printf 'Installed module repository: %s\n' "$ROOT/modules/jdk25-cosmo.zip"
