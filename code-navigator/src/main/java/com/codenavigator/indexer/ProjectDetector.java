package com.codenavigator.indexer;

import com.codenavigator.graph.Project;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class ProjectDetector {

    private static final Pattern DDD_MARKERS = Pattern.compile(
        "CommandBus|AggregateRoot|implements\\s+Command<|implements\\s+CommandHandler<|implements\\s+QueryHandler<");
    private static final Pattern SPRING_MARKERS = Pattern.compile(
        "@RestController|@Service|@Repository|@Entity|@SpringBootApplication");

    public Project detect(Path projectPath) {
        var foundDdd = new AtomicBoolean(false);
        var foundSpring = new AtomicBoolean(false);

        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                    if (file.toString().contains("build") || file.toString().contains(".gradle"))
                        return FileVisitResult.CONTINUE;

                    var content = Files.readString(file);
                    if (DDD_MARKERS.matcher(content).find()) {
                        foundDdd.set(true);
                        return FileVisitResult.TERMINATE;
                    }
                    if (SPRING_MARKERS.matcher(content).find()) {
                        foundSpring.set(true);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Fall through to GENERIC
        }

        if (foundDdd.get()) return Project.DDD;
        if (foundSpring.get()) return Project.CRUD;
        return Project.GENERIC;
    }
}
