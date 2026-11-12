package me.piitex.engine.access.speech;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real PowerShell helper with its voice pointed at nothing, so it exercises SAPI without making a sound.
 * Skips off Windows and where the synthesizer cannot start.
 */
class WindowsSpeakerTest {
    @Test
    void theHelperStartsAndAnswersEveryCommand() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        WindowsSpeaker speaker = new WindowsSpeaker(true);
        try {
            speaker.speak("Screen reader test", true);
            String ready = speaker.replies().poll(30, TimeUnit.SECONDS);
            assumeTrue(ready != null && ready.startsWith("ready"), "the speech synthesizer did not start: " + ready);
            assertEquals("ok", speaker.replies().poll(10, TimeUnit.SECONDS));

            speaker.speak("Second line\nwith a break, and an accent: café", false);
            speaker.setRate(5);
            speaker.stop();
            assertEquals("ok", speaker.replies().poll(10, TimeUnit.SECONDS));
            assertEquals("ok", speaker.replies().poll(10, TimeUnit.SECONDS));
            assertEquals("ok", speaker.replies().poll(10, TimeUnit.SECONDS));
        } finally {
            speaker.close();
        }
    }

    @Test
    void speakingAfterCloseDoesNothing() {
        WindowsSpeaker speaker = new WindowsSpeaker(true);
        speaker.close();
        speaker.speak("late", true);
        assertTrue(speaker.replies().isEmpty());
    }
}
