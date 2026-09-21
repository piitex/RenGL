package me.piitex.engine.io.download;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One file being downloaded over HTTP(S) on a background thread, with everything a UI needs to show it: state,
 * bytes so far, total size, smoothed speed and ETA.
 * <p>
 * <b>Threading.</b> The transfer runs on its own (virtual) thread and every getter is safe to call from any
 * thread, so the render thread can simply read {@link #getProgress()}, {@link #getBytesPerSecond()} and friends
 * once per frame. That polling is what the display classes do; {@link DownloadListener}s exist for the moments
 * that matter (finished, failed) and run on the worker thread.
 * <p>
 * <b>Behavior.</b> Bytes are written to {@code <target>.part} and moved to the target only when complete (and
 * verified, if {@link #setExpectedSha256} was given), so a crash never leaves a half-file that looks finished.
 * {@link #pause()} keeps the {@code .part} file and {@link #start()} continues it with an HTTP {@code Range}
 * request; a dropped connection is retried the same way, up to {@link #setMaxRetries}. If the server ignores
 * {@code Range} the file restarts from zero.
 * <pre>{@code
 * Download d = new Download(URI.create("https://example.com/backend.zip"), env.getDirectory("backend").resolve("backend.zip"));
 * d.setExpectedSha256("9f86d0...");
 * d.addListener(new DownloadListener() {
 *     public void onComplete(Download done) { extractLater = true; }
 * });
 * d.start();
 * }</pre>
 */
public class Download {
    private static final Logger log = LogManager.getLogger("Download");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final long SAMPLE_NANOS = 250_000_000L;
    private static final long STALL_NANOS = 2_000_000_000L;

    private final URI url;
    private final Path target;
    private final Path partFile;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<DownloadListener> listeners = new CopyOnWriteArrayList<>();

    private String displayName;
    private String expectedSha256;
    private int maxRetries = 3;

    private volatile DownloadState state = DownloadState.QUEUED;
    private volatile long downloaded;
    private volatile long total = -1;
    private volatile double bytesPerSecond;
    private volatile long lastSampleNanos;
    private volatile Throwable error;

    private volatile boolean stopRequested;
    private volatile boolean discardOnStop;
    private volatile InputStream activeStream;

    /** Downloads {@code url} to {@code target}; the parent folders are created when the transfer begins. */
    public Download(URI url, Path target) {
        this.url = url;
        this.target = target;
        this.partFile = target.resolveSibling(target.getFileName() + ".part");
        this.displayName = target.getFileName().toString();
    }

    public Download(String url, Path target) {
        this(URI.create(url), target);
    }

    // ---------------------------------------------------------------- configuration

    /** Adds a request header, e.g. {@code Authorization}. Takes effect on the next attempt. */
    public Download setHeader(String name, String value) {
        synchronized (headers) {
            headers.put(name, value);
        }
        return this;
    }

    /** Lowercase or uppercase hex SHA-256 the finished file must match, or null to skip verification. */
    public Download setExpectedSha256(String sha256) {
        this.expectedSha256 = sha256 == null ? null : sha256.trim().toLowerCase();
        return this;
    }

    /** How many times a dropped connection is retried before failing; the count resets whenever an attempt made progress. Default 3. */
    public Download setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
        return this;
    }

    /** The name displays show. Defaults to the target's file name. */
    public Download setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    /**
     * Size to assume when the server sends no {@code Content-Length} (chunked responses). Ignored once the server
     * reports a real size. Lets a manifest-driven app still show a determinate bar.
     */
    public Download setExpectedSize(long bytes) {
        if (total < 0) total = bytes;
        return this;
    }

    public void addListener(DownloadListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DownloadListener listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------------- state (safe from any thread)

    public URI getUrl() {
        return url;
    }

    public Path getTarget() {
        return target;
    }

    public String getDisplayName() {
        return displayName;
    }

    public DownloadState getState() {
        return state;
    }

    /** Bytes on disk so far, including any resumed from an earlier attempt. */
    public long getDownloadedBytes() {
        return downloaded;
    }

    /** Total size in bytes, or -1 while unknown. */
    public long getTotalBytes() {
        return total;
    }

    public boolean isSizeKnown() {
        return total > 0;
    }

    /** Fraction complete in [0, 1]; 0 while the size is unknown (check {@link #isSizeKnown()}); 1 once completed. */
    public double getProgress() {
        if (state == DownloadState.COMPLETED) return 1.0;
        long t = total;
        if (t <= 0) return 0.0;
        return Math.min(1.0, (double) downloaded / t);
    }

    /** Smoothed speed in bytes per second; 0 when not downloading or when no data has arrived for two seconds. */
    public double getBytesPerSecond() {
        if (state != DownloadState.DOWNLOADING) return 0;
        if (System.nanoTime() - lastSampleNanos > STALL_NANOS) return 0;
        return bytesPerSecond;
    }

    /** Estimated seconds remaining, or -1 if the size or speed is unknown. */
    public double getEtaSeconds() {
        double speed = getBytesPerSecond();
        if (total <= 0 || speed < 1) return -1;
        return Math.max(0, (total - downloaded) / speed);
    }

    /** Why the download failed, or null. */
    public Throwable getError() {
        return error;
    }

    // ---------------------------------------------------------------- control

    /**
     * Starts, or resumes from a {@code .part} file. A no-op while already active or completed. Restarting a failed
     * or cancelled download begins a fresh attempt.
     */
    public synchronized void start() {
        if (state.isActive() || state == DownloadState.COMPLETED) return;
        stopRequested = false;
        discardOnStop = false;
        error = null;
        setState(DownloadState.CONNECTING);
        Thread.ofVirtual().name("download-" + displayName).start(this::run);
    }

    /** Stops the transfer but keeps the partial file; {@link #start()} resumes it. A queued download becomes paused too. */
    public void pause() {
        if (state == DownloadState.QUEUED) {
            setState(DownloadState.PAUSED);
            return;
        }
        stop(false);
    }

    /** Stops the transfer and deletes the partial file. Also works on a queued or paused download. */
    public void cancel() {
        if (!state.isActive()) {
            if (state == DownloadState.QUEUED || state == DownloadState.PAUSED) {
                deletePart();
                setState(DownloadState.CANCELLED);
            }
            return;
        }
        stop(true);
    }

    private void stop(boolean discard) {
        if (!state.isActive()) return;
        discardOnStop = discard;
        stopRequested = true;
        InputStream stream = activeStream;
        if (stream != null) {
            try {
                stream.close(); // unblocks a read stuck on a stalled connection
            } catch (IOException ignored) {
            }
        }
    }

    /** Package-private: puts a not-running download back in line for a {@link DownloadManager}. */
    synchronized void markQueued() {
        if (!state.isActive() && state != DownloadState.COMPLETED) {
            setState(DownloadState.QUEUED);
        }
    }

    // ---------------------------------------------------------------- worker

    private void run() {
        int attempt = 0;
        try {
            while (true) {
                long before = downloaded;
                try {
                    transfer();
                    break;
                } catch (StopException e) {
                    finishStopped();
                    return;
                } catch (IOException | InterruptedException e) {
                    if (stopRequested) {
                        finishStopped();
                        return;
                    }
                    if (downloaded > before) attempt = 0;
                    if (attempt++ >= maxRetries) {
                        fail(e);
                        return;
                    }
                    log.warn("Download {} interrupted ({}), retry {}/{}", displayName, e.getMessage(), attempt, maxRetries);
                    bytesPerSecond = 0;
                    setState(DownloadState.CONNECTING);
                    try {
                        Thread.sleep(Math.min(8000L, 500L << attempt));
                    } catch (InterruptedException ie) {
                        fail(ie);
                        return;
                    }
                }
            }
            verifyAndFinish();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void transfer() throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().getParent());

        long existing = Files.exists(partFile) ? Files.size(partFile) : 0;
        HttpRequest.Builder request = HttpRequest.newBuilder(url).GET().timeout(Duration.ofSeconds(30));
        synchronized (headers) {
            headers.forEach(request::header);
        }
        if (existing > 0) request.header("Range", "bytes=" + existing + "-");

        HttpResponse<InputStream> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (stopRequested) {
            response.body().close();
            throw new StopException();
        }

        int code = response.statusCode();
        boolean append;
        if (code == 206) {
            append = true;
            long fromRange = parseRangeTotal(response.headers().firstValue("Content-Range").orElse(null));
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (fromRange > 0) total = fromRange;
            else if (length >= 0) total = existing + length;
        } else if (code == 200) {
            append = false;
            existing = 0; // the server ignored Range (or there was nothing to resume): start over
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length >= 0) total = length;
        } else {
            response.body().close();
            if (code == 416) Files.deleteIfExists(partFile); // stale .part bigger than the file: restart cleanly
            throw new IOException("HTTP " + code + " from " + url);
        }

        downloaded = existing;
        bytesPerSecond = 0;
        lastSampleNanos = System.nanoTime();
        setState(DownloadState.DOWNLOADING);

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(partFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            activeStream = in;
            byte[] buffer = new byte[64 * 1024];
            long windowStart = System.nanoTime();
            long windowBytes = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (stopRequested) throw new StopException();
                out.write(buffer, 0, read);
                downloaded += read; // single writer: only this thread mutates it
                windowBytes += read;

                long now = System.nanoTime();
                if (now - windowStart >= SAMPLE_NANOS) {
                    double instant = windowBytes * 1_000_000_000.0 / (now - windowStart);
                    bytesPerSecond = bytesPerSecond == 0 ? instant : bytesPerSecond * 0.7 + instant * 0.3;
                    lastSampleNanos = now;
                    windowStart = now;
                    windowBytes = 0;
                }
            }
        } catch (IOException e) {
            if (stopRequested) throw new StopException();
            throw e;
        } finally {
            activeStream = null;
        }

        if (total > 0 && downloaded < total) {
            throw new IOException("Connection closed after " + downloaded + " of " + total + " bytes");
        }
        if (total < 0 || downloaded > total) total = downloaded; // chunked response: the real size is what arrived
    }

    private void verifyAndFinish() throws IOException, NoSuchAlgorithmException {
        bytesPerSecond = 0;
        if (expectedSha256 != null) {
            setState(DownloadState.VERIFYING);
            String actual = sha256(partFile);
            if (!actual.equals(expectedSha256)) {
                Files.deleteIfExists(partFile);
                throw new IOException("Checksum mismatch: expected " + expectedSha256 + " but got " + actual);
            }
        }
        try {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
        setState(DownloadState.COMPLETED);
        for (DownloadListener listener : listeners) {
            try {
                listener.onComplete(this);
            } catch (RuntimeException e) {
                log.error("Download listener threw", e);
            }
        }
    }

    private void finishStopped() {
        bytesPerSecond = 0;
        if (discardOnStop) {
            deletePart();
            downloaded = 0;
            setState(DownloadState.CANCELLED);
        } else {
            setState(DownloadState.PAUSED);
        }
    }

    private void fail(Throwable t) {
        log.error("Download {} failed", displayName, t);
        bytesPerSecond = 0;
        error = t;
        setState(DownloadState.FAILED);
        for (DownloadListener listener : listeners) {
            try {
                listener.onFailed(this, t);
            } catch (RuntimeException e) {
                log.error("Download listener threw", e);
            }
        }
    }

    private void deletePart() {
        try {
            Files.deleteIfExists(partFile);
        } catch (IOException e) {
            log.warn("Could not delete {}", partFile, e);
        }
    }

    private void setState(DownloadState newState) {
        state = newState;
        for (DownloadListener listener : listeners) {
            try {
                listener.onStateChanged(this, newState);
            } catch (RuntimeException e) {
                log.error("Download listener threw", e);
            }
        }
    }

    private static long parseRangeTotal(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1; // "*", meaning the server does not know
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Unwinds the transfer loop when {@link #pause()}/{@link #cancel()} was requested. */
    private static final class StopException extends IOException {
        StopException() {
            super("stopped");
        }
    }
}
