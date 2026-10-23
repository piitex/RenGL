package me.piitex.engine.io.download;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>
 * <b>Speed.</b> {@link #setMaxBytesPerSecond} caps the transfer (a {@link DownloadManager} can cap everything it owns
 * together), {@link #setSegments} fetches a big file over several connections at once when the server allows it, and
 * {@link #setProxy(String, int)} sends it through a proxy.
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

    private static final Map<String, ProxySelector> PROXIES = new ConcurrentHashMap<>();
    private static final Map<ClientKey, HttpClient> CLIENTS = new ConcurrentHashMap<>();
    private static volatile ProxySelector defaultProxy;

    /** What makes one {@link HttpClient} differ from another: the proxy and the login for it. */
    private record ClientKey(ProxySelector proxy, String user, String password) {
    }

    /** A segment no smaller than this is worth a connection of its own. */
    private static final long MIN_SEGMENT_BYTES = 256L * 1024;
    private static final int MAX_SEGMENTS = 16;

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
    private int segments = 1;
    private ProxySelector proxy;
    private String proxyUser, proxyPassword;
    private final Throttle own = new Throttle();
    private volatile Throttle shared; // the manager's limit for all its downloads, if it has one

    private volatile DownloadState state = DownloadState.QUEUED;
    private volatile long downloaded;
    private volatile long total = -1;
    private volatile double bytesPerSecond;
    private volatile long lastSampleNanos;
    private volatile Throwable error;

    private volatile boolean stopRequested;
    private volatile boolean discardOnStop;
    private final Set<InputStream> activeStreams = ConcurrentHashMap.newKeySet();
    private long windowStart, windowBytes; // the speed sample being gathered, guarded by this

    /**
     * Downloads {@code url} to {@code target}; the parent folders are created when the transfer begins.
     */
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

    /**
     * Adds a request header, e.g. {@code Authorization}. Takes effect on the next attempt.
     */
    public Download setHeader(String name, String value) {
        synchronized (headers) {
            headers.put(name, value);
        }
        return this;
    }

    /**
     * Lowercase or uppercase hex SHA-256 the finished file must match, or null to skip verification.
     */
    public Download setExpectedSha256(String sha256) {
        this.expectedSha256 = sha256 == null ? null : sha256.trim().toLowerCase();
        return this;
    }

    /**
     * How many times a dropped connection is retried before failing; the count resets whenever an attempt made progress. Default 3.
     */
    public Download setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
        return this;
    }

    /**
     * Caps the transfer at about this many bytes per second, taking effect at once even mid-download; 0 or less
     * removes the cap. With several {@linkplain #setSegments segments} the cap is for all of them together. A
     * {@link DownloadManager} can also cap everything it owns; the slower of the two applies.
     */
    public Download setMaxBytesPerSecond(long bytesPerSecond) {
        own.setRate(bytesPerSecond);
        return this;
    }

    public long getMaxBytesPerSecond() {
        return own.getRate();
    }

    /**
     * Fetches the file over up to {@code segments} connections at once (1 to 16, default 1), each taking its own part of
     * it, which is faster when a server limits a single connection. It only happens when the server answers {@code
     * Range} requests and reports the file's size, and the file is big enough to give every connection at least 256
     * KiB; otherwise the download quietly uses one connection. Pause and resume work as usual: each part keeps its own
     * progress in a {@code .part.N} file next to the {@code .part}, and they are joined when all are done.
     */
    public Download setSegments(int segments) {
        this.segments = Math.max(1, Math.min(MAX_SEGMENTS, segments));
        return this;
    }

    public int getSegments() {
        return segments;
    }

    /**
     * Sends this download through a proxy, or back to the default if {@code selector} is null. Takes effect on the next
     * attempt.
     */
    public Download setProxy(ProxySelector selector) {
        this.proxy = selector;
        return this;
    }

    /**
     * Sends this download through the HTTP proxy at {@code host}:{@code port}.
     */
    public Download setProxy(String host, int port) {
        this.proxy = PROXIES.computeIfAbsent(host + ":" + port, key -> ProxySelector.of(new InetSocketAddress(host, port)));
        return this;
    }

    /**
     * The login for a proxy that asks for one. Basic authentication to a proxy for an HTTPS address is switched off in
     * Java unless the program is started with {@code -Djdk.http.auth.tunneling.disabledSchemes=""}.
     */
    public Download setProxyLogin(String user, String password) {
        this.proxyUser = user;
        this.proxyPassword = password;
        return this;
    }

    /**
     * The proxy every download uses unless it was given its own with {@link #setProxy}; null, the default, uses the
     * JVM's own settings ({@code http.proxyHost}, {@code https.proxyHost}, ...).
     */
    public static void setDefaultProxy(ProxySelector selector) {
        defaultProxy = selector;
    }

    /**
     * The name displays show. Defaults to the target's file name.
     */
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

    /**
     * Bytes on disk so far, including any resumed from an earlier attempt.
     */
    public long getDownloadedBytes() {
        return downloaded;
    }

    /**
     * Total size in bytes, or -1 while unknown.
     */
    public long getTotalBytes() {
        return total;
    }

    public boolean isSizeKnown() {
        return total > 0;
    }

    /**
     * Fraction complete in [0, 1]; 0 while the size is unknown (check {@link #isSizeKnown()}); 1 once completed.
     */
    public double getProgress() {
        if (state == DownloadState.COMPLETED) return 1.0;
        long t = total;
        if (t <= 0) return 0.0;
        return Math.min(1.0, (double) downloaded / t);
    }

    /**
     * Smoothed speed in bytes per second; 0 when not downloading or when no data has arrived for two seconds.
     */
    public double getBytesPerSecond() {
        if (state != DownloadState.DOWNLOADING) return 0;
        if (System.nanoTime() - lastSampleNanos > STALL_NANOS) return 0;
        return bytesPerSecond;
    }

    /**
     * Estimated seconds remaining, or -1 if the size or speed is unknown.
     */
    public double getEtaSeconds() {
        double speed = getBytesPerSecond();
        if (total <= 0 || speed < 1) return -1;
        return Math.max(0, (total - downloaded) / speed);
    }

    /**
     * Why the download failed, or null.
     */
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

    /**
     * Stops the transfer but keeps the partial file; {@link #start()} resumes it. A queued download becomes paused too.
     */
    public void pause() {
        if (state == DownloadState.QUEUED) {
            setState(DownloadState.PAUSED);
            return;
        }
        stop(false);
    }

    /**
     * Stops the transfer and deletes the partial file. Also works on a queued or paused download.
     */
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
        closeActiveStreams(); // unblocks a read stuck on a stalled connection
    }

    private void closeActiveStreams() {
        for (InputStream stream : activeStreams) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Package-private: the limit a {@link DownloadManager} sets for everything it owns.
     */
    void setSharedThrottle(Throttle throttle) {
        this.shared = throttle;
    }

    /**
     * Package-private: puts a not-running download back in line for a {@link DownloadManager}.
     */
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

    private HttpClient client() {
        ProxySelector selector = proxy != null ? proxy : defaultProxy;
        if (selector == null && proxyUser == null) return CLIENT;
        return CLIENTS.computeIfAbsent(new ClientKey(selector, proxyUser, proxyPassword), key -> {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15));
            if (key.proxy() != null) builder.proxy(key.proxy());
            if (key.user() != null) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return getRequestorType() == RequestorType.PROXY
                                ? new PasswordAuthentication(key.user(), key.password() == null ? new char[0] : key.password().toCharArray()) : null;
                    }
                });
            }
            return builder.build();
        });
    }

    private HttpRequest.Builder request() {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).GET().timeout(Duration.ofSeconds(30));
        synchronized (headers) {
            headers.forEach(request::header);
        }
        return request;
    }

    private void transfer() throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        if (segments > 1 && transferSegmented()) return;
        transferSingle();
    }

    private void transferSingle() throws IOException, InterruptedException {
        long existing = Files.exists(partFile) ? Files.size(partFile) : 0;
        HttpRequest.Builder request = request();
        if (existing > 0) request.header("Range", "bytes=" + existing + "-");

        HttpResponse<InputStream> response = client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
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

        beginTransfer(existing);

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(partFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            activeStreams.add(in);
            pump(in, out, Long.MAX_VALUE);
        } catch (IOException e) {
            if (stopRequested) throw new StopException();
            throw e;
        } finally {
            activeStreams.clear();
        }

        if (total > 0 && downloaded < total) {
            throw new IOException("Connection closed after " + downloaded + " of " + total + " bytes");
        }
        if (total < 0 || downloaded > total) total = downloaded; // chunked response: the real size is what arrived
    }

    /**
     * Resets the speed measurement and moves to DOWNLOADING with {@code existing} bytes already on disk.
     */
    private void beginTransfer(long existing) {
        synchronized (this) {
            downloaded = existing;
            bytesPerSecond = 0;
            lastSampleNanos = System.nanoTime();
            windowStart = lastSampleNanos;
            windowBytes = 0;
        }
        setState(DownloadState.DOWNLOADING);
    }

    /**
     * Copies up to {@code limit} bytes of {@code in} to {@code out}, counting them, waiting as the bandwidth limits say,
     * and stopping if a pause or cancel is asked for. Returns how many it copied.
     */
    private long pump(InputStream in, OutputStream out, long limit) throws IOException, InterruptedException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        while (copied < limit) {
            int want = (int) Math.min(buffer.length, limit - copied);
            long cap = effectiveLimit();
            if (cap > 0) want = (int) Math.min(want, Math.max(1024, cap / 10)); // small reads keep a slow limit smooth
            int read = in.read(buffer, 0, want);
            if (read < 0) break;
            if (stopRequested) throw new StopException();
            out.write(buffer, 0, read);
            copied += read;
            record(read);
            pace(read);
        }
        return copied;
    }

    /**
     * The tighter of this download's cap and the manager's, or 0 for none.
     */
    private long effectiveLimit() {
        long mine = own.getRate();
        Throttle manager = shared;
        long theirs = manager == null ? 0 : manager.getRate();
        if (mine <= 0) return theirs;
        return theirs <= 0 ? mine : Math.min(mine, theirs);
    }

    /**
     * Waits long enough that the bytes just read keep the transfer under its caps, in short sleeps so a pause or cancel
     * is not kept waiting.
     */
    private void pace(int bytes) throws InterruptedException, StopException {
        Throttle manager = shared;
        long wait = Math.max(own.reserve(bytes), manager == null ? 0 : manager.reserve(bytes));
        if (wait <= 0) return;
        long deadline = System.nanoTime() + wait;
        long left;
        while ((left = deadline - System.nanoTime()) > 0) {
            if (stopRequested) throw new StopException();
            Thread.sleep(Math.min(50L, left / 1_000_000L + 1L));
        }
    }

    /**
     * Counts bytes that reached the disk, and refreshes the speed every quarter second. Called by every connection.
     */
    private synchronized void record(int bytes) {
        downloaded += bytes;
        windowBytes += bytes;
        long now = System.nanoTime();
        if (now - windowStart >= SAMPLE_NANOS) {
            double instant = windowBytes * 1_000_000_000.0 / (now - windowStart);
            bytesPerSecond = bytesPerSecond == 0 ? instant : bytesPerSecond * 0.7 + instant * 0.3;
            lastSampleNanos = now;
            windowStart = now;
            windowBytes = 0;
        }
    }

    // ---------------------------------------------------------------- segmented transfer

    private Path segmentFile(int index) {
        return index == 0 ? partFile : partFile.resolveSibling(partFile.getFileName() + "." + index);
    }

    /**
     * The file's size if the server answers range requests, else -1.
     */
    private long probeSize() throws IOException, InterruptedException {
        HttpResponse<Void> response = client().send(request().header("Range", "bytes=0-0").build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 206) return -1;
        return parseRangeTotal(response.headers().firstValue("Content-Range").orElse(null));
    }

    /**
     * Downloads over several connections. Returns false, having done nothing, when the server or the file doesn't suit
     * it, so the caller uses one connection.
     */
    private boolean transferSegmented() throws IOException, InterruptedException {
        long size = probeSize();
        if (stopRequested) throw new StopException();
        int count = (int) Math.min(segments, size / MIN_SEGMENT_BYTES);
        if (size <= 0 || count < 2) return false;

        total = size;
        long length = (size + count - 1) / count;
        long[] from = new long[count], to = new long[count], have = new long[count];
        long already = 0;
        for (int i = 0; i < count; i++) {
            from[i] = i * length;
            to[i] = Math.min(size, (i + 1L) * length) - 1;
            long room = to[i] - from[i] + 1;
            Path file = segmentFile(i);
            long onDisk = Files.exists(file) ? Files.size(file) : 0;
            if (onDisk > room) { // from a run with another layout: keep what belongs to this part
                try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.truncate(room);
                }
                onDisk = room;
            }
            have[i] = onDisk;
            already += onDisk;
        }
        beginTransfer(already);

        AtomicBoolean abort = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            threads.add(Thread.ofVirtual().name("download-" + displayName + "-" + i).start(() -> {
                try {
                    fetchSegment(segmentFile(index), from[index], to[index], have[index], abort);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    abort.set(true);
                    closeActiveStreams();
                }
            }));
        }
        try {
            for (Thread thread : threads) thread.join();
        } catch (InterruptedException e) {
            abort.set(true);
            closeActiveStreams();
            throw e;
        } finally {
            activeStreams.clear();
        }
        if (stopRequested) throw new StopException();
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof IOException io && !(error instanceof StopException)) throw io;
            if (error instanceof StopException) throw new IOException("a connection was interrupted", error);
            throw new IOException(error.toString(), error);
        }

        for (int i = 1; i < count; i++) { // join the parts onto the first
            Path file = segmentFile(i);
            try (var out = java.nio.channels.FileChannel.open(partFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 var in = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ)) {
                long moved = 0, partSize = in.size();
                while (moved < partSize) moved += in.transferTo(moved, partSize - moved, out);
            }
            Files.delete(file);
        }
        if (Files.size(partFile) != size) throw new IOException("Joined parts are " + Files.size(partFile) + " bytes, not " + size);
        return true;
    }

    /**
     * Fetches bytes {@code from} + {@code have} to {@code to} (inclusive) into {@code file}, after what is already there.
     */
    private void fetchSegment(Path file, long from, long to, long have, AtomicBoolean abort) throws IOException, InterruptedException {
        long start = from + have;
        if (start > to) return; // this part was finished in an earlier run
        HttpResponse<InputStream> response = client().send(request().header("Range", "bytes=" + start + "-" + to).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (abort.get() || stopRequested) {
            response.body().close();
            throw new StopException();
        }
        if (response.statusCode() != 206) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " from " + url + " for the range " + start + "-" + to);
        }
        long wanted = to - start + 1;
        InputStream in = response.body();
        activeStreams.add(in);
        try (in; OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (abort.get() || stopRequested) throw new StopException();
            long copied = pump(in, out, wanted);
            if (copied < wanted) throw new IOException("Connection closed after " + copied + " of " + wanted + " bytes of a part");
        } catch (IOException e) {
            if (stopRequested || abort.get() && !(e instanceof StopException)) throw new StopException();
            throw e;
        } finally {
            activeStreams.remove(in);
        }
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
        deleteSegmentFiles();
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
        deleteSegmentFiles();
    }

    /**
     * Removes the {@code .part.N} files of a segmented transfer, which a finished or cancelled download leaves behind.
     */
    private void deleteSegmentFiles() {
        for (int i = 1; i < MAX_SEGMENTS; i++) {
            try {
                Files.deleteIfExists(segmentFile(i));
            } catch (IOException e) {
                log.warn("Could not delete {}", segmentFile(i), e);
            }
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

    /**
     * Unwinds the transfer loop when {@link #pause()}/{@link #cancel()} was requested.
     */
    private static final class StopException extends IOException {
        StopException() {
            super("stopped");
        }
    }
}
