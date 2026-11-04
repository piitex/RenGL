package me.piitex.engine.audio;

import me.piitex.engine.SoundOutput;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Plays the core's own sounds, such as the RenGL splash's, through {@link Audio}. The core finds it through
 * {@link java.util.ServiceLoader}; applications have no need for it. The sounds play on {@link AudioChannel#UI}, so the
 * master volume, that channel's volume and {@link Audio#setMuted mute} all apply to them.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class AudioSoundOutput implements SoundOutput {
    /**
     * How often a released sound checks whether its last play has finished, in milliseconds.
     */
    private static final long RELEASE_POLL_MILLIS = 100;

    @Override
    public boolean isAvailable() {
        return Audio.isAvailable();
    }

    @Override
    public Clip load(String name, short[] samples, int channels, int sampleRate) {
        return new SoundClip(Sound.fromSamples(name, samples, channels, sampleRate).channel(AudioChannel.UI));
    }

    private record SoundClip(Sound sound) implements Clip {
        @Override
        public void play(float volume) {
            sound.play(volume);
        }

        /**
         * Closing a sound stops it, so a sound still playing is closed by a virtual thread once it has finished. If the
         * device was shut down meanwhile the sound went with it and there is nothing to close; closing would only open
         * the device again.
         */
        @Override
        public void release() {
            if (finishedOrGone()) return;
            Thread.ofVirtual().name("RenGL-Sound-Release").start(() -> {
                try {
                    while (!finishedOrGone()) Thread.sleep(RELEASE_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        /**
         * Closes the sound and returns true when nothing is playing it any more, or returns true without closing when
         * the device is no longer open.
         */
        private boolean finishedOrGone() {
            synchronized (Audio.LOCK) {
                if (!Audio.isOpen()) return true;
                if (Audio.countPlaying(sound) > 0) return false;
                sound.close();
                return true;
            }
        }
    }
}
