#!/usr/bin/env python3
"""
Model Advisor Phase Detector

Detects workflow phase from tool calls and recommends optimal Claude model.
Uses hybrid approach: env override > content keywords > tool patterns > session memory.
"""

import json
import os
import sys
import re
import fnmatch
from pathlib import Path
from typing import Optional

SCRIPT_DIR = Path(__file__).parent
PATTERNS_FILE = SCRIPT_DIR / "patterns.json"
KEYWORDS_FILE = SCRIPT_DIR / "keywords.json"

def get_session_file() -> Path:
    """Get session-specific phase memory file."""
    session_id = os.environ.get("CLAUDE_SESSION_ID", "default")
    return Path(f"/tmp/woa-workflow-phase-{session_id}")

def load_json(filepath: Path) -> dict:
    """Load JSON configuration file."""
    try:
        with open(filepath) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

def load_session_state() -> dict:
    """Load current session state from memory file."""
    session_file = get_session_file()
    if session_file.exists():
        try:
            with open(session_file) as f:
                return json.load(f)
        except (json.JSONDecodeError, FileNotFoundError):
            pass
    return {"phase": "research", "signal_count": 0, "pending_phase": None}

def save_session_state(state: dict) -> None:
    """Save session state to memory file."""
    session_file = get_session_file()
    with open(session_file, "w") as f:
        json.dump(state, f)

def check_env_override() -> Optional[str]:
    """Check for user environment override."""
    override = os.environ.get("WOA_WORKFLOW_PHASE", "").lower().strip()
    if override and override != "auto":
        return override
    return None

def detect_from_keywords(tool_input: str, keywords_config: dict) -> Optional[tuple[str, bool]]:
    """
    Detect phase from content keywords.
    Returns (phase, is_strong) or None.
    """
    tool_input_lower = tool_input.lower()

    # Check strong keywords first
    strong_keywords = keywords_config.get("strong_keywords", {})
    for phase, keywords in strong_keywords.items():
        for keyword in keywords:
            if keyword in tool_input_lower:
                return (phase, True)

    # Check weak keywords
    weak_keywords = keywords_config.get("weak_keywords", {})
    for phase, keywords in weak_keywords.items():
        for keyword in keywords:
            if keyword in tool_input_lower:
                return (phase, False)

    return None

def detect_from_tool_patterns(tool_name: str, tool_input: dict, patterns_config: dict) -> Optional[str]:
    """Detect phase from tool type and patterns."""
    tool_patterns = patterns_config.get("tool_patterns", {})

    # Extract relevant info from tool input
    file_path = tool_input.get("file_path", "") or tool_input.get("path", "") or ""
    command = tool_input.get("command", "") or ""
    agent_type = tool_input.get("subagent_type", "") or ""

    for phase, config in tool_patterns.items():
        # Check direct tool match
        if tool_name in config.get("tools", []):
            # For Edit/Write, check file patterns to distinguish impl vs testing
            if tool_name in ["Edit", "Write"] and file_path:
                test_patterns = tool_patterns.get("testing", {}).get("file_patterns", [])
                for pattern in test_patterns:
                    if fnmatch.fnmatch(file_path, pattern):
                        return "testing"

            # Check for refactoring (Edit without strong impl signals)
            if tool_name == "Edit" and phase == "implementation":
                # Will be refined by keyword detection
                pass

            return phase

        # Check tool prefix match
        for prefix in config.get("tool_prefixes", []):
            if tool_name.startswith(prefix):
                return phase

        # Check bash patterns
        if tool_name == "Bash" and command:
            for pattern in config.get("bash_patterns", []):
                if pattern in command:
                    return phase

        # Check agent types
        if tool_name == "Task" and agent_type:
            if agent_type in config.get("agent_types", []):
                return phase

    return None

def update_phase_with_debounce(detected_phase: str, is_strong_signal: bool,
                                state: dict, config: dict) -> str:
    """Apply debounce logic to phase transitions."""
    current_phase = state.get("phase", "research")
    debounce_count = config.get("debounce_count", 3)

    # Strong signals bypass debounce
    if is_strong_signal:
        state["phase"] = detected_phase
        state["signal_count"] = 0
        state["pending_phase"] = None
        save_session_state(state)
        return detected_phase

    # Same phase as current - no change needed
    if detected_phase == current_phase:
        state["pending_phase"] = None
        state["signal_count"] = 0
        save_session_state(state)
        return current_phase

    # Different phase - apply debounce
    if state.get("pending_phase") == detected_phase:
        state["signal_count"] = state.get("signal_count", 0) + 1
    else:
        state["pending_phase"] = detected_phase
        state["signal_count"] = 1

    # Check if debounce threshold reached
    if state["signal_count"] >= debounce_count:
        state["phase"] = detected_phase
        state["signal_count"] = 0
        state["pending_phase"] = None
        save_session_state(state)
        return detected_phase

    save_session_state(state)
    return current_phase

def format_status_line(phase: str, config: dict, is_override: bool = False) -> str:
    """Format the status line output."""
    model_mapping = config.get("model_mapping", {})
    phase_info = model_mapping.get(phase, model_mapping.get("research", {}))

    icon = phase_info.get("icon", "📊")
    label = phase_info.get("label", phase.title())
    full_name = phase_info.get("full_name", "Unknown")

    override_hint = " [override]" if is_override else ""

    return f"{icon} {label} | Model: {full_name}{override_hint}"

def recommend_model_for_task(tool_input: dict, keywords_config: dict) -> Optional[str]:
    """
    Recommend a Claude model for a Task tool call based on content analysis.
    Returns model name (haiku/sonnet/opus) or None if model already specified.
    """
    # If model already specified, no recommendation needed
    if tool_input.get("model"):
        return None

    prompt = (tool_input.get("prompt", "") or "").lower()
    description = (tool_input.get("description", "") or "").lower()
    subagent_type = (tool_input.get("subagent_type", "") or "").lower()
    combined = f"{prompt} {description} {subagent_type}"

    # Subagent type mappings
    type_to_model = {
        "explore": "haiku",
        "plan": "sonnet",
        "bash": "haiku",
    }
    if subagent_type in type_to_model:
        return type_to_model[subagent_type]

    # Content-based detection
    # Opus signals: architecture, complex reasoning, orchestration
    opus_keywords = [
        "architect", "system design", "trade-off", "evaluate approach",
        "decompose complex", "orchestrat", "cross-cutting"
    ]
    for kw in opus_keywords:
        if kw in combined:
            return "opus"

    # Haiku signals: review, search, simple/mechanical, checklist
    haiku_keywords = [
        "review", "spec compliance", "code quality", "checklist",
        "find", "search", "grep", "locate", "simple", "delete",
        "remove", "rename", "verify", "check"
    ]
    for kw in haiku_keywords:
        if kw in combined:
            return "haiku"

    # Sonnet is the default for implementation/complex work
    return "sonnet"


def detect_phase(tool_name: str, tool_input: dict) -> dict:
    """
    Main detection function.
    Returns dict with phase, model info, status line, and task_model_recommendation.
    """
    patterns_config = load_json(PATTERNS_FILE)
    keywords_config = load_json(KEYWORDS_FILE)
    state = load_session_state()

    # Priority 1: Environment override
    override = check_env_override()
    if override:
        model_mapping = patterns_config.get("model_mapping", {})
        if override in model_mapping:
            result = {
                "phase": override,
                "model": model_mapping[override],
                "status_line": format_status_line(override, patterns_config, is_override=True),
                "source": "override"
            }
            # Still check Task model for overrides
            if tool_name == "Task":
                rec = recommend_model_for_task(tool_input, keywords_config)
                if rec:
                    result["task_model_recommendation"] = rec
            return result

    # Convert tool_input to string for keyword analysis
    tool_input_str = json.dumps(tool_input) if isinstance(tool_input, dict) else str(tool_input)

    # Priority 2: Content keywords
    keyword_result = detect_from_keywords(tool_input_str, keywords_config)
    is_strong_signal = False
    detected_phase = None

    if keyword_result:
        detected_phase, is_strong_signal = keyword_result

    # Priority 3: Tool patterns (if no keyword match or weak signal)
    if not detected_phase:
        detected_phase = detect_from_tool_patterns(tool_name, tool_input, patterns_config)

    # Priority 4: Session memory (fallback)
    if not detected_phase:
        detected_phase = state.get("phase", patterns_config.get("default_phase", "research"))

    # Apply debounce
    final_phase = update_phase_with_debounce(detected_phase, is_strong_signal, state, patterns_config)

    model_mapping = patterns_config.get("model_mapping", {})
    result = {
        "phase": final_phase,
        "model": model_mapping.get(final_phase, {}),
        "status_line": format_status_line(final_phase, patterns_config),
        "source": "keyword" if keyword_result else "pattern" if detected_phase else "memory"
    }

    # Task tool: recommend model if missing
    if tool_name == "Task":
        rec = recommend_model_for_task(tool_input, keywords_config)
        if rec:
            result["task_model_recommendation"] = rec

    return result

def main():
    """Entry point - reads tool info from stdin or args."""
    # Read hook input from stdin (Claude Code passes JSON)
    try:
        hook_input = json.load(sys.stdin)
    except json.JSONDecodeError:
        hook_input = {}

    tool_name = hook_input.get("tool_name", os.environ.get("TOOL_NAME", ""))
    tool_input = hook_input.get("tool_input", {})

    result = detect_phase(tool_name, tool_input)

    # Output for status line (stderr for display, stdout for hook response)
    print(result["status_line"], file=sys.stderr)

    # Output JSON result
    print(json.dumps(result))

if __name__ == "__main__":
    main()
