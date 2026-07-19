#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MCP_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Building all MCP JARs ==="

# code-navigator
echo ""
echo "Building code-navigator..."
cd "$MCP_DIR"
./gradlew shadowJar -q
cp build/libs/code-navigator-0.1.0.jar "$SCRIPT_DIR/code-navigator.jar"
echo "  -> code-navigator.jar"

echo ""
echo "=== All JARs built ==="
ls -lh "$SCRIPT_DIR"/*.jar
