package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Connects the method names a layout holds to the methods of a class, so the code a layout's events run is plain Java
 * in the program:
 * <pre>{@code
 * Container ui = UiFile.load("layouts/main.json");     // "play" is a button whose On Click is "startGame"
 * UiHandlers.bind(ui, new MainController(ui));
 *
 * public class MainController {
 *     public void startGame() { ... }
 * }
 * }</pre>
 * A method may take nothing, the value the event carries (the new text of a text field, {@code true} or
 * {@code false} for a check box...), or the element the event came from. Of those it takes the one that carries the
 * most: the value, else the element, else nothing. It may be of any visibility, and static or not.
 * <p>
 * A name that no method answers to is not an error that stops anything: it is reported in the list {@code bind}
 * returns and the element does nothing, so a half-written controller can still run. An exception a method throws is
 * passed to the handler given to {@code bind}, or logged, and does not reach the window's loop.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiHandlers {
    private static final Logger log = LogManager.getLogger("UiHandlers");

    private UiHandlers() {
    }

    /**
     * An event of an element with the name of the method it runs.
     */
    public record Handler(Element element, UiEvents.Event event, String method) {
        @Override
        public String toString() {
            String name = UiFile.getName(element);
            return (name.isEmpty() ? element.getClass().getSimpleName() : "\"" + name + "\"") + " " + event.getLabel() + " -> " + method;
        }
    }

    /**
     * Every event in {@code root} and what is in it that names a method, in the order they are in the layout.
     */
    public static List<Handler> handlers(Element root) {
        List<Handler> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Element element, List<Handler> into) {
        for (UiEvents.Event event : UiEvents.of(element)) {
            String method = UiEvents.getHandler(element, event);
            if (!method.isEmpty()) into.add(new Handler(element, event, method));
        }
        UiKind kind = UiKinds.of(element);
        if (element instanceof Container container && kind != null && kind.isContainer()) {
            for (Element child : UiKinds.children(container)) collect(child, into);
        }
    }

    /**
     * As {@link #bind(Element, Object, Consumer)}, with an exception from a method logged.
     */
    public static List<String> bind(Element root, Object controller) {
        return bind(root, controller, e -> log.error("A layout handler failed", e));
    }

    /**
     * Makes each event in {@code root} that names a method run that method of {@code controller}.
     *
     * @param onError told of an exception a method throws, on the thread the event came on
     * @return what couldn't be connected, one line each: the name has no method, or it is the wrong shape. Empty if all were.
     */
    public static List<String> bind(Element root, Object controller, Consumer<Throwable> onError) {
        List<String> problems = new ArrayList<>();
        for (Handler handler : handlers(root)) {
            Method method = find(controller.getClass(), handler.method(), handler.event().getValue(), handler.element());
            if (method == null) {
                problems.add(handler + ": " + controller.getClass().getSimpleName() + " has no method " + handler.method()
                        + "(" + shape(handler.event().getValue()) + ")");
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            Element source = handler.element();
            Class<?> carried = handler.event().getValue();
            boolean takesValue = parameters.length == 1 && carried != Void.class && accepts(parameters[0], carried);
            handler.event().connect(source, value -> {
                Object[] arguments = parameters.length == 0 ? new Object[0] : new Object[]{takesValue ? value : source};
                try {
                    method.invoke(Modifier.isStatic(method.getModifiers()) ? null : controller, arguments);
                } catch (InvocationTargetException e) {
                    onError.accept(e.getCause() == null ? e : e.getCause());
                } catch (IllegalAccessException | RuntimeException e) {
                    onError.accept(e);
                }
            });
        }
        return problems;
    }

    /**
     * The method of {@code type} (or what it extends) called {@code name} that an event carrying {@code value} can
     * run, or null if there is none. Of the shapes that fit, the one taking the value is chosen, else the one taking
     * the {@code source} element, else the one taking nothing.
     */
    public static Method find(Class<?> type, String name, Class<?> value, Element source) {
        Method withValue = null, withSource = null, plain = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.isSynthetic() || method.isBridge()) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 0) {
                    if (plain == null) plain = method;
                } else if (parameters.length == 1) {
                    if (value != Void.class && accepts(parameters[0], value)) {
                        if (withValue == null) withValue = method;
                    } else if (source != null && parameters[0].isInstance(source)) {
                        if (withSource == null) withSource = method;
                    }
                }
            }
        }
        Method found = withValue != null ? withValue : withSource != null ? withSource : plain;
        if (found != null) found.setAccessible(true);
        return found;
    }

    /** Whether a parameter of {@code parameter} takes a {@code value}, counting a primitive as its box. */
    private static boolean accepts(Class<?> parameter, Class<?> value) {
        if (parameter.isAssignableFrom(value)) return true;
        return parameter.isPrimitive() && boxOf(parameter) == value;
    }

    private static Class<?> boxOf(Class<?> primitive) {
        return PRIMITIVES.getOrDefault(primitive, primitive);
    }

    private static final Map<Class<?>, Class<?>> PRIMITIVES = Map.of(
            boolean.class, Boolean.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class, char.class, Character.class,
            byte.class, Byte.class, short.class, Short.class);

    private static String shape(Class<?> value) {
        return value == Void.class ? "" : value.getSimpleName() + " value";
    }
}
