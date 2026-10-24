package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.CrimsonTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows runtime window styles with one OS window playing two roles:
 * <ul>
 *   <li><b>Splash:</b> a small {@link WindowStyle#BORDERLESS} window (no title bar, title or close/minimize/maximize
 *       controls, always on top, centered) with a progress bar that fills over a few seconds.</li>
 *   <li><b>Main window:</b> when the bar completes, the same window switches to {@link WindowStyle#STANDARD}, resizes,
 *       re-centers and swaps its container.</li>
 * </ul>
 * The "Show splash again" button on the main window runs the switch in reverse, so you can watch the style change both ways.
 */
public class SplashDemo {
    private static final int SPLASH_W = 480, SPLASH_H = 270;
    private static final int MAIN_W = 1000, MAIN_H = 640;

    private static Window window;
    private static Container splash;
    private static Container main;
    private static ProgressBarOverlay progress;

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        // The window is created as the splash. The style and always-on-top are creation options here.
        window = new Window(new WindowOptions("Splash Demo")
                .setDimensions(SPLASH_W, SPLASH_H)
                .setStyle(WindowStyle.BORDERLESS)
                .setAlwaysOnTop(true)
                .setResizable(false));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new CrimsonTheme());

        splash = buildSplash();
        main = buildMain();
        window.addContainer(splash);
        startLoading();

        new Engine().start(window);
    }

    private static Container buildSplash() {
        Container container = new Container(SPLASH_W, SPLASH_H);
        container.getStyling().setBorderThickness(0f);
        container.useTheme();

        TextOverlay title = new TextOverlay("Crimson", 42f, Color.WHITE);
        title.setPosition(150, 80);
        container.addElement(title);

        TextOverlay status = new TextOverlay("Loading...", 14f, Color.WHITE);
        status.setPosition(150, 150);
        container.addElement(status);

        progress = new ProgressBarOverlay(SPLASH_W - 80, 8);
        progress.setPosition(40, SPLASH_H - 50);
        container.addElement(progress);
        return container;
    }

    private static Container buildMain() {
        Container container = new Container(MAIN_W, MAIN_H);
        container.getStyling().setBorderThickness(0f);
        container.useTheme();

        TextOverlay heading = new TextOverlay("The main window", 28f, Color.WHITE);
        heading.setPosition(40, 40);
        container.addElement(heading);

        TextOverlay body = new TextOverlay("Same OS window as the splash: the style, size and position changed at runtime.", 15f, Color.WHITE);
        body.setPosition(40, 100);
        container.addElement(body);

        ButtonOverlay again = new ButtonOverlay("Show splash again", 180, 36);
        again.setPosition(40, 150);
        again.onAction(SplashDemo::showSplash);
        container.addElement(again);
        return container;
    }

    /**
     * Fills the bar, then hands over to the main window. The completion callback fires on the render thread, and every Window call used here is safe from any thread.
     */
    private static void startLoading() {
        progress.setProgress(0);
        progress.animateTo(1.0, 3f, me.piitex.engine.ui.animation.Easing.LINEAR, SplashDemo::showMain);
    }

    private static void showMain() {
        window.setStyle(WindowStyle.STANDARD);
        window.setAlwaysOnTop(false);
        window.setTitle("Splash Demo");
        window.setSize(MAIN_W, MAIN_H);
        window.center();
        window.removeContainer(splash);
        window.addContainer(main);
    }

    private static void showSplash() {
        window.setStyle(WindowStyle.BORDERLESS);
        window.setAlwaysOnTop(true);
        window.setSize(SPLASH_W, SPLASH_H);
        window.center();
        window.removeContainer(main);
        window.addContainer(splash);
        startLoading();
    }
}
