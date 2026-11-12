package me.piitex.engine.access.speech;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Speaks a window's accessibility events: each time the keyboard focus lands on something it says what that is, and
 * {@link Window#announce} says whatever the game asks. This is self-voicing, the game talking for itself, not a
 * connection to a screen reader such as NVDA or VoiceOver; it works with nothing else installed.
 * <p>
 * Turn it on with {@link Accessibility#enable(Window)}. Focus is reached with Tab and Shift+Tab, a gamepad's bumpers, or
 * code; Space and Enter (the A button) use the focused control.
 */
public final class SpeechHost implements AccessibilityHost {
    private final Speaker speaker;
    private final Narrator narrator;
    private volatile boolean enabled = true;
    private volatile BooleanSupplier muted = () -> false;

    // A meter that keeps moving (an engine's heat) must not cut itself off: readings wait their turn, newest wins.
    private volatile long liveIntervalMillis = 2500;
    private ScheduledExecutorService liveTimer;
    private ScheduledFuture<?> liveFlush;
    private String livePending;
    private long lastSpokenNanos = System.nanoTime();

    public SpeechHost(Speaker speaker, Narrator narrator) {
        this.speaker = speaker;
        this.narrator = narrator;
    }

    public Speaker getSpeaker() {
        return speaker;
    }

    /**
     * The words it speaks, to translate or reword. See {@link Narrator#setWord}.
     */
    public Narrator getNarrator() {
        return narrator;
    }

    /**
     * Whether it speaks. A game's accessibility setting turns this on and off without removing the host.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) speaker.stop();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The least time between two spoken readings of a value that changes by itself, such as a gauge. While one is
     * still due, only the newest reading is kept and it is spoken when the time is up, without cutting off what is
     * being said. Default 2.5 seconds.
     */
    public void setLiveInterval(long millis) {
        this.liveIntervalMillis = Math.max(0, millis);
    }

    /**
     * Stays quiet whenever {@code condition} is true, such as while a screen reader is reading the window itself. It is
     * asked each time there is something to say.
     */
    public void muteWhile(BooleanSupplier condition) {
        this.muted = condition == null ? () -> false : condition;
    }

    private boolean speaking() {
        return enabled && !muted.getAsBoolean();
    }

    @Override
    public void focusChanged(Window window, Element previous, Element now, boolean byKeyboard) {
        if (!speaking()) return;
        dropLive(); // the reading that was waiting belonged to what the user just left
        if (now == null) {
            speaker.stop(); // focus left everything: whatever was being read is no longer where the user is
            return;
        }
        String text = narrator.describe(now);
        if (!text.isEmpty()) {
            speaker.speak(text, true);
            spoke();
        }
    }

    @Override
    public void valueChanged(Window window, Element element) {
        if (!speaking()) return;
        String text = narrator.describeChange(element);
        if (text.isEmpty()) return;
        if (element.getAccessibleRole() == AccessibleRole.PROGRESS_BAR) {
            speakLive(text);
            return;
        }
        speaker.speak(text, true);
        spoke();
    }

    private synchronized void spoke() {
        lastSpokenNanos = System.nanoTime();
    }

    private synchronized void dropLive() {
        livePending = null;
        if (liveFlush != null) {
            liveFlush.cancel(false);
            liveFlush = null;
        }
    }

    /**
     * Speaks a reading now if enough time has passed since anything was said, otherwise keeps it (replacing an older
     * one) for when the time is up. It queues behind the current sentence rather than cutting it off.
     */
    private synchronized void speakLive(String text) {
        long wait = lastSpokenNanos + TimeUnit.MILLISECONDS.toNanos(liveIntervalMillis) - System.nanoTime();
        if (wait <= 0 && liveFlush == null) {
            speaker.speak(text, false);
            lastSpokenNanos = System.nanoTime();
            return;
        }
        livePending = text;
        if (liveFlush == null) {
            if (liveTimer == null) {
                liveTimer = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "RenGL speech timer");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            liveFlush = liveTimer.schedule(this::flushLive, Math.max(0, wait), TimeUnit.NANOSECONDS);
        }
    }

    private synchronized void flushLive() {
        String text = livePending;
        livePending = null;
        liveFlush = null;
        if (text != null && speaking()) {
            speaker.speak(text, false);
            lastSpokenNanos = System.nanoTime();
        }
    }

    @Override
    public void announce(Window window, String text, boolean urgent) {
        if (!speaking()) return;
        speaker.speak(text, urgent);
        spoke();
    }

    @Override
    public void close() {
        dropLive();
        synchronized (this) {
            if (liveTimer != null) liveTimer.shutdownNow();
        }
        speaker.close();
    }
}
