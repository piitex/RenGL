package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.video.VideoPlayer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.VideoOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Plays a video file in a window: {@code VideoDemo path/to/video.avi}. Click the picture to pause or resume, click
 * the bar underneath to jump to that point. The video loops, and the file is streamed from disk however big it is.
 */
public class VideoDemo {
    private static final int W = 1000, H = 640, BAR_H = 14;

    static void main(String[] args) {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED
        if (args.length == 0) {
            System.err.println("Usage: VideoDemo <video file>   (AVI with Motion JPEG or uncompressed video, PCM audio)");
            return;
        }
        try {
            VideoPlayer.open(new File(args[0])).close();
        } catch (UnsupportedOperationException e) {
            System.err.println(e.getMessage());
            return;
        }
        Window window = new Window(new WindowOptions("Video demo").setDimensions(W, H));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(W, H);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);

        VideoOverlay video = new VideoOverlay(args[0], W, H - 60);
        video.setPosition(0, 0);
        video.getPlayer().setLooping(true);
        video.onMouseClick(_ -> video.toggle());
        root.addElement(video);

        ProgressBarOverlay bar = new ProgressBarOverlay(W - 40, BAR_H);
        bar.setPosition(20, H - 50);
        bar.onMouseClick(e -> video.getPlayer().setProgress((float) ((e.getMouseX() - bar.getAbsoluteX()) / bar.getWidth())));
        bar.onRender(_ -> bar.setProgress(video.getPlayer().getProgress()));
        root.addElement(bar);

        var info = video.getPlayer().getInfo();
        TextOverlay caption = new TextOverlay(new File(args[0]).getName() + "   " + info.width() + "x" + info.height()
                + "   " + info.videoCodec() + (info.hasAudio() ? " + " + info.audioCodec() : "")
                + "   click the picture to pause, the bar to seek", 13, Color.WHITE);
        caption.setPosition(20, H - 28);
        root.addElement(caption);

        window.addContainer(root);
        new Engine().start(window);
        video.dispose();
    }
}
