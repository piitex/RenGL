package me.piitex.engine.ui.image;

import me.piitex.engine.graphics.Animation;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureRegion;
import org.jetbrains.skia.AnimationFrameInfo;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Codec;
import org.jetbrains.skia.Data;
import org.jetbrains.skia.Image;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A multi-frame image such as an animated GIF (or animated WEBP), decoded frame by frame as it is asked for.
 * This is the picture data only; {@link ImagePlayer} keeps the playback time, and {@link
 * me.piitex.engine.ui.overlays.ImageOverlay} wires the two together, so most code never touches this class.
 * <pre>{@code
 * AnimatedImage gif = AnimatedImage.load("assets/cat.gif");
 * gif.getFrameCount();        // 24
 * gif.getDuration();          // seconds for one pass
 * Image frame = gif.getFrame(gif.frameAt(1.5)); // what shows 1.5 seconds in
 * }</pre>
 * Frames are composited the way the file asks (each frame drawn over the last, with the file's own disposal
 * rules), so every {@link #getFrame} is the whole picture, not just the part that changed.
 * <p>
 * To keep large animations from filling memory, only a bounded number of frames stay decoded: a short GIF
 * is decoded once and then replays from memory, while one past {@link #CACHE_LIMIT_BYTES} decodes each frame as
 * it comes up, which is cheap in order but costs a re-decode on a backwards seek.
 * <p>
 * Not thread-safe: use one instance from the render thread. Every overlay loading the same file gets its own
 * instance, since each plays at its own position. {@link #close()} frees the native decoder; it is also freed
 * when the instance is garbage collected.
 */
public final class AnimatedImage implements AutoCloseable {
    /**
     * Total size of decoded frames kept for replay, 64 MiB. An animation whose frames add up to more than this is
     * decoded on demand instead.
     */
    public static final long CACHE_LIMIT_BYTES = 64L << 20;

    /**
     * Browsers show a GIF frame that asks for under 20 ms (and a lot of old files write 0) at 100 ms, since
     * those files were made for players that rounded up. The same rule is applied here so files look as they do
     * in a browser.
     */
    private static final int MIN_FRAME_MS = 20;
    private static final int DEFAULT_FRAME_MS = 100;

    private final Codec codec;
    private final Data data;
    private final int width;
    private final int height;
    private final int loopCount;
    private final int[] frameMs;
    private final long[] frameStartMs; // milliseconds into one pass where each frame begins; whole numbers keep boundaries exact
    private final AnimationFrameInfo[] infos;
    private final Image[] cache;       // null when too large to keep; otherwise filled in as frames are decoded

    private final Bitmap work;
    private int workFrame = -1;        // the frame whose pixels are in `work`
    private Image current;             // snapshot of workFrame when nothing is cached
    private int currentFrame = -1;
    private boolean closed;

    private AnimatedImage(byte[] bytes) {
        this.data = Data.Companion.makeFromBytes(bytes, 0, bytes.length);
        Codec c;
        try {
            c = Codec.Companion.makeFromData(data);
        } catch (RuntimeException e) {
            data.close();
            throw new IllegalArgumentException("Not a decodable image", e);
        }
        this.codec = c;
        this.width = c.getWidth();
        this.height = c.getHeight();
        int count = Math.max(1, c.getFrameCount());
        this.infos = count > 1 ? c.getFramesInfo() : new AnimationFrameInfo[0];
        this.loopCount = c.getRepetitionCount();

        frameMs = new int[count];
        frameStartMs = new long[count];
        long t = 0;
        for (int i = 0; i < count; i++) {
            int ms = infos.length == 0 ? 0 : infos[i].getDuration();
            if (ms < MIN_FRAME_MS) ms = DEFAULT_FRAME_MS;
            frameMs[i] = ms;
            frameStartMs[i] = t;
            t += ms;
        }

        work = new Bitmap();
        work.allocPixels(c.getImageInfo());
        cache = (long) width * height * 4L * count <= CACHE_LIMIT_BYTES ? new Image[count] : null;
    }

    /**
     * Opens an animated image from its file bytes (the bytes of a GIF file, say). A file with a single frame is
     * accepted too, as a one-frame animation.
     *
     * @throws IllegalArgumentException if the bytes are not an image Skia can decode
     */
    public static AnimatedImage decode(byte[] bytes) {
        return new AnimatedImage(bytes);
    }

    public static AnimatedImage load(File file) {
        try {
            return decode(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read image file: " + file, e);
        }
    }

    public static AnimatedImage load(String path) {
        return load(new File(path));
    }

    /**
     * Loads from any path, including one inside a zip file system such as a mounted asset pack.
     */
    public static AnimatedImage load(Path path) {
        try {
            return decode(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read image: " + path, e);
        }
    }

    /**
     * Whether {@code bytes} start like a format that can hold an animation (GIF or WEBP), a cheap check that
     * avoids opening a codec for the PNGs and JPEGs that make up most images.
     */
    public static boolean mayBeAnimated(byte[] bytes) {
        if (bytes.length < 12) return false;
        boolean gif = bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
        boolean webp = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        return gif || webp;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFrameCount() {
        return frameMs.length;
    }

    public boolean isAnimated() {
        return frameMs.length > 1;
    }

    /**
     * How many times the file asks to be played, counting the first play: 1 plays once, 3 plays three times.
     * 0 or less means forever, which is what a GIF without a loop block or with an infinite one asks for. A file
     * that asks to play once but is shown looping is a common surprise; {@link ImagePlayer} follows this number
     * unless told otherwise.
     */
    public int getLoopCount() {
        // Skia reports the number of repeats after the first play; -1 means forever.
        return loopCount < 0 ? 0 : loopCount + 1;
    }

    /**
     * Seconds that frame {@code index} is shown, after the browser rule for the zero and near-zero durations that
     * old files carry.
     */
    public float getFrameDuration(int index) {
        return frameMs[index] / 1000f;
    }

    /**
     * Seconds one pass through all frames takes.
     */
    public float getDuration() {
        int last = frameMs.length - 1;
        return (frameStartMs[last] + frameMs[last]) / 1000f;
    }

    /**
     * The frame showing {@code seconds} into a pass, clamped to the first and last frame; the player is what
     * wraps time around for looping.
     */
    public int frameAt(double seconds) {
        if (!(seconds > 0)) return 0;
        double ms = seconds * 1000.0;
        int lo = 0, hi = frameMs.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (frameStartMs[mid] <= ms) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /**
     * The complete picture of frame {@code index}. The Image is owned by this AnimatedImage and may be closed by
     * it once a later frame is requested, so draw it right away and do not hold on to it; {@link #toAnimation}
     * is the way to get frames that stay.
     */
    public Image getFrame(int index) {
        if (closed) throw new IllegalStateException("closed");
        if (index < 0 || index >= frameMs.length) {
            throw new IndexOutOfBoundsException("frame " + index + " of " + frameMs.length);
        }
        if (cache != null) {
            Image cached = cache[index];
            if (cached == null) cached = cache[index] = snapshot(index);
            return cached;
        }
        if (currentFrame != index) {
            Image old = current;
            current = snapshot(index);
            currentFrame = index;
            if (old != null) old.close();
        }
        return current;
    }

    private Image snapshot(int index) {
        decodeInto(index);
        return Image.Companion.makeFromBitmap(work);
    }

    /**
     * Brings {@code work} to frame {@code index}. Skia composites over a prior frame when that is the one the
     * new frame is drawn on, and otherwise decodes whatever the frame depends on first.
     */
    private void decodeInto(int index) {
        if (workFrame == index) return;
        int required = infos.length == 0 ? -1 : infos[index].getRequiredFrame();
        int prior = workFrame >= 0 && workFrame == required ? workFrame : -1;
        codec.readPixels(work, index, prior);
        workFrame = index;
    }

    /**
     * Decodes every frame into its own texture and returns them as a sprite {@link Animation}, so a GIF can be
     * played by an {@link me.piitex.engine.graphics.AnimationPlayer} on a {@link me.piitex.engine.graphics.Sprite}
     * like a sprite sheet. Unlike {@link #getFrame}, the frames are independent copies that stay valid, at the
     * price of holding every frame in memory no matter how large; for a long or big animation, play it through an
     * {@link ImagePlayer} instead.
     */
    public Animation toAnimation(Animation.PlayMode mode) {
        List<Animation.Frame> frames = new ArrayList<>(frameMs.length);
        for (int i = 0; i < frameMs.length; i++) {
            decodeInto(i);
            Texture texture = new Texture(Image.Companion.makeFromBitmap(work));
            frames.add(new Animation.Frame(new TextureRegion(texture), getFrameDuration(i)));
        }
        return new Animation(frames, mode);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (current != null) current.close();
        if (cache != null) {
            for (Image image : cache) if (image != null) image.close();
        }
        work.close();
        codec.close();
        data.close();
    }
}
