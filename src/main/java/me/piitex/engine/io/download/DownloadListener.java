package me.piitex.engine.io.download;

/**
 * Observes a {@link Download}. <b>Callbacks run on the download's worker thread, not the render thread</b>, so
 * do not touch UI elements from them directly. Set a flag or queue a value and read it from an {@code onUpdate}
 * hook, or (more simply) let a display such as {@code DownloadBarContainer} poll the download instead.
 * <p>
 * Unlike the engine's single-slot {@code onXxx} hooks, a download holds any number of listeners.
 */
public interface DownloadListener {
    /** Fired on every state change, including the first move out of {@link DownloadState#QUEUED}. */
    default void onStateChanged(Download download, DownloadState state) {
    }

    /** Fired once when the file is complete and verified, after {@link #onStateChanged}. */
    default void onComplete(Download download) {
    }

    /** Fired once when the download gives up, after {@link #onStateChanged}. */
    default void onFailed(Download download, Throwable error) {
    }
}
