package io.aicompanion;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Tracks which tasks have already passed or failed across runs so a subsequent
 * `run` can skip the ones whose content has not changed.
 *
 * State is persisted to {@link #DEFAULT_PATH} as YAML; missing or malformed
 * files are tolerated and treated as empty.
 */
public class RunState {

    public static final Path DEFAULT_PATH = Path.of(".aicompanion", "state.yml");

    public enum Status { PASSED, FAILED }

    public record TaskState(Status status, String hash, String at) {}

    private final Path path;
    private final Map<String, TaskState> tasks;
    private String tasksDir;

    private RunState(Path path, String tasksDir, Map<String, TaskState> tasks) {
        this.path     = path;
        this.tasksDir = tasksDir;
        this.tasks    = new LinkedHashMap<>(tasks);
    }

    /** Load state from the default path. Returns empty state if missing/corrupt. */
    public static RunState load() {
        return load(DEFAULT_PATH);
    }

    @SuppressWarnings("unchecked")
    public static RunState load(Path path) {
        if (!Files.exists(path)) {
            return new RunState(path, null, new LinkedHashMap<>());
        }
        try (var reader = Files.newBufferedReader(path)) {
            Object raw = new Yaml().load(reader);
            if (!(raw instanceof Map)) {
                return new RunState(path, null, new LinkedHashMap<>());
            }
            Map<String, Object> root = (Map<String, Object>) raw;
            String tasksDir = root.get("tasks_dir") instanceof String s ? s : null;

            Map<String, TaskState> parsed = new LinkedHashMap<>();
            Object tasksObj = root.get("tasks");
            if (tasksObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String name)) continue;
                    if (!(e.getValue() instanceof Map<?, ?> entry))  continue;
                    Status status = parseStatus(entry.get("status"));
                    if (status == null) continue;
                    String hash = entry.get("hash") instanceof String h ? h : "";
                    String at   = entry.get("at")   instanceof String a ? a : "";
                    parsed.put(name, new TaskState(status, hash, at));
                }
            }
            return new RunState(path, tasksDir, parsed);
        } catch (IOException | RuntimeException e) {
            System.err.println("Warning: could not read state file " + path
                + " — treating as empty. (" + e.getMessage() + ")");
            return new RunState(path, null, new LinkedHashMap<>());
        }
    }

    private static Status parseStatus(Object v) {
        if (!(v instanceof String s)) return null;
        try { return Status.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public void setTasksDir(String dir) { this.tasksDir = dir; }

    public TaskState get(String name) { return tasks.get(name); }

    public Map<String, TaskState> tasks() { return Collections.unmodifiableMap(tasks); }

    public int passedCount() { return countByStatus(Status.PASSED); }
    public int failedCount() { return countByStatus(Status.FAILED); }

    private int countByStatus(Status s) {
        int n = 0;
        for (TaskState t : tasks.values()) if (t.status() == s) n++;
        return n;
    }

    public void markPassed(String name, String hash) {
        tasks.put(name, new TaskState(Status.PASSED, hash, Instant.now().toString()));
    }

    public void markFailed(String name, String hash) {
        tasks.put(name, new TaskState(Status.FAILED, hash, Instant.now().toString()));
    }

    /**
     * Decide whether to skip a task during resume.
     *
     * Skip when the recorded hash matches the current file content AND either:
     *   - status = PASSED, or
     *   - status = FAILED and {@code retryFailed} is false.
     *
     * A hash mismatch means the user edited the task; never skip in that case.
     */
    public boolean shouldSkip(String name, String currentHash, boolean retryFailed) {
        TaskState t = tasks.get(name);
        if (t == null) return false;
        if (!Objects.equals(t.hash(), currentHash)) return false;
        return switch (t.status()) {
            case PASSED -> true;
            case FAILED -> !retryFailed;
        };
    }

    /** Human-readable label for the {@code status} command: passed / failed / edited / pending. */
    public String labelFor(String name, String currentHash) {
        TaskState t = tasks.get(name);
        if (t == null) return "pending";
        if (!Objects.equals(t.hash(), currentHash)) return "edited";
        return t.status().name().toLowerCase();
    }

    public void save() throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());

        Map<String, Object> root = new LinkedHashMap<>();
        if (tasksDir != null) root.put("tasks_dir", tasksDir);
        root.put("updated_at", Instant.now().toString());

        Map<String, Object> taskMap = new LinkedHashMap<>();
        for (Map.Entry<String, TaskState> e : tasks.entrySet()) {
            TaskState t = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", t.status().name().toLowerCase());
            entry.put("hash",   t.hash());
            entry.put("at",     t.at());
            taskMap.put(e.getKey(), entry);
        }
        root.put("tasks", taskMap);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Files.writeString(path, new Yaml(opts).dump(root));
    }

    /** Delete the state file at the default path. No-op if absent. */
    public static void delete() throws IOException {
        delete(DEFAULT_PATH);
    }

    public static void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /** SHA-256 hex digest of UTF-8 bytes. Used to detect task-file edits. */
    public static String hash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
