package me.piitex.engine.video;

/**
 * One compressed unit of a track, as cut out of the container: a video frame or a slice of audio.
 *
 * @param stream   the container's index for the track
 * @param pts      when it should be shown or heard, in seconds from the start of the file
 * @param keyframe whether decoding can start here
 */
record Packet(int stream, double pts, boolean keyframe, byte[] data) {
}
