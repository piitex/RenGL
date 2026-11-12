package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.access.AccessibilityTree;
import me.piitex.engine.access.AccessibleNode;
import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.AccordionContainer;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ListBox;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.containers.TreeView;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.GaugeOverlay;
import me.piitex.engine.ui.overlays.HotkeyOverlay;
import me.piitex.engine.ui.overlays.KnobOverlay;
import me.piitex.engine.ui.overlays.RangeSliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The composite controls (knob, range slider, list, tree, table, tabs, accordion) say what they are and what they hold,
 * and tell an {@link AccessibilityHost} when the keyboard changes it.
 */
class AccessibleControlsTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(600, 600));
    private final InputProcessor processor = new InputProcessor();
    private final Container panel = new Container(0, 0, 600, 600);
    private final List<String> heard = new ArrayList<>();

    AccessibleControlsTest() {
        window.addContainer(panel);
        window.setAccessibilityHost(new AccessibilityHost() {
            @Override
            public void valueChanged(Window w, Element element) {
                heard.add(element.getAccessibleRole() + " " + element.getAccessibleValue());
            }

            @Override
            public void focusChanged(Window w, Element previous, Element now, boolean byKeyboard) {
            }

            @Override
            public void announce(Window w, String text, boolean urgent) {
                heard.add("announce " + text);
            }

            @Override
            public void close() {
            }
        });
    }

    private <E extends Element> E put(E element, double x, double y) {
        element.setPosition(x, y);
        panel.addElement(element);
        return element;
    }

    private void key(int key) {
        key(key, 0);
    }

    private void key(int key, int mods) {
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, mods);
        processor.processEvents(window);
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, mods);
        processor.processEvents(window);
    }

    @Test
    void aKnobIsASliderThatTheKeyboardMovesAndSpeaks() {
        KnobOverlay knob = put(new KnobOverlay(60, 0, 10), 10, 10);
        knob.setStep(1);
        window.requestFocus(knob);
        assertEquals(AccessibleRole.SLIDER, knob.getAccessibleRole());
        assertFalse(knob.grabsKeyboard(), "like a slider it leaves the other keys to the game");
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_END);
        key(GLFW.GLFW_KEY_END); // already there: nothing to say
        assertEquals(List.of("SLIDER 1", "SLIDER 2", "SLIDER 10"), heard);
        knob.setValueByUser(4);
        assertEquals("4", knob.getAccessibleValue());
        assertEquals("SLIDER 4", heard.getLast());
    }

    @Test
    void aRangeSliderReadsBothEndsAndSaysWhichThumbIsMoving() {
        RangeSliderOverlay range = put(new RangeSliderOverlay(200, 0, 100), 10, 10);
        range.setStep(10);
        range.setRange(20, 80);
        window.requestFocus(range);
        assertEquals("20 to 80", range.getAccessibleValue());
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_SPACE);
        key(GLFW.GLFW_KEY_LEFT);
        assertEquals(List.of("SLIDER 30 to 80", "announce Adjusting the upper value", "SLIDER 30 to 70"), heard);
    }

    @Test
    void aListSaysTheRowItIsOn() {
        ListBox<String> list = put(new ListBox<>(150, 100), 10, 10);
        list.setItems(List.of("Sword", "Shield", "Potion"));
        window.requestFocus(list);
        assertEquals(AccessibleRole.LIST, list.getAccessibleRole());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("LIST Sword", "LIST Shield"), heard);
    }

    @Test
    void aTreeSaysTheItemAndWhetherItIsOpen() {
        TreeView<String> tree = put(new TreeView<String>(150, 100, item -> item.equals("Weapons") ? List.of("Sword") : List.of()), 10, 10);
        tree.setRoots(List.of("Weapons"));
        window.requestFocus(tree);
        assertEquals(AccessibleRole.TREE, tree.getAccessibleRole());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_RIGHT); // opens it
        key(GLFW.GLFW_KEY_RIGHT); // into the first child
        assertEquals(List.of("TREE Weapons, collapsed", "TREE Weapons, expanded", "TREE Sword"), heard);
    }

    @Test
    void aTableReadsTheRowAfterItsColumnTitles() {
        TableView<String[]> table = put(new TableView<>(300, 100), 10, 10);
        table.addColumn("Name", row -> row[0]);
        table.addColumn("Price", row -> row[1]);
        table.setItems(List.of(new String[]{"Sword", "40"}, new String[]{"Shield", "25"}));
        window.requestFocus(table);
        assertEquals(AccessibleRole.TABLE, table.getAccessibleRole());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("TABLE Name: Sword, Price: 40", "TABLE Name: Shield, Price: 25"), heard);
    }

    @Test
    void theTabStripSaysWhichTabAndWhere() {
        TabContainer tabs = put(new TabContainer(300, 100), 10, 10);
        tabs.addTab("Graphics", new TextOverlay("g", 12f, me.piitex.engine.ui.color.Color.BLACK));
        tabs.addTab("Audio", new TextOverlay("a", 12f, me.piitex.engine.ui.color.Color.BLACK));
        AccessibleNode strip = find(AccessibilityTree.snapshot(window), AccessibleRole.TAB_LIST);
        assertNotNull(strip, "the strip is in the tree");
        assertEquals("Graphics, tab 1 of 2", strip.value());
        window.requestFocus(strip.element());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(List.of("TAB_LIST Audio, tab 2 of 2"), heard);
    }

    @Test
    void anAccordionSaysTheSectionAndWhetherItIsOpen() {
        AccordionContainer accordion = put(new AccordionContainer(300), 10, 10);
        accordion.addSection("Graphics", new TextOverlay("g", 12f, me.piitex.engine.ui.color.Color.BLACK));
        accordion.addSection("Audio", new TextOverlay("a", 12f, me.piitex.engine.ui.color.Color.BLACK));
        window.requestFocus(accordion);
        key(GLFW.GLFW_KEY_ENTER);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("GROUP Graphics, expanded, section 1 of 2", "GROUP Audio, collapsed, section 2 of 2"), heard);
    }

    @Test
    void aGaugeIsReadAsAMeter() {
        GaugeOverlay gauge = put(new GaugeOverlay(80, 0, 100), 10, 10);
        gauge.setValue(42);
        assertEquals(AccessibleRole.PROGRESS_BAR, gauge.getAccessibleRole());
        assertEquals("42", gauge.getAccessibleValue());
        assertEquals(List.of(), heard, "nobody is listening to it yet, so a change says nothing");
    }

    @Test
    void aGaugeIsATabStopThatKeepsTheListenerUpToDate() {
        GaugeOverlay gauge = put(new GaugeOverlay(80, 0, 100), 10, 10);
        key(GLFW.GLFW_KEY_TAB);
        assertSame(gauge, window.getFocusedElement());
        assertFalse(gauge.grabsKeyboard());
        assertFalse(gauge.handlesKey(GLFW.GLFW_KEY_W, 0), "WASD still belongs to the game");
        gauge.setValue(30);
        gauge.setValue(30); // the same again: nothing new to say
        gauge.setValue(40);
        assertEquals(List.of("PROGRESS_BAR 30", "PROGRESS_BAR 40"), heard);
    }

    @Test
    void aColorPickerIsChangedChannelByChannelAndSaysTheColorsName() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30, me.piitex.engine.ui.color.Color.BLUE), 10, 10);
        window.requestFocus(picker);
        assertFalse(picker.grabsKeyboard(), "a game keeps its keys when the picker was only clicked");
        assertEquals("#0000FF, blue", picker.getAccessibleValue());
        assertNotNull(picker.getAccessibleDescription());
        key(GLFW.GLFW_KEY_LEFT); // hue 240 -> 230
        key(GLFW.GLFW_KEY_DOWN); // to saturation
        key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_SHIFT); // 100% -> 80%
        key(GLFW.GLFW_KEY_HOME); // no colour left: white
        key(GLFW.GLFW_KEY_HOME); // already there: nothing to say
        assertEquals(4, heard.size(), heard.toString());
        assertTrue(heard.get(0).startsWith("BUTTON #00") && heard.get(0).endsWith(", blue"), heard.get(0));
        assertEquals("announce Saturation, 100 percent", heard.get(1));
        assertTrue(heard.get(2).endsWith(", blue") || heard.get(2).endsWith(", pale blue"), heard.get(2));
        assertEquals("BUTTON #FFFFFF, white", heard.get(3));
    }

    @Test
    void aColorPickerIsReachedWithTabAndChangesWithTheArrowKeys() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30), 10, 10);
        key(GLFW.GLFW_KEY_TAB);
        assertSame(picker, window.getFocusedElement());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(1, heard.size(), heard.toString());
    }

    @Test
    void aColorPickerOpenedWithTheMouseSaysSo() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30, me.piitex.engine.ui.color.Color.BLUE), 10, 10);
        picker.toggleOpen();
        picker.toggleOpen();
        assertEquals(List.of("announce Colour panel open, #0000FF, blue", "announce Colour panel closed"), heard);
    }

    @Test
    void aColorPickerStepsThroughItsPresets() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30), 10, 10);
        window.requestFocus(picker);
        key(GLFW.GLFW_KEY_PAGE_DOWN);
        key(GLFW.GLFW_KEY_PAGE_DOWN);
        key(GLFW.GLFW_KEY_PAGE_UP);
        assertEquals(List.of("BUTTON #E53935, red", "BUTTON #FB8C00, orange", "BUTTON #E53935, red"), heard);
    }

    @Test
    void aColorPickersPanelTakesTheKeyboardForTheHexCodeAndGivesItBack() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30), 10, 10);
        window.requestFocus(picker);
        key(GLFW.GLFW_KEY_ENTER);
        assertTrue(picker.isOpen(), "Enter opens the panel");
        assertNotSame(picker, window.getFocusedElement(), "the cursor is in the hex field");
        assertNotNull(window.getFocusedElement());
        key(GLFW.GLFW_KEY_ESCAPE);
        assertFalse(picker.isOpen(), "Escape closes it");
        assertSame(picker, window.getFocusedElement(), "and the picker has the keyboard again");
    }

    private static AccessibleNode find(List<AccessibleNode> nodes, AccessibleRole role) {
        for (AccessibleNode node : nodes) {
            if (node.role() == role) return node;
            AccessibleNode inside = find(node.children(), role);
            if (inside != null) return inside;
        }
        return null;
    }

    private void press(int key) {
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, 0);
        processor.processEvents(window);
    }

    private void release(int key) {
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, 0);
        processor.processEvents(window);
    }

    @Test
    void aHotkeyBoxSaysWhatItHoldsAndSpeaksWhileItLearnsAKey() {
        HotkeyOverlay jump = put(new HotkeyOverlay(160, 32), 10, 10);
        jump.setBinding(me.piitex.engine.input.Binding.key(GLFW.GLFW_KEY_SPACE));
        assertEquals(AccessibleRole.BUTTON, jump.getAccessibleRole());
        assertEquals("Space", jump.getAccessibleValue());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(jump, window.getFocusedElement());
        key(GLFW.GLFW_KEY_ENTER);
        assertTrue(jump.isListening());
        assertEquals("announce Press a key... Escape cancels.", heard.getLast());
        key(GLFW.GLFW_KEY_F);
        assertFalse(jump.isListening());
        assertEquals("BUTTON F", heard.getLast());
        key(GLFW.GLFW_KEY_ENTER);
        key(GLFW.GLFW_KEY_ESCAPE);
        assertEquals("announce Cancelled, F", heard.getLast());
        assertEquals("F", jump.getAccessibleValue(), "cancelling keeps the old key");
    }

    @Test
    void aHotkeyBoxTellsWhyAKeyWasRefusedAndKeepsListening() {
        HotkeyOverlay fire = put(new HotkeyOverlay(160, 32), 10, 10);
        fire.setConflictCheck(binding -> "In use by Jump");
        window.requestFocus(fire);
        fire.startListening();
        key(GLFW.GLFW_KEY_J);
        assertTrue(fire.isListening());
        assertEquals("announce In use by Jump. Press another key.", heard.getLast());
        fire.setConflictCheck(null);
        key(GLFW.GLFW_KEY_BACKSPACE);
        assertEquals("BUTTON Unbound", heard.getLast());
    }

    @Test
    void anInventoryGridIsListedSlotBySlotAndItemsMoveWithTheKeyboard() {
        me.piitex.engine.ui.overlays.InventoryGridOverlay<String> bag = put(new me.piitex.engine.ui.overlays.InventoryGridOverlay<>(3, 2, 40), 10, 10);
        bag.setSlot(0, "Sword", 1);
        bag.setSlot(1, "Potion", 5);
        bag.setLocked(5, true);
        assertEquals(AccessibleRole.LIST, bag.getAccessibleRole());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(bag, window.getFocusedElement());
        assertFalse(bag.grabsKeyboard(), "a click on the grid must not keep WASD from the game");
        assertFalse(bag.handlesKey(GLFW.GLFW_KEY_W, 0));
        assertEquals("Slot 1 of 6, Sword", bag.getAccessibleValue());
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("LIST Slot 2 of 6, Potion, 5", "LIST Slot 3 of 6, empty", "LIST Slot 6 of 6, locked"), heard);

        heard.clear();
        key(GLFW.GLFW_KEY_HOME);
        key(GLFW.GLFW_KEY_M);
        assertTrue(bag.isCarrying());
        assertTrue(heard.getLast().startsWith("announce Picked up Sword"), heard.getLast());
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_M);
        assertFalse(bag.isCarrying());
        assertEquals("announce Moved Sword to slot 3", heard.getLast());
        assertTrue(bag.isEmpty(0));
        assertEquals("Sword", bag.getItem(2));
    }

    @Test
    void aStackBeingCarriedCanBeCancelledAndASlotThatRefusesItSaysSo() {
        me.piitex.engine.ui.overlays.InventoryGridOverlay<String> bag = put(new me.piitex.engine.ui.overlays.InventoryGridOverlay<>(2, 1, 40), 10, 10);
        bag.setSlot(0, "Helmet", 1);
        bag.setSlotFilter(1, item -> item.equals("Sword"));
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_M);
        key(GLFW.GLFW_KEY_ESCAPE);
        assertFalse(bag.isCarrying());
        assertEquals("announce Cancelled", heard.getLast());
        key(GLFW.GLFW_KEY_M);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_M);
        assertEquals("announce It does not go there", heard.getLast());
        assertEquals("Helmet", bag.getItem(0), "nothing moved");
    }

    @Test
    void aJoystickIsAGroupThatSaysWhichWayItIsPushedWhenItChanges() {
        me.piitex.engine.ui.overlays.JoystickOverlay stick = put(new me.piitex.engine.ui.overlays.JoystickOverlay(100), 10, 10);
        assertEquals(AccessibleRole.GROUP, stick.getAccessibleRole());
        assertEquals("Joystick", stick.getAccessibleName());
        assertEquals("centered", stick.getAccessibleValue());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(stick, window.getFocusedElement());
        assertFalse(stick.grabsKeyboard());
        assertFalse(stick.handlesKey(GLFW.GLFW_KEY_W, 0), "WASD is the game's");
        press(GLFW.GLFW_KEY_UP);
        assertEquals(1.0, stick.getAxisY(), 1e-9);
        press(GLFW.GLFW_KEY_RIGHT);
        release(GLFW.GLFW_KEY_UP);
        release(GLFW.GLFW_KEY_RIGHT);
        assertEquals(0.0, stick.getMagnitude(), 1e-9, "it springs back");
        assertEquals(List.of("GROUP up, 100 percent", "GROUP up right, 100 percent", "GROUP right, 100 percent", "GROUP centered"), heard);
    }

    @Test
    void aJoystickDraggedWithTheMouseSpeaksTheDirectionOnlyWhenItChanges() {
        me.piitex.engine.ui.overlays.JoystickOverlay stick = put(new me.piitex.engine.ui.overlays.JoystickOverlay(100), 10, 10);
        stick.onDragStart(60, 10); // straight up from the middle
        stick.onDragUpdate(60, 8);
        stick.onDragUpdate(61, 6); // same sector: nothing new
        stick.onDragUpdate(110, 60); // to the right
        stick.onDragEnd(110, 60);
        assertEquals(3, heard.size(), heard.toString());
        assertTrue(heard.get(0).startsWith("GROUP up"), heard.get(0));
        assertTrue(heard.get(1).startsWith("GROUP right"), heard.get(1));
        assertEquals("GROUP centered", heard.get(2));
    }

    @Test
    void aMinimapListsItsMarkersAndTheKeyboardWalksThemAndNavigates() {
        me.piitex.engine.ui.overlays.MinimapOverlay map = put(new me.piitex.engine.ui.overlays.MinimapOverlay(200, 200), 10, 10);
        map.setWorld(0, 0, 1000, 1000);
        map.addMarker(900, 900, me.piitex.engine.ui.color.Color.RED).setLabel("Shop");
        me.piitex.engine.ui.overlays.MinimapOverlay.Marker player = map.addMarker(100, 100, me.piitex.engine.ui.color.Color.WHITE).setLabel("Player");
        map.addMarker(500, 500, me.piitex.engine.ui.color.Color.GREEN); // no label: left out
        map.setFollow(player);
        List<String> went = new ArrayList<>();
        map.onNavigate((x, y) -> went.add(Math.round(x) + "," + Math.round(y)));
        assertEquals(AccessibleRole.IMAGE, map.getAccessibleRole());
        assertEquals("Map", map.getAccessibleName());
        assertEquals("2 markers", map.getAccessibleValue());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(map, window.getFocusedElement());
        assertFalse(map.grabsKeyboard());

        key(GLFW.GLFW_KEY_PAGE_DOWN);
        key(GLFW.GLFW_KEY_PAGE_DOWN);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals("IMAGE Shop, bottom right, 1131 units south east of you", heard.get(0));
        assertEquals("IMAGE Player, top left", heard.get(1));
        assertEquals("IMAGE top left, near Player", heard.get(2));
        assertEquals(List.of("150,100"), went);
        assertEquals("announce Going to the top left", heard.getLast());
    }

    @Test
    void aMinimapMarkerClickedWithTheMouseIsSaidAloud() {
        me.piitex.engine.ui.overlays.MinimapOverlay map = put(new me.piitex.engine.ui.overlays.MinimapOverlay(200, 200), 10, 10);
        map.setWorld(0, 0, 200, 200);
        map.addMarker(100, 100, me.piitex.engine.ui.color.Color.RED).setLabel("Gate");
        map.onDragStart(110, 110); // the marker sits at the middle of the map
        map.onDragEnd(110, 110);
        assertEquals("IMAGE Gate, center", heard.getLast());
    }

    @Test
    void aRadialMenuReadsOutEachWedgeAsItIsReached() {
        me.piitex.engine.ui.menu.RadialMenu radial = new me.piitex.engine.ui.menu.RadialMenu();
        List<String> chosen = new ArrayList<>();
        radial.addItem("Attack", () -> chosen.add("Attack"));
        radial.addItem("Defend", () -> chosen.add("Defend"));
        radial.addItem("Item", () -> chosen.add("Item"));
        radial.show(window, 300, 300);
        assertTrue(radial.isOpen());
        assertTrue(heard.getLast().startsWith("announce Radial menu, 3 choices"), heard.getLast());
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals("announce Attack, 1 of 3", heard.get(1));
        assertEquals("announce Defend, 2 of 3", heard.get(2));
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals("announce Chose Defend", heard.getLast());
        assertEquals(List.of("Defend"), chosen);
        assertFalse(radial.isOpen());
    }

    @Test
    void aConsoleReadsNewLinesAloudWithTheirLevelAndSenderButNotNoise() {
        me.piitex.engine.ui.containers.ConsoleLog console = put(new me.piitex.engine.ui.containers.ConsoleLog(300, 200), 10, 10);
        console.info("Game loaded");
        console.debug("shader cache warm");
        console.warn("Low fuel");
        console.error("Engine failed");
        console.chat("Ana", "anyone seen the key?", null);
        assertEquals(List.of("announce Game loaded", "announce Warning, Low fuel", "announce Error, Engine failed",
                "announce Ana says, anyone seen the key?"), heard);
        console.setAnnounceLevel(null);
        console.info("silent now");
        assertEquals(4, heard.size());
    }

    @Test
    void aBurstOfConsoleLinesIsCutShortInsteadOfLeavingTheVoiceBehind() {
        me.piitex.engine.ui.containers.ConsoleLog console = put(new me.piitex.engine.ui.containers.ConsoleLog(300, 200), 10, 10);
        for (int i = 0; i < 40; i++) console.info("line " + i);
        assertEquals(5, heard.size(), heard.toString());
        assertEquals(40, console.getLineCount(), "every line is still in the log");
    }

    private me.piitex.engine.ui.menu.MenuBarOverlay menuBar(boolean[] grid) {
        me.piitex.engine.ui.menu.MenuBarOverlay bar = put(new me.piitex.engine.ui.menu.MenuBarOverlay(400), 0, 0);
        bar.setUseNativeMenuBar(false);
        bar.addMenu("File",
                new me.piitex.engine.ui.menu.MenuItem("New", () -> { }),
                new me.piitex.engine.ui.menu.MenuItem("Open", () -> { }),
                me.piitex.engine.ui.menu.MenuItem.separator(),
                me.piitex.engine.ui.menu.MenuItem.check("Show grid", grid[0], on -> grid[0] = on),
                me.piitex.engine.ui.menu.MenuItem.submenu("Recent",
                        new me.piitex.engine.ui.menu.MenuItem("A.txt", () -> { }),
                        new me.piitex.engine.ui.menu.MenuItem("B.txt", () -> { })),
                new me.piitex.engine.ui.menu.MenuItem("Quit", () -> { }));
        bar.addMenu("Edit", new me.piitex.engine.ui.menu.MenuItem("Undo", () -> { }));
        return bar;
    }

    @Test
    void theMenuBarIsATabStopThatReadsEachTitleAndOpensAMenuFromTheKeyboard() {
        boolean[] grid = {true};
        me.piitex.engine.ui.menu.MenuBarOverlay bar = menuBar(grid);
        window.requestFocus(bar);
        assertTrue(bar.isFocusTraversable());
        assertFalse(bar.grabsKeyboard());
        assertEquals(AccessibleRole.MENU, bar.getAccessibleRole());
        assertEquals("File, 1 of 2", bar.getAccessibleValue());

        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals("MENU Edit, 2 of 2", heard.getLast());
        key(GLFW.GLFW_KEY_LEFT);
        assertEquals("MENU File, 1 of 2", heard.getLast());

        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("announce File menu, 5 items. New, 1 of 5", heard.getLast());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("announce Open, 2 of 5", heard.getLast());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("announce Show grid, checked, 3 of 5", heard.getLast());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("announce Recent, submenu, 4 of 5", heard.getLast());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals("announce A.txt, 1 of 2", heard.getLast());
        key(GLFW.GLFW_KEY_LEFT);
        assertEquals("announce Recent, submenu, 4 of 5", heard.getLast());
        key(GLFW.GLFW_KEY_ESCAPE);
        assertEquals("announce Menu closed", heard.getLast());
        assertSame(bar, window.getFocusedElement(), "the bar keeps the keyboard so it can be reopened");
    }

    @Test
    void choosingACheckItemSaysWhereItEndedUp() {
        boolean[] grid = {true};
        me.piitex.engine.ui.menu.MenuBarOverlay bar = menuBar(grid);
        window.requestFocus(bar);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_ENTER);
        assertFalse(grid[0]);
        assertEquals("announce Show grid, not checked", heard.getLast());
    }

    @Test
    void movingSidewaysInAnOpenMenuNamesTheNextMenu() {
        me.piitex.engine.ui.menu.MenuBarOverlay bar = menuBar(new boolean[]{false});
        window.requestFocus(bar);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_LEFT); // nothing to leave, so it moves to the neighbouring menu
        window.getPopup().update(0f);
        assertEquals("announce Edit menu, 1 item", heard.getLast());
    }

    @Test
    void aContextMenuSaysItOpenedWithoutANameToGoWith() {
        me.piitex.engine.ui.menu.PopupMenu menu = new me.piitex.engine.ui.menu.PopupMenu(
                new me.piitex.engine.ui.menu.MenuItem("Copy", () -> { }),
                new me.piitex.engine.ui.menu.MenuItem("Paste", () -> { }));
        menu.show(window, 100, 100);
        window.getPopup().update(0f);
        assertEquals("announce Menu, 2 items", heard.getLast());
    }

    @Test
    void aDatePickerReadsItsDateOpensOnTheKeyboardAndReadsEachDayItMovesTo() {
        me.piitex.engine.ui.overlays.DateTimePickerOverlay picker = put(new me.piitex.engine.ui.overlays.DateTimePickerOverlay(150, 30), 10, 100);
        picker.setDate(java.time.LocalDate.of(2026, 11, 14));
        window.requestFocus(picker);
        assertEquals(AccessibleRole.COMBO_BOX, picker.getAccessibleRole());
        assertEquals("Date", picker.getAccessibleName());
        assertEquals("2026-11-14", picker.getAccessibleValue());

        key(GLFW.GLFW_KEY_DOWN);
        assertTrue(picker.isOpen());
        assertTrue(heard.getLast().startsWith("announce Calendar. "), heard.getLast());
        assertTrue(heard.getLast().contains("2026") && heard.getLast().contains("selected"), heard.getLast());
        key(GLFW.GLFW_KEY_RIGHT);
        assertTrue(heard.getLast().startsWith("announce ") && heard.getLast().contains("15"), heard.getLast());
        assertFalse(heard.getLast().contains("selected"), "the 15th is not the chosen day yet");
        key(GLFW.GLFW_KEY_ENTER);
        assertFalse(picker.isOpen());
        assertEquals("COMBO_BOX 2026-11-15", heard.getLast(), "closing says what the box holds");
    }

    @Test
    void aTimePickerReadsTheColumnItIsOnAndItsValue() {
        me.piitex.engine.ui.overlays.DateTimePickerOverlay picker = put(new me.piitex.engine.ui.overlays.DateTimePickerOverlay(
                150, 30, me.piitex.engine.ui.overlays.DateTimePickerOverlay.Mode.TIME), 10, 100);
        picker.setTime(java.time.LocalTime.of(14, 30));
        window.requestFocus(picker);
        key(GLFW.GLFW_KEY_ENTER);
        assertTrue(heard.getLast().startsWith("announce Clock. Hours, 14."), heard.getLast());
        key(GLFW.GLFW_KEY_UP);
        assertEquals("announce Hours, 15", heard.getLast());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals("announce Minutes, 30", heard.getLast());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("announce Minutes, 29", heard.getLast());
    }

    @Test
    void aDatePickerSaysWhenWhatWasTypedIsNotADate() {
        me.piitex.engine.ui.overlays.DateTimePickerOverlay picker = put(new me.piitex.engine.ui.overlays.DateTimePickerOverlay(150, 30), 10, 100);
        window.requestFocus(picker);
        assertEquals("No date chosen", picker.getAccessibleValue());
        picker.onCharTyped('x');
        key(GLFW.GLFW_KEY_ENTER);
        assertTrue(heard.getLast().startsWith("announce Not a valid date. It should look like "), heard.getLast());
    }

    @Test
    void aDialogIntroducesItselfAndTellsYouWhenItClosed() {
        me.piitex.engine.ui.containers.MessageBox.alert(window, "Save changes?", "Your work will be lost.");
        assertEquals("announce Save changes? dialog. Your work will be lost. Tab moves through it, Escape closes it.", heard.getLast());
        key(GLFW.GLFW_KEY_ESCAPE);
        assertEquals("announce Dialog closed", heard.getLast());
    }

    @Test
    void aPromptLetsTheFieldBeReadFirstThenSaysWhatTheDialogIs() {
        me.piitex.engine.ui.containers.MessageBox.prompt(window, "Rename", "Enter a new name", "old", name -> { });
        assertEquals("announce Rename dialog. Enter a new name. Tab moves through it, Escape closes it.", heard.getLast());
        assertEquals(1, heard.stream().filter(h -> h.contains("dialog")).count(), "said once, not before and after the field");
    }

    @Test
    void aStatusBarReadsEachNewMessageOnceButNotTheSameOneAgain() {
        me.piitex.engine.ui.containers.StatusBar status = put(new me.piitex.engine.ui.containers.StatusBar(400), 0, 500);
        status.setMessage("Ready");
        status.setMessage("Ready");
        status.setMessage("Saved");
        assertEquals(List.of("announce Ready", "announce Saved"), heard);
        status.setAnnounceMessages(false);
        status.setMessage("Quiet");
        assertEquals(2, heard.size());
    }

    @Test
    void anIconButtonIsNamedByItsTooltipAndRadioButtonsSayWhereTheyAre() {
        me.piitex.engine.ui.containers.ToolBar bar = put(new me.piitex.engine.ui.containers.ToolBar(300), 0, 0);
        me.piitex.engine.ui.overlays.ButtonOverlay save = bar.addButton(null, "Save the file", () -> { });
        assertEquals("Save the file", save.getAccessibleName());

        me.piitex.engine.ui.overlays.RadioGroup group = new me.piitex.engine.ui.overlays.RadioGroup();
        me.piitex.engine.ui.overlays.RadioButtonOverlay a = put(new me.piitex.engine.ui.overlays.RadioButtonOverlay(), 10, 50);
        me.piitex.engine.ui.overlays.RadioButtonOverlay b = put(new me.piitex.engine.ui.overlays.RadioButtonOverlay(), 10, 80);
        group.add(a);
        group.add(b);
        assertEquals("2 of 2", b.getAccessibleDescription());
    }

    @Test
    void aSplitDividerIsATabStopThatTheArrowKeysMoveAndRead() {
        me.piitex.engine.ui.containers.SplitContainer split = put(new me.piitex.engine.ui.containers.SplitContainer(
                me.piitex.engine.ui.containers.SplitContainer.Orientation.HORIZONTAL, 400, 200), 10, 10);
        split.setFirst(new Container(10, 10));
        split.setSecond(new Container(10, 10));
        Element divider = split.getElements().get(1);
        assertTrue(divider.isFocusTraversable());
        window.requestFocus(divider);
        assertEquals(AccessibleRole.SLIDER, divider.getAccessibleRole());
        assertEquals("50 percent", divider.getAccessibleValue());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals("SLIDER 52 percent", heard.getLast());
        key(GLFW.GLFW_KEY_HOME);
        assertTrue(heard.getLast().startsWith("SLIDER ") && heard.getLast().endsWith(" percent"), heard.getLast());
        assertTrue(split.getDividerPosition() < 0.2, "the first pane stops at its smallest size");
    }

    @Test
    void aSpinnerSaysWhenTheWaitIsOver() {
        me.piitex.engine.ui.overlays.LoadingSpinnerOverlay spinner = put(new me.piitex.engine.ui.overlays.LoadingSpinnerOverlay(), 10, 10);
        assertEquals(AccessibleRole.PROGRESS_BAR, spinner.getAccessibleRole());
        assertEquals("Please wait", spinner.getAccessibleValue());
        spinner.setSpinning(false);
        assertEquals("announce Done loading", heard.getLast());
        assertEquals("Done", spinner.getAccessibleValue());
    }
}
