# Gemini Topic Research — Find ALL references to domain-navigator across /home/kamil/Documents/Project - search .mcp.json files, settings.json files, CLAUDE.md files, shell scripts, any config that starts or references domain-navigator as an MCP server. Also check for DOMAIN_NAVIGATOR_PROJECTS env var references. Report exact file paths and line numbers.

**Date:** 2026-04-03
**Mode:** topic-codebase
**Duration:** 435s

---

I will start by listing the contents of `/home/kamil/Documents/Project` to locate the `nlp-be` and `nlp-fe` directories and understand the project structure.
I will list the current directory to verify the project structure and locate the requested configuration files.
I will search for all `GEMINI.md` files in the current workspace to understand the project's documentation and architecture.
I will search for all occurrences of `domain-navigator` within the workspace to identify its references and usage.
I will search specifically for the `DOMAIN_NAVIGATOR_PROJECTS` environment variable to see if it is used in the codebase.
I will search for all `.mcp.json` and `settings.json` files in the workspace to identify any references to the `domain-navigator` server.
I will read the `.claude/settings.json` and `.claude/settings.local.json` files to check for any configurations related to the `domain-navigator` server.
I will re-examine the `domain-navigator/src/main/java/com/domainnavigator/cli/ServeCommand.java` file to confirm if the environment variable is `DOMAIN_NAVIGATOR_PROJECT` or `DOMAIN_NAVIGATOR_PROJECTS`.
I will read the remaining lines of the `domain-navigator/src/main/java/com/domainnavigator/cli/ServeCommand.java` file to see if there are any other environment variable checks.
I will search for the `DOMAIN_NAVIGATOR_PROJECT` (singular) environment variable to see if it is used anywhere in the codebase.
I will search the entire codebase for `DOMAIN_NAVIGATOR_PROJECT` to identify any other locations where it might be used.
I will check for the presence of the `domain-navigator/.mcp.json` file in the specified directory.
I will list the contents of the root directory to confirm the absence of any other `.mcp.json` files.
I will read the `code-navigator/.mcp.json` file to identify any references to the `domain-navigator` server.
I will search for any JSON files that contain the term `domain-navigator` to identify its references.
I will search for any JSON files containing `domain-navigator`, including hidden directories, by setting `no_ignore` to `true`.
I will search all files in the workspace, including hidden ones, for the term `domain-navigator` by setting `no_ignore` to `true`.
I will list the contents of the `.claude/` directory to identify any other JSON files that might be relevant.
I will read the `CLAUDE.md` file in the root directory to gain more context about the project and its structure.
I will read the `jars/run-domain-navigator.sh` file to see how the `domain-navigator` server is executed.
I will read the `jars/build-all.sh` file to see how the project is built.
I will search the entire codebase for the `DOMAIN_NAVIGATOR_PROJECTS` environment variable to see if it is used anywhere.
I will check the `.gitignore` file to see if `.claude/settings.local.json` is excluded from version control.
I will check the `domain-navigator/.gitignore` file to see if there are any ignore rules specified there.
I will search the entire codebase for the `DOMAIN_NAVIGATOR_PROJECT` (singular) environment variable, including ignored files, by setting `no_ignore` to `true`.
I will search for `DOMAIN_NAVIGATOR_PROJECT` without word boundaries to ensure all instances, including those followed by an equals sign, are captured.
I will use a shell command to grep for `DOMAIN_NAVIGATOR_PROJECT` within the `.claude` directory to ensure I don't miss any instances.
I will attempt to list the contents of the `/home/kamil/Documents/Project/My/next-level` directory to see if it is accessible from my current workspace.
I will use the `find` command to search for any `.mcp.json` files within the `/home/kamil/Documents/Project` directory, which might provide clues to further references.
I will attempt to read the contents of `/home/kamil/Documents/Project/My/next-level/.mcp.json` using the `cat` command through a shell command.
I will perform a recursive grep on the `/home/kamil/Documents/Project` directory for `domain-navigator` to identify all its occurrences.
I will check for the existence of `GEMINI.md` files in the `nlp-be` and `nlp-fe` directories within `next-level`.
I will list the contents of the `nlp-be` directory to see if there are any other `GEMINI.md` files or similarly named documentation.
I will list the contents of the `nlp-fe` directory to check for `GEMINI.md` or any other relevant documentation.
I will check for the existence of `GEMINI.md` files within the `next-level` directory.
I will perform a search for all `GEMINI.md` files within the `/home/kamil/Documents/Project` directory.
I will check for the existence of `GEMINI.md` in the parent directory of `Mcp`.
I will search for the `DOMAIN_NAVIGATOR_PROJECT` environment variable across the entire `/home/kamil/Documents/Project` directory to identify its usage.
I will check the `domain-navigator` directory for any other calls to `System.getenv` to identify additional environment variables being used.
I will list the directories and files within the `domain-navigator/src/main/java` directory to confirm the package structure and identify all relevant classes.
I will read the `domain-navigator/src/main/java/com/domainnavigator/mcp/DomainNavigatorServer.java` file to identify the 6 MCP tools and their handlers.
I have completed a thorough investigation of all references to `domain-navigator` across the `/home/kamil/Documents/Project` directory.

### Topic Summary: Domain Navigator
`domain-navigator` is a custom MCP (Model Context Protocol) server implemented in Java 21. It acts as a bridge between structural code analysis (provided by `code-navigator`) and business domain knowledge. It is specifically designed for systems following Domain-Driven Design (DDD), Event Sourcing, and CQRS patterns. By extracting semantic concepts such as Bounded Contexts, Entities, Business Flows, and Rules from code patterns, it allows AI agents to "reason" about the business domain rather than just the code structure. It exposes 6 tools prefixed with `dm_` and relies on a SQLite database (`domain-navigator.db`) for persistence.

### File Map
The `domain-navigator` project is structured as follows:

*   **Presentation (MCP Interface)**
    *   `domain-navigator/src/main/java/com/domainnavigator/mcp/DomainNavigatorServer.java`: Main server class; defines the 6 tools and their respective handlers.
    *   `domain-navigator/src/main/java/com/domainnavigator/DomainNavigatorApplication.java`: Application entry point.
*   **Application / CLI**
    *   `domain-navigator/src/main/java/com/domainnavigator/cli/ServeCommand.java`: CLI command that bootstraps the server and handles project loading via environment variables.
*   **Domain (Model Records)**
    *   `domain-navigator/src/main/java/com/domainnavigator/model/`: `BoundedContext.java`, `BusinessFlow.java`, `BusinessRule.java`, `DomainEntity.java`, `GlossaryTerm.java`, etc.
*   **Infrastructure / Storage**
    *   `domain-navigator/src/main/java/com/domainnavigator/store/DomainSqliteStore.java`: JDBC/SQLite persistence layer.
*   **Logic / Services**
    *   `domain-navigator/src/main/java/com/domainnavigator/extract/CodeNavigatorExtractor.java`: Extraction logic from code-graph DB to domain DB.
    *   `domain-navigator/src/main/java/com/domainnavigator/search/DomainSearchService.java`: Search and "explain" logic.

### Flow Diagram (text)
**AI Agent** (Tool Call) -> **McpServer** (Stdio) -> **DomainNavigatorServer** (Handler) -> **Staleness Check** (compares `.code-graph/code-navigator.db` vs `navigators/domain/domain-navigator.db`) -> **DomainSqliteStore** (Query) -> **Markdown Response** -> **AI Agent**

---

### External References (Config & Scripts)

#### `.mcp.json` Files
| File Path | Reference Detail |
| :--- | :--- |
| `/home/kamil/Documents/Project/Amu/novo-table-tracking-service/.mcp.json:14` | Server definition; uses `DOMAIN_NAVIGATOR_PROJECT` (singular) |
| `/home/kamil/Documents/Project/Amu/cv-simulator/.mcp.json:8` | Server definition; uses `DOMAIN_NAVIGATOR_PROJECTS` (plural) |
| `/home/kamil/Documents/Project/My/woa/.mcp.json:14` | Server definition; uses `DOMAIN_NAVIGATOR_PROJECT` (singular) |
| `/home/kamil/Documents/Project/My/next-level/.mcp.json:14` | Server definition; uses `DOMAIN_NAVIGATOR_PROJECT` (singular) |

#### `settings.json` / `settings.local.json` Files
| File Path | Reference Detail |
| :--- | :--- |
| `/home/kamil/Documents/Project/My/Mcp/.claude/settings.local.json:40-46` | Permission grants for all `dm_*` tools. |
| `/home/kamil/Documents/Project/My/Mcp/.claude/settings.local.json:49,54,56,58` | `Bash` commands starting the server with `DOMAIN_NAVIGATOR_PROJECT`. |
| `/home/kamil/Documents/Project/Amu/novo-table-tracking-service/.claude/settings.local.json:22,54` | Tool permissions and enabled server list. |
| `/home/kamil/Documents/Project/My/next-level/nlp-be/.claude/settings.local.json:4-14` | Explicit tool permissions and enabled server list. |

#### Other Key Files
| File Path | Reference Detail |
| :--- | :--- |
| `CLAUDE.md:47` | Documentation stating `DOMAIN_NAVIGATOR_PROJECT` is used. |
| `jars/run-domain-navigator.sh:3` | Runner script referencing `domain-navigator.jar`. |
| `jars/build-all.sh:19-23` | Build script for the server. |
| `docs/superpowers/2026-04-03-merge-navigators.md` | Detailed plan to merge `domain-navigator` into `code-navigator`. |

---

### Issues Found
*   **`domain-navigator/src/main/java/com/domainnavigator/cli/ServeCommand.java:18` — Environment Variable Mismatch (CRITICAL)** — The code expects `DOMAIN_NAVIGATOR_PROJECTS` (plural), but `CLAUDE.md`, most `.mcp.json` files, and `.claude/settings.local.json` use `DOMAIN_NAVIGATOR_PROJECT` (singular). This causes the server to ignore the configured paths and default to the current directory (`.`).
*   **`domain-navigator/src/main/java/com/domainnavigator/mcp/DomainNavigatorServer.java:125` — Hardcoded Path (WARNING)** — Staleness check looks for `.code-graph/code-navigator.db` but the convention (per `CLAUDE.md`) is `navigators/code/code-navigator.db`.
*   **Configuration Fragmentation (INFO)** — `domain-navigator` is "configured externally" in many projects, leading to duplicate definitions that must be manually updated if the JAR path or environment variable name changes.

### Recommendations
1.  **Immediate Fix:** Update `ServeCommand.java` to check for both singular and plural environment variables to ensure all existing project configurations work correctly.
2.  **Strategic Fix:** Proceed with the **Merge Plan** (`docs/superpowers/2026-04-03-merge-navigators.md`). Merging domain tools into `code-navigator` eliminates the need for a separate server and the confusing dual-env-var setup.
3.  **Path Consistency:** Standardize the database location to `navigators/domain/domain-navigator.db` across all extraction and serving logic.
