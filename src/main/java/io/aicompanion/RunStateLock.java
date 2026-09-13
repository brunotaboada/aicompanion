package io.aicompanion;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/**
 * Exclusive lock for {@link RunState} persistence. A second concurrent
 * {@code run} in the same project fails fast instead of racing on state.yml.
 */
public final class RunStateLock implements AutoCloseable {

    /** Lock file next to the default state file, relative to the working directory. */
    public static final Path DEFAULT_PATH =
        RunState.DEFAULT_PATH.getParent().resolve("state.lock");

    private final FileChannel channel;
    private final FileLock    lock;

    private RunStateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock    = lock;
    }

    /** Lock file for a project — sibling of {@link RunState#pathFor(String)}. */
    public static Path pathFor(String projectDir) {
        return RunState.pathFor(projectDir).resolveSibling("state.lock");
    }

    public static RunStateLock acquire() throws IOException {
        return acquire(DEFAULT_PATH);
    }

    public static RunStateLock acquire(Path lockPath) throws IOException {
        if (lockPath.getParent() != null) {
            Files.createDirectories(lockPath.getParent());
        }
        FileChannel ch = FileChannel.open(lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock fileLock = ch.tryLock();
        if (fileLock == null) {
            ch.close();
            throw new IllegalStateException(
                "Another aicompanion run is in progress (lock: " + lockPath + "). "
                    + "Wait for it to finish. (The lock is released automatically when "
                    + "that process exits — deleting the file does not help.)");
        }
        return new RunStateLock(ch, fileLock);
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }
}
