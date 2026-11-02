package me.piitex.engine.input;

import java.nio.file.Path;
import java.util.List;

/**
 * Dragging files between the application and the operating system's own windows: Explorer, Finder, the desktop, other
 * programs.
 * <p>
 * <b>Into the application</b> is {@link me.piitex.engine.Window#onFileDrop}, {@link me.piitex.engine.Window#onFileDropAt}
 * and, on Windows, the hover events {@link me.piitex.engine.Window#onFileDragOver} and
 * {@link me.piitex.engine.Window#onFileDragLeave}.
 * <p>
 * <b>Out of the application</b> is {@link me.piitex.engine.Window#startFileDrag}: the user holds the left button on
 * something, and files travel with the pointer until it is released over Explorer or the desktop, which copy them.
 * {@link me.piitex.engine.ui.containers.TreeView#setFileDragSource} starts one by itself when a row is dragged out of
 * the window.
 * <p>
 * Dragging out works on Windows. {@link #isSupported()} says whether it does here. Elsewhere
 * {@code startFileDrag} reports {@link Outcome#UNSUPPORTED}.
 */
public final class FileDrag {
    private FileDrag() {
    }

    /**
     * What is dragged out, worked out only when something wants the files. That is the point of it: Explorer asks for
     * the files at the moment the user lets go, not when the drag starts, so the work of making them (extracting a
     * folder from an archive, say) is not done for a drag that is cancelled or dropped nowhere.
     * <p>
     * It runs on the engine's main thread, inside the operating system's request, so it must not touch the UI, and
     * the target waits while it works. It is asked at most once per drag. If it throws, the drop fails.
     * <p>
     * The paths must exist when it returns, and must go on existing: the target may still be copying them after the
     * drag has ended. Put them in a temporary folder and delete it later, when the application exits, not when this
     * returns or when the drag's {@code done} callback runs.
     */
    @FunctionalInterface
    public interface Source {
        List<Path> files() throws Exception;
    }

    /**
     * How a drag out of the application ended.
     */
    public enum Outcome {
        /** The user let go over something that accepted the files. */
        DROPPED,
        /** Escape, or let go over something that did not take them. */
        CANCELLED,
        /** This platform cannot drag files out of the application. */
        UNSUPPORTED,
        /** The drag could not start, or the source threw. */
        FAILED
    }

    /**
     * Files being dragged over the window, not yet dropped. {@code x} and {@code y} are the pointer in window
     * coordinates, like a mouse event's.
     */
    public record Hover(List<Path> files, double x, double y) {
    }

    /**
     * Files dropped on the window, and where the pointer was.
     */
    public record Drop(List<Path> files, double x, double y) {
    }

    /**
     * Whether files can be dragged out of the application on this platform.
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
