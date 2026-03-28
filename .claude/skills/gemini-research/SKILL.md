---
name: gemini-research
description: Offloads codebase research to Gemini CLI to reduce Claude Code token usage. Auto mode runs at session start; deep mode via /research command.
---

# Gemini Research

## Overview

This skill offloads codebase analysis to Gemini CLI (free) so Claude Code starts every session with pre-built context and spends zero tokens on exploration.

## Two Modes

### Auto Mode (Automatic)

Runs automatically at session start via the SessionStart hook. Analyzes recent changes (<15s):
- Git history (last 24h), current diff, branch status
- Dead code in changed files
- Duplicate code in changed files
- Pattern violations (CQRS/DDD)
- Missing test coverage

Output is injected into your context. Use it — don't re-explore what Gemini already found.

### Deep Mode (Manual)

Invoke via `/research` slash command. Full repository audit (1-3 min):
- All 6 analyses across entire BE+FE codebase
- Writes report to `docs/gemini-research/YYYY-MM-DD-analysis.md`
- Categorized findings: critical > warning > info

## When to Use Deep Mode

- Before major refactoring — find all duplicates and dead code first
- Periodic cleanup — run weekly to catch drift
- After merging large PRs — verify pattern consistency
- When Claude Code context seems stale mid-session — `/research` refreshes

## How It Works

1. **Auto:** `session-start.sh` → `gemini-research.sh --auto` → Gemini CLI headless → context injection
2. **Deep:** `/research` → Claude runs `gemini-research.sh --deep` → Gemini CLI with tools → report file

## Files

- `.claude/scripts/gemini-research.sh` — Core script (both modes)
- `.claude/skills/gemini-research/prompts/auto-scan.md` — Auto mode prompt
- `.claude/skills/gemini-research/prompts/deep-audit.md` — Deep mode prompt
- `.claude/commands/research.md` — Slash command