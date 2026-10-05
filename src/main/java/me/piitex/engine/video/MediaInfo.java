package me.piitex.engine.video;

/**
 * What the container says about a video file, read when it is opened.
 *
 * @param width         picture width in pixels
 * @param height        picture height in pixels
 * @param frameRate     frames per second, or 0 when the file does not say
 * @param duration      length in seconds, or 0 when the file does not say
 * @param videoCodec    a readable name for the picture codec, such as {@code "MJPEG"}
 * @param audioChannels 0 when the file has no usable soundtrack
 * @param audioRate     samples per second of the soundtrack
 * @param audioCodec    a readable name for the audio codec, or null
 * @param container     the container format, such as {@code "AVI"}
 */
public record MediaInfo(int width, int height, double frameRate, double duration, String videoCodec,
                        int audioChannels, int audioRate, String audioCodec, String container) {
    public boolean hasAudio() {
        return audioChannels > 0;
    }
}
