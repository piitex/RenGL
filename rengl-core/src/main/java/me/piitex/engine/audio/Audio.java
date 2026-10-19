package me.piitex.engine.audio;

import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTReopenDevice;

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
    // The attenuation each positioned playback starts with.
    private static float defaultReferenceDistance = 1f, defaultMaxDistance = Float.MAX_VALUE, defaultRolloff = 1f;

    private static Playback music;
    private static String musicPath;

    // The output device asked for with setDevice, or null for the system default, and the one actually open.
    private static String preferredDevice, openDevice;
    private static ALCCapabilities alcCaps;
    private static String lastDefaultDevice; // the system default as last seen, to notice it changing
    private static long lastDeviceCheck;

    // Reverb: one effect in one auxiliary slot, shared by every playback that sends to it. Zero until first used.
    private static Reverb reverb;
    private static int reverbEffect, reverbSlot;

    private Audio() {

    }

    /**
     * Plays a sound effect once on {@link AudioChannel#SFX}. The file is loaded on first use and cached.
     */
    public static Playback play(String path) {
        return sound(path).play();
    }

    /**
     * Plays audio you produce yourself, such as the soundtrack of a video, on {@code channel}. The stream is read on
     * the audio thread as it plays and closed when the playback ends. Use {@link Playback#getPlayedSeconds()} on the
     * result as a clock.
     */
    public static Playback playStream(PcmStream stream, AudioChannel channel) {
        if (!ensureInit()) {
            stream.close();
            return Playback.silent(channel);
        }
        return Playback.start(new Playback.Spec(stream, channel, 0, stream.channels(), stream, 1f, 1f, false, 0, 0, null));
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
     * Sets up positioned sounds for a 2D game, in one call: a sound played at (x, y) is at full volume within
     * {@code fullVolumeDistance} of the listener, fades evenly to silence at {@code silentDistance}, and is heard
     * from the left or right by how far it is to either side. Then move the listener with the camera each frame and
     * play sounds where they happen:
     * <pre>{@code
     * Audio.hearIn2D(300f, 1500f);                                  // once, in world units
     * Audio.listener().setPosition(camera.getX(), camera.getY());   // each frame
     * explosion.playAt(x, y);
     * }</pre>
     * The listener hovers half the silent distance in front of the plane, facing it with y down the screen, so a
     * sound a little to one side comes a little from that side rather than all from one speaker. A one-shot started
     * beyond {@code silentDistance} is skipped, as if it had played, so far-off sounds cost nothing and don't count
     * towards a sound's {@link Sound#minGap}.
     * <p>
     * It sets the {@linkplain #setDistanceModel distance model} to {@link DistanceModel#LINEAR_CLAMPED}, the
     * {@linkplain #setDefaultAttenuation default attenuation}, and the listener's orientation. Playbacks already
     * playing keep their attenuation.
     *
     * @throws IllegalArgumentException if {@code fullVolumeDistance} is negative or not below {@code silentDistance}
     */
    public static void hearIn2D(float fullVolumeDistance, float silentDistance) {
        if (!(fullVolumeDistance >= 0f) || !(silentDistance > fullVolumeDistance) || Float.isInfinite(silentDistance)) {
            throw new IllegalArgumentException("need 0 <= fullVolumeDistance < silentDistance, got " + fullVolumeDistance + " and " + silentDistance);
        }
        float height = silentDistance / 2f;
        synchronized (LOCK) {
            // Facing the plane from in front of it, with up towards the top of the screen: x is still to the right.
            listener.setOrientation(0f, 0f, 1f, 0f, -1f, 0f);
            listener.setHeight(height);
            setDistanceModel(DistanceModel.LINEAR_CLAMPED);
            // Distances are measured from the hovering listener, so reach the plane's distances along the slant.
            setDefaultAttenuation((float) Math.hypot(fullVolumeDistance, height), (float) Math.hypot(silentDistance, height), 1f);
        }
    }

    /**
     * Sets the {@linkplain Playback#attenuation attenuation} positioned playbacks start with, so it needn't be set on
     * each one. The defaults are 1, unlimited and 1. Playbacks already playing keep theirs.
     */
    public static void setDefaultAttenuation(float referenceDistance, float maxDistance, float rolloff) {
        synchronized (LOCK) {
            defaultReferenceDistance = Math.max(0f, referenceDistance);
            defaultMaxDistance = Math.max(defaultReferenceDistance, maxDistance);
            defaultRolloff = Math.max(0f, rolloff);
        }
    }

    /**
     * {referenceDistance, maxDistance, rolloff} for a new positioned playback. Call with {@link #LOCK} held.
     */
    static float[] defaultAttenuation() {
        return new float[]{defaultReferenceDistance, defaultMaxDistance, defaultRolloff};
    }

    /**
     * Whether a one-shot at (x, y, z) would be silent from the start: the distance model fades linearly to nothing and
     * the point is past the default max distance. Call with {@link #LOCK} held.
     */
    static boolean isOutOfEarshot(float x, float y, float z) {
        if (distanceModel != DistanceModel.LINEAR_CLAMPED && distanceModel != DistanceModel.LINEAR) return false;
        if (defaultMaxDistance == Float.MAX_VALUE || !(defaultRolloff >= 1f)) return false;
        float dx = x - listener.getX(), dy = y - listener.getY(), dz = z - listener.getZ();
        return dx * dx + dy * dy + dz * dz >= (double) defaultMaxDistance * defaultMaxDistance;
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
     * Gives every sound in the world a room: the {@link Reverb} is added to each playback in proportion to its own
     * {@linkplain Playback#reverb(float) reverb amount}. By default a positioned playback sends all of its sound to the
     * reverb and a flat or panned one (interface sounds, music) sends none. Pass null to turn it off. Playbacks already
     * playing pick it up at once.
     * <p>
     * Needs the sound system's effects extension (OpenAL Soft has it); without it this does nothing and
     * {@link #isReverbSupported()} is false.
     */
    public static void setReverb(Reverb reverb) {
        synchronized (LOCK) {
            Audio.reverb = reverb;
            if (!ensureInit()) return;
            applyReverb();
        }
    }

    /**
     * Puts the current reverb into the OpenAL effect. Call with {@link #LOCK} held and the device open.
     */
    private static void applyReverb() {
        {
            if (!hasEfx()) return;
            if (reverb == null) {
                if (reverbSlot != 0) EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, EXTEfx.AL_EFFECT_NULL);
            } else {
                if (reverbSlot == 0) {
                    reverbEffect = EXTEfx.alGenEffects();
                    reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
                    EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
                }
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DENSITY, reverb.density);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, reverb.diffusion);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, reverb.gain);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAINHF, reverb.gainHF);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, reverb.decayTime);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_HFRATIO, reverb.decayHFRatio);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, reverb.reflectionsGain);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, reverb.reflectionsDelay);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, reverb.lateGain);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, reverb.lateDelay);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, reverb.airAbsorptionGainHF);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_ROOM_ROLLOFF_FACTOR, reverb.roomRolloff);
                EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_REVERB_DECAY_HFLIMIT, reverb.decayHFLimit ? 1 : 0);
                EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
            }
            for (Playback p : active) p.applyEffects();
        }
    }

    /**
     * The reverb last passed to {@link #setReverb}, or null.
     */
    public static Reverb getReverb() {
        synchronized (LOCK) {
            return reverb;
        }
    }

    /**
     * Whether the sound system has the effects extension that {@link #setReverb} and {@link Playback#occlusion} need.
     */
    public static boolean isReverbSupported() {
        synchronized (LOCK) {
            return ensureInit() && hasEfx();
        }
    }

    static boolean hasEfx() {
        return available && alcCaps != null && alcCaps.ALC_EXT_EFX;
    }

    /**
     * The auxiliary slot the reverb lives in, or 0 when no reverb is set. Call with {@link #LOCK} held.
     */
    static int reverbSlot() {
        return reverb != null && hasEfx() ? reverbSlot : 0;
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
     * The names of the output devices the system offers (speakers, headphones, ...), or an empty list when audio is
     * unavailable or the system cannot list them. Pass one to {@link #setDevice}.
     */
    public static List<String> getDevices() {
        synchronized (LOCK) {
            if (!ensureInit()) return List.of();
            List<String> names = ALUtil.getStringList(NULL, alcCaps.ALC_ENUMERATE_ALL_EXT
                    ? org.lwjgl.openal.ALC11.ALC_ALL_DEVICES_SPECIFIER : ALC_DEVICE_SPECIFIER);
            return names == null ? List.of() : List.copyOf(names);
        }
    }

    /**
     * The name of the device sound comes out of now, or null when audio is unavailable.
     */
    public static String getDevice() {
        synchronized (LOCK) {
            return ensureInit() ? openDevice : null;
        }
    }

    /**
     * Moves all sound to another output device, a name from {@link #getDevices()}, or {@code null} for the system's
     * default. What is playing carries on from where it was on the new device. Nothing changes if the device cannot be
     * opened, and the previous one keeps playing. The choice is remembered: if the chosen device later disappears (an unplugged
     * headset), sound moves to the default device. With {@code null} chosen, sound also follows the system's default device
     * when it changes.
     * <p>
     * Needs a sound system that supports switching devices live (OpenAL Soft, which the engine ships, does). Where it
     * does not, this returns false and the device stays as it was.
     *
     * @return true if the sound now comes out of the requested device
     */
    public static boolean setDevice(String name) {
        synchronized (LOCK) {
            preferredDevice = name;
            if (!ensureInit()) return false;
            return reopen(name);
        }
    }

    private static boolean reopen(String name) {
        if (!alcCaps.ALC_SOFT_reopen_device) return false;
        if (!SOFTReopenDevice.alcReopenDeviceSOFT(device, name, (IntBuffer) null)) {
            log.warn("Could not switch audio to {}", name == null ? "the default device" : name);
            return false;
        }
        openDevice = deviceName();
        log.info("Audio moved to: {}", openDevice);
        return true;
    }

    private static String deviceName() {
        String name = null;
        if (alcCaps.ALC_ENUMERATE_ALL_EXT) {
            name = alcGetString(device, org.lwjgl.openal.ALC11.ALC_ALL_DEVICES_SPECIFIER);
        }
        return name != null ? name : alcGetString(device, ALC_DEVICE_SPECIFIER);
    }

    /**
     * Every second: moves sound to a device that is available if the open one was lost, and follows the system default
     * when that is what was asked for. Called by the audio thread with {@link #LOCK} held.
     */
    private static void watchDevice(long now) {
        if (now - lastDeviceCheck < 1_000_000_000L) return;
        lastDeviceCheck = now;
        ALCCapabilities caps = alcCaps;
        if (!caps.ALC_SOFT_reopen_device) return;
        boolean lost = caps.ALC_EXT_disconnect && alcGetInteger(device, org.lwjgl.openal.EXTDisconnect.ALC_CONNECTED) == 0;
        if (lost) {
            log.warn("The audio device {} was lost", openDevice);
            if (!reopen(preferredDevice)) reopen(null);
        } else if (preferredDevice == null && caps.ALC_ENUMERATE_ALL_EXT) {
            String current = alcGetString(NULL, org.lwjgl.openal.ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
            if (current != null && !current.equals(lastDefaultDevice)) {
                lastDefaultDevice = current;
                reopen(null);
            }
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
            reverbEffect = reverbSlot = 0; // they go with the context
            if (available) {
                alcMakeContextCurrent(NULL);
                alcDestroyContext(context);
                alcCloseDevice(device);
            }
            device = context = NULL;
            available = false;
            initTried = false;
            openDevice = null;
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

    /**
     * How many playbacks of {@code owner} haven't finished. Call with {@link #LOCK} held.
     */
    static int countPlaying(Object owner) {
        int count = 0;
        for (Playback p : active) {
            if (p.owner() == owner && !p.isFinished()) count++;
        }
        return count;
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
                device = preferredDevice == null ? NULL : alcOpenDevice(preferredDevice);
                if (device == NULL && preferredDevice != null) log.warn("Could not open the audio device {}; using the default", preferredDevice);
                if (device == NULL) device = alcOpenDevice((ByteBuffer) null);
                if (device == NULL) throw new IllegalStateException("no audio output device found");
                alcCaps = ALC.createCapabilities(device);
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
                if (reverb != null) applyReverb();
                openDevice = deviceName();
                lastDefaultDevice = alcCaps.ALC_ENUMERATE_ALL_EXT ? alcGetString(NULL, org.lwjgl.openal.ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER) : null;
                lastDeviceCheck = System.nanoTime();
                log.info("Audio ready: {}", openDevice);
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
                if (available) watchDevice(now);
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
