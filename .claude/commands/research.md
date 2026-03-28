Run a deep Gemini CLI analysis of the entire repository.

Execute the following steps:
1. Run the deep research script: `bash .claude/scripts/gemini-research.sh --deep`
2. Wait for completion (this takes 1-3 minutes)
3. Read the generated report from `docs/gemini-research/` (latest file)
4. Present a summary of findings organized by severity (critical > warning > info)
5. For each finding, suggest whether to fix now or defer
6. Ask the user which findings to act on
