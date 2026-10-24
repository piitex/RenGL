package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ResizableContainer;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows the two ways to use a {@link ResizableContainer}. Resize the window to watch both scale, and drag a
 * container's corners (the translucent squares) to resize it directly.
 * <ul>
 *   <li><b>Floating panel</b>: a top-level container with all four corners draggable. Draggable by its
 *       empty space too. It also follows the window size.</li>
 *   <li><b>Panel in a centering layout</b>: the layout re-centers it every frame, so only the bottom/right
 *       handles make sense (a top/left drag would be undone by the layout).</li>
 * </ul>
 * Child positions are relative to the container they are added to, so nothing here depends on build order.
 */
public class ResizableContainerDemo {

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED
        Window window = new Window(new WindowOptions("Resizable containers").setDimensions(1280, 720));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        // The full-window root goes first (drawn and hit-tested underneath); a full-window container added
        // after the floating panel would sit on top of it and take every click.
        window.addContainer(rootWithCenteredPanel());
        window.addContainer(floatingPanel());

        new Engine().start(window);
    }

    /**
     * A panel positioned by hand, with every corner draggable.
     */
    private static ResizableContainer floatingPanel() {
        ResizableContainer panel = new ResizableContainer(60, 60, 360, 260);
        panel.useTheme();
        panel.getStyling().setBackgroundColor(Color.TRANSPARENT);
        panel.setDraggable(true);
        panel.setUserResizable(true);
        panel.setShowResizeHandles(true);
        panel.setMinSize(200, 140);
        panel.setFontSizeLimits(10, 40);

        VerticalLayout form = new VerticalLayout(360, 260); // relative to the panel: 0,0
        form.setAlignment(Layout.Alignment.TOP_CENTER);
        form.setPadding(24);
        form.setSpacing(12);
        form.getStyling().setBackgroundColor(Color.TRANSPARENT);
        form.getStyling().setHoverColor(Color.TRANSPARENT);
        form.addElement(new TextOverlay("Floating panel", 22, Color.WHITE));
        form.addElement(new TextFieldOverlay(240, 34));
        form.addElement(new ButtonOverlay("Button", 100, 34));
        panel.addElement(form);
        return panel;
    }

    /**
     * A plain full-window root holding a layout that centers a resizable panel.
     */
    private static Container rootWithCenteredPanel() {
        Container root = new Container(1280, 720);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);

        VerticalLayout center = new VerticalLayout(1280, 720);
        center.setAlignment(Layout.Alignment.CENTER_RIGHT);
        center.setPadding(60);
        center.getStyling().setBackgroundColor(Color.TRANSPARENT);
        center.getStyling().setHoverColor(Color.TRANSPARENT);
        center.getStyling().setBorderThickness(0f);
        root.addElement(center);

        ResizableContainer panel = new ResizableContainer(360, 260);
        panel.useTheme();
        panel.getStyling().setBackgroundColor(Color.TRANSPARENT);
        panel.getStyling().setHoverColor(Color.TRANSPARENT);
        panel.setResizeHandles(ResizableContainer.Handle.SOUTH_EAST, ResizableContainer.Handle.SOUTH, ResizableContainer.Handle.EAST);
        panel.setShowResizeHandles(true);
        panel.setMinSize(200, 140);

        VerticalLayout form = new VerticalLayout(360, 260);
        form.setAlignment(Layout.Alignment.TOP_CENTER);
        form.setPadding(24);
        form.setSpacing(12);
        form.getStyling().setBackgroundColor(Color.TRANSPARENT);
        form.getStyling().setHoverColor(Color.TRANSPARENT);
        form.addElement(new TextOverlay("Panel in a layout", 22, Color.WHITE));
        form.addElement(new ButtonOverlay("Button", 100, 34));
        panel.addElement(form);

        center.addElement(panel); // added last on purpose: the layout moves it and everything inside
        return root;
    }
}
