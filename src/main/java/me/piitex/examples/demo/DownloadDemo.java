package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadListener;
import me.piitex.engine.io.download.DownloadManager;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.StripeStyle;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.download.DownloadListContainer;
import me.piitex.engine.ui.download.DownloadTextOverlay;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads a llama.cpp release binary and shows it three ways.
 * <ul>
 *   <li><b>Start download</b> queues the file on a {@link DownloadManager}. Nothing is fetched until you click.</li>
 *   <li>The <b>card</b> (a {@link DownloadListContainer}, one card per download) shows the file name, a progress bar,
 *       percent, size, speed and ETA, with <b>Pause / Resume / Cancel</b> buttons. Pause keeps the partial file, so
 *       Resume continues from where it stopped; Cancel deletes it and offers Retry.</li>
 *   <li>While bytes are flowing the bar shows moving stripes. The controls beside the button edit the shared
 *       {@link StripeStyle} live: switch the stripes on or off, freeze them, and change their speed (negative moves
 *       left) and width. They work before the download starts and while it runs.</li>
 *   <li>The <b>single line</b> underneath is a {@link DownloadTextOverlay} bound to the same download.</li>
 * </ul>
 * The file lands in {@code run/downloads/} (the dev-mode data folder, which is git-ignored). This is the macOS
 * arm64 build; it is a {@code .tar.gz}, and unpacking it is left to the app.
 */
public class DownloadDemo {

    private static final String URL =
            "https://github.com/ggml-org/llama.cpp/releases/download/b10964/llama-b10964-bin-win-cuda-13.3-x64.zip";
    private static final Path TARGET = Path.of("run", "downloads", "llama-b10964-bin-macos-arm64.tar.gz");

    // One style shared by every card; the controls edit it and the bars follow on the next frame.
    private static final StripeStyle STRIPES = new StripeStyle()
            .setColor(new Color(0.65f, 0.65f, 0.65f, 1f))
            .setWidth(8f)
            .setSpeed(30f)
            .setEnabled(false);

    private static Container root;
    private static TextOverlay result;

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        Window window = new Window(new WindowOptions("Downloads").setDimensions(720, 420));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        root = new Container(720, 420);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("llama.cpp b10964 (macOS arm64)", 22f, Color.WHITE);
        title.setPosition(30, 24);
        root.addElement(title);

        // Two at a time is plenty here; with one file it only matters if you queue more.
        DownloadManager manager = new DownloadManager(2);

        // The list creates and removes a card for every download in the manager by itself.
        ScrollContainer scroll = new ScrollContainer(30, 130, 660, 170);
        scroll.useTheme();
        scroll.getStyling().setBackgroundColor(Color.TRANSPARENT);
        scroll.getStyling().setHoverColor(Color.TRANSPARENT);
        scroll.getStyling().setBorderThickness(0f);
        DownloadListContainer list = new DownloadListContainer(manager, 640);
        list.setCardCustomizer(card -> card.setStripeStyle(STRIPES));
        scroll.addElement(list);
        root.addElement(scroll);

        result = new TextOverlay("", 14f, Color.WHITE);
        result.setPosition(30, 370);
        root.addElement(result);

        ButtonOverlay start = new ButtonOverlay("Start download", 16f, Color.BLACK, 160, 40);
        start.setPosition(30, 72);
        start.onAction(() -> {
            start.setEnabled(false); // hides the button; one download is enough for the demo
            startDownload(manager);
        });
        root.addElement(start);

        addStripeControls();

        new Engine().start(window);
    }

    /** A row of controls, right of the start button, that edit {@link #STRIPES}. */
    private static void addStripeControls() {
        CheckBoxOverlay enabled = new CheckBoxOverlay(20, STRIPES.isEnabled());
        enabled.setPosition(210, 82);
        enabled.onToggle(STRIPES::setEnabled);
        TextOverlay enabledLabel = new TextOverlay("Stripes", 14f, Color.WHITE);
        enabledLabel.setPosition(236, 83);

        CheckBoxOverlay animated = new CheckBoxOverlay(20, STRIPES.isAnimated());
        animated.setPosition(310, 82);
        animated.onToggle(STRIPES::setAnimated);
        TextOverlay animatedLabel = new TextOverlay("Animated", 14f, Color.WHITE);
        animatedLabel.setPosition(336, 83);

        TextOverlay speedLabel = new TextOverlay("Speed: " + Math.round(STRIPES.getSpeed()), 12f, Color.WHITE);
        speedLabel.setPosition(430, 58);
        SliderOverlay speed = new SliderOverlay(120, -100, 100, STRIPES.getSpeed());
        speed.setPosition(430, 84);
        speed.onValueChange(value -> {
            STRIPES.setSpeed(value.floatValue());
            speedLabel.setText("Speed: " + Math.round(value));
        });

        TextOverlay widthLabel = new TextOverlay("Width: " + Math.round(STRIPES.getWidth()), 12f, Color.WHITE);
        widthLabel.setPosition(570, 58);
        SliderOverlay width = new SliderOverlay(120, 2, 30, STRIPES.getWidth());
        width.setPosition(570, 84);
        width.onValueChange(value -> {
            STRIPES.setWidth(value.floatValue());
            widthLabel.setText("Width: " + Math.round(value));
        });

        root.addElement(enabled);
        root.addElement(enabledLabel);
        root.addElement(animated);
        root.addElement(animatedLabel);
        root.addElement(speedLabel);
        root.addElement(speed);
        root.addElement(widthLabel);
        root.addElement(width);
    }

    private static void startDownload(DownloadManager manager) {
        Download download = new Download(URI.create(URL), TARGET);

        // Listener callbacks run on the download's worker thread, so they only store the message. The text element
        // is updated from its onUpdate hook, which runs on the render thread.
        AtomicReference<String> outcome = new AtomicReference<>("");
        download.addListener(new DownloadListener() {
            @Override
            public void onComplete(Download d) {
                outcome.set("Saved to " + d.getTarget().toAbsolutePath());
            }

            @Override
            public void onFailed(Download d, Throwable error) {
                outcome.set("Download failed: " + error.getMessage());
            }
        });
        result.onUpdate(event -> {
            String message = outcome.get();
            if (!message.equals(result.getText())) result.setText(message);
        });

        manager.add(download);

        // The same download, as one line of text.
        DownloadTextOverlay line = new DownloadTextOverlay(download, 14f, Color.WHITE);
        line.setShowName(false); // the card above already shows the name
        line.setPosition(30, 320);
        root.addElement(line);
    }
}
