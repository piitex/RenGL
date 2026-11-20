package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Controls in a table's cells: made only for the rows on screen, reused as it scrolls, and pressed through the window. */
class TableCellWidgetTest {
    private static final class Track {
        final String name;
        double volume;

        Track(String name, double volume) {
            this.name = name;
            this.volume = volume;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final float HEADER = 28, ROW = 30;

    private final Window window = new Window(new WindowOptions("test").setDimensions(600, 400));
    private final InputProcessor processor = new InputProcessor();
    private final List<Track> tracks = new ArrayList<>();
    private final List<String> clicks = new ArrayList<>();
    private TableView<Track> table;
    private TableView.Column<Track> name, volume, play;
    private int slidersMade, buttonsMade, binds;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < 100; i++) tracks.add(new Track("track " + i, i));
        table = new TableView<>(400, 200);
        table.setRowHeight(ROW);
        name = table.addColumn("Name", t -> t.name).setWidth(120);
        volume = table.addColumn("Volume", t -> String.valueOf(t.volume)).setWidth(160)
                .setCellWidget(() -> {
                    slidersMade++;
                    return new SliderOverlay(100, 0, 100);
                }, (slider, track) -> {
                    binds++;
                    slider.setValue(track.volume);
                    slider.onValueChange(v -> track.volume = v);
                });
        play = table.addColumn("Play", t -> "").setWidth(100).setSortable(false)
                .setCellWidget(() -> {
                    buttonsMade++;
                    ButtonOverlay button = new ButtonOverlay("Play", 60, 22);
                    button.setClickOnRelease(false);
                    return button;
                }, (button, track) -> button.onMouseClick(_ -> clicks.add(track.name)));
        table.setItems(tracks);
        window.addContainer(table);
        table.update(0);
    }

    private void send(Runnable... steps) {
        for (Runnable step : steps) step.run();
        processor.processEvents(window);
        table.update(0);
    }

    private Runnable move(double x, double y) {
        return () -> window.getInputManager().onCursorPos(0, x, y);
    }

    private Runnable press() {
        return () -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
    }

    private Runnable release() {
        return () -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
    }

    private Runnable key(int key) {
        return () -> {
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, 0);
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, 0);
        };
    }

    private static double rowMiddle(int row) {
        return HEADER + row * ROW + ROW / 2;
    }

    private Element slider(int row) {
        return table.getCellWidget(tracks.get(row), volume);
    }

    @Test
    void onlyTheRowsOnScreenGetControls() {
        int onScreen = (int) Math.ceil((200 - HEADER) / ROW) + 1; // a part row at the bottom counts
        assertTrue(slidersMade <= onScreen, "made " + slidersMade);
        assertEquals(slidersMade, buttonsMade);
        assertNotNull(slider(0));
        assertNull(slider(50), "row 50 is far below the table");
        assertNull(table.getCellWidget(tracks.get(0), name), "the Name column shows text");
    }

    @Test
    void scrollingHandsTheSameControlsToOtherRows() {
        int made = slidersMade;
        table.scrollTo(0, 50 * ROW);
        table.update(0);
        assertEquals(made, slidersMade, "nothing new is made, the old controls move down");
        assertNull(slider(0));
        Element moved = slider(52);
        assertNotNull(moved);
        assertEquals(52, ((SliderOverlay) moved).getValue(), 1e-9, "bound to its new row");
    }

    @Test
    void aSortMovesAControlWithItsItemWithoutBindingItAgain() {
        Element first = slider(0);
        double y = first.getY();
        int before = binds;
        table.sortBy(name, false); // "track 99" first, "track 0" last
        table.update(0);
        assertNull(slider(0), "track 0 sorted to the bottom, off screen");
        table.scrollTo(0, 0);
        table.sortBy(name, true);
        table.update(0);
        assertEquals(y, slider(0).getY(), 1e-9);
        assertTrue(binds >= before, "binding is allowed, just never to the wrong row");
        assertEquals(0, ((SliderOverlay) slider(0)).getValue(), 1e-9);
    }

    @Test
    void refreshBindsTheControlsOnScreenAgain() {
        tracks.get(1).volume = 77;
        int before = binds;
        table.refresh();
        table.update(0);
        assertTrue(binds > before);
        assertEquals(77, ((SliderOverlay) slider(1)).getValue(), 1e-9);
    }

    @Test
    void pressingAButtonInACellClicksItAndSelectsItsRow() {
        Element button = table.getCellWidget(tracks.get(2), play);
        double x = button.getAbsoluteX() + button.getWidth() / 2, y = button.getAbsoluteY() + button.getHeight() / 2;
        assertEquals(rowMiddle(2), y, 1);
        send(move(x, y), press(), release());
        assertEquals(List.of("track 2"), clicks);
        assertEquals(tracks.get(2), table.getSelected());
    }

    @Test
    void draggingASliderInACellChangesItsItemAndSelectsTheRow() {
        SliderOverlay slider = (SliderOverlay) slider(3);
        double y = slider.getAbsoluteY() + slider.getHeight() / 2;
        send(move(slider.getAbsoluteX() + 5, y), press(), move(slider.getAbsoluteX() + slider.getWidth() - 1, y), release());
        assertEquals(tracks.get(3), table.getSelected());
        assertTrue(tracks.get(3).volume > 90, "volume " + tracks.get(3).volume);
    }

    @Test
    void aHeldControlKeepsItsItemWhenItsRowScrollsAway() {
        SliderOverlay slider = (SliderOverlay) slider(0);
        send(move(slider.getAbsoluteX() + 5, slider.getAbsoluteY() + slider.getHeight() / 2), press());
        table.scrollTo(0, 40 * ROW);
        table.update(0);
        assertSame(slider, slider(0), "still held");
        send(release());
        assertNull(slider(0), "let go, it goes back to the pool");
    }

    @Test
    void theHeaderTakesPressesOverAControlScrolledUnderIt() {
        table.scrollTo(0, 15); // row 0's button is half under the header now
        table.update(0);
        Element button = table.getCellWidget(tracks.get(0), play);
        double x = button.getAbsoluteX() + button.getWidth() / 2, y = HEADER - 3;
        assertTrue(button.contains(x, y), "the button reaches up under the header");
        send(move(x, y), press(), release());
        assertTrue(clicks.isEmpty());
    }

    @Test
    void cellControlsAreNotTabStops() {
        assertFalse(slider(0).isFocusTraversable());
        assertFalse(table.getCellWidget(tracks.get(0), play).isFocusTraversable());
    }

    @Test
    void f2MovesTheKeyboardIntoTheRowAndEscapeBringsItBack() {
        table.setSelected(tracks.get(1));
        window.requestFocus(table);
        send(key(GLFW.GLFW_KEY_F2));
        assertSame(slider(1), window.getFocusedElement());

        table.scrollTo(0, 60 * ROW);
        table.update(0);
        assertSame(window.getFocusedElement(), slider(1), "a control with the keyboard is kept off screen");

        send(key(GLFW.GLFW_KEY_ESCAPE));
        assertSame(table, window.getFocusedElement());
        assertNull(slider(1));
    }

    @Test
    void aControlWhoseItemLeavesIsTakenOutAndTheTableGetsTheKeyboard() {
        table.setSelected(tracks.get(0));
        window.requestFocus(table);
        send(key(GLFW.GLFW_KEY_F2));
        Element focused = window.getFocusedElement();
        assertSame(slider(0), focused);
        tracks.removeFirst();
        table.refresh();
        table.update(0);
        assertNotSame(focused, window.getFocusedElement());
        assertSame(table, window.getFocusedElement());
    }

    @Test
    void turningTheControlsOffGoesBackToText() {
        volume.setCellWidget(null, null);
        table.update(0);
        assertNull(slider(0));
        assertEquals(tracks.size(), table.getItems().size());
    }
}
