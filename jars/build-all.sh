#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MCP_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Building all MCP JARs ==="

# code-navigator
echo ""
echo "Building code-navigator..."
cd "$MCP_DIR/code-navigator"
./gradlew shadowJar -q
cp build/libs/code-navigator-0.1.0.jar "$SCRIPT_DIR/code-navigator.jar"
echo "  -> code-navigator.jar"

# domain-navigator
echo ""
echo "Building domain-navigator..."
cd "$MCP_DIR/domain-navigator"
./gradlew shadowJar -q
cp build/libs/domain-navigator-0.1.0.jar "$SCRIPT_DIR/domain-navigator.jar"
echo "  -> domain-navigator.jar"

# agent-memory
echo ""
echo "Building agent-memory..."
cd "$MCP_DIR/agent-memory"
./gradlew shadowJar -q
cp build/libs/agent-memory-0.1.0.jar "$SCRIPT_DIR/agent-memory.jar"
echo "  -> agent-memory.jar"

echo ""
echo "=== All JARs built ==="
ls -lh "$SCRIPT_DIR"/*.jar
