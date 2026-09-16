package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.Node;
import com.codenavigator.graph.NodeType;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Seam for a language indexed alongside the hand-wired Java/TypeScript/Groovy/Dart pipeline.
 * Java, TS, Groovy and Dart stay hand-wired; this interface is for the languages that come after
 * (shell today, SQL and Python next — see the "code-navigator indexes Python, SQL and shell"
 * plan). There is no separate "method node" model in this codebase to reuse, so a function or
 * method is an ordinary {@link com.codenavigator.graph.NodeType#METHOD} node returned from
 * {@link #extractMethods}, and its {@code DECLARES_METHOD} edge is emitted by {@link
 * #extractEdges} alongside every other edge for the file.
 */
public interface LanguageIndexer {

    /** True if this indexer should process {@code file} (extension check + exclusions). */
    boolean matches(Path file);

    /** Top-level nodes for one file — a script, a module, a class... */
    List<Node> indexFile(Path file, Path projectRoot);

    /** METHOD nodes declared in this file. Runs after this file's top-level nodes are saved. */
    default List<Node> extractMethods(Path file, List<Node> fileNodes) {
        return List.of();
    }

    /**
     * Every edge for this file — including {@code DECLARES_METHOD} from a top-level node to its
     * methods. Runs after this file's nodes and methods are saved, so {@code fileNodes} carries
     * both.
     */
    default List<Edge> extractEdges(Path file, List<Node> fileNodes, KnownNames known) {
        return List.of();
    }

    /**
     * True when any changed file of this language forces a full re-scan of every file of that
     * language, rather than an incremental re-index of just the changed one (SQL: last-migration-
     * wins means one new file can rename or drop an object defined anywhere else).
     */
    default boolean rebuildAllOnChange() {
        return false;
    }

    /**
     * Whole-history scan run once per indexing run, before any {@link #indexFile} call for this
     * indexer, over every file this indexer matches (already in Flyway/path order for SQL). A
     * no-op for indexers with no cross-file semantics (shell); SQL uses it to build the final
     * schema model in memory — last migration wins for a dropped or renamed object — so {@link
     * #indexFile} and {@link #extractEdges} just read from it.
     */
    default void prepare(List<Path> files, Path projectRoot) {
    }

    /**
     * The {@link NodeType}s this indexer owns, so {@link ProjectIndexer} can delete every node of
     * those types and re-run {@link #prepare} + {@link #indexFile} over the full file list when
     * {@link #rebuildAllOnChange} fires, without hard-coding the type list itself.
     */
    default Set<NodeType> ownedTypes() {
        return Set.of();
    }
}
