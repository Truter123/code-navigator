# Claude FX — JavaFX Terminal GUI for Claude Code

## Overview

A JavaFX desktop application that wraps Claude Code CLI in a full terminal emulator. Tabbed interface where each tab runs an independent `claude` process via a real PTY. Full ANSI escape code rendering, configurable settings, session persistence, in-terminal search, and log export.

## Goals

- Faithful terminal emulation — Claude Code's TUI features (spinners, progress bars, colors, interactive prompts) render correctly
- Multi-session tabs — run multiple independent Claude Code sessions simultaneously
- Desktop-native experience — keyboard shortcuts, window management, drag-and-drop tab reorder
- Configurable — binary path, arguments, working directory, font, colors, key bindings
- Full-featured — search in output, log export, session persistence across restarts

## Non-Goals

- Not a chat UI with bubbles/formatting — this is a terminal emulator
- No direct Claude API integration — communicates only through the CLI
- No per-project settings — global config only in `~/.claude-fx/`
- No terminal multiplexing (splits within a tab) — one terminal per tab
- No accessibility (screen reader, high-contrast) — may be added later

## Architecture

New Gradle subproject `claude-fx/` in the existing monorepo.

```
claude-fx/
├── src/main/java/com/claudefx/
│   ├── ClaudeFxApplication.java
│   ├── terminal/
│   │   ├── AnsiParser.java
│   │   ├── TerminalBuffer.java
│   │   ├── TerminalCell.java
│   │   ├── TerminalRenderer.java
│   │   └── TerminalWidget.java
│   ├── process/
│   │   ├── ClaudeProcess.java
│   │   └── PtyBridge.java
│   ├── session/
│   │   ├── Session.java
│   │   ├── SessionManager.java
│   │   └── SessionState.java
│   ├── ui/
│   │   ├── MainWindow.java
│   │   ├── TabHeader.java
│   │   ├── SettingsDialog.java
│   │   ├── SearchBar.java
│   │   └── KeyBindingsDialog.java
│   └── config/
│       ├── AppConfig.java
│       ├── KeyBindings.java
│       └── ConfigStore.java
├── src/main/resources/
│   ├── styles/terminal.css
│   └── fonts/
└── build.gradle
```

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| JavaFX | 21 | UI framework (controls, graphics, Canvas) |
| javafx-gradle-plugin | 0.1.0 | Handles `--module-path` and `--add-modules` for JavaFX |
| pty4j | 0.12.13 | Pseudo-terminal allocation for subprocess |
| Jackson | 2.18.2 | JSON config serialization |
| Shadow plugin | 9.0.0-beta12 | Fat JAR packaging |

**JavaFX packaging note:** JavaFX 21 requires module path configuration. The `javafx-gradle-plugin` handles this for `run` tasks. For the shadow JAR, JavaFX platform-specific native libraries must be included — use `classifier` dependencies for the target platform (linux, win, mac). Similarly, `pty4j` bundles platform-native libraries that must be preserved in the fat JAR (not merged/relocated by Shadow).

## Component Design

### 1. Terminal Engine

Three layers that transform raw PTY bytes into rendered pixels.

#### PtyBridge

Spawns `claude` in a real pseudo-terminal via `pty4j`.

- Sets `TERM=xterm-256color`
- Propagates window resize as `SIGWINCH` to the child process
- Raw byte stream in/out — no line buffering
- Runs reader thread that feeds bytes to `AnsiParser`
- Stdout and stderr are merged into a single stream by the PTY — this is intentional and matches real terminal behavior

#### AnsiParser

State machine that processes the PTY byte stream. Implements a subset of VT100/xterm:

- **CSI sequences** — cursor movement (`CUU/CUD/CUF/CUB`), erase (`ED/EL`), scroll regions (`DECSTBM`), SGR (colors and text attributes)
- **OSC sequences** — window/tab title (OSC 0/2)
- **Alternate screen buffer** — `DECSET 1049` / `DECRST 1049` for full-screen TUI modes
- **Color support** — 16 standard, 256 indexed, 24-bit RGB via SGR 38/48
- **UTF-8** — multibyte character decoding

The parser calls methods on `TerminalBuffer` as it processes sequences.

#### TerminalBuffer

Grid of `TerminalCell` objects representing the virtual screen.

- **Primary + alternate screen buffers** — switch on DECSET/DECRST 1049
- **Scrollback history** — ring buffer, configurable limit (default 10,000 lines)
- **Cursor state** — position (row, col), visibility, style
- **Dirty tracking** — bitset of rows modified since last render
- **Default size** — 80x24, resizable (recalculated on widget resize)

#### TerminalCell

Single cell in the grid:

```java
public class TerminalCell {
    int codepoint;         // Unicode codepoint (int, not char — supports supplementary planes/emoji)
    boolean wideChar;      // true if this is a double-width character (CJK etc.)
    boolean widthContinuation; // true if this cell is the trailing half of a wide char
    Color foreground;      // 24-bit color
    Color background;      // 24-bit color
    boolean bold;
    boolean italic;
    boolean underline;
    boolean strikethrough;
    boolean inverse;
}
```

#### TerminalRenderer

Draws the buffer onto a JavaFX `Canvas`:

- Only redraws dirty rows (not full screen)
- Cell-by-cell: background rect, then glyph from monospace font
- Cursor rendering: blinking block/underline/bar (configurable)
- Selection highlighting for copy support
- Render loop via `AnimationTimer`: checks dirty flag each pulse, skips rendering when buffer is clean and cursor blink state unchanged. This avoids idle CPU waste.
- Scrollbar for scrollback navigation

#### TerminalWidget

JavaFX `Region` subclass that composes buffer + renderer + input handling:

- Captures keyboard events, translates to terminal escape sequences (arrow keys, function keys, etc.), writes to PTY stdin
- Mouse events for text selection (click-drag, double-click word select, triple-click line select)
- Right-click context menu: copy, paste, select all
- Resize listener: recalculates grid dimensions, sends SIGWINCH

### 2. Process Management

#### ClaudeProcess

Manages a single `claude` subprocess lifecycle:

- **Start** — builds command from config (binary path + args), sets working directory, spawns via `PtyBridge`
- **Stop** — sends SIGHUP, waits 2 seconds, SIGKILL if still alive
- **Status** — tracks process state: running, exited (with exit code)
- **Environment** — inherits system env, adds/overrides as configured

### 3. Session & Tab Management

#### Session

Binds one `ClaudeProcess` to one `TerminalWidget`:

- Working directory
- Tab title (auto-set from OSC title or directory basename)
- Creation timestamp
- Process status

#### SessionManager

Controls multi-tab lifecycle:

- **New tab** — spawns process, creates terminal widget, adds tab
- **Close tab** — stops process, disposes resources, confirms if process still running
- **Tab reorder** — drag-and-drop within the tab bar
- **Tab status** — green dot = running, gray dot = exited
- **Tab header** — directory name (truncated), status indicator, close button, double-click to rename

#### Session Persistence

On application exit, saves to `~/.claude-fx/sessions.json`:

```json
{
  "tabs": [
    {
      "workingDir": "/home/user/project-a",
      "title": "project-a",
      "position": 0
    }
  ],
  "activeTabIndex": 0,
  "window": {
    "width": 1200,
    "height": 800,
    "x": 100,
    "y": 100,
    "maximized": false
  }
}
```

On startup: restores window geometry from `sessions.json`, reopens tabs as fresh sessions in the saved directories. Terminal scrollback is NOT persisted (too large).

**Window geometry precedence:** `sessions.json` stores last-used window state and is authoritative on startup. `config.json`'s `window` section stores the user's preferred defaults — used only when no `sessions.json` exists (first launch or after reset).

### 4. Settings & Configuration

Config file: `~/.claude-fx/config.json`

```json
{
  "claudeBinary": "claude",
  "defaultArgs": [],
  "defaultWorkingDir": "~",
  "terminal": {
    "fontFamily": "JetBrains Mono",
    "fontSize": 14,
    "scrollbackLines": 10000,
    "cursorStyle": "block",
    "cursorBlink": true,
    "colorScheme": "dark"
  },
  "keyBindings": {
    "newTab": "Ctrl+T",
    "closeTab": "Ctrl+W",
    "nextTab": "Ctrl+Tab",
    "prevTab": "Ctrl+Shift+Tab",
    "search": "Ctrl+F",
    "copy": "Ctrl+Shift+C",
    "paste": "Ctrl+Shift+V",
    "zoomIn": "Ctrl+Plus",
    "zoomOut": "Ctrl+Minus",
    "exportLog": "Ctrl+Shift+S",
    "settings": "Ctrl+Comma"
  },
  "window": {
    "width": 1200,
    "height": 800,
    "x": null,
    "y": null,
    "maximized": false
  }
}
```

#### Color Schemes

A color scheme defines the 16 ANSI palette colors plus UI colors:

```json
{
  "name": "dark",
  "foreground": "#d4d4d4",
  "background": "#1e1e1e",
  "cursorColor": "#ffffff",
  "selectionBackground": "#264f78",
  "ansi": [
    "#000000", "#cd3131", "#0dbc79", "#e5e510",
    "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
    "#666666", "#f14c4c", "#23d18b", "#f5f543",
    "#3b8eea", "#d670d6", "#29b8db", "#ffffff"
  ]
}
```

Built-in schemes: `dark` (default), `light`. Stored in `~/.claude-fx/schemes/` as JSON files — users can add custom schemes by dropping files there.

#### SettingsDialog

Tabbed dialog:

- **General** — claude binary path (with file picker), default arguments, default working directory
- **Terminal** — font family, font size, scrollback lines, cursor style, cursor blink, color scheme
- **Key Bindings** — table of action + shortcut, click a row then press key combo to rebind

Changes apply immediately to new tabs. Terminal appearance changes apply to all open tabs live.

#### KeyBindings

All shortcuts are remappable. Default shortcuts avoid conflicting with Claude Code's own keybindings. `Ctrl+Shift+C/V` for copy/paste (not `Ctrl+C/V`) so `Ctrl+C` passes through as SIGINT.

**Platform-specific defaults:** On Linux, defaults use `Ctrl+Shift+` prefix. On macOS, defaults use `Cmd+` prefix (e.g., `Cmd+C` for copy, `Cmd+T` for new tab). Platform is detected at startup and the appropriate default set is loaded. Users can override any binding regardless of platform.

### 5. Search

**SearchBar** — overlay that slides down from top of terminal on `Ctrl+F`:

- Text input with match count ("3 of 17")
- Up/Down navigation between matches
- Toggles: case-sensitive, regex
- Searches scrollback + visible screen
- Matches highlighted with distinct background color in the renderer
- `Escape` closes the bar and clears highlights

### 6. Log Export

Triggered via `Ctrl+Shift+S`:

- File chooser dialog, default filename: `claude-session-YYYY-MM-DD-HHmmss.log`
- Two formats:
  - **Plain text** — strips all ANSI escape codes, pure text content
  - **ANSI** — preserves escape codes, viewable with `less -R` or similar
- Exports full scrollback history + visible screen content

## Threading & Synchronization

The PTY reader runs on a background thread. The JavaFX renderer runs on the Application Thread. Synchronization strategy:

1. PTY reader thread reads bytes, feeds them to `AnsiParser`
2. `AnsiParser` updates `TerminalBuffer` while holding the buffer's lock
3. `AnsiParser` sets a dirty flag (atomic boolean) after each update batch
4. On the JavaFX Application Thread, the `AnimationTimer` checks the dirty flag each pulse
5. If dirty, it acquires the buffer lock, copies the dirty rows into a render snapshot, releases the lock, then renders the snapshot

This lock-and-snapshot approach keeps the lock hold time minimal and avoids `Platform.runLater` flooding under heavy output.

## Data Flow

```
Keyboard Input (JavaFX Application Thread)
    │
    ▼
TerminalWidget (translates keys to escape sequences)
    │
    ▼
PtyBridge (writes to PTY stdin)
    │
    ▼
claude process (reads stdin, writes stdout+stderr merged by PTY)
    │
    ▼
PtyBridge (reads PTY output on background reader thread)
    │
    ▼
AnsiParser (state machine, updates buffer under lock)
    │
    ▼
TerminalBuffer (grid of cells, marks dirty rows, sets dirty flag)
    │
    ▼
TerminalRenderer (JavaFX Application Thread — snapshots dirty rows, renders on Canvas)
```

## Error Handling

- **Claude binary not found** — show dialog on first tab creation, open settings
- **Process crash** — terminal shows exit code, tab goes gray. Pressing Enter creates a new `ClaudeProcess` in the same `Session` (same working directory, same tab), effectively restarting. User can also close the tab.
- **Corrupted config** — fall back to defaults, log warning
- **PTY allocation failure** — show error dialog and refuse to start the session. PTY is required for correct Claude Code behavior (it disables TUI mode without `isatty`). No pipe-based fallback.

## Testing Strategy

- **AnsiParser** — unit tests with known escape sequences, verify buffer state
- **TerminalBuffer** — unit tests for cursor movement, scroll regions, alternate buffer
- **Integration** — spawn a real process (`echo`, `ls --color`), verify rendered output
- **Config** — round-trip serialization tests
