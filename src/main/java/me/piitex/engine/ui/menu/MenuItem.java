package me.piitex.engine.ui.menu;

import me.piitex.engine.ui.image.IconAsset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * One row of a {@link PopupMenu} (and so of a {@link MenuBarOverlay} menu or an
 * {@link me.piitex.engine.ui.Element#setContextMenu context menu}). A row is one of:
 * <ul>
 *     <li>an <b>action</b>, {@link #MenuItem(String, Runnable)}: runs when chosen;</li>
 *     <li>a <b>check item</b>, {@link #check}: toggles a tick each time it is chosen;</li>
 *     <li>a <b>radio item</b>, {@link #radioGroup}: a ticked choice among a group, where choosing one clears the rest;</li>
 *     <li>a <b>submenu</b>, {@link #submenu}: opens a cascading menu to the side;</li>
 *     <li>a <b>separator</b>, {@link #separator()}: a thin divider line, never selectable.</li>
 * </ul>
 * Choosing an action or check item closes the whole menu first, then runs the callback,
 * so the callback is free to open a dialog or another menu.
 */
public class MenuItem {
    private String label;
    private String shortcut;
    private boolean enabled = true;
    private final boolean separator;
    private final boolean checkable;
    private boolean radio;
    private List<MenuItem> radioGroup;
    private boolean checked;
    private IconAsset icon;
    private char mnemonic;
    private Runnable action;
    private Consumer<Boolean> onToggle;
    private final List<MenuItem> children = new ArrayList<>();

    /**
     * An action item. {@code action} may be null (an inert row).
     */
    public MenuItem(String label, Runnable action) {
        this(label, action, false, false);
    }

    private MenuItem(String label, Runnable action, boolean separator, boolean checkable) {
        this.label = label == null ? "" : label;
        this.action = action;
        this.separator = separator;
        this.checkable = checkable;
    }

    /**
     * A divider line between groups of items.
     */
    public static MenuItem separator() {
        return new MenuItem("", null, true, false);
    }

    /**
     * An item with a tick that flips on every choice; {@code onToggle} gets the new state.
     */
    public static MenuItem check(String label, boolean checked, Consumer<Boolean> onToggle) {
        MenuItem item = new MenuItem(label, null, false, true);
        item.checked = checked;
        item.onToggle = onToggle;
        return item;
    }

    /**
     * A group of radio items: one of them is ticked, and choosing another moves the tick to it. Pass the result to
     * {@link PopupMenu#PopupMenu(MenuItem...)} or {@link PopupMenu#addAll}; keep the items next to each other so they
     * read as a group. {@code onSelect} gets the index of the item chosen, and is not called for the one that is already
     * ticked. {@code selected} is the index ticked to begin with, or -1 for none.
     */
    public static MenuItem[] radioGroup(int selected, java.util.function.IntConsumer onSelect, String... labels) {
        MenuItem[] items = new MenuItem[labels.length];
        List<MenuItem> group = new ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            MenuItem item = new MenuItem(labels[i], null, false, true);
            item.radio = true;
            item.radioGroup = group;
            item.checked = i == selected;
            int index = i;
            item.onToggle = on -> {
                if (onSelect != null) onSelect.accept(index);
            };
            items[i] = item;
            group.add(item);
        }
        return items;
    }

    /**
     * An item that opens {@code children} as a cascading submenu.
     */
    public static MenuItem submenu(String label, MenuItem... children) {
        MenuItem item = new MenuItem(label, null);
        Collections.addAll(item.children, children);
        return item;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    /**
     * The accelerator text shown right-aligned, such as {@code "Ctrl+S"}, or null. This is display only and does not bind a key.
     */
    public String getShortcut() {
        return shortcut;
    }

    public void setShortcut(String shortcut) {
        this.shortcut = shortcut;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * A disabled item is drawn dimmed and ignores hover and clicks.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSeparator() {
        return separator;
    }

    /**
     * Whether this row has a tick: a check item or a radio item.
     */
    public boolean isCheckable() {
        return checkable;
    }

    /**
     * Whether this is one of a {@link #radioGroup}: ticked with a dot, and exclusive with the rest of its group.
     */
    public boolean isRadio() {
        return radio;
    }

    public IconAsset getIcon() {
        return icon;
    }

    /**
     * A small picture drawn before the label, in the label's color, or null for none. Menus that have an icon on any
     * row leave room for it on every row. The in-window menus draw it; the macOS system menu bar does not.
     */
    public void setIcon(IconAsset icon) {
        this.icon = icon;
    }

    /**
     * The letter that chooses this row while its menu is open, such as {@code 'S'} for Save, or 0 for none. It is
     * underlined in the first place it appears in the label, ignoring case. Pressing it chooses the row (or, if
     * several rows share it, moves to the next of them). Only the in-window menus use it.
     */
    public char getMnemonic() {
        return mnemonic;
    }

    public void setMnemonic(char mnemonic) {
        this.mnemonic = mnemonic;
    }

    /**
     * Where the mnemonic is underlined in the label, or -1.
     */
    int mnemonicIndex() {
        return mnemonic == 0 ? -1 : label.toLowerCase().indexOf(Character.toLowerCase(mnemonic));
    }

    public boolean isChecked() {
        return checked;
    }

    /**
     * Sets the tick without firing the toggle callback. Only meaningful for {@link #check} items.
     */
    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public Runnable getAction() {
        return action;
    }

    public void setAction(Runnable action) {
        this.action = action;
    }

    public boolean hasSubmenu() {
        return !children.isEmpty();
    }

    /**
     * The submenu rows; add to it to grow the submenu. Empty for anything that isn't a {@link #submenu}.
     */
    public List<MenuItem> getChildren() {
        return children;
    }

    /**
     * Chooses this item as if the user had: flips a check item and reports the new state, or runs the action for
     * anything else. The menus call it after closing; it is public so a native (system) menu bar can too. Does not
     * check {@link #isEnabled()} and does nothing for a separator or submenu.
     */
    public void fire() {
        if (radio) {
            if (checked) return; // already the chosen one
            for (MenuItem other : radioGroup) other.checked = false;
            checked = true;
            if (onToggle != null) onToggle.accept(true);
        } else if (checkable) {
            checked = !checked;
            if (onToggle != null) onToggle.accept(checked);
        } else if (action != null) {
            action.run();
        }
    }
}
