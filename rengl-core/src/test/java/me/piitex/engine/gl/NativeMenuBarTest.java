package me.piitex.engine.gl;

import me.piitex.engine.ui.menu.PopupMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** With several windows each offering a menu bar, the system menu bar follows the one with the focus. */
class NativeMenuBarTest {
    private final Object a = new Object(), b = new Object();

    // A window's menus are the same objects frame after frame, as a MenuBarOverlay's are.
    private final List<PopupMenu> menusA = List.of(new PopupMenu().add("Item", () -> { }));
    private final List<PopupMenu> menusB = List.of(new PopupMenu().add("Item", () -> { }));

    @AfterEach
    void clear() {
        NativeMenuBar.release(a);
        NativeMenuBar.release(b);
        NativeMenuBar.clearPending();
    }

    private void offer(Object owner, boolean focused, String title) {
        NativeMenuBar.submit(owner, focused, List.of(title), owner == a ? menusA : menusB);
    }

    @Test
    void theFocusedWindowsBarIsTheOneShown() {
        Assumptions.assumeTrue(NativeMenuBar.isSupported(), "macOS only");
        offer(a, true, "A");
        offer(b, false, "B");
        assertEquals(List.of("A"), NativeMenuBar.pendingTitles());

        offer(a, false, "A");
        offer(b, true, "B"); // the user clicked the other window
        assertEquals(List.of("B"), NativeMenuBar.pendingTitles());

        NativeMenuBar.clearPending();
        offer(a, false, "A");
        offer(b, true, "B");
        assertNull(NativeMenuBar.pendingTitles(), "nothing changed, nothing is rebuilt");
    }

    @Test
    void theLastShownBarStaysWhileNoWindowIsFocused() {
        Assumptions.assumeTrue(NativeMenuBar.isSupported(), "macOS only");
        offer(a, false, "A");
        offer(b, true, "B");
        offer(b, false, "B"); // the application went to the background
        offer(a, false, "A");
        assertEquals(List.of("B"), NativeMenuBar.pendingTitles());
    }

    @Test
    void closingTheShownWindowHandsTheBarToTheOther() {
        Assumptions.assumeTrue(NativeMenuBar.isSupported(), "macOS only");
        offer(a, false, "A");
        offer(b, true, "B");
        NativeMenuBar.release(b);
        assertEquals(List.of("A"), NativeMenuBar.pendingTitles());
    }

    @Test
    void aChangedMenuOfTheShownBarIsRebuilt() {
        Assumptions.assumeTrue(NativeMenuBar.isSupported(), "macOS only");
        offer(a, true, "A");
        NativeMenuBar.clearPending();
        offer(a, true, "A2");
        assertEquals(List.of("A2"), NativeMenuBar.pendingTitles());
    }
}
