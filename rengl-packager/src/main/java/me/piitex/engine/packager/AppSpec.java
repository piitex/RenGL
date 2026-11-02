package me.piitex.engine.packager;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything that describes the app, as given on the command line.
 *
 * @param javaOptions       JVM options for every platform; {@code $APPDIR} and {@code $ROOTDIR} are expanded at launch
 * @param icons             icon file by extension: {@code ico} (Windows), {@code icns} (macOS), {@code png} (Linux)
 * @param fileAssociations  file types the app opens: the macOS Info.plist lists them, and the Windows setup adds the app to their Open with list
 * @param contentDirs       folders whose contents are copied into the app folder beside the jars (where {@code AppEnvironment} looks for assets)
 * @param shellVerbs        entries the Windows setup adds to Explorer's right-click menu
 * @param documentTypes     the {@code Info.plist} entries (already checked) that replace the generated {@code CFBundleDocumentTypes}, or empty
 */
record AppSpec(String name, String version, String vendor, String description, String identifier,
               Path inputDir, String mainJar, String mainClass,
               List<String> javaOptions, Map<String, Path> icons, List<FileAssociation> fileAssociations, List<Path> contentDirs,
               List<ShellVerb> shellVerbs, String documentTypes) {

    AppSpec(String name, String version, String vendor, String description, String identifier,
            Path inputDir, String mainJar, String mainClass,
            List<String> javaOptions, Map<String, Path> icons, List<FileAssociation> fileAssociations, List<Path> contentDirs) {
        this(name, version, vendor, description, identifier, inputDir, mainJar, mainClass, javaOptions, icons, fileAssociations, contentDirs, List.of(), "");
    }

    record FileAssociation(String extension, String mimeType, String description) {
    }

    /**
     * One entry of the right-click menu: {@code --shell-verb "<target>|<id>|<label>|<args>"}.
     *
     * @param target an extension with its dot ({@code .zip}), or one of the Explorer classes in {@link #CLASSES}
     * @param id     the registry key name; Explorer sorts the verbs of a target by it
     * @param label  the menu text
     * @param args   the command line after the app's exe, e.g. {@code "%1"}
     */
    record ShellVerb(String target, String id, String label, String args) {
        /** Explorer's own names for things that are not one extension. */
        static final Set<String> CLASSES = Set.of("Directory", "Directory\\Background", "Drive", "AllFilesystemObjects", "*");

        static ShellVerb parse(String spec) {
            String[] parts = spec.split("\\|", 4);
            if (parts.length < 4) throw new IllegalArgumentException("--shell-verb expects <target>|<id>|<label>|<args>, got '" + spec + "'");
            String target = parts[0].strip();
            String id = parts[1].strip();
            String label = parts[2].strip();
            String args = parts[3].strip();
            if (!target.matches("\\.[A-Za-z0-9_+-]+") && !CLASSES.contains(target)) {
                throw new IllegalArgumentException("--shell-verb target '" + target + "' must be an extension such as .zip, or one of "
                        + String.join(", ", CLASSES.stream().sorted().toList()));
            }
            if (!id.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("--shell-verb id '" + id + "' may only use letters, digits, '.', '_' and '-'");
            if (label.isEmpty()) throw new IllegalArgumentException("--shell-verb '" + id + "' has no label");
            for (String text : List.of(label, args)) {
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) throw new IllegalArgumentException("--shell-verb '" + id + "' must be on one line");
            }
            return new ShellVerb(target.startsWith(".") ? target.toLowerCase(Locale.ROOT) : target, id, label, args);
        }

        /** The key under {@code Software\Classes}, which both hives share, that holds the verb. */
        String registryKey() {
            String base = target.startsWith(".") ? "SystemFileAssociations\\" + target : target;
            return base + "\\shell\\" + id;
        }
    }
}
