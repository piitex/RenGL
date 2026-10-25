package me.piitex.engine.installer;

import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point of the installer window. The setup program (packager {@code --installer-ui}) starts it with
 * <pre>
 *   --setup &lt;setup.exe&gt;   the setup program itself, which holds the payload and does the installing
 *   --dir &lt;folder&gt;        the install folder to offer
 *   --ready &lt;file&gt;        a file to create once the window is up
 * </pre>
 * and takes the exit code: 0 when the window closed (installed or cancelled), 1 when the install failed, and
 * {@value #EXIT_NO_WINDOW} when no window could be shown, which makes the setup program fall back to its own wizard.
 * Started without {@code --setup} it shows a preview of the window that installs nothing; {@code --fail <reason>} makes
 * the first pretend install fail with that reason, to see the failed page.
 */
public final class InstallerMain {
    /** The code the setup program (setup.c) reads as "use the built-in wizard instead". */
    public static final int EXIT_NO_WINDOW = 3;

    private InstallerMain() {
    }

    static void main(String[] args) {
        InstallerWindow window = null;
        int exit;
        try {
            Map<String, String> options = options(args);
            Path setup = options.containsKey("setup") ? Path.of(options.get("setup")) : null;
            SetupInfo info = setup == null ? SetupInfo.preview() : SetupInfo.read(setup);
            String folder = options.getOrDefault("dir", defaultFolder(info));
            Path ready = options.containsKey("ready") ? Path.of(options.get("ready")) : null;
            ThemeManager.setCurrent(new DarkTheme());
            String failure = setup == null ? options.get("fail") : null;
            window = new InstallerWindow(info, setup, folder, ready, failure);
            exit = window.run();
            if (!window.wasShown()) exit = EXIT_NO_WINDOW;
        } catch (Throwable t) {
            t.printStackTrace();
            exit = window != null && window.wasShown() ? 1 : EXIT_NO_WINDOW;
        }
        System.exit(exit);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static String defaultFolder(SetupInfo info) {
        String local = System.getenv("LOCALAPPDATA");
        return (local != null ? local : System.getProperty("user.home")) + "\\Programs\\" + info.name();
    }
}
