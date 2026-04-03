# The Godfather Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a JavaFX desktop app that orchestrates multiple Claude Code sessions across git worktrees with an IDE-like UI (terminal, file explorer, diff viewer).

**Architecture:** Three-layer architecture — UI (JavaFX), Orchestration (pty4j process management, agent registry), Git (worktree/diff operations). Terminal uses pty4j for PTY + xterm.js in a JavaFX WebView for rendering. Code viewer uses RichTextFX. MCP client connects to code-navigator only (agent-memory removed).

**Tech Stack:** Java 21, JavaFX 21, pty4j 0.13.10, RichTextFX 0.11.5, xterm.js 5.5.0 (bundled), Jackson 2.18.2, JUnit 5, Gradle + Shadow plugin

**Deferred from MVP:** MCP integration (McpBridge for code-navigator) is defined in the design spec but deferred to a follow-up task after the core app is working. TerminalFX replaced by pty4j + xterm.js WebView (TerminalFX is unmaintained).

**Target directory:** A new standalone repository. The user will create it. All paths below are relative to the project root.

---

### Task 1: Scaffold Project

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `src/main/java/com/godfather/App.java`
- Create: `src/main/resources/styles/godfather.css`
- Create: `.gitignore`

- [ ] **Step 1: Create `settings.gradle`**

```groovy
rootProject.name = 'the-godfather'
```

- [ ] **Step 2: Create `build.gradle`**

```groovy
plugins {
    id 'java'
    id 'application'
    id 'org.openjfx.javafxplugin' version '0.1.0'
    id 'com.gradleup.shadow' version '9.0.0-beta12'
}

group = 'com.godfather'
version = '0.1.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = 'com.godfather.App'
}

javafx {
    version = '21.0.2'
    modules = ['javafx.controls', 'javafx.web']
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.jetbrains.pty4j:pty4j:0.13.10'
    implementation 'org.fxmisc.richtext:richtextfx:0.11.5'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'

    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core:3.27.3'
}

tasks.named('test') {
    useJUnitPlatform()
}

shadowJar {
    archiveBaseName = 'the-godfather'
    archiveClassifier = ''
    mergeServiceFiles()
}
```

- [ ] **Step 3: Create `.gitignore`**

```
.gradle/
build/
.idea/
*.iml
.godfather/
```

- [ ] **Step 4: Create minimal `App.java`**

```java
package com.godfather;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        var root = new StackPane(new Label("The Godfather"));
        var scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/styles/godfather.css").toExternalForm());
        stage.setTitle("The Godfather");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 5: Create `godfather.css`**

```css
.root {
    -fx-base: #1e1e1e;
    -fx-background: #1e1e1e;
    -fx-control-inner-background: #252526;
    -fx-accent: #007acc;
    -fx-focus-color: #007acc;
    -fx-text-fill: #cccccc;
    -fx-font-family: "Monospace";
    -fx-font-size: 13px;
}

.label {
    -fx-text-fill: #cccccc;
}

.split-pane > .split-pane-divider {
    -fx-background-color: #333333;
    -fx-padding: 0 1 0 1;
}

.tab-pane > .tab-header-area {
    -fx-background-color: #252526;
}

.tab-pane > .tab-header-area > .tab-header-background {
    -fx-background-color: #252526;
}

.tab {
    -fx-background-color: #2d2d2d;
    -fx-text-base-color: #cccccc;
}

.tab:selected {
    -fx-background-color: #1e1e1e;
}

.tree-view {
    -fx-background-color: #252526;
}

.tree-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #cccccc;
}

.tree-cell:selected {
    -fx-background-color: #094771;
}

.button {
    -fx-background-color: #0e639c;
    -fx-text-fill: white;
    -fx-background-radius: 2;
}

.button:hover {
    -fx-background-color: #1177bb;
}

.status-bar {
    -fx-background-color: #007acc;
    -fx-padding: 2 8;
}

.status-bar .label {
    -fx-text-fill: white;
    -fx-font-size: 11px;
}
```

- [ ] **Step 6: Verify the app compiles and launches**

Run: `./gradlew run`
Expected: A dark-themed window opens showing "The Godfather" centered.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: scaffold The Godfather project with JavaFX"
```

---

### Task 2: Data Models

**Files:**
- Create: `src/main/java/com/godfather/model/Worktree.java`
- Create: `src/main/java/com/godfather/model/AgentSession.java`
- Create: `src/main/java/com/godfather/model/AgentStatus.java`
- Create: `src/main/java/com/godfather/model/DiffResult.java`
- Create: `src/test/java/com/godfather/model/WorktreeTest.java`

- [ ] **Step 1: Write test for Worktree model**

```java
package com.godfather.model;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class WorktreeTest {

    @Test
    void createWorktree() {
        var wt = new Worktree("feat-login", "feature/login", Path.of("/tmp/worktrees/feat-login"));
        assertThat(wt.name()).isEqualTo("feat-login");
        assertThat(wt.branch()).isEqualTo("feature/login");
        assertThat(wt.path()).isEqualTo(Path.of("/tmp/worktrees/feat-login"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.godfather.model.WorktreeTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Create models**

`Worktree.java`:
```java
package com.godfather.model;

import java.nio.file.Path;
import java.time.Instant;

public record Worktree(
    String name,
    String branch,
    Path path,
    Instant createdAt
) {
    public Worktree(String name, String branch, Path path) {
        this(name, branch, path, Instant.now());
    }
}
```

`AgentStatus.java`:
```java
package com.godfather.model;

public enum AgentStatus {
    IDLE, RUNNING, FINISHED, ERROR
}
```

`AgentSession.java`:
```java
package com.godfather.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

public class AgentSession {
    private final Worktree worktree;
    private final ObjectProperty<AgentStatus> status = new SimpleObjectProperty<>(AgentStatus.IDLE);
    private Process process;

    public AgentSession(Worktree worktree) {
        this.worktree = worktree;
    }

    public Worktree worktree() { return worktree; }
    public AgentStatus getStatus() { return status.get(); }
    public ObjectProperty<AgentStatus> statusProperty() { return status; }
    public void setStatus(AgentStatus s) { status.set(s); }
    public Process getProcess() { return process; }
    public void setProcess(Process p) { this.process = p; }
}
```

`DiffResult.java`:
```java
package com.godfather.model;

import java.util.List;

public record DiffResult(
    String filePath,
    List<DiffHunk> hunks
) {
    public record DiffHunk(
        int oldStart, int oldCount,
        int newStart, int newCount,
        List<DiffLine> lines
    ) {}

    public record DiffLine(Type type, String content) {
        public enum Type { CONTEXT, ADD, REMOVE }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.godfather.model.WorktreeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add data models (Worktree, AgentSession, DiffResult)"
```

---

### Task 3: WorktreeManager

**Files:**
- Create: `src/main/java/com/godfather/core/WorktreeManager.java`
- Create: `src/main/java/com/godfather/core/CommandRunner.java`
- Create: `src/test/java/com/godfather/core/WorktreeManagerTest.java`

- [ ] **Step 1: Write tests for WorktreeManager**

```java
package com.godfather.core;

import com.godfather.model.Worktree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorktreeManagerTest {

    @TempDir Path tempDir;
    private WorktreeManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Init a real git repo in tempDir
        run(tempDir, "git", "init");
        run(tempDir, "git", "commit", "--allow-empty", "-m", "init");
        manager = new WorktreeManager(tempDir);
    }

    @Test
    void createAndListWorktree() {
        Worktree wt = manager.create("test-branch", "test-wt");
        assertThat(wt.name()).isEqualTo("test-wt");
        assertThat(wt.branch()).isEqualTo("test-branch");
        assertThat(wt.path()).exists();

        List<Worktree> list = manager.list();
        assertThat(list).extracting(Worktree::name).contains("test-wt");
    }

    @Test
    void removeWorktree() {
        Worktree wt = manager.create("rm-branch", "rm-wt");
        assertThat(wt.path()).exists();

        manager.remove(wt);
        assertThat(wt.path()).doesNotExist();
    }

    private void run(Path dir, String... cmd) throws Exception {
        new ProcessBuilder(cmd).directory(dir.toFile()).start().waitFor();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.godfather.core.WorktreeManagerTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement CommandRunner**

```java
package com.godfather.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class CommandRunner {

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean success() { return exitCode == 0; }
    }

    public static Result run(Path workDir, String... command) {
        try {
            var pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
            var process = pb.start();
            var stdout = new String(process.getInputStream().readAllBytes());
            var stderr = new String(process.getErrorStream().readAllBytes());
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, stdout, "Process timed out");
            }
            return new Result(process.exitValue(), stdout.trim(), stderr.trim());
        } catch (IOException | InterruptedException e) {
            return new Result(-1, "", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Implement WorktreeManager**

```java
package com.godfather.core;

import com.godfather.model.Worktree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WorktreeManager {

    private final Path repoRoot;
    private final Path worktreeDir;

    public WorktreeManager(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.worktreeDir = repoRoot.resolve(".godfather").resolve("worktrees");
    }

    public Worktree create(String branch, String name) {
        try {
            Files.createDirectories(worktreeDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create worktree directory", e);
        }
        Path wtPath = worktreeDir.resolve(name);
        var result = CommandRunner.run(repoRoot, "git", "worktree", "add", "-b", branch, wtPath.toString());
        if (!result.success()) {
            throw new RuntimeException("git worktree add failed: " + result.stderr());
        }
        return new Worktree(name, branch, wtPath);
    }

    public List<Worktree> list() {
        var result = CommandRunner.run(repoRoot, "git", "worktree", "list", "--porcelain");
        if (!result.success()) {
            return List.of();
        }
        return parseWorktreeList(result.stdout());
    }

    public void remove(Worktree worktree) {
        var result = CommandRunner.run(repoRoot, "git", "worktree", "remove", worktree.path().toString(), "--force");
        if (!result.success()) {
            throw new RuntimeException("git worktree remove failed: " + result.stderr());
        }
    }

    public Path repoRoot() { return repoRoot; }

    private List<Worktree> parseWorktreeList(String output) {
        var worktrees = new ArrayList<Worktree>();
        String[] blocks = output.split("\n\n");
        for (String block : blocks) {
            String[] lines = block.strip().split("\n");
            String path = null;
            String branch = null;
            for (String line : lines) {
                if (line.startsWith("worktree ")) {
                    path = line.substring("worktree ".length());
                } else if (line.startsWith("branch ")) {
                    branch = line.substring("branch refs/heads/".length());
                }
            }
            if (path != null && branch != null) {
                Path wtPath = Path.of(path);
                // Skip the main worktree (the repo itself)
                if (!wtPath.equals(repoRoot)) {
                    String name = wtPath.getFileName().toString();
                    worktrees.add(new Worktree(name, branch, wtPath));
                }
            }
        }
        return worktrees;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.godfather.core.WorktreeManagerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add WorktreeManager with git worktree operations"
```

---

### Task 4: DiffEngine

**Files:**
- Create: `src/main/java/com/godfather/core/DiffEngine.java`
- Create: `src/test/java/com/godfather/core/DiffEngineTest.java`

- [ ] **Step 1: Write test for DiffEngine**

```java
package com.godfather.core;

import com.godfather.model.DiffResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DiffEngineTest {

    @Test
    void parseUnifiedDiff() {
        String diff = """
                diff --git a/Hello.java b/Hello.java
                --- a/Hello.java
                +++ b/Hello.java
                @@ -1,3 +1,4 @@
                 public class Hello {
                -    // old
                +    // new
                +    int x;
                 }
                """;
        List<DiffResult> results = DiffEngine.parse(diff);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).filePath()).isEqualTo("b/Hello.java");
        assertThat(results.get(0).hunks()).hasSize(1);

        var hunk = results.get(0).hunks().get(0);
        assertThat(hunk.lines()).hasSize(4);
        assertThat(hunk.lines().get(1).type()).isEqualTo(DiffResult.DiffLine.Type.REMOVE);
        assertThat(hunk.lines().get(2).type()).isEqualTo(DiffResult.DiffLine.Type.ADD);
    }

    @Test
    void emptyDiff() {
        assertThat(DiffEngine.parse("")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.godfather.core.DiffEngineTest"`
Expected: FAIL

- [ ] **Step 3: Implement DiffEngine**

```java
package com.godfather.core;

import com.godfather.model.DiffResult;
import com.godfather.model.DiffResult.DiffHunk;
import com.godfather.model.DiffResult.DiffLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiffEngine {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@");

    public static List<DiffResult> forWorktree(Path worktreePath) {
        var result = CommandRunner.run(worktreePath, "git", "diff");
        if (!result.success() || result.stdout().isBlank()) {
            return List.of();
        }
        return parse(result.stdout());
    }

    public static List<String> statusForWorktree(Path worktreePath) {
        var result = CommandRunner.run(worktreePath, "git", "status", "--porcelain");
        if (!result.success()) return List.of();
        return result.stdout().lines().filter(l -> !l.isBlank()).toList();
    }

    public static List<DiffResult> parse(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return List.of();

        var results = new ArrayList<DiffResult>();
        String[] lines = unifiedDiff.split("\n");
        String currentFile = null;
        List<DiffHunk> currentHunks = null;
        List<DiffLine> currentLines = null;
        int oldStart = 0, oldCount = 0, newStart = 0, newCount = 0;

        for (String line : lines) {
            if (line.startsWith("+++ ")) {
                if (currentFile != null && currentHunks != null) {
                    if (currentLines != null) {
                        currentHunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, currentLines));
                    }
                    results.add(new DiffResult(currentFile, currentHunks));
                }
                currentFile = line.substring(4);
                currentHunks = new ArrayList<>();
                currentLines = null;
            } else if (line.startsWith("--- ")) {
                // skip, we use +++ for file path
            } else if (line.startsWith("@@")) {
                if (currentLines != null && currentHunks != null) {
                    currentHunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, currentLines));
                }
                Matcher m = HUNK_HEADER.matcher(line);
                if (m.find()) {
                    oldStart = Integer.parseInt(m.group(1));
                    oldCount = Integer.parseInt(m.group(2));
                    newStart = Integer.parseInt(m.group(3));
                    newCount = Integer.parseInt(m.group(4));
                }
                currentLines = new ArrayList<>();
            } else if (currentLines != null) {
                if (line.startsWith("+")) {
                    currentLines.add(new DiffLine(DiffLine.Type.ADD, line.substring(1)));
                } else if (line.startsWith("-")) {
                    currentLines.add(new DiffLine(DiffLine.Type.REMOVE, line.substring(1)));
                } else if (line.startsWith(" ")) {
                    currentLines.add(new DiffLine(DiffLine.Type.CONTEXT, line.substring(1)));
                }
            }
        }
        // Flush last file
        if (currentFile != null && currentHunks != null) {
            if (currentLines != null) {
                currentHunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, currentLines));
            }
            results.add(new DiffResult(currentFile, currentHunks));
        }
        return results;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.godfather.core.DiffEngineTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add DiffEngine for parsing unified diffs"
```

---

### Task 5: ProcessManager (Claude Code PTY)

**Files:**
- Create: `src/main/java/com/godfather/core/ProcessManager.java`
- Create: `src/test/java/com/godfather/core/ProcessManagerTest.java`

- [ ] **Step 1: Write test for ProcessManager**

```java
package com.godfather.core;

import com.godfather.model.AgentSession;
import com.godfather.model.AgentStatus;
import com.godfather.model.Worktree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessManagerTest {

    @TempDir Path tempDir;

    @Test
    void spawnAndStopProcess() throws Exception {
        var wt = new Worktree("test", "main", tempDir);
        var session = new AgentSession(wt);
        var manager = new ProcessManager(false); // testMode=false uses direct status updates

        // Spawn a simple echo process instead of claude
        manager.spawn(session, "echo", "hello");

        // Wait for process to finish
        var latch = new CountDownLatch(1);
        session.statusProperty().addListener((obs, old, val) -> {
            if (val == AgentStatus.FINISHED || val == AgentStatus.ERROR) {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);

        assertThat(session.getStatus()).isIn(AgentStatus.FINISHED, AgentStatus.ERROR);
    }

    @Test
    void stopRunningProcess() throws Exception {
        var wt = new Worktree("test", "main", tempDir);
        var session = new AgentSession(wt);
        var manager = new ProcessManager(false);

        // Spawn a long-running process
        manager.spawn(session, "sleep", "60");
        assertThat(session.getStatus()).isEqualTo(AgentStatus.RUNNING);

        manager.stop(session);
        Thread.sleep(100); // Give destroyForcibly time
        assertThat(session.getProcess().isAlive()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.godfather.core.ProcessManagerTest"`
Expected: FAIL

- [ ] **Step 3: Implement ProcessManager**

Uses pty4j for proper PTY support so Claude Code gets a real terminal with ANSI escape sequences, isatty() checks, and proper signal handling.

```java
package com.godfather.core;

import com.godfather.model.AgentSession;
import com.godfather.model.AgentStatus;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import javafx.application.Platform;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProcessManager {

    private final Map<AgentSession, Thread> monitorThreads = new ConcurrentHashMap<>();
    private final boolean usePlatformRunLater;

    public ProcessManager() {
        this(true);
    }

    public ProcessManager(boolean usePlatformRunLater) {
        this.usePlatformRunLater = usePlatformRunLater;
    }

    public void spawn(AgentSession session, String... command) {
        try {
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");

            PtyProcess process = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(env)
                .setDirectory(session.worktree().path().toString())
                .setConsole(false)
                .setInitialColumns(120)
                .setInitialRows(40)
                .start();

            session.setProcess(process);
            session.setStatus(AgentStatus.RUNNING);

            var monitor = new Thread(() -> {
                try {
                    process.waitFor();
                    updateStatus(session, process.exitValue() == 0
                        ? AgentStatus.FINISHED : AgentStatus.ERROR);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "agent-monitor-" + session.worktree().name());
            monitor.setDaemon(true);
            monitor.start();
            monitorThreads.put(session, monitor);
        } catch (Exception e) {
            session.setStatus(AgentStatus.ERROR);
        }
    }

    public void spawnClaude(AgentSession session) {
        spawn(session, "claude", "--no-update-check");
    }

    public void stop(AgentSession session) {
        var process = session.getProcess();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            updateStatus(session, AgentStatus.FINISHED);
        }
    }

    public void stopAll() {
        monitorThreads.keySet().forEach(this::stop);
    }

    public InputStream getOutputStream(AgentSession session) {
        var process = session.getProcess();
        return process != null ? process.getInputStream() : null;
    }

    public OutputStream getInputStream(AgentSession session) {
        var process = session.getProcess();
        return process != null ? process.getOutputStream() : null;
    }

    public void resize(AgentSession session, int cols, int rows) {
        var process = session.getProcess();
        if (process instanceof PtyProcess pty) {
            pty.setWinSize(new WinSize(cols, rows));
        }
    }

    private void updateStatus(AgentSession session, AgentStatus status) {
        if (usePlatformRunLater) {
            Platform.runLater(() -> session.setStatus(status));
        } else {
            session.setStatus(status);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.godfather.core.ProcessManagerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add ProcessManager for spawning and monitoring agents"
```

---

### Task 6: AgentRegistry

**Files:**
- Create: `src/main/java/com/godfather/core/AgentRegistry.java`
- Create: `src/test/java/com/godfather/core/AgentRegistryTest.java`

- [ ] **Step 1: Write test**

```java
package com.godfather.core;

import com.godfather.model.AgentSession;
import com.godfather.model.AgentStatus;
import com.godfather.model.Worktree;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class AgentRegistryTest {

    @Test
    void registerAndLookup() {
        var registry = new AgentRegistry();
        var wt = new Worktree("feat-x", "feature/x", Path.of("/tmp/feat-x"));
        var session = registry.register(wt);

        assertThat(session.worktree()).isEqualTo(wt);
        assertThat(session.getStatus()).isEqualTo(AgentStatus.IDLE);
        assertThat(registry.get("feat-x")).isPresent();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void unregister() {
        var registry = new AgentRegistry();
        var wt = new Worktree("feat-x", "feature/x", Path.of("/tmp/feat-x"));
        registry.register(wt);
        registry.unregister("feat-x");
        assertThat(registry.get("feat-x")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.godfather.core.AgentRegistryTest"`
Expected: FAIL

- [ ] **Step 3: Implement AgentRegistry**

```java
package com.godfather.core;

import com.godfather.model.AgentSession;
import com.godfather.model.Worktree;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Optional;

public class AgentRegistry {

    private final ObservableList<AgentSession> sessions = FXCollections.observableArrayList();

    public AgentSession register(Worktree worktree) {
        var session = new AgentSession(worktree);
        sessions.add(session);
        return session;
    }

    public void unregister(String worktreeName) {
        sessions.removeIf(s -> s.worktree().name().equals(worktreeName));
    }

    public Optional<AgentSession> get(String worktreeName) {
        return sessions.stream()
            .filter(s -> s.worktree().name().equals(worktreeName))
            .findFirst();
    }

    public ObservableList<AgentSession> all() {
        return sessions;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.godfather.core.AgentRegistryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add AgentRegistry for tracking agent sessions"
```

---

### Task 7: Main Window Layout (Sidebar + Content Area + Status Bar)

**Files:**
- Create: `src/main/java/com/godfather/ui/MainWindow.java`
- Create: `src/main/java/com/godfather/ui/Sidebar.java`
- Create: `src/main/java/com/godfather/ui/ContentArea.java`
- Create: `src/main/java/com/godfather/ui/StatusBar.java`
- Modify: `src/main/java/com/godfather/App.java`

- [ ] **Step 1: Create StatusBar**

```java
package com.godfather.ui;

import com.godfather.core.AgentRegistry;
import com.godfather.model.AgentStatus;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

public class StatusBar extends HBox {

    private final Label agentCount = new Label("0 agents");
    private final Label activeAgent = new Label("No active agent");

    public StatusBar(AgentRegistry registry) {
        getStyleClass().add("status-bar");
        setSpacing(20);
        getChildren().addAll(agentCount, activeAgent);

        Runnable updateCounts = () -> {
            long running = registry.all().stream()
                .filter(s -> s.getStatus() == AgentStatus.RUNNING)
                .count();
            agentCount.setText(registry.all().size() + " agents, " + running + " running");
        };

        registry.all().addListener((javafx.collections.ListChangeListener<? super com.godfather.model.AgentSession>) change -> {
            while (change.next()) {
                for (var added : change.getAddedSubList()) {
                    added.statusProperty().addListener((obs, old, s) -> updateCounts.run());
                }
            }
            updateCounts.run();
        });
    }

    public void setActiveAgent(String name) {
        activeAgent.setText(name != null ? name : "No active agent");
    }
}
```

- [ ] **Step 2: Create ContentArea**

```java
package com.godfather.ui;

import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;

public class ContentArea extends StackPane {

    private final TabPane tabPane = new TabPane();
    private final Label placeholder = new Label("Select a worktree to begin");

    public ContentArea() {
        placeholder.setStyle("-fx-text-fill: #666; -fx-font-size: 16px;");
        getChildren().add(placeholder);
    }

    public TabPane getTabPane() { return tabPane; }

    public void showTabs() {
        getChildren().setAll(tabPane);
    }

    public void showPlaceholder() {
        getChildren().setAll(placeholder);
    }
}
```

- [ ] **Step 3: Create Sidebar**

```java
package com.godfather.ui;

import com.godfather.core.AgentRegistry;
import com.godfather.model.AgentSession;
import com.godfather.model.AgentStatus;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.function.Consumer;

public class Sidebar extends VBox {

    private final ListView<AgentSession> worktreeList = new ListView<>();
    private Consumer<AgentSession> onSelect;

    public Sidebar(AgentRegistry registry) {
        setPrefWidth(220);
        setMinWidth(200);
        setStyle("-fx-background-color: #252526;");
        setPadding(new Insets(8));
        setSpacing(8);

        var projectLabel = new Label("WORKTREES");
        projectLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11px; -fx-font-weight: bold;");

        worktreeList.setItems(registry.all());
        worktreeList.setCellFactory(lv -> new WorktreeCell());
        worktreeList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && onSelect != null) {
                onSelect.accept(selected);
            }
        });

        getChildren().addAll(projectLabel, worktreeList);
    }

    public void setOnSelect(Consumer<AgentSession> handler) {
        this.onSelect = handler;
    }

    private static class WorktreeCell extends ListCell<AgentSession> {
        @Override
        protected void updateItem(AgentSession session, boolean empty) {
            super.updateItem(session, empty);
            if (empty || session == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(session.worktree().name() + " (" + session.worktree().branch() + ")");
                var dot = new Circle(5);
                dot.setFill(colorFor(session.getStatus()));
                setGraphic(dot);
                session.statusProperty().addListener((obs, old, s) -> dot.setFill(colorFor(s)));
            }
        }

        private Color colorFor(AgentStatus status) {
            return switch (status) {
                case RUNNING -> Color.web("#4ec9b0");
                case ERROR -> Color.web("#f44747");
                case FINISHED -> Color.web("#dcdcaa");
                case IDLE -> Color.web("#666666");
            };
        }
    }
}
```

- [ ] **Step 4: Create MainWindow**

```java
package com.godfather.ui;

import com.godfather.core.AgentRegistry;
import com.godfather.core.ProcessManager;
import com.godfather.core.WorktreeManager;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;

public class MainWindow extends BorderPane {

    private final WorktreeManager worktreeManager;
    private final ProcessManager processManager;
    private final AgentRegistry agentRegistry;
    private final Sidebar sidebar;
    private final ContentArea contentArea;
    private final StatusBar statusBar;

    public MainWindow(WorktreeManager worktreeManager, ProcessManager processManager, AgentRegistry agentRegistry) {
        this.worktreeManager = worktreeManager;
        this.processManager = processManager;
        this.agentRegistry = agentRegistry;

        this.sidebar = new Sidebar(agentRegistry);
        this.contentArea = new ContentArea();
        this.statusBar = new StatusBar(agentRegistry);

        sidebar.setOnSelect(session -> {
            statusBar.setActiveAgent(session.worktree().name());
            // Content area tab switching handled in Task 8-10
        });

        var splitPane = new SplitPane(sidebar, contentArea);
        splitPane.setDividerPositions(0.18);

        setCenter(splitPane);
        setBottom(statusBar);
    }

    public Sidebar sidebar() { return sidebar; }
    public ContentArea contentArea() { return contentArea; }
    public WorktreeManager worktreeManager() { return worktreeManager; }
    public ProcessManager processManager() { return processManager; }
    public AgentRegistry agentRegistry() { return agentRegistry; }
}
```

- [ ] **Step 5: Update App.java to use MainWindow**

```java
package com.godfather;

import com.godfather.core.AgentRegistry;
import com.godfather.core.ProcessManager;
import com.godfather.core.WorktreeManager;
import com.godfather.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.nio.file.Path;

public class App extends Application {

    private ProcessManager processManager;

    @Override
    public void start(Stage stage) {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Select Git Repository");
        var dir = chooser.showDialog(stage);
        if (dir == null) {
            System.exit(0);
            return;
        }

        var worktreeManager = new WorktreeManager(dir.toPath());
        processManager = new ProcessManager();
        var agentRegistry = new AgentRegistry();

        var mainWindow = new MainWindow(worktreeManager, processManager, agentRegistry);
        var scene = new Scene(mainWindow, 1400, 900);
        scene.getStylesheets().add(getClass().getResource("/styles/godfather.css").toExternalForm());

        stage.setTitle("The Godfather — " + dir.getName());
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> processManager.stopAll());
        stage.show();

        // Load existing worktrees
        for (var wt : worktreeManager.list()) {
            agentRegistry.register(wt);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 6: Verify the app compiles and launches**

Run: `./gradlew run`
Expected: Directory chooser opens, then main window with sidebar and content area appears.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add main window layout with sidebar, content area, status bar"
```

---

### Task 8: Worktree Creation Dialog

**Files:**
- Create: `src/main/java/com/godfather/ui/CreateWorktreeDialog.java`
- Modify: `src/main/java/com/godfather/ui/Sidebar.java`

- [ ] **Step 1: Create dialog**

```java
package com.godfather.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.util.Optional;

public class CreateWorktreeDialog extends Dialog<CreateWorktreeDialog.Result> {

    public record Result(String branchName, String worktreeName) {}

    public CreateWorktreeDialog() {
        setTitle("Create Worktree");
        setHeaderText("Create a new worktree with a branch");

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        var branchField = new TextField();
        branchField.setPromptText("feature/my-feature");
        var nameField = new TextField();
        nameField.setPromptText("my-feature");

        // Auto-fill name from branch
        branchField.textProperty().addListener((obs, old, val) -> {
            if (val.contains("/")) {
                nameField.setText(val.substring(val.lastIndexOf('/') + 1));
            } else {
                nameField.setText(val);
            }
        });

        grid.add(new Label("Branch:"), 0, 0);
        grid.add(branchField, 1, 0);
        grid.add(new Label("Name:"), 0, 1);
        grid.add(nameField, 1, 1);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        branchField.textProperty().addListener((obs, old, val) ->
            okButton.setDisable(val.isBlank() || nameField.getText().isBlank()));
        nameField.textProperty().addListener((obs, old, val) ->
            okButton.setDisable(val.isBlank() || branchField.getText().isBlank()));

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return new Result(branchField.getText().trim(), nameField.getText().trim());
            }
            return null;
        });
    }
}
```

- [ ] **Step 2: Add "Create Worktree" button to Sidebar**

Add the button and wire it up. Modify `Sidebar.java` — add a `Button` at the bottom and a `Consumer<CreateWorktreeDialog.Result>` callback called `onCreate`:

```java
// Add to Sidebar constructor, after getChildren().addAll(projectLabel, worktreeList):
var createBtn = new Button("+ New Worktree");
createBtn.setMaxWidth(Double.MAX_VALUE);
createBtn.setOnAction(e -> {
    var dialog = new CreateWorktreeDialog();
    dialog.showAndWait().ifPresent(result -> {
        if (onCreate != null) onCreate.accept(result);
    });
});
getChildren().add(createBtn);
```

Add field and setter to `Sidebar`:
```java
private Consumer<CreateWorktreeDialog.Result> onCreate;

public void setOnCreate(Consumer<CreateWorktreeDialog.Result> handler) {
    this.onCreate = handler;
}
```

- [ ] **Step 3: Wire creation in MainWindow**

Add to `MainWindow` constructor after `sidebar.setOnSelect(...)`:

```java
sidebar.setOnCreate(result -> {
    try {
        var wt = worktreeManager.create(result.branchName(), result.worktreeName());
        agentRegistry.register(wt);
    } catch (Exception ex) {
        new Alert(Alert.AlertType.ERROR, "Failed to create worktree: " + ex.getMessage()).showAndWait();
    }
});
```

- [ ] **Step 4: Verify manually**

Run: `./gradlew run`
Expected: Click "+ New Worktree", fill branch/name, click OK → worktree appears in sidebar.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add worktree creation dialog"
```

---

### Task 9: Terminal Tab (pty4j + xterm.js WebView)

**Files:**
- Create: `src/main/java/com/godfather/ui/TerminalTab.java`
- Create: `src/main/resources/terminal/terminal.html`
- Modify: `src/main/java/com/godfather/ui/MainWindow.java`

- [ ] **Step 1: Download and bundle xterm.js**

Download xterm.js and fit addon into the resources directory so the app works offline:

```bash
mkdir -p src/main/resources/terminal/lib
curl -L -o src/main/resources/terminal/lib/xterm.min.js "https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.min.js"
curl -L -o src/main/resources/terminal/lib/xterm.min.css "https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/css/xterm.min.css"
curl -L -o src/main/resources/terminal/lib/addon-fit.min.js "https://cdn.jsdelivr.net/npm/@xterm/addon-fit@0.10.0/lib/addon-fit.min.js"
```

- [ ] **Step 2: Create `terminal.html` with bundled xterm.js**

This HTML file is loaded in a JavaFX WebView. It uses locally bundled xterm.js and exposes JS functions that Java calls via WebView's `executeScript`. Data from Java is passed as Base64 to avoid escaping issues with raw terminal output.

```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<link rel="stylesheet" href="lib/xterm.min.css">
<script src="lib/xterm.min.js"></script>
<script src="lib/addon-fit.min.js"></script>
<style>
    html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: #1e1e1e; }
    #terminal { width: 100%; height: 100%; }
</style>
</head>
<body>
<div id="terminal"></div>
<script>
    const term = new Terminal({
        theme: {
            background: '#1e1e1e',
            foreground: '#cccccc',
            cursor: '#cccccc',
            selectionBackground: '#264f78'
        },
        fontSize: 14,
        fontFamily: 'Menlo, Monaco, Consolas, monospace',
        cursorBlink: true
    });
    const fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(document.getElementById('terminal'));
    fitAddon.fit();

    // Java bridge calls this with Base64-encoded data to avoid escaping issues
    function writeToTerminal(base64Data) {
        var binary = atob(base64Data);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        term.write(bytes);
    }

    // Notify Java when user types (via javaBridge object injected by Java)
    term.onData(function(data) {
        if (window.javaBridge) {
            window.javaBridge.onInput(data);
        }
    });

    // Handle resize
    new ResizeObserver(() => {
        fitAddon.fit();
        if (window.javaBridge) {
            window.javaBridge.onResize(term.cols, term.rows);
        }
    }).observe(document.getElementById('terminal'));
</script>
</body>
</html>
```

- [ ] **Step 3: Create TerminalTab**

```java
package com.godfather.ui;

import com.godfather.core.ProcessManager;
import com.godfather.model.AgentSession;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.control.Tab;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class TerminalTab extends Tab {

    private final AgentSession session;
    private final ProcessManager processManager;
    private final WebView webView;
    private WebEngine engine;
    private volatile boolean ready = false;

    public TerminalTab(AgentSession session, ProcessManager processManager) {
        super("Terminal — " + session.worktree().name());
        this.session = session;
        this.processManager = processManager;
        this.webView = new WebView();

        setContent(webView);
        setClosable(false);

        engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaBridge", new JavaBridge());
                ready = true;
            }
        });

        var terminalUrl = getClass().getResource("/terminal/terminal.html");
        engine.load(terminalUrl.toExternalForm());
    }

    public void startAgent() {
        processManager.spawnClaude(session);
        startOutputReader();
    }

    public void startCommand(String... command) {
        processManager.spawn(session, command);
        startOutputReader();
    }

    private void startOutputReader() {
        var input = processManager.getOutputStream(session);
        if (input == null) return;

        var reader = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    var base64 = Base64.getEncoder().encodeToString(chunk);
                    Platform.runLater(() -> {
                        if (ready) {
                            engine.executeScript("writeToTerminal('" + base64 + "')");
                        }
                    });
                }
            } catch (Exception e) {
                // Process ended
            }
        }, "terminal-reader-" + session.worktree().name());
        reader.setDaemon(true);
        reader.start();
    }

    public class JavaBridge {
        public void onInput(String data) {
            try {
                OutputStream os = processManager.getInputStream(session);
                if (os != null) {
                    os.write(data.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        public void onResize(int cols, int rows) {
            processManager.resize(session, cols, rows);
        }
    }
}
```

- [ ] **Step 4: Wire terminal into MainWindow**

Add to `MainWindow` — update `sidebar.setOnSelect` to show terminal tab:

```java
sidebar.setOnSelect(session -> {
    statusBar.setActiveAgent(session.worktree().name());
    contentArea.showTabs();
    contentArea.getTabPane().getTabs().clear();

    var termTab = new TerminalTab(session, processManager);
    contentArea.getTabPane().getTabs().add(termTab);

    // Auto-start agent if idle
    if (session.getStatus() == com.godfather.model.AgentStatus.IDLE) {
        termTab.startAgent();
    }
});
```

- [ ] **Step 5: Verify manually**

Run: `./gradlew run`
Expected: Select repo, create a worktree, click it → terminal tab appears, Claude Code launches in the worktree directory.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add embedded terminal with pty4j + xterm.js WebView"
```

---

### Task 10: Files Tab (File Explorer + Read-Only Editor)

**Files:**
- Create: `src/main/java/com/godfather/ui/FilesTab.java`
- Create: `src/main/resources/styles/syntax.css`
- Modify: `src/main/java/com/godfather/ui/MainWindow.java`

- [ ] **Step 1: Create syntax.css**

```css
.styled-text-area {
    -fx-background-color: #1e1e1e;
}

.styled-text-area .text {
    -fx-fill: #cccccc;
}

.paragraph-box:has-caret {
    -fx-background-color: #2a2d2e;
}

.keyword {
    -fx-fill: #569cd6;
    -fx-font-weight: bold;
}

.string {
    -fx-fill: #ce9178;
}

.comment {
    -fx-fill: #6a9955;
}

.type {
    -fx-fill: #4ec9b0;
}

.number {
    -fx-fill: #b5cea8;
}

.annotation {
    -fx-fill: #dcdcaa;
}

.lineno {
    -fx-background-color: #1e1e1e;
}
```

- [ ] **Step 2: Create FilesTab**

```java
package com.godfather.ui;

import com.godfather.model.AgentSession;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilesTab extends Tab {

    private static final Pattern JAVA_PATTERN = Pattern.compile(
        "(?<KEYWORD>\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|record|return|sealed|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|yield)\\b)"
        + "|(?<STRING>\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\")"
        + "|(?<COMMENT>//[^\n]*|/\\*[\\s\\S]*?\\*/)"
        + "|(?<ANNOTATION>@\\w+)"
        + "|(?<NUMBER>\\b\\d+\\.?\\d*[fFdDlL]?\\b)"
        + "|(?<TYPE>\\b[A-Z][\\w]*\\b)"
    );

    private final TreeView<PathItem> fileTree;
    private final CodeArea codeArea;

    public FilesTab(AgentSession session) {
        super("Files — " + session.worktree().name());
        setClosable(false);

        fileTree = new TreeView<>();
        fileTree.setPrefWidth(250);
        fileTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(PathItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name);
            }
        });

        codeArea = new CodeArea();
        codeArea.setEditable(false);
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStylesheets().add(getClass().getResource("/styles/syntax.css").toExternalForm());

        var split = new SplitPane(fileTree, codeArea);
        split.setDividerPositions(0.25);
        setContent(split);

        loadFileTree(session.worktree().path());

        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.getValue() != null && !selected.getValue().isDirectory) {
                openFile(selected.getValue().path);
            }
        });
    }

    private void loadFileTree(Path root) {
        var rootItem = createTreeItem(root);
        rootItem.setExpanded(true);
        fileTree.setRoot(rootItem);
    }

    private TreeItem<PathItem> createTreeItem(Path path) {
        var name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        boolean isDir = Files.isDirectory(path);
        var item = new TreeItem<>(new PathItem(name, path, isDir));

        if (isDir) {
            try (var stream = Files.list(path)) {
                stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("node_modules"))
                    .filter(p -> !p.getFileName().toString().equals("build"))
                    .filter(p -> !p.getFileName().toString().equals("target"))
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    })
                    .forEach(p -> item.getChildren().add(createTreeItem(p)));
            } catch (IOException e) {
                // skip unreadable dirs
            }
        }
        return item;
    }

    private void openFile(Path file) {
        try {
            String content = Files.readString(file);
            codeArea.replaceText(content);
            if (file.toString().endsWith(".java")) {
                applyJavaHighlighting(content);
            }
        } catch (IOException e) {
            codeArea.replaceText("Error reading file: " + e.getMessage());
        }
    }

    private void applyJavaHighlighting(String text) {
        var spansBuilder = new StyleSpansBuilder<Collection<String>>();
        Matcher matcher = JAVA_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String styleClass =
                matcher.group("KEYWORD") != null ? "keyword" :
                matcher.group("STRING") != null ? "string" :
                matcher.group("COMMENT") != null ? "comment" :
                matcher.group("ANNOTATION") != null ? "annotation" :
                matcher.group("NUMBER") != null ? "number" :
                matcher.group("TYPE") != null ? "type" : "";
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastEnd);
        codeArea.setStyleSpans(0, spansBuilder.create());
    }

    record PathItem(String name, Path path, boolean isDirectory) {}
}
```

- [ ] **Step 3: Add Files tab to MainWindow**

In `MainWindow`, update `sidebar.setOnSelect` — after adding the TerminalTab, also add a FilesTab:

```java
var filesTab = new FilesTab(session);
contentArea.getTabPane().getTabs().add(filesTab);
```

- [ ] **Step 4: Verify manually**

Run: `./gradlew run`
Expected: Select a worktree → Files tab shows file tree, clicking a `.java` file shows syntax-highlighted content.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add file explorer with read-only syntax-highlighted code viewer"
```

---

### Task 11: Diff Tab

**Files:**
- Create: `src/main/java/com/godfather/ui/DiffTab.java`
- Modify: `src/main/java/com/godfather/ui/MainWindow.java`

- [ ] **Step 1: Create DiffTab**

```java
package com.godfather.ui;

import com.godfather.core.DiffEngine;
import com.godfather.model.AgentSession;
import com.godfather.model.DiffResult;
import com.godfather.model.DiffResult.DiffLine;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;

public class DiffTab extends Tab {

    private final VBox diffContent = new VBox();
    private final AgentSession session;

    public DiffTab(AgentSession session) {
        super("Diff — " + session.worktree().name());
        this.session = session;
        setClosable(false);

        diffContent.setSpacing(12);
        diffContent.setPadding(new Insets(12));
        diffContent.setStyle("-fx-background-color: #1e1e1e;");

        var scrollPane = new ScrollPane(diffContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: #1e1e1e;");
        setContent(scrollPane);

        var refreshBtn = new Button("Refresh Diff");
        refreshBtn.setOnAction(e -> refresh());
        diffContent.getChildren().add(refreshBtn);

        refresh();
    }

    public void refresh() {
        diffContent.getChildren().clear();

        var refreshBtn = new Button("Refresh Diff");
        refreshBtn.setOnAction(e -> refresh());
        diffContent.getChildren().add(refreshBtn);

        List<DiffResult> diffs = DiffEngine.forWorktree(session.worktree().path());
        if (diffs.isEmpty()) {
            var noChanges = new Label("No changes detected");
            noChanges.setStyle("-fx-text-fill: #666;");
            diffContent.getChildren().add(noChanges);
            return;
        }

        for (var diff : diffs) {
            var fileLabel = new Label(diff.filePath());
            fileLabel.setStyle("-fx-text-fill: #569cd6; -fx-font-weight: bold; -fx-font-size: 14px;");
            diffContent.getChildren().add(fileLabel);

            for (var hunk : diff.hunks()) {
                var hunkBox = new VBox();
                hunkBox.setSpacing(0);
                hunkBox.setStyle("-fx-background-color: #252526; -fx-padding: 4; -fx-background-radius: 4;");

                for (var line : hunk.lines()) {
                    var text = new Text(formatLine(line));
                    text.setStyle(styleFor(line.type()));
                    text.setFont(javafx.scene.text.Font.font("Monospace", 13));
                    var flow = new TextFlow(text);
                    flow.setStyle(bgFor(line.type()));
                    flow.setPadding(new Insets(0, 4, 0, 4));
                    hunkBox.getChildren().add(flow);
                }

                diffContent.getChildren().add(hunkBox);
            }
        }
    }

    private String formatLine(DiffLine line) {
        return switch (line.type()) {
            case ADD -> "+ " + line.content();
            case REMOVE -> "- " + line.content();
            case CONTEXT -> "  " + line.content();
        };
    }

    private String styleFor(DiffLine.Type type) {
        return switch (type) {
            case ADD -> "-fx-fill: #4ec9b0;";
            case REMOVE -> "-fx-fill: #f44747;";
            case CONTEXT -> "-fx-fill: #cccccc;";
        };
    }

    private String bgFor(DiffLine.Type type) {
        return switch (type) {
            case ADD -> "-fx-background-color: #1e3a1e;";
            case REMOVE -> "-fx-background-color: #3a1e1e;";
            case CONTEXT -> "";
        };
    }
}
```

- [ ] **Step 2: Add Diff tab to MainWindow**

In `MainWindow`, update `sidebar.setOnSelect` — after adding FilesTab:

```java
var diffTab = new DiffTab(session);
contentArea.getTabPane().getTabs().add(diffTab);
```

- [ ] **Step 3: Verify manually**

Run: `./gradlew run`
Expected: Select a worktree with changes → Diff tab shows color-coded additions/removals.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add diff viewer tab with color-coded changes"
```

---

### Task 12: Session Persistence

**Files:**
- Create: `src/main/java/com/godfather/core/SessionStore.java`
- Create: `src/test/java/com/godfather/core/SessionStoreTest.java`
- Modify: `src/main/java/com/godfather/App.java`

- [ ] **Step 1: Write test**

```java
package com.godfather.core;

import com.godfather.model.Worktree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    @TempDir Path tempDir;

    @Test
    void saveAndLoad() {
        var store = new SessionStore(tempDir.resolve("session.json"));
        var worktrees = List.of(
            new Worktree("feat-a", "feature/a", Path.of("/tmp/a")),
            new Worktree("feat-b", "feature/b", Path.of("/tmp/b"))
        );
        store.save(worktrees);

        var loaded = store.load();
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).name()).isEqualTo("feat-a");
        assertThat(loaded.get(1).branch()).isEqualTo("feature/b");
    }

    @Test
    void loadFromMissingFile() {
        var store = new SessionStore(tempDir.resolve("nonexistent.json"));
        assertThat(store.load()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.godfather.core.SessionStoreTest"`
Expected: FAIL

- [ ] **Step 3: Implement SessionStore**

```java
package com.godfather.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.godfather.model.Worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SessionStore {

    private final Path file;
    private final ObjectMapper mapper;

    public SessionStore(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();
    }

    public void save(List<Worktree> worktrees) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), worktrees);
        } catch (IOException e) {
            System.err.println("Failed to save session: " + e.getMessage());
        }
    }

    public List<Worktree> load() {
        if (!Files.exists(file)) return List.of();
        try {
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            System.err.println("Failed to load session: " + e.getMessage());
            return List.of();
        }
    }
}
```

Note: The `Worktree` record uses `java.time.Instant` and `java.nio.file.Path`. Add Jackson modules for these. Add to `build.gradle` dependencies:

```groovy
implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2'
```

And update `Worktree.java` to add Jackson annotations for `Path`:

```java
package com.godfather.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.file.Path;
import java.time.Instant;

public record Worktree(
    String name,
    String branch,
    @JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @JsonDeserialize(using = com.godfather.model.PathDeserializer.class)
    Path path,
    Instant createdAt
) {
    public Worktree(String name, String branch, Path path) {
        this(name, branch, path, Instant.now());
    }
}
```

Create `PathDeserializer.java`:

```java
package com.godfather.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.nio.file.Path;

public class PathDeserializer extends StdDeserializer<Path> {
    public PathDeserializer() { super(Path.class); }

    @Override
    public Path deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        return Path.of(p.getValueAsString());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.godfather.core.SessionStoreTest"`
Expected: PASS

- [ ] **Step 5: Wire SessionStore into App.java**

In `App.java`, add session persistence:

```java
// After creating agentRegistry, add:
var sessionStore = new SessionStore(Path.of(System.getProperty("user.home"), ".godfather", "sessions.json"));

// When loading worktrees, save session:
stage.setOnCloseRequest(e -> {
    processManager.stopAll();
    var worktrees = agentRegistry.all().stream()
        .map(AgentSession::worktree)
        .toList();
    sessionStore.save(worktrees);
});
```

Import `com.godfather.model.AgentSession` and `com.godfather.core.SessionStore` in App.java.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add session persistence with SessionStore"
```

---

### Task 13: Worktree Deletion + Context Menu

**Files:**
- Modify: `src/main/java/com/godfather/ui/Sidebar.java`
- Modify: `src/main/java/com/godfather/ui/MainWindow.java`

- [ ] **Step 1: Add context menu to Sidebar worktree list**

In `Sidebar`, update `WorktreeCell` to add a context menu:

```java
private static class WorktreeCell extends ListCell<AgentSession> {
    @Override
    protected void updateItem(AgentSession session, boolean empty) {
        super.updateItem(session, empty);
        if (empty || session == null) {
            setText(null);
            setGraphic(null);
            setContextMenu(null);
        } else {
            setText(session.worktree().name() + " (" + session.worktree().branch() + ")");
            var dot = new Circle(5);
            dot.setFill(colorFor(session.getStatus()));
            setGraphic(dot);
            session.statusProperty().addListener((obs, old, s) -> dot.setFill(colorFor(s)));

            var deleteItem = new MenuItem("Delete Worktree");
            deleteItem.setOnAction(e -> {
                if (onDelete != null) onDelete.accept(session);
            });
            var startItem = new MenuItem("Start Agent");
            startItem.setOnAction(e -> {
                if (onStartAgent != null) onStartAgent.accept(session);
            });
            var stopItem = new MenuItem("Stop Agent");
            stopItem.setOnAction(e -> {
                if (onStopAgent != null) onStopAgent.accept(session);
            });
            setContextMenu(new ContextMenu(startItem, stopItem, new SeparatorMenuItem(), deleteItem));
        }
    }
}
```

Add instance callbacks to `Sidebar` (not static — avoids shared state issues):

```java
private Consumer<AgentSession> onDelete;
private Consumer<AgentSession> onStartAgent;
private Consumer<AgentSession> onStopAgent;

public void setOnDelete(Consumer<AgentSession> handler) { this.onDelete = handler; }
public void setOnStartAgent(Consumer<AgentSession> handler) { this.onStartAgent = handler; }
public void setOnStopAgent(Consumer<AgentSession> handler) { this.onStopAgent = handler; }

public void select(AgentSession session) {
    worktreeList.getSelectionModel().select(session);
}
```

Since `WorktreeCell` needs access to these instance callbacks, change the cell factory to pass them:

```java
worktreeList.setCellFactory(lv -> {
    var cell = new WorktreeCell();
    cell.setCallbacks(this::getOnDelete, this::getOnStartAgent, this::getOnStopAgent);
    return cell;
});
```

Alternatively, keep it simple — use lambdas directly in `setCellFactory` and reference the `Sidebar` instance fields from the non-static inner class. The cell is created by the `Sidebar` instance, so it can access `Sidebar.this.onDelete` etc.

- [ ] **Step 2: Wire callbacks in MainWindow**

```java
sidebar.setOnDelete(session -> {
    var confirm = new Alert(Alert.AlertType.CONFIRMATION,
        "Delete worktree '" + session.worktree().name() + "'?");
    confirm.showAndWait().ifPresent(btn -> {
        if (btn == ButtonType.OK) {
            processManager.stop(session);
            try {
                worktreeManager.remove(session.worktree());
            } catch (Exception ex) {
                // worktree may already be gone
            }
            agentRegistry.unregister(session.worktree().name());
            contentArea.showPlaceholder();
        }
    });
});

sidebar.setOnStopAgent(session -> processManager.stop(session));

sidebar.setOnStartAgent(session -> {
    // Programmatically re-select to refresh tabs with new terminal
    sidebar.select(session);
});
```

- [ ] **Step 3: Verify manually**

Run: `./gradlew run`
Expected: Right-click a worktree → context menu with Start/Stop/Delete. Delete removes worktree from sidebar and disk.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add worktree deletion and agent start/stop context menu"
```

---

### Task 14: Final Polish and Smoke Test

**Files:**
- Modify: `src/main/resources/styles/godfather.css` (minor tweaks if needed)
- Modify: `src/main/java/com/godfather/App.java`

- [ ] **Step 1: Add .godfather to .gitignore in managed repos**

In `WorktreeManager.create()`, after creating the worktree, auto-append `.godfather/` to the repo's `.gitignore` if not already present:

```java
// Add to WorktreeManager after successful worktree creation:
private void ensureGitignore() {
    Path gitignore = repoRoot.resolve(".gitignore");
    try {
        String content = Files.exists(gitignore) ? Files.readString(gitignore) : "";
        if (!content.contains(".godfather")) {
            Files.writeString(gitignore, content + "\n.godfather/\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        }
    } catch (IOException e) {
        // non-critical
    }
}
```

Call `ensureGitignore()` at the end of `create()`.

- [ ] **Step 2: Run all tests**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 3: Full manual smoke test**

Run: `./gradlew run`

Test the following flow:
1. Select a git repository
2. Click "+ New Worktree" → create a worktree
3. Worktree appears in sidebar with gray dot
4. Click the worktree → Terminal, Files, Diff tabs appear
5. Terminal launches Claude Code (or shows error if `claude` not on PATH)
6. Files tab shows file tree, click a `.java` file → syntax highlighting
7. Diff tab shows changes (or "no changes")
8. Right-click worktree → Stop Agent → dot changes color
9. Right-click → Delete → worktree removed
10. Close app, reopen → worktree list restored from session

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: final polish — auto-gitignore, smoke test verified"
```

- [ ] **Step 5: Tag release**

```bash
git tag -a v0.1.0 -m "MVP: The Godfather v0.1.0"
```
