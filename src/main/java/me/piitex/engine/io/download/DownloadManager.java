package me.piitex.engine.io.download;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A queue of {@link Download}s that runs at most {@link #getMaxConcurrent()} at a time and starts the next as each
 * one finishes, pauses or fails. It is also what a list display binds to ({@code DownloadListContainer}), and it
 * totals speed and progress across everything it owns.
 * <p>
 * Downloads are started in the order added. A download the manager owns can still be paused/cancelled directly;
 * to resume one, use {@link #resume(Download)} so it goes back through the queue and respects the limit (calling
 * {@link Download#start()} on it directly bypasses the limit).
 * <p>
 * Everything here is safe to call from any thread.
 */
public class DownloadManager {
    private final List<Download> downloads = new CopyOnWriteArrayList<>();
    private volatile int maxConcurrent;

    private final DownloadListener pump = new DownloadListener() {
        @Override
        public void onStateChanged(Download download, DownloadState state) {
            if (!state.isActive() && state != DownloadState.QUEUED) pump();
        }
    };

    public DownloadManager() {
        this(3);
    }

    public DownloadManager(int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        pump();
    }

    /** Queues {@code download} and starts it as soon as a slot is free. */
    public Download add(Download download) {
        download.markQueued();
        download.addListener(pump);
        downloads.add(download);
        pump();
        return download;
    }

    public Download add(URI url, Path target) {
        return add(new Download(url, target));
    }

    /** Queues a download of {@code url} to {@code directory/fileName}. */
    public Download add(URI url, Path directory, String fileName) {
        return add(new Download(url, directory.resolve(fileName)));
    }

    /** Puts a paused, failed or cancelled download back in the queue. */
    public void resume(Download download) {
        if (!downloads.contains(download)) return;
        download.markQueued();
        pump();
    }

    /** Stops tracking {@code download}. It is cancelled first if still running. */
    public void remove(Download download) {
        if (!download.getState().isFinished()) download.cancel();
        download.removeListener(pump);
        downloads.remove(download);
        pump();
    }

    /** Stops tracking every completed, failed or cancelled download. */
    public void clearFinished() {
        for (Download d : downloads) {
            if (d.getState().isFinished()) {
                d.removeListener(pump);
                downloads.remove(d);
            }
        }
    }

    /** Pauses everything running or waiting; {@link #resume} brings individual downloads back. */
    public void pauseAll() {
        for (Download d : downloads) d.pause();
    }

    public void cancelAll() {
        for (Download d : downloads) {
            if (!d.getState().isFinished()) d.cancel();
        }
    }

    /** A snapshot, in the order added. */
    public List<Download> getDownloads() {
        return List.copyOf(downloads);
    }

    public int getActiveCount() {
        int n = 0;
        for (Download d : downloads) if (d.getState().isActive()) n++;
        return n;
    }

    /** Combined speed of every running download, in bytes per second. */
    public double getTotalBytesPerSecond() {
        double sum = 0;
        for (Download d : downloads) sum += d.getBytesPerSecond();
        return sum;
    }

    /**
     * Progress across every download whose size is known, weighted by size, in [0, 1]. 0 if nothing has a known
     * size. Finished (completed) downloads count as whole.
     */
    public double getOverallProgress() {
        long done = 0, all = 0;
        for (Download d : downloads) {
            if (!d.isSizeKnown() || d.getState() == DownloadState.CANCELLED) continue;
            all += d.getTotalBytes();
            done += d.getState() == DownloadState.COMPLETED ? d.getTotalBytes() : d.getDownloadedBytes();
        }
        return all == 0 ? 0 : (double) done / all;
    }

    /** True when at least one download is queued or running. */
    public boolean isBusy() {
        for (Download d : downloads) {
            DownloadState s = d.getState();
            if (s == DownloadState.QUEUED || s.isActive()) return true;
        }
        return false;
    }

    private synchronized void pump() {
        int running = 0;
        for (Download d : downloads) if (d.getState().isActive()) running++;
        for (Download d : downloads) {
            if (running >= maxConcurrent) break;
            if (d.getState() == DownloadState.QUEUED) {
                d.start();
                running++;
            }
        }
    }
}
