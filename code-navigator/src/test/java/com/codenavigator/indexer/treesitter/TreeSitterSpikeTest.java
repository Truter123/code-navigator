package com.codenavigator.indexer.treesitter;

import at.released.weh.bindings.chicory.ChicoryEmscriptenHostInstaller;
import at.released.weh.emcripten.runtime.export.EmscriptenRuntime;
import at.released.weh.host.EmbedderHost;
import at.released.weh.host.EmbedderHostBuilder;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.UnlinkableException;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.Import;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEASIBILITY SPIKE — THROWAWAY CODE (decision gate).
 *
 * Question: can tree-sitter grammars run on the pure-Java Chicory WASM runtime via the
 * wasi-emscripten-host (weh) bindings, parse a Python file, and expose the syntax tree?
 *
 * Verdict (see docs/superpowers/specs/2026-06-23-tree-sitter-spike-result.md): FAIL.
 * The shipped web-tree-sitter wasm artifacts are emscripten MAIN_MODULE/SIDE_MODULE
 * pairs that require an emscripten dynamic LINKER at runtime (shared imported memory +
 * indirect function table, __memory_base/__table_base/__stack_pointer globals, a GOT,
 * and data relocations). Neither weh 0.6.0 nor Chicory 1.5.1 implements that linker; weh
 * targets the STANDALONE/reactor emscripten profile where the module owns its own memory.
 *
 * These tests are written to PASS by ASSERTING the blocker empirically, so the committed
 * suite is a deterministic record of the negative result rather than a red build.
 */
class TreeSitterSpikeTest {

    private static final String CORE = "/treesitter/tree-sitter.wasm";
    private static final String GRAMMAR = "/treesitter/tree-sitter-python.wasm";

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = TreeSitterSpikeTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found on classpath: " + path);
            }
            return in.readAllBytes();
        }
    }

    /** Names "module.field" that the weh installer exposes as host functions. */
    private static Set<String> wehProvidedImportNames() {
        EmbedderHostBuilder hostBuilder = new EmbedderHostBuilder();
        hostBuilder.fileSystem().setUnrestricted(true);
        try (EmbedderHost host = hostBuilder.build()) {
            ChicoryEmscriptenHostInstaller installer =
                    new ChicoryEmscriptenHostInstaller.Builder().setHost(host).build();
            List<ImportFunction> functions = new ArrayList<>(installer.setupWasiPreview1HostFunctions());
            functions.addAll(installer.setupEmscriptenFunctions().getEmscriptenFunctions());
            return functions.stream()
                    .map(f -> f.module() + "." + f.name())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Every import a module declares, as "module.field (kind)". */
    private static List<String> moduleImports(WasmModule m) {
        List<String> out = new ArrayList<>();
        var section = m.importSection();
        for (int i = 0; i < section.importCount(); i++) {
            Import imp = section.getImport(i);
            out.add(imp.module() + "." + imp.name() + " (" + imp.importType() + ")");
        }
        return out;
    }

    /**
     * GATE 0a — attempt to boot the CORE web-tree-sitter module on Chicory via weh.
     *
     * Empirically records that linking fails. The first unsatisfied import is
     * env.abort (this core is built against an older emscripten than weh targets,
     * which exports env._abort_js / env.exit instead). Renaming would only uncover
     * the deeper, unfixable blockers asserted in {@link #gate0a_dynamicLinkingGap}.
     */
    @Test
    void gate0a_coreFailsToInstantiate() throws Exception {
        byte[] core = resource(CORE);
        System.out.println("[gate0a] core wasm = " + core.length + " bytes");

        EmbedderHostBuilder hostBuilder = new EmbedderHostBuilder();
        hostBuilder.fileSystem().setUnrestricted(true);

        assertThatThrownBy(() -> {
            try (EmbedderHost host = hostBuilder.build()) {
                ChicoryEmscriptenHostInstaller installer =
                        new ChicoryEmscriptenHostInstaller.Builder().setHost(host).build();
                List<ImportFunction> functions = new ArrayList<>(installer.setupWasiPreview1HostFunctions());
                var finalizer = installer.setupEmscriptenFunctions();
                functions.addAll(finalizer.getEmscriptenFunctions());
                ImportValues imports = ImportValues.builder().withFunctions(functions).build();
                WasmModule module = Parser.parse(core);
                Instance instance = Instance.builder(module)
                        .withImportValues(imports)
                        .withInitialize(true)
                        .withStart(false)
                        .build();
                EmscriptenRuntime runtime = finalizer.finalize(instance);
                runtime.initMainThread();
            }
        })
                .as("core links cleanly against weh-provided imports")
                .isInstanceOf(UnlinkableException.class)
                .hasMessageContaining("env.abort");
    }

    /**
     * GATE 0a (root cause) — the dynamic-linking gap, asserted from the wasm binaries
     * and the weh host-function set. This is the real blocker.
     */
    @Test
    void gate0a_dynamicLinkingGap() throws Exception {
        WasmModule core = Parser.parse(resource(CORE));
        WasmModule grammar = Parser.parse(resource(GRAMMAR));

        List<String> coreImports = moduleImports(core);
        List<String> grammarImports = moduleImports(grammar);
        System.out.println("[gap] CORE imports (" + coreImports.size() + "):");
        coreImports.forEach(s -> System.out.println("    " + s));
        System.out.println("[gap] GRAMMAR imports (" + grammarImports.size() + "):");
        grammarImports.forEach(s -> System.out.println("    " + s));

        // 1) Both modules are emscripten RELOCATABLE side modules: they carry a dylink
        //    custom section and do NOT contain their own memory/table — they IMPORT them.
        assertThat(core.customSections().stream().map(cs -> cs.name()).toList())
                .as("core has a dylink custom section (relocatable module)")
                .anyMatch(n -> n.startsWith("dylink"));
        assertThat(grammar.customSections().stream().map(cs -> cs.name()).toList())
                .as("grammar has a dylink custom section (relocatable module)")
                .anyMatch(n -> n.startsWith("dylink"));

        // 2) The core imports a shared linear MEMORY and an indirect function TABLE,
        //    plus the linker-assigned relocation globals. None are provided by weh.
        assertThat(core.importSection().count(ExternalType.MEMORY))
                .as("core imports linear memory (does not own it)").isEqualTo(1);
        assertThat(core.importSection().count(ExternalType.TABLE))
                .as("core imports the indirect function table").isEqualTo(1);
        assertThat(coreImports)
                .anyMatch(s -> s.contains("env.__stack_pointer"))
                .anyMatch(s -> s.contains("env.__memory_base"))
                .anyMatch(s -> s.contains("env.__table_base"))
                .anyMatch(s -> s.contains("GOT.mem.__heap_base"));

        // 3) The grammar is dynamically linked AGAINST the core: it imports the core's
        //    allocator (malloc/free/realloc/calloc/memcpy) and resolves its external
        //    scanner functions through the GOT (GOT.func.*). This only works with a
        //    runtime emscripten linker that shares state across the two modules.
        assertThat(grammarImports)
                .anyMatch(s -> s.contains("env.malloc"))
                .anyMatch(s -> s.contains("env.free"))
                .anyMatch(s -> s.contains("GOT.func.tree_sitter_python_external_scanner_scan"));

        // 4) weh provides ONLY host FUNCTIONS (WASI + emscripten env syscalls) — it does
        //    NOT provide imported memory, an imported table, any globals, or a GOT/linker.
        Set<String> weh = wehProvidedImportNames();
        System.out.println("[gap] weh provides " + weh.size() + " host functions; "
                + "0 memories, 0 tables, 0 globals, no GOT, no dlopen.");

        // The structural imports the core needs that weh cannot supply, by name:
        Set<String> unsatisfiableStructural = new LinkedHashSet<>(List.of(
                "env.memory",                 // imported shared memory
                "env.__indirect_function_table", // imported table
                "env.__stack_pointer",        // linker global
                "env.__memory_base",          // linker global
                "env.__table_base",           // linker global
                "GOT.mem.__heap_base"));      // GOT entry
        assertThat(weh)
                .as("weh does not provide any of the dynamic-linking structural imports")
                .doesNotContainAnyElementsOf(unsatisfiableStructural);

        // And the env FUNCTION names this core build uses do not even match weh's names:
        assertThat(weh)
                .as("core build predates weh's emscripten naming: env.abort vs env._abort_js")
                .contains("env._abort_js")
                .doesNotContain("env.abort")
                .doesNotContain("env.emscripten_memcpy_js"); // core needs it; weh lacks it
    }

    /**
     * GATE 0b — parse Python. CANNOT be reached: grammar loading requires the emscripten
     * dynamic linker proven absent in {@link #gate0a_dynamicLinkingGap}.
     *
     * Loading the grammar as an independent second Chicory instance is also a dead end:
     * the grammar imports the CORE's malloc/free/memcpy and resolves its scanner via the
     * shared GOT/indirect table, so it must run inside the core's linked address space —
     * not as an isolated module with its own memory. There is no public weh/Chicory API to
     * (a) instantiate the core with a host-owned shared Memory + Table, (b) assign
     * __memory_base/__table_base, (c) apply data/function relocations, or (d) populate the
     * GOT. Additionally, the data-exchange ABI of THIS build is the web-tree-sitter
     * "_wasm" wrapper convention (ts_tree_root_node_wasm, ts_node_symbol_wasm, …) which
     * marshals nodes through a JS-side TRANSFER_BUFFER and the env.tree_sitter_parse_callback
     * import — i.e. it also assumes the JS glue, not raw C-ABI calls.
     */
    @Test
    void gate0b_parsePython_blocked() throws Exception {
        WasmModule grammar = Parser.parse(resource(GRAMMAR));
        // The grammar exports the language constructor but cannot be linked standalone.
        Set<String> grammarExports = new TreeSet<>();
        var es = grammar.exportSection();
        for (int i = 0; i < es.exportCount(); i++) {
            grammarExports.add(es.getExport(i).name());
        }
        System.out.println("[gate0b] grammar exports: " + grammarExports);
        assertThat(grammarExports)
                .as("grammar exposes the tree_sitter_python language constructor")
                .contains("tree_sitter_python");
        System.out.println("[gate0b] BLOCKED: grammar requires emscripten dynamic linking "
                + "(shared memory/table + GOT + relocations) absent from weh 0.6.0 / Chicory 1.5.1.");
    }
}
