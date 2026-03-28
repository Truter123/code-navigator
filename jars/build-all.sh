#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MCP_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Building all MCP JARs ==="

# code-graph
echo ""
echo "Building code-graph..."
cd "$MCP_DIR/code-graph"
./gradlew shadowJar -q
cp build/libs/code-graph-0.1.0.jar "$SCRIPT_DIR/code-graph.jar"
echo "  -> code-graph.jar"

# domain-mcp
echo ""
echo "Building domain-mcp..."
cd "$MCP_DIR/domain-mcp"
./gradlew shadowJar -q
cp build/libs/domain-mcp-0.1.0.jar "$SCRIPT_DIR/domain-mcp.jar"
echo "  -> domain-mcp.jar"

echo ""
echo "=== All JARs built ==="
ls -lh "$SCRIPT_DIR"/*.jar
