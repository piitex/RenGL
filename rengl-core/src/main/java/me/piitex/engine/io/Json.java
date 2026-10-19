package me.piitex.engine.io;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, as used by {@link me.piitex.engine.graphics.TextureAtlas} and
 * {@link me.piitex.engine.tilemap.TileMap}. Objects become {@link LinkedHashMap}s, keeping their key order, arrays
 * {@link List}s, numbers {@link Double}s, and the rest {@link String}, {@link Boolean} or null.
 * <pre>{@code
 * Map<?, ?> level = (Map<?, ?>) Json.parse(Files.readString(path));
 * }</pre>
 */
public final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Reads one JSON value.
     *
     * @throws IllegalArgumentException if the text isn't valid JSON, giving the line and column
     */
    public static Object parse(String text) {
        Json json = new Json(text);
        json.skipSpace();
        Object value = json.value();
        json.skipSpace();
        if (json.at < text.length()) throw json.error("unexpected text after the end");
        return value;
    }

    private Object value() {
        if (at >= text.length()) throw error("unexpected end");
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw error("unexpected '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek('}')) return map;
        while (true) {
            skipSpace();
            if (at >= text.length() || text.charAt(at) != '"') throw error("expected a key");
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, value());
            skipSpace();
            if (peek('}')) return map;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        skipSpace();
        if (peek(']')) return list;
        while (true) {
            skipSpace();
            list.add(value());
            skipSpace();
            if (peek(']')) return list;
            expect(',');
        }
    }

    private String string() {
        at++; // opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error("unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) throw error("unterminated string");
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) throw error("bad \\u escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("bad \\u escape");
                    }
                    at += 4;
                }
                default -> throw error("bad escape \\" + escaped);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            at = start;
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("unexpected text");
        at += word.length();
        return value;
    }

    private boolean peek(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private IllegalArgumentException error(String message) {
        int line = 1, column = 1;
        for (int i = 0; i < Math.min(at, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new IllegalArgumentException("Bad JSON at line " + line + ", column " + column + ": " + message);
    }
}
