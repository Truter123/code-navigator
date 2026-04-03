#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/code-navigator.jar"

if [ ! -f "$JAR" ]; then
  echo "code-navigator.jar not found. Run build-all.sh first." >&2
  exit 1
fi

# Auto-detect project: use env var, or fall back to CWD
PROJECT="${CODE_NAVIGATOR_PROJECT:-$(pwd)}"
DB="$PROJECT/navigators/code/code-navigator.db"

# Auto-index if no index exists and project has Java sources
if [ ! -f "$DB" ] && find "$PROJECT" -name "*.java" -not -path "*/build/*" -not -path "*/.gradle/*" | head -1 | grep -q .; then
  echo "Auto-indexing project: $PROJECT" >&2
  java -jar "$JAR" init "$PROJECT" >&2
fi

# Serve (always — even without index, tools like cg_status will report empty)
export CODE_NAVIGATOR_PROJECT="$PROJECT"
exec java -jar "$JAR" serve
