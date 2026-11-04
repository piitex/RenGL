package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;

/**
 * A splash screen: something the engine draws over the first window while the application starts. The engine plays the
 * {@link RenGLSplash RenGL splash} unless the application chooses otherwise with {@link WindowOptions#setSplash} (its
 * own splash) or {@link WindowOptions#setSplashEnabled} (none).
 * <p>
 * Every method is called on the render thread. Each frame, while the splash is active, the engine:
 * <ol>
 *   <li>calls {@link #update(float)}, then {@link #isFinished()}: a finished splash is {@linkplain #dispose() disposed}
 *       and never called again;</li>
 *   <li>asks {@link #isHoldingApp()}. While it is true the application is held back: its containers and scenes are
 *       neither updated nor drawn, and the input meant for them is dropped, so the application's first frame is the
 *       first one after the splash lets go. The window is cleared to {@link #getClearColor()} instead of the
 *       application's background;</li>
 *   <li>draws the application, unless it is held, and then calls {@link #render}, so the splash is on top. A splash
 *       that stops holding the application but is not finished yet is drawn over the running application, which is
 *       how a fade-out works.</li>
 * </ol>
 * A splash that throws from any of these is logged and dropped, and the application carries on. One that never
 * finishes while it holds the application keeps the application held, which is what a splash that waits for a long
 * load wants; nothing else ends it.
 * <p>
 * An instance plays once. For example, a splash that holds the application until a background load has finished,
 * then fades for a third of a second:
 * <pre>{@code
 * class LoadingSplash implements Splash {
 *     private final AtomicBoolean loaded = new AtomicBoolean();
 *     private float fade; // seconds since loading finished
 *
 *     public void update(float delta) {
 *         if (loaded.get()) fade += delta;
 *     }
 *
 *     public void render(Renderer renderer, int width, int height) {
 *         renderer.pushAlpha(1f - Math.min(fade / 0.33f, 1f));
 *         renderer.drawQuad(0, 0, width, height, Color.BLACK, 0);
 *         renderer.popAlpha();
 *     }
 *
 *     public boolean isHoldingApp() {
 *         return !loaded.get();
 *     }
 *
 *     public boolean isFinished() {
 *         return fade >= 0.33f;
 *     }
 * }
 *
 * options.setSplash(new LoadingSplash());
 * }</pre>
 */
public interface Splash {
    /**
     * Plays {@code splashes} one after another as a single splash, such as a publisher's, a framework's and then the
     * RenGL splash itself:
     * <pre>{@code
     * options.setSplash(Splash.sequence(new PublisherSplash(), new FrameworkSplash(), new RenGLSplash()));
     * }</pre>
     * Each splash gets its own {@link #onStart}, runs until {@link #isFinished()}, and is disposed before the next
     * begins. The application is held through every splash but the last, so it never shows between two; the last one
     * decides for itself, so it can fade out over the application. The window is cleared to the colour of the splash
     * playing. A splash that throws is logged and skipped, and the others still play. Splashes that never got to play
     * because the window closed first are disposed too. A splash plays once, so list each instance only once.
     * <p>
     * RenGL's own splash is part of the sequence only if it is listed. With none listed, the sequence is over at once.
     */
    static Splash sequence(Splash... splashes) {
        return new SplashSequence(splashes);
    }

    /**
     * Called once, before the first frame, with the window the splash plays over. Its {@link Window#getRenderer()} says
     * which graphics API draws the splash. Does nothing unless overridden.
     */
    default void onStart(Window window) {
    }

    /**
     * Advances the splash by {@code delta} seconds, the time since the previous frame. The first frame, which compiles
     * shaders, can be long; a splash that is a function of elapsed time may want to cap what it adds.
     */
    void update(float delta);

    /**
     * Draws the splash over a window of {@code width} x {@code height} logical pixels, with the origin at the top
     * left. Every {@code push} on the renderer (alpha, scale, clip) must be matched by its {@code pop} before this
     * returns.
     */
    void render(Renderer renderer, int width, int height);

    /**
     * Whether the application is held back this frame. See the class description for what that means.
     */
    boolean isHoldingApp();

    /**
     * Whether the splash is over. The engine disposes of it and stops calling it as soon as this is true.
     */
    boolean isFinished();

    /**
     * The colour the window is cleared to while {@link #isHoldingApp()} is true, so no frame flashes the
     * application's own background. Black unless overridden.
     */
    default Color getClearColor() {
        return Color.BLACK;
    }

    /**
     * Frees what the splash holds, such as images and fonts. Called once, when the splash finishes or the window
     * closes first. Does nothing unless overridden.
     */
    default void dispose() {
    }
}
