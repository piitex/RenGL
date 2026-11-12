package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.SpacerOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

/**
 * The strip along the bottom of a window that says what is going on: a message on the left ("Ready", "3 files selected")
 * and small readouts at the sides (a cursor position, a zoom level, a progress bar).
 * <pre>{@code
 * StatusBar status = new StatusBar(800);
 * status.setMessage("Ready");
 * TextOverlay position = status.addRightText("Ln 1, Col 1");
 * status.addRight(new ProgressBarOverlay(120, 10));
 * // later
 * position.setText("Ln 12, Col 4");
 * status.setMessage("Saved");
 * }</pre>
 * Left of the middle go the {@linkplain #setMessage message} and anything added with {@link #addLeft} or {@link
 * #addLeftText}; right of it, in order, go {@link #addRight} and {@link #addRightText}. Thin dividers are drawn between
 * neighbours on the same side. Text segments are ordinary {@link TextOverlay}s, returned so they can be updated, and any
 * other widget can be added. It is a {@link HorizontalLayout}, so it follows the theme like a {@link Container} that {@link
 * #useTheme() uses the theme}.
 */
public class StatusBar extends HorizontalLayout {
    private static final float FONT_SIZE = 14f;

    private final SpacerOverlay middle = new SpacerOverlay();
    private TextOverlay message;
    private int leftCount;  // children before the spacer, dividers included
    private boolean announceMessages = true;
    private int rightCount; // children after the spacer, dividers included

    public StatusBar(double width) {
        this(width, 28);
    }

    public StatusBar(double width, double height) {
        super(width, height);
        setPadding(6f);
        setSpacing(8f);
        setAlignment(Layout.Alignment.CENTER_LEFT);
        useTheme();
        addElement(middle);
    }

    /**
     * The message at the far left, replacing the last one. Null or empty clears it.
     */
    public void setMessage(String text) {
        if (message == null) {
            if (text == null || text.isEmpty()) return;
            message = text(text);
            insertElement(message, 0);
            leftCount++;
            sayMessage(text);
            return;
        }
        String previous = message.getText();
        message.setText(text == null ? "" : text);
        if (text != null && !text.equals(previous)) sayMessage(text);
    }

    /**
     * Whether a changed {@link #setMessage message} is read out by a screen reader. On by default, so "Saved" is heard
     * without the user going to look for it. The readouts added at the sides are not read; they change too often.
     */
    public void setAnnounceMessages(boolean announce) {
        this.announceMessages = announce;
    }

    public boolean isAnnounceMessages() {
        return announceMessages;
    }

    private void sayMessage(String text) {
        if (!announceMessages || text.isEmpty()) return;
        me.piitex.engine.Window window = getWindow();
        if (window != null) window.announce(text);
    }

    public String getMessage() {
        return message == null ? "" : message.getText();
    }

    /**
     * A readout after the message and the other left ones, divided from the one before it. Returns it to update later.
     */
    public TextOverlay addLeftText(String text) {
        TextOverlay segment = text(text);
        addLeft(segment);
        return segment;
    }

    /**
     * Any widget after the message and the other left ones.
     */
    public void addLeft(Element element) {
        if (leftCount > 0) {
            insertElement(divider(), leftCount);
            leftCount++;
        }
        insertElement(element, leftCount);
        leftCount++;
    }

    /**
     * A readout at the right, after the ones already there, divided from the one before it. Returns it to update later.
     */
    public TextOverlay addRightText(String text) {
        TextOverlay segment = text(text);
        addRight(segment);
        return segment;
    }

    /**
     * Any widget at the right, after the ones already there.
     */
    public void addRight(Element element) {
        if (rightCount > 0) {
            addElement(divider());
            rightCount++;
        }
        addElement(element);
        rightCount++;
    }

    private static TextOverlay text(String text) {
        return new TextOverlay(text == null ? "" : text, FONT_SIZE, Color.BLACK);
    }

    private SeparatorOverlay divider() {
        return new SeparatorOverlay(Math.max(8, getHeight() - 14), SeparatorOverlay.Orientation.VERTICAL);
    }
}
