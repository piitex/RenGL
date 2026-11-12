package me.piitex.engine.access;

/**
 * What an Element is, to someone who cannot see it: the kind of thing a screen reader announces ("Save, button") and
 * the operating system's accessibility tree files it under. {@link #NONE} is an Element that adds nothing of its own,
 * such as a layout box, which the {@link AccessibilityTree} skips over and whose children it keeps.
 */
public enum AccessibleRole {
    NONE,
    /**
     * A region with a name of its own, such as a labelled panel.
     */
    GROUP,
    /**
     * Static text.
     */
    TEXT,
    BUTTON,
    LINK,
    CHECK_BOX,
    RADIO_BUTTON,
    /**
     * An on/off switch.
     */
    SWITCH,
    SLIDER,
    SPIN_BUTTON,
    /**
     * A drop-down or combo box.
     */
    COMBO_BOX,
    TEXT_FIELD,
    TEXT_AREA,
    PROGRESS_BAR,
    IMAGE,
    LIST,
    LIST_ITEM,
    TAB_LIST,
    TAB,
    TREE,
    TREE_ITEM,
    TABLE,
    MENU,
    MENU_ITEM,
    DIALOG,
    /**
     * A message that appears on its own and is not asked for, such as a toast.
     */
    STATUS
}
