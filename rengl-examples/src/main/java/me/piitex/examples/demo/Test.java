package me.piitex.examples.demo;

import me.piitex.engine.*;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.*;
import me.piitex.engine.ui.theme.CrimsonTheme;
import me.piitex.engine.ui.theme.ThemeManager;

public class Test {
    public static void main(String[] args) {
        ThemeManager.setCurrent(new CrimsonTheme()); // before building, so everything starts dark
        Window window = new Window(new WindowOptions("Form").setDimensions(800, 600));

        Container root = new Container(800, 600);
        window.addContainer(root);

        VerticalLayout panel = new VerticalLayout(250, 150, 300, 300);
        panel.setSpacing(16);
        panel.setPadding(20);
        panel.setAlignment(Layout.Alignment.TOP_CENTER);
        panel.useTheme(); // opt this panel into theme colors
        root.addElement(panel);

        TextFieldOverlay name = new TextFieldOverlay(200, 28);
        name.setPlaceholder("Your name...");
        panel.addElement(name);

        ButtonOverlay submit = new ButtonOverlay("Submit", 200, 36);
        submit.onAction(() -> System.out.println("Hello, " + name.getText()));
        panel.addElement(submit);

        new Engine().start(window);
    }
}
