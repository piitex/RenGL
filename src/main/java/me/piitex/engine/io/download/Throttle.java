package me.piitex.engine.io.download;

/**
 * Hands out bytes at no more than a set rate, for the bandwidth limit of a {@link Download} or a
 * {@link DownloadManager}. Callers ask for the bytes they have just read and wait as long as it says. Safe from any
 * thread. Package-private.
 */
final class Throttle {
    private volatile long bytesPerSecond;
    private long next; // the nano time at which the next byte may go

    long getRate() {
        return bytesPerSecond;
    }

    /**
     * 0 or less for no limit.
     */
    synchronized void setRate(long bytesPerSecond) {
        this.bytesPerSecond = Math.max(0L, bytesPerSecond);
        next = 0; // a new rate starts afresh
    }

    /**
     * Counts {@code bytes} against the limit and returns how many nanoseconds to wait before they may be used.
     * A transfer that has been idle gets its first bytes at once, so the average, not the burst, is what is limited.
     */
    synchronized long reserve(int bytes) {
        long rate = bytesPerSecond;
        if (rate <= 0) return 0;
        long now = System.nanoTime();
        long start = Math.max(now, next);
        next = start + bytes * 1_000_000_000L / rate;
        return Math.max(0L, start - now);
    }
}
