package me.piitex.engine.gl;

import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link GLFWindow} tells its owner about files dragged onto it from Explorer / Finder. Everything runs on the
 * main thread. {@code x} and {@code y} are the pointer in window coordinates.
 * <p>
 * Only {@link #drop} comes from every platform. The rest come from Windows, whose drag and drop reports a drag while it
 * is still over the window; GLFW reports nothing until the files are dropped.
 */
public interface FileDropListener {
    /**
     * Whether a drop would be taken. When it is not, the pointer shows the "not allowed" sign over the window.
     */
    default boolean accepts() {
        return true;
    }

    /**
     * Files are over the window at (x, y), called repeatedly as they move.
     */
    default void over(List<Path> files, double x, double y) {
    }

    /**
     * The files left the window or the drag was cancelled. Not called for a drop.
     */
    default void leave() {
    }

    void drop(List<Path> files, double x, double y);
}
