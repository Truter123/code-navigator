package com.codenavigator.cli;

import com.codenavigator.briefing.BriefingGenerator;
import com.codenavigator.domain.DomainSqliteStore;
import com.codenavigator.graph.GraphStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "briefing", description = "Generate compact codebase index for AI assistants")
public class BriefingCommand implements Runnable {

    @Parameters(index = "0", description = "Path to the project root")
    private Path projectPath;

    @Option(names = "--output", description = "Output directory (default: .ai-briefing)", defaultValue = ".ai-briefing")
    private String output;

    @Override
    public void run() {
        if (!ProjectPaths.hasIndex(projectPath)) {
            System.err.println("No index found. Run 'init' first.");
            return;
        }

        var outputDir = projectPath.resolve(output);
        DomainSqliteStore domainStore = null;

        try (var store = new GraphStore(ProjectPaths.graphDb(projectPath))) {
            if (ProjectPaths.hasDomainIndex(projectPath)) {
                domainStore = new DomainSqliteStore(ProjectPaths.domainDb(projectPath));
            }

            var generator = new BriefingGenerator(store, domainStore);
            generator.generate(outputDir);
            System.out.println("Briefing generated at " + outputDir);
        } catch (Exception e) {
            System.err.println("Briefing generation failed: " + e.getMessage());
        } finally {
            if (domainStore != null) domainStore.close();
        }
    }
}
