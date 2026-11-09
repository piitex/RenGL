package me.piitex.engine.webview.chromium;

import me.piitex.engine.io.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One Chrome DevTools protocol connection over a WebSocket: sends commands, matches their replies and hands events on.
 * Everything it reports is run on the executor it is given, one after the other, so the backend that owns it needs no
 * locking of its own.
 */
final class CdpConnection implements WebSocket.Listener {
    /** What a connection reports. */
    interface Handler {
        void event(String method, Map<String, Object> params, String sessionId);

        void closed(String reason);
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Handler handler;
    private final Executor executor;
    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Consumer<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final StringBuilder partial = new StringBuilder();
    private final Object sendLock = new Object();
    private volatile WebSocket socket;
    private volatile boolean closing;

    CdpConnection(Handler handler, Executor executor) {
        this.handler = handler;
        this.executor = executor;
    }

    void connect(URI uri) throws Exception {
        socket = CLIENT.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, this).get(15, TimeUnit.SECONDS);
    }

    /**
     * Runs a command. {@code reply}, if given, gets the whole answer ({@code result} or {@code error} in it) on the
     * executor; null means nobody waits for it.
     */
    void send(String sessionId, String method, String paramsJson, Consumer<Map<String, Object>> reply) {
        WebSocket ws = socket;
        if (ws == null || closing) {
            if (reply != null) executor.execute(() -> reply.accept(Map.of("error", Map.of("message", "closed"))));
            return;
        }
        int id = ids.incrementAndGet();
        if (reply != null) pending.put(id, reply);
        StringBuilder b = new StringBuilder(64 + (paramsJson == null ? 0 : paramsJson.length()));
        b.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append('"');
        if (paramsJson != null) b.append(",\"params\":").append(paramsJson);
        if (sessionId != null) b.append(",\"sessionId\":\"").append(sessionId).append('"');
        b.append('}');
        try {
            synchronized (sendLock) { // one send at a time on a socket
                ws.sendText(b, true).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            Consumer<Map<String, Object>> r = pending.remove(id);
            if (r != null) executor.execute(() -> r.accept(Map.of("error", Map.of("message", String.valueOf(e.getMessage())))));
        }
    }

    void close() {
        closing = true;
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
        failPending("closed");
    }

    private void failPending(String why) {
        for (Integer id : pending.keySet()) {
            Consumer<Map<String, Object>> r = pending.remove(id);
            if (r != null) executor.execute(() -> r.accept(Map.of("error", Map.of("message", why))));
        }
    }

    // ------------------------------------------------------------------------------------------------ WebSocket.Listener

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                Object parsed = Json.parse(text);
                if (parsed instanceof Map<?, ?> m) {
                    Map<String, Object> message = (Map<String, Object>) m;
                    Object id = message.get("id");
                    if (id instanceof Number n) {
                        Consumer<Map<String, Object>> reply = pending.remove(n.intValue());
                        if (reply != null) executor.execute(() -> reply.accept(message));
                    } else if (message.get("method") instanceof String method) {
                        Object params = message.get("params");
                        Map<String, Object> p = params instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
                        String session = message.get("sessionId") instanceof String s ? s : null;
                        executor.execute(() -> handler.event(method, p, session));
                    }
                }
            } catch (RuntimeException e) {
                // a message that is not understood is dropped; the next one is fine
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        boolean expected = closing;
        closing = true;
        failPending("closed");
        if (!expected) executor.execute(() -> handler.closed("The browser closed its connection"));
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        boolean expected = closing;
        closing = true;
        failPending("connection failed");
        if (!expected) executor.execute(() -> handler.closed("The connection to the browser failed: " + error));
    }
}
