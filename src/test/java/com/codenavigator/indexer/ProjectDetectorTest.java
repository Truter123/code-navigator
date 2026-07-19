package com.codenavigator.indexer;

import com.codenavigator.graph.Project;
import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectDetectorTest {
    private final ProjectDetector detector = new ProjectDetector();

    @Test
    void detectsDddProject() {
        assertThat(detector.detect(Paths.get("src/test/resources/sample-ddd"))).isEqualTo(Project.DDD);
    }

    @Test
    void detectsSpringProject() {
        assertThat(detector.detect(Paths.get("src/test/resources/sample-spring"))).isEqualTo(Project.CRUD);
    }

    @Test
    void detectsGenericProject() {
        assertThat(detector.detect(Paths.get("src/test/resources/sample-generic"))).isEqualTo(Project.GENERIC);
    }
}
