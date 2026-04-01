package com.agentmemory.store;

import com.agentmemory.model.Memory;
import com.agentmemory.model.MemoryLink;

import java.time.Instant;
import java.util.*;

public class GraphStore {

    private final MemoryStore memoryStore;

    public GraphStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public void link(String sourceKey, String targetKey, String relation, String agent) {
        Memory source = memoryStore.recallByKey(sourceKey)
                .orElseThrow(() -> new IllegalArgumentException("Source memory not found: " + sourceKey));
        Memory target = memoryStore.recallByKey(targetKey)
                .orElseThrow(() -> new IllegalArgumentException("Target memory not found: " + targetKey));

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        memoryStore.executeUpdate("""
            INSERT OR IGNORE INTO memory_links (id, source_id, target_id, relation, created_at)
            VALUES (?, ?, ?, ?, ?)
        """, id, source.id(), target.id(), relation, now);
    }

    public List<MemoryLink> getLinksFrom(String key) {
        Memory source = memoryStore.recallByKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Memory not found: " + key));

        return memoryStore.query("""
            SELECT * FROM memory_links WHERE source_id = ?
        """, rs -> new MemoryLink(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("target_id"),
                rs.getString("relation"),
                rs.getString("created_at")
        ), source.id());
    }

    public List<Memory> traverse(String startKey, int maxDepth) {
        Memory start = memoryStore.recallByKey(startKey)
                .orElseThrow(() -> new IllegalArgumentException("Memory not found: " + startKey));

        Set<String> visited = new HashSet<>();
        visited.add(start.id());

        List<Memory> result = new ArrayList<>();
        Queue<String> frontier = new LinkedList<>();
        frontier.add(start.id());

        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            int levelSize = frontier.size();
            for (int i = 0; i < levelSize; i++) {
                String currentId = frontier.poll();

                List<String> neighborIds = memoryStore.query("""
                    SELECT target_id AS neighbor FROM memory_links WHERE source_id = ?
                    UNION
                    SELECT source_id AS neighbor FROM memory_links WHERE target_id = ?
                """, rs -> rs.getString("neighbor"), currentId, currentId);

                for (String neighborId : neighborIds) {
                    if (!visited.contains(neighborId)) {
                        visited.add(neighborId);
                        memoryStore.findById(neighborId).ifPresent(m -> {
                            result.add(m);
                            frontier.add(m.id());
                        });
                    }
                }
            }
        }

        return result;
    }

    public List<MemoryLink> getAllLinks(String agent, String project) {
        if (agent == null && project == null) {
            return memoryStore.query("""
                SELECT * FROM memory_links
            """, rs -> new MemoryLink(
                    rs.getString("id"),
                    rs.getString("source_id"),
                    rs.getString("target_id"),
                    rs.getString("relation"),
                    rs.getString("created_at")
            ));
        }

        StringBuilder sql = new StringBuilder("""
            SELECT ml.* FROM memory_links ml
            JOIN memories m ON ml.source_id = m.id
            WHERE 1=1
        """);
        List<Object> params = new ArrayList<>();

        if (agent != null) {
            sql.append(" AND m.agent = ?");
            params.add(agent);
        }
        if (project != null) {
            sql.append(" AND m.project = ?");
            params.add(project);
        }

        return memoryStore.query(sql.toString(), rs -> new MemoryLink(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("target_id"),
                rs.getString("relation"),
                rs.getString("created_at")
        ), params.toArray());
    }
}
