package me.piitex.engine.access.speech;

import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns an Element into the words a screen reader would say for it: "Fullscreen, check box, not checked", "Music volume,
 * slider, 50", "Save, button". The order is name, role, value, state, then the description.
 * <p>
 * Every word it uses is in a table so a game can translate them: {@code narrator.setWord("role.button", "Schaltfläche")}.
 * See {@link #word} for the keys.
 */
public final class Narrator {
    private static final Map<String, String> ENGLISH = new HashMap<>();

    static {
        ENGLISH.put("role.group", "group");
        ENGLISH.put("role.link", "link");
        ENGLISH.put("role.button", "button");
        ENGLISH.put("role.check_box", "check box");
        ENGLISH.put("role.radio_button", "radio button");
        ENGLISH.put("role.switch", "switch");
        ENGLISH.put("role.slider", "slider");
        ENGLISH.put("role.spin_button", "spin box");
        ENGLISH.put("role.combo_box", "combo box");
        ENGLISH.put("role.text_field", "edit text");
        ENGLISH.put("role.text_area", "multiline edit text");
        ENGLISH.put("role.progress_bar", "progress bar");
        ENGLISH.put("role.image", "image");
        ENGLISH.put("role.list", "list");
        ENGLISH.put("role.list_item", "list item");
        ENGLISH.put("role.tab", "tab");
        ENGLISH.put("role.tab_list", "tab list");
        ENGLISH.put("role.tree", "tree");
        ENGLISH.put("role.tree_item", "tree item");
        ENGLISH.put("role.table", "table");
        ENGLISH.put("role.menu", "menu");
        ENGLISH.put("role.menu_item", "menu item");
        ENGLISH.put("role.dialog", "dialog");
        ENGLISH.put("state.checked", "checked");
        ENGLISH.put("state.unchecked", "not checked");
        ENGLISH.put("state.on", "on");
        ENGLISH.put("state.off", "off");
        ENGLISH.put("state.selected", "selected");
        ENGLISH.put("state.unselected", "not selected");
        ENGLISH.put("state.disabled", "unavailable");
        ENGLISH.put("value.empty", "blank");
    }

    private final Map<String, String> words = new HashMap<>(ENGLISH);

    /**
     * Replaces one of the words the narrator uses. The keys are {@code role.<role in lower case>} for each
     * {@link AccessibleRole} that is spoken (such as {@code role.check_box}), {@code state.checked},
     * {@code state.unchecked}, {@code state.on}, {@code state.off}, {@code state.selected}, {@code state.unselected},
     * {@code state.disabled}, and {@code value.empty} for a text field with nothing in it. Static text, roles with no key,
     * and {@code NONE} are not named.
     */
    public void setWord(String key, String text) {
        words.put(key, text);
    }

    /**
     * The word for {@code key}, or null when there is none.
     */
    public String word(String key) {
        return words.get(key);
    }

    /**
     * Everything to say when focus arrives on {@code element}: "Save, button", or "Fullscreen, check box, checked,
     * unavailable". Empty when there is nothing to say.
     */
    public String describe(Element element) {
        AccessibleRole role = element.getAccessibleRole();
        StringBuilder out = new StringBuilder();
        add(out, element.getAccessibleName());
        add(out, roleWord(role));

        String value = element.getAccessibleValue();
        if (value != null) { // null is a password field, which says nothing of what is in it
            if (value.isBlank()) add(out, valueWhenEmpty(role));
            else if (!value.equals(element.getAccessibleName())) add(out, value);
        }

        add(out, stateWord(role, element.getAccessibleChecked()));
        if (element.isEffectivelyDisabled()) add(out, word("state.disabled"));
        add(out, element.getAccessibleDescription());
        return out.toString();
    }

    /**
     * What to say when {@code element}'s value or state changes under the user's hands: the new value of a slider, or
     * "checked" for a check box that was just toggled.
     */
    public String describeChange(Element element) {
        StringBuilder out = new StringBuilder();
        String value = element.getAccessibleValue();
        if (value != null && !value.isBlank()) add(out, value);
        add(out, stateWord(element.getAccessibleRole(), element.getAccessibleChecked()));
        return out.toString();
    }

    private String roleWord(AccessibleRole role) {
        return switch (role) {
            case NONE, TEXT, STATUS -> null;
            default -> word("role." + role.name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    private String valueWhenEmpty(AccessibleRole role) {
        return role == AccessibleRole.TEXT_FIELD || role == AccessibleRole.TEXT_AREA ? word("value.empty") : null;
    }

    private String stateWord(AccessibleRole role, Boolean checked) {
        if (checked == null) return null;
        return switch (role) {
            case SWITCH -> word(checked ? "state.on" : "state.off");
            case RADIO_BUTTON -> word(checked ? "state.selected" : "state.unselected");
            default -> word(checked ? "state.checked" : "state.unchecked");
        };
    }

    private static void add(StringBuilder out, String part) {
        if (part == null || part.isBlank()) return;
        if (!out.isEmpty()) out.append(", ");
        out.append(part.strip());
    }
}
