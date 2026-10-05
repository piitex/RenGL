package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.video.VideoPlayer;

import java.io.File;

/**
 * Plays a video file inside the element's bounds. Put it in a container like any other element, or give it the
 * whole window for a cutscene.
 * <pre>{@code
 * VideoOverlay intro = new VideoOverlay("assets/intro.avi", 1280, 720);
 * intro.getPlayer().onFinish(() -> startGame());
 * container.addElement(intro);
 * }</pre>
 * The video starts playing at once and the container advances it every frame. {@link #getPlayer()} has the full
 * controls (seek, loop, speed, volume, callbacks), and {@link #play()}, {@link #pause()}, {@link #stop()} and
 * {@link #toggle()} cover the common ones. Files are streamed from disk, so size does not matter: a video of tens
 * of gigabytes uses the same memory as a short clip.
 * <p>
 * The picture keeps its shape by default ({@link Fit#CONTAIN}), with the background showing in the bars. The
 * background is black, like a video player's; change it through {@link #getStyling()}. Fading the element uses
 * the normal element {@link #setOpacity opacity}.
 * <p>
 * An overlay owns its decode thread and the open file. Call {@link #dispose()} when you remove it, as it also
 * silences the soundtrack; letting the overlay go without disposing it leaves both running until the JVM exits.
 * See {@link VideoPlayer} for the supported formats.
 */
public class VideoOverlay extends Overlay {
    /**
     * How the picture is fitted when the overlay's shape differs from the video's.
     */
    public enum Fit {
        /** Whole picture visible, keeping its shape; the background shows in the bars. */
        CONTAIN,
        /** Fills the overlay, keeping the picture's shape and cropping what spills over. */
        COVER,
        /** Fills the overlay, stretching the picture to match. */
        STRETCH
    }

    private final VideoPlayer player;
    private Fit fit = Fit.CONTAIN;

    /**
     * Opens {@code file}, sized to the video's own pixel dimensions.
     */
    public VideoOverlay(File file) {
        this(VideoPlayer.open(file), 0, 0);
        setWidth(player.getWidth());
        setHeight(player.getHeight());
    }

    public VideoOverlay(String path) {
        this(new File(path));
    }

    /**
     * Opens {@code file}, fitted into the given bounds.
     */
    public VideoOverlay(File file, double width, double height) {
        this(VideoPlayer.open(file), width, height);
    }

    public VideoOverlay(String path, double width, double height) {
        this(new File(path), width, height);
    }

    /**
     * Shows a player you opened yourself. The overlay takes ownership: {@link #dispose()} closes it.
     */
    public VideoOverlay(VideoPlayer player, double width, double height) {
        this.player = player;
        setWidth(width);
        setHeight(height);
        getStyling().applyThemeBackgroundColor(Color.BLACK);
        getStyling().applyThemeHoverColor(Color.BLACK);
        getStyling().applyThemeBorderThickness(0f);
    }

    /**
     * The playback controls: seek, loop count, speed, volume and the finish/loop/error callbacks.
     */
    public VideoPlayer getPlayer() {
        return player;
    }

    public Fit getFit() {
        return fit;
    }

    public void setFit(Fit fit) {
        this.fit = fit == null ? Fit.CONTAIN : fit;
    }

    /**
     * Plays or resumes; after the video has ended, starts it again from the beginning.
     */
    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    /**
     * Pauses and returns to the first frame.
     */
    public void stop() {
        player.stop();
    }

    /**
     * Pauses a playing video and plays a paused one, which is what clicking a video usually does.
     */
    public void toggle() {
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    /**
     * The video's natural width in pixels, independent of this overlay's size.
     */
    public int getNaturalWidth() {
        return player.getWidth();
    }

    public int getNaturalHeight() {
        return player.getHeight();
    }

    /**
     * Stops the decode thread, silences the sound and closes the file. The overlay then draws only its background.
     */
    public void dispose() {
        player.close();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (isEnabled()) player.update(delta);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        var frame = player.getFrame();
        if (frame == null) return;
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float iw = frame.getWidth(), ih = frame.getHeight();
        if (!(w > 0) || !(h > 0) || !(iw > 0) || !(ih > 0)) return;
        float radius = getStyling().getCornerRadius();

        switch (fit) {
            case STRETCH -> renderer.drawImage(frame, x, y, w, h, radius, 1f);
            case CONTAIN -> {
                float scale = Math.min(w / iw, h / ih);
                float dw = iw * scale, dh = ih * scale;
                boolean fills = Math.abs(dw - w) < 0.5f && Math.abs(dh - h) < 0.5f;
                renderer.drawImage(frame, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh, fills ? radius : 0f, 1f);
            }
            case COVER -> {
                float scale = Math.max(w / iw, h / ih);
                float sw = w / scale, sh = h / scale;
                renderer.drawImageRegion(frame, (iw - sw) / 2f, (ih - sh) / 2f, sw, sh, x, y, w, h, null, true);
            }
        }
    }
}
