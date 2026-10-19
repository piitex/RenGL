package me.piitex.engine.tilemap;

import me.piitex.engine.io.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Tiled project file ({@code .tiled-project}): the custom classes a project defines, with the default of each of their
 * members. Tiled doesn't save a member that is left at its default, so a map only holds what was changed; pass the
 * project when loading and the defaults fill in the rest:
 * <pre>{@code
 * TiledProject project = TiledProject.load("assets/game.tiled-project");
 * TileMap map = TileMap.load("assets/levels/forest.tmx", project);
 * float speed = enemy.getProperties().getFloat("speed", 80f);   // the class default when the map doesn't set it
 * }</pre>
 * It applies to a map, layer, tile set, tile or object with a class set, and to a property whose type is a class. Only
 * the class definitions are read; the project's other settings are ignored.
 */
public final class TiledProject {
    /** One member of a class: its name, type and default, and for a member that is a class, which one. */
    record Member(String name, String type, Object value, String propertyType) {
    }

    private final Map<String, List<Member>> classes = new LinkedHashMap<>();
    private final Path source;

    private TiledProject(Path source) {
        this.source = source;
    }

    public static TiledProject load(String path) {
        return load(Path.of(path));
    }

    /**
     * Reads a project file.
     *
     * @throws UncheckedIOException     if the file can't be read
     * @throws IllegalArgumentException if it isn't a Tiled project, naming the file and what is wrong
     */
    public static TiledProject load(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
        TiledProject project = new TiledProject(path);
        try {
            if (!(Json.parse(text) instanceof Map<?, ?> root)) throw new IllegalArgumentException("not a Tiled project: expected a JSON object");
            if (root.get("propertyTypes") instanceof List<?> types) {
                for (Object type : types) {
                    if (!(type instanceof Map<?, ?> definition) || !"class".equals(definition.get("type"))) continue;
                    List<Member> members = new ArrayList<>();
                    if (definition.get("members") instanceof List<?> list) {
                        for (Object m : list) {
                            if (!(m instanceof Map<?, ?> member)) continue;
                            members.add(new Member(String.valueOf(member.get("name")), member.get("type") == null ? "string" : String.valueOf(member.get("type")),
                                    member.get("value"), member.get("propertyType") == null ? "" : String.valueOf(member.get("propertyType"))));
                        }
                    }
                    project.classes.put(String.valueOf(definition.get("name")), members);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
        return project;
    }

    /**
     * The file the project was read from.
     */
    public Path getSource() {
        return source;
    }

    /**
     * The names of the classes the project defines.
     */
    public Set<String> getClassNames() {
        return java.util.Collections.unmodifiableSet(classes.keySet());
    }

    public boolean hasClass(String name) {
        return classes.containsKey(name);
    }

    List<Member> members(String className) {
        return classes.getOrDefault(className, List.of());
    }

    Member member(String className, String name) {
        for (Member member : members(className)) if (member.name().equals(name)) return member;
        return null;
    }
}
