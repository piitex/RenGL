package me.piitex.engine.ui.download;

import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link Download} into the one-line status text the download displays show, e.g.
 * {@code "45% - 18.0 MB / 40.0 MB - 3.2 MB/s - ETA 6s"}. Each part can be switched off, so the same class serves a
 * bare "speed only" readout and a full status line. The static helpers ({@link #bytes}, {@link #speed},
 * {@link #duration}) are public for building your own text.
 * <p>
 * Non-downloading states get a fixed wording ("Queued", "Paused - 45%", "Complete - 40.0 MB", "Failed: reason").
 */
public class DownloadFormat {
    private boolean percent = true;
    private boolean size = true;
    private boolean speed = true;
    private boolean eta = true;
    private String separator = " - ";

    public DownloadFormat setShowPercent(boolean show) {
        this.percent = show;
        return this;
    }

    public DownloadFormat setShowSize(boolean show) {
        this.size = show;
        return this;
    }

    public DownloadFormat setShowSpeed(boolean show) {
        this.speed = show;
        return this;
    }

    public DownloadFormat setShowEta(boolean show) {
        this.eta = show;
        return this;
    }

    /**
     * Text placed between parts. Default {@code " - "}.
     */
    public DownloadFormat setSeparator(String separator) {
        this.separator = separator;
        return this;
    }

    /**
     * The status text for {@code download} right now.
     */
    public String format(Download download) {
        DownloadState state = download.getState();
        switch (state) {
            case QUEUED:
                return "Queued";
            case CONNECTING:
                return "Connecting...";
            case VERIFYING:
                return "Verifying...";
            case CANCELLED:
                return "Cancelled";
            case FAILED: {
                Throwable error = download.getError();
                String reason = error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
                return "Failed: " + reason;
            }
            case COMPLETED:
                return join("Complete", size ? bytes(download.getTotalBytes()) : null);
            default:
                break;
        }

        List<String> parts = new ArrayList<>();
        if (state == DownloadState.PAUSED) parts.add("Paused");

        if (percent && download.isSizeKnown()) {
            parts.add(Math.round(download.getProgress() * 100) + "%");
        }
        if (size) {
            parts.add(download.isSizeKnown()
                    ? bytes(download.getDownloadedBytes()) + " / " + bytes(download.getTotalBytes())
                    : bytes(download.getDownloadedBytes()));
        }
        if (state == DownloadState.DOWNLOADING) {
            if (speed) parts.add(speed(download.getBytesPerSecond()));
            if (eta) {
                double seconds = download.getEtaSeconds();
                if (seconds >= 0) parts.add("ETA " + duration(seconds));
            }
        }
        return String.join(separator, parts);
    }

    private String join(String first, String second) {
        return second == null ? first : first + separator + second;
    }

    /**
     * {@code 1536} becomes {@code "1.5 KB"}; units are 1024-based (B, KB, MB, GB, TB). A negative size is {@code "?"}.
     */
    public static String bytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /**
     * {@code bytes(perSecond) + "/s"}.
     */
    public static String speed(double bytesPerSecond) {
        return bytes((long) bytesPerSecond) + "/s";
    }

    /**
     * {@code 75} becomes {@code "1m 15s"}; {@code 3700} becomes {@code "1h 1m"}.
     */
    public static String duration(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }
}
