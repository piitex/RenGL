package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.SplitContainer;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.layout.DockLayout;
import me.piitex.engine.ui.layout.FlowLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.RadioGroup;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UiContainersTest {
    private static Container root() {
        Container root = (Container) UiKinds.get("container").create("Root");
        root.setWidth(400);
        root.setHeight(300);
        return root;
    }

    private static Element put(Container parent, String kind, String name) {
        Element element = UiKinds.get(kind).create(name);
        UiKinds.add(parent, element, -1);
        return element;
    }

    private static Container reload(Container root) {
        return UiFile.parse(UiFile.toJson(root), Path.of(""));
    }

    private static void set(Element element, String property, Object value) {
        UiKinds.of(element).getProperty(property).set(element, value);
    }

    // --- Tabs -----------------------------------------------------------------------------------------------------

    @Test
    void tabsHoldTheirContentAndNotTheBar() {
        Container root = root();
        TabContainer tabs = (TabContainer) put(root, "tabs", "tabs");
        assertTrue(UiKinds.children(tabs).isEmpty(), "a made one is empty; the editor's starter adds the first tabs");
        assertTrue(tabs.getElements().size() > 0, "the bar is in the container's elements");
        Element first = put(tabs, "button", "one");
        Element second = put(tabs, "text", "two");
        assertEquals(List.of(first, second), UiKinds.children(tabs));
        assertEquals(2, tabs.getTabCount());
    }

    @Test
    void tabTitlesAreSavedAndLoaded() {
        Container root = root();
        TabContainer tabs = (TabContainer) put(root, "tabs", "tabs");
        Element page = put(tabs, "container", "page");
        set(page, "tabTitle", "Audio");
        put(tabs, "container", "other");

        TabContainer again = UiFile.find(reload(root), "tabs", TabContainer.class);
        assertEquals(2, again.getTabCount());
        assertEquals("Audio", again.getTabs().get(0).getTitle());
        assertEquals("other", again.getTabs().get(1).getTitle(), "a tab without a title is named after what is in it");
        assertEquals("page", UiFile.getName(again.getTabs().get(0).getContent()));
    }

    @Test
    void aTabTitleChangesTheLiveTab() {
        TabContainer tabs = (TabContainer) UiKinds.get("tabs").create("tabs");
        Element page = put(tabs, "container", "page");
        set(page, "tabTitle", "Video");
        assertEquals("Video", tabs.getTabs().getFirst().getTitle());
        assertEquals("Video", UiKinds.of(page).getProperty("tabTitle").get(page));
    }

    @Test
    void tabPropertiesOnlyApplyInsideTabs() {
        Container root = root();
        Element button = put(root, "button", "b");
        UiProperty title = UiKinds.get("button").getProperty("tabTitle");
        assertFalse(title.appliesTo(button));
        assertFalse(UiFile.write(button).containsKey("tabTitle"));
        TabContainer tabs = (TabContainer) put(root, "tabs", "tabs");
        Element page = put(tabs, "container", "page");
        assertTrue(title.appliesTo(page));
        assertFalse(UiKinds.get("container").getProperty("width").appliesTo(page), "the tabs decide a page's size");
        assertFalse(UiKinds.get("container").getProperty("x").appliesTo(page));
    }

    @Test
    void movingATabKeepsItsTitleAndRevealingSelectsIt() {
        Container root = root();
        TabContainer tabs = (TabContainer) put(root, "tabs", "tabs");
        Element a = put(tabs, "container", "a");
        Element b = put(tabs, "container", "b");
        set(b, "tabTitle", "Second");
        assertSame(a, tabs.getSelectedTab().getContent());
        UiKinds.reveal(b);
        assertSame(b, tabs.getSelectedTab().getContent());

        UiKinds.remove(tabs, b);
        assertNull(b.owner);
        assertEquals(1, tabs.getTabCount());
        UiKinds.add(tabs, b, 0);
        assertEquals(List.of(b, a), UiKinds.children(tabs));
        assertEquals("Second", tabs.getTabs().getFirst().getTitle());
    }

    @Test
    void aTabContainerCanSitInsideAnother() {
        Container root = root();
        TabContainer outer = (TabContainer) put(root, "tabs", "outer");
        Element page = put(outer, "container", "page");
        TabContainer inner = (TabContainer) put((Container) page, "tabs", "inner");
        put(inner, "button", "deep");
        Container again = reload(root);
        assertNotNull(UiFile.find(again, "deep", ButtonOverlay.class));
        assertEquals(1, UiFile.find(again, "inner", TabContainer.class).getTabCount());
    }

    // --- Splits ---------------------------------------------------------------------------------------------------

    @Test
    void aSplitHoldsTwoPanes() {
        Container root = root();
        SplitContainer split = (SplitContainer) put(root, "hsplit", "split");
        Element left = put(split, "container", "left");
        assertNull(UiKinds.full(split));
        Element right = put(split, "container", "right");
        assertEquals(List.of(left, right), UiKinds.children(split));
        assertNotNull(UiKinds.full(split));
        assertThrows(IllegalStateException.class, () -> put(split, "button", "third"));
        assertSame(left, split.getFirst());
        assertSame(right, split.getSecond());
    }

    @Test
    void aSplitPutsANewPaneWhereItWasToldAndSavesBothAndTheDivider() {
        Container root = root();
        SplitContainer split = (SplitContainer) put(root, "vsplit", "split");
        Element bottom = put(split, "container", "bottom");
        Element top = UiKinds.get("container").create("top");
        UiKinds.add(split, top, 0);
        assertSame(top, split.getFirst());
        assertSame(bottom, split.getSecond());
        set(split, "dividerPosition", 0.25);
        set(split, "dividerThickness", 10);

        SplitContainer again = UiFile.find(reload(root), "split", SplitContainer.class);
        assertEquals(SplitContainer.Orientation.VERTICAL, again.getOrientation());
        assertEquals(0.25, again.getDividerPosition());
        assertEquals(10f, again.getDividerThickness());
        assertEquals("top", UiFile.getName(again.getFirst()));
        assertEquals("bottom", UiFile.getName(again.getSecond()));
        assertEquals(2, ((List<?>) UiFile.write(again).get("children")).size(), "the divider isn't written");
    }

    @Test
    void anEmptySidedSplitKeepsTheOnePaneItHas() {
        Container root = root();
        SplitContainer split = (SplitContainer) put(root, "hsplit", "split");
        Element a = put(split, "container", "a");
        Element b = put(split, "container", "b");
        UiKinds.remove(split, a);
        assertEquals(List.of(b), UiKinds.children(split));
        UiKinds.add(split, a, 0);
        assertSame(a, split.getFirst());
        assertSame(b, split.getSecond());
    }

    @Test
    void aFileWithThreePanesIsRefusedWithAReason() {
        String json = """
                {"format":1,"root":{"kind":"hsplit","name":"s","children":[
                  {"kind":"container","name":"a"},{"kind":"container","name":"b"},{"kind":"container","name":"c"}]}}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> UiFile.parse(json, Path.of("")));
        assertTrue(e.getMessage().contains("\"c\""), e.getMessage());
    }

    @Test
    void startersGiveFreshOnesSomethingToStartFromAndLoadingDoesNot() {
        TabContainer tabs = (TabContainer) UiKinds.get("tabs").createNew("t");
        assertEquals(2, tabs.getTabCount());
        assertEquals("Tab 1", tabs.getTabs().get(0).getTitle());
        SplitContainer split = (SplitContainer) UiKinds.get("hsplit").createNew("s");
        assertEquals(2, UiKinds.children(split).size());

        Container root = root();
        UiKinds.add(root, tabs, -1);
        TabContainer again = UiFile.find(reload(root), "t", TabContainer.class);
        assertEquals(2, again.getTabCount(), "what the file says, not the starter on top of it");
    }

    // --- Dock and flow --------------------------------------------------------------------------------------------

    @Test
    void dockedChildrenTakeTheEdgesInOrder() {
        DockLayout dock = new DockLayout(300, 200);
        ButtonOverlay header = new ButtonOverlay("h", 50, 30);
        ButtonOverlay side = new ButtonOverlay("s", 60, 30);
        ButtonOverlay footer = new ButtonOverlay("f", 50, 20);
        ButtonOverlay body = new ButtonOverlay("b", 10, 10);
        dock.addElement(header, DockLayout.Dock.TOP);
        dock.addElement(footer, DockLayout.Dock.BOTTOM);
        dock.addElement(side, DockLayout.Dock.LEFT);
        dock.addElement(body, DockLayout.Dock.FILL);
        dock.relayout();

        assertEquals(0, header.getY());
        assertEquals(300, header.getWidth());
        assertEquals(30, header.getHeight());
        assertEquals(180, footer.getY());
        assertEquals(300, footer.getWidth());
        assertEquals(0, side.getX());
        assertEquals(30, side.getY());
        assertEquals(150, side.getHeight());
        assertEquals(60, body.getX());
        assertEquals(30, body.getY());
        assertEquals(240, body.getWidth());
        assertEquals(150, body.getHeight());
    }

    @Test
    void dockSettingsAndSpacingSurviveAFile() {
        Container root = root();
        DockLayout dock = (DockLayout) put(root, "dock", "dock");
        set(dock, "spacing", 4);
        Element bar = put(dock, "button", "bar");
        Element main = put(dock, "container", "main");
        set(bar, "dock", DockLayout.Dock.BOTTOM);
        set(main, "dock", DockLayout.Dock.FILL);
        assertEquals(DockLayout.Dock.BOTTOM, dock.getDock(bar));

        DockLayout again = UiFile.find(reload(root), "dock", DockLayout.class);
        assertEquals(4f, again.getSpacing());
        assertEquals(DockLayout.Dock.BOTTOM, again.getDock(UiFile.find(again, "bar")));
        assertEquals(DockLayout.Dock.FILL, again.getDock(UiFile.find(again, "main")));
    }

    @Test
    void whichSizeADockedChildHasDependsOnItsEdge() {
        Container root = root();
        DockLayout dock = (DockLayout) put(root, "dock", "dock");
        Element child = put(dock, "button", "b");
        UiProperty width = UiKinds.get("button").getProperty("width"), height = UiKinds.get("button").getProperty("height");
        assertFalse(width.appliesTo(child), "a top child is stretched across");
        assertTrue(height.appliesTo(child));
        set(child, "dock", DockLayout.Dock.LEFT);
        assertTrue(width.appliesTo(child));
        assertFalse(height.appliesTo(child));
        set(child, "dock", DockLayout.Dock.FILL);
        assertFalse(width.appliesTo(child) || height.appliesTo(child));
    }

    @Test
    void flowWrapsWhenARowIsFull() {
        FlowLayout flow = new FlowLayout(100, 200);
        flow.setSpacing(10);
        flow.setLineSpacing(5);
        Element[] chips = new Element[3];
        for (int i = 0; i < 3; i++) {
            chips[i] = new ButtonOverlay("c", 40, 20);
            flow.addElement(chips[i]);
        }
        flow.relayout();
        // 40 + 10 + 40 = 90 fits in 100; a third wouldn't.
        assertEquals(0, chips[0].getX());
        assertEquals(50, chips[1].getX());
        assertEquals(0, chips[0].getY());
        assertEquals(0, chips[2].getX());
        assertEquals(25, chips[2].getY());
        assertEquals(45, flow.getContentHeight());

        flow.setSizeToContent(false, true);
        assertEquals(45, flow.getHeight());
    }

    @Test
    void flowSettingsSurviveAFile() {
        Container root = root();
        FlowLayout flow = (FlowLayout) put(root, "flow", "flow");
        set(flow, "lineSpacing", 3);
        set(flow, "spacing", 6);
        put(flow, "button", "a");
        FlowLayout again = UiFile.find(reload(root), "flow", FlowLayout.class);
        assertEquals(3f, again.getLineSpacing());
        assertEquals(6f, again.getSpacing());
        assertEquals(1, UiKinds.children(again).size());
    }

    // --- Radio groups ---------------------------------------------------------------------------------------------

    @Test
    void radioButtonsWithTheSameGroupNameChooseOne() {
        Container root = root();
        RadioButtonOverlay a = (RadioButtonOverlay) put(root, "radio", "a");
        RadioButtonOverlay b = (RadioButtonOverlay) put(root, "radio", "b");
        RadioButtonOverlay c = (RadioButtonOverlay) put(root, "radio", "c");
        set(a, "group", "size");
        set(b, "group", "size");
        assertSame(a.getGroup(), b.getGroup());
        assertNull(c.getGroup(), "no group name, no group");
        set(a, "selected", true);
        set(b, "selected", true);
        assertFalse(a.isSelected());
        assertTrue(b.isSelected());
    }

    @Test
    void radioGroupsComeBackFromAFile() {
        Container root = root();
        for (String name : List.of("a", "b")) {
            RadioButtonOverlay r = (RadioButtonOverlay) put(root, "radio", name);
            set(r, "group", "g");
        }
        set(UiFile.find(root, "b"), "selected", true);
        Container again = reload(root);
        RadioButtonOverlay a = UiFile.find(again, "a", RadioButtonOverlay.class), b = UiFile.find(again, "b", RadioButtonOverlay.class);
        assertNotNull(a.getGroup());
        assertSame(a.getGroup(), b.getGroup());
        assertTrue(b.isSelected());
        assertSame(b, a.getGroup().getSelected());
        assertFalse(UiFile.write(a).get("group").toString().isEmpty());
    }

    @Test
    void aRadioTakenOutLeavesItsGroup() {
        Container root = root();
        RadioButtonOverlay a = (RadioButtonOverlay) put(root, "radio", "a");
        RadioButtonOverlay b = (RadioButtonOverlay) put(root, "radio", "b");
        set(a, "group", "g");
        set(b, "group", "g");
        RadioGroup group = a.getGroup();
        UiKinds.remove(root, b);
        assertNull(b.getGroup());
        assertEquals(List.of(a), group.getButtons());
    }

    // --- Other new kinds ------------------------------------------------------------------------------------------

    @Test
    void aNumberFieldIsOneElementNotALayoutOfParts() {
        Container root = root();
        SpinnerOverlay number = (SpinnerOverlay) put(root, "number", "count");
        assertFalse(UiKinds.get("number").isContainer());
        assertTrue(UiKinds.children(number).isEmpty());
        set(number, "max", 20);
        set(number, "value", 7);
        set(number, "step", 2);
        set(number, "decimals", 0);
        assertNull(UiFile.write(number).get("children"));
        assertNull(UiFile.find(root, "nothing"));

        SpinnerOverlay again = UiFile.find(reload(root), "count", SpinnerOverlay.class);
        assertEquals(20, again.getMax());
        assertEquals(7, again.getValue());
        assertEquals(2, again.getStep());
    }

    @Test
    void aDatePickerKeepsItsDateAndTimeAsText() {
        Container root = root();
        Element picker = put(root, "datepicker", "when");
        set(picker, "mode", DateTimePickerOverlay.Mode.DATE_TIME);
        set(picker, "date", "2026-10-03");
        set(picker, "time", "14:30");
        DateTimePickerOverlay again = UiFile.find(reload(root), "when", DateTimePickerOverlay.class);
        assertEquals(LocalDate.of(2026, 10, 3), again.getDate());
        assertEquals("14:30", again.getTime().toString());
        assertThrows(IllegalArgumentException.class, () -> set(picker, "date", "next tuesday"));
    }

    @Test
    void theDatePickerShowsOnlyWhatItsModeNeeds() {
        Element picker = UiKinds.get("datepicker").create("p");
        set(picker, "mode", DateTimePickerOverlay.Mode.DATE);
        assertTrue(UiKinds.get("datepicker").getProperty("date").appliesTo(picker));
        assertFalse(UiKinds.get("datepicker").getProperty("time").appliesTo(picker));
        set(picker, "mode", DateTimePickerOverlay.Mode.TIME);
        assertFalse(UiKinds.get("datepicker").getProperty("date").appliesTo(picker));
    }

    @Test
    void aResizableContainerKeepsItsLimits() {
        Container root = root();
        Element resizable = put(root, "resizable", "panel");
        set(resizable, "minWidth", 100);
        set(resizable, "maxWidth", 500);
        set(resizable, "userResizable", true);
        Element again = UiFile.find(reload(root), "panel");
        assertEquals(100, ((me.piitex.engine.ui.containers.ResizableContainer) again).getMinWidth());
        assertEquals(500, ((me.piitex.engine.ui.containers.ResizableContainer) again).getMaxWidth());
        assertTrue(((me.piitex.engine.ui.containers.ResizableContainer) again).isUserResizable());
        // No maximum is written as "no maximum", not as infinity, which JSON can't hold.
        Element free = put(root, "resizable", "free");
        assertFalse(UiFile.write(free).containsKey("maxWidth"));
        assertTrue(Double.isInfinite(((me.piitex.engine.ui.containers.ResizableContainer) UiFile.find(reload(root), "free")).getMaxWidth()));
    }

    @Test
    void everyKindIsInAGroupAndTheNewOnesAreListed() {
        List<String> ids = UiKinds.all().stream().map(UiKind::getId).toList();
        for (String id : List.of("resizable", "tabs", "hsplit", "vsplit", "flow", "dock", "textflow", "textarea", "number",
                "radio", "percent", "colorpicker", "datepicker")) {
            assertTrue(ids.contains(id), id);
        }
        // Kinds in a group sit together, so an editor's menu can put a break between groups.
        String previous = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (UiKind kind : UiKinds.all()) {
            if (!kind.getGroup().equals(previous)) assertTrue(seen.add(kind.getGroup()), kind.getGroup() + " is split up");
            previous = kind.getGroup();
        }
    }
}
