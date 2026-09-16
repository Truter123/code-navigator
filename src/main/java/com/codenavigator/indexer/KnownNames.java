package com.codenavigator.indexer;

import java.util.Set;

/**
 * Per-run lookups the extra-language edge passes need, built once by {@link ProjectIndexer}
 * before it calls {@link LanguageIndexer#extractEdges}. Empty today — shell needs nothing from
 * it; SQL (a later step) populates {@code sqlTables} so the Java/Python edge passes can resolve
 * a table name they see in code against the schema the SQL indexer already found.
 */
public record KnownNames(Set<String> sqlTables) {

    public static KnownNames empty() {
        return new KnownNames(Set.of());
    }
}
