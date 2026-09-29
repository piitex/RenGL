package me.piitex.engine.audio;

import me.piitex.engine.utils.flags.GeneratedClass;

import static org.lwjgl.openal.AL10.*;

/**
 * The listener is where the player "is" in the world: positioned playbacks are heard relative to its position and
 * direction. There is exactly one, reached with {@link Audio#listener()}. Move it every frame to follow the camera or
 * player:
 * <pre>{@code
 * Audio.listener().setPosition(player.x, player.y, player.z);
 * Audio.listener().setOrientation(forwardX, forwardY, forwardZ, 0, 1, 0);
 * }</pre>
 * By default it sits at the origin facing down the negative z axis with y up, the OpenGL camera convention: +x is
 * to the right, +y is up, and things in front of the listener have negative z. For a 2D game keep everything on the
 * plane {@code z = 0}, and left and right then come from x alone; {@link Audio#hearIn2D} sets the listener up for
 * that.
 * <p>
 * All methods return the listener so they chain, and are safe from any thread. Values set before audio starts are
 * applied when it does.
 */
public final class AudioListener {
    private float x, y, z;
    private float vx, vy, vz;
    private final float[] orientation = {0f, 0f, -1f, 0f, 1f, 0f}; // forward, then up
    private float height; // how far in front of the plane z = 0 a 2D listener hovers, set by Audio.hearIn2D

    AudioListener() {
    }

    /**
     * Moves the listener.
     */
    public AudioListener setPosition(float x, float y, float z) {
        synchronized (Audio.LOCK) {
            this.x = x;
            this.y = y;
            this.z = z;
            if (Audio.isReady()) alListener3f(AL_POSITION, x, y, z);
        }
        return this;
    }

    /**
     * {@link #setPosition(float, float, float)} for a 2D game: moves over (x, y) on the plane {@code z = 0}. After
     * {@link Audio#hearIn2D} it hovers a little in front of the plane, so sounds to either side don't come all from one
     * speaker; otherwise it is on the plane.
     */
    public AudioListener setPosition(float x, float y) {
        synchronized (Audio.LOCK) {
            return setPosition(x, y, -height);
        }
    }

    /**
     * Moves a 2D listener to hover {@code height} in front of the plane z = 0, keeping x and y. Holds {@link Audio#LOCK}.
     */
    void setHeight(float height) {
        this.height = height;
        setPosition(x, y, -height);
    }

    /**
     * Sets how fast the listener is moving, in world units per second, for the Doppler effect.
     */
    public AudioListener setVelocity(float x, float y, float z) {
        synchronized (Audio.LOCK) {
            vx = x;
            vy = y;
            vz = z;
            if (Audio.isReady()) alListener3f(AL_VELOCITY, x, y, z);
        }
        return this;
    }

    /**
     * Turns the listener. {@code forward} is the direction it faces and {@code up} is the direction of its head. They
     * must not be parallel.
     */
    public AudioListener setOrientation(float forwardX, float forwardY, float forwardZ, float upX, float upY, float upZ) {
        synchronized (Audio.LOCK) {
            orientation[0] = forwardX;
            orientation[1] = forwardY;
            orientation[2] = forwardZ;
            orientation[3] = upX;
            orientation[4] = upY;
            orientation[5] = upZ;
            if (Audio.isReady()) alListenerfv(AL_ORIENTATION, orientation);
        }
        return this;
    }

    public float getX() {
        synchronized (Audio.LOCK) {
            return x;
        }
    }

    public float getY() {
        synchronized (Audio.LOCK) {
            return y;
        }
    }

    public float getZ() {
        synchronized (Audio.LOCK) {
            return z;
        }
    }

    /**
     * Pushes the stored state to OpenAL once the device is open. Holds {@link Audio#LOCK}.
     */
    void apply() {
        alListener3f(AL_POSITION, x, y, z);
        alListener3f(AL_VELOCITY, vx, vy, vz);
        alListenerfv(AL_ORIENTATION, orientation);
    }
}
