#!/usr/bin/env bash
# Test: Skills Loading
# Verifies that the skills are correctly installed in the test environment
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Test: Skills Loading ==="

# Source setup to create isolated environment
source "$SCRIPT_DIR/setup.sh"

# Trap to cleanup on exit
trap cleanup_test_env EXIT

# Test 1: Verify skills directory is populated
echo "Test 1: Checking skills directory..."
skill_count=$(find "$HOME/.config/opencode/superpowers/skills" -name "SKILL.md" | wc -l)
if [ "$skill_count" -gt 0 ]; then
    echo "  [PASS] Found $skill_count skills installed"
else
    echo "  [FAIL] No skills found in installed location"
    exit 1
fi

# Test 2: Check using-superpowers skill exists (critical for bootstrap)
echo "Test 2: Checking using-superpowers skill (required for bootstrap)..."
if [ -f "$HOME/.config/opencode/superpowers/skills/using-superpowers/SKILL.md" ]; then
    echo "  [PASS] using-superpowers skill exists"
else
    echo "  [FAIL] using-superpowers skill not found (required for bootstrap)"
    exit 1
fi

# Test 3: Check brainstorming skill exists
echo "Test 3: Checking brainstorming skill..."
if [ -f "$HOME/.config/opencode/superpowers/skills/brainstorming/SKILL.md" ]; then
    echo "  [PASS] brainstorming skill exists"
else
    echo "  [FAIL] brainstorming skill not found"
    exit 1
fi

# Test 4: Verify personal test skill was created by setup
echo "Test 4: Checking test fixtures..."
if [ -f "$HOME/.config/opencode/skills/personal-test/SKILL.md" ]; then
    echo "  [PASS] Personal test skill fixture created"
else
    echo "  [FAIL] Personal test skill fixture not found"
    exit 1
fi

# Test 5: Verify project test skill was created by setup
echo "Test 5: Checking project test fixture..."
if [ -f "$TEST_HOME/test-project/.opencode/skills/project-test/SKILL.md" ]; then
    echo "  [PASS] Project test skill fixture created"
else
    echo "  [FAIL] Project test skill fixture not found"
    exit 1
fi

echo ""
echo "=== All skills loading tests passed ==="
