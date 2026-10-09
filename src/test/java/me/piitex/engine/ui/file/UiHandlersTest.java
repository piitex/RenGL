package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UiHandlersTest {
    private final Container root = (Container) UiKinds.get("container").create("Root");

    private Element put(String kind, String name) {
        Element element = UiKinds.get(kind).create(name);
        UiKinds.add(root, element, -1);
        return element;
    }

    private static void set(Element element, String property, Object value) {
        UiKinds.of(element).getProperty(property).set(element, value);
    }

    private static void click(ButtonOverlay button) {
        button.getMouseClickListenerConsumer().accept(new MouseClickEvent(0, 0, 0, 0));
    }

    private Container reload() {
        return UiFile.parse(UiFile.toJson(root), Path.of(""));
    }

    public static class Controller {
        final List<String> calls = new ArrayList<>();

        public void play() {
            calls.add("play");
        }

        void withElement(ButtonOverlay source) {
            calls.add("element " + UiFile.getName(source));
        }

        void withFlag(boolean on) {
            calls.add("flag " + on);
        }

        void boxed(Boolean on) {
            calls.add("boxed " + on);
        }

        // Of the shapes that fit, the one that takes the value is the one that is used.
        void both() {
            calls.add("both()");
        }

        void both(boolean on) {
            calls.add("both(" + on + ")");
        }

        void broken() {
            throw new IllegalStateException("boom");
        }

        static final List<String> statics = new ArrayList<>();

        static void shared() {
            statics.add("shared");
        }
    }

    public static class Base {
        final List<String> calls = new ArrayList<>();

        void inherited() {
            calls.add("inherited");
        }
    }

    public static class Derived extends Base {
    }

    @Test
    void aButtonsHandlerIsSavedWithItAndLoadedBack() {
        Element play = put("button", "play");
        assertEquals("", UiEvents.getHandler(play, UiEvents.of(play).getFirst()));
        assertFalse(UiFile.toJson(root).contains("onAction"), "nothing is written for an event with no handler");

        set(play, "onAction", "play");
        assertTrue(UiFile.toJson(root).contains("\"onAction\": \"play\""));

        Container again = reload();
        List<UiHandlers.Handler> handlers = UiHandlers.handlers(again);
        assertEquals(1, handlers.size());
        assertEquals("play", handlers.getFirst().method());
        assertEquals("play", UiFile.getName(handlers.getFirst().element()));
        assertEquals(UiFile.toJson(root), UiFile.toJson(again));
    }

    @Test
    void theRootsControllerIsSavedOnlyOnTheRoot() {
        set(root, "controller", "game.MainController");
        Element inner = put("container", "inner");
        assertFalse(UiKinds.of(inner).getProperty("controller").appliesTo(inner), "it means nothing inside another container");
        assertEquals("game.MainController", UiEvents.getController(reload()));
        assertEquals(1, UiFile.toJson(root).split("controller", -1).length - 1);
    }

    @Test
    void everyKindsEventsAreListedInTheEventsGroup() {
        for (UiKind kind : UiKinds.all()) {
            for (UiEvents.Event event : UiEvents.of(kind.getType())) {
                UiProperty property = kind.getProperty(event.getName());
                assertNotNull(property, kind.getId() + " " + event);
                assertEquals(UiEvents.GROUP, property.getGroup());
            }
        }
        assertEquals(List.of("onAction"), UiEvents.of(ButtonOverlay.class).stream().map(UiEvents.Event::getName).toList());
        assertTrue(UiEvents.of(UiKinds.get("text").getType()).isEmpty(), "a label has no events");
    }

    @Test
    void aClickRunsTheNamedMethod() {
        ButtonOverlay play = (ButtonOverlay) put("button", "play");
        set(play, "onAction", "play");
        Controller controller = new Controller();
        assertEquals(List.of(), UiHandlers.bind(root, controller));
        assertEquals(List.of(), controller.calls);
        click(play);
        click(play);
        assertEquals(List.of("play", "play"), controller.calls);
    }

    @Test
    void aMethodMayTakeTheElementItCameFrom() {
        ButtonOverlay play = (ButtonOverlay) put("button", "play");
        set(play, "onAction", "withElement");
        Controller controller = new Controller();
        assertEquals(List.of(), UiHandlers.bind(root, controller));
        click(play);
        assertEquals(List.of("element play"), controller.calls);
    }

    @Test
    void aMethodMayTakeTheValueAsAPrimitiveOrItsBox() {
        CheckBoxOverlay box = (CheckBoxOverlay) put("checkbox", "box");
        Controller controller = new Controller();
        set(box, "onToggle", "withFlag");
        assertEquals(List.of(), UiHandlers.bind(root, controller));
        box.toggle();
        box.toggle();
        set(box, "onToggle", "boxed");
        assertEquals(List.of(), UiHandlers.bind(root, controller));
        box.toggle();
        assertEquals(List.of("flag true", "flag false", "boxed true"), controller.calls);
    }

    @Test
    void theShapeThatTakesTheValueIsChosenOverOneThatTakesNothing() {
        CheckBoxOverlay box = (CheckBoxOverlay) put("checkbox", "box");
        set(box, "onToggle", "both");
        Controller controller = new Controller();
        UiHandlers.bind(root, controller);
        box.toggle();
        assertEquals(List.of("both(true)"), controller.calls);
    }

    @Test
    void aNameWithNoMethodIsReportedAndLeavesTheElementAlone() {
        ButtonOverlay play = (ButtonOverlay) put("button", "play");
        set(play, "onAction", "nothingLikeIt");
        CheckBoxOverlay box = (CheckBoxOverlay) put("checkbox", "box");
        set(box, "onToggle", "withElement"); // takes a button, which a toggle's value isn't, and it isn't one itself
        List<String> problems = UiHandlers.bind(root, new Controller());
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("nothingLikeIt"), problems.get(0));
        assertTrue(problems.get(0).contains("play"), problems.get(0));
        assertDoesNotThrow(() -> click(play));
        assertDoesNotThrow(box::toggle);
    }

    @Test
    void anExceptionInAMethodIsHandedOnAndDoesNotEscape() {
        ButtonOverlay play = (ButtonOverlay) put("button", "play");
        set(play, "onAction", "broken");
        List<Throwable> errors = new ArrayList<>();
        UiHandlers.bind(root, new Controller(), errors::add);
        assertDoesNotThrow(() -> click(play));
        assertEquals(1, errors.size());
        assertInstanceOf(IllegalStateException.class, errors.getFirst());
        assertEquals("boom", errors.getFirst().getMessage());
    }

    @Test
    void staticAndInheritedMethodsAreFound() {
        ButtonOverlay play = (ButtonOverlay) put("button", "play");
        set(play, "onAction", "shared");
        UiHandlers.bind(root, new Controller());
        click(play);
        assertEquals(List.of("shared"), Controller.statics);

        set(play, "onAction", "inherited");
        Derived derived = new Derived();
        assertEquals(List.of(), UiHandlers.bind(root, derived));
        click(play);
        assertEquals(List.of("inherited"), derived.calls);
    }

    @Test
    void handlersInsideContainersAreFound() {
        Container panel = (Container) put("vertical", "panel");
        Element deep = UiKinds.get("button").create("deep");
        UiKinds.add(panel, deep, -1);
        set(deep, "onAction", "play");
        Container again = reload();
        assertEquals(List.of(), UiHandlers.bind(again, new Controller()));
        assertEquals(1, UiHandlers.handlers(again).size());
    }

    @Test
    void aFileNamingAHandlerLoadsAndIsWiredWithoutTheEditor() {
        String json = """
                { "format": 1, "root": { "kind": "container", "name": "Root", "controller": "x.Y", "children": [
                    { "kind": "button", "name": "go", "onAction": "play" } ] } }
                """;
        Container ui = UiFile.parse(json, Path.of(""));
        Controller controller = new Controller();
        assertEquals(List.of(), UiHandlers.bind(ui, controller));
        click(UiFile.find(ui, "go", ButtonOverlay.class));
        assertEquals(List.of("play"), controller.calls);
        assertEquals("x.Y", UiEvents.getController(ui));
    }
}
