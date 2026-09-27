package me.piitex.engine.graphics;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A frame-by-frame animation: a list of {@link TextureRegion}s, how long each is shown, and how it repeats. It is
 * only the description, and is immutable, so one animation can be shared by every character that uses it. An
 * {@link AnimationPlayer} plays it for one of them.
 * <pre>{@code
 * List<TextureRegion> frames = sheet.split(32, 32);
 * Animation walk = Animation.of(frames.subList(0, 6), 0.1f, Animation.PlayMode.LOOP)
 *         .withEvent(2, "footstep")
 *         .withEvent(5, "footstep");
 * }</pre>
 * Each frame can carry an <b>event</b>, a name the player reports whenever that frame starts, for things that must
 * line up with the picture: a footstep sound, the moment a sword swing hits.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Animation {

    /**
     * How an animation carries on after its last frame.
     */
    public enum PlayMode {
        /**
         * Stops on the last frame.
         */
        ONCE,
        /**
         * Starts again from the first frame.
         */
        LOOP,
        /**
         * Plays backwards to the first frame, then forwards again, and so on: 0 1 2 3 2 1 0 1 ...
         */
        PING_PONG
    }

    /**
     * One frame: the picture, how many seconds it is shown, and the event it reports when it starts, or null.
     */
    public record Frame(TextureRegion region, float duration, String event) {
        public Frame {
            if (region == null) throw new IllegalArgumentException("region must not be null");
            if (!(duration > 0f) || !Float.isFinite(duration)) throw new IllegalArgumentException("duration must be above 0: " + duration);
        }

        public Frame(TextureRegion region, float duration) {
            this(region, duration, null);
        }
    }

    private final List<Frame> frames;
    private final PlayMode mode;
    private final int[] sequence; // frame indices in the order one pass shows them
    private final float duration;

    public Animation(List<Frame> frames, PlayMode mode) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("an animation needs at least one frame");
        this.frames = List.copyOf(frames);
        this.mode = mode == null ? PlayMode.LOOP : mode;
        this.sequence = sequence(this.frames.size(), this.mode);
        float total = 0f;
        for (int index : sequence) total += this.frames.get(index).duration();
        this.duration = total;
    }

    /**
     * An animation showing each of {@code regions} for {@code frameDuration} seconds.
     */
    public static Animation of(List<TextureRegion> regions, float frameDuration, PlayMode mode) {
        List<Frame> frames = new ArrayList<>(regions.size());
        for (TextureRegion region : regions) frames.add(new Frame(region, frameDuration));
        return new Animation(frames, mode);
    }

    public static Animation of(float frameDuration, PlayMode mode, TextureRegion... regions) {
        return of(Arrays.asList(regions), frameDuration, mode);
    }

    private static int[] sequence(int count, PlayMode mode) {
        if (mode != PlayMode.PING_PONG || count < 3) {
            int[] forward = new int[count];
            for (int i = 0; i < count; i++) forward[i] = i;
            return forward;
        }
        int[] there = new int[count * 2 - 2]; // there and back, without showing either end twice in a row
        for (int i = 0; i < count; i++) there[i] = i;
        for (int i = 1; i < count - 1; i++) there[count - 1 + i] = count - 1 - i;
        return there;
    }

    /**
     * The same frames with another play mode.
     */
    public Animation withMode(PlayMode mode) {
        return new Animation(frames, mode);
    }

    /**
     * The same animation with frame {@code frameIndex} reporting {@code event} when it starts; null removes the
     * frame's event.
     */
    public Animation withEvent(int frameIndex, String event) {
        List<Frame> changed = new ArrayList<>(frames);
        Frame frame = frames.get(frameIndex);
        changed.set(frameIndex, new Frame(frame.region(), frame.duration(), event));
        return new Animation(changed, mode);
    }

    /**
     * The same animation with its frames in the opposite order.
     */
    public Animation reversed() {
        List<Frame> backwards = new ArrayList<>(frames);
        java.util.Collections.reverse(backwards);
        return new Animation(backwards, mode);
    }

    public List<Frame> getFrames() {
        return frames;
    }

    public Frame getFrame(int index) {
        return frames.get(index);
    }

    public int getFrameCount() {
        return frames.size();
    }

    public PlayMode getMode() {
        return mode;
    }

    /**
     * Seconds for one pass: every frame once, or for {@link PlayMode#PING_PONG} there and back.
     */
    public float getDuration() {
        return duration;
    }

    /**
     * The picture shown {@code time} seconds after the animation started, without an {@link AnimationPlayer}. Handy
     * for things that all animate in step, such as every coin in a level: {@code anim.getRegionAt(GameTime.getTime())}.
     * Takes a double so it stays exact on a clock that has run for hours.
     */
    public TextureRegion getRegionAt(double time) {
        return frames.get(frameIndexAt(time)).region();
    }

    /**
     * The index of the frame shown {@code time} seconds after the animation started.
     */
    public int frameIndexAt(double time) {
        if (!(time > 0)) return sequence[0];
        if (time >= duration) {
            if (mode == PlayMode.ONCE) return sequence[sequence.length - 1];
            time %= duration;
        }
        for (int step = 0; step < sequence.length; step++) {
            time -= frames.get(sequence[step]).duration();
            if (time < 0) return sequence[step];
        }
        return sequence[sequence.length - 1];
    }

    int sequenceLength() {
        return sequence.length;
    }

    int sequenceFrame(int step) {
        return sequence[step];
    }
}
