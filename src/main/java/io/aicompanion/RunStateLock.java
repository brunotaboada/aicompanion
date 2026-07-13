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

    private static final Path LOCK_PATH =
        RunState.DEFAULT_PATH.getParent().resolve("state.lock");

    private final FileChannel channel;
    private final FileLock    lock;

    private RunStateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock    = lock;
    }

    public static RunStateLock acquire() throws IOException {
        if (LOCK_PATH.getParent() != null) {
            Files.createDirectories(LOCK_PATH.getParent());
        }
        FileChannel ch = FileChannel.open(LOCK_PATH,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock fileLock = ch.tryLock();
        if (fileLock == null) {
            ch.close();
            throw new IllegalStateException(
                "Another aicompanion run is in progress (lock: " + LOCK_PATH + "). "
                    + "Wait for it to finish or remove the lock file if the process died.");
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
