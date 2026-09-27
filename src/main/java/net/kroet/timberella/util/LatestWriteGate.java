package net.kroet.timberella.util;

/**
 * Runs a write only if its version is newer than the last one that ran, so a
 * delayed older snapshot never overwrites a newer one on disk.
 */
public final class LatestWriteGate {

    private long lastWritten = 0L;

    /**
     * Returns {@code true} if {@code write} ran, {@code false} if it was outdated.
     */
    public synchronized boolean writeIfNewer(long version, Runnable write) {
        if (version <= lastWritten) {
            return false;
        }
        write.run();
        lastWritten = version;
        return true;
    }
}
