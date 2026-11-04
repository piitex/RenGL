package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;

import java.util.function.Consumer;

/**
 * Work to finish while the {@linkplain Splash splash screen} is up, so the application's first frame does not stall on
 * it: loading a vendor's upscaler, building a 3D scene's pipelines, compiling shaders, reading a big file. Add one with
 * {@link WindowOptions#addWarmUp}; the engine runs them one after another on the render thread, starting once the
 * splash has been drawn, and keeps the application held back until every one is done.
 * <p>
 * Each {@link #step} call is one slice of work. The splash cannot move while a slice runs, because it is drawn by the
 * same thread, so a task that takes a long time should do a little per call and return {@code false} until it is
 * finished; the splash gets a frame between the calls. What needs no graphics context at all, such as reading files, is
 * better done on another thread, with a step that only checks whether that has finished.
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
     * Does a slice of the work, on the render thread with the window's graphics context current, between the frame's
     * begin and end like the application's own drawing. Called once per frame until it returns true.
     *
     * @return true when the work is finished and this warm-up is not to be called again; false to be called again next frame
     */
    boolean step(Window window, Renderer renderer);

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
}
