package me.piitex.engine.audio;

import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.openal.AL10.alDistanceModel;
import static org.lwjgl.openal.AL10.alDopplerFactor;
import static org.lwjgl.openal.AL11.alSpeedOfSound;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The entry point for audio, backed by OpenAL. Everything here is static and works with no setup: the audio
 * device opens the first time something plays, and the engine closes it when the window closes.
 * <pre>{@code
 * Audio.play("sfx/click.ogg");                 // one-shot effect
 * Audio.playMusic("music/theme.ogg");          // looping background music, crossfades from the previous track
 * Audio.setVolume(AudioChannel.MUSIC, 0.5f);   // a settings slider
 * }</pre>
 * <h2>Loading</h2>
 * Paths are looked up on disk, then on the classpath. Sounds and tracks loaded through this class are cached by
 * path, so calling {@code play} repeatedly does not decode again. Use {@link #preload} to pay the decode cost up
 * front, or hold a {@link Sound} / {@link Music} yourself for full control.
 * <h2>Volume</h2>
 * The loudness of a playback is its own {@linkplain Playback#volume volume} times its
 * {@linkplain #setVolume(AudioChannel, float) channel volume} times the {@linkplain #setMasterVolume master volume}.
 * <h2>3D audio</h2>
 * {@link #playAt} and {@link Playback#position} place a sound in the world; move the {@link #listener()} with your
 * camera or player. See {@link DistanceModel} for how sounds fade with distance.
 * <h2>Threading</h2>
 * Every method is safe from any thread. Playback callbacks are delivered on the render thread.
 * <h2>No audio device</h2>
 * If OpenAL cannot start (no output device, missing driver) a warning is logged once and audio is switched off:
 * nothing throws, {@link #isAvailable()} returns false and every playback is silent. A game keeps running.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5", reviewed = false)
public final class Audio {
    private static final Logger log = LogManager.getLogger("Audio");

    /**
     * Guards all OpenAL calls, the active list and every {@link Playback}'s state.
     */
    static final Object LOCK = new Object();

    private static final long TICK_MILLIS = 15;

    private static long device = NULL, context = NULL;
    private static boolean initTried, available;
    private static Thread thread;

    private static final List<Playback> active = new ArrayList<>();
    private static final Map<String, Sound> sounds = new ConcurrentHashMap<>();
    private static final Map<String, Music> tracks = new ConcurrentHashMap<>();

    private static float master = 1f;
    private static final float[] channelVolume = {1f, 1f, 1f, 1f};
    private static boolean muted;

    private static final AudioListener listener = new AudioListener();
    private static DistanceModel distanceModel = DistanceModel.INVERSE_CLAMPED;
    private static float dopplerFactor = 1f, speedOfSound = 343.3f;

    private static Playback music;
    private static String musicPath;

    private Audio() {

    }

    /**
     * Plays a sound effect once on {@link AudioChannel#SFX}. The file is loaded on first use and cached.
     */
    public static Playback play(String path) {
        return sound(path).play();
    }

    /**
     * Plays a sound effect once at {@code volume}.
     */
    public static Playback play(String path, float volume) {
        return sound(path).play(volume);
    }

    /**
     * Plays a sound effect on a specific channel, for example {@link AudioChannel#UI}.
     */
    public static Playback play(String path, AudioChannel channel) {
        return sound(path).channel(channel).play();
    }

    /**
     * Plays a sound effect once at a position in the world. See {@link #listener()} and {@link Playback#position}.
     */
    public static Playback playAt(String path, float x, float y, float z) {
        return sound(path).playAt(x, y, z);
    }

    /**
     * {@link #playAt(String, float, float, float)} for a 2D game: plays at (x, y) on the plane {@code z = 0}.
     */
    public static Playback playAt(String path, float x, float y) {
        return sound(path).playAt(x, y);
    }

    /**
     * Switches the background music to {@code path}, looping. Any current music fades out over one second while the
     * new track fades in. Calling it with the track that is already playing does nothing, so it is safe to call
     * on every screen change.
     */
    public static Playback playMusic(String path) {
        return playMusic(path, 1f);
    }

    /**
     * Like {@link #playMusic(String)} with a custom crossfade length. {@code 0} switches instantly.
     */
    public static Playback playMusic(String path, float fadeSeconds) {
        synchronized (LOCK) {
            if (music != null && !music.isFinished() && path.equals(musicPath)) return music;
            if (music != null) music.stop(fadeSeconds);
            Music track = music(path);
            musicPath = path;
            music = track.loop(fadeSeconds);
            return music;
        }
    }

    /**
     * Fades the background music out over one second and stops it.
     */
    public static void stopMusic() {
        stopMusic(1f);
    }

    /**
     * Fades the background music out over {@code fadeSeconds} ({@code 0} stops instantly).
     */
    public static void stopMusic(float fadeSeconds) {
        synchronized (LOCK) {
            if (music != null) music.stop(fadeSeconds);
            music = null;
            musicPath = null;
        }
    }

    /**
     * The playback started by {@link #playMusic}, or null if there is none.
     */
    public static Playback getMusic() {
        synchronized (LOCK) {
            return music != null && !music.isFinished() ? music : null;
        }
    }


    /**
     * Loads (or fetches from the cache) the sound at {@code path}. Do not {@code close()} a sound you got here; use {@link #unload}.
     */
    public static Sound sound(String path) {
        return sounds.computeIfAbsent(path, Sound::load);
    }

    /**
     * Loads (or fetches from the cache) the track at {@code path}, for example to set its
     * {@linkplain Music#loopPoints loop points} before {@link #playMusic}. Release it with {@link #unload}, not {@code close()}.
     */
    public static Music music(String path) {
        return tracks.computeIfAbsent(path, Music::load);
    }

    /**
     * Decodes the given sound effects now so the first {@link #play(String)} of each has no delay.
     */
    public static void preload(String... paths) {
        for (String path : paths) sound(path);
    }

    /**
     * Stops and frees a cached sound or track. It is loaded again the next time it is played.
     */
    public static void unload(String path) {
        Sound s = sounds.remove(path);
        if (s != null) s.close();
        Music m = tracks.remove(path);
        if (m != null) m.close();
    }


    /**
     * The listener: where the player is in the world, for {@linkplain Playback#position positioned} sounds. Move it
     * with the camera or player.
     */
    public static AudioListener listener() {
        return listener;
    }

    /**
     * Sets how positioned sounds fade with distance. The default is {@link DistanceModel#INVERSE_CLAMPED}.
     */
    public static void setDistanceModel(DistanceModel model) {
        synchronized (LOCK) {
            distanceModel = model;
            if (available) alDistanceModel(model.alValue);
        }
    }

    /**
     * Scales the Doppler effect, the pitch shift of moving sources. 1 is realistic and 0 turns it off. It only acts on
     * sources and a listener that have a velocity, so it does nothing unless you set one. Positions in pixels make
     * speeds huge, so a 2D game usually wants 0, or a {@linkplain #setSpeedOfSound speed of sound} in pixels per second.
     */
    public static void setDopplerFactor(float factor) {
        synchronized (LOCK) {
            dopplerFactor = Math.max(0f, factor);
            if (available) alDopplerFactor(dopplerFactor);
        }
    }

    /**
     * Sets the speed of sound in world units per second, for the Doppler effect. The default, 343.3, suits metres.
     */
    public static void setSpeedOfSound(float unitsPerSecond) {
        synchronized (LOCK) {
            speedOfSound = Math.max(0.001f, unitsPerSecond);
            if (available) alSpeedOfSound(speedOfSound);
        }
    }

    /**
     * Sets the volume of one channel, 0 to 1. Applies to playbacks already running.
     */
    public static void setVolume(AudioChannel channel, float volume) {
        synchronized (LOCK) {
            channelVolume[channel.ordinal()] = Math.max(0f, volume);
            refreshGains();
        }
    }

    public static float getVolume(AudioChannel channel) {
        synchronized (LOCK) {
            return channelVolume[channel.ordinal()];
        }
    }

    /**
     * Sets the volume of everything, 0 to 1.
     */
    public static void setMasterVolume(float volume) {
        synchronized (LOCK) {
            master = Math.max(0f, volume);
            refreshGains();
        }
    }

    public static float getMasterVolume() {
        synchronized (LOCK) {
            return master;
        }
    }

    /**
     * Silences everything without changing any volume, and restores it when set back to false.
     */
    public static void setMuted(boolean muted) {
        synchronized (LOCK) {
            Audio.muted = muted;
            refreshGains();
        }
    }

    public static boolean isMuted() {
        synchronized (LOCK) {
            return muted;
        }
    }


    /**
     * Pauses everything currently playing. Use {@link #resumeAll()} to continue.
     */
    public static void pauseAll() {
        synchronized (LOCK) {
            for (Playback p : new ArrayList<>(active)) p.pause();
        }
    }

    public static void resumeAll() {
        synchronized (LOCK) {
            for (Playback p : new ArrayList<>(active)) p.resume();
        }
    }

    /**
     * Stops everything, music included.
     */
    public static void stopAll() {
        synchronized (LOCK) {
            for (Playback p : new ArrayList<>(active)) p.stop();
            music = null;
            musicPath = null;
        }
    }

    /**
     * True if the audio device opened. False means every playback is silent.
     */
    public static boolean isAvailable() {
        return ensureInit();
    }

    /**
     * Stops all audio and closes the device. The engine calls this when the window closes, so you only need it if
     * you use audio without an {@code Engine}. Audio can be used again afterwards.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (!initTried) return;
            for (Playback p : new ArrayList<>(active)) p.stop();
            active.clear();
            music = null;
            musicPath = null;
            for (Sound s : sounds.values()) s.close();
            sounds.clear();
            for (Music m : tracks.values()) m.close();
            tracks.clear();
            Thread t = thread;
            thread = null;
            if (t != null) t.interrupt();
            if (available) {
                alcMakeContextCurrent(NULL);
                alcDestroyContext(context);
                alcCloseDevice(device);
            }
            device = context = NULL;
            available = false;
            initTried = false;
        }
    }


    static float channelGain(AudioChannel channel) {
        return muted ? 0f : master * channelVolume[channel.ordinal()];
    }

    private static void refreshGains() {
        for (Playback p : active) p.applyGain();
    }

    /**
     * True once the device is open. Holds no lock of its own; call with {@link #LOCK} held.
     */
    static boolean isReady() {
        return available;
    }

    static void register(Playback p) {
        active.add(p);
    }

    static void unregister(Playback p) {
        active.remove(p);
    }

    static void stopOwnedBy(Object owner) {
        synchronized (LOCK) {
            for (Playback p : new ArrayList<>(active)) {
                if (p.owner() == owner) p.stop();
            }
        }
    }

    /**
     * Opens the device on first use. Returns whether audio is available.
     */
    static boolean ensureInit() {
        synchronized (LOCK) {
            if (initTried) return available;
            initTried = true;
            try {
                device = alcOpenDevice((ByteBuffer) null);
                if (device == NULL) throw new IllegalStateException("no audio output device found");
                ALCCapabilities alcCaps = ALC.createCapabilities(device);
                context = alcCreateContext(device, (IntBuffer) null);
                if (context == NULL) throw new IllegalStateException("could not create an audio context");
                alcMakeContextCurrent(context);
                AL.createCapabilities(alcCaps);
                available = true;
                listener.apply();
                alDistanceModel(distanceModel.alValue);
                alDopplerFactor(dopplerFactor);
                alSpeedOfSound(speedOfSound);
                thread = new Thread(Audio::run, "Audio-Thread");
                thread.setDaemon(true);
                thread.start();
                log.info("Audio ready: {}", alcGetString(device, ALC_DEVICE_SPECIFIER));
            } catch (Throwable t) {
                log.warn("Audio is disabled: {}", t.getMessage());
                if (context != NULL) alcDestroyContext(context);
                if (device != NULL) alcCloseDevice(device);
                device = context = NULL;
                available = false;
            }
            return available;
        }
    }

    /**
     * Audio thread: advances fades and refills streams.
     */
    private static void run() {
        long last = System.nanoTime();
        Thread self = Thread.currentThread();
        while (!self.isInterrupted()) {
            try {
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            float dt = (now - last) / 1_000_000_000f;
            last = now;
            synchronized (LOCK) {
                if (thread != self) return;
                for (Playback p : new ArrayList<>(active)) {
                    try {
                        p.update(dt);
                    } catch (RuntimeException e) {
                        log.warn("Stopping a playback after an audio error: {}", e.getMessage());
                        p.finish();
                    }
                }
            }
        }
    }
}
