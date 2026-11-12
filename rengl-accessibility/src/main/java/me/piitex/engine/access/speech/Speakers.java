package me.piitex.engine.access.speech;

import java.util.List;
import java.util.Locale;

/**
 * The {@link Speaker}s that come with the module.
 */
public final class Speakers {
    private Speakers() {
    }

    /**
     * The system's own voice: SAPI on Windows, {@code say} on macOS, {@code spd-say} or {@code espeak-ng} on Linux. If the
     * system has none, the speaker is silent and logs why, once.
     */
    public static Speaker system() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new WindowsSpeaker(false);
        if (os.contains("mac")) {
            return new CommandSpeaker("say",
                    (text, rate) -> List.of("say", "-r", String.valueOf(175 + rate * 15), text), List.of());
        }
        return linux();
    }

    private static Speaker linux() {
        if (CommandSpeaker.exists("spd-say")) {
            return new CommandSpeaker("spd-say",
                    (text, rate) -> List.of("spd-say", "-w", "-r", String.valueOf(rate * 10), "--", text), List.of("spd-say", "-C"));
        }
        String espeak = CommandSpeaker.exists("espeak-ng") ? "espeak-ng" : "espeak";
        return new CommandSpeaker(espeak,
                (text, rate) -> List.of(espeak, "-s", String.valueOf(175 + rate * 15), "--", text), List.of());
    }

    /**
     * A speaker that says nothing, for a build with speech turned off.
     */
    public static Speaker none() {
        return new Speaker() {
            @Override
            public void speak(String text, boolean interrupt) {
            }

            @Override
            public void stop() {
            }

            @Override
            public void setRate(int rate) {
            }

            @Override
            public void close() {
            }
        };
    }
}
