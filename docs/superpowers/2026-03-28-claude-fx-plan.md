# Claude FX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a JavaFX desktop terminal emulator that wraps Claude Code CLI, with tabbed sessions, full ANSI rendering, configurable settings, search, and log export.

**Architecture:** New standalone Gradle subproject `claude-fx/` in the monorepo. Three-layer terminal engine (PTY bridge → ANSI parser → cell buffer → Canvas renderer). Each tab owns an independent claude subprocess spawned via pty4j. Settings stored in `~/.claude-fx/config.json`.

**Tech Stack:** Java 21, JavaFX 21, pty4j 0.12.13, Jackson 2.18.2, JUnit 5, AssertJ, Shadow plugin

**Spec:** `docs/superpowers/2026-03-28-claude-fx-design.md`

---

## File Structure

```
claude-fx/
├── settings.gradle
├── build.gradle
├── src/main/java/com/claudefx/
│   ├── ClaudeFxApplication.java              — JavaFX Application entry, wires MainWindow
│   ├── terminal/
│   │   ├── TerminalCell.java                 — Single cell: codepoint + attributes
│   │   ├── TerminalBuffer.java               — Grid of cells, scrollback, cursor, dirty tracking
│   │   ├── AnsiParser.java                   — VT100/xterm state machine, updates buffer
│   │   ├── TerminalRenderer.java             — Renders buffer to JavaFX Canvas
│   │   └── TerminalWidget.java               — Region composing buffer+renderer+input
│   ├── process/
│   │   ├── PtyBridge.java                    — PTY allocation and I/O via pty4j
│   │   └── ClaudeProcess.java                — Lifecycle: start/stop/status of claude subprocess
│   ├── session/
│   │   ├── Session.java                      — Binds ClaudeProcess + TerminalWidget + metadata
│   │   ├── SessionManager.java               — Multi-tab lifecycle, persistence
│   │   └── SessionState.java                 — Serializable state for sessions.json
│   ├── ui/
│   │   ├── MainWindow.java                   — Primary window: tab bar + terminal area
│   │   ├── TabHeader.java                    — Custom tab: title, status dot, close button
│   │   ├── SearchBar.java                    — Overlay search with match navigation
│   │   ├── SettingsDialog.java               — Tabbed settings UI
│   │   └── KeyBindingsDialog.java            — Shortcut rebinding table
│   └── config/
│       ├── AppConfig.java                    — Settings POJO (Jackson-serializable)
│       ├── ColorScheme.java                  — Color scheme POJO
│       ├── KeyBindings.java                  — Shortcut mappings with platform defaults
│       └── ConfigStore.java                  — Read/write ~/.claude-fx/ files
├── src/main/resources/
│   ├── styles/terminal.css                   — JavaFX CSS for dark theme
│   ├── schemes/dark.json                     — Built-in dark color scheme
│   └── schemes/light.json                    — Built-in light color scheme
├── src/test/java/com/claudefx/
│   ├── terminal/
│   │   ├── TerminalCellTest.java
│   │   ├── TerminalBufferTest.java
│   │   └── AnsiParserTest.java
│   ├── process/
│   │   └── PtyBridgeTest.java
│   ├── config/
│   │   ├── AppConfigTest.java
│   │   └── ConfigStoreTest.java
│   └── session/
│       └── SessionStateTest.java
```

---

### Task 1: Project Scaffolding

**Files:**
- Create: `claude-fx/settings.gradle`
- Create: `claude-fx/build.gradle`
- Create: `claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java`

- [ ] **Step 0: Bootstrap Gradle wrapper**

Run: `cd claude-fx && gradle wrapper --gradle-version 8.5`
Expected: Creates `gradlew`, `gradlew.bat`, `gradle/wrapper/` files.

- [ ] **Step 1: Create `settings.gradle`**

```groovy
rootProject.name = 'claude-fx'
```

- [ ] **Step 2: Create `build.gradle`**

```groovy
plugins {
    id 'java'
    id 'application'
    id 'org.openjfx.javafxplugin' version '0.1.0'
    id 'com.gradleup.shadow' version '9.0.0-beta12'
}

group = 'com.claudefx'
version = '0.1.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = 'com.claudefx.ClaudeFxApplication'
}

javafx {
    version = '21.0.2'
    modules = ['javafx.controls', 'javafx.graphics']
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.jetbrains.pty4j:pty4j:0.12.13'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'

    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core:3.27.3'
}

tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'failed', 'skipped'
        showStandardStreams = false
    }
}

shadowJar {
    archiveBaseName = 'claude-fx'
    archiveClassifier = ''
    mergeServiceFiles()
    // Preserve pty4j native libraries — do not relocate or merge
    exclude 'META-INF/native/**'
}

// Re-include pty4j natives from the original jar
tasks.shadowJar {
    from(configurations.runtimeClasspath.filter { it.name.contains('pty4j') || it.name.contains('jna') }) {
        include 'META-INF/native/**'
    }
}

distTar.dependsOn shadowJar
distZip.dependsOn shadowJar
startScripts.dependsOn shadowJar
startShadowScripts.dependsOn jar
```

- [ ] **Step 3: Create `ClaudeFxApplication.java`**

```java
package com.claudefx;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class ClaudeFxApplication extends Application {

    @Override
    public void start(Stage primaryStage) {
        var label = new Label("Claude FX — Loading...");
        var root = new StackPane(label);
        var scene = new Scene(root, 1200, 800);
        primaryStage.setTitle("Claude FX");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add claude-fx/settings.gradle claude-fx/build.gradle claude-fx/src/
git commit -m "feat(claude-fx): scaffold JavaFX project with pty4j and shadow jar"
```

---

### Task 2: TerminalCell

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/terminal/TerminalCell.java`
- Create: `claude-fx/src/test/java/com/claudefx/terminal/TerminalCellTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.claudefx.terminal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TerminalCellTest {

    @Test
    void defaultCellIsSpaceWithDefaultColors() {
        var cell = new TerminalCell();
        assertThat(cell.getCodepoint()).isEqualTo(' ');
        assertThat(cell.isBold()).isFalse();
        assertThat(cell.isWideChar()).isFalse();
        assertThat(cell.isWidthContinuation()).isFalse();
    }

    @Test
    void resetClearsAllAttributes() {
        var cell = new TerminalCell();
        cell.setCodepoint('A');
        cell.setBold(true);
        cell.setItalic(true);
        cell.setUnderline(true);
        cell.setStrikethrough(true);
        cell.setInverse(true);
        cell.setWideChar(true);

        cell.reset();

        assertThat(cell.getCodepoint()).isEqualTo(' ');
        assertThat(cell.isBold()).isFalse();
        assertThat(cell.isItalic()).isFalse();
        assertThat(cell.isUnderline()).isFalse();
        assertThat(cell.isStrikethrough()).isFalse();
        assertThat(cell.isInverse()).isFalse();
        assertThat(cell.isWideChar()).isFalse();
    }

    @Test
    void copyFromCopiesAllFields() {
        var source = new TerminalCell();
        source.setCodepoint(0x1F600); // emoji
        source.setBold(true);
        source.setWideChar(true);

        var target = new TerminalCell();
        target.copyFrom(source);

        assertThat(target.getCodepoint()).isEqualTo(0x1F600);
        assertThat(target.isBold()).isTrue();
        assertThat(target.isWideChar()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.terminal.TerminalCellTest" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

```java
package com.claudefx.terminal;

import javafx.scene.paint.Color;

public class TerminalCell {

    private static final Color DEFAULT_FG = Color.rgb(212, 212, 212);
    private static final Color DEFAULT_BG = Color.rgb(30, 30, 30);

    private int codepoint = ' ';
    private boolean wideChar;
    private boolean widthContinuation;
    private Color foreground = DEFAULT_FG;
    private Color background = DEFAULT_BG;
    private boolean bold;
    private boolean italic;
    private boolean underline;
    private boolean strikethrough;
    private boolean inverse;

    public void reset() {
        codepoint = ' ';
        wideChar = false;
        widthContinuation = false;
        foreground = DEFAULT_FG;
        background = DEFAULT_BG;
        bold = false;
        italic = false;
        underline = false;
        strikethrough = false;
        inverse = false;
    }

    public void copyFrom(TerminalCell other) {
        this.codepoint = other.codepoint;
        this.wideChar = other.wideChar;
        this.widthContinuation = other.widthContinuation;
        this.foreground = other.foreground;
        this.background = other.background;
        this.bold = other.bold;
        this.italic = other.italic;
        this.underline = other.underline;
        this.strikethrough = other.strikethrough;
        this.inverse = other.inverse;
    }

    // --- getters and setters ---
    public int getCodepoint() { return codepoint; }
    public void setCodepoint(int codepoint) { this.codepoint = codepoint; }

    public boolean isWideChar() { return wideChar; }
    public void setWideChar(boolean wideChar) { this.wideChar = wideChar; }

    public boolean isWidthContinuation() { return widthContinuation; }
    public void setWidthContinuation(boolean widthContinuation) { this.widthContinuation = widthContinuation; }

    public Color getForeground() { return foreground; }
    public void setForeground(Color foreground) { this.foreground = foreground; }

    public Color getBackground() { return background; }
    public void setBackground(Color background) { this.background = background; }

    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }

    public boolean isItalic() { return italic; }
    public void setItalic(boolean italic) { this.italic = italic; }

    public boolean isUnderline() { return underline; }
    public void setUnderline(boolean underline) { this.underline = underline; }

    public boolean isStrikethrough() { return strikethrough; }
    public void setStrikethrough(boolean strikethrough) { this.strikethrough = strikethrough; }

    public boolean isInverse() { return inverse; }
    public void setInverse(boolean inverse) { this.inverse = inverse; }

    public static Color getDefaultForeground() { return DEFAULT_FG; }
    public static Color getDefaultBackground() { return DEFAULT_BG; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.terminal.TerminalCellTest" -v`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/TerminalCell.java \
        claude-fx/src/test/java/com/claudefx/terminal/TerminalCellTest.java
git commit -m "feat(claude-fx): add TerminalCell with attributes and reset/copy"
```

---

### Task 3: TerminalBuffer

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/terminal/TerminalBuffer.java`
- Create: `claude-fx/src/test/java/com/claudefx/terminal/TerminalBufferTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.claudefx.terminal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TerminalBufferTest {

    @Test
    void initialSizeIs80x24() {
        var buf = new TerminalBuffer(80, 24, 100);
        assertThat(buf.getColumns()).isEqualTo(80);
        assertThat(buf.getRows()).isEqualTo(24);
    }

    @Test
    void cursorStartsAtOrigin() {
        var buf = new TerminalBuffer(80, 24, 100);
        assertThat(buf.getCursorRow()).isZero();
        assertThat(buf.getCursorCol()).isZero();
    }

    @Test
    void putCharWritesAndAdvancesCursor() {
        var buf = new TerminalBuffer(80, 24, 100);
        buf.putChar('H');
        buf.putChar('i');
        assertThat(buf.getCell(0, 0).getCodepoint()).isEqualTo('H');
        assertThat(buf.getCell(0, 1).getCodepoint()).isEqualTo('i');
        assertThat(buf.getCursorCol()).isEqualTo(2);
    }

    @Test
    void putCharWrapsAtEndOfLine() {
        var buf = new TerminalBuffer(5, 3, 100);
        for (int i = 0; i < 5; i++) buf.putChar('A');
        buf.putChar('B');
        assertThat(buf.getCursorRow()).isEqualTo(1);
        assertThat(buf.getCursorCol()).isEqualTo(1);
        assertThat(buf.getCell(1, 0).getCodepoint()).isEqualTo('B');
    }

    @Test
    void lineFeedScrollsWhenAtBottom() {
        var buf = new TerminalBuffer(5, 3, 100);
        buf.setCursorRow(2);
        buf.lineFeed();
        // cursor stays at row 2, content scrolled up
        assertThat(buf.getCursorRow()).isEqualTo(2);
        assertThat(buf.getScrollbackSize()).isEqualTo(1);
    }

    @Test
    void carriageReturnMovesCursorToColumnZero() {
        var buf = new TerminalBuffer(80, 24, 100);
        buf.putChar('A');
        buf.putChar('B');
        buf.carriageReturn();
        assertThat(buf.getCursorCol()).isZero();
    }

    @Test
    void eraseLineClearsCurrentRow() {
        var buf = new TerminalBuffer(10, 3, 100);
        buf.putChar('X');
        buf.putChar('Y');
        buf.eraseLine(0); // erase from cursor to end
        assertThat(buf.getCell(0, 2).getCodepoint()).isEqualTo(' ');
    }

    @Test
    void setCursorPositionClamps() {
        var buf = new TerminalBuffer(80, 24, 100);
        buf.setCursorPosition(100, 200);
        assertThat(buf.getCursorRow()).isEqualTo(23);
        assertThat(buf.getCursorCol()).isEqualTo(79);
    }

    @Test
    void alternateBufferSwitchPreservesMainContent() {
        var buf = new TerminalBuffer(10, 3, 100);
        buf.putChar('M');
        buf.switchToAlternateBuffer();
        assertThat(buf.getCell(0, 0).getCodepoint()).isEqualTo(' ');
        buf.switchToMainBuffer();
        assertThat(buf.getCell(0, 0).getCodepoint()).isEqualTo('M');
    }

    @Test
    void dirtyTrackingReportsChangedRows() {
        var buf = new TerminalBuffer(10, 3, 100);
        buf.clearDirty();
        buf.putChar('A');
        assertThat(buf.isDirty()).isTrue();
        assertThat(buf.isDirtyRow(0)).isTrue();
        assertThat(buf.isDirtyRow(1)).isFalse();
    }

    @Test
    void eraseDisplayClearsEntireScreen() {
        var buf = new TerminalBuffer(5, 3, 100);
        buf.putChar('X');
        buf.eraseDisplay(2); // erase entire display
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 5; c++)
                assertThat(buf.getCell(r, c).getCodepoint()).isEqualTo(' ');
    }

    @Test
    void scrollRegionScrollsOnlyWithinBounds() {
        var buf = new TerminalBuffer(10, 5, 100);
        buf.setScrollRegion(1, 3); // rows 1-3 scroll
        buf.setCursorRow(3);
        buf.lineFeed();
        // row 1 should have been scrolled away, row 0 untouched
        assertThat(buf.getCursorRow()).isEqualTo(3);
    }

    @Test
    void getTextExtractsPlainText() {
        var buf = new TerminalBuffer(10, 3, 100);
        buf.putChar('H');
        buf.putChar('i');
        assertThat(buf.getText(0, 0, 0, 2)).isEqualTo("Hi");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.terminal.TerminalBufferTest" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

```java
package com.claudefx.terminal;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class TerminalBuffer {

    private final int maxScrollback;
    private int columns;
    private int rows;

    private TerminalCell[][] mainGrid;
    private TerminalCell[][] altGrid;
    private TerminalCell[][] activeGrid;
    private boolean usingAltBuffer;

    private final List<TerminalCell[]> scrollback = new ArrayList<>();

    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;

    private int scrollTop;
    private int scrollBottom;

    private final BitSet dirtyRows = new BitSet();
    private volatile boolean dirty;

    // Current SGR attributes applied to new characters
    private final TerminalCell currentAttributes = new TerminalCell();

    public TerminalBuffer(int columns, int rows, int maxScrollback) {
        this.columns = columns;
        this.rows = rows;
        this.maxScrollback = maxScrollback;
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;
        this.mainGrid = createGrid(columns, rows);
        this.altGrid = createGrid(columns, rows);
        this.activeGrid = mainGrid;
    }

    private TerminalCell[][] createGrid(int cols, int rowCount) {
        var grid = new TerminalCell[rowCount][cols];
        for (int r = 0; r < rowCount; r++)
            for (int c = 0; c < cols; c++)
                grid[r][c] = new TerminalCell();
        return grid;
    }

    // --- Character output ---

    public void putChar(int codepoint) {
        if (cursorCol >= columns) {
            carriageReturn();
            lineFeed();
        }
        var cell = activeGrid[cursorRow][cursorCol];
        cell.copyFrom(currentAttributes);
        cell.setCodepoint(codepoint);
        markDirty(cursorRow);
        cursorCol++;
    }

    public void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    public void carriageReturn() {
        cursorCol = 0;
    }

    // --- Scrolling ---

    private void scrollUp(int count) {
        for (int i = 0; i < count; i++) {
            if (!usingAltBuffer && scrollTop == 0) {
                // Copy top row to scrollback
                var scrolledRow = new TerminalCell[columns];
                for (int c = 0; c < columns; c++) {
                    scrolledRow[c] = new TerminalCell();
                    scrolledRow[c].copyFrom(activeGrid[scrollTop][c]);
                }
                scrollback.add(scrolledRow);
                if (scrollback.size() > maxScrollback) {
                    scrollback.removeFirst();
                }
            }
            // Shift rows up within scroll region
            for (int r = scrollTop; r < scrollBottom; r++) {
                var temp = activeGrid[r];
                activeGrid[r] = activeGrid[r + 1];
                activeGrid[r + 1] = temp;
            }
            // Clear the bottom row of scroll region
            for (int c = 0; c < columns; c++) {
                activeGrid[scrollBottom][c].reset();
            }
        }
        // Mark all rows in scroll region dirty
        for (int r = scrollTop; r <= scrollBottom; r++) markDirty(r);
    }

    // --- Erase ---

    public void eraseLine(int mode) {
        switch (mode) {
            case 0 -> { // cursor to end
                for (int c = cursorCol; c < columns; c++) activeGrid[cursorRow][c].reset();
            }
            case 1 -> { // start to cursor
                for (int c = 0; c <= cursorCol && c < columns; c++) activeGrid[cursorRow][c].reset();
            }
            case 2 -> { // entire line
                for (int c = 0; c < columns; c++) activeGrid[cursorRow][c].reset();
            }
        }
        markDirty(cursorRow);
    }

    public void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> { // cursor to end
                eraseLine(0);
                for (int r = cursorRow + 1; r < rows; r++) clearRow(r);
            }
            case 1 -> { // start to cursor
                for (int r = 0; r < cursorRow; r++) clearRow(r);
                eraseLine(1);
            }
            case 2 -> { // entire display
                for (int r = 0; r < rows; r++) clearRow(r);
            }
        }
    }

    private void clearRow(int row) {
        for (int c = 0; c < columns; c++) activeGrid[row][c].reset();
        markDirty(row);
    }

    // --- Cursor ---

    public void setCursorPosition(int row, int col) {
        this.cursorRow = Math.max(0, Math.min(row, rows - 1));
        this.cursorCol = Math.max(0, Math.min(col, columns - 1));
    }

    public void moveCursorUp(int n) { setCursorPosition(cursorRow - n, cursorCol); }
    public void moveCursorDown(int n) { setCursorPosition(cursorRow + n, cursorCol); }
    public void moveCursorForward(int n) { setCursorPosition(cursorRow, cursorCol + n); }
    public void moveCursorBackward(int n) { setCursorPosition(cursorRow, cursorCol - n); }

    // --- Scroll region ---

    public void setScrollRegion(int top, int bottom) {
        this.scrollTop = Math.max(0, Math.min(top, rows - 1));
        this.scrollBottom = Math.max(this.scrollTop, Math.min(bottom, rows - 1));
    }

    public void resetScrollRegion() {
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;
    }

    // --- Alternate buffer ---

    public void switchToAlternateBuffer() {
        if (!usingAltBuffer) {
            usingAltBuffer = true;
            // Clear alt buffer
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < columns; c++)
                    altGrid[r][c].reset();
            activeGrid = altGrid;
            markAllDirty();
        }
    }

    public void switchToMainBuffer() {
        if (usingAltBuffer) {
            usingAltBuffer = false;
            activeGrid = mainGrid;
            markAllDirty();
        }
    }

    // --- Dirty tracking ---

    public void markDirty(int row) {
        dirtyRows.set(row);
        dirty = true;
    }

    public void markAllDirty() {
        dirtyRows.set(0, rows);
        dirty = true;
    }

    public boolean isDirty() { return dirty; }
    public boolean isDirtyRow(int row) { return dirtyRows.get(row); }

    public void clearDirty() {
        dirtyRows.clear();
        dirty = false;
    }

    // --- Text extraction ---

    public String getText(int startRow, int startCol, int endRow, int endCol) {
        var sb = new StringBuilder();
        for (int r = startRow; r <= endRow; r++) {
            int cStart = (r == startRow) ? startCol : 0;
            int cEnd = (r == endRow) ? endCol : columns;
            for (int c = cStart; c < cEnd; c++) {
                int cp = activeGrid[r][c].getCodepoint();
                if (!activeGrid[r][c].isWidthContinuation()) {
                    sb.appendCodePoint(cp);
                }
            }
            if (r < endRow) sb.append('\n');
        }
        return sb.toString();
    }

    // --- Resize ---

    public void resize(int newCols, int newRows) {
        var newGrid = createGrid(newCols, newRows);
        int copyRows = Math.min(rows, newRows);
        int copyCols = Math.min(columns, newCols);
        for (int r = 0; r < copyRows; r++)
            for (int c = 0; c < copyCols; c++)
                newGrid[r][c].copyFrom(activeGrid[r][c]);

        this.columns = newCols;
        this.rows = newRows;
        if (usingAltBuffer) {
            altGrid = newGrid;
            activeGrid = altGrid;
        } else {
            mainGrid = newGrid;
            activeGrid = mainGrid;
        }
        // Reset alt buffer on resize too
        if (!usingAltBuffer) altGrid = createGrid(newCols, newRows);
        else mainGrid = createGrid(newCols, newRows);

        scrollTop = 0;
        scrollBottom = newRows - 1;
        setCursorPosition(Math.min(cursorRow, newRows - 1), Math.min(cursorCol, newCols - 1));
        markAllDirty();
    }

    // --- Accessors ---

    public TerminalCell getCell(int row, int col) { return activeGrid[row][col]; }
    public TerminalCell getCurrentAttributes() { return currentAttributes; }
    public int getColumns() { return columns; }
    public int getRows() { return rows; }
    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }
    public void setCursorRow(int row) { this.cursorRow = Math.max(0, Math.min(row, rows - 1)); }
    public void setCursorCol(int col) { this.cursorCol = Math.max(0, Math.min(col, columns - 1)); }
    public boolean isCursorVisible() { return cursorVisible; }
    public void setCursorVisible(boolean visible) { this.cursorVisible = visible; }
    public int getScrollbackSize() { return scrollback.size(); }
    public TerminalCell[] getScrollbackRow(int index) { return scrollback.get(index); }
    public int getScrollTop() { return scrollTop; }
    public int getScrollBottom() { return scrollBottom; }
    public Object getLock() { return this; }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.terminal.TerminalBufferTest" -v`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/TerminalBuffer.java \
        claude-fx/src/test/java/com/claudefx/terminal/TerminalBufferTest.java
git commit -m "feat(claude-fx): add TerminalBuffer with grid, scrollback, cursor, dirty tracking"
```

---

### Task 4: AnsiParser

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/terminal/AnsiParser.java`
- Create: `claude-fx/src/test/java/com/claudefx/terminal/AnsiParserTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.claudefx.terminal;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnsiParserTest {

    private TerminalBuffer buffer;
    private AnsiParser parser;

    @BeforeEach
    void setUp() {
        buffer = new TerminalBuffer(80, 24, 100);
        parser = new AnsiParser(buffer);
    }

    @Test
    void plainTextWrittenToBuffer() {
        parser.process("Hello".getBytes());
        assertThat(buffer.getCell(0, 0).getCodepoint()).isEqualTo('H');
        assertThat(buffer.getCell(0, 4).getCodepoint()).isEqualTo('o');
        assertThat(buffer.getCursorCol()).isEqualTo(5);
    }

    @Test
    void newlineAndCarriageReturn() {
        parser.process("AB\r\nCD".getBytes());
        assertThat(buffer.getCell(0, 0).getCodepoint()).isEqualTo('A');
        assertThat(buffer.getCell(1, 0).getCodepoint()).isEqualTo('C');
    }

    @Test
    void cursorUpSequence() {
        parser.process("Hello\n".getBytes());
        parser.process("\033[1A".getBytes());
        assertThat(buffer.getCursorRow()).isEqualTo(0);
    }

    @Test
    void cursorPositionSequence() {
        parser.process("\033[5;10H".getBytes());
        assertThat(buffer.getCursorRow()).isEqualTo(4); // 1-based to 0-based
        assertThat(buffer.getCursorCol()).isEqualTo(9);
    }

    @Test
    void sgrBoldAttribute() {
        parser.process("\033[1mX".getBytes());
        assertThat(buffer.getCell(0, 0).isBold()).isTrue();
    }

    @Test
    void sgrResetClearsAttributes() {
        parser.process("\033[1m\033[0mX".getBytes());
        assertThat(buffer.getCell(0, 0).isBold()).isFalse();
    }

    @Test
    void sgr256ColorForeground() {
        parser.process("\033[38;5;196mX".getBytes()); // color index 196 = bright red
        var cell = buffer.getCell(0, 0);
        assertThat(cell.getForeground()).isNotEqualTo(TerminalCell.getDefaultForeground());
    }

    @Test
    void sgr24BitColorForeground() {
        parser.process("\033[38;2;255;128;0mX".getBytes());
        var cell = buffer.getCell(0, 0);
        assertThat(cell.getForeground()).isEqualTo(Color.rgb(255, 128, 0));
    }

    @Test
    void eraseDisplaySequence() {
        parser.process("ABCDE".getBytes());
        parser.process("\033[2J".getBytes());
        assertThat(buffer.getCell(0, 0).getCodepoint()).isEqualTo(' ');
    }

    @Test
    void eraseLineFromCursor() {
        parser.process("ABCDE".getBytes());
        parser.process("\033[3D".getBytes()); // move back 3
        parser.process("\033[0K".getBytes());  // erase to end
        assertThat(buffer.getCell(0, 0).getCodepoint()).isEqualTo('A');
        assertThat(buffer.getCell(0, 1).getCodepoint()).isEqualTo('B');
        assertThat(buffer.getCell(0, 2).getCodepoint()).isEqualTo(' ');
    }

    @Test
    void alternateBufferSwitch() {
        parser.process("Main".getBytes());
        parser.process("\033[?1049h".getBytes()); // switch to alt
        assertThat(buffer.getCell(0, 0).getCodepoint()).isEqualTo(' ');
        parser.process("\033[?1049l".getBytes()); // switch to main
        assertThat(buffer.getCell(0, 0).getCodepoint()).isEqualTo('M');
    }

    @Test
    void oscSetsTitle() {
        parser.process("\033]0;My Title\007".getBytes());
        assertThat(parser.getLastTitle()).isEqualTo("My Title");
    }

    @Test
    void scrollRegionSet() {
        parser.process("\033[2;10r".getBytes());
        assertThat(buffer.getScrollTop()).isEqualTo(1);  // 1-based to 0-based
        assertThat(buffer.getScrollBottom()).isEqualTo(9);
    }

    @Test
    void splitEscapeSequenceAcrossChunks() {
        parser.process("\033[".getBytes());
        parser.process("1m".getBytes());
        parser.process("X".getBytes());
        assertThat(buffer.getCell(0, 0).isBold()).isTrue();
    }

    @Test
    void cursorVisibilityToggle() {
        parser.process("\033[?25l".getBytes()); // hide cursor
        assertThat(buffer.isCursorVisible()).isFalse();
        parser.process("\033[?25h".getBytes()); // show cursor
        assertThat(buffer.isCursorVisible()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.terminal.AnsiParserTest" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

```java
package com.claudefx.terminal;

import javafx.scene.paint.Color;

public class AnsiParser {

    private enum State { NORMAL, ESCAPE, CSI_PARAM, CSI_INTERMEDIATE, OSC }

    private static final Color[] STANDARD_COLORS = {
        Color.rgb(0, 0, 0),       Color.rgb(205, 49, 49),
        Color.rgb(13, 188, 121),   Color.rgb(229, 229, 16),
        Color.rgb(36, 114, 200),   Color.rgb(188, 63, 188),
        Color.rgb(17, 168, 205),   Color.rgb(229, 229, 229),
        Color.rgb(102, 102, 102),  Color.rgb(241, 76, 76),
        Color.rgb(35, 209, 139),   Color.rgb(245, 245, 67),
        Color.rgb(59, 142, 234),   Color.rgb(214, 112, 214),
        Color.rgb(41, 184, 219),   Color.rgb(255, 255, 255)
    };

    private final TerminalBuffer buffer;
    private State state = State.NORMAL;
    private final StringBuilder csiParams = new StringBuilder();
    private final StringBuilder oscPayload = new StringBuilder();
    private String lastTitle = "";

    public AnsiParser(TerminalBuffer buffer) {
        this.buffer = buffer;
    }

    public void process(byte[] data) {
        for (byte b : data) {
            processChar(b & 0xFF);
        }
    }

    private void processChar(int ch) {
        switch (state) {
            case NORMAL -> processNormal(ch);
            case ESCAPE -> processEscape(ch);
            case CSI_PARAM, CSI_INTERMEDIATE -> processCsi(ch);
            case OSC -> processOsc(ch);
        }
    }

    private void processNormal(int ch) {
        switch (ch) {
            case 0x1B -> state = State.ESCAPE;
            case '\n' -> buffer.lineFeed();
            case '\r' -> buffer.carriageReturn();
            case '\t' -> {
                int nextTab = (buffer.getCursorCol() / 8 + 1) * 8;
                buffer.setCursorCol(Math.min(nextTab, buffer.getColumns() - 1));
            }
            case '\b' -> buffer.moveCursorBackward(1);
            case 0x07 -> {} // bell, ignore
            default -> {
                if (ch >= 0x20) {
                    buffer.putChar(ch);
                }
            }
        }
    }

    private void processEscape(int ch) {
        switch (ch) {
            case '[' -> {
                state = State.CSI_PARAM;
                csiParams.setLength(0);
            }
            case ']' -> {
                state = State.OSC;
                oscPayload.setLength(0);
            }
            case '(' -> state = State.NORMAL; // charset designation, ignore
            case 'c' -> { // full reset
                buffer.eraseDisplay(2);
                buffer.setCursorPosition(0, 0);
                state = State.NORMAL;
            }
            default -> state = State.NORMAL; // unrecognized, return to normal
        }
    }

    private void processCsi(int ch) {
        if (ch >= 0x30 && ch <= 0x3F) {
            // Parameter byte (digits, semicolons, question mark)
            csiParams.append((char) ch);
        } else if (ch >= 0x20 && ch <= 0x2F) {
            // Intermediate byte
            csiParams.append((char) ch);
            state = State.CSI_INTERMEDIATE;
        } else if (ch >= 0x40 && ch <= 0x7E) {
            // Final byte — dispatch
            dispatchCsi((char) ch);
            state = State.NORMAL;
        } else {
            // Invalid, abort
            state = State.NORMAL;
        }
    }

    private void dispatchCsi(char finalChar) {
        String params = csiParams.toString();

        // Handle DEC private modes
        if (params.startsWith("?")) {
            handleDecPrivateMode(params.substring(1), finalChar);
            return;
        }

        int[] args = parseArgs(params);

        switch (finalChar) {
            case 'A' -> buffer.moveCursorUp(Math.max(1, arg(args, 0, 1)));
            case 'B' -> buffer.moveCursorDown(Math.max(1, arg(args, 0, 1)));
            case 'C' -> buffer.moveCursorForward(Math.max(1, arg(args, 0, 1)));
            case 'D' -> buffer.moveCursorBackward(Math.max(1, arg(args, 0, 1)));
            case 'H', 'f' -> buffer.setCursorPosition(arg(args, 0, 1) - 1, arg(args, 1, 1) - 1);
            case 'J' -> buffer.eraseDisplay(arg(args, 0, 0));
            case 'K' -> buffer.eraseLine(arg(args, 0, 0));
            case 'm' -> handleSgr(args);
            case 'r' -> {
                if (args.length >= 2) {
                    buffer.setScrollRegion(args[0] - 1, args[1] - 1);
                } else {
                    buffer.resetScrollRegion();
                }
            }
            case 'd' -> buffer.setCursorRow(arg(args, 0, 1) - 1);
            case 'G' -> buffer.setCursorCol(arg(args, 0, 1) - 1);
            // Other sequences silently ignored
        }
    }

    private void handleDecPrivateMode(String params, char finalChar) {
        int mode = 0;
        try { mode = Integer.parseInt(params); } catch (NumberFormatException e) { return; }

        boolean set = (finalChar == 'h');

        switch (mode) {
            case 25 -> buffer.setCursorVisible(set);
            case 1049 -> {
                if (set) buffer.switchToAlternateBuffer();
                else buffer.switchToMainBuffer();
            }
        }
    }

    private void handleSgr(int[] args) {
        if (args.length == 0) {
            buffer.getCurrentAttributes().reset();
            return;
        }

        var attrs = buffer.getCurrentAttributes();
        for (int i = 0; i < args.length; i++) {
            int code = args[i];
            switch (code) {
                case 0 -> attrs.reset();
                case 1 -> attrs.setBold(true);
                case 3 -> attrs.setItalic(true);
                case 4 -> attrs.setUnderline(true);
                case 7 -> attrs.setInverse(true);
                case 9 -> attrs.setStrikethrough(true);
                case 22 -> attrs.setBold(false);
                case 23 -> attrs.setItalic(false);
                case 24 -> attrs.setUnderline(false);
                case 27 -> attrs.setInverse(false);
                case 29 -> attrs.setStrikethrough(false);
                case 30, 31, 32, 33, 34, 35, 36, 37 ->
                    attrs.setForeground(STANDARD_COLORS[code - 30]);
                case 38 -> {
                    i = handleExtendedColor(args, i, true);
                }
                case 39 -> attrs.setForeground(TerminalCell.getDefaultForeground());
                case 40, 41, 42, 43, 44, 45, 46, 47 ->
                    attrs.setBackground(STANDARD_COLORS[code - 40]);
                case 48 -> {
                    i = handleExtendedColor(args, i, false);
                }
                case 49 -> attrs.setBackground(TerminalCell.getDefaultBackground());
                case 90, 91, 92, 93, 94, 95, 96, 97 ->
                    attrs.setForeground(STANDARD_COLORS[code - 90 + 8]);
                case 100, 101, 102, 103, 104, 105, 106, 107 ->
                    attrs.setBackground(STANDARD_COLORS[code - 100 + 8]);
            }
        }
    }

    private int handleExtendedColor(int[] args, int i, boolean foreground) {
        if (i + 1 >= args.length) return i;
        var attrs = buffer.getCurrentAttributes();

        if (args[i + 1] == 5 && i + 2 < args.length) {
            // 256 color mode
            Color color = color256(args[i + 2]);
            if (foreground) attrs.setForeground(color);
            else attrs.setBackground(color);
            return i + 2;
        } else if (args[i + 1] == 2 && i + 4 < args.length) {
            // 24-bit RGB
            Color color = Color.rgb(
                Math.min(255, Math.max(0, args[i + 2])),
                Math.min(255, Math.max(0, args[i + 3])),
                Math.min(255, Math.max(0, args[i + 4]))
            );
            if (foreground) attrs.setForeground(color);
            else attrs.setBackground(color);
            return i + 4;
        }
        return i;
    }

    private Color color256(int index) {
        if (index < 16) return STANDARD_COLORS[index];
        if (index < 232) {
            index -= 16;
            int r = (index / 36) * 51;
            int g = ((index / 6) % 6) * 51;
            int b = (index % 6) * 51;
            return Color.rgb(r, g, b);
        }
        // Grayscale
        int gray = 8 + (index - 232) * 10;
        return Color.rgb(gray, gray, gray);
    }

    private void processOsc(int ch) {
        if (ch == 0x07 || ch == 0x1B) { // BEL or ESC terminates OSC
            dispatchOsc();
            state = (ch == 0x1B) ? State.ESCAPE : State.NORMAL;
        } else {
            oscPayload.append((char) ch);
        }
    }

    private void dispatchOsc() {
        String payload = oscPayload.toString();
        int semi = payload.indexOf(';');
        if (semi < 0) return;
        String code = payload.substring(0, semi);
        String text = payload.substring(semi + 1);
        if ("0".equals(code) || "2".equals(code)) {
            lastTitle = text;
        }
    }

    // --- Helpers ---

    private int[] parseArgs(String params) {
        if (params.isEmpty()) return new int[0];
        String[] parts = params.split(";");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    private int arg(int[] args, int index, int defaultValue) {
        if (index >= args.length || args[index] == 0) return defaultValue;
        return args[index];
    }

    public String getLastTitle() { return lastTitle; }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.terminal.AnsiParserTest" -v`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/AnsiParser.java \
        claude-fx/src/test/java/com/claudefx/terminal/AnsiParserTest.java
git commit -m "feat(claude-fx): add AnsiParser with CSI, SGR, OSC, alt buffer support"
```

---

### Task 5: Config System

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/config/AppConfig.java`
- Create: `claude-fx/src/main/java/com/claudefx/config/ColorScheme.java`
- Create: `claude-fx/src/main/java/com/claudefx/config/KeyBindings.java`
- Create: `claude-fx/src/main/java/com/claudefx/config/ConfigStore.java`
- Create: `claude-fx/src/main/resources/schemes/dark.json`
- Create: `claude-fx/src/main/resources/schemes/light.json`
- Create: `claude-fx/src/test/java/com/claudefx/config/AppConfigTest.java`
- Create: `claude-fx/src/test/java/com/claudefx/config/ConfigStoreTest.java`

- [ ] **Step 1: Write failing tests for AppConfig serialization**

```java
package com.claudefx.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    @Test
    void defaultConfigHasExpectedValues() {
        var config = AppConfig.defaults();
        assertThat(config.getClaudeBinary()).isEqualTo("claude");
        assertThat(config.getTerminal().getFontSize()).isEqualTo(14);
        assertThat(config.getTerminal().getScrollbackLines()).isEqualTo(10000);
    }

    @Test
    void roundTripSerialization() throws Exception {
        var mapper = new ObjectMapper();
        var config = AppConfig.defaults();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        var restored = mapper.readValue(json, AppConfig.class);
        assertThat(restored.getClaudeBinary()).isEqualTo(config.getClaudeBinary());
        assertThat(restored.getTerminal().getFontFamily()).isEqualTo(config.getTerminal().getFontFamily());
        assertThat(restored.getKeyBindings().getNewTab()).isEqualTo(config.getKeyBindings().getNewTab());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.config.AppConfigTest" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Write AppConfig, KeyBindings, ColorScheme POJOs**

`AppConfig.java`:
```java
package com.claudefx.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    private String claudeBinary = "claude";
    private List<String> defaultArgs = List.of();
    private String defaultWorkingDir = System.getProperty("user.home");
    private TerminalConfig terminal = new TerminalConfig();
    private KeyBindings keyBindings = KeyBindings.platformDefaults();
    private WindowConfig window = new WindowConfig();

    public static AppConfig defaults() { return new AppConfig(); }

    // --- nested classes ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TerminalConfig {
        private String fontFamily = "JetBrains Mono";
        private int fontSize = 14;
        private int scrollbackLines = 10000;
        private String cursorStyle = "block";
        private boolean cursorBlink = true;
        private String colorScheme = "dark";

        public String getFontFamily() { return fontFamily; }
        public void setFontFamily(String f) { this.fontFamily = f; }
        public int getFontSize() { return fontSize; }
        public void setFontSize(int s) { this.fontSize = s; }
        public int getScrollbackLines() { return scrollbackLines; }
        public void setScrollbackLines(int n) { this.scrollbackLines = n; }
        public String getCursorStyle() { return cursorStyle; }
        public void setCursorStyle(String s) { this.cursorStyle = s; }
        public boolean isCursorBlink() { return cursorBlink; }
        public void setCursorBlink(boolean b) { this.cursorBlink = b; }
        public String getColorScheme() { return colorScheme; }
        public void setColorScheme(String s) { this.colorScheme = s; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WindowConfig {
        private int width = 1200;
        private int height = 800;
        private Integer x;
        private Integer y;
        private boolean maximized = false;

        public int getWidth() { return width; }
        public void setWidth(int w) { this.width = w; }
        public int getHeight() { return height; }
        public void setHeight(int h) { this.height = h; }
        public Integer getX() { return x; }
        public void setX(Integer x) { this.x = x; }
        public Integer getY() { return y; }
        public void setY(Integer y) { this.y = y; }
        public boolean isMaximized() { return maximized; }
        public void setMaximized(boolean m) { this.maximized = m; }
    }

    // --- getters/setters ---
    public String getClaudeBinary() { return claudeBinary; }
    public void setClaudeBinary(String b) { this.claudeBinary = b; }
    public List<String> getDefaultArgs() { return defaultArgs; }
    public void setDefaultArgs(List<String> a) { this.defaultArgs = a; }
    public String getDefaultWorkingDir() { return defaultWorkingDir; }
    public void setDefaultWorkingDir(String d) { this.defaultWorkingDir = d; }
    public TerminalConfig getTerminal() { return terminal; }
    public void setTerminal(TerminalConfig t) { this.terminal = t; }
    public KeyBindings getKeyBindings() { return keyBindings; }
    public void setKeyBindings(KeyBindings k) { this.keyBindings = k; }
    public WindowConfig getWindow() { return window; }
    public void setWindow(WindowConfig w) { this.window = w; }
}
```

`KeyBindings.java`:
```java
package com.claudefx.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyBindings {
    private String newTab;
    private String closeTab;
    private String nextTab;
    private String prevTab;
    private String search;
    private String copy;
    private String paste;
    private String zoomIn;
    private String zoomOut;
    private String exportLog;
    private String settings;

    public static KeyBindings platformDefaults() {
        var kb = new KeyBindings();
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        String mod = mac ? "Meta" : "Ctrl";
        String modShift = mac ? "Meta+Shift" : "Ctrl+Shift";

        kb.newTab = mod + "+T";
        kb.closeTab = mod + "+W";
        kb.nextTab = "Ctrl+Tab";
        kb.prevTab = "Ctrl+Shift+Tab";
        kb.search = mod + "+F";
        kb.copy = mac ? "Meta+C" : "Ctrl+Shift+C";
        kb.paste = mac ? "Meta+V" : "Ctrl+Shift+V";
        kb.zoomIn = mod + "+Plus";
        kb.zoomOut = mod + "+Minus";
        kb.exportLog = modShift + "+S";
        kb.settings = mod + "+Comma";
        return kb;
    }

    // --- getters/setters ---
    public String getNewTab() { return newTab; }
    public void setNewTab(String s) { this.newTab = s; }
    public String getCloseTab() { return closeTab; }
    public void setCloseTab(String s) { this.closeTab = s; }
    public String getNextTab() { return nextTab; }
    public void setNextTab(String s) { this.nextTab = s; }
    public String getPrevTab() { return prevTab; }
    public void setPrevTab(String s) { this.prevTab = s; }
    public String getSearch() { return search; }
    public void setSearch(String s) { this.search = s; }
    public String getCopy() { return copy; }
    public void setCopy(String s) { this.copy = s; }
    public String getPaste() { return paste; }
    public void setPaste(String s) { this.paste = s; }
    public String getZoomIn() { return zoomIn; }
    public void setZoomIn(String s) { this.zoomIn = s; }
    public String getZoomOut() { return zoomOut; }
    public void setZoomOut(String s) { this.zoomOut = s; }
    public String getExportLog() { return exportLog; }
    public void setExportLog(String s) { this.exportLog = s; }
    public String getSettings() { return settings; }
    public void setSettings(String s) { this.settings = s; }
}
```

`ColorScheme.java`:
```java
package com.claudefx.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ColorScheme {
    private String name;
    private String foreground;
    private String background;
    private String cursorColor;
    private String selectionBackground;
    private List<String> ansi;

    // --- getters/setters ---
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getForeground() { return foreground; }
    public void setForeground(String f) { this.foreground = f; }
    public String getBackground() { return background; }
    public void setBackground(String b) { this.background = b; }
    public String getCursorColor() { return cursorColor; }
    public void setCursorColor(String c) { this.cursorColor = c; }
    public String getSelectionBackground() { return selectionBackground; }
    public void setSelectionBackground(String s) { this.selectionBackground = s; }
    public List<String> getAnsi() { return ansi; }
    public void setAnsi(List<String> a) { this.ansi = a; }
}
```

- [ ] **Step 4: Write SessionState POJO** (needed by ConfigStore)

```java
package com.claudefx.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionState {
    private List<TabState> tabs = List.of();
    private int activeTabIndex;
    private AppConfig.WindowConfig window = new AppConfig.WindowConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TabState {
        private String workingDir;
        private String title;
        private int position;

        public String getWorkingDir() { return workingDir; }
        public void setWorkingDir(String d) { this.workingDir = d; }
        public String getTitle() { return title; }
        public void setTitle(String t) { this.title = t; }
        public int getPosition() { return position; }
        public void setPosition(int p) { this.position = p; }
    }

    public List<TabState> getTabs() { return tabs; }
    public void setTabs(List<TabState> t) { this.tabs = t; }
    public int getActiveTabIndex() { return activeTabIndex; }
    public void setActiveTabIndex(int i) { this.activeTabIndex = i; }
    public AppConfig.WindowConfig getWindow() { return window; }
    public void setWindow(AppConfig.WindowConfig w) { this.window = w; }
}
```

- [ ] **Step 5: Write ConfigStore**

```java
package com.claudefx.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configDir;

    public ConfigStore(Path configDir) {
        this.configDir = configDir;
    }

    public static ConfigStore defaultStore() {
        return new ConfigStore(Path.of(System.getProperty("user.home"), ".claude-fx"));
    }

    public AppConfig loadConfig() {
        Path file = configDir.resolve("config.json");
        if (Files.exists(file)) {
            try {
                return MAPPER.readValue(file.toFile(), AppConfig.class);
            } catch (IOException e) {
                System.err.println("Warning: corrupted config.json, using defaults: " + e.getMessage());
            }
        }
        return AppConfig.defaults();
    }

    public void saveConfig(AppConfig config) throws IOException {
        Files.createDirectories(configDir);
        MAPPER.writeValue(configDir.resolve("config.json").toFile(), config);
    }

    public SessionState loadSessions() {
        Path file = configDir.resolve("sessions.json");
        if (Files.exists(file)) {
            try {
                return MAPPER.readValue(file.toFile(), SessionState.class);
            } catch (IOException e) {
                System.err.println("Warning: corrupted sessions.json: " + e.getMessage());
            }
        }
        return null;
    }

    public void saveSessions(SessionState state) throws IOException {
        Files.createDirectories(configDir);
        MAPPER.writeValue(configDir.resolve("sessions.json").toFile(), state);
    }

    public ColorScheme loadColorScheme(String name) {
        // Check user schemes first
        Path userScheme = configDir.resolve("schemes").resolve(name + ".json");
        if (Files.exists(userScheme)) {
            try { return MAPPER.readValue(userScheme.toFile(), ColorScheme.class); }
            catch (IOException e) { /* fall through to built-in */ }
        }
        // Load built-in from classpath
        try (var stream = getClass().getResourceAsStream("/schemes/" + name + ".json")) {
            if (stream != null) return MAPPER.readValue(stream, ColorScheme.class);
        } catch (IOException e) { /* fall through */ }
        return null;
    }

    public Path getConfigDir() { return configDir; }
}
```

- [ ] **Step 5: Write ConfigStore tests**

```java
package com.claudefx.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {

    @TempDir Path tempDir;

    @Test
    void saveAndLoadConfig() throws Exception {
        var store = new ConfigStore(tempDir);
        var config = AppConfig.defaults();
        config.setClaudeBinary("/usr/local/bin/claude");

        store.saveConfig(config);
        var loaded = store.loadConfig();

        assertThat(loaded.getClaudeBinary()).isEqualTo("/usr/local/bin/claude");
    }

    @Test
    void loadMissingConfigReturnsDefaults() {
        var store = new ConfigStore(tempDir.resolve("nonexistent"));
        var config = store.loadConfig();
        assertThat(config.getClaudeBinary()).isEqualTo("claude");
    }

    @Test
    void loadBuiltInDarkScheme() {
        var store = new ConfigStore(tempDir);
        var scheme = store.loadColorScheme("dark");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getName()).isEqualTo("dark");
        assertThat(scheme.getAnsi()).hasSize(16);
    }
}
```

- [ ] **Step 6: Create built-in scheme files**

`src/main/resources/schemes/dark.json`:
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

`src/main/resources/schemes/light.json`:
```json
{
  "name": "light",
  "foreground": "#333333",
  "background": "#ffffff",
  "cursorColor": "#000000",
  "selectionBackground": "#add6ff",
  "ansi": [
    "#000000", "#cd3131", "#00bc00", "#949800",
    "#0451a5", "#bc05bc", "#0598bc", "#555555",
    "#666666", "#cd3131", "#14ce14", "#b5ba00",
    "#0451a5", "#bc05bc", "#0598bc", "#a5a5a5"
  ]
}
```

- [ ] **Step 7: Run all config tests**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.config.*" -v`
Expected: All tests PASS

- [ ] **Step 8: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/config/ \
        claude-fx/src/main/resources/schemes/ \
        claude-fx/src/test/java/com/claudefx/config/
git commit -m "feat(claude-fx): add config system with AppConfig, ColorScheme, ConfigStore"
```

---

### Task 6: PtyBridge and ClaudeProcess

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/process/PtyBridge.java`
- Create: `claude-fx/src/main/java/com/claudefx/process/ClaudeProcess.java`
- Create: `claude-fx/src/test/java/com/claudefx/process/PtyBridgeTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.claudefx.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PtyBridgeTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void spawnEchoProcessAndReadOutput() throws Exception {
        var latch = new CountDownLatch(1);
        var output = new StringBuilder();

        var pty = new PtyBridge("/bin/echo", new String[]{"hello-pty"}, "/tmp",
                data -> {
                    output.append(new String(data));
                    if (output.toString().contains("hello-pty")) latch.countDown();
                });

        pty.start();
        boolean received = latch.await(5, TimeUnit.SECONDS);
        pty.stop();

        assertThat(received).isTrue();
        assertThat(output.toString()).contains("hello-pty");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void processExitIsDetected() throws Exception {
        var latch = new CountDownLatch(1);

        var pty = new PtyBridge("/bin/true", new String[0], "/tmp", data -> {});
        pty.setOnExit(exitCode -> latch.countDown());
        pty.start();

        boolean exited = latch.await(5, TimeUnit.SECONDS);
        assertThat(exited).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.process.PtyBridgeTest" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Write PtyBridge**

```java
package com.claudefx.process;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PtyBridge {

    private final String command;
    private final String[] args;
    private final String workingDir;
    private final Consumer<byte[]> onOutput;
    private IntConsumer onExit;

    private PtyProcess process;
    private Thread readerThread;

    public PtyBridge(String command, String[] args, String workingDir, Consumer<byte[]> onOutput) {
        this.command = command;
        this.args = args;
        this.workingDir = workingDir;
        this.onOutput = onOutput;
    }

    public void start() throws IOException {
        String[] fullCmd = new String[args.length + 1];
        fullCmd[0] = command;
        System.arraycopy(args, 0, fullCmd, 1, args.length);

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        process = new PtyProcessBuilder(fullCmd)
                .setDirectory(workingDir)
                .setEnvironment(env)
                .setInitialColumns(80)
                .setInitialRows(24)
                .start();

        readerThread = new Thread(() -> readLoop(process.getInputStream()), "pty-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Monitor exit
        var exitThread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                if (onExit != null) onExit.accept(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pty-exit-monitor");
        exitThread.setDaemon(true);
        exitThread.start();
    }

    private void readLoop(InputStream in) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                onOutput.accept(chunk);
            }
        } catch (IOException e) {
            // PTY closed, normal on process exit
        }
    }

    public void write(byte[] data) throws IOException {
        if (process != null) {
            OutputStream out = process.getOutputStream();
            out.write(data);
            out.flush();
        }
    }

    public void resize(int cols, int rows) {
        if (process != null && process.isAlive()) {
            process.setWinSize(new com.pty4j.WinSize(cols, rows));
        }
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void setOnExit(IntConsumer onExit) { this.onExit = onExit; }
}
```

- [ ] **Step 4: Write ClaudeProcess**

```java
package com.claudefx.process;

import com.claudefx.config.AppConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class ClaudeProcess {

    public enum Status { RUNNING, EXITED }

    private final AppConfig config;
    private final String workingDir;
    private PtyBridge pty;
    private Status status = Status.EXITED;
    private int exitCode = -1;
    private IntConsumer onExit;

    public ClaudeProcess(AppConfig config, String workingDir) {
        this.config = config;
        this.workingDir = workingDir;
    }

    public void start(Consumer<byte[]> onOutput) throws IOException {
        var argsList = new ArrayList<>(config.getDefaultArgs());
        String[] args = argsList.toArray(new String[0]);

        pty = new PtyBridge(config.getClaudeBinary(), args, workingDir, onOutput);
        pty.setOnExit(code -> {
            this.exitCode = code;
            this.status = Status.EXITED;
            if (onExit != null) onExit.accept(code);
        });
        pty.start();
        status = Status.RUNNING;
    }

    public void stop() {
        if (pty != null) pty.stop();
        status = Status.EXITED;
    }

    public void write(byte[] data) throws IOException {
        if (pty != null) pty.write(data);
    }

    public void resize(int cols, int rows) {
        if (pty != null) pty.resize(cols, rows);
    }

    public Status getStatus() { return status; }
    public int getExitCode() { return exitCode; }
    public boolean isAlive() { return pty != null && pty.isAlive(); }
    public String getWorkingDir() { return workingDir; }
    public void setOnExit(IntConsumer onExit) { this.onExit = onExit; }
}
```

- [ ] **Step 5: Run tests**

Run: `cd claude-fx && ./gradlew test --tests "com.claudefx.process.PtyBridgeTest" -v`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/process/ \
        claude-fx/src/test/java/com/claudefx/process/
git commit -m "feat(claude-fx): add PtyBridge and ClaudeProcess for PTY subprocess management"
```

---

### Task 7: TerminalRenderer

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/terminal/TerminalRenderer.java`

This is a visual component — testing is integration-level (verified in Task 9). Focus on correct implementation.

- [ ] **Step 1: Write TerminalRenderer**

```java
package com.claudefx.terminal;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class TerminalRenderer {

    private final Canvas canvas;
    private final TerminalBuffer buffer;
    private Font normalFont;
    private Font boldFont;
    private double cellWidth;
    private double cellHeight;
    private double fontAscent;

    private boolean cursorBlinkOn = true;
    private long lastBlinkToggle = 0;
    private static final long BLINK_INTERVAL_NS = 500_000_000L; // 500ms

    private boolean cursorBlink = true;
    private String cursorStyle = "block";

    // Selection state
    private int selStartRow = -1, selStartCol = -1;
    private int selEndRow = -1, selEndCol = -1;
    private Color selectionColor = Color.rgb(38, 79, 120, 0.6);

    private final AnimationTimer timer;

    public TerminalRenderer(Canvas canvas, TerminalBuffer buffer) {
        this.canvas = canvas;
        this.buffer = buffer;
        setFont("JetBrains Mono", 14);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                boolean blinkChanged = false;
                if (cursorBlink && now - lastBlinkToggle > BLINK_INTERVAL_NS) {
                    cursorBlinkOn = !cursorBlinkOn;
                    lastBlinkToggle = now;
                    blinkChanged = true;
                }
                if (buffer.isDirty() || blinkChanged) {
                    render();
                    buffer.clearDirty();
                }
            }
        };
    }

    public void start() { timer.start(); }
    public void stop() { timer.stop(); }

    public void setFont(String family, int size) {
        normalFont = Font.font(family, FontWeight.NORMAL, size);
        boldFont = Font.font(family, FontWeight.BOLD, size);
        // Measure cell size using a temporary text
        var gc = canvas.getGraphicsContext2D();
        gc.setFont(normalFont);
        // Approximate monospace cell dimensions
        cellWidth = size * 0.6;
        cellHeight = size * 1.2;
        fontAscent = size * 0.9;
    }

    public void render() {
        var gc = canvas.getGraphicsContext2D();
        synchronized (buffer.getLock()) {
            for (int r = 0; r < buffer.getRows(); r++) {
                renderRow(gc, r);
            }
            renderCursor(gc);
        }
    }

    private void renderRow(GraphicsContext gc, int row) {
        double y = row * cellHeight;
        for (int col = 0; col < buffer.getColumns(); col++) {
            var cell = buffer.getCell(row, col);
            if (cell.isWidthContinuation()) continue;

            double x = col * cellWidth;
            double w = cell.isWideChar() ? cellWidth * 2 : cellWidth;

            // Background
            Color bg = cell.isInverse() ? cell.getForeground() : cell.getBackground();
            gc.setFill(bg);
            gc.fillRect(x, y, w, cellHeight);

            // Selection highlight
            if (isSelected(row, col)) {
                gc.setFill(selectionColor);
                gc.fillRect(x, y, w, cellHeight);
            }

            // Character
            int cp = cell.getCodepoint();
            if (cp > ' ') {
                Color fg = cell.isInverse() ? cell.getBackground() : cell.getForeground();
                gc.setFill(fg);
                gc.setFont(cell.isBold() ? boldFont : normalFont);
                String ch = new String(Character.toChars(cp));
                gc.fillText(ch, x, y + fontAscent);

                if (cell.isUnderline()) {
                    gc.setStroke(fg);
                    gc.setLineWidth(1);
                    gc.strokeLine(x, y + cellHeight - 1, x + w, y + cellHeight - 1);
                }
                if (cell.isStrikethrough()) {
                    gc.setStroke(fg);
                    gc.setLineWidth(1);
                    gc.strokeLine(x, y + cellHeight / 2, x + w, y + cellHeight / 2);
                }
            }
        }
    }

    private void renderCursor(GraphicsContext gc) {
        if (!buffer.isCursorVisible()) return;
        if (cursorBlink && !cursorBlinkOn) return;

        int row = buffer.getCursorRow();
        int col = buffer.getCursorCol();
        double x = col * cellWidth;
        double y = row * cellHeight;

        gc.setFill(Color.WHITE);
        switch (cursorStyle) {
            case "block" -> gc.fillRect(x, y, cellWidth, cellHeight);
            case "underline" -> gc.fillRect(x, y + cellHeight - 2, cellWidth, 2);
            case "bar" -> gc.fillRect(x, y, 2, cellHeight);
        }
    }

    private boolean isSelected(int row, int col) {
        if (selStartRow < 0) return false;
        int startPos = selStartRow * buffer.getColumns() + selStartCol;
        int endPos = selEndRow * buffer.getColumns() + selEndCol;
        if (startPos > endPos) { int tmp = startPos; startPos = endPos; endPos = tmp; }
        int pos = row * buffer.getColumns() + col;
        return pos >= startPos && pos <= endPos;
    }

    public void setSelection(int startRow, int startCol, int endRow, int endCol) {
        this.selStartRow = startRow; this.selStartCol = startCol;
        this.selEndRow = endRow; this.selEndCol = endCol;
        buffer.markAllDirty();
    }

    public void clearSelection() {
        selStartRow = selStartCol = selEndRow = selEndCol = -1;
        buffer.markAllDirty();
    }

    public void setCursorStyle(String style) { this.cursorStyle = style; }
    public void setCursorBlink(boolean blink) { this.cursorBlink = blink; }
    public void setSelectionColor(Color color) { this.selectionColor = color; }
    public double getCellWidth() { return cellWidth; }
    public double getCellHeight() { return cellHeight; }
}
```

- [ ] **Step 2: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/TerminalRenderer.java
git commit -m "feat(claude-fx): add TerminalRenderer with Canvas rendering and cursor blink"
```

---

### Task 8: TerminalWidget

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/terminal/TerminalWidget.java`

- [ ] **Step 1: Write TerminalWidget**

```java
package com.claudefx.terminal;

import javafx.scene.canvas.Canvas;
import javafx.scene.input.*;
import javafx.scene.layout.Region;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class TerminalWidget extends Region {

    private final TerminalBuffer buffer;
    private final AnsiParser parser;
    private final Canvas canvas;
    private final TerminalRenderer renderer;
    private Consumer<byte[]> inputHandler;

    // Selection tracking
    private boolean selecting;
    private int selectStartRow, selectStartCol;

    public TerminalWidget(int scrollbackLines) {
        buffer = new TerminalBuffer(80, 24, scrollbackLines);
        parser = new AnsiParser(buffer);
        canvas = new Canvas();
        renderer = new TerminalRenderer(canvas, buffer);

        getChildren().add(canvas);
        setFocusTraversable(true);

        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);

        widthProperty().addListener((obs, o, n) -> onResize());
        heightProperty().addListener((obs, o, n) -> onResize());
    }

    public void start() { renderer.start(); }
    public void stop() { renderer.stop(); }

    public void feed(byte[] data) {
        synchronized (buffer.getLock()) {
            parser.process(data);
        }
    }

    public void setInputHandler(Consumer<byte[]> handler) {
        this.inputHandler = handler;
    }

    private void sendInput(String seq) {
        if (inputHandler != null) {
            inputHandler.accept(seq.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleKeyPressed(KeyEvent e) {
        // Ctrl+letter sends control character (e.g., Ctrl+C = 0x03)
        if (e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown()) {
            KeyCode code = e.getCode();
            if (code.isLetterKey()) {
                int ctrl = code.getName().charAt(0) - 'A' + 1;
                sendInput(String.valueOf((char) ctrl));
                e.consume();
                return;
            }
        }

        String seq = switch (e.getCode()) {
            case UP -> "\033[A";
            case DOWN -> "\033[B";
            case RIGHT -> "\033[C";
            case LEFT -> "\033[D";
            case HOME -> "\033[H";
            case END -> "\033[F";
            case PAGE_UP -> "\033[5~";
            case PAGE_DOWN -> "\033[6~";
            case INSERT -> "\033[2~";
            case DELETE -> "\033[3~";
            case F1 -> "\033OP";
            case F2 -> "\033OQ";
            case F3 -> "\033OR";
            case F4 -> "\033OS";
            case F5 -> "\033[15~";
            case F6 -> "\033[17~";
            case F7 -> "\033[18~";
            case F8 -> "\033[19~";
            case F9 -> "\033[20~";
            case F10 -> "\033[21~";
            case F11 -> "\033[23~";
            case F12 -> "\033[24~";
            case ENTER -> "\r";
            case BACK_SPACE -> "\177";
            case TAB -> "\t";
            case ESCAPE -> "\033";
            default -> null;
        };

        if (seq != null) {
            sendInput(seq);
            e.consume();
        }
    }

    private void handleKeyTyped(KeyEvent e) {
        String ch = e.getCharacter();
        if (ch.isEmpty() || ch.charAt(0) < 0x20) return;
        // Skip if modifier keys are held (handled by key bindings)
        if (e.isControlDown() || e.isMetaDown() || e.isAltDown()) return;
        sendInput(ch);
        e.consume();
    }

    private void handleMousePressed(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            requestFocus();
            renderer.clearSelection();
            selecting = true;
            selectStartRow = (int) (e.getY() / renderer.getCellHeight());
            selectStartCol = (int) (e.getX() / renderer.getCellWidth());
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (selecting) {
            int row = (int) (e.getY() / renderer.getCellHeight());
            int col = (int) (e.getX() / renderer.getCellWidth());
            renderer.setSelection(selectStartRow, selectStartCol, row, col);
        }
    }

    private void handleMouseReleased(MouseEvent e) {
        selecting = false;
    }

    private void onResize() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.setWidth(w);
        canvas.setHeight(h);

        int cols = Math.max(1, (int) (w / renderer.getCellWidth()));
        int rows = Math.max(1, (int) (h / renderer.getCellHeight()));

        synchronized (buffer.getLock()) {
            if (cols != buffer.getColumns() || rows != buffer.getRows()) {
                buffer.resize(cols, rows);
            }
        }
    }

    public TerminalBuffer getBuffer() { return buffer; }
    public AnsiParser getParser() { return parser; }
    public TerminalRenderer getRenderer() { return renderer; }

    public int getGridColumns() { return buffer.getColumns(); }
    public int getGridRows() { return buffer.getRows(); }

    public String getTitle() { return parser.getLastTitle(); }

    public void setFont(String family, int size) {
        renderer.setFont(family, size);
        onResize();
    }

    public String getSelectedText() {
        // Delegate to buffer text extraction using selection bounds
        // Returns empty string if no selection
        return "";
    }
}
```

- [ ] **Step 2: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/TerminalWidget.java
git commit -m "feat(claude-fx): add TerminalWidget composing buffer, parser, renderer, input"
```

---

### Task 9: Session and SessionManager

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/session/Session.java`
- Create: `claude-fx/src/main/java/com/claudefx/session/SessionManager.java`

- [ ] **Step 1: Write Session**

```java
package com.claudefx.session;

import com.claudefx.config.AppConfig;
import com.claudefx.process.ClaudeProcess;
import com.claudefx.terminal.TerminalWidget;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

public class Session {

    private final TerminalWidget terminal;
    private ClaudeProcess process;
    private final AppConfig config;
    private String workingDir;
    private String title;
    private final Instant createdAt;

    public Session(AppConfig config, String workingDir) {
        this.config = config;
        this.workingDir = workingDir;
        this.title = Path.of(workingDir).getFileName().toString();
        this.createdAt = Instant.now();
        this.terminal = new TerminalWidget(config.getTerminal().getScrollbackLines());

        terminal.setFont(
            config.getTerminal().getFontFamily(),
            config.getTerminal().getFontSize()
        );
    }

    public void start() throws IOException {
        process = new ClaudeProcess(config, workingDir);
        terminal.setInputHandler(data -> {
            try { process.write(data); }
            catch (IOException e) { /* process may have exited */ }
        });
        process.start(terminal::feed);
        process.setOnExit(code -> {
            // Process exited — terminal keeps showing last output
        });
        terminal.start();
    }

    public void restart() throws IOException {
        if (process != null) process.stop();
        start();
    }

    public void stop() {
        terminal.stop();
        if (process != null) process.stop();
    }

    public void resize(int cols, int rows) {
        if (process != null) process.resize(cols, rows);
    }

    public boolean isAlive() { return process != null && process.isAlive(); }
    public String getWorkingDir() { return workingDir; }
    public String getTitle() {
        String oscTitle = terminal.getTitle();
        return (oscTitle != null && !oscTitle.isEmpty()) ? oscTitle : title;
    }
    public void setTitle(String title) { this.title = title; }
    public TerminalWidget getTerminal() { return terminal; }
    public ClaudeProcess getProcess() { return process; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: Write SessionManager**

```java
package com.claudefx.session;

import com.claudefx.config.AppConfig;
import com.claudefx.config.ConfigStore;
import com.claudefx.config.SessionState;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.util.ArrayList;

public class SessionManager {

    private final ObservableList<Session> sessions = FXCollections.observableArrayList();
    private final AppConfig config;
    private final ConfigStore configStore;
    private int activeIndex = -1;

    public SessionManager(AppConfig config, ConfigStore configStore) {
        this.config = config;
        this.configStore = configStore;
    }

    public Session createSession(String workingDir) throws IOException {
        var session = new Session(config, workingDir);
        sessions.add(session);
        session.start();
        activeIndex = sessions.size() - 1;
        return session;
    }

    public void closeSession(int index) {
        if (index < 0 || index >= sessions.size()) return;
        sessions.get(index).stop();
        sessions.remove(index);
        if (activeIndex >= sessions.size()) {
            activeIndex = sessions.size() - 1;
        }
    }

    public void closeSession(Session session) {
        int idx = sessions.indexOf(session);
        if (idx >= 0) closeSession(idx);
    }

    public void setActiveIndex(int index) {
        if (index >= 0 && index < sessions.size()) {
            this.activeIndex = index;
        }
    }

    public Session getActiveSession() {
        if (activeIndex >= 0 && activeIndex < sessions.size()) {
            return sessions.get(activeIndex);
        }
        return null;
    }

    public void saveState(AppConfig.WindowConfig windowConfig) {
        var state = new SessionState();
        var tabs = new ArrayList<SessionState.TabState>();
        for (int i = 0; i < sessions.size(); i++) {
            var s = sessions.get(i);
            var tab = new SessionState.TabState();
            tab.setWorkingDir(s.getWorkingDir());
            tab.setTitle(s.getTitle());
            tab.setPosition(i);
            tabs.add(tab);
        }
        state.setTabs(tabs);
        state.setActiveTabIndex(activeIndex);
        state.setWindow(windowConfig);
        try { configStore.saveSessions(state); }
        catch (IOException e) { System.err.println("Failed to save sessions: " + e.getMessage()); }
    }

    public void restoreState() {
        var state = configStore.loadSessions();
        if (state == null || state.getTabs().isEmpty()) return;
        for (var tab : state.getTabs()) {
            try { createSession(tab.getWorkingDir()); }
            catch (IOException e) { System.err.println("Failed to restore tab: " + e.getMessage()); }
        }
        setActiveIndex(state.getActiveTabIndex());
    }

    public void closeAll() {
        for (var session : new ArrayList<>(sessions)) session.stop();
        sessions.clear();
        activeIndex = -1;
    }

    public ObservableList<Session> getSessions() { return sessions; }
    public int getActiveIndex() { return activeIndex; }
}
```

- [ ] **Step 3: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/session/
git commit -m "feat(claude-fx): add Session and SessionManager with lifecycle and persistence"
```

---

### Task 10: MainWindow and TabHeader

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/ui/MainWindow.java`
- Create: `claude-fx/src/main/java/com/claudefx/ui/TabHeader.java`
- Create: `claude-fx/src/main/resources/styles/terminal.css`
- Modify: `claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java`

- [ ] **Step 1: Write terminal.css**

```css
.root {
    -fx-base: #1e1e1e;
    -fx-background: #1e1e1e;
}

.tab-pane {
    -fx-tab-min-height: 30px;
    -fx-tab-max-height: 30px;
}

.tab-pane .tab-header-area .tab-header-background {
    -fx-background-color: #252526;
}

.tab {
    -fx-background-color: #2d2d2d;
    -fx-padding: 4 8 4 8;
}

.tab:selected {
    -fx-background-color: #1e1e1e;
}

.tab .tab-label {
    -fx-text-fill: #cccccc;
    -fx-font-family: "sans-serif";
    -fx-font-size: 12px;
}

.tab:selected .tab-label {
    -fx-text-fill: #ffffff;
}

.tab-header-status {
    -fx-min-width: 8;
    -fx-min-height: 8;
    -fx-max-width: 8;
    -fx-max-height: 8;
    -fx-background-radius: 4;
}

.tab-header-status.running {
    -fx-background-color: #0dbc79;
}

.tab-header-status.exited {
    -fx-background-color: #666666;
}
```

- [ ] **Step 2: Write TabHeader**

```java
package com.claudefx.ui;

import com.claudefx.session.Session;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

public class TabHeader extends HBox {

    private final Label titleLabel;
    private final Region statusDot;
    private final Session session;

    public TabHeader(Session session) {
        this.session = session;
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);

        statusDot = new Region();
        statusDot.getStyleClass().addAll("tab-header-status", "running");

        titleLabel = new Label(session.getTitle());
        titleLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12;");

        getChildren().addAll(statusDot, titleLabel);
    }

    public void refresh() {
        titleLabel.setText(truncate(session.getTitle(), 25));
        statusDot.getStyleClass().removeAll("running", "exited");
        statusDot.getStyleClass().add(session.isAlive() ? "running" : "exited");
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
```

- [ ] **Step 3: Write MainWindow**

```java
package com.claudefx.ui;

import com.claudefx.config.AppConfig;
import com.claudefx.config.ConfigStore;
import com.claudefx.session.Session;
import com.claudefx.session.SessionManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainWindow extends BorderPane {

    private final TabPane tabPane;
    private final SessionManager sessionManager;
    private final AppConfig config;
    private final Map<Tab, TabHeader> tabHeaders = new HashMap<>();
    private final Map<Tab, Session> tabSessions = new HashMap<>();

    public MainWindow(AppConfig config, ConfigStore configStore) {
        this.config = config;
        this.sessionManager = new SessionManager(config, configStore);

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        setCenter(tabPane);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null) {
                var session = tabSessions.get(newTab);
                if (session != null) {
                    sessionManager.setActiveIndex(sessionManager.getSessions().indexOf(session));
                    session.getTerminal().requestFocus();
                }
            }
        });

        // Periodic refresh of tab headers
        var refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshHeaders()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }

    public void newTab() { newTab(config.getDefaultWorkingDir()); }

    public void newTab(String workingDir) {
        try {
            var session = sessionManager.createSession(workingDir);
            var tab = new Tab();
            var header = new TabHeader(session);
            tab.setGraphic(header);
            tab.setContent(session.getTerminal());
            tab.setOnClosed(e -> {
                sessionManager.closeSession(session);
                tabHeaders.remove(tab);
                tabSessions.remove(tab);
            });

            tabHeaders.put(tab, header);
            tabSessions.put(tab, session);
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
            session.getTerminal().requestFocus();
        } catch (IOException e) {
            System.err.println("Failed to create tab: " + e.getMessage());
        }
    }

    public void closeCurrentTab() {
        var tab = tabPane.getSelectionModel().getSelectedItem();
        if (tab != null) {
            tabPane.getTabs().remove(tab);
            var session = tabSessions.remove(tab);
            if (session != null) sessionManager.closeSession(session);
            tabHeaders.remove(tab);
        }
    }

    public void nextTab() {
        int idx = tabPane.getSelectionModel().getSelectedIndex();
        if (idx < tabPane.getTabs().size() - 1) {
            tabPane.getSelectionModel().select(idx + 1);
        }
    }

    public void prevTab() {
        int idx = tabPane.getSelectionModel().getSelectedIndex();
        if (idx > 0) {
            tabPane.getSelectionModel().select(idx - 1);
        }
    }

    private void refreshHeaders() {
        tabHeaders.values().forEach(TabHeader::refresh);
    }

    public SessionManager getSessionManager() { return sessionManager; }
    public TabPane getTabPane() { return tabPane; }
}
```

- [ ] **Step 4: Update ClaudeFxApplication**

```java
package com.claudefx;

import com.claudefx.config.AppConfig;
import com.claudefx.config.ConfigStore;
import com.claudefx.config.SessionState;
import com.claudefx.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

public class ClaudeFxApplication extends Application {

    private MainWindow mainWindow;
    private ConfigStore configStore;
    private AppConfig config;

    @Override
    public void start(Stage primaryStage) {
        configStore = ConfigStore.defaultStore();
        config = configStore.loadConfig();

        mainWindow = new MainWindow(config, configStore);

        // Restore sessions or open default tab
        var savedSessions = configStore.loadSessions();
        if (savedSessions != null && !savedSessions.getTabs().isEmpty()) {
            for (var tab : savedSessions.getTabs()) {
                mainWindow.newTab(tab.getWorkingDir());
            }
            var windowConfig = savedSessions.getWindow();
            primaryStage.setWidth(windowConfig.getWidth());
            primaryStage.setHeight(windowConfig.getHeight());
            if (windowConfig.getX() != null) primaryStage.setX(windowConfig.getX());
            if (windowConfig.getY() != null) primaryStage.setY(windowConfig.getY());
            primaryStage.setMaximized(windowConfig.isMaximized());
        } else {
            mainWindow.newTab();
            primaryStage.setWidth(config.getWindow().getWidth());
            primaryStage.setHeight(config.getWindow().getHeight());
        }

        var scene = new Scene(mainWindow);
        scene.getStylesheets().add(getClass().getResource("/styles/terminal.css").toExternalForm());

        // Register global keyboard shortcuts
        setupKeyBindings(scene);

        primaryStage.setTitle("Claude FX");
        primaryStage.setScene(scene);

        primaryStage.setOnCloseRequest(e -> {
            var windowCfg = new AppConfig.WindowConfig();
            windowCfg.setWidth((int) primaryStage.getWidth());
            windowCfg.setHeight((int) primaryStage.getHeight());
            windowCfg.setX((int) primaryStage.getX());
            windowCfg.setY((int) primaryStage.getY());
            windowCfg.setMaximized(primaryStage.isMaximized());
            mainWindow.getSessionManager().saveState(windowCfg);
            mainWindow.getSessionManager().closeAll();
        });

        primaryStage.show();
    }

    private void setupKeyBindings(Scene scene) {
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN),
            () -> mainWindow.newTab()
        );
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.W, KeyCombination.CONTROL_DOWN),
            () -> mainWindow.closeCurrentTab()
        );
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
            () -> mainWindow.nextTab()
        );
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
            () -> mainWindow.prevTab()
        );
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 5: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/ui/ \
        claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java \
        claude-fx/src/main/resources/styles/terminal.css
git commit -m "feat(claude-fx): add MainWindow with tabbed UI, TabHeader, and key bindings"
```

---

### Task 11: SearchBar

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/ui/SearchBar.java`

- [ ] **Step 1: Write SearchBar**

```java
package com.claudefx.ui;

import com.claudefx.terminal.TerminalBuffer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchBar extends HBox {

    private final TextField searchField;
    private final Label matchCountLabel;
    private final ToggleButton caseSensitiveBtn;
    private final ToggleButton regexBtn;

    private TerminalBuffer buffer;
    private final List<int[]> matches = new ArrayList<>(); // [row, col, length]
    private int currentMatch = -1;

    public SearchBar() {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPadding(new Insets(4, 8, 4, 8));
        setStyle("-fx-background-color: #252526;");
        setVisible(false);
        setManaged(false);

        searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.setPrefWidth(250);
        searchField.setOnAction(e -> findNext());

        matchCountLabel = new Label("");
        matchCountLabel.setStyle("-fx-text-fill: #999;");

        caseSensitiveBtn = new ToggleButton("Aa");
        caseSensitiveBtn.setStyle("-fx-font-size: 11;");
        caseSensitiveBtn.setOnAction(e -> search());

        regexBtn = new ToggleButton(".*");
        regexBtn.setStyle("-fx-font-size: 11;");
        regexBtn.setOnAction(e -> search());

        var upBtn = new Button("\u25B2");
        upBtn.setOnAction(e -> findPrev());

        var downBtn = new Button("\u25BC");
        downBtn.setOnAction(e -> findNext());

        var closeBtn = new Button("\u2715");
        closeBtn.setOnAction(e -> hide());

        searchField.textProperty().addListener((obs, o, n) -> search());

        getChildren().addAll(searchField, matchCountLabel, caseSensitiveBtn, regexBtn, upBtn, downBtn, closeBtn);
    }

    public void show(TerminalBuffer buffer) {
        this.buffer = buffer;
        setVisible(true);
        setManaged(true);
        searchField.requestFocus();
        searchField.selectAll();
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
        matches.clear();
        currentMatch = -1;
        matchCountLabel.setText("");
    }

    private void search() {
        matches.clear();
        currentMatch = -1;
        String query = searchField.getText();
        if (query.isEmpty() || buffer == null) {
            matchCountLabel.setText("");
            return;
        }

        boolean caseSensitive = caseSensitiveBtn.isSelected();
        boolean regex = regexBtn.isSelected();

        synchronized (buffer.getLock()) {
            for (int r = 0; r < buffer.getRows(); r++) {
                String line = buffer.getText(r, 0, r, buffer.getColumns()).stripTrailing();
                findInLine(line, query, r, caseSensitive, regex);
            }
        }

        if (!matches.isEmpty()) {
            currentMatch = 0;
            matchCountLabel.setText("1 of " + matches.size());
        } else {
            matchCountLabel.setText("No results");
        }
    }

    private void findInLine(String line, String query, int row, boolean caseSensitive, boolean regex) {
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            String pattern = regex ? query : Pattern.quote(query);
            Matcher m = Pattern.compile(pattern, flags).matcher(line);
            while (m.find()) {
                matches.add(new int[]{row, m.start(), m.end() - m.start()});
            }
        } catch (Exception e) {
            // Invalid regex, ignore
        }
    }

    public void findNext() {
        if (matches.isEmpty()) return;
        currentMatch = (currentMatch + 1) % matches.size();
        matchCountLabel.setText((currentMatch + 1) + " of " + matches.size());
    }

    public void findPrev() {
        if (matches.isEmpty()) return;
        currentMatch = (currentMatch - 1 + matches.size()) % matches.size();
        matchCountLabel.setText((currentMatch + 1) + " of " + matches.size());
    }

    public List<int[]> getMatches() { return matches; }
    public int getCurrentMatchIndex() { return currentMatch; }
    public boolean isActive() { return isVisible(); }
}
```

- [ ] **Step 2: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/ui/SearchBar.java
git commit -m "feat(claude-fx): add SearchBar with regex, case-sensitive toggle, match navigation"
```

---

### Task 12: Log Export

Add export logic directly into `Session` and wire it from `MainWindow`.

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/session/Session.java`
- Modify: `claude-fx/src/main/java/com/claudefx/ui/MainWindow.java`

- [ ] **Step 1: Add export methods to Session**

Add to `Session.java`:

```java
public String exportPlainText() {
    var sb = new StringBuilder();
    var buf = terminal.getBuffer();
    synchronized (buf.getLock()) {
        // Scrollback
        for (int i = 0; i < buf.getScrollbackSize(); i++) {
            var row = buf.getScrollbackRow(i);
            for (var cell : row) {
                if (!cell.isWidthContinuation()) sb.appendCodePoint(cell.getCodepoint());
            }
            sb.append('\n');
        }
        // Visible screen
        for (int r = 0; r < buf.getRows(); r++) {
            sb.append(buf.getText(r, 0, r, buf.getColumns()).stripTrailing());
            sb.append('\n');
        }
    }
    return sb.toString();
}
```

- [ ] **Step 2: Add export action to MainWindow**

Add to `MainWindow.java` — wire `Ctrl+Shift+S` accelerator:

```java
public void exportLog() {
    var session = sessionManager.getActiveSession();
    if (session == null) return;

    var fileChooser = new javafx.stage.FileChooser();
    fileChooser.setTitle("Export Session Log");
    String timestamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
    fileChooser.setInitialFileName("claude-session-" + timestamp + ".log");
    fileChooser.getExtensionFilters().addAll(
        new javafx.stage.FileChooser.ExtensionFilter("Plain Text", "*.log", "*.txt"),
        new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*")
    );

    var file = fileChooser.showSaveDialog(getScene().getWindow());
    if (file != null) {
        try {
            java.nio.file.Files.writeString(file.toPath(), session.exportPlainText());
        } catch (java.io.IOException e) {
            System.err.println("Export failed: " + e.getMessage());
        }
    }
}
```

Add accelerator in `setupKeyBindings`:

```java
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
    () -> mainWindow.exportLog()
);
```

- [ ] **Step 3: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/session/Session.java \
        claude-fx/src/main/java/com/claudefx/ui/MainWindow.java
git commit -m "feat(claude-fx): add log export with plain text output and file chooser"
```

---

### Task 13: SettingsDialog and KeyBindingsDialog

**Files:**
- Create: `claude-fx/src/main/java/com/claudefx/ui/SettingsDialog.java`
- Create: `claude-fx/src/main/java/com/claudefx/ui/KeyBindingsDialog.java`

- [ ] **Step 1: Write SettingsDialog**

```java
package com.claudefx.ui;

import com.claudefx.config.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;

public class SettingsDialog extends Dialog<AppConfig> {

    private final AppConfig config;

    public SettingsDialog(AppConfig config) {
        this.config = config;
        setTitle("Settings");
        setHeaderText(null);

        var tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(createGeneralTab(), createTerminalTab());

        getDialogPane().setContent(tabPane);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefSize(500, 400);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                applySettings();
                return config;
            }
            return null;
        });
    }

    // Fields saved from tab builders for final apply
    private TextField binaryField, argsField, dirField, fontField;
    private Spinner<Integer> sizeSpinner, scrollbackSpinner;
    private ChoiceBox<String> cursorChoice;
    private CheckBox blinkCheck;

    private void applySettings() {
        config.setClaudeBinary(binaryField.getText());
        config.setDefaultWorkingDir(dirField.getText());
        String args = argsField.getText().trim();
        config.setDefaultArgs(args.isEmpty() ? java.util.List.of() : java.util.List.of(args.split("\\s+")));
        config.getTerminal().setFontFamily(fontField.getText());
        config.getTerminal().setFontSize(sizeSpinner.getValue());
        config.getTerminal().setScrollbackLines(scrollbackSpinner.getValue());
        config.getTerminal().setCursorStyle(cursorChoice.getValue());
        config.getTerminal().setCursorBlink(blinkCheck.isSelected());
    }

    private Tab createGeneralTab() {
        var grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPadding(new Insets(20));

        binaryField = new TextField(config.getClaudeBinary());
        var browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> {
            var fc = new FileChooser();
            fc.setTitle("Select Claude Binary");
            var file = fc.showOpenDialog(getOwner());
            if (file != null) binaryField.setText(file.getAbsolutePath());
        });

        argsField = new TextField(String.join(" ", config.getDefaultArgs()));
        dirField = new TextField(config.getDefaultWorkingDir());

        grid.add(new Label("Claude Binary:"), 0, 0);
        grid.add(binaryField, 1, 0);
        grid.add(browseBtn, 2, 0);
        grid.add(new Label("Default Args:"), 0, 1);
        grid.add(argsField, 1, 1);
        grid.add(new Label("Working Dir:"), 0, 2);
        grid.add(dirField, 1, 2);

        var tab = new Tab("General");
        tab.setContent(grid);
        return tab;
    }

    private Tab createTerminalTab() {
        var grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPadding(new Insets(20));

        fontField = new TextField(config.getTerminal().getFontFamily());
        sizeSpinner = new Spinner<>(8, 36, config.getTerminal().getFontSize());
        scrollbackSpinner = new Spinner<>(100, 100000, config.getTerminal().getScrollbackLines(), 1000);

        cursorChoice = new ChoiceBox<>();
        cursorChoice.getItems().addAll("block", "underline", "bar");
        cursorChoice.setValue(config.getTerminal().getCursorStyle());

        blinkCheck = new CheckBox("Cursor Blink");
        blinkCheck.setSelected(config.getTerminal().isCursorBlink());

        grid.add(new Label("Font:"), 0, 0);
        grid.add(fontField, 1, 0);
        grid.add(new Label("Size:"), 0, 1);
        grid.add(sizeSpinner, 1, 1);
        grid.add(new Label("Scrollback:"), 0, 2);
        grid.add(scrollbackSpinner, 1, 2);
        grid.add(new Label("Cursor:"), 0, 3);
        grid.add(cursorChoice, 1, 3);
        grid.add(blinkCheck, 0, 4, 2, 1);

        var tab = new Tab("Terminal");
        tab.setContent(grid);
        return tab;
    }
}
```

- [ ] **Step 2: Write KeyBindingsDialog**

```java
package com.claudefx.ui;

import com.claudefx.config.KeyBindings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public class KeyBindingsDialog extends Dialog<KeyBindings> {

    private final KeyBindings bindings;
    private final Map<String, TextField> fields = new LinkedHashMap<>();

    public KeyBindingsDialog(KeyBindings bindings) {
        this.bindings = bindings;
        setTitle("Key Bindings");
        setHeaderText("Click a field and press a key combination to rebind.");

        var vbox = new VBox(8);
        vbox.setPadding(new Insets(20));

        addBinding(vbox, "New Tab", "newTab", bindings.getNewTab());
        addBinding(vbox, "Close Tab", "closeTab", bindings.getCloseTab());
        addBinding(vbox, "Next Tab", "nextTab", bindings.getNextTab());
        addBinding(vbox, "Previous Tab", "prevTab", bindings.getPrevTab());
        addBinding(vbox, "Search", "search", bindings.getSearch());
        addBinding(vbox, "Copy", "copy", bindings.getCopy());
        addBinding(vbox, "Paste", "paste", bindings.getPaste());
        addBinding(vbox, "Zoom In", "zoomIn", bindings.getZoomIn());
        addBinding(vbox, "Zoom Out", "zoomOut", bindings.getZoomOut());
        addBinding(vbox, "Export Log", "exportLog", bindings.getExportLog());
        addBinding(vbox, "Settings", "settings", bindings.getSettings());

        var scroll = new ScrollPane(vbox);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(400, 400);

        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                applyBindings();
                return bindings;
            }
            return null;
        });
    }

    private void addBinding(VBox parent, String label, String key, String current) {
        var field = new TextField(current);
        field.setEditable(false);
        field.setPrefWidth(200);
        field.setOnKeyPressed(e -> {
            field.setText(keyEventToString(e));
            e.consume();
        });
        fields.put(key, field);

        var hbox = new javafx.scene.layout.HBox(10);
        hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var lbl = new Label(label);
        lbl.setPrefWidth(120);
        hbox.getChildren().addAll(lbl, field);
        parent.getChildren().add(hbox);
    }

    private String keyEventToString(KeyEvent e) {
        var sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isShiftDown()) sb.append("Shift+");
        if (e.isAltDown()) sb.append("Alt+");
        if (e.isMetaDown()) sb.append("Meta+");
        sb.append(e.getCode().name().substring(0, 1).toUpperCase()
                + e.getCode().name().substring(1).toLowerCase());
        return sb.toString();
    }

    private void applyBindings() {
        for (var entry : fields.entrySet()) {
            String setter = "set" + entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
            try {
                Method m = KeyBindings.class.getMethod(setter, String.class);
                m.invoke(bindings, entry.getValue().getText());
            } catch (Exception e) {
                // ignore reflection errors
            }
        }
    }
}
```

- [ ] **Step 3: Wire settings shortcut in ClaudeFxApplication**

Add to `setupKeyBindings`:

```java
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
    () -> {
        var dialog = new SettingsDialog(config);
        dialog.showAndWait().ifPresent(result -> {
            try { configStore.saveConfig(result); }
            catch (Exception ex) { System.err.println("Failed to save config: " + ex.getMessage()); }
        });
    }
);
```

- [ ] **Step 4: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/ui/SettingsDialog.java \
        claude-fx/src/main/java/com/claudefx/ui/KeyBindingsDialog.java \
        claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java
git commit -m "feat(claude-fx): add SettingsDialog, KeyBindingsDialog, and settings shortcut"
```

---

### Task 14: Integration — Wire SearchBar into MainWindow

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/ui/MainWindow.java`

- [ ] **Step 1: Add SearchBar to MainWindow**

In `MainWindow` constructor, add a `SearchBar` instance above the `TabPane`:

```java
private final SearchBar searchBar = new SearchBar();

// In constructor, before setCenter:
setTop(searchBar);
```

Add method:
```java
public void toggleSearch() {
    var session = sessionManager.getActiveSession();
    if (session == null) return;
    if (searchBar.isActive()) {
        searchBar.hide();
    } else {
        searchBar.show(session.getTerminal().getBuffer());
    }
}
```

- [ ] **Step 2: Wire Ctrl+F accelerator**

Add in `setupKeyBindings`:
```java
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
    () -> mainWindow.toggleSearch()
);
```

- [ ] **Step 3: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/ui/MainWindow.java \
        claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java
git commit -m "feat(claude-fx): wire SearchBar into MainWindow with Ctrl+F toggle"
```

---

### Task 15: Fix Missing Features — Search Scrollback, Highlights, Context Menu, Restart, Resize

This task addresses features from the spec that were not wired up in earlier tasks.

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/ui/SearchBar.java`
- Modify: `claude-fx/src/main/java/com/claudefx/terminal/TerminalRenderer.java`
- Modify: `claude-fx/src/main/java/com/claudefx/terminal/TerminalWidget.java`
- Modify: `claude-fx/src/main/java/com/claudefx/session/Session.java`

- [ ] **Step 1: SearchBar — search scrollback rows too**

In `SearchBar.search()`, add scrollback iteration before the visible-screen loop:

```java
// Search scrollback
for (int i = 0; i < buffer.getScrollbackSize(); i++) {
    var row = buffer.getScrollbackRow(i);
    var sb = new StringBuilder();
    for (var cell : row) {
        if (!cell.isWidthContinuation()) sb.appendCodePoint(cell.getCodepoint());
    }
    findInLine(sb.toString().stripTrailing(), query, -(buffer.getScrollbackSize() - i), caseSensitive, regex);
}
```

- [ ] **Step 2: TerminalRenderer — accept and render search highlights**

Add to `TerminalRenderer`:

```java
private List<int[]> searchMatches = List.of();
private int currentSearchMatch = -1;

public void setSearchMatches(List<int[]> matches, int currentIndex) {
    this.searchMatches = matches;
    this.currentSearchMatch = currentIndex;
    buffer.markAllDirty();
}

public void clearSearchMatches() {
    this.searchMatches = List.of();
    this.currentSearchMatch = -1;
    buffer.markAllDirty();
}
```

In `renderRow`, after selection highlight, add:

```java
// Search match highlight
for (int m = 0; m < searchMatches.size(); m++) {
    int[] match = searchMatches.get(m);
    if (match[0] == row && col >= match[1] && col < match[1] + match[2]) {
        Color highlightColor = (m == currentSearchMatch)
            ? Color.rgb(255, 150, 50, 0.5)  // current match — orange
            : Color.rgb(255, 255, 0, 0.3);   // other matches — yellow
        gc.setFill(highlightColor);
        gc.fillRect(x, y, w, cellHeight);
    }
}
```

- [ ] **Step 3: TerminalWidget — add right-click context menu**

Add to `TerminalWidget` constructor:

```java
var contextMenu = new javafx.scene.control.ContextMenu();
var copyItem = new javafx.scene.control.MenuItem("Copy");
copyItem.setOnAction(e -> {
    String text = getSelectedText();
    if (!text.isEmpty()) {
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }
});
var pasteItem = new javafx.scene.control.MenuItem("Paste");
pasteItem.setOnAction(e -> {
    String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
    if (text != null) sendInput(text);
});
var selectAllItem = new javafx.scene.control.MenuItem("Select All");
selectAllItem.setOnAction(e -> renderer.setSelection(0, 0, buffer.getRows() - 1, buffer.getColumns() - 1));
contextMenu.getItems().addAll(copyItem, pasteItem, selectAllItem);
setOnContextMenuRequested(e -> contextMenu.show(this, e.getScreenX(), e.getScreenY()));
```

- [ ] **Step 4: TerminalWidget — implement getSelectedText()**

Replace the stub:

```java
public String getSelectedText() {
    // renderer tracks selection bounds
    return buffer.getText(
        Math.min(renderer.getSelStartRow(), renderer.getSelEndRow()),
        Math.min(renderer.getSelStartCol(), renderer.getSelEndCol()),
        Math.max(renderer.getSelStartRow(), renderer.getSelEndRow()),
        Math.max(renderer.getSelStartCol(), renderer.getSelEndCol())
    );
}
```

Add getters to `TerminalRenderer`:
```java
public int getSelStartRow() { return selStartRow; }
public int getSelStartCol() { return selStartCol; }
public int getSelEndRow() { return selEndRow; }
public int getSelEndCol() { return selEndCol; }
```

- [ ] **Step 5: Session — restart on Enter when process exited**

In `Session.start()`, add key event filter to terminal:

```java
terminal.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
    if (!isAlive() && e.getCode() == javafx.scene.input.KeyCode.ENTER) {
        try { restart(); }
        catch (IOException ex) { System.err.println("Restart failed: " + ex.getMessage()); }
        e.consume();
    }
});
```

- [ ] **Step 6: TerminalWidget — notify Session of resize for SIGWINCH**

Add a resize callback:

```java
private Runnable onResize;
public void setOnResize(Runnable callback) { this.onResize = callback; }
```

At the end of `onResize()`:
```java
if (onResize != null) onResize.run();
```

In `Session.start()`:
```java
terminal.setOnResize(() -> resize(terminal.getGridColumns(), terminal.getGridRows()));
```

- [ ] **Step 7: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/
git commit -m "feat(claude-fx): wire search highlights, context menu, restart, resize propagation"
```

---

### Task 16: Tab Drag-and-Drop Reorder and Double-Click Rename

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/ui/TabHeader.java`
- Modify: `claude-fx/src/main/java/com/claudefx/ui/MainWindow.java`

- [ ] **Step 1: TabHeader — add double-click to rename**

Add to `TabHeader` constructor:

```java
titleLabel.setOnMouseClicked(e -> {
    if (e.getClickCount() == 2) {
        var input = new javafx.scene.control.TextField(titleLabel.getText());
        input.setPrefWidth(150);
        input.setOnAction(ev -> {
            session.setTitle(input.getText());
            titleLabel.setText(truncate(input.getText(), 25));
            getChildren().set(getChildren().indexOf(input), titleLabel);
        });
        input.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                session.setTitle(input.getText());
                titleLabel.setText(truncate(input.getText(), 25));
                getChildren().set(getChildren().indexOf(input), titleLabel);
            }
        });
        getChildren().set(getChildren().indexOf(titleLabel), input);
        input.requestFocus();
        input.selectAll();
    }
});
```

- [ ] **Step 2: MainWindow — add drag-and-drop tab reorder**

Add to `MainWindow.newTab()`, after creating the tab:

```java
tab.getGraphic().setOnDragDetected(e -> {
    var dragboard = tab.getGraphic().startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
    var content = new javafx.scene.input.ClipboardContent();
    content.putString(String.valueOf(tabPane.getTabs().indexOf(tab)));
    dragboard.setContent(content);
    e.consume();
});
tab.getGraphic().setOnDragOver(e -> {
    if (e.getGestureSource() != tab.getGraphic() && e.getDragboard().hasString()) {
        e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
    }
    e.consume();
});
tab.getGraphic().setOnDragDropped(e -> {
    var db = e.getDragboard();
    if (db.hasString()) {
        int srcIdx = Integer.parseInt(db.getString());
        int tgtIdx = tabPane.getTabs().indexOf(tab);
        if (srcIdx != tgtIdx) {
            var srcTab = tabPane.getTabs().remove(srcIdx);
            tabPane.getTabs().add(tgtIdx, srcTab);
            tabPane.getSelectionModel().select(srcTab);
        }
        e.setDropCompleted(true);
    }
    e.consume();
});
```

- [ ] **Step 3: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/ui/TabHeader.java \
        claude-fx/src/main/java/com/claudefx/ui/MainWindow.java
git commit -m "feat(claude-fx): add tab drag-and-drop reorder and double-click rename"
```

---

### Task 17: ANSI Log Export Format

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/session/Session.java`
- Modify: `claude-fx/src/main/java/com/claudefx/ui/MainWindow.java`

- [ ] **Step 1: Add ANSI raw buffer to AnsiParser**

The simplest approach: have the parser accumulate raw bytes alongside parsing.

Add to `AnsiParser`:

```java
private final java.io.ByteArrayOutputStream rawLog = new java.io.ByteArrayOutputStream();

public void process(byte[] data) {
    rawLog.write(data, 0, data.length);
    for (byte b : data) {
        processChar(b & 0xFF);
    }
}

public byte[] getRawLog() { return rawLog.toByteArray(); }
```

- [ ] **Step 2: Add exportAnsi() to Session**

```java
public byte[] exportAnsi() {
    return terminal.getParser().getRawLog();
}
```

- [ ] **Step 3: Update export dialog with format choice**

In `MainWindow.exportLog()`, add format selection:

```java
var formatChoice = new javafx.scene.control.ChoiceDialog<>("Plain Text", "Plain Text", "ANSI (raw)");
formatChoice.setTitle("Export Format");
formatChoice.setHeaderText("Choose export format:");
var format = formatChoice.showAndWait();
if (format.isEmpty()) return;

// ... in the file write section:
if ("ANSI (raw)".equals(format.get())) {
    java.nio.file.Files.write(file.toPath(), session.exportAnsi());
} else {
    java.nio.file.Files.writeString(file.toPath(), session.exportPlainText());
}
```

- [ ] **Step 4: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/AnsiParser.java \
        claude-fx/src/main/java/com/claudefx/session/Session.java \
        claude-fx/src/main/java/com/claudefx/ui/MainWindow.java
git commit -m "feat(claude-fx): add ANSI raw log export format"
```

---

### Task 18: Color Scheme Integration

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/terminal/AnsiParser.java`
- Modify: `claude-fx/src/main/java/com/claudefx/terminal/TerminalCell.java`
- Modify: `claude-fx/src/main/java/com/claudefx/terminal/TerminalRenderer.java`
- Modify: `claude-fx/src/main/java/com/claudefx/session/Session.java`
- Modify: `claude-fx/src/main/java/com/claudefx/ui/SettingsDialog.java`

- [ ] **Step 1: Make AnsiParser accept a color palette**

Add to `AnsiParser`:

```java
private Color[] palette = STANDARD_COLORS.clone();

public void setPalette(Color[] palette) {
    if (palette.length == 16) this.palette = palette;
}
```

Replace all references to `STANDARD_COLORS` in `handleSgr` and `color256` with `palette`.

- [ ] **Step 2: Make TerminalCell defaults configurable**

Add to `TerminalCell`:

```java
private static Color defaultFg = Color.rgb(212, 212, 212);
private static Color defaultBg = Color.rgb(30, 30, 30);

public static void setDefaults(Color fg, Color bg) {
    defaultFg = fg;
    defaultBg = bg;
}
```

Update `reset()` and constructor to use these mutable defaults instead of the static final constants.

- [ ] **Step 3: Load and apply color scheme in Session**

In `Session` constructor, after creating the terminal:

```java
var configStore = new ConfigStore(Path.of(System.getProperty("user.home"), ".claude-fx"));
var scheme = configStore.loadColorScheme(config.getTerminal().getColorScheme());
if (scheme != null) {
    // Apply to cell defaults
    TerminalCell.setDefaults(
        Color.web(scheme.getForeground()),
        Color.web(scheme.getBackground())
    );
    // Apply palette to parser
    Color[] palette = new Color[16];
    for (int i = 0; i < 16 && i < scheme.getAnsi().size(); i++) {
        palette[i] = Color.web(scheme.getAnsi().get(i));
    }
    terminal.getParser().setPalette(palette);
    // Apply to renderer
    terminal.getRenderer().setCursorColor(Color.web(scheme.getCursorColor()));
    terminal.getRenderer().setSelectionColor(Color.web(scheme.getSelectionBackground()));
}
```

- [ ] **Step 4: Add cursorColor to TerminalRenderer**

```java
private Color cursorColor = Color.WHITE;
public void setCursorColor(Color color) { this.cursorColor = color; }
```

Use `cursorColor` in `renderCursor` instead of hardcoded `Color.WHITE`.

- [ ] **Step 5: Add color scheme dropdown to SettingsDialog Terminal tab**

Add in `createTerminalTab()`:

```java
var schemeChoice = new ChoiceBox<String>();
schemeChoice.getItems().addAll("dark", "light");
schemeChoice.setValue(config.getTerminal().getColorScheme());
grid.add(new Label("Color Scheme:"), 0, 5);
grid.add(schemeChoice, 1, 5);
```

In `applySettings()`:
```java
config.getTerminal().setColorScheme(schemeChoice.getValue());
```

Add `schemeChoice` as instance field like the others.

- [ ] **Step 6: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/terminal/ \
        claude-fx/src/main/java/com/claudefx/session/Session.java \
        claude-fx/src/main/java/com/claudefx/ui/SettingsDialog.java
git commit -m "feat(claude-fx): integrate color schemes from config into terminal rendering"
```

---

### Task 19: Dynamic Key Bindings + Missing Shortcuts

**Files:**
- Modify: `claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java`
- Modify: `claude-fx/src/main/java/com/claudefx/config/KeyBindings.java`

- [ ] **Step 1: Add KeyBindings parser method**

Add to `KeyBindings.java`:

```java
import javafx.scene.input.*;
import java.util.ArrayList;
import java.util.List;

public static KeyCombination parse(String binding) {
    if (binding == null || binding.isEmpty()) return null;
    String[] parts = binding.split("\\+");
    List<KeyCombination.Modifier> mods = new ArrayList<>();
    String key = parts[parts.length - 1];

    for (int i = 0; i < parts.length - 1; i++) {
        switch (parts[i].toLowerCase()) {
            case "ctrl" -> mods.add(KeyCombination.CONTROL_DOWN);
            case "shift" -> mods.add(KeyCombination.SHIFT_DOWN);
            case "alt" -> mods.add(KeyCombination.ALT_DOWN);
            case "meta" -> mods.add(KeyCombination.META_DOWN);
        }
    }

    try {
        KeyCode code = KeyCode.valueOf(key.toUpperCase());
        return new KeyCodeCombination(code, mods.toArray(new KeyCombination.Modifier[0]));
    } catch (IllegalArgumentException e) {
        // Handle special names
        return switch (key.toLowerCase()) {
            case "plus" -> new KeyCodeCombination(KeyCode.PLUS, mods.toArray(new KeyCombination.Modifier[0]));
            case "minus" -> new KeyCodeCombination(KeyCode.MINUS, mods.toArray(new KeyCombination.Modifier[0]));
            case "comma" -> new KeyCodeCombination(KeyCode.COMMA, mods.toArray(new KeyCombination.Modifier[0]));
            case "tab" -> new KeyCodeCombination(KeyCode.TAB, mods.toArray(new KeyCombination.Modifier[0]));
            default -> null;
        };
    }
}
```

- [ ] **Step 2: Rewrite setupKeyBindings to use config**

Replace hardcoded accelerators in `ClaudeFxApplication.setupKeyBindings`:

```java
private void setupKeyBindings(Scene scene) {
    var kb = config.getKeyBindings();

    registerAccelerator(scene, kb.getNewTab(), () -> mainWindow.newTab());
    registerAccelerator(scene, kb.getCloseTab(), () -> mainWindow.closeCurrentTab());
    registerAccelerator(scene, kb.getNextTab(), () -> mainWindow.nextTab());
    registerAccelerator(scene, kb.getPrevTab(), () -> mainWindow.prevTab());
    registerAccelerator(scene, kb.getSearch(), () -> mainWindow.toggleSearch());
    registerAccelerator(scene, kb.getExportLog(), () -> mainWindow.exportLog());
    registerAccelerator(scene, kb.getSettings(), () -> {
        var dialog = new SettingsDialog(config);
        dialog.showAndWait().ifPresent(result -> {
            try { configStore.saveConfig(result); }
            catch (Exception ex) { System.err.println("Failed to save config: " + ex.getMessage()); }
        });
    });
    registerAccelerator(scene, kb.getCopy(), () -> {
        var session = mainWindow.getSessionManager().getActiveSession();
        if (session != null) {
            String text = session.getTerminal().getSelectedText();
            if (!text.isEmpty()) {
                var content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
            }
        }
    });
    registerAccelerator(scene, kb.getPaste(), () -> {
        var session = mainWindow.getSessionManager().getActiveSession();
        if (session != null) {
            String text = Clipboard.getSystemClipboard().getString();
            if (text != null) {
                try { session.getProcess().write(text.getBytes()); }
                catch (Exception ex) { /* ignore */ }
            }
        }
    });
    registerAccelerator(scene, kb.getZoomIn(), () -> mainWindow.zoomIn());
    registerAccelerator(scene, kb.getZoomOut(), () -> mainWindow.zoomOut());
}

private void registerAccelerator(Scene scene, String binding, Runnable action) {
    var combo = KeyBindings.parse(binding);
    if (combo != null) scene.getAccelerators().put(combo, action);
}
```

- [ ] **Step 3: Add zoom methods to MainWindow**

```java
public void zoomIn() {
    int newSize = config.getTerminal().getFontSize() + 1;
    config.getTerminal().setFontSize(Math.min(36, newSize));
    applyFontToAllTabs();
}

public void zoomOut() {
    int newSize = config.getTerminal().getFontSize() - 1;
    config.getTerminal().setFontSize(Math.max(8, newSize));
    applyFontToAllTabs();
}

private void applyFontToAllTabs() {
    for (var session : sessionManager.getSessions()) {
        session.getTerminal().setFont(
            config.getTerminal().getFontFamily(),
            config.getTerminal().getFontSize()
        );
    }
}
```

- [ ] **Step 4: Verify compiles**

Run: `cd claude-fx && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add claude-fx/src/main/java/com/claudefx/ClaudeFxApplication.java \
        claude-fx/src/main/java/com/claudefx/config/KeyBindings.java \
        claude-fx/src/main/java/com/claudefx/ui/MainWindow.java
git commit -m "feat(claude-fx): dynamic key bindings from config, add copy/paste/zoom shortcuts"
```

---

### Task 20: Additional Tests

**Files:**
- Create: `claude-fx/src/test/java/com/claudefx/config/SessionStateTest.java`
- Modify: `claude-fx/src/test/java/com/claudefx/terminal/TerminalBufferTest.java`

- [ ] **Step 1: Write SessionState serialization test**

```java
package com.claudefx.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SessionStateTest {

    @Test
    void roundTripSerialization() throws Exception {
        var mapper = new ObjectMapper();
        var state = new SessionState();
        var tab = new SessionState.TabState();
        tab.setWorkingDir("/home/user/project");
        tab.setTitle("my-project");
        tab.setPosition(0);
        state.setTabs(List.of(tab));
        state.setActiveTabIndex(0);

        String json = mapper.writeValueAsString(state);
        var restored = mapper.readValue(json, SessionState.class);

        assertThat(restored.getTabs()).hasSize(1);
        assertThat(restored.getTabs().get(0).getWorkingDir()).isEqualTo("/home/user/project");
        assertThat(restored.getActiveTabIndex()).isZero();
    }
}
```

- [ ] **Step 2: Add TerminalBuffer resize test**

Add to `TerminalBufferTest`:

```java
@Test
void resizePreservesContent() {
    var buf = new TerminalBuffer(10, 3, 100);
    buf.putChar('A');
    buf.putChar('B');
    buf.resize(20, 5);
    assertThat(buf.getColumns()).isEqualTo(20);
    assertThat(buf.getRows()).isEqualTo(5);
    assertThat(buf.getCell(0, 0).getCodepoint()).isEqualTo('A');
    assertThat(buf.getCell(0, 1).getCodepoint()).isEqualTo('B');
}

@Test
void resizeShrinkClampsCursor() {
    var buf = new TerminalBuffer(10, 10, 100);
    buf.setCursorPosition(8, 8);
    buf.resize(5, 5);
    assertThat(buf.getCursorRow()).isEqualTo(4);
    assertThat(buf.getCursorCol()).isEqualTo(4);
}
```

- [ ] **Step 3: Run all tests**

Run: `cd claude-fx && ./gradlew test -v`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add claude-fx/src/test/java/com/claudefx/
git commit -m "test(claude-fx): add SessionState roundtrip and TerminalBuffer resize tests"
```

---

### Task 21: Run All Tests and Final Build

**Files:** None new — verification only.

- [ ] **Step 1: Run full test suite**

Run: `cd claude-fx && ./gradlew test -v`
Expected: All tests PASS

- [ ] **Step 2: Build shadow JAR**

Run: `cd claude-fx && ./gradlew shadowJar`
Expected: BUILD SUCCESSFUL, JAR at `claude-fx/build/libs/claude-fx-0.1.0.jar`

- [ ] **Step 3: Smoke test — launch the app**

Run: `cd claude-fx && ./gradlew run`
Expected: Window opens with one tab, terminal renders, can type and see Claude Code output (if `claude` is on PATH). If not on PATH, error dialog should appear.

- [ ] **Step 4: Verify all spec features**

Manually verify:
- [ ] Multiple tabs (Ctrl+T to create, Ctrl+W to close)
- [ ] Tab drag-and-drop reorder
- [ ] Double-click tab to rename
- [ ] Terminal renders ANSI colors, bold, cursor movement
- [ ] Ctrl+C sends SIGINT to process
- [ ] Right-click context menu (copy/paste/select all)
- [ ] Search (Ctrl+F) with highlights in scrollback
- [ ] Log export in plain text and ANSI format
- [ ] Settings dialog (Ctrl+,) saves to ~/.claude-fx/config.json
- [ ] Session persistence — close and reopen, tabs restore
- [ ] Process restart on Enter when exited
- [ ] Window resize propagates to PTY (SIGWINCH)

- [ ] **Step 5: Commit any final fixes and tag**

```bash
git add -A
git commit -m "feat(claude-fx): complete initial build with all features"
```
