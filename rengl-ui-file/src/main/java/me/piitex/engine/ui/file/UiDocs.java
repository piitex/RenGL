package me.piitex.engine.ui.file;

import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The documentation of each {@link UiKind}, as Markdown that an editor can show beside the element, such as with a
 * {@link me.piitex.engine.ui.overlays.FormattedTextOverlay} and a {@link me.piitex.engine.ui.text.MarkdownFormatPreset}.
 * <p>
 * A page has two parts. The first is written by hand, in a file that comes with the engine, and says what the kind is for
 * and how it behaves: what it holds, what its settings do together, and what to watch for. It begins with a paragraph that
 * says in a sentence what the kind is, which is its {@link #summary}. The second is made from the kind's
 * {@link UiProperty properties}, so it can't fall out of step with them: each with its label, its saved name, its
 * tooltip and what it can be set to. The properties every kind has are left out of a kind's page and explained once, on
 * {@link #commonPage()}.
 * <p>
 * A kind of your own, added with {@link UiKinds#register}, has the second part, and the first once {@link #register}
 * gives it one.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiDocs {
    private static final String RESOURCES = "/me/piitex/engine/ui/file/docs/";
    private static final String COMMON = "_common";

    private static final Map<String, String> written = new ConcurrentHashMap<>();

    private UiDocs() {
    }

    /**
     * Gives a kind a written part of its page, replacing the one it has. {@code markdown} starts with a paragraph that
     * says in a sentence what the kind is.
     */
    public static void register(String kindId, String markdown) {
        written.put(kindId, markdown == null ? "" : markdown);
    }

    /**
     * What a kind's page says before its properties, or an empty text if nothing has been written for it.
     */
    public static String body(UiKind kind) {
        return written.computeIfAbsent(kind.getId(), UiDocs::load);
    }

    /**
     * One sentence, or a short paragraph, on what the kind is: the first paragraph of {@link #body}. Empty if it has none.
     */
    public static String summary(UiKind kind) {
        return firstParagraph(body(kind));
    }

    /**
     * The whole page for {@code kind}: its name, what was written for it, and its own properties.
     */
    public static String page(UiKind kind) {
        StringBuilder page = new StringBuilder("# ").append(kind.getLabel()).append("\n\n");
        String body = body(kind).strip();
        if (!body.isEmpty()) page.append(body).append("\n\n");
        String properties = properties(kind.getProperties(), false);
        if (!properties.isEmpty()) page.append("## Settings\n\n").append(properties);
        page.append("\n## Every element\n\nAn element also has what every element has: where it is and how big, its background and border, an opacity and a tooltip. Those are on the Every Element page.\n");
        return page.toString();
    }

    /**
     * The page for what every element has, which each kind's page leaves out.
     */
    public static String commonPage() {
        String body = commonBody().strip();
        StringBuilder page = new StringBuilder("# Every Element\n\n");
        if (!body.isEmpty()) page.append(body).append("\n\n");
        UiKind container = UiKinds.get("container");
        if (container != null) page.append("## Settings\n\n").append(properties(container.getProperties(), true));
        return page.toString();
    }

    /**
     * What was written for the page of what every element has, without the settings that are made from the properties.
     */
    public static String commonBody() {
        return written.computeIfAbsent(COMMON, UiDocs::load);
    }

    /** Whether {@code kind} has anything written for it, beyond its properties. */
    public static boolean isWritten(UiKind kind) {
        return !body(kind).isBlank();
    }

    // --- The properties -------------------------------------------------------------------------------------------

    private static String properties(List<UiProperty> all, boolean common) {
        Map<String, StringBuilder> groups = new LinkedHashMap<>();
        for (UiProperty property : all) {
            if (property.isCommon() != common) continue;
            StringBuilder group = groups.computeIfAbsent(property.getGroup().isEmpty() ? "Other" : property.getGroup(), g -> new StringBuilder());
            group.append("- **").append(property.getLabel()).append("** (`").append(property.getName()).append("`)");
            String tip = property.getTooltip().strip();
            if (!tip.isEmpty()) group.append(": ").append(tip.endsWith(".") ? tip : tip + ".");
            String can = choices(property);
            if (!can.isEmpty()) group.append(' ').append(can);
            group.append('\n');
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, StringBuilder> group : groups.entrySet()) {
            text.append("### ").append(group.getKey()).append("\n\n");
            if (group.getKey().equals(UiEvents.GROUP)) {
                text.append("An event is the name of a method. The layout's controller class is where the methods are looked for.\n\n");
            }
            text.append(group.getValue()).append('\n');
        }
        return text.toString();
    }

    /** What a property can be set to, in words, when its tooltip can't be relied on to say. */
    private static String choices(UiProperty property) {
        Class<?> type = property.getType();
        if (type.isEnum()) {
            StringBuilder names = new StringBuilder("One of ");
            Object[] constants = type.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) names.append(i == constants.length - 1 ? " or " : ", ");
                names.append(((Enum<?>) constants[i]).name());
            }
            return names.append('.').toString();
        }
        if (Paint.class.isAssignableFrom(type) || type == Color.class) {
            return Paint.class.isAssignableFrom(type) ? "A color, a gradient or a rainbow." : "A color.";
        }
        if (Background.class.isAssignableFrom(type)) return "";
        if (property.isNumber()) {
            // A limit that is only there to be out of the way, such as a million, isn't worth saying.
            boolean low = Double.isFinite(property.getMin()) && property.getMin() > -10000;
            boolean high = Double.isFinite(property.getMax()) && property.getMax() < 10000;
            if (low && high) return "From " + number(property.getMin()) + " to " + number(property.getMax()) + ".";
            if (low) return "At least " + number(property.getMin()) + ".";
            if (high) return "At most " + number(property.getMax()) + ".";
        }
        return "";
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    // --- Reading --------------------------------------------------------------------------------------------------

    private static String load(String id) {
        try (InputStream in = UiDocs.class.getResourceAsStream(RESOURCES + id + ".md")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The first run of lines that isn't a heading, as one line of text. */
    static String firstParagraph(String markdown) {
        StringBuilder paragraph = new StringBuilder();
        for (String line : markdown.split("\r?\n")) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                if (!paragraph.isEmpty()) break;
                continue;
            }
            if (!paragraph.isEmpty()) paragraph.append(' ');
            paragraph.append(text);
        }
        return paragraph.toString();
    }
}
