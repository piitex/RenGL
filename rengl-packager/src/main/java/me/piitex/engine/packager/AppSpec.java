package me.piitex.engine.packager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything that describes the app, as given on the command line.
 *
 * @param javaOptions       JVM options for every platform; {@code $APPDIR} and {@code $ROOTDIR} are expanded at launch
 * @param icons             icon file by extension: {@code ico} (Windows), {@code icns} (macOS), {@code png} (Linux)
 * @param fileAssociations  file types the app opens, written into the macOS Info.plist
 * @param contentDirs       folders whose contents are copied into the app folder beside the jars (where {@code AppEnvironment} looks for assets)
 */
record AppSpec(String name, String version, String vendor, String description, String identifier,
               Path inputDir, String mainJar, String mainClass,
               List<String> javaOptions, Map<String, Path> icons, List<FileAssociation> fileAssociations, List<Path> contentDirs) {

    record FileAssociation(String extension, String mimeType, String description) {
    }
}
