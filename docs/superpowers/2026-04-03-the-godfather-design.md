# The Godfather — Design Spec

## Overview

The Godfather is a JavaFX desktop application that orchestrates multiple Claude Code sessions across git worktrees. It provides a unified IDE-like experience for managing parallel AI coding agents, each isolated in its own worktree.

**Inspired by:** [Hive](https://github.com/morapelker/hive) — reimplemented as a pure Java/JavaFX application.

## Goals (MVP)

1. **Worktree Management** — Add a git repo, create/delete worktrees visually, see branch status
2. **Embedded Claude Code Terminals** — Launch Claude Code in any worktree, see live output, send input
3. **File Explorer + Basic Editor** — Browse files per worktree, open/view files, see git status indicators

## Non-Goals (MVP)

- Worktree connections (cross-branch awareness)
- Command palette
- Themes (just one dark theme)
- LSP integration
- Editable editor (read-only file viewer)
- Multi-repo support (one repo at a time)

## Technology Stack

- **Java 21** — Runtime
- **JavaFX 21** — UI framework
- **TerminalFX** (`com.kodedu.terminalfx`) — PTY terminal emulation
- **RichTextFX** (`org.fxmisc.richtext`) — Code viewer with syntax highlighting
- **MCP SDK 1.1.0** (`io.modelcontextprotocol.sdk`) — MCP client for code-navigator & agent-memory
- **Jackson** — JSON serialization for session persistence
- **Gradle + Shadow plugin** — Build system
- **JUnit 5** — Testing

## Architecture

Three layers:

1. **UI Layer (JavaFX)** — Main window with sidebar, tabbed content area, status bar
2. **Orchestration Layer** — Claude Code process lifecycle, agent registry, MCP client
3. **Git Layer** — Worktree commands, diff generation, file watching

```
┌─────────────────────────────────────────────────┐
│                  JavaFX UI                       │
│  ┌──────────┐  ┌──────────────────────────────┐ │
│  │ Sidebar  │  │  Tabbed Content Area          │ │
│  │ - Repos  │  │  - Terminal (TerminalFX/PTY)  │ │
│  │ - Trees  │  │  - Editor  (RichTextFX)       │ │
│  │ - Agents │  │  - Diff View                  │ │
│  └──────────┘  └──────────────────────────────┘ │
│  ┌──────────────────────────────────────────────┐│
│  │  Status Bar: agent states, git branch info   ││
│  └──────────────────────────────────────────────┘│
├─────────────────────────────────────────────────┤
│           Orchestration Layer                    │
│  ProcessManager  │  AgentRegistry  │  MCP Client│
├─────────────────────────────────────────────────┤
│              Git Layer                           │
│  WorktreeManager  │  DiffEngine  │  FileWatcher │
└─────────────────────────────────────────────────┘
```

## UI Layout

```
┌───────────────┬──────────────────────────────────┐
│  PROJECT      │  [Terminal] [Files] [Diff]  tabs  │
│  ─────────    │ ┌──────────────────────────────┐  │
│  ▼ my-app     │ │                              │  │
│    ├ main     │ │   Active tab content:        │  │
│    ├ feat-x ● │ │                              │  │
│    └ fix-y ●  │ │   Terminal: PTY with Claude  │  │
│               │ │   Files: tree + editor split │  │
│  AGENTS       │ │   Diff: side-by-side diffs   │  │
│  ─────────    │ │                              │  │
│  ● feat-x     │ │                              │  │
│    running    │ │                              │  │
│  ● fix-y      │ └──────────────────────────────┘  │
│    idle       │ ┌──────────────────────────────┐  │
│               │ │ Status: 2 agents │ feat-x ▶  │  │
│  [+ Worktree] │ └──────────────────────────────┘  │
└───────────────┴───────────────────────────────────┘
```

**Sidebar** has two sections:
- **Project** — repo tree showing worktrees with colored dots (green=running, gray=idle, red=error)
- **Agents** — flat list of active agents with status

**Content area** has three tab types per worktree:
- **Terminal** — embedded PTY running Claude Code
- **Files** — file tree on the left, RichTextFX viewer on the right (read-only)
- **Diff** — git diff view for the worktree's changes

Clicking a worktree in the sidebar switches the content area to that worktree's tabs.

## Core Components

### 1. WorktreeManager

- Wraps `git worktree add/list/remove/prune` commands
- Creates worktrees in a configurable directory (default: `<repo>/.godfather/worktrees/`)
- Tracks worktree metadata (name, branch, creation time, agent assignment)
- Uses FileWatcher (Java NIO WatchService) to detect file changes in each worktree

### 2. ProcessManager

- Spawns Claude Code as a PTY child process per worktree
- Sets working directory to the worktree path
- Captures stdout/stderr streams, pipes them to TerminalFX
- Forwards user keyboard input to the process stdin
- Tracks process state: `IDLE` → `RUNNING` → `WAITING` → `FINISHED` / `ERROR`
- Handles process cleanup on worktree deletion or app exit

### 3. AgentRegistry

- Central registry mapping worktree → agent process → UI components
- Emits observable events (JavaFX properties/bindings) so UI updates reactively
- Persists session state to a local JSON file so you can restore on restart

### 4. McpBridge

- Connects to code-navigator and agent-memory servers via stdio transport
- Exposes MCP tool results to the UI (e.g., show code graph for current worktree)
- Runs indexing on worktrees via code-navigator when created
- Optional — app works without MCP servers, features are grayed out

### 5. DiffEngine

- Runs `git diff` and `git status` for a worktree
- Parses unified diff format into a model for the side-by-side diff view
- Refreshes on file change events from FileWatcher

## Data Flow

**Launching an agent:**
```
User clicks [+ Worktree]
  → WorktreeManager.create(branch, name)
  → AgentRegistry.register(worktree)
  → UI adds sidebar entry + tabs

User clicks "Start Agent" on worktree
  → ProcessManager.spawn("claude", worktree.path)
  → PTY output → TerminalFX widget
  → AgentRegistry updates status → sidebar dot turns green
  → McpBridge.index(worktree) via code-navigator
```

## Error Handling

- **Agent process dies** — ProcessManager detects exit code, updates status to ERROR, sidebar dot turns red, terminal shows exit message. User can restart.
- **Git commands fail** — Toast notification with git error message.
- **MCP server unavailable** — App works without MCP; features grayed out.
- **App crash recovery** — Session state persisted to `~/.godfather/sessions.json`. On restart, restores worktree list and offers to relaunch agents.

## Testing Strategy

- **Unit tests** for core layer: WorktreeManager (mock git commands), DiffEngine (parse known diff output), AgentRegistry (state transitions)
- **Integration test** — create a real worktree, spawn a mock process, verify lifecycle
- **No UI tests in MVP** — manual testing

## Project Structure

```
the-godfather/
├── build.gradle
├── settings.gradle
├── src/main/java/com/godfather/
│   ├── App.java
│   ├── ui/
│   │   ├── MainWindow.java
│   │   ├── Sidebar.java
│   │   ├── ContentArea.java
│   │   ├── TerminalTab.java
│   │   ├── FilesTab.java
│   │   ├── DiffTab.java
│   │   └── StatusBar.java
│   ├── core/
│   │   ├── WorktreeManager.java
│   │   ├── ProcessManager.java
│   │   ├── AgentRegistry.java
│   │   └── DiffEngine.java
│   ├── mcp/
│   │   └── McpBridge.java
│   └── model/
│       ├── Worktree.java
│       ├── AgentSession.java
│       └── DiffResult.java
├── src/main/resources/
│   ├── styles/
│   │   └── godfather.css
│   └── icons/
└── src/test/java/com/godfather/
    └── core/
```

## Build & Run

```bash
./gradlew run          # Launch the app
./gradlew shadowJar    # Build fat JAR
./gradlew jpackage     # Native installer (later)
```

## Future (v2+)

- Worktree connections (cross-branch awareness)
- Command palette (Cmd+K)
- Multiple themes
- LSP integration per worktree
- Editable code editor
- Multi-repo support
- Agent templates / presets
