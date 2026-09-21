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
     * Adds {@code button} to this group and points it back at this group. See {@link RadioButtonOverlay#getGroup()}. A no-op if {@code button} is null or already a member.
     */
    public void add(RadioButtonOverlay button) {
        if (button == null || buttons.contains(button)) return;
        buttons.add(button);
        button.setGroupInternal(this);
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
     * Selects {@code button} and deselects every other member. Called by {@link RadioButtonOverlay#select()}, so it is rarely called directly.
     */
    void select(RadioButtonOverlay button) {
        if (selected == button) return;
        selected = button;
        for (RadioButtonOverlay b : buttons) {
            b.setSelectedInternal(b == button);
        }
        if (onChange != null) {
            onChange.accept(button);
        }
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
     * Registers the callback fired with the newly selected button every time this group's selection changes. Only one is held at a time; calling this again replaces the previous one.
     */
    public void onChange(Consumer<RadioButtonOverlay> onChange) {
        this.onChange = onChange;
    }
}
