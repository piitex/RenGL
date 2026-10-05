package me.piitex.engine.video;

/**
 * A decoded picture: {@code width * height} pixels of BGRA with premultiplied alpha, which is what Skia draws
 * without converting. Produced on the decode thread, uploaded on the render thread.
 */
record VideoFrame(int width, int height, byte[] pixels, double pts) {
}
