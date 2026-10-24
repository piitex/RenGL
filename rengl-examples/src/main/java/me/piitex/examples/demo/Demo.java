package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.CrimsonTheme;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows {@link TabContainer}.
 * <ul>
 *   <li><b>Left</b>: a settings-style tab container with form controls, a scrolling page, a page that animates
 *       (it pauses while its tab is hidden) and a disabled tab. The buttons below it flip the strip to the
 *       bottom, enable or disable the "Locked" tab, and switch the theme.</li>
 *   <li><b>Top right</b>: closable document tabs. "New tab" adds one, and the last remaining tab refuses to close
 *       (see {@code onTabCloseRequested}). Middle-click also closes.</li>
 *   <li><b>Bottom right</b>: a bottom strip with more tabs than fit, to show the shrinking, the ellipsis with
 *       its tooltip, and wheel scrolling of the strip.</li>
 * </ul>
 * Click a tab strip, then use Left/Right/Home/End to change tabs from the keyboard.
 */
public class Demo {

    private static int documentCount = 0;
    private static boolean dark = true;

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        Window window = new Window(new WindowOptions("Tab containers").setDimensions(1280, 720));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        // Full-window root; transparent so the window background shows through.
        Container root = new Container(1280, 720);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        TextOverlay status = new TextOverlay("Selected: General", 14f, Color.WHITE);
        status.setPosition(40, 540);

        TextOverlay title = new TextOverlay("TabContainer", 24f, Color.WHITE);
        title.setPosition(40, 30);
        root.addElement(title);
        root.addElement(status);

        TabContainer settings = buildSettingsTabs(status);
        root.addElement(settings);
        addSettingsControls(root, settings);
        root.addElement(buildDocumentTabs(root));
        root.addElement(buildOverflowTabs());

        new Engine().start(window);
    }

    // ---- left: settings tabs --------------------------------------------------------------------

    private static TabContainer buildSettingsTabs(TextOverlay status) {
        TabContainer tabs = new TabContainer(40, 80, 680, 420);
        tabs.addTab("General", generalPage());
        tabs.addTab("Notes", notesPage());
        tabs.addTab("Progress", progressPage());
        tabs.addTab("Locked", labelPage("This tab is disabled, so the user cannot open it.")).setEnabled(false);
        tabs.addTab("About", labelPage("Only the selected tab's content is enabled.\nThe others keep their state but do not draw, update or take input."));

        tabs.onTabSelected(tab -> status.setText("Selected: " + tab.getTitle()));
        return tabs;
    }

    private static VerticalLayout generalPage() {
        VerticalLayout page = page(14);

        page.addElement(new TextOverlay("Display name", 14f, Color.WHITE));
        TextFieldOverlay name = new TextFieldOverlay(280, 32);
        name.setPlaceholder("Type here, then switch tabs and back");
        page.addElement(name);

        HorizontalLayout notify = new HorizontalLayout(300, 24);
        notify.setSpacing(10);
        notify.setAlignment(Layout.Alignment.CENTER_LEFT);
        notify.getStyling().setBackgroundColor(Color.TRANSPARENT);
        notify.getStyling().setHoverColor(Color.TRANSPARENT);
        notify.getStyling().setBorderThickness(0f);
        notify.addElement(new CheckBoxOverlay(20, true));
        notify.addElement(new TextOverlay("Enable notifications", 14f, Color.WHITE));
        page.addElement(notify);

        page.addElement(new TextOverlay("Volume", 14f, Color.WHITE));
        page.addElement(new SliderOverlay(300, 0, 100, 60));

        page.addElement(new TextOverlay("Language", 14f, Color.WHITE));
        DropdownOverlay<String> language = new DropdownOverlay<>(200, 32);
        language.addItem("English");
        language.addItem("Deutsch");
        language.addItem("Español");
        language.setSelectedIndex(0);
        page.addElement(language);
        return page;
    }

    private static ScrollContainer notesPage() {
        String paragraph = "A tab's content can be any element. This one is a ScrollContainer, so it keeps its own "
                + "scroll position while you look at other tabs.\n\n";
        ScrollContainer scroll = new ScrollContainer(100, 100);
        scroll.useTheme();
        scroll.getStyling().setBackgroundColor(Color.TRANSPARENT);
        scroll.getStyling().setHoverColor(Color.TRANSPARENT);
        scroll.getStyling().setBorderThickness(0f);

        TextFlowOverlay text = new TextFlowOverlay(paragraph.repeat(12), 620);
        text.setPosition(16, 16);
        scroll.addElement(text);
        return scroll;
    }

    private static VerticalLayout progressPage() {
        VerticalLayout page = page(14);
        page.addElement(new TextOverlay("This bar only advances while its tab is showing.", 14f, Color.WHITE));

        ProgressBarOverlay bar = new ProgressBarOverlay(400, 16);
        page.addElement(bar);
        double[] progress = {0};
        page.onUpdate(event -> {
            progress[0] = (progress[0] + event.getDelta() * 0.15) % 1.0;
            bar.setProgress(progress[0]);
        });
        return page;
    }

    private static void addSettingsControls(Container root, TabContainer settings) {
        ButtonOverlay position = new ButtonOverlay("Strip: top", 120, 32);
        position.setPosition(40, 580);
        position.onAction(() -> {
            boolean top = settings.getTabPosition() == TabContainer.TabPosition.TOP;
            settings.setTabPosition(top ? TabContainer.TabPosition.BOTTOM : TabContainer.TabPosition.TOP);
            position.setText(top ? "Strip: bottom" : "Strip: top");
        });

        ButtonOverlay locked = new ButtonOverlay("Enable \"Locked\"", 150, 32);
        locked.setPosition(176, 580);
        TabContainer.Tab lockedTab = settings.getTabs().get(3);
        locked.onAction(() -> {
            lockedTab.setEnabled(!lockedTab.isEnabled());
            locked.setText(lockedTab.isEnabled() ? "Disable \"Locked\"" : "Enable \"Locked\"");
        });

        ButtonOverlay theme = new ButtonOverlay("Crimson theme", 120, 32);
        theme.setPosition(342, 580);
        theme.onAction(() -> {
            dark = !dark;
            ThemeManager.setCurrent(dark ? new DarkTheme() : new CrimsonTheme());
            theme.setText(dark ? "Crimson theme" : "Dark theme");
        });

        root.addElement(position);
        root.addElement(locked);
        root.addElement(theme);
    }

    // ---- top right: closable documents ----------------------------------------------------------

    private static TabContainer buildDocumentTabs(Container root) {
        TabContainer documents = new TabContainer(760, 80, 480, 280);
        documents.setPadding(8);
        newDocument(documents);

        // The last tab stays: closing it would leave nothing to show.
        documents.onTabCloseRequested(tab -> documents.getTabCount() > 1);

        ButtonOverlay add = new ButtonOverlay("New tab", 100, 32);
        add.setPosition(760, 372);
        add.onAction(() -> newDocument(documents));
        root.addElement(add);
        return documents;
    }

    private static void newDocument(TabContainer documents) {
        String title = "Untitled " + (++documentCount);
        TextAreaOverlay area = new TextAreaOverlay(100, 100);
        area.setText("Document " + documentCount + "\n\nEach tab keeps its own text. Close it with the x or a middle-click.");

        TabContainer.Tab tab = documents.addTab(title, area);
        tab.setClosable(true);
        documents.selectTab(tab);
    }

    // ---- bottom right: overflow -----------------------------------------------------------------

    private static TabContainer buildOverflowTabs() {
        TabContainer tabs = new TabContainer(760, 430, 480, 250);
        tabs.setTabPosition(TabContainer.TabPosition.BOTTOM);
        tabs.setPadding(8);

        for (int i = 1; i <= 6; i++) {
            tabs.addTab("Tab " + i, labelPage("Content of tab " + i));
        }
        tabs.addTab("A rather long tab title that gets cut short", labelPage("Hover the tab to see its full title."));
        for (int i = 8; i <= 10; i++) {
            tabs.addTab("Tab " + i, labelPage("Content of tab " + i));
        }
        tabs.selectTab(0);
        return tabs;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A transparent column, so the tab container's own panel shows through.
     */
    private static VerticalLayout page(float spacing) {
        VerticalLayout page = new VerticalLayout(100, 100);
        page.setPadding(16);
        page.setSpacing(spacing);
        page.getStyling().setBackgroundColor(Color.TRANSPARENT);
        page.getStyling().setHoverColor(Color.TRANSPARENT);
        page.getStyling().setBorderThickness(0f);
        return page;
    }

    private static VerticalLayout labelPage(String text) {
        VerticalLayout page = page(0);
        page.addElement(new TextFlowOverlay(text, 320));
        return page;
    }
}
