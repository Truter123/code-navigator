package com.codenavigator.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * One indexing run per project. The Stop hook starts {@code sync-if-dirty} after every turn and
 * nothing stopped a second run while the first was still writing: five of them fought over one
 * SQLite file on nlp, none finished, and the WAL grew to 2 GB.
 *
 * <p>An OS file lock on {@code navigators/code/.sync.lock}: it is released when the process dies,
 * so a run killed by {@code timeout} leaves no stale lock. The file itself is never deleted.
 */
public final class IndexLock implements AutoCloseable {

    private static final String LOCK_FILE = ".sync.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private IndexLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Waits until no other run holds the project. For {@code init} and {@code sync}. */
    public static IndexLock acquire(Path projectRoot) throws IOException {
        FileChannel channel = open(projectRoot);
        try {
            return new IndexLock(channel, channel.lock());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** Empty when another run holds the project. For {@code sync-if-dirty}, which then skips. */
    public static Optional<IndexLock> tryAcquire(Path projectRoot) throws IOException {
        FileChannel channel = open(projectRoot);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new IndexLock(channel, lock));
        } catch (OverlappingFileLockException e) {   // held by this same JVM
            channel.close();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static FileChannel open(Path projectRoot) throws IOException {
        Path dir = ProjectPaths.codeGraphDir(projectRoot);
        Files.createDirectories(dir);
        return FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
