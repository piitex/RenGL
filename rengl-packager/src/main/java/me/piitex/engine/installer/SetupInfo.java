package me.piitex.engine.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the app being installed calls itself, from the {@code setup.ini} the packager puts first in the setup program's payload.
 *
 * @param exe the app's launcher, relative to the install folder
 */
record SetupInfo(String name, String version, String vendor, String description, String exe) {

    /** What the window shows when it is run on its own, to look at it: nothing is installed. */
    static SetupInfo preview() {
        return new SetupInfo("Sample App", "1.0.0", "RenGL", "A preview of the installer window. Nothing is installed.", "SampleApp.exe");
    }

    static SetupInfo parse(String ini) {
        Map<String, String> values = new HashMap<>();
        for (String line : ini.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        String name = values.getOrDefault("name", "App");
        return new SetupInfo(name, values.getOrDefault("version", ""), values.getOrDefault("vendor", ""),
                values.getOrDefault("description", ""), values.getOrDefault("exe", name + ".exe"));
    }

    /**
     * Reads {@code setup.ini} out of the zip at the end of the setup program. {@link ZipFile} finds the end of the zip and
     * takes whatever is in front of it, here the program itself, as a prefix to skip.
     */
    static SetupInfo read(Path setupProgram) throws IOException {
        try (ZipFile zip = new ZipFile(setupProgram.toFile())) {
            ZipEntry entry = zip.getEntry("setup.ini");
            if (entry == null) throw new IOException(setupProgram + " has no setup.ini");
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
