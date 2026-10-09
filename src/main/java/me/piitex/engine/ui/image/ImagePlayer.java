package me.piitex.engine.ui.image;

import org.jetbrains.skia.Image;

import java.util.ArrayList;
import java.util.List;

/**
 * The playback controls for an {@link AnimatedImage}, which is what makes a GIF behave like a small video:
 * play, pause, stop, seek, loop, speed, and callbacks when it loops or ends.
 * <pre>{@code
 * ImagePlayer player = new ImagePlayer(AnimatedImage.load("assets/intro.gif"));
 * player.setLoopCount(1);                   // play through once
 * player.onFinish(() -> startGame());
 *
 * // every frame:
 * player.update(delta);
 * renderer.drawImage(player.getFrame(), x, y, w, h, 0f, 1f);
 * }</pre>
 * An {@link me.piitex.engine.ui.overlays.ImageOverlay} showing a GIF already owns one of these (see
 * {@code ImageOverlay.getPlayer()}), so code that only wants a GIF on screen never creates one by hand.
 * <p>
 * It runs on whatever time it is given. Pass a scene's {@code delta} to follow game time, so a GIF slows down
 * with the time scale and holds still while the game is paused. A new player is already playing, as a GIF does
 * when a page loads. Callbacks run inside {@link #update}. Not thread-safe; use it on the render thread.
 */
public final class ImagePlayer {
    private final AnimatedImage image;
    private final List<Runnable> finishListeners = new ArrayList<>();
    private final List<Runnable> loopListeners = new ArrayList<>();

    private double position;   // seconds into the current pass
    private int loopsLeft;     // passes still to start after this one; < 0 = forever
    private int loopCount;     // passes in total, 0 = forever
    private boolean playing = true;
    private boolean finished;
    private float speed = 1f;

    /**
     * Plays {@code image} as many times as the file asks, which for most GIFs is forever.
     */
    public ImagePlayer(AnimatedImage image) {
        if (image == null) throw new IllegalArgumentException("image must not be null");
        this.image = image;
        setLoopCount(image.getLoopCount());
    }

    public AnimatedImage getImage() {
        return image;
    }

    /**
     * Moves playback on by {@code delta} seconds, times the {@link #setSpeed speed}. Does nothing while paused or
     * finished.
     */
    public void update(float delta) {
        if (!playing || finished || !(delta > 0f) || !Float.isFinite(delta)) return;
        position += delta * speed;
        float duration = image.getDuration();
        if (position < duration) return;

        // Whole passes may have gone by in one long frame; count them rather than loop over each.
        int passes = (int) Math.min(Integer.MAX_VALUE, (long) Math.floor(position / duration));
        if (loopsLeft >= 0 && passes > loopsLeft) {
            position = duration;
            loopsLeft = 0;
            finished = true;
            playing = false;
            for (Runnable listener : List.copyOf(finishListeners)) listener.run();
            return;
        }
        position -= passes * (double) duration;
        if (loopsLeft > 0) loopsLeft -= passes;
        for (Runnable listener : List.copyOf(loopListeners)) listener.run();
    }

    /**
     * Starts or resumes playing. After the end of a play-once animation this starts it again from the first
     * frame, as pressing play on a finished video does.
     */
    public void play() {
        if (finished) rewind();
        playing = true;
    }

    /**
     * Holds on the current frame; {@link #play()} carries on from there.
     */
    public void pause() {
        playing = false;
    }

    /**
     * Pauses and goes back to the first frame.
     */
    public void stop() {
        playing = false;
        rewind();
    }

    /**
     * Back to the first frame of a fresh play, leaving it playing or paused as it was.
     */
    public void rewind() {
        position = 0;
        finished = false;
        loopsLeft = loopCount == 0 ? -1 : loopCount - 1;
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * True once an animation that plays a limited number of times has shown its last frame. A forever-looping
     * one never finishes.
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Jumps to {@code seconds} into the current pass, clamped to the animation's length. Seeking does not start or
     * stop playback, but it does clear a finished state when it moves back from the end.
     */
    public void seek(double seconds) {
        double duration = image.getDuration();
        position = Math.clamp(Double.isNaN(seconds) ? 0 : seconds, 0, duration);
        if (finished && position < duration) {
            finished = false;
            loopsLeft = 0;
        }
    }

    /**
     * Jumps to the start of frame {@code index}.
     */
    public void seekFrame(int index) {
        if (index < 0 || index >= image.getFrameCount()) {
            throw new IndexOutOfBoundsException("frame " + index + " of " + image.getFrameCount());
        }
        seek(frameStartSeconds(index));
    }

    private double frameStartSeconds(int index) {
        double t = 0;
        for (int i = 0; i < index; i++) t += image.getFrameDuration(i);
        return t;
    }

    /**
     * Seconds into the current pass.
     */
    public double getPosition() {
        return position;
    }

    /**
     * Seconds one pass takes at normal speed. The same as {@link AnimatedImage#getDuration()}.
     */
    public float getDuration() {
        return image.getDuration();
    }

    /**
     * How far through the current pass playback is, 0 to 1.
     */
    public float getProgress() {
        return (float) (position / image.getDuration());
    }

    public void setProgress(float progress) {
        seek(progress * (double) image.getDuration());
    }

    /**
     * The index of the frame showing now.
     */
    public int getFrameIndex() {
        return image.frameAt(position);
    }

    /**
     * The picture showing now. See {@link AnimatedImage#getFrame} for how long it stays valid: draw it, don't keep
     * it.
     */
    public Image getFrame() {
        return image.getFrame(getFrameIndex());
    }

    /**
     * How many times to play in total, counting the first: 1 plays once, 3 plays three times, and 0 (or less)
     * plays forever. The default is whatever the file asks for. The count starts again from the current pass, so
     * calling this partway through gives that many passes from now.
     */
    public void setLoopCount(int count) {
        this.loopCount = Math.max(0, count);
        if (!finished) loopsLeft = loopCount == 0 ? -1 : loopCount - 1;
    }

    public int getLoopCount() {
        return loopCount;
    }

    /**
     * Shorthand for {@link #setLoopCount}: true loops forever, false plays once.
     */
    public void setLooping(boolean looping) {
        setLoopCount(looping ? 0 : 1);
    }

    public boolean isLooping() {
        return loopCount == 0;
    }

    public float getSpeed() {
        return speed;
    }

    /**
     * How fast it plays, 1 by default: 2 plays twice as fast, 0.5 at half speed.
     */
    public void setSpeed(float speed) {
        if (!(speed >= 0f) || !Float.isFinite(speed)) throw new IllegalArgumentException("speed must be 0 or more: " + speed);
        this.speed = speed;
    }

    /**
     * Calls {@code listener} when a limited-loop animation ends. It does not fire for one that loops forever.
     */
    public ImagePlayer onFinish(Runnable listener) {
        finishListeners.add(listener);
        return this;
    }

    /**
     * Calls {@code listener} each time playback wraps from the last frame back to the first.
     */
    public ImagePlayer onLoop(Runnable listener) {
        loopListeners.add(listener);
        return this;
    }
}
