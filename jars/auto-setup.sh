#!/bin/bash
# Auto-setup MCP servers for the current project.
# Intended as a Claude Code SessionStart hook.
# - Adds code-navigator if Java sources found + auto-indexes
# - Adds Angular MCP for each angular.json found in subdirectories

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/code-navigator.jar"
PROJECT="$(pwd)"
DB="$PROJECT/navigators/code/code-navigator.db"
MCP_JSON="$PROJECT/.mcp.json"

ensure_mcp_json() {
  if [ ! -f "$MCP_JSON" ]; then
    echo '{"mcpServers":{}}' > "$MCP_JSON"
  fi
}

add_server() {
  local name="$1"
  local json_fragment="$2"
  if grep -q "\"$name\"" "$MCP_JSON" 2>/dev/null; then
    return 0
  fi
  ensure_mcp_json
  python3 -c "
import json
with open('$MCP_JSON') as f:
    data = json.load(f)
data.setdefault('mcpServers', {})['$name'] = json.loads('''$json_fragment''')
with open('$MCP_JSON', 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')
" 2>/dev/null && echo "Added $name to .mcp.json" >&2
}

# --- Code Navigator (Java projects) ---
if [ -f "$JAR" ]; then
  if find "$PROJECT" -maxdepth 4 -name "*.java" -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/node_modules/*" 2>/dev/null | head -1 | grep -q .; then
    add_server "code-navigator" "{
      \"command\": \"java\",
      \"args\": [\"-jar\", \"$JAR\", \"serve\"],
      \"env\": {\"CODE_NAVIGATOR_PROJECT\": \"$PROJECT\"}
    }"

    # Auto-index if no index exists
    if [ ! -f "$DB" ]; then
      echo "Auto-indexing $PROJECT..." >&2
      java -jar "$JAR" init "$PROJECT" >&2 2>&1 || true
    fi
  fi
fi

# --- Angular MCP (Angular projects) ---
# Only add for real app frontends, skip embedded/internal dashboards
while IFS= read -r angular_json; do
  ng_dir=$(dirname "$angular_json")

  # Skip if this is an internal/embedded UI
  echo "$ng_dir" | grep -qE "(src/main/resources|/angular$)" && continue

  # Derive a server name from the directory
  if [ "$ng_dir" = "$PROJECT" ]; then
    server_name="angular"
  else
    server_name="angular-$(basename "$ng_dir")"
  fi

  add_server "$server_name" "{
    \"command\": \"npx\",
    \"args\": [\"-p\", \"@angular/cli\", \"ng\", \"mcp\"],
    \"cwd\": \"$ng_dir\"
  }"
done < <(find "$PROJECT" -maxdepth 3 -name "angular.json" -not -path "*/node_modules/*" -not -path "*/build/*" -not -path "*/.angular/*" 2>/dev/null)
