package com.agentmemory.store;

import com.agentmemory.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GraphStoreTest {

    @TempDir
    Path tempDir;

    MemoryStore memoryStore;
    GraphStore graphStore;

    @BeforeEach
    void setUp() {
        memoryStore = new MemoryStore(tempDir.resolve("test.db"));
        graphStore = new GraphStore(memoryStore);

        memoryStore.upsert("A", "value-a", "claude", "proj", List.of(), 0.5, false);
        memoryStore.upsert("B", "value-b", "claude", "proj", List.of(), 0.5, false);
        memoryStore.upsert("C", "value-c", "claude", "proj", List.of(), 0.5, false);
    }

    @Test
    void createAndRetrieveLink() {
        graphStore.link("A", "B", "depends_on", "claude");

        List<MemoryLink> links = graphStore.getLinksFrom("A");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).relation()).isEqualTo("depends_on");

        Memory sourceMemory = memoryStore.recallByKey("A").orElseThrow();
        Memory targetMemory = memoryStore.recallByKey("B").orElseThrow();
        assertThat(links.get(0).sourceId()).isEqualTo(sourceMemory.id());
        assertThat(links.get(0).targetId()).isEqualTo(targetMemory.id());
    }

    @Test
    void linkThrowsForMissingSource() {
        assertThatThrownBy(() -> graphStore.link("missing", "B", "rel", "claude"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void linkThrowsForMissingTarget() {
        assertThatThrownBy(() -> graphStore.link("A", "missing", "rel", "claude"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void duplicateLinkIsIgnored() {
        graphStore.link("A", "B", "depends_on", "claude");
        graphStore.link("A", "B", "depends_on", "claude");

        List<MemoryLink> links = graphStore.getLinksFrom("A");
        assertThat(links).hasSize(1);
    }

    @Test
    void traverseDepth2ReturnsTransitiveNeighbors() {
        graphStore.link("A", "B", "depends_on", "claude");
        graphStore.link("B", "C", "depends_on", "claude");

        List<Memory> reachable = graphStore.traverse("A", 2);

        assertThat(reachable).hasSize(2);
        assertThat(reachable).extracting(Memory::key).containsExactlyInAnyOrder("B", "C");
    }

    @Test
    void traverseDepth1ReturnsOnlyDirectNeighbors() {
        graphStore.link("A", "B", "depends_on", "claude");
        graphStore.link("B", "C", "depends_on", "claude");

        List<Memory> reachable = graphStore.traverse("A", 1);

        assertThat(reachable).hasSize(1);
        assertThat(reachable.get(0).key()).isEqualTo("B");
    }

    @Test
    void traverseFollowsBothDirections() {
        graphStore.link("A", "B", "depends_on", "claude");

        List<Memory> fromA = graphStore.traverse("A", 1);
        assertThat(fromA).extracting(Memory::key).containsExactly("B");

        List<Memory> fromB = graphStore.traverse("B", 1);
        assertThat(fromB).extracting(Memory::key).containsExactly("A");
    }

    @Test
    void traverseDoesNotIncludeStartNode() {
        graphStore.link("A", "B", "depends_on", "claude");

        List<Memory> reachable = graphStore.traverse("A", 1);
        assertThat(reachable).extracting(Memory::key).doesNotContain("A");
    }

    @Test
    void getAllLinksUnfiltered() {
        graphStore.link("A", "B", "depends_on", "claude");
        graphStore.link("B", "C", "uses", "claude");

        List<MemoryLink> all = graphStore.getAllLinks(null, null);
        assertThat(all).hasSize(2);
    }

    @Test
    void getAllLinksFilteredByAgent() {
        memoryStore.upsert("D", "value-d", "other", "proj", List.of(), 0.5, false);
        graphStore.link("A", "B", "depends_on", "claude");
        graphStore.link("D", "B", "uses", "other");

        List<MemoryLink> claudeLinks = graphStore.getAllLinks("claude", null);
        assertThat(claudeLinks).hasSize(1);
        assertThat(claudeLinks.get(0).relation()).isEqualTo("depends_on");
    }

    @Test
    void getAllLinksFilteredByProject() {
        memoryStore.upsert("D", "value-d", "claude", "other-proj", List.of(), 0.5, false);
        graphStore.link("A", "B", "depends_on", "claude");
        graphStore.link("D", "B", "uses", "claude");

        List<MemoryLink> projLinks = graphStore.getAllLinks(null, "proj");
        assertThat(projLinks).hasSize(1);
        assertThat(projLinks.get(0).relation()).isEqualTo("depends_on");
    }
}
