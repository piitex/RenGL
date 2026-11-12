package me.piitex.engine.access.speech;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityHost;

/**
 * The one call that makes a window talk.
 * <pre>{@code
 * Window window = new Window(options);
 * Accessibility.enable(window);          // Tab now reads each control aloud
 * window.announce("Game saved");         // anything the game wants said
 * }</pre>
 * What gets read comes from each Element's accessible name, role and value; see {@link me.piitex.engine.ui.Element#setAccessibleName} and
 * {@link me.piitex.engine.ui.Element#setLabelledBy} for naming a check box or slider, whose label is a separate Element.
 *
 * @see SpeechHost
 */
public final class Accessibility {
    private Accessibility() {
    }

    /**
     * Makes {@code window} speak with the system's voice. Replaces any host already installed; the old one is closed.
     */
    public static SpeechHost enable(Window window) {
        return enable(window, Speakers.system());
    }

    /**
     * As {@link #enable(Window)}, with a voice of your own.
     */
    public static SpeechHost enable(Window window, Speaker speaker) {
        SpeechHost host = new SpeechHost(speaker, new Narrator());
        window.setAccessibilityHost(host);
        return host;
    }

    /**
     * Stops {@code window} speaking and releases the voice. A no-op if its host is not a {@link SpeechHost}.
     */
    public static void disable(Window window) {
        AccessibilityHost host = window.getAccessibilityHost();
        if (host instanceof SpeechHost) window.setAccessibilityHost(null);
    }
}
