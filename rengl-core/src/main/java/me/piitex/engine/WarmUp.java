package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;

import java.util.function.Consumer;

/**
 * Work to finish while the {@linkplain Splash splash screen} is up, so the application's first frame does not stall on
 * it: loading a vendor's upscaler, building a 3D scene's pipelines, compiling shaders, reading a big file. Add one with
 * {@link WindowOptions#addWarmUp}; the engine runs them one after another on the render thread, and keeps the application
 * held back until every one is done.
 * <p>
 * What the player sees is the splash, so the work is arranged to keep it moving:
 * <ul>
 *   <li>{@link #prepare()} runs on a worker thread from the moment the window opens, alongside the splash. Anything that
 *       needs no graphics context at all, such as opening the audio device or reading files, belongs there: the splash
 *       does not feel it.</li>
 *   <li>{@link #step} is a slice of the work that needs the render thread (and so stops the splash while it runs). The
 *       engine does not start the steps until the splash has had {@linkplain WindowOptions#setWarmUpDelay a moment} to
 *       play its introduction, so a stall lands on a picture that is settled instead of in the middle of the animation.
 *       A task should do a little per call and return {@code false} until it is finished: the splash gets a frame
 *       between the calls, and quick slices share a frame.</li>
 *   <li>{@link #onApplicationStarted} lets the warm-up let go of what only it kept alive, once the application has drawn
 *       its first frames behind the splash.</li>
 * </ul>
 * Work that cannot be cut up, such as a driver call that takes seconds, still stops the splash for as long as it takes;
 * the order above only decides when and how often.
 * <p>
 * Warm-ups run only while there is a splash. With {@link WindowOptions#setSplashEnabled the splash switched off} they are
 * skipped, and the work happens when the application first needs it, as it would without them. Only the first window has
 * a splash, so only its options' warm-ups run.
 */
public interface WarmUp {
    /**
     * A short name for the splash to show, such as "3D scene", and for logs.
     */
    String getName();

    /**
     * Work that needs no graphics context, done on a worker thread while the splash plays. It starts when the window
     * opens; the warm-ups' prepares run one after another, and a warm-up's {@link #step} is not called until its prepare
     * has returned. Must not use the renderer, the window's GPU device or anything else that belongs to the render
     * thread. An exception is logged and the warm-up carries on to its steps. Does nothing unless overridden.
     */
    default void prepare() {
    }

    /**
     * Does a slice of the work, on the render thread with the window's graphics context current, between the frame's
     * begin and end like the application's own drawing. Called until it returns true.
     *
     * @return true when the work is finished and this warm-up is not to be called again; false to be called again later
     */
    boolean step(Window window, Renderer renderer);

    /**
     * Called once on the render thread when the warm-ups are done and the application has run its first few frames, still
     * hidden behind the splash. Those frames are where the application first draws what the warm-up prepared, so this is
     * the place to release what the warm-up only held to keep it alive until then, such as a hidden viewport. Called for
     * every warm-up that ran a step, including one that failed. Does nothing unless overridden.
     */
    default void onApplicationStarted(Window window) {
    }

    /**
     * A warm-up that does all of its work in one call.
     */
    static WarmUp of(String name, Consumer<Window> work) {
        return new WarmUp() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean step(Window window, Renderer renderer) {
                work.accept(window);
                return true;
            }
        };
    }

    /**
     * A warm-up whose work needs no graphics context and so runs on a worker thread while the splash plays, like
     * {@link #prepare()}; for example {@code WarmUp.inBackground("Audio", Audio::isAvailable)}.
     */
    static WarmUp inBackground(String name, Runnable work) {
        return new WarmUp() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void prepare() {
                work.run();
            }

            @Override
            public boolean step(Window window, Renderer renderer) {
                return true;
            }
        };
    }
}
