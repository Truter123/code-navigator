#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/code-navigator.jar"

if [ -z "$1" ]; then
    echo "Usage: index-project.sh <project-path>"
    echo ""
    echo "Indexes a project and generates .ai-briefing/ files."
    echo ""
    echo "Examples:"
    echo "  $0 /home/kamil/Documents/Project/My/woa"
    echo "  $0 /home/kamil/Documents/Project/My/next-level"
    exit 1
fi

PROJECT="$1"

if [ ! -d "$PROJECT" ]; then
    echo "Error: Directory not found: $PROJECT"
    exit 1
fi

echo "=== Indexing $PROJECT ==="
java -jar "$JAR" init "$PROJECT"

echo ""
echo "=== Generating briefing ==="
java -jar "$JAR" briefing "$PROJECT"

echo ""
echo "=== Done ==="
echo "Briefing files:"
ls -la "$PROJECT/.ai-briefing/" 2>/dev/null || echo "  (no briefing files generated)"
