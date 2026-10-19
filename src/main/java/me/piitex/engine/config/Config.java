package me.piitex.engine.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A dependency-free configuration tree that reads flat properties and nested blocks in one file.
 * <pre>
 * # comment
 * engine-vsync=true            # flat key=value
 * window.width=1280            # dotted keys nest: same as a "window" block with "width"
 *
 * entry:                       # nested block, indent with spaces
 *   item: "value"
 *   limits:
 *     max: 10
 * tags:                        # list of scalars
 *   - one
 *   - two
 * sizes: [1, 2, 3]             # inline list
 * </pre>
 * Values are quoted strings ({@code "..."} with escapes, or literal {@code '...'}), booleans,
 * integers ({@code long}), decimals ({@code double}) or plain text. A {@code #} starts a comment
 * only when preceded by whitespace, so {@code color=#ff0000} works unquoted after {@code =}
 * (after {@code :} quote it, since {@code key: #fff} is a comment). Lists hold scalars only.
 * <p>
 * Everything is addressed by dotted path: {@code config.getInt("entry.limits.max", 5)}.
 * Saving rewrites the file from the tree, so comments and original ordering of dotted keys are not preserved.
 * Not thread-safe.
 */
public final class Config {
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_][\\w\\-./@]*");

    private final Map<String, Object> root;
    private Path sourcePath;

    public Config() {
        this(new LinkedHashMap<>());
    }

    private Config(Map<String, Object> root) {
        this.root = root;
    }

    // ---------------------------------------------------------------- loading / saving

    public static Config parse(String text) {
        return new Config(ConfigParser.parse(text));
    }

    /**
     * Loads a file; throws {@link java.nio.file.NoSuchFileException} if missing.
     */
    public static Config load(Path path) throws IOException {
        Config config = parse(Files.readString(path, StandardCharsets.UTF_8));
        config.sourcePath = path;
        return config;
    }

    /**
     * Loads a file, or returns an empty config if it does not exist yet.
     */
    public static Config loadOrEmpty(Path path) throws IOException {
        if (Files.exists(path)) return load(path);
        Config config = new Config();
        config.sourcePath = path;
        return config;
    }

    /**
     * First-run helper: loads the file (or starts empty if it does not exist), lets {@code defaults} fill in
     * anything missing (typically with {@link #setDefault}), and saves the file if it was absent or the defaults
     * changed something. Returns the resulting config.
     */
    public static Config loadOrCreate(Path path, Consumer<Config> defaults) throws IOException {
        Config config = loadOrEmpty(path);
        boolean existed = Files.exists(path);
        String before = config.toString();
        defaults.accept(config);
        if (!existed || !before.equals(config.toString())) config.save(path);
        return config;
    }

    /**
     * True if nothing is set. A config loaded from a missing file (see {@link #loadOrEmpty}) is empty.
     */
    public boolean isEmpty() {
        return root.isEmpty();
    }

    /**
     * The file this config saves to with {@link #save()}, or null if it has none. Set by load, save(Path) and the environment.
     */
    public Path getSourcePath() {
        return sourcePath;
    }

    /**
     * Sets the file {@link #save()} writes to.
     */
    public Config setSourcePath(Path path) {
        this.sourcePath = path;
        return this;
    }

    /**
     * Saves to the file this config was loaded from or last saved to. A config loaded through
     * {@link me.piitex.engine.io.AppEnvironment} is bound to its data-directory file, so this is all you need.
     *
     * @throws IllegalStateException if the config has no source path (for example one created with {@code new Config()})
     */
    public void save() throws IOException {
        if (sourcePath == null) {
            throw new IllegalStateException("This config has no file. Use save(Path) or setSourcePath(Path) first.");
        }
        save(sourcePath);
    }

    /**
     * Writes the config, replacing the file atomically so a crash never leaves it half-written. Later {@link #save()} calls use this path.
     */
    public void save(Path path) throws IOException {
        this.sourcePath = path;
        Path abs = path.toAbsolutePath();
        if (abs.getParent() != null) Files.createDirectories(abs.getParent());
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.writeString(tmp, toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Raw value at {@code path} (String, Boolean, Long, Double, List, or a nested section map), or null.
     */
    public Object get(String path) {
        Object cur = root;
        for (String part : split(path)) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    public boolean has(String path) {
        return get(path) != null;
    }

    public String getString(String path, String def) {
        Object v = get(path);
        return v == null || v instanceof Map<?, ?> || v instanceof List<?> ? def : format(v);
    }

    public String getString(String path) {
        return getString(path, null);
    }

    /**
     * The int at {@code path}, or null if missing or not a number.
     */
    public Integer getInt(String path) {
        Long v = getLong(path);
        return v == null ? null : v.intValue();
    }

    public int getInt(String path, int def) {
        Integer v = getInt(path);
        return v == null ? def : v;
    }

    /**
     * The long at {@code path}, or null if missing or not a number.
     */
    public Long getLong(String path) {
        Object v = get(path);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public long getLong(String path, long def) {
        Long v = getLong(path);
        return v == null ? def : v;
    }

    /**
     * The double at {@code path}, or null if missing or not a number.
     */
    public Double getDouble(String path) {
        Object v = get(path);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public double getDouble(String path, double def) {
        Double v = getDouble(path);
        return v == null ? def : v;
    }

    /**
     * The float at {@code path}, or null if missing or not a number.
     */
    public Float getFloat(String path) {
        Object v = get(path);
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof String s) {
            try {
                return Float.parseFloat(s.strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public double getFloat(String path, double def) {
        Float v = getFloat(path);
        return v == null ? def : v;
    }

    /**
     * The boolean at {@code path}, or null if missing or not {@code true}/{@code false}.
     */
    public Boolean getBoolean(String path) {
        Object v = get(path);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return null;
    }

    public boolean getBoolean(String path, boolean def) {
        Boolean v = getBoolean(path);
        return v == null ? def : v;
    }

    /**
     * A list at {@code path}. A single scalar is returned as a one-element list; missing gives an empty list.
     */
    public List<Object> getList(String path) {
        Object v = get(path);
        if (v instanceof List<?> l) return Collections.unmodifiableList(new ArrayList<>(l));
        if (v == null || v instanceof Map<?, ?>) return List.of();
        return List.of(v);
    }

    public List<String> getStringList(String path) {
        List<String> out = new ArrayList<>();
        for (Object o : getList(path)) out.add(format(o));
        return out;
    }

    /**
     * A live view of the nested section at {@code path}; changes write through to this config.
     */
    @SuppressWarnings("unchecked")
    public Optional<Config> getSection(String path) {
        return get(path) instanceof Map<?, ?> m ? Optional.of(new Config((Map<String, Object>) m)) : Optional.empty();
    }

    /**
     * Keys of this config. Deep returns dotted paths of every leaf value; shallow returns top-level names.
     */
    public Set<String> getKeys(boolean deep) {
        Set<String> out = new LinkedHashSet<>();
        collectKeys(root, "", deep, out);
        return out;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Sets a value, creating intermediate sections as needed. Accepts String, Boolean, Number,
     * or a List of those. A null value removes the entry.
     */
    @SuppressWarnings("unchecked")
    public Config set(String path, Object value) {
        if (value == null) return remove(path);
        Object stored = value;
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof String || o instanceof Boolean || o instanceof Number)) {
                    throw new IllegalArgumentException("Lists may only contain strings, booleans and numbers: " + o);
                }
                copy.add(normalize(o));
            }
            stored = copy;
        } else if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            stored = normalize(value);
        } else {
            throw new IllegalArgumentException("Unsupported config value type: " + value.getClass().getName());
        }

        String[] parts = split(path);
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], stored);
        return this;
    }

    /**
     * Sets a value only if nothing exists at {@code path}; use to seed defaults before saving.
     */
    public Config setDefault(String path, Object value) {
        return has(path) ? this : set(path, value);
    }

    /**
     * Ensures a section exists at {@code path} and returns a live view of it.
     */
    @SuppressWarnings("unchecked")
    public Config createSection(String path) {
        Map<String, Object> cur = root;
        for (String p : split(path)) {
            Object next = cur.get(p);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(p, next);
            }
            cur = (Map<String, Object>) next;
        }
        return new Config(cur);
    }

    public Config remove(String path) {
        String[] parts = split(path);
        Object cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(cur instanceof Map<?, ?> m)) return this;
            cur = m.get(parts[i]);
        }
        if (cur instanceof Map<?, ?> m) m.remove(parts[parts.length - 1]);
        return this;
    }

    // ---------------------------------------------------------------- serialization

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        write(root, 0, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Map<String, Object> map, int indent, StringBuilder sb) {
        String pad = " ".repeat(indent);
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            sb.append(pad).append(e.getKey());
            if (v instanceof Map<?, ?> m) {
                sb.append(":\n");
                write((Map<String, Object>) m, indent + 2, sb);
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) {
                    sb.append(": []\n");
                } else {
                    sb.append(":\n");
                    for (Object item : l) sb.append(pad).append("  - ").append(render(item)).append('\n');
                }
            } else {
                sb.append(indent == 0 ? "=" : ": ").append(render(v)).append('\n');
            }
        }
    }

    private static String render(Object v) {
        if (!(v instanceof String s)) return format(v);
        boolean plain = PLAIN.matcher(s).matches() && ConfigParser.scalar(s) instanceof String;
        if (plain) return s;
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- helpers

    private static void collectKeys(Map<?, ?> map, String prefix, boolean deep, Set<String> out) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = prefix + e.getKey();
            if (deep && e.getValue() instanceof Map<?, ?> m) collectKeys(m, key + ".", true, out);
            else out.add(key);
        }
    }

    private static Object normalize(Object o) {
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) return ((Number) o).longValue();
        if (o instanceof Float f) return f.doubleValue();
        return o;
    }

    private static String format(Object v) {
        return String.valueOf(v);
    }

    private static String[] split(String path) {
        if (path == null || path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("Invalid config path: '" + path + "'");
        }
        return path.split("\\.");
    }
}
