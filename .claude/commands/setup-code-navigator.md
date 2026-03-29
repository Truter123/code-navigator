Set up code-navigator MCP for the current project. This configures the MCP server, permissions, auto-sync hooks, and CLAUDE.md instructions.

Execute these steps in order:

## Step 1: Configure MCP server in ~/.claude.json

Run: `bash .claude/scripts/setup-code-navigator.sh --mcp`

If output is `ALREADY_EXISTS`, tell the user MCP server is already configured globally.
If output is `ADDED`, confirm it was added.
If output is `FAILED`, warn the user and suggest manual setup.

## Step 2: Set up auto-allow permissions

Run: `bash .claude/scripts/setup-code-navigator.sh --permissions`

Confirm permissions were added to `.claude/settings.local.json`.

## Step 3: Ask about initializing the current project

Check if `navigators/code-navigator.db` exists in the current project directory.

If it does NOT exist, ask the user: "Do you want to initialize code-navigator for this project now? This will do a full index of the codebase."

If they say yes, run: `bash .claude/scripts/setup-code-navigator.sh --init`

If it already exists, tell the user the index already exists and they can run `sync` to update it.

## Step 4: Write auto-sync hook

Run: `bash .claude/scripts/setup-code-navigator.sh --hook`

If output is `ALREADY_EXISTS`, tell the user the hook is already configured.
If output is `ADDED`, confirm the PostToolUse hook was added to `.claude/settings.json`.

## Step 5: Write CLAUDE.md instructions

Ask the user: "Where should I write the code-navigator instructions — project CLAUDE.md or global ~/.claude/CLAUDE.md?"

Then append the following block to the chosen file (do NOT overwrite existing content):

```
## Code Navigator

This project uses code-navigator MCP for codebase navigation. Available tools:

- `cg_search` — Full-text search across all indexed symbols
- `cg_chain` — Trace CQRS/DDD chain from any symbol (controller→command→handler→aggregate→event→projection)
- `cg_impact` — Find blast radius of changes within N edges of a symbol
- `cg_context` — Given a task description, find all relevant files and connections
- `cg_overview` — Show full structure of an aggregate/service/controller
- `cg_map` — System-wide map of all aggregates/controllers with counts

When working on a task, use `cg_context` first to find relevant code, then `cg_chain` or `cg_impact` to understand dependencies before making changes.

The index auto-syncs after git commits. To manually re-index: `java -jar /home/kamil/Documents/Project/My/code-navigator/build/libs/code-navigator-0.1.0.jar sync .`
```

## Step 6: Summary

Print a summary of what was done and remind the user to restart Claude Code for the MCP server changes to take effect.
