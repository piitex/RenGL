package me.piitex.engine.installer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Does the installing by running the setup program again, silently: {@code setup.exe /S /DIR=... /PROGRESS}. The setup
 * program unpacks the files, makes the shortcuts, writes the uninstaller and the Apps &amp; features entry; this window
 * only asks the questions and shows the answer. With {@code /PROGRESS} it prints {@code progress=<percent>} lines, and
 * {@code error=<text>} if it fails.
 */
final class SetupProcess {
    private SetupProcess() {
    }

    /** What the person chose. {@code allUsers} installs for everyone, which the setup program asks Windows for administrator rights to do. */
    record Choices(String folder, boolean desktopShortcut, boolean startMenuShortcut, boolean allUsers) {
    }

    sealed interface Line {
    }

    record Progress(int percent) implements Line {
    }

    record Failure(String message) implements Line {
    }

    /** One line of the setup program's output, or null for a line that says nothing about progress. */
    static Line parse(String line) {
        if (line.startsWith("progress=")) {
            try {
                return new Progress(Math.clamp(Integer.parseInt(line.substring("progress=".length()).strip()), 0, 100));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (line.startsWith("error=")) return new Failure(line.substring("error=".length()).strip());
        return null;
    }

    static List<String> command(Path setupProgram, Choices choices) {
        List<String> command = new ArrayList<>(List.of(setupProgram.toString(), "/S", "/DIR=" + choices.folder(),
                "/DESKTOP=" + (choices.desktopShortcut() ? 1 : 0), "/STARTMENU=" + (choices.startMenuShortcut() ? 1 : 0), "/PROGRESS"));
        if (choices.allUsers()) command.add("/ALLUSERS=1");
        return command;
    }

    /**
     * Installs, blocking until the setup program ends, and reports each percent as it arrives (on this thread).
     *
     * @return null when it worked, otherwise what went wrong
     */
    static String run(Path setupProgram, Choices choices, IntConsumer progress) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command(setupProgram, choices)).redirectErrorStream(true).start();
        String error = null;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = out.readLine()) != null; ) {
                switch (parse(line)) {
                    case Progress p -> progress.accept(p.percent());
                    case Failure f -> error = f.message();
                    case null -> {
                    }
                }
            }
        }
        int exit = process.waitFor();
        if (exit == 0) return null;
        return error != null ? error : "The setup program stopped with exit code " + exit + ".";
    }

    /**
     * Stands in for {@link #run} when the window is only being looked at.
     *
     * @param failure null to finish, or the reason to fail with halfway through
     */
    static String simulate(IntConsumer progress, String failure) throws InterruptedException {
        for (int percent = 0; percent <= 100; percent++) {
            if (failure != null && percent == 50) return failure;
            progress.accept(percent);
            Thread.sleep(25);
        }
        return null;
    }
}
