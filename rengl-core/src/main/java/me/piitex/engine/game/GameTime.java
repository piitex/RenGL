package me.piitex.engine.game;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * The game clock, kept separate from the real clock the UI runs on.
 * <ul>
 *   <li><b>Real time</b> drives elements, transitions, {@link me.piitex.engine.scheduler.Scheduler} timers and
 *       everything else in the UI. It never pauses and ignores the time scale, so menus keep animating while the
 *       game is paused.</li>
 *   <li><b>Game time</b> drives {@link Scene}s. It is real time multiplied by {@link #setTimeScale}, and stands
 *       still while {@link #setPaused} is on.</li>
 * </ul>
 * Game time is consumed in two ways. {@link Scene#update} runs once per frame with the scaled frame delta
 * ({@link #getDelta()}), which suits visuals and anything that should be smooth at any frame rate.
 * {@link Scene#fixedUpdate} runs at a fixed {@link #setTickRate tick rate} (60 per second by default) whatever the
 * frame rate is, which suits physics, movement and anything that must play out the same way on every machine.
 * A frame runs as many ticks as the game time it covers holds, possibly none; {@link #getAlpha()} tells how far the
 * clock is between the last tick and the next, for drawing moving things smoothly between their tick positions.
 * <p>
 * Settings can be changed from any thread and take effect on the next frame. The clocks are advanced by the engine
 * on the render thread and can be read from any thread.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class GameTime {
    private static volatile int tickRate = 60;
    private static volatile float timeScale = 1f;
    private static volatile boolean paused;
    private static volatile int maxTicksPerFrame = 10;

    private static volatile double time;
    private static volatile double realTime;
    private static volatile float delta;
    private static volatile float realDelta;
    private static volatile long ticks;
    private static volatile float alpha;
    private static volatile long frames;
    private static volatile boolean inFixedStep;
    private static double accumulator; // render thread only

    private GameTime() {
    }

    /**
     * How many {@link Scene#fixedUpdate} steps run per second of game time, from 1 to 1000. 60 by default. A higher
     * rate is more precise and costs more.
     */
    public static void setTickRate(int ticksPerSecond) {
        if (ticksPerSecond < 1 || ticksPerSecond > 1000) {
            throw new IllegalArgumentException("tick rate must be between 1 and 1000: " + ticksPerSecond);
        }
        tickRate = ticksPerSecond;
    }

    public static int getTickRate() {
        return tickRate;
    }

    /**
     * The length of one fixed step in seconds of game time: {@code 1 / tickRate}. This is the value
     * {@link Scene#fixedUpdate} receives.
     */
    public static float getFixedDelta() {
        return 1f / tickRate;
    }

    /**
     * How fast game time runs compared to real time: 1 is normal, 0.5 half speed (slow motion), 2 double speed. 0
     * stops game time much like {@link #setPaused}. The tick rate stays the same in game time, so at 0.5 there are
     * half as many ticks per real second, each still {@link #getFixedDelta()} long.
     */
    public static void setTimeScale(float scale) {
        if (!(scale >= 0f) || Float.isInfinite(scale)) {
            throw new IllegalArgumentException("time scale must be zero or positive: " + scale);
        }
        timeScale = scale;
    }

    public static float getTimeScale() {
        return timeScale;
    }

    /**
     * Stops or restarts game time. While paused no fixed steps run and {@link Scene#update} receives a delta of 0.
     * The UI, and {@link me.piitex.engine.scheduler.Scheduler} timers, keep running on real time.
     * <p>
     * This pauses every scene at once. To pause the game under a pause menu, pushing the menu as a {@link Scene} is
     * usually enough, since a scene pauses the ones below it (see {@link Scene#setPausesBelow}).
     */
    public static void setPaused(boolean pause) {
        paused = pause;
    }

    public static boolean isPaused() {
        return paused;
    }

    /**
     * The most fixed steps one frame may run, 10 by default. When the game falls further behind than that (frames
     * too slow for the tick rate), the extra time is dropped and the game runs slower than real time instead of
     * trying to catch up, which would only make each frame slower still.
     */
    public static void setMaxTicksPerFrame(int max) {
        if (max < 1) throw new IllegalArgumentException("max ticks per frame must be at least 1: " + max);
        maxTicksPerFrame = max;
    }

    public static int getMaxTicksPerFrame() {
        return maxTicksPerFrame;
    }

    /**
     * Seconds of game time since the engine started (or since {@link #reset}). Scaled, and stands still while paused.
     */
    public static double getTime() {
        return time;
    }

    /**
     * Seconds of real time since the engine started (or since {@link #reset}). Each frame counts for at most 0.25
     * seconds, so a debugger pause or long stall doesn't show up in full.
     */
    public static double getRealTime() {
        return realTime;
    }

    /**
     * Game time covered by the current frame, in seconds: the real frame time times the time scale, or 0 while
     * paused. This is the delta {@link Scene#update} receives.
     */
    public static float getDelta() {
        return delta;
    }

    /**
     * Real time covered by the current frame, in seconds, capped at 0.25. The same delta elements receive.
     */
    public static float getRealDelta() {
        return realDelta;
    }

    /**
     * How many fixed steps have completed. Inside {@link Scene#fixedUpdate} this is the number of the step being
     * run, counting from 0.
     */
    public static long getTicks() {
        return ticks;
    }

    /**
     * How many frames have started since the engine started (or since {@link #reset}), paused or not. It goes up by
     * one at the start of each frame, just before the frame's fixed steps.
     */
    public static long getFrameCount() {
        return frames;
    }

    /**
     * True while a fixed step runs, that is inside {@link Scene#fixedUpdate}. Lets code called from both kinds of
     * update, such as {@link me.piitex.engine.graphics.DebugDraw}, tell which one it is in.
     */
    public static boolean isInFixedStep() {
        return inFixedStep;
    }

    /**
     * How far game time is past the last fixed step, as a fraction of a step from 0 up to 1. To draw something that
     * moves in {@link Scene#fixedUpdate} without stutter, remember its position before and after each step and draw
     * it at {@code previous + (current - previous) * alpha}. {@link Scene#render} is given this value.
     */
    public static float getAlpha() {
        return alpha;
    }

    /**
     * Sets every clock back to 0 (settings such as the tick rate and time scale are kept). The engine calls this
     * when it starts. Call it on the render thread, for example when starting a new game.
     */
    public static void reset() {
        time = 0;
        realTime = 0;
        delta = 0f;
        realDelta = 0f;
        ticks = 0;
        alpha = 0f;
        frames = 0;
        accumulator = 0;
    }

    /**
     * Render thread, called by the engine once per frame. Advances the clocks by one frame of {@code frameDelta}
     * real seconds and runs {@code fixedStep} once for each fixed step that frame covers.
     */
    public static void advance(float frameDelta, Runnable fixedStep) {
        double step = 1.0 / tickRate;
        float scaled = paused ? 0f : frameDelta * timeScale;

        frames++;
        realDelta = frameDelta;
        delta = scaled;
        realTime += frameDelta;
        time += scaled;

        accumulator += scaled;
        int due = (int) (accumulator / step);
        int max = maxTicksPerFrame;
        if (due > max) {
            accumulator -= (due - max) * step; // too far behind: drop the backlog rather than chase it
            due = max;
        }
        for (int i = 0; i < due; i++) {
            accumulator -= step;
            inFixedStep = true;
            try {
                fixedStep.run();
            } finally {
                inFixedStep = false;
            }
            ticks++;
        }
        alpha = (float) Math.max(0.0, Math.min(1.0, accumulator / step));
    }
}
