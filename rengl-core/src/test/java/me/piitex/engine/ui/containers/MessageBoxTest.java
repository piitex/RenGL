package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageBoxTest {
    private static List<ButtonOverlay> buttons(ModalContainer modal) {
        List<ButtonOverlay> found = new ArrayList<>();
        for (Element child : modal.getContent().getElements().values()) {
            if (child instanceof HorizontalLayout row) {
                for (Element button : row.getElements().values()) found.add((ButtonOverlay) button);
            }
        }
        return found;
    }

    private static void press(ButtonOverlay button) {
        button.getMouseClickListenerConsumer().accept(new MouseClickEvent(1, 1, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
    }

    @Test
    void alertHasOneOkButtonAndReportsWhenItCloses() {
        int[] closed = {0};
        ModalContainer modal = MessageBox.alertDialog("Saved", "Your game was saved.", () -> closed[0]++);
        List<ButtonOverlay> buttons = buttons(modal);
        assertEquals(1, buttons.size());
        assertEquals("OK", buttons.getFirst().getText());
        press(buttons.getFirst());
        assertEquals(1, closed[0]);
        modal.notifyClosed(); // the close that follows must not report again
        assertEquals(1, closed[0]);
    }

    @Test
    void confirmGivesTrueForTheSecondButtonAndFalseForTheFirst() {
        List<Boolean> answers = new ArrayList<>();
        ModalContainer yes = MessageBox.confirmDialog("Delete?", "Sure?", "Delete", "Keep", answers::add);
        List<ButtonOverlay> buttons = buttons(yes);
        assertEquals(List.of("Keep", "Delete"), buttons.stream().map(ButtonOverlay::getText).toList());
        press(buttons.get(1));
        assertEquals(List.of(true), answers);

        ModalContainer no = MessageBox.confirmDialog("Delete?", "Sure?", "Delete", "Keep", answers::add);
        press(buttons(no).get(0));
        assertEquals(List.of(true, false), answers);
    }

    @Test
    void dismissingAConfirmIsNo() {
        List<Boolean> answers = new ArrayList<>();
        ModalContainer modal = MessageBox.confirmDialog("Delete?", "Sure?", "OK", "Cancel", answers::add);
        modal.notifyClosed(); // Escape or a click outside
        assertEquals(List.of(false), answers);
        modal.notifyClosed();
        assertEquals(1, answers.size(), "only the first answer counts");
    }

    @Test
    void aPressedButtonIsNotAlsoADismissal() {
        List<Integer> answers = new ArrayList<>();
        ModalContainer modal = MessageBox.chooseDialog("Close", "Save first?", List.of("Save", "Discard", "Cancel"), answers::add);
        press(buttons(modal).get(1));
        modal.notifyClosed();
        assertEquals(List.of(1), answers);
    }

    @Test
    void chooseNumbersTheButtonsLeftToRightAndDismissalIsMinusOne() {
        List<Integer> answers = new ArrayList<>();
        ModalContainer modal = MessageBox.chooseDialog("Close", "Save first?", List.of("Save", "Discard", "Cancel"), answers::add);
        assertEquals(3, buttons(modal).size());
        modal.notifyClosed();
        assertEquals(List.of(-1), answers);
    }

    @Test
    void chooseNeedsAButton() {
        assertThrows(IllegalArgumentException.class, () -> MessageBox.chooseDialog("t", "m", List.of(), i -> {
        }));
    }

    @Test
    void promptReturnsTheFieldTextForOkAndNullOtherwise() {
        List<String> answers = new ArrayList<>();
        TextFieldOverlay field = new TextFieldOverlay(340, 34);
        ModalContainer modal = MessageBox.promptDialog("Rename", "New name:", "Old", field, answers::add);
        assertEquals("Old", field.getText());
        field.setText("New");
        press(buttons(modal).get(1));
        assertEquals(List.of("New"), answers);

        TextFieldOverlay other = new TextFieldOverlay(340, 34);
        ModalContainer cancelled = MessageBox.promptDialog("Rename", "New name:", null, other, answers::add);
        assertEquals("", other.getText());
        press(buttons(cancelled).get(0));
        assertEquals(java.util.Arrays.asList("New", null), answers);
    }

    @Test
    void theDialogSizesItselfToItsContents() {
        ModalContainer small = MessageBox.alertDialog("Hi", "Short.", null);
        ModalContainer big = MessageBox.alertDialog("Hi", "A long message. ".repeat(30), null);
        assertEquals(400, small.getContent().getWidth(), 0.001);
        assertTrue(big.getContent().getHeight() > small.getContent().getHeight() + 40,
                big.getContent().getHeight() + " vs " + small.getContent().getHeight());
    }
}
