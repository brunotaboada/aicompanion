package io.aicompanion;

import io.aicompanion.config.Config;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Scans the features directory and builds the ordered list of {@link Batch}es
 * to execute. Pure I/O — no agent state, no console output.
 */
public final class BatchResolver {

    private final Config config;

    public BatchResolver(Config config) {
        this.config = config;
    }

    /**
     * One batch per feature subdirectory under {@code features_dir} that has
     * a {@code tasks/} child with at least one matching task file.
     * Sorted alphabetically by feature name, or in discovery order when
     * {@code task_sort=none}.
     */
    public List<Batch> resolveBatches() throws IOException {
        Path featuresDir = Path.of(config.featuresDir());
        if (!Files.isDirectory(featuresDir)) {
            throw new IllegalArgumentException(
                "Features directory not found: " + featuresDir.toAbsolutePath()
                + " — create it with at least one feature subfolder containing tasks/.");
        }

        Comparator<Path> order = sortOrder();
        List<Batch> batches = new ArrayList<>();
        try (var stream = Files.list(featuresDir)) {
            List<Path> featureDirs = stream
                .filter(Files::isDirectory)
                .filter(p -> Files.isDirectory(p.resolve("tasks")))
                .sorted(order)
                .toList();
            for (Path featureDir : featureDirs) {
                List<Path> taskPaths = resolveTaskPathsIn(featureDir.resolve("tasks"));
                if (taskPaths.isEmpty()) continue;
                batches.add(new Batch(featureDir.getFileName().toString(), taskPaths));
            }
        }
        return batches;
    }

    private List<Path> resolveTaskPathsIn(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot + 1) : "";
                    return config.taskExtensions().contains(ext);
                })
                .sorted(sortOrder())
                .toList();
        }
    }

    private Comparator<Path> sortOrder() {
        return "none".equalsIgnoreCase(config.taskSort())
            ? Comparator.comparing(p -> 0)
            : Comparator.comparing(p -> p.getFileName().toString());
    }
}
