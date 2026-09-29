package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.entity.EntityScene;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.File;

/**
 * Several windows from one engine, the way an editor opens a game window beside itself.
 * <ul>
 *   <li>"Open a game window" opens a new window running the entity demo's scene. Each click opens another, and each
 *       has its own scene, input and size.</li>
 *   <li>Closing a game window closes only it. Closing this window closes them all and ends the program.</li>
 *   <li>"Reopen the last one" opens the last closed game window again, with the scene where it left off.</li>
 * </ul>
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class MultiWindowDemo {
    private static int opened;
    private static Window lastClosed;

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window main = new Window(new WindowOptions("Multi Window Demo").setDimensions(420, 260));

        VerticalLayout layout = new VerticalLayout(20, 20, 380, 220);
        layout.setSpacing(14f);
        TextOverlay count = new TextOverlay("No game windows open", 16f, Color.WHITE);
        ButtonOverlay open = new ButtonOverlay("Open a game window", 240, 40);
        ButtonOverlay reopen = new ButtonOverlay("Reopen the last one", 240, 40);
        layout.addElement(new TextOverlay("Close this window to quit", 20f, Color.WHITE));
        layout.addElement(open);
        layout.addElement(reopen);
        layout.addElement(count);
        main.addContainer(layout);

        open.onAction(() -> {
            opened++;
            Window game = new Window(new WindowOptions("Game window " + opened).setDimensions(640, 420));
            EntityScene scene = EntityScene.load("src/main/resources/demo/entities.json");
            scene.getCamera().setZoom(0.6f);
            game.getSceneManager().set(scene);
            game.onClose(() -> lastClosed = game);
            game.open();
        });
        reopen.onAction(() -> {
            if (lastClosed != null) lastClosed.open();
        });
        count.onUpdate(event -> {
            int games = Engine.getRunning() == null ? 0 : Engine.getRunning().getWindows().size() - 1;
            count.setText(games == 0 ? "No game windows open" : games + " game window" + (games == 1 ? "" : "s") + " open");
        });

        new Engine().start(main);
    }
}
