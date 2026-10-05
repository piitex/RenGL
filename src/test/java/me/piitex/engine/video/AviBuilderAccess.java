package me.piitex.engine.video;

import java.io.File;
import java.io.IOException;

/** Lets tests in other packages write a small AVI with {@link AviBuilder}. */
public final class AviBuilderAccess {
    private AviBuilderAccess() {
    }

    /** Writes an MJPEG video of {@code frames} solid frames, the first one red. */
    public static File write(File dir, String name, int width, int height, int fps, int frames) throws IOException {
        return new AviBuilder(width, height, fps, false).frames(frames).write(dir, name);
    }
}
