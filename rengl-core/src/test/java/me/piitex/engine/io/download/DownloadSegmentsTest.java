package me.piitex.engine.io.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bandwidth limits, segmented transfers and proxies, against a small HTTP server in the test.
 */
class DownloadSegmentsTest {
    @TempDir
    Path dir;
    private HttpServer server;
    private byte[] content;
    private boolean ranges = true;
    private final List<String> rangeHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger open = new AtomicInteger(), maxOpen = new AtomicInteger(), requests = new AtomicInteger();
    private volatile int pauseMillis; // each response waits this long before sending, so overlapping connections are visible

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private URI serve(int size) throws IOException {
        content = new byte[size];
        new Random(7).nextBytes(content);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file.bin");
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        int now = open.incrementAndGet();
        maxOpen.accumulateAndGet(now, Math::max);
        try {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) rangeHeaders.add(range);
            if (pauseMillis > 0) {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException ignored) {
                }
            }
            long from = 0, to = content.length - 1;
            int status = 200;
            if (ranges && range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-");
                from = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) to = Math.min(to, Long.parseLong(parts[1]));
                status = 206;
                exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + content.length);
            }
            long length = to - from + 1;
            exchange.sendResponseHeaders(status, length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content, (int) from, (int) length);
            } catch (IOException clientHungUp) {
                // a paused or cancelled download closes its connection
            }
        } finally {
            open.decrementAndGet();
            exchange.close();
        }
    }

    private static boolean wait(BooleanSupplier done, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (done.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return done.getAsBoolean();
    }

    private Download finish(Download d) throws Exception {
        d.start();
        assertTrue(wait(() -> d.getState() == DownloadState.COMPLETED || d.getState() == DownloadState.FAILED, 20_000),
                "stuck in " + d.getState());
        assertEquals(DownloadState.COMPLETED, d.getState(), String.valueOf(d.getError()));
        assertArrayEquals(content, Files.readAllBytes(d.getTarget()));
        return d;
    }

    private List<Path> leftovers() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().contains(".part")).toList();
        }
    }

    // ---- segments

    @Test
    void aSingleConnectionStillWorks() throws Exception {
        URI url = serve(300_000);
        finish(new Download(url, dir.resolve("a.bin")));
        assertTrue(leftovers().isEmpty());
    }

    @Test
    void segmentsFetchDifferentPartsAtTheSameTime() throws Exception {
        pauseMillis = 150;
        URI url = serve(2_000_000);
        Download d = new Download(url, dir.resolve("seg.bin")).setSegments(4);
        finish(d);
        List<String> starts = rangeHeaders.stream().filter(r -> !r.equals("bytes=0-0")).toList();
        assertEquals(4, starts.size(), "one request for each part: " + rangeHeaders);
        assertEquals(4, starts.stream().distinct().count());
        assertTrue(maxOpen.get() >= 3, "the parts were fetched together, not one after another: " + maxOpen.get());
        assertTrue(leftovers().isEmpty(), "the joined parts leave nothing behind");
        assertEquals(content.length, d.getTotalBytes());
        assertEquals(1.0, d.getProgress());
    }

    @Test
    void aServerWithoutRangeSupportGetsOneConnection() throws Exception {
        ranges = false;
        URI url = serve(1_000_000);
        finish(new Download(url, dir.resolve("plain.bin")).setSegments(4));
        assertEquals(2, requests.get(), "the probe, then the one real request");
    }

    @Test
    void aSmallFileIsNotSplit() throws Exception {
        URI url = serve(100_000);
        finish(new Download(url, dir.resolve("small.bin")).setSegments(8));
        assertEquals(2, requests.get(), "too small for parts: the probe and one request");
    }

    @Test
    void segmentsAreCappedAtSixteen() {
        assertEquals(16, new Download("http://x/y", dir.resolve("c")).setSegments(99).getSegments());
        assertEquals(1, new Download("http://x/y", dir.resolve("c")).setSegments(0).getSegments());
    }

    @Test
    void aSegmentedDownloadPausesAndResumesWhereItLeftOff() throws Exception {
        URI url = serve(2_000_000);
        Download d = new Download(url, dir.resolve("resume.bin")).setSegments(4).setMaxBytesPerSecond(600_000);
        d.start();
        assertTrue(wait(() -> d.getDownloadedBytes() > 400_000, 10_000));
        d.pause();
        assertTrue(wait(() -> d.getState() == DownloadState.PAUSED, 3_000), "a pause is quick even when the cap makes it wait: " + d.getState());
        long kept = d.getDownloadedBytes();
        assertTrue(kept > 0 && kept < content.length);
        assertFalse(leftovers().isEmpty(), "the parts are kept");

        rangeHeaders.clear();
        d.setMaxBytesPerSecond(0);
        finish(d);
        assertTrue(rangeHeaders.stream().noneMatch(r -> r.startsWith("bytes=0-") && !r.equals("bytes=0-0") && !r.contains("-0")),
                "nothing was fetched again from the start of the first part: " + rangeHeaders);
        assertTrue(leftovers().isEmpty());
    }

    @Test
    void cancellingRemovesEveryPart() throws Exception {
        URI url = serve(2_000_000);
        Download d = new Download(url, dir.resolve("cancel.bin")).setSegments(4).setMaxBytesPerSecond(500_000);
        d.start();
        assertTrue(wait(() -> d.getDownloadedBytes() > 200_000, 10_000));
        d.cancel();
        assertTrue(wait(() -> d.getState() == DownloadState.CANCELLED, 3_000));
        assertTrue(leftovers().isEmpty(), leftovers().toString());
        assertFalse(Files.exists(dir.resolve("cancel.bin")));
    }

    // ---- bandwidth

    @Test
    void aCapSlowsTheTransfer() throws Exception {
        URI url = serve(600_000);
        long start = System.nanoTime();
        finish(new Download(url, dir.resolve("slow.bin")).setMaxBytesPerSecond(300_000));
        double seconds = (System.nanoTime() - start) / 1e9;
        assertTrue(seconds > 1.2, "600 kB at 300 kB/s should take about 2 s, took " + seconds);
        assertTrue(seconds < 8, "but not much longer: " + seconds);
    }

    @Test
    void theCapCoversAllSegmentsTogether() throws Exception {
        URI url = serve(1_200_000);
        long start = System.nanoTime();
        finish(new Download(url, dir.resolve("seg-slow.bin")).setSegments(4).setMaxBytesPerSecond(600_000));
        double seconds = (System.nanoTime() - start) / 1e9;
        assertTrue(seconds > 1.2, "1.2 MB at 600 kB/s should take about 2 s however many connections: " + seconds);
    }

    @Test
    void aManagerCapSharesTheSpeedBetweenItsDownloads() throws Exception {
        URI url = serve(400_000);
        DownloadManager manager = new DownloadManager(2);
        manager.setMaxBytesPerSecond(400_000);
        assertEquals(400_000, manager.getMaxBytesPerSecond());
        long start = System.nanoTime();
        Download a = manager.add(url, dir.resolve("m1.bin")), b = manager.add(url, dir.resolve("m2.bin"));
        assertTrue(wait(() -> a.getState() == DownloadState.COMPLETED && b.getState() == DownloadState.COMPLETED, 20_000));
        double seconds = (System.nanoTime() - start) / 1e9;
        assertTrue(seconds > 1.2, "800 kB between them at 400 kB/s should take about 2 s: " + seconds);
        assertArrayEquals(content, Files.readAllBytes(dir.resolve("m1.bin")));
        assertArrayEquals(content, Files.readAllBytes(dir.resolve("m2.bin")));
    }

    @Test
    void theCapCanBeChangedWhileItRuns() throws Exception {
        URI url = serve(3_000_000);
        Download d = new Download(url, dir.resolve("change.bin")).setMaxBytesPerSecond(100_000);
        d.start();
        assertTrue(wait(() -> d.getDownloadedBytes() > 50_000, 10_000));
        long start = System.nanoTime();
        d.setMaxBytesPerSecond(0);
        assertTrue(wait(() -> d.getState() == DownloadState.COMPLETED, 15_000));
        assertTrue((System.nanoTime() - start) / 1e9 < 8, "lifting the cap lets it run at full speed");
    }

    @Test
    void theThrottleHandsOutBytesAtItsRate() {
        Throttle throttle = new Throttle();
        assertEquals(0, throttle.reserve(1_000_000), "no limit, no wait");
        throttle.setRate(1000);
        assertEquals(0, throttle.reserve(1000), "the first bytes of an idle transfer go at once");
        long wait = throttle.reserve(1000);
        assertTrue(wait > 800_000_000L && wait < 1_100_000_000L, "the next second's worth waits a second: " + wait);
    }

    // ---- proxy

    @Test
    void aProxyReceivesTheRequest() throws Exception {
        serve(50_000);
        int port = server.getAddress().getPort();
        // example.invalid can never be looked up, so the file can only arrive through the proxy.
        Download d = new Download(URI.create("http://example.invalid/file.bin"), dir.resolve("proxied.bin")).setProxy("127.0.0.1", port);
        finish(d);
        assertTrue(requests.get() >= 1);
    }

    @Test
    void withoutTheProxyTheSameAddressFails() {
        Download d = new Download(URI.create("http://example.invalid/file.bin"), dir.resolve("noproxy.bin")).setMaxRetries(0);
        d.start();
        try {
            assertTrue(wait(() -> d.getState() == DownloadState.FAILED, 20_000));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        assertEquals(DownloadState.FAILED, d.getState());
    }
}
