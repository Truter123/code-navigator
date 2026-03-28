#!/usr/bin/env bash
# Gemini Research — runs codebase analysis via Gemini CLI
# Usage: gemini-research.sh --auto | --deep | --topic "query" | --topic-web "query"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROMPTS_DIR_RESEARCH="${SCRIPT_DIR}/../skills/gemini-research/prompts"
PROMPTS_DIR_TOPIC="${SCRIPT_DIR}/../skills/gemini-topic/prompts"

# Check if gemini is installed
if ! command -v gemini &>/dev/null; then
    exit 0
fi

cd "$PROJECT_ROOT"

run_auto() {
    # Collect git context with size caps
    local git_status git_log git_diff
    git_status=$(git status --short 2>/dev/null || true)
    git_log=$(git log --oneline --since=1.day 2>/dev/null | head -30 || true)
    git_diff=$(git diff 2>/dev/null | head -500 || true)

    # Skip if nothing changed
    if [[ -z "$git_status" && -z "$git_log" && -z "$git_diff" ]]; then
        exit 0
    fi

    # Read prompt template and append git context via concatenation
    # (avoids bash ${//} substitution bugs with / \ & in git diff)
    local prompt_template
    prompt_template=$(cat "${PROMPTS_DIR_RESEARCH}/auto-scan.md" 2>/dev/null || exit 0)

    local prompt
    prompt="${prompt_template}

## Git Context

### Branch Status
${git_status}

### Recent Commits (last 24h)
${git_log}

### Current Diff
${git_diff}"

    # Run Gemini in headless mode, text output, no tools needed
    gemini -p "$prompt" -o text 2>/dev/null || true
}

run_deep() {
    local report_dir="${PROJECT_ROOT}/docs/gemini-research"
    local today
    today=$(date +%Y-%m-%d)
    local report_file="${report_dir}/${today}-analysis.md"

    mkdir -p "$report_dir"

    # Read prompt template
    local prompt
    prompt=$(cat "${PROMPTS_DIR_RESEARCH}/deep-audit.md" 2>/dev/null)
    if [[ -z "$prompt" ]]; then
        echo "ERROR: Could not read deep-audit.md prompt template"
        exit 1
    fi

    echo "Running deep Gemini audit... (this takes 1-3 minutes)"

    # Run Gemini with tool access (yolo mode) for full repo scan
    local start_time
    start_time=$(date +%s)

    local output
    output=$(gemini -p "$prompt" -o text --approval-mode yolo 2>/dev/null) || {
        echo "ERROR: Gemini CLI failed"
        exit 1
    }

    local end_time duration
    end_time=$(date +%s)
    duration=$(( end_time - start_time ))

    # Write report with header
    {
        echo "# Gemini Research Report — ${today}"
        echo ""
        echo "**Repository:** next-level (NLP Manufacturing System)"
        echo "**Mode:** Deep Audit"
        echo "**Duration:** ${duration}s"
        echo ""
        echo "---"
        echo ""
        echo "$output"
    } > "$report_file"

    # Clean up reports older than 7 days
    find "$report_dir" -name "*.md" -mtime +7 -delete 2>/dev/null || true

    echo "Report written to: ${report_file}"
    echo "Duration: ${duration}s"
}

run_topic() {
    local topic="$1"
    local prompt_file="$2"
    local slug

    # Create URL-safe slug from topic
    slug=$(echo "$topic" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/--*/-/g' | sed 's/^-//;s/-$//' | cut -c1-50)

    local report_dir="${PROJECT_ROOT}/docs/gemini-research"
    local today
    today=$(date +%Y-%m-%d)
    local report_file="${report_dir}/${today}-topic-${slug}.md"

    mkdir -p "$report_dir"

    # Read prompt template and inject topic
    local prompt_template
    prompt_template=$(cat "$prompt_file" 2>/dev/null)
    if [[ -z "$prompt_template" ]]; then
        echo "ERROR: Could not read prompt template: $prompt_file"
        exit 1
    fi

    local prompt="${prompt_template//\{\{TOPIC\}\}/$topic}"

    echo "Researching topic: ${topic}"
    echo "This takes 1-3 minutes..."

    local start_time
    start_time=$(date +%s)

    local output
    output=$(gemini -p "$prompt" -o text --approval-mode yolo 2>/dev/null) || {
        echo "ERROR: Gemini CLI failed"
        exit 1
    }

    local end_time duration
    end_time=$(date +%s)
    duration=$(( end_time - start_time ))

    # Write report
    {
        echo "# Gemini Topic Research — ${topic}"
        echo ""
        echo "**Date:** ${today}"
        echo "**Mode:** $(basename "$prompt_file" .md)"
        echo "**Duration:** ${duration}s"
        echo ""
        echo "---"
        echo ""
        echo "$output"
    } > "$report_file"

    # Clean up reports older than 7 days
    find "$report_dir" -name "*-topic-*.md" -mtime +7 -delete 2>/dev/null || true

    echo "Report written to: ${report_file}"
    echo "Duration: ${duration}s"
}

# Parse arguments
case "${1:-}" in
    --auto)
        run_auto
        ;;
    --deep)
        run_deep
        ;;
    --topic)
        [[ -z "${2:-}" ]] && { echo "Usage: gemini-research.sh --topic \"your topic\""; exit 1; }
        run_topic "$2" "${PROMPTS_DIR_TOPIC}/topic-codebase.md"
        ;;
    --topic-web)
        [[ -z "${2:-}" ]] && { echo "Usage: gemini-research.sh --topic-web \"your topic\""; exit 1; }
        run_topic "$2" "${PROMPTS_DIR_TOPIC}/topic-web.md"
        ;;
    *)
        echo "Usage: gemini-research.sh --auto | --deep | --topic \"query\" | --topic-web \"query\""
        exit 1
        ;;
esac