package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.color.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One-line dialogs over a {@link ModalContainer}: tell the user something, ask yes or no, ask for a line of text, or ask
 * which of a few buttons.
 * <pre>{@code
 * MessageBox.alert(window, "Saved", "Your game was saved to slot 2.");
 * MessageBox.confirm(window, "Delete save?", "This cannot be undone.", yes -> { if (yes) delete(slot); });
 * MessageBox.prompt(window, "Rename", "New name for the city:", city.name(), name -> { if (name != null) city.rename(name); });
 * MessageBox.choose(window, "Unsaved changes", "Save before closing?", List.of("Save", "Discard", "Cancel"), i -> handle(i));
 * }</pre>
 * Each shows the dialog at once and returns the {@link ModalContainer}, in case it needs to be closed from code or
 * restyled; the callback runs once, when the dialog closes. Dismissing it with Escape or a click outside counts as the
 * "no" answer: {@code false} for {@link #confirm}, {@code null} for {@link #prompt}, -1 for {@link #choose}. Like any
 * modal it blocks the rest of the UI until it is closed, and dialogs opened from a callback stack on top.
 * <p>
 * The panel is a themed {@link VerticalLayout} 400 points wide with the title, the message (wrapped), an optional text
 * field and a row of buttons on the right, available as {@link ModalContainer#getContent()}. For anything with different
 * contents, build a {@link ModalContainer} directly.
 */
public final class MessageBox {
    private static final double WIDTH = 400, PADDING = 20, INNER = WIDTH - 2 * PADDING;

    private MessageBox() {
    }

    /**
     * A message with one OK button.
     */
    public static ModalContainer alert(Window window, String title, String message) {
        return alert(window, title, message, null);
    }

    /**
     * A message with one OK button; {@code onClose} runs when the dialog closes, however it is dismissed.
     */
    public static ModalContainer alert(Window window, String title, String message, Runnable onClose) {
        return show(window, alertDialog(title, message, onClose), null);
    }

    /**
     * A question with OK and Cancel. {@code result} gets true for OK, false for Cancel or a dismissal.
     */
    public static ModalContainer confirm(Window window, String title, String message, Consumer<Boolean> result) {
        return confirm(window, title, message, "OK", "Cancel", result);
    }

    /**
     * As {@link #confirm(Window, String, String, Consumer)} with your own button labels, such as "Delete" and "Keep".
     */
    public static ModalContainer confirm(Window window, String title, String message, String yesLabel, String noLabel,
                                         Consumer<Boolean> result) {
        return show(window, confirmDialog(title, message, yesLabel, noLabel, result), null);
    }

    /**
     * Asks for a line of text, starting as {@code initial}, with OK and Cancel. Enter in the field is OK. {@code result} gets
     * the text, or null for Cancel or a dismissal.
     */
    public static ModalContainer prompt(Window window, String title, String message, String initial, Consumer<String> result) {
        TextFieldOverlay field = new TextFieldOverlay(INNER, 34);
        return show(window, promptDialog(title, message, initial, field, result), field);
    }

    /**
     * A message with your own buttons, left to right. {@code result} gets the index of the one pressed, or -1 for a
     * dismissal.
     */
    public static ModalContainer choose(Window window, String title, String message, List<String> buttons, IntConsumer result) {
        return show(window, chooseDialog(title, message, buttons, result), null);
    }

    static ModalContainer alertDialog(String title, String message, Runnable onClose) {
        return build(title, message, null, List.of("OK"), index -> {
            if (onClose != null) onClose.run();
        });
    }

    static ModalContainer confirmDialog(String title, String message, String yesLabel, String noLabel, Consumer<Boolean> result) {
        return build(title, message, null, List.of(noLabel, yesLabel), index -> {
            if (result != null) result.accept(index == 1);
        });
    }

    static ModalContainer promptDialog(String title, String message, String initial, TextFieldOverlay field, Consumer<String> result) {
        field.setText(initial == null ? "" : initial);
        return build(title, message, field, List.of("Cancel", "OK"), index -> {
            if (result != null) result.accept(index == 1 ? field.getText() : null);
        });
    }

    static ModalContainer chooseDialog(String title, String message, List<String> buttons, IntConsumer result) {
        if (buttons == null || buttons.isEmpty()) throw new IllegalArgumentException("a message box needs at least one button");
        return build(title, message, null, buttons, result == null ? index -> {
        } : result);
    }

    private static ModalContainer show(Window window, ModalContainer modal, TextFieldOverlay field) {
        if (field == null) {
            modal.show(window);
        } else {
            // The field takes the keyboard and is read first; the dialog's words follow rather than being cut off by it.
            modal.show(window, false);
            window.requestFocus(field);
            window.announce(modal.openingText(), false);
        }
        return modal;
    }

    /**
     * Builds the dialog without showing it.
     */
    static ModalContainer build(String title, String message, TextFieldOverlay field, List<String> labels, IntConsumer result) {
        VerticalLayout panel = new VerticalLayout(WIDTH, 0);
        panel.setPadding((float) PADDING);
        panel.setSpacing(14f);
        panel.setSizeToContent(false, true);
        panel.useTheme();

        if (title != null && !title.isEmpty()) {
            TextOverlay heading = new TextOverlay(title, 20f, Color.BLACK);
            heading.setBold(true);
            heading.setMaxWidth(INNER);
            panel.addElement(heading);
        }
        if (message != null && !message.isEmpty()) {
            TextFlowOverlay body = new TextFlowOverlay(message, INNER, 16f, Color.BLACK);
            body.setAutoHeight(true);
            panel.addElement(body);
        }
        if (field != null) panel.addElement(field);

        HorizontalLayout row = new HorizontalLayout(INNER, 36);
        row.setSpacing(10f);
        row.setAlignment(Layout.Alignment.CENTER_RIGHT);

        ModalContainer modal = new ModalContainer(panel);
        if (title != null && !title.isEmpty()) modal.setAccessibleName(title);
        if (message != null && !message.isEmpty()) modal.setAccessibleDescription(message);
        boolean[] answered = {false};
        IntConsumer answer = index -> {
            if (answered[0]) return;
            answered[0] = true;
            modal.close();
            result.accept(index);
        };
        List<String> buttons = new ArrayList<>(labels);
        for (int i = 0; i < buttons.size(); i++) {
            int index = i;
            String label = buttons.get(i) == null ? "" : buttons.get(i);
            ButtonOverlay button = new ButtonOverlay(label, 16f, Color.BLACK, Math.max(90, label.length() * 9 + 36), 36);
            button.onAction(() -> answer.accept(index));
            row.addElement(button);
        }
        panel.addElement(row);

        if (field != null) field.onSubmit(text -> answer.accept(buttons.size() - 1));
        // Escape or a click outside: the "no" answer.
        modal.onClose(() -> {
            if (answered[0]) return;
            answered[0] = true;
            result.accept(-1);
        });
        return modal;
    }
}
