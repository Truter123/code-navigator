#!/usr/bin/env bash
# Model Advisor PreToolUse Hook
# Detects workflow phase, recommends optimal Claude model via status line,
# and auto-injects model parameter for Task tool calls missing it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
DETECTOR="${SCRIPT_DIR}/model-advisor/detector.py"

# Read hook input from stdin
HOOK_INPUT=$(cat)

# Extract tool name from hook data
TOOL_NAME=$(echo "$HOOK_INPUT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('tool_name', ''))" 2>/dev/null || echo "")

# Run detector
if [ -f "$DETECTOR" ]; then
    RESULT=$(echo "$HOOK_INPUT" | python3 "$DETECTOR" 2>/dev/null || echo '{}')

    # Extract status line from result
    STATUS_LINE=$(echo "$RESULT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('status_line', ''))" 2>/dev/null || echo "")

    # Update status file for context-monitor.py to read
    if [ -n "$STATUS_LINE" ]; then
        SESSION_ID="${CLAUDE_SESSION_ID:-default}"
        echo "$STATUS_LINE" > "/tmp/woa-model-status-${SESSION_ID}"
    fi

    # For Task tool: check if model recommendation exists (means model was missing)
    if [ "$TOOL_NAME" = "Task" ]; then
        RECOMMENDED_MODEL=$(echo "$RESULT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('task_model_recommendation', ''))" 2>/dev/null || echo "")

        if [ -n "$RECOMMENDED_MODEL" ]; then
            # Write active agent info for context-monitor
            DESCRIPTION=$(echo "$HOOK_INPUT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('tool_input', {}).get('description', 'agent')[:30])" 2>/dev/null || echo "agent")
            SUBAGENT_TYPE=$(echo "$HOOK_INPUT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('tool_input', {}).get('subagent_type', ''))" 2>/dev/null || echo "")
            SESSION_ID="${CLAUDE_SESSION_ID:-default}"
            echo "${SUBAGENT_TYPE}|${RECOMMENDED_MODEL}|${DESCRIPTION}" > "/tmp/woa-active-agent-${SESSION_ID}"

            # Block and recommend model
            echo "⚠️ Task tool called without model parameter. Recommended model: ${RECOMMENDED_MODEL} (based on: subagent_type=${SUBAGENT_TYPE}, description='${DESCRIPTION}'). Please re-issue with model: \"${RECOMMENDED_MODEL}\"." >&2
            exit 2
        else
            # Model was specified - write active agent info
            SPECIFIED_MODEL=$(echo "$HOOK_INPUT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('tool_input', {}).get('model', ''))" 2>/dev/null || echo "")
            DESCRIPTION=$(echo "$HOOK_INPUT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('tool_input', {}).get('description', 'agent')[:30])" 2>/dev/null || echo "agent")
            SUBAGENT_TYPE=$(echo "$HOOK_INPUT" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('tool_input', {}).get('subagent_type', ''))" 2>/dev/null || echo "")
            SESSION_ID="${CLAUDE_SESSION_ID:-default}"
            echo "${SUBAGENT_TYPE}|${SPECIFIED_MODEL}|${DESCRIPTION}" > "/tmp/woa-active-agent-${SESSION_ID}"
        fi
    fi
fi

# Continue (non-blocking for non-Task tools, or Task with model specified)
cat <<EOF
{
  "continue": true
}
EOF
