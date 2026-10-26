package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComboBoxOverlayTest {
    /** A popup host with no window: one popup at a time, a focus slot, an 800 by 600 area. */
    private static final class Host implements PopupHost {
        Container popup;
        Runnable onDismiss;
        Element focused;

        @Override
        public void showPopup(Container popup, Element anchor, Runnable onDismiss) {
            closePopup();
            this.popup = popup;
            this.onDismiss = onDismiss;
        }

        @Override
        public void closePopup() {
            if (popup == null) return;
            Runnable dismiss = onDismiss;
            popup = null;
            onDismiss = null;
            if (dismiss != null) dismiss.run();
        }

        @Override
        public Container getPopup() {
            return popup;
        }

        @Override
        public double[] getPopupArea() {
            return new double[]{0, 0, 800, 600};
        }

        @Override
        public Element getFocusedElement() {
            return focused;
        }

        @Override
        public void requestFocus(Element element) {
            focused = element;
        }
    }

    private Host host;
    private ComboBoxOverlay<String> combo;
    private final List<String> picked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host();
        Container root = new Container(800, 600);
        root.setPopupHost(host);
        combo = new ComboBoxOverlay<>(200, 30);
        combo.setPosition(10, 10);
        combo.setItems(List.of("Amsterdam", "Berlin", "Bern", "Boston", "Chicago"));
        combo.onItemSelect(picked::add);
        root.addElement(combo);
    }

    private void type(String text) {
        for (char ch : text.toCharArray()) combo.onCharTyped(ch);
    }

    private void key(int key) {
        combo.onKeyPressed(key, GLFW.GLFW_PRESS, 0);
    }

    @Test
    void typingOpensTheListNarrowedToTheMatches() {
        type("b");
        assertTrue(combo.isOpen());
        assertSame(host.getPopup(), host.popup);
        assertEquals(List.of("Berlin", "Bern", "Boston"), combo.getSuggestions());
        type("er");
        assertEquals(List.of("Berlin", "Bern"), combo.getSuggestions());
    }

    @Test
    void matchingIgnoresCaseAndLooksAnywhereInTheLabel() {
        type("CAGO");
        assertEquals(List.of("Chicago"), combo.getSuggestions());
    }

    @Test
    void aCustomMatcherReplacesTheDefault() {
        combo.setMatcher((item, typed) -> item.toLowerCase().startsWith(typed.toLowerCase()));
        type("e");
        assertFalse(combo.isOpen(), "no label starts with e");
        combo.setText("");
        type("b");
        assertEquals(List.of("Berlin", "Bern", "Boston"), combo.getSuggestions());
    }

    @Test
    void theListClosesWhenNothingMatches() {
        type("b");
        assertTrue(combo.isOpen());
        type("zz");
        assertFalse(combo.isOpen());
        assertNull(host.getPopup());
    }

    @Test
    void theListClosesWhenTheOnlyMatchIsWhatIsTyped() {
        type("Chicago");
        assertFalse(combo.isOpen());
    }

    @Test
    void downOpensTheListAndMovesTheHighlight() {
        key(GLFW.GLFW_KEY_DOWN);
        assertTrue(combo.isOpen());
        assertEquals(5, combo.getSuggestions().size());
        assertEquals(0, combo.getHighlightedIndex());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(2, combo.getHighlightedIndex());
        key(GLFW.GLFW_KEY_UP);
        assertEquals(1, combo.getHighlightedIndex());
        key(GLFW.GLFW_KEY_UP);
        key(GLFW.GLFW_KEY_UP);
        assertEquals(-1, combo.getHighlightedIndex(), "up past the first row leaves no row highlighted");
    }

    @Test
    void enterPicksTheHighlightedRow() {
        type("b");
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN); // Bern
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals("Bern", combo.getText());
        assertEquals(List.of("Bern"), picked);
        assertFalse(combo.isOpen());
        assertEquals("Bern", combo.getSelectedItem());
    }

    @Test
    void enterWithNoHighlightSubmitsTheTypedText() {
        List<String> submitted = new ArrayList<>();
        combo.onSubmit(submitted::add);
        type("be");
        key(GLFW.GLFW_KEY_ENTER);
        assertFalse(combo.isOpen());
        assertEquals(List.of("be"), submitted);
        assertTrue(picked.isEmpty());
        assertNull(combo.getSelectedItem(), "free text is not one of the items");
    }

    @Test
    void clickingARowPicksItAndGivesTheKeyboardBack() {
        type("b");
        Container popup = host.getPopup();
        // rows are as tall as the field, 30; the second row's middle
        popup.getMouseClickListenerConsumer().accept(new me.piitex.engine.ui.events.MouseClickEvent(
                popup.getX() + 20, popup.getY() + 30 + 15, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
        assertEquals("Bern", combo.getText());
        assertEquals(List.of("Bern"), picked);
        assertSame(combo, host.focused);
        assertFalse(combo.isOpen());
    }

    @Test
    void theListOpensBelowTheFieldAndFlipsAboveWhenThereIsNoRoom() {
        key(GLFW.GLFW_KEY_DOWN);
        Container popup = host.getPopup();
        assertEquals(10 + 30, popup.getY(), 0.001);
        assertEquals(10, popup.getX(), 0.001);
        assertEquals(200, popup.getWidth(), 0.001);
        assertEquals(5 * 30, popup.getHeight(), 0.001);

        combo.close();
        combo.setPosition(10, 560);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(560 - 5 * 30, host.getPopup().getY(), 0.001);
    }

    @Test
    void aLongListIsCappedAtTheMaxVisibleRows() {
        combo.setMaxVisibleRows(3);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(3 * 30, host.getPopup().getHeight(), 0.001);
    }

    @Test
    void escapeClosingTheHostsPopupLeavesTheFieldReadyToOpenAgain() {
        type("b");
        host.closePopup(); // what Escape does
        assertFalse(combo.isOpen());
        type("e");
        assertTrue(combo.isOpen());
    }

    @Test
    void openOnFocusShowsEveryItem() {
        combo.setOpenOnFocus(true);
        combo.onFocusGained();
        assertTrue(combo.isOpen());
        assertEquals(5, combo.getSuggestions().size());
    }

    @Test
    void losingFocusClosesTheList() {
        type("b");
        try (var loan = me.piitex.engine.input.MouseTracker.lend(700, 500, true)) { // tabbing away: the pointer is elsewhere
            combo.onFocusLost();
        }
        assertFalse(combo.isOpen());
        assertNull(host.getPopup());
    }

    @Test
    void clickingTheListTakesTheKeyboardButDoesNotCloseIt() {
        type("b");
        try (var loan = me.piitex.engine.input.MouseTracker.lend(50, 60, true)) { // over the list
            combo.onFocusLost();
        }
        assertTrue(combo.isOpen());
    }

    @Test
    void theSelectedItemFollowsTheText() {
        combo.setSelectedItem("Boston");
        assertEquals("Boston", combo.getText());
        assertEquals("Boston", combo.getSelectedItem());
        assertTrue(picked.isEmpty(), "setting it in code is silent");
        assertFalse(combo.isOpen());
        combo.setText("Bos");
        assertNull(combo.getSelectedItem());
        combo.setText("Berlin");
        assertEquals("Berlin", combo.getSelectedItem(), "text that equals a label names that item");
        combo.setSelectedItem(null);
        assertEquals("", combo.getText());
    }

    @Test
    void aLabelProviderShapesTheTextAndTheMatching() {
        ComboBoxOverlay<Integer> numbers = new ComboBoxOverlay<>(200, 30);
        numbers.setLabelProvider(n -> "No. " + n);
        numbers.setItems(List.of(1, 2, 12));
        Container root = new Container(800, 600);
        root.setPopupHost(host);
        root.addElement(numbers);
        for (char ch : "no. 1".toCharArray()) numbers.onCharTyped(ch);
        assertEquals(List.of(1, 12), numbers.getSuggestions());
    }

    @Test
    void withNoHostNothingOpensAndNothingFails() {
        ComboBoxOverlay<String> loose = new ComboBoxOverlay<>(200, 30);
        loose.setItems(List.of("a", "b"));
        loose.onCharTyped('a');
        assertFalse(loose.isOpen());
        loose.dispose();
    }
}
