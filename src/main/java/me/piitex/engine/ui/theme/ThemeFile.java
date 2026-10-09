package me.piitex.engine.ui.theme;

import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.ui.background.BackgroundKinds;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.PaintCodec;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A {@link Theme} as a file: every color and size the theme has, by name, so an app's look is data that a person (or an
 * editor) changes instead of code.
 * <pre>{@code
 * {
 *   "format": 1,
 *   "base": "dark",
 *   "buttonBackground": "#9E1221",
 *   "cornerRadius": 4
 * }
 * }</pre>
 * Colors are {@code "#RRGGBB"}, {@code "#RRGGBBAA"} or {@code [r, g, b, a]} with each from 0 to 1. A file need only say
 * what differs from its {@code base}, which is {@code "light"}, {@code "dark"} or {@code "crimson"} and is "light" when
 * left out. {@link #save} writes every setting, so a saved file stands on its own.
 * <p>
 * The window's and the panels' backgrounds, and the tooltip's fill, border and text, are paints: a color as above, or a gradient or rainbow as {@link PaintCodec}
 * describes. Each also has an effect to go behind it, the settings of a {@link BackgroundKinds kind of background}:
 * <pre>{@code
 * "windowBackground": {"type": "gradient", "colors": ["#101020", "#2A1040"], "angle": 90, "scroll": 30},
 * "windowEffect": {"type": "constellation", "particleCount": 90},
 * "containerBackground": "#1B1B24"
 * }</pre>
 * {@code null} for an effect is none. A picture an effect names is looked for in the folder of the theme file.
 * <p>
 * Use it from an app with {@code ThemeManager.setCurrent(ThemeFile.load("theme.json"))} before the window is made.
 * {@link #tokens()} lists the settings, with what a tool needs to show them.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class ThemeFile {
    /** The newest format this reads; it is the one it writes. */
    public static final int FORMAT = 1;

    private static final List<String> BUILT_IN = List.of("light", "dark", "crimson");

    /**
     * One setting of a theme: its name in the file, what a person calls it, the group it sits in and how to read and
     * change it. Its {@code type} says what it holds:
     * <ul>
     *   <li>{@link Color}, a solid color;</li>
     *   <li>{@link Paint}, a color, gradient or rainbow;</li>
     *   <li>{@link Map}, the settings of a background effect, which {@code get} gives as null for none;</li>
     *   <li>{@link Float}, a size, between {@code min} and {@code max}.</li>
     * </ul>
     * A color's, paint's and effect's {@code min} and {@code max} are 0. The {@code tooltip} says what the setting changes.
     */
    public record Token(String name, String label, String group, Class<?> type, float min, float max,
                        Function<Theme, Object> get, BiConsumer<BaseTheme, Object> set, String tooltip) {
        public boolean isColor() {
            return type == Color.class;
        }

        /** Whether it is a paint, which a gradient can be. A color is not. */
        public boolean isPaint() {
            return type == Paint.class;
        }

        /** Whether it is the settings of a background effect. */
        public boolean isEffect() {
            return type == Map.class;
        }
    }

    /** What each setting changes, by name, for the tooltip an editor shows. */
    private static final Map<String, String> TIPS = Map.ofEntries(
            Map.entry("windowBackground", "The background of every window. A color, a gradient, a gradient that moves, or a rainbow"),
            Map.entry("windowEffect", "Dots, moving shapes or a picture drawn on the window's background"),
            Map.entry("text", "The color of text that doesn't have a color of its own"),
            Map.entry("cornerRadius", "How round the corners of buttons, fields and panels are, in points"),
            Map.entry("borderThickness", "How thick the lines round buttons, fields and panels are, in points"),
            Map.entry("containerBackground", "The background of a panel, which is a container with Themed Panel on. A color, a gradient, a gradient that moves, or a rainbow"),
            Map.entry("containerEffect", "Dots, moving shapes or a picture drawn on a panel's background"),
            Map.entry("containerBorder", "The line round a panel"),
            Map.entry("buttonBackground", "The color of a button"),
            Map.entry("buttonHoverBackground", "The color of a button while the mouse is over it"),
            Map.entry("buttonBorder", "The line round a button"),
            Map.entry("fieldBackground", "The color of text fields, text areas, dropdowns and the other boxes the player types or picks in"),
            Map.entry("fieldHoverBackground", "The color of a field while the mouse is over it"),
            Map.entry("fieldBorder", "The line round a field"),
            Map.entry("placeholderText", "The color of the hint shown in a field that is empty"),
            Map.entry("caret", "The color of the blinking line where typing goes"),
            Map.entry("selection", "The color behind text that is selected"),
            Map.entry("scrollThumb", "The color of the handle of a scroll bar"),
            Map.entry("scrollTrack", "The color of the strip a scroll bar's handle moves along"),
            Map.entry("tooltipBackground", "The color behind a tooltip, like this one"),
            Map.entry("tooltipBorder", "The line round a tooltip"),
            Map.entry("tooltipText", "The color of the words in a tooltip"));

    private static final List<Token> TOKENS = List.of(
            paint("windowBackground", "Window Background", "Window", Theme::getWindowBackground, BaseTheme::setWindowBackground),
            effect("windowEffect", "Window Effect", "Window", Theme::getWindowEffect, BaseTheme::setWindowEffect),
            color("text", "Text", "Window", Theme::getText, BaseTheme::setText),
            size("cornerRadius", "Corner Radius", "Window", 0, 64, Theme::getCornerRadius, BaseTheme::setCornerRadius),
            size("borderThickness", "Border Thickness", "Window", 0, 16, Theme::getBorderThickness, BaseTheme::setBorderThickness),
            paint("containerBackground", "Panel Background", "Panels", Theme::getContainerBackground, BaseTheme::setContainerBackground),
            effect("containerEffect", "Panel Effect", "Panels", Theme::getContainerEffect, BaseTheme::setContainerEffect),
            color("containerBorder", "Panel Border", "Panels", Theme::getContainerBorder, BaseTheme::setContainerBorder),
            color("buttonBackground", "Button", "Buttons", Theme::getButtonBackground, BaseTheme::setButtonBackground),
            color("buttonHoverBackground", "Button Hovered", "Buttons", Theme::getButtonHoverBackground, BaseTheme::setButtonHoverBackground),
            color("buttonBorder", "Button Border", "Buttons", Theme::getButtonBorder, BaseTheme::setButtonBorder),
            color("fieldBackground", "Field", "Fields", Theme::getFieldBackground, BaseTheme::setFieldBackground),
            color("fieldHoverBackground", "Field Hovered", "Fields", Theme::getFieldHoverBackground, BaseTheme::setFieldHoverBackground),
            color("fieldBorder", "Field Border", "Fields", Theme::getFieldBorder, BaseTheme::setFieldBorder),
            color("placeholderText", "Placeholder Text", "Fields", Theme::getPlaceholderText, BaseTheme::setPlaceholderText),
            color("caret", "Caret", "Fields", Theme::getCaret, BaseTheme::setCaret),
            color("selection", "Selection", "Fields", Theme::getSelection, BaseTheme::setSelection),
            color("scrollThumb", "Scroll Bar", "Scroll Bars", Theme::getScrollThumb, BaseTheme::setScrollThumb),
            color("scrollTrack", "Scroll Track", "Scroll Bars", Theme::getScrollTrack, BaseTheme::setScrollTrack),
            paint("tooltipBackground", "Tooltip", "Tooltips", Theme::getTooltipBackground, BaseTheme::setTooltipBackground),
            paint("tooltipBorder", "Tooltip Border", "Tooltips", Theme::getTooltipBorder, BaseTheme::setTooltipBorder),
            paint("tooltipText", "Tooltip Text", "Tooltips", Theme::getTooltipText, BaseTheme::setTooltipText));

    private static final Map<String, Token> BY_NAME = TOKENS.stream().collect(Collectors.toMap(Token::name, t -> t));

    private ThemeFile() {
    }

    private static Token color(String name, String label, String group, Function<Theme, Color> get, BiConsumer<BaseTheme, Color> set) {
        return new Token(name, label, group, Color.class, 0, 0, get::apply, (theme, value) -> set.accept(theme, (Color) value), TIPS.getOrDefault(name, ""));
    }

    private static Token paint(String name, String label, String group, Function<Theme, Paint> get, BiConsumer<BaseTheme, Paint> set) {
        return new Token(name, label, group, Paint.class, 0, 0, get::apply, (theme, value) -> set.accept(theme, (Paint) value), TIPS.getOrDefault(name, ""));
    }

    @SuppressWarnings("unchecked")
    private static Token effect(String name, String label, String group, Function<Theme, Map<String, Object>> get, BiConsumer<BaseTheme, Map<String, Object>> set) {
        return new Token(name, label, group, Map.class, 0, 0, get::apply, (theme, value) -> set.accept(theme, (Map<String, Object>) value), TIPS.getOrDefault(name, ""));
    }

    private static Token size(String name, String label, String group, float min, float max, Function<Theme, Float> get, BiConsumer<BaseTheme, Float> set) {
        return new Token(name, label, group, Float.class, min, max, get::apply, (theme, value) -> set.accept(theme, ((Number) value).floatValue()), TIPS.getOrDefault(name, ""));
    }

    // --- The settings ---------------------------------------------------------------------------------------------

    /** Every setting a theme has, in the order a file writes them and a tool should show them. */
    public static List<Token> tokens() {
        return TOKENS;
    }

    /** The names {@code "base"} can be: the themes the engine comes with. */
    public static List<String> builtIns() {
        return BUILT_IN;
    }

    /** A new theme that is the built-in one called {@code id} ({@link #builtIns()}), or null if there is none. */
    public static BaseTheme builtIn(String id) {
        return switch (id == null ? "" : id.toLowerCase(Locale.ROOT)) {
            case "light" -> new LightTheme();
            case "dark" -> new DarkTheme();
            case "crimson" -> new CrimsonTheme();
            default -> null;
        };
    }

    /** A theme that shares nothing with {@code theme} and has the same settings, which can then be changed. */
    public static BaseTheme copy(Theme theme) {
        BaseTheme copy = new BaseTheme();
        for (Token token : TOKENS) {
            Object value = token.get().apply(theme);
            if (value != null || token.isEffect()) token.set().accept(copy, value);
        }
        copy.setAssetBase(theme.assetBase());
        return copy;
    }

    // --- Reading and writing --------------------------------------------------------------------------------------

    /**
     * Reads a theme file.
     *
     * @throws IllegalArgumentException if it isn't a theme file, naming what is wrong with it
     * @throws UncheckedIOException     if it can't be read
     */
    public static BaseTheme load(String path) {
        return load(Path.of(path));
    }

    /** As {@link #load(String)}. */
    public static BaseTheme load(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + path, e);
        }
        try {
            BaseTheme theme = parse(text);
            theme.setAssetBase(path.getParent());
            return theme;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    /**
     * The theme {@code json} describes: its base with each setting the file has put on top.
     *
     * @throws IllegalArgumentException for text that isn't JSON, a setting that isn't one of {@link #tokens()}, or a
     *                                  value that isn't a color or number as the setting needs
     */
    public static BaseTheme parse(String json) {
        if (!(Json.parse(json) instanceof Map<?, ?> file)) throw new IllegalArgumentException("a theme file must be a JSON object");
        if (file.get("format") instanceof Number n && n.intValue() > FORMAT) {
            throw new IllegalArgumentException("it was saved by a newer version (format " + n.intValue() + ")");
        }
        BaseTheme theme = new BaseTheme();
        if (file.get("base") != null) {
            theme = builtIn(String.valueOf(file.get("base")));
            if (theme == null) throw new IllegalArgumentException("\"base\" is " + file.get("base") + ", which isn't one of " + String.join(", ", BUILT_IN));
        }
        for (Map.Entry<?, ?> entry : file.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (name.equals("format") || name.equals("base")) continue;
            Token token = BY_NAME.get(name);
            if (token == null) {
                throw new IllegalArgumentException("\"" + name + "\" isn't a theme setting. They are "
                        + TOKENS.stream().map(Token::name).collect(Collectors.joining(", ")));
            }
            token.set().accept(theme, read(token, entry.getValue()));
        }
        return theme;
    }

    private static Object read(Token token, Object json) {
        if (token.isEffect()) {
            if (json == null) return null;
            try {
                return BackgroundKinds.write(BackgroundKinds.read(json, Path.of("")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + token.name() + "\": " + e.getMessage(), e);
            }
        }
        if (token.isPaint()) {
            try {
                return PaintCodec.HEX.read(json);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + token.name() + "\": " + e.getMessage(), e);
            }
        }
        if (token.isColor()) {
            try {
                return PropertyTypes.codecFor(Color.class).read(json);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + token.name() + "\": " + e.getMessage(), e);
            }
        }
        if (!(json instanceof Number n)) throw new IllegalArgumentException("\"" + token.name() + "\" must be a number, not " + json);
        return Math.clamp(n.floatValue(), token.min(), token.max());
    }

    /**
     * The file's text for {@code theme}: every setting, colors as hex. An effect that is none is left out.
     */
    public static String toJson(Theme theme) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        for (Token token : TOKENS) {
            Object value = token.get().apply(theme);
            if (value == null) continue;
            Object json = value instanceof Paint paint ? PaintCodec.HEX.write(paint) : value;
            if (json != null) file.put(token.name(), json);
        }
        return JsonWriter.write(file) + "\n";
    }

    /**
     * Writes {@code theme} to a file, creating parent folders. It goes to a temporary file first and is moved into place,
     * so a crash part way through leaves the old file whole.
     */
    public static void save(Theme theme, Path path) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, toJson(theme));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code #RRGGBB}, or {@code #RRGGBBAA} for a color that isn't fully opaque. */
    public static String hex(Color color) {
        return PaintCodec.hex(color);
    }
}
