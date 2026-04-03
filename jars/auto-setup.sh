#!/bin/bash
# Auto-setup code-navigator for the current project.
# Intended as a Claude Code SessionStart hook.
# - Adds code-navigator to .mcp.json if missing (preserves existing servers)
# - Auto-indexes if Java sources exist but no index
# - Does nothing for non-Java projects (exits silently)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/code-navigator.jar"
PROJECT="$(pwd)"
DB="$PROJECT/navigators/code/code-navigator.db"
MCP_JSON="$PROJECT/.mcp.json"

# Skip if no JAR
[ -f "$JAR" ] || exit 0

# Skip if no Java sources (not a Java project)
if ! find "$PROJECT" -maxdepth 4 -name "*.java" -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/node_modules/*" | head -1 | grep -q .; then
  exit 0
fi

# Add code-navigator to .mcp.json if not configured
if [ -f "$MCP_JSON" ]; then
  if ! grep -q '"code-navigator"' "$MCP_JSON" 2>/dev/null; then
    # Inject code-navigator server into existing .mcp.json
    python3 -c "
import json, sys
with open('$MCP_JSON') as f:
    data = json.load(f)
data.setdefault('mcpServers', {})['code-navigator'] = {
    'command': 'java',
    'args': ['-jar', '$JAR', 'serve'],
    'env': {'CODE_NAVIGATOR_PROJECT': '$PROJECT'}
}
with open('$MCP_JSON', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
" 2>/dev/null && echo "Added code-navigator to .mcp.json" >&2
  fi
else
  # Create new .mcp.json
  cat > "$MCP_JSON" <<MCPEOF
{
  "mcpServers": {
    "code-navigator": {
      "command": "java",
      "args": ["-jar", "$JAR", "serve"],
      "env": {
        "CODE_NAVIGATOR_PROJECT": "$PROJECT"
      }
    }
  }
}
MCPEOF
  echo "Created .mcp.json with code-navigator" >&2
fi

# Auto-index if no index exists
if [ ! -f "$DB" ]; then
  echo "Auto-indexing $PROJECT..." >&2
  java -jar "$JAR" init "$PROJECT" >&2 2>&1 || true
fi
