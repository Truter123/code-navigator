#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/domain-navigator.jar"

if [ ! -f "$JAR" ]; then
  echo "domain-navigator.jar not found. Run build-all.sh first."
  exit 1
fi

java -jar "$JAR" "$@"
