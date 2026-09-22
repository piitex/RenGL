package me.piitex.engine.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser for the {@link Config} text format. Package-private; use {@link Config#parse(String)}.
 */
final class ConfigParser {
    private static final Pattern NUMBER = Pattern.compile("-?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?");
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    private record Frame(int indent, Object container) {
    }

    private record Pending(Map<String, Object> parent, String key, int indent) {
    }

    private ConfigParser() {
    }

    static Map<String, Object> parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(0, root));
        Pending pending = null;

        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\\R", -1);

        for (int n = 1; n <= lines.length; n++) {
            String line = lines[n - 1];
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String content = line.substring(indent).stripTrailing();
            if (content.isEmpty() || content.startsWith("#")) continue;
            if (content.charAt(0) == '\t') throw new ConfigException(n, "tabs are not allowed for indentation");

            boolean isItem = content.equals("-") || content.startsWith("- ");

            if (pending != null) {
                if (indent > pending.indent) {
                    Object container = isItem ? new ArrayList<Object>() : new LinkedHashMap<String, Object>();
                    pending.parent.put(pending.key, container);
                    stack.push(new Frame(indent, container));
                } else {
                    pending.parent.put(pending.key, new LinkedHashMap<String, Object>());
                }
                pending = null;
            }

            while (stack.size() > 1 && stack.peek().indent > indent) stack.pop();
            Frame frame = stack.peek();
            if (frame.indent != indent) throw new ConfigException(n, "inconsistent indentation");

            if (isItem) {
                if (!(frame.container instanceof List<?>)) throw new ConfigException(n, "list item outside of a list");
                @SuppressWarnings("unchecked") List<Object> list = (List<Object>) frame.container;
                Object value = parseValue(content.length() > 1 ? content.substring(1) : "", n);
                if (value instanceof List<?>) throw new ConfigException(n, "nested lists are not supported");
                list.add(value);
                continue;
            }
            if (!(frame.container instanceof Map<?, ?>))
                throw new ConfigException(n, "expected a list item ('- value')");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) frame.container;

            int sep = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '=' || c == ':') {
                    sep = i;
                    break;
                }
            }
            if (sep <= 0) throw new ConfigException(n, "expected 'key=value' or 'key: value'");
            String key = content.substring(0, sep).strip();
            if (key.isEmpty() || key.matches(".*[\\s\"'#\\[\\]].*") || key.startsWith(".") || key.endsWith(".") || key.contains("..")) {
                throw new ConfigException(n, "invalid key '" + key + "'");
            }
            String rest = content.substring(sep + 1);

            String[] parts = key.split("\\.");
            Map<String, Object> parent = map;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = parent.get(parts[i]);
                if (!(next instanceof Map<?, ?>)) {
                    next = new LinkedHashMap<String, Object>();
                    parent.put(parts[i], next);
                }
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) next;
                parent = m;
            }
            String leaf = parts[parts.length - 1];

            if (content.charAt(sep) == ':' && stripComment(rest).isBlank()) {
                pending = new Pending(parent, leaf, indent);
            } else {
                parent.put(leaf, parseValue(rest, n));
            }
        }
        if (pending != null) pending.parent.put(pending.key, new LinkedHashMap<String, Object>());
        return root;
    }

    private static String stripComment(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '#' && Character.isWhitespace(s.charAt(i - 1))) return s.substring(0, i);
        }
        return s;
    }

    /**
     * Parses one value: quoted string, inline list, or plain scalar.
     */
    private static Object parseValue(String raw, int line) {
        String s = raw.stripLeading();
        if (s.isEmpty()) return "";
        char first = s.charAt(0);
        if (first == '"' || first == '\'') {
            StringBuilder out = new StringBuilder();
            int end = readQuoted(s, 0, out, line);
            requireOnlyComment(s.substring(end), line);
            return out.toString();
        }
        if (first == '[') {
            List<Object> items = new ArrayList<>();
            int i = 1;
            while (true) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                if (i >= s.length()) throw new ConfigException(line, "unterminated list, missing ']'");
                if (s.charAt(i) == ']') {
                    i++;
                    break;
                }
                if (s.charAt(i) == '"' || s.charAt(i) == '\'') {
                    StringBuilder out = new StringBuilder();
                    i = readQuoted(s, i, out, line);
                    items.add(out.toString());
                } else {
                    int start = i;
                    while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ']') i++;
                    items.add(scalar(s.substring(start, i).strip()));
                }
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
            requireOnlyComment(s.substring(i), line);
            return items;
        }
        return scalar(stripComment(s).strip());
    }

    /**
     * Reads a quoted string starting at {@code start}; returns the index just past the closing quote.
     */
    private static int readQuoted(String s, int start, StringBuilder out, int line) {
        char quote = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote == '"' && c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(++i);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"', '\\' -> out.append(e);
                    default -> throw new ConfigException(line, "unknown escape '\\" + e + "'");
                }
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                } else return i + 1;
            } else {
                out.append(c);
            }
        }
        throw new ConfigException(line, "unterminated string");
    }

    private static void requireOnlyComment(String rest, int line) {
        String r = rest.strip();
        if (!r.isEmpty() && !(r.startsWith("#") && (rest.isEmpty() || Character.isWhitespace(rest.charAt(0))))) {
            throw new ConfigException(line, "unexpected text after value: " + r);
        }
    }

    static Object scalar(String s) {
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        if (NUMBER.matcher(s).matches()) {
            try {
                return INTEGER.matcher(s).matches() ? (Object) Long.parseLong(s) : (Object) Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return s; // integer too large for a long
            }
        }
        return s;
    }
}
