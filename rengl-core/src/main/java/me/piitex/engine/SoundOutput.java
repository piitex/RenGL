package me.piitex.engine;

import org.apache.logging.log4j.LogManager;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * How the core plays the few sounds of its own, such as the {@link RenGLSplash RenGL splash}'s, without depending on the
 * audio module. The core makes the samples itself and hands them over; {@code rengl-audio} supplies the implementation
 * through {@link ServiceLoader}, so with that module on the classpath the sounds play and without it they are skipped.
 * Applications play their own sounds with {@code me.piitex.engine.audio.Audio}, not this.
 */
public interface SoundOutput {
    /**
     * Whether a device is open to play on. The first call may open it, which can take a tenth of a second or more, so
     * call it off the render thread.
     */
    boolean isAvailable();

    /**
     * Uploads a sound so it can start at once later. Call it off the render thread, after {@link #isAvailable()}.
     *
     * @param name       a name for logs
     * @param samples    16-bit samples, left then right when there are two channels; the array is not copied
     * @param channels   1 or 2
     * @param sampleRate samples per second of each channel
     */
    Clip load(String name, short[] samples, int channels, int sampleRate);

    /**
     * A loaded sound.
     */
    interface Clip {
        /**
         * Starts the sound at {@code volume} (1 is as made). Quick enough to call on the render thread.
         */
        void play(float volume);

        /**
         * Frees the sound once every play of it has finished. It cannot be played afterwards.
         */
        void release();
    }

    /**
     * The sound output on the classpath, or null when there is none (the audio module is not there).
     */
    static SoundOutput find() {
        return Lookup.OUTPUT;
    }

    /**
     * Holds the output, looked up once, the first time it is asked for.
     */
    final class Lookup {
        private static final SoundOutput OUTPUT = lookUp();

        private Lookup() {
        }

        private static SoundOutput lookUp() {
            try {
                Iterator<SoundOutput> found = ServiceLoader.load(SoundOutput.class, SoundOutput.class.getClassLoader()).iterator();
                return found.hasNext() ? found.next() : null;
            } catch (ServiceConfigurationError e) {
                LogManager.getLogger("Engine").warn("No sound output: {}", e.getMessage());
                return null;
            }
        }
    }
}
