package me.piitex.engine.ui.overlays;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Makes a set of {@link RadioButtonOverlay}s mutually exclusive. Selecting one through
 * {@link RadioButtonOverlay#select()}, which is what a click runs, deselects every other
 * member added here, filling the role JavaFX's {@code ToggleGroup} plays. This is not an
 * {@link me.piitex.engine.ui.Element}: it only coordinates selection state and has
 * nothing of its own to add to a Container or render.
 */
public class RadioGroup {
    private final List<RadioButtonOverlay> buttons = new ArrayList<>();
    private RadioButtonOverlay selected;
    private Consumer<RadioButtonOverlay> onChange;

    /**
     * Adds {@code button} to this group and points it back at this group. See {@link RadioButtonOverlay#getGroup()}.
     * A button in another group leaves that group first. A selected button becomes this group's selection if the
     * group has none, and is deselected if it already has one. {@link #onChange} does not fire. A no-op if
     * {@code button} is null or already a member.
     */
    public void add(RadioButtonOverlay button) {
        if (button == null || buttons.contains(button)) return;
        if (button.getGroup() != null) {
            button.getGroup().remove(button);
        }
        buttons.add(button);
        button.setGroupInternal(this);
        if (button.isSelected()) {
            if (selected == null) selected = button;
            else button.setSelectedInternal(false);
        }
    }

    /**
     * Removes {@code button} from this group. It goes back to selecting itself in
     * isolation (see {@link RadioButtonOverlay#select()}) and no longer takes part in
     * this group's exclusivity. If it was the selected member, this group has no
     * selection afterward.
     */
    public void remove(RadioButtonOverlay button) {
        if (buttons.remove(button)) {
            button.setGroupInternal(null);
            if (selected == button) {
                selected = null;
            }
        }
    }

    /**
     * Selects {@code button} and deselects every other member, or deselects them all if {@code button} is null. Called
     * by {@link RadioButtonOverlay#select()} with {@code fire} set, and by {@link #setSelected} and
     * {@link RadioButtonOverlay#setSelected} without it.
     */
    void select(RadioButtonOverlay button, boolean fire) {
        if (selected == button) return;
        selected = button;
        for (RadioButtonOverlay b : buttons) {
            b.setSelectedInternal(b == button);
        }
        if (fire && onChange != null) {
            onChange.accept(button);
        }
    }

    /**
     * Selects {@code button} programmatically, deselecting every other member, or clears the selection if it is
     * null. Unlike a click or {@link RadioButtonOverlay#select()}, this does not fire {@link #onChange}. Ignored if
     * {@code button} is not a member.
     */
    public void setSelected(RadioButtonOverlay button) {
        if (button != null && !buttons.contains(button)) return;
        select(button, false);
    }

    /**
     * The currently selected member, or null if this group has no selection yet.
     */
    public RadioButtonOverlay getSelected() {
        return selected;
    }

    /**
     * This group's members, in the order they were {@link #add}ed.
     */
    public List<RadioButtonOverlay> getButtons() {
        return List.copyOf(buttons);
    }

    /**
     * Registers the callback fired with the newly selected button every time the user, or {@link RadioButtonOverlay#select()}, changes this group's selection. {@link #setSelected} and {@link RadioButtonOverlay#setSelected} do not fire it. Only one is held at a time; calling this again replaces the previous one.
     */
    public void onChange(Consumer<RadioButtonOverlay> onChange) {
        this.onChange = onChange;
    }
}
