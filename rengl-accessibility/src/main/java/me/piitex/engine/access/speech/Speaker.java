package me.piitex.engine.access.speech;

/**
 * Something that says text aloud. {@link Speakers#system()} gives the one for this operating system; a game with its
 * own voice-over or a cloud voice implements this instead and hands it to {@link Accessibility#enable}.
 * <p>
 * Every method returns without waiting for the speech, and is safe from any thread.
 */
public interface Speaker extends AutoCloseable {
    /**
     * Says {@code text}.
     *
     * @param interrupt true to cut off whatever is being said first; false to say it after
     */
    void speak(String text, boolean interrupt);

    /**
     * Gets ready to speak without saying anything, so the first word is not held up by start-up work. Returns at once;
     * the default does nothing, for a speaker with nothing to start.
     */
    default void warmUp() {
    }

    /**
     * Stops what is being said and drops anything waiting.
     */
    void stop();

    /**
     * How fast to talk, from -10 (slowest) through 0 (the voice's normal pace) to 10 (fastest). People who use a screen
     * reader every day often listen well above normal.
     */
    void setRate(int rate);

    /**
     * Stops and releases whatever the speaker holds, a helper process for one. It cannot be used afterwards.
     */
    @Override
    void close();
}
