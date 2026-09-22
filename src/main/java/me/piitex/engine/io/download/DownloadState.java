package me.piitex.engine.io.download;

/**
 * Where a {@link Download} is in its life.
 */
public enum DownloadState {
    /**
     * Not started, or waiting in a {@link DownloadManager} queue for a free slot.
     */
    QUEUED,
    /**
     * Opening the connection and waiting for the server's response headers.
     */
    CONNECTING,
    /**
     * Bytes are flowing.
     */
    DOWNLOADING,
    /**
     * Stopped on request with the partial file kept; {@link Download#start()} resumes it.
     */
    PAUSED,
    /**
     * All bytes arrived and the checksum is being computed.
     */
    VERIFYING,
    /**
     * The file is complete at its target path.
     */
    COMPLETED,
    /**
     * Gave up after an error (see {@link Download#getError()}).
     */
    FAILED,
    /**
     * Stopped on request and the partial file was deleted.
     */
    CANCELLED;

    /**
     * True while a worker is running: connecting, downloading or verifying.
     */
    public boolean isActive() {
        return this == CONNECTING || this == DOWNLOADING || this == VERIFYING;
    }

    /**
     * True once nothing more will happen without a new {@link Download#start()}: completed, failed or cancelled.
     */
    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
