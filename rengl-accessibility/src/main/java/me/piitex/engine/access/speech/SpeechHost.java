package me.piitex.engine.access.speech;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.ui.Element;

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
        if (now == null) {
            speaker.stop(); // focus left everything: whatever was being read is no longer where the user is
            return;
        }
        String text = narrator.describe(now);
        if (!text.isEmpty()) speaker.speak(text, true);
    }

    @Override
    public void valueChanged(Window window, Element element) {
        if (!speaking()) return;
        String text = narrator.describeChange(element);
        if (!text.isEmpty()) speaker.speak(text, true);
    }

    @Override
    public void announce(Window window, String text, boolean urgent) {
        if (speaking()) speaker.speak(text, urgent);
    }

    @Override
    public void close() {
        speaker.close();
    }
}
