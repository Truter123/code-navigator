You are performing a deep audit of a manufacturing system codebase (Spring Boot + Angular, DDD/Event Sourcing/CQRS).

Use your tools (grep_search, glob, read_file) extensively to analyze the ENTIRE repository.

Read these files first for project context:
- GEMINI.md (root)
- nlp-be/GEMINI.md (backend architecture)
- nlp-fe/GEMINI.md (frontend architecture)
- nlp-be/ARCHITECTURE-RULES.md (DDD/CQRS rules)

## Analyses to Perform

### 1. Git History (last 7 days)
- Run: `git log --oneline --since=7.days --all`
- Identify: active areas of development, potential conflicts, patterns in recent work

### 2. Dead Code Detection
- Scan ALL Java files for unused imports (import statements where the imported class is not referenced)
- Scan ALL TypeScript files for unused imports
- Find orphaned files: source files with no imports/references from other files
- Find unreachable methods: public methods with zero callers outside their own class
- IMPORTANT: In event-sourced systems, keep event handlers even if no current code emits those events (needed for replay)

### 3. Duplicate Code Detection
- Find similar code blocks (>10 lines) across:
  - Backend controllers (nlp-be/src/main/java/**/presentation/)
  - Backend handlers (nlp-be/src/main/java/**/application/)
  - Frontend components (nlp-fe/src/app/features/)
  - Frontend services (nlp-fe/src/app/features/**/*.service.ts)
- Flag identical DTOs/interfaces between features

### 4. Pattern Consistency
Check ALL aggregates against these rules:
- Controllers use CommandBus/QueryBus (not direct repository access)
- QueryHandlers read from *View tables only (not aggregates/event store)
- Each command has exactly one handler registered in CqrsConfiguration
- Projection handlers listen to domain events and update view tables
- No circular dependencies between bounded contexts

### 5. Dependency Analysis
- Backend: Check build.gradle for unused dependencies
- Frontend: Check package.json for unused packages
- Look for circular imports in TypeScript files
- Flag any deprecated dependency versions

### 6. Test Coverage Gaps
- For each source file in nlp-be/src/main/java, check if a corresponding test exists in nlp-be/src/test/java
- For each component in nlp-fe/src/app, check if a .spec.ts file exists
- Flag source files modified in last 7 days that lack tests

## Output Format

Categorize ALL findings by severity:

### CRITICAL (must fix)
Findings that indicate bugs, broken patterns, or security issues.
- `file:line` — description — **evidence:** snippet or explanation

### WARNING (should fix)
Findings that indicate tech debt, inconsistency, or potential problems.
- `file:line` — description — **evidence:** snippet or explanation

### INFO (nice to have)
Findings that are informational or very low impact.
- `file:line` — description — **evidence:** snippet or explanation

### Statistics
- Total files scanned: N
- Dead code candidates: N
- Duplicate blocks found: N
- Pattern violations: N
- Missing tests: N
- Unused dependencies: N