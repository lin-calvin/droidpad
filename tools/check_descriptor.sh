#!/usr/bin/env bash
# Regenerates tools/descriptor.hex from HidDescriptor.java and validates it.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$DIR/../src/dev/calvin/droidpad/HidDescriptor.java"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/dev/calvin/droidpad"
cp "$SRC" "$TMP/dev/calvin/droidpad/"
cp "$DIR/Dump.java" "$TMP/"
( cd "$TMP" && "$JAVA_HOME/bin/javac" -nowarn -d . dev/calvin/droidpad/HidDescriptor.java Dump.java )
HEX="$("$JAVA_HOME/bin/java" -cp "$TMP" Dump | tr -d ' ')"
echo "$HEX" > "$DIR/descriptor.hex"
python3 "$DIR/parse_hid.py" "$HEX" >/dev/null
echo "descriptor: $((${#HEX}/2)) bytes; written to $DIR/descriptor.hex"
