#!/usr/bin/env bash
# setup-code-navigator.sh — configure code-navigator MCP for the current project
# Usage: setup-code-navigator.sh [--mcp] [--permissions] [--hook] [--init]

set -euo pipefail

CODE_GRAPH_JAR="/home/kamil/Documents/Project/My/code-navigator/build/libs/code-navigator-0.1.0.jar"
CLAUDE_JSON="$HOME/.claude.json"
PROJECT_DIR="$(pwd)"

action="${1:-}"

case "$action" in
  --mcp)
    # Add code-navigator MCP server to ~/.claude.json under the user's home entry
    if python3 -c "
import json, sys
with open('$CLAUDE_JSON', 'r') as f:
    data = json.load(f)
home = data.get('projects', {}).get('$HOME', {})
servers = home.get('mcpServers', {})
if 'code-navigator' in servers:
    print('ALREADY_EXISTS')
    sys.exit(0)
servers['code-navigator'] = {
    'type': 'stdio',
    'command': 'java',
    'args': ['-jar', '$CODE_GRAPH_JAR', 'serve'],
    'env': {}
}
home['mcpServers'] = servers
data.setdefault('projects', {})['$HOME'] = home
with open('$CLAUDE_JSON', 'w') as f:
    json.dump(data, f, indent=2)
print('ADDED')
" 2>/dev/null; then
      :
    else
      echo "FAILED"
    fi
    ;;

  --permissions)
    # Add mcp__code-navigator__* to project settings.local.json permissions
    SETTINGS_LOCAL="$PROJECT_DIR/.claude/settings.local.json"
    mkdir -p "$PROJECT_DIR/.claude"
    if [ -f "$SETTINGS_LOCAL" ]; then
      python3 -c "
import json
with open('$SETTINGS_LOCAL', 'r') as f:
    data = json.load(f)
allow = data.setdefault('permissions', {}).setdefault('allow', [])
if 'mcp__code-navigator__*' not in allow:
    allow.append('mcp__code-navigator__*')
enabled = data.setdefault('enabledMcpjsonServers', [])
if 'code-navigator' not in enabled:
    enabled.append('code-navigator')
with open('$SETTINGS_LOCAL', 'w') as f:
    json.dump(data, f, indent=2)
print('UPDATED')
"
    else
      cat > "$SETTINGS_LOCAL" << 'EOJSON'
{
  "permissions": {
    "allow": [
      "mcp__code-navigator__*"
    ]
  },
  "enabledMcpjsonServers": [
    "code-navigator"
  ]
}
EOJSON
      echo "CREATED"
    fi
    ;;

  --hook)
    # Add PostToolUse auto-sync hook to project .claude/settings.json
    SETTINGS="$PROJECT_DIR/.claude/settings.json"
    mkdir -p "$PROJECT_DIR/.claude"
    python3 -c "
import json, os
path = '$SETTINGS'
data = {}
if os.path.exists(path):
    with open(path, 'r') as f:
        data = json.load(f)
hooks = data.setdefault('hooks', {})
post_hooks = hooks.setdefault('PostToolUse', [])
# Check if already exists
for h in post_hooks:
    if h.get('matcher') == 'Bash':
        for cmd in h.get('hooks', []):
            if 'code-navigator' in cmd.get('command', ''):
                print('ALREADY_EXISTS')
                exit(0)
post_hooks.append({
    'matcher': 'Bash',
    'hooks': [{
        'type': 'command',
        'if': 'Bash(git commit:*)',
        'command': 'java -jar $CODE_GRAPH_JAR sync \"\$(pwd)\" 2>/dev/null &',
        'async': True,
        'statusMessage': 'Syncing code-navigator index...'
    }]
})
with open(path, 'w') as f:
    json.dump(data, f, indent=2)
print('ADDED')
"
    ;;

  --init)
    # Initialize code-navigator index for the current project
    if [ ! -f "$CODE_GRAPH_JAR" ]; then
      echo "JAR_NOT_FOUND"
      exit 1
    fi
    java -jar "$CODE_GRAPH_JAR" init "$PROJECT_DIR"
    echo "INIT_COMPLETE"
    ;;

  *)
    echo "Usage: setup-code-navigator.sh [--mcp|--permissions|--hook|--init]"
    exit 1
    ;;
esac
