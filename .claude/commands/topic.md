Research a specific topic using Gemini CLI. Supports codebase tracing and web research.

Usage:
- `/topic material reservation flow` — traces the flow in the codebase
- `/topic --web Spring Boot event sourcing best practices` — researches from the web

The user's input: $ARGUMENTS

Execute the following steps:

1. Parse the arguments:
   - If starts with `--web`, extract the topic after the flag and run: `bash .claude/scripts/gemini-research.sh --topic-web "<topic>"`
   - Otherwise, run: `bash .claude/scripts/gemini-research.sh --topic "$ARGUMENTS"`
2. Wait for completion (1-3 minutes)
3. Read the generated report from `docs/gemini-research/` (latest topic file)
4. Present the findings clearly
5. Ask the user what they'd like to do next (fix issues, start implementation, explore further)