package me.piitex.engine.packager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs the running JDK's own command-line tools ({@code jlink}, {@code jdeps}). */
final class Tools {
    private Tools() {
    }

    private static Path tool(String name) {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? name + ".exe" : name;
        Path path = Path.of(System.getProperty("java.home"), "bin", exe);
        if (!Files.exists(path)) throw new IllegalStateException(name + " not found at " + path + ". Run the packager with a full JDK, not a JRE.");
        return path;
    }

    /** Modules the jars need, found with {@code jdeps}. Falls back to {@code null} (the caller picks a default) if it cannot tell. */
    static List<String> detectModules(List<Path> jars) {
        List<String> command = new ArrayList<>(List.of(tool("jdeps").toString(), "--print-module-deps", "--ignore-missing-deps",
                "--multi-release", String.valueOf(Runtime.version().feature())));
        jars.forEach(jar -> command.add(jar.toString()));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0 || out.isEmpty()) return null;
            return List.of(out.split(","));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Builds a runtime for {@code target} out of that platform's {@code jmods}. */
    static void jlink(Path jmods, List<String> modules, Path output) throws IOException, InterruptedException {
        List<String> command = List.of(tool("jlink").toString(),
                "--module-path", jmods.toString(),
                "--add-modules", String.join(",", modules),
                "--strip-debug", "--no-header-files", "--no-man-pages",
                "--compress", "zip-6",
                "--output", output.toString());
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IOException("jlink failed (see its output above). If it complains about a version mismatch, the target JDK's jmods are a "
                    + "different release from the JDK running the packager; pass --jdk <platform>=<path> with a matching one.");
        }
    }
}
