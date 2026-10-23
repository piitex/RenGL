package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The things an element tells a program about, such as a button being clicked, and how a layout file names the code
 * to run for each. A layout holds only the <i>name</i> of a method for an event, saved with the element:
 * <pre>{@code
 * { "kind": "button", "name": "play", "text": "Play", "onAction": "startGame" }
 * }</pre>
 * and the root of the layout may name the class the methods are looked for in, as {@code "controller": "game.MainController"}.
 * {@link UiHandlers} connects the two when the program runs.
 * <p>
 * The events are those the element classes already have: a button's {@code onAction}, a check box's {@code onToggle}, a
 * text field's {@code onTextChanged} and {@code onSubmit}, and so on. Each is a property of the element's
 * {@link UiKind kind} in the group "Events", so an editor lists and saves them like any other.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiEvents {
    /** The group an editor lists events in. */
    public static final String GROUP = "Events";
    /** The name the controller class is saved under, on the layout's root. */
    public static final String CONTROLLER = "controller";

    /**
     * One thing an element can tell about: its name in files, what it is called to people, and what it passes along.
     */
    public static final class Event {
        private final String name;
        private final String label;
        private final String tip;
        private final Class<? extends Element> owner;
        private final Class<?> value;
        private final BiConsumer<Element, Consumer<Object>> wire;

        private Event(String name, String label, String tip, Class<? extends Element> owner, Class<?> value,
                      BiConsumer<Element, Consumer<Object>> wire) {
            this.name = name;
            this.label = label;
            this.tip = tip;
            this.owner = owner;
            this.value = value;
            this.wire = wire;
        }

        /** The name saved in files, which is also the name of the element class's own method for it. */
        public String getName() {
            return name;
        }

        /** A name for people: "On Click". */
        public String getLabel() {
            return label;
        }

        public String getTooltip() {
            return tip;
        }

        /** The class the event belongs to; elements of it, and of classes that extend it, have the event. */
        public Class<? extends Element> getOwner() {
            return owner;
        }

        /** What the method may take, such as a {@link String} for new text, or {@link Void} if the event carries nothing. */
        public Class<?> getValue() {
            return value;
        }

        public boolean appliesTo(Element element) {
            return owner.isInstance(element);
        }

        /** Makes {@code element} call {@code fire} with the event's value each time it happens, in place of any listener it had. */
        void connect(Element element, Consumer<Object> fire) {
            wire.accept(element, fire);
        }

        @Override
        public String toString() {
            return owner.getSimpleName() + "." + name;
        }
    }

    private static final List<Event> ALL = new ArrayList<>();

    static {
        event("onAction", "On Click", "Runs when the button is clicked", ButtonOverlay.class, Void.class,
                (button, fire) -> button.onAction(() -> fire.accept(null)));
        event("onToggle", "On Toggle", "Runs when it is switched, with true for on", CheckBoxOverlay.class, Boolean.class,
                (box, fire) -> box.onToggle(fire::accept));
        event("onToggle", "On Toggle", "Runs when it is switched, with true for on", ToggleSwitchOverlay.class, Boolean.class,
                (toggle, fire) -> toggle.onToggle(fire::accept));
        event("onTextChanged", "On Text Change", "Runs each time the text changes, with the new text", TextFieldOverlay.class, String.class,
                (field, fire) -> field.onTextChanged(fire::accept));
        event("onSubmit", "On Submit", "Runs when Enter is pressed, with the text", TextFieldOverlay.class, String.class,
                (field, fire) -> field.onSubmit(fire::accept));
        event("onTextChanged", "On Text Change", "Runs each time the text changes, with the new text", TextAreaOverlay.class, String.class,
                (area, fire) -> area.onTextChanged(fire::accept));
        event("onSubmit", "On Submit", "Runs when Ctrl or Cmd and Enter are pressed, with the text", TextAreaOverlay.class, String.class,
                (area, fire) -> area.onSubmit(fire::accept));
        event("onValueChange", "On Value Change", "Runs when the value changes, with the new value", SliderOverlay.class, Double.class,
                (slider, fire) -> slider.onValueChange(fire::accept));
        event("onValueChange", "On Value Change", "Runs when the value changes, with the new value", SpinnerOverlay.class, Double.class,
                (spinner, fire) -> spinner.onValueChange(fire::accept));
        event("onSelect", "On Select", "Runs when an item is picked, with the item", DropdownOverlay.class, Object.class,
                (dropdown, fire) -> dropdown.onSelect(fire::accept));
        event("onChange", "On Change", "Runs when a color is picked, with the color", ColorPickerOverlay.class, Color.class,
                (picker, fire) -> picker.onChange(fire::accept));
        event("onChange", "On Change", "Runs when the date or time changes, with the picker", DateTimePickerOverlay.class, DateTimePickerOverlay.class,
                (picker, fire) -> picker.onChange(fire::accept));
    }

    private UiEvents() {
    }

    private static <E extends Element, T> void event(String name, String label, String tip, Class<E> owner, Class<T> value,
                                                     BiConsumer<E, Consumer<Object>> wire) {
        ALL.add(new Event(name, label, tip, owner, value, (element, fire) -> wire.accept(owner.cast(element), fire)));
    }

    /**
     * The events elements of {@code type} have, in the order they are listed.
     */
    public static List<Event> of(Class<?> type) {
        List<Event> events = new ArrayList<>();
        for (Event event : ALL) if (event.owner.isAssignableFrom(type)) events.add(event);
        return events;
    }

    /**
     * The events {@code element} has.
     */
    public static List<Event> of(Element element) {
        return of(element.getClass());
    }

    /**
     * The name of the method {@code element} runs for {@code event}, empty if none is named.
     */
    public static String getHandler(Element element, Event event) {
        return stashed(element, event.name);
    }

    /**
     * The class the layout whose root is {@code root} looks its methods up in, empty if none is named.
     */
    public static String getController(Element root) {
        return stashed(root, CONTROLLER);
    }

    private static String stashed(Element element, String key) {
        UiMeta meta = UiMeta.peek(element);
        return meta != null && meta.stash.get(key) instanceof String text ? text : "";
    }

    private static void stash(Element element, String key, Object value) {
        UiMeta.of(element).stash.put(key, value == null ? "" : ((String) value).strip());
    }

    /**
     * The properties a kind whose elements are {@code type} has for events: one for each event, naming a method.
     */
    static List<UiProperty> properties(Class<? extends Element> type) {
        List<UiProperty> properties = new ArrayList<>();
        for (Event event : of(type)) {
            properties.add(new UiProperty(event.name, event.label, String.class, e -> stashed(e, event.name),
                    (e, v, base) -> stash(e, event.name, v))
                    .group(GROUP).omitWhen("").tip(event.tip + ". Give the name of a method."));
        }
        return properties;
    }

    /**
     * The property of a layout's root that names its controller class. It means nothing for an element that is in another.
     */
    static UiProperty controller() {
        return new UiProperty(CONTROLLER, "Controller", String.class, e -> stashed(e, CONTROLLER),
                (e, v, base) -> stash(e, CONTROLLER, v))
                .group(GROUP).omitWhen("").when(e -> e.owner == null && e instanceof Container)
                .tip("The class, with its package, that has the methods the layout's events name, such as game.MainController");
    }
}
