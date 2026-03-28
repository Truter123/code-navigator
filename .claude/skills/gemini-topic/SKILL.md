---
name: gemini-topic
description: Use when the user wants to research a specific topic, flow, or feature using Gemini CLI. Supports codebase tracing and web research. Triggered by /topic command or when user asks to trace, investigate, or understand a domain concept, feature flow, library, or technology.
---

# Gemini Topic Research

Offloads topic-focused research to Gemini CLI. Two modes:

- **Codebase mode** (default): Traces a feature/flow across the entire repo
- **Web mode** (`--web`): Researches external topics (libraries, patterns, technologies)

## Invocation

Via `/topic` slash command:
```
/topic material reservation flow
/topic --web Spring Boot event sourcing best practices
/topic --web Tailwind CSS data table patterns
```

Or directly:
```bash
bash .claude/scripts/gemini-research.sh --topic "material reservation flow"
bash .claude/scripts/gemini-research.sh --topic-web "Spring Boot event sourcing best practices"
```

## Files

- `.claude/scripts/gemini-research.sh` — Core script (`--topic` and `--topic-web` modes)
- `.claude/skills/gemini-topic/prompts/topic-codebase.md` — Codebase research prompt
- `.claude/skills/gemini-topic/prompts/topic-web.md` — Web research prompt
- `.claude/commands/topic.md` — Slash command