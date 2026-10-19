package me.piitex.engine.ui.file;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.HashMap;
import java.util.Map;

/**
 * What a {@link UiProperty}'s tooltip says when its declaration didn't give one. Most properties mean the same on every
 * kind, such as a font size or a corner radius, so they are explained once here by name; a few mean something different
 * on a kind of their own and are explained for that kind. {@link UiKinds} fills them in when it registers a kind, so an
 * editor can show a tooltip for every property without each declaration having to carry one.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
final class UiTips {
    private static final Map<String, String> BY_NAME = new HashMap<>();
    private static final Map<String, String> BY_KIND = new HashMap<>();

    static {
        // Place and size
        name("x", "How far from the left edge of its container, in points");
        name("y", "How far from the top edge of its container, in points");
        name("width", "How wide it is, in points");
        name("height", "How tall it is, in points");
        name("fitWidth", "Make the layout as wide as what is in it, instead of the width set here");
        name("fitHeight", "Make the layout as tall as what is in it, instead of the height set here");
        name("spacing", "The gap between one item and the next, in points");
        name("padding", "The space kept clear inside the edge, in points");
        name("alignment", "Where the items sit across the layout when they are smaller than it");
        name("clipping", "Hide whatever sticks out past the edge of the container");

        // Look
        name("opacity", "How solid it is, from 0 (invisible) to 1 (solid). What is inside it fades too");
        name("tooltip", "Text shown beside the mouse when it rests on the element, as this one is");
        name("border", "The color of the line round the edge. A gradient or a rainbow works too");
        name("borderWidth", "How thick the line round the edge is, in points; 0 for none");
        name("cornerRadius", "How round the corners are, in points; 0 for square corners");
        name("tint", "Paints the picture over with a color. Not set, the picture is shown as it is");

        // Text
        name("text", "What it says");
        name("fontSize", "How big the letters are, in points");
        name("bold", "Draw the text in a heavier weight");
        name("italic", "Draw the text slanted");
        name("textColor", "The color of the text. A gradient or a rainbow works too");
        name("align", "Whether the lines sit to the left, the middle or the right");
        name("lineSpacing", "Extra space between lines, in points");
        name("placeholder", "Grey text shown while there is nothing in it, as a hint for what to put");
        name("placeholderColor", "The color of the placeholder text");
        name("autoHeight", "Make the element as tall as its text, instead of the height set here");
        name("maxLines", "The most lines shown, and an ellipsis is put on the last if there was more");
        name("wordWrap", "Start a new line at the edge instead of running past it");
        name("readOnly", "The text can be selected and copied but not changed");
        name("password", "Show dots in place of what is typed");
        name("rightAligned", "Push the text to the right edge of the field");
        name("caretColor", "The color of the blinking line where typing goes");
        name("selectionColor", "The color behind text that is selected");
        name("maxLength", "The most characters that can be typed");

        // Values
        name("min", "The lowest value");
        name("max", "The highest value");
        name("value", "The value now");
        name("step", "How much the value changes at a time; 0 for any amount");
        name("decimals", "How many digits are shown after the point");
        name("checked", "Whether the box is ticked");
        name("selected", "Whether this is the one chosen");
        name("on", "Whether the switch is on");
        name("progress", "How far along it is, from 0 (not started) to 1 (done)");
        name("indeterminate", "Show a bar that keeps moving, for when there is no way to say how far along something is");
        name("spinning", "Whether it turns");
        name("items", "The choices the list offers");
        name("color", "The color that is chosen");
        name("alpha", "Let the player choose how see-through the color is");
        name("mode", "Whether it picks a date, a time or both");
        name("use24Hour", "Show 14:30 instead of 2:30 PM");
        name("minuteStep", "The minutes can be chosen in steps this big, such as 15 for 00, 15, 30 and 45");

        // Colors of a control
        name("fillColor", "The color of the part that is filled in");
        name("trackColor", "The color of the bar the control moves along");
        name("thumbColor", "The color of the handle that is dragged");
        name("checkColor", "The color of the tick");
        name("dotColor", "The color of the dot in the middle when it is chosen");
        name("onColor", "The color of the switch while it is on");
        name("offColor", "The color of the switch while it is off");
        name("knobColor", "The color of the round handle");
        name("lineColor", "The color of the line");
        name("thickness", "How thick the line is, in points");

        // Scrolling, panes and tabs
        name("verticalScroll", "Let the contents be scrolled up and down");
        name("horizontalScroll", "Let the contents be scrolled left and right");
        name("scrollbar", "Draw the bar that shows where in the contents you are");
        name("scrollSpeed", "How far one turn of the mouse wheel scrolls, in points");
        name("showHandles", "Draw the grips on its corners and edges");
        name("scaleFonts", "Make the text grow and shrink with the container");
        name("minWidth", "It can't be made narrower than this, in points");
        name("minHeight", "It can't be made shorter than this, in points");
        name("minPaneSize", "Neither side can be dragged smaller than this, in points");
        name("dividerThickness", "How thick the bar between the two sides is, in points");
        name("tabPosition", "Which edge the row of tabs is on");
        name("columns", "How many items there are in a row");
        name("columnSpacing", "The gap between one column and the next, in points");
        name("rowSpacing", "The gap between one row and the next, in points");
        name("image", "The picture file, as a path in the project, such as assets/logo.png");
        name("icon", "Which of the built-in icons to draw");
        name("textAlign", "Where the text sits inside the element");

        // Kinds that use a name for something of their own
        kind("slider", "value", "Where the handle is now, between the minimum and the maximum");
        kind("number", "value", "The number in the field now");
        kind("percent", "value", "Where the handle is now, as a percentage from 0 to 100");
        kind("number", "min", "The lowest number that can be entered");
        kind("number", "max", "The highest number that can be entered");
        kind("number", "step", "How much the arrows add or take away; 0 for any amount");
        kind("slider", "min", "The value with the handle all the way to the left");
        kind("slider", "max", "The value with the handle all the way to the right");
        kind("slider", "step", "The handle moves in jumps of this size; 0 to move smoothly");
        kind("spinner", "color", "The color of the turning part");
        kind("textarea", "scrollbar", "Draw the bar that shows where in the text you are");
        kind("textfield", "padding", "The space between the edge of the field and its text, in points");
        kind("textarea", "lineSpacing", "Extra space between lines, in points");
        kind("textflow", "padding", "The space between the edge and the text, in points");
        kind("radio", "selected", "Whether this is the one chosen of its group");
        kind("tabs", "padding", "The space between the edge and the tab's contents, in points");
        kind("hsplit", "padding", "The space between the edge and the two sides, in points");
        kind("vsplit", "padding", "The space between the edge and the two sides, in points");
        kind("text", "text", "What it says. Use a Wrapped Text for more than a line");
        kind("button", "text", "The words on the button");
        kind("button", "textColor", "The color of the words on the button");
        kind("dropdown", "textColor", "The color of the chosen item shown in the box");
        kind("dropdown", "placeholder", "What the box says while nothing is chosen");
        kind("datepicker", "placeholder", "What the box says while no date or time is chosen");
        kind("textfield", "text", "What is in the field. The player can change it");
        kind("textarea", "text", "What is in the area. The player can change it");
        kind("textfield", "readOnly", "The text can be selected and copied but not typed over");
        kind("progress", "fillColor", "The color of the part of the bar that is done");
    }

    private UiTips() {
    }

    private static void name(String name, String tip) {
        BY_NAME.put(name, tip);
    }

    private static void kind(String kind, String name, String tip) {
        BY_KIND.put(kind + "." + name, tip);
    }

    /**
     * The tip for property {@code name} of kind {@code kindId}: the one written for that kind, else the one for the
     * name, else empty.
     */
    static String of(String kindId, String name) {
        String own = BY_KIND.get(kindId + "." + name);
        if (own != null) return own;
        return BY_NAME.getOrDefault(name, "");
    }
}
