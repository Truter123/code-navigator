Show a developer dashboard with today's activity and knowledge base overview.

Execute the following steps:

1. **Activity Summary** — Call the `activity_today` MCP tool to get today's commits and notes across all tracked repos
2. **Recent Knowledge** — Call the `kb_list` MCP tool to show all knowledge base entries
3. **Repo Status** — Call the `activity_repos` MCP tool to show tracked repositories and their scan status

Present the results as a clean dashboard:

## Today's Activity
- Group commits by repo, show short hash + message
- Show manual notes separately
- Include total counts (commits, notes, repos active)

## Knowledge Base
- Group entries by category (PATTERN, DEFINITION, TIP, GOTCHA, REFERENCE)
- Show entry count per category
- List recent entries (by updated date)

## Tracked Repos
- List each repo with last scan time

End with: "Use `kb_search <query>` to search knowledge, `activity_range <from> <to>` for historical activity, or `kb_add` to add new knowledge."