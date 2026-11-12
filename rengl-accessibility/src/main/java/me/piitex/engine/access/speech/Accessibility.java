package me.piitex.engine.access.speech;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.access.uia.UiaHost;

/**
 * The one call that makes a window usable without sight.
 * <pre>{@code
 * Window window = new Window(options);
 * Accessibility.enable(window);          // Tab now reads each control aloud; NVDA and Narrator can read the window
 * window.announce("Game saved");         // anything the game wants said
 * }</pre>
 * Two things are switched on together:
 * <ul>
 *   <li><b>Self-voicing</b> ({@link SpeechHost}): the window speaks through the system's text-to-speech. It stays quiet
 *       while a screen reader is running, so nothing is said twice.</li>
 *   <li><b>A screen reader connection</b> ({@link UiaHost}, Windows only): NVDA, JAWS and Narrator can read the window's
 *       controls through UI Automation, and press, toggle and set them.</li>
 * </ul>
 * What gets read comes from each Element's accessible name, role and value; see {@link me.piitex.engine.ui.Element#setAccessibleName} and
 * {@link me.piitex.engine.ui.Element#setLabelledBy} for naming a check box or slider, whose label is a separate Element.
 *
 * @see SpeechHost
 * @see UiaHost
 */
public final class Accessibility {
    private Accessibility() {
    }

    /**
     * Makes {@code window} speak with the system's voice, and readable by a screen reader where there is a way to
     * connect one. Replaces any host already installed; the old one is closed.
     *
     * @return the self-voicing half, for its settings
     */
    public static SpeechHost enable(Window window) {
        return enable(window, Speakers.system());
    }

    /**
     * As {@link #enable(Window)}, with a voice of your own.
     */
    public static SpeechHost enable(Window window, Speaker speaker) {
        SpeechHost speech = new SpeechHost(speaker, new Narrator());
        if (UiaHost.isSupported()) {
            speech.muteWhile(UiaHost::screenReaderRunning);
            window.setAccessibilityHost(new HostGroup(speech, new UiaHost(window)));
        } else {
            window.setAccessibilityHost(speech);
        }
        return speech;
    }

    /**
     * Only the speech, with no connection to screen readers.
     */
    public static SpeechHost enableSpeech(Window window, Speaker speaker) {
        SpeechHost speech = new SpeechHost(speaker, new Narrator());
        window.setAccessibilityHost(speech);
        return speech;
    }

    /**
     * Only the screen reader connection, with no voice of the window's own.
     *
     * @return the connection, or null where there is no way to make one (anywhere but Windows)
     */
    public static UiaHost enableScreenReader(Window window) {
        if (!UiaHost.isSupported()) return null;
        UiaHost host = new UiaHost(window);
        window.setAccessibilityHost(host);
        return host;
    }

    /**
     * Stops {@code window} speaking and being read, and releases the voice. A no-op if its host is not one of ours.
     */
    public static void disable(Window window) {
        AccessibilityHost host = window.getAccessibilityHost();
        if (host instanceof SpeechHost || host instanceof HostGroup || host instanceof UiaHost) window.setAccessibilityHost(null);
    }
}
