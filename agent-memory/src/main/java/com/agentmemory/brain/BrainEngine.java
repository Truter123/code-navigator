package com.agentmemory.brain;

import com.agentmemory.model.Anomaly;
import com.agentmemory.store.GraphStore;
import com.agentmemory.store.MemoryStore;

import java.util.ArrayList;
import java.util.List;

public class BrainEngine {

    private final LoopDetector loopDetector;
    private final DriftDetector driftDetector;
    private final ContradictionDetector contradictionDetector;

    public BrainEngine(MemoryStore memoryStore, GraphStore graphStore) {
        this.loopDetector = new LoopDetector(memoryStore);
        this.driftDetector = new DriftDetector(memoryStore);
        this.contradictionDetector = new ContradictionDetector(memoryStore, graphStore);
    }

    public List<Anomaly> check(String agent, String project) {
        List<Anomaly> all = new ArrayList<>();
        all.addAll(loopDetector.detect(agent, project));
        all.addAll(driftDetector.detect(agent, project));
        all.addAll(contradictionDetector.detectAll(agent, project));
        return all;
    }

    public List<Anomaly> onStore(String key, String newValue, String agent, String project) {
        return contradictionDetector.checkForContradictions(key, newValue, agent, project);
    }
}
