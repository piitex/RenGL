package me.piitex.engine.installer;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The installer's window: options, then progress, then "installed" (or the reason it failed). The pages are containers
 * that replace each other in the one window.
 */
final class InstallerWindow {
    private static final int WIDTH = 560;
    private static final int HEIGHT = 380;
    private static final Color ERROR = new Color(0.90f, 0.30f, 0.30f, 1f);

    private enum State {OPTIONS, WORKING, DONE, FAILED}

    private final SetupInfo info;
    private final Path setupProgram;   // null: the window is only being looked at, and installs nothing
    private final Path readyFile;      // created once the window is up, so the setup program can drop its "please wait" box
    private String previewFailure;     // preview only: the reason the first pretend install fails with, or null to succeed
    private boolean hidden;            // tests drive the window with posted messages and don't need it on screen

    private final String perUserFolder;   // what the install folder starts as; for everyone it starts as the Program Files one
    private SetupProcess.Choices choices;
    private Window window;
    private Container page;
    private ProgressBarOverlay bar;
    private TextOverlay status;
    private CheckBoxOverlay launchBox;
    private volatile State state = State.OPTIONS;
    private volatile boolean shown;
    private int exitCode;

    InstallerWindow(SetupInfo info, Path setupProgram, String folder, Path readyFile, String previewFailure) {
        this.info = info;
        this.setupProgram = setupProgram;
        this.readyFile = readyFile;
        this.previewFailure = previewFailure;
        this.perUserFolder = folder;
        this.choices = new SetupProcess.Choices(folder, true, true, false);
    }

    void keepHidden() {
        hidden = true;
    }

    /** For tests: closes the window whatever page it is on. */
    void forceClose() {
        if (window != null) window.close();
    }

    /** True once the window has been on screen. A failure before that is the setup program's cue to show its own wizard. */
    boolean wasShown() {
        return shown;
    }

    /** Shows the window and returns when it closes: 0 when it was closed or installed, 1 when the install failed. */
    int run() {
        window = new Window(new WindowOptions(info.name() + " Setup").setDimensions(WIDTH, HEIGHT).setResizable(false).setSplashEnabled(false));
        window.onCloseRequest(() -> {
            if (state != State.WORKING) window.close();   // the files are half-written until the setup program ends
        });
        if (hidden) window.hide();
        show(optionsPage(null));
        Scheduler.after(0.3f, this::signalReady);
        new Engine().start(window);
        return exitCode;
    }

    private void signalReady() {
        shown = true;
        if (readyFile == null) return;
        try {
            Files.writeString(readyFile, "ready");
        } catch (IOException e) {
            // The setup program then keeps its box up until this window closes. Nothing else depends on the file.
        }
    }

    private void show(Container next) {
        if (page != null) window.removeContainer(page);
        page = next;
        window.addContainer(next);
    }

    private Container optionsPage(String problemText) {
        Container c = new Container(WIDTH, HEIGHT);
        text(c, "Install " + info.name(), 30f, ink(), 32, 26).setBold(true);
        String who = info.vendor().isBlank() ? "" : "  -  " + info.vendor();
        text(c, "Version " + info.version() + who, 14f, muted(), 32, 72);
        if (!info.description().isBlank()) text(c, info.description(), 14f, ink(), 32, 100).setMaxWidth(WIDTH - 64);

        text(c, "Install location", 14f, ink(), 32, 156);
        TextFieldOverlay folder = new TextFieldOverlay(32, 180, 400, 32);
        folder.setText(choices.folder());
        c.addElement(folder);
        ButtonOverlay browse = new ButtonOverlay("Browse...", 96, 32);
        browse.setPosition(440, 180);
        browse.onAction(() -> browse(folder));
        c.addElement(browse);
        text(c, problemText == null ? "" : problemText, 13f, ERROR, 32, 216);

        CheckBoxOverlay desktop = checkBox(c, "Create a desktop shortcut", choices.desktopShortcut(), 32, 238);
        CheckBoxOverlay menu = checkBox(c, "Add to the Start menu", choices.startMenuShortcut(), 32, 264);
        CheckBoxOverlay everyone = checkBox(c, "Install for all users (asks for administrator permission)", choices.allUsers(), 32, 290);
        everyone.onToggle(all -> {
            // The folder follows the choice, unless the person has typed their own.
            String before = all ? perUserFolder : allUsersFolder();
            if (folder.getText().strip().equalsIgnoreCase(before)) folder.setText(all ? allUsersFolder() : perUserFolder);
        });

        button(c, "Install", 332, 322, () -> install(folder.getText(), desktop.isChecked(), menu.isChecked(), everyone.isChecked()));
        button(c, "Cancel", 440, 322, () -> window.close());
        return c;
    }

    private Container workingPage() {
        Container c = new Container(WIDTH, HEIGHT);
        text(c, "Installing " + info.name(), 30f, ink(), 32, 26).setBold(true);
        bar = new ProgressBarOverlay(WIDTH - 64, 18);
        bar.setPosition(32, 160);
        c.addElement(bar);
        status = text(c, "Starting...", 14f, muted(), 32, 190);
        return c;
    }

    private Container donePage() {
        Container c = new Container(WIDTH, HEIGHT);
        text(c, info.name() + " is installed", 30f, ink(), 32, 26).setBold(true);
        text(c, choices.folder(), 14f, muted(), 32, 72).setMaxWidth(WIDTH - 64);
        launchBox = checkBox(c, "Launch " + info.name(), true, 32, 160);
        button(c, "Finish", 440, 322, this::finish);
        return c;
    }

    private Container failedPage(String message) {
        Container c = new Container(WIDTH, HEIGHT);
        text(c, "The installation failed", 30f, ERROR, 32, 26).setBold(true);
        text(c, message, 14f, ink(), 32, 84).setMaxWidth(WIDTH - 64);
        button(c, "Back", 332, 322, () -> {
            state = State.OPTIONS;
            show(optionsPage(null));
        });
        button(c, "Close", 440, 322, () -> window.close());
        return c;
    }

    private void browse(TextFieldOverlay folder) {
        FileDialog dialog = FileDialog.pickFolder().onSelect(path -> folder.setText(path.toString()));
        try {
            dialog.defaultPath(Path.of(folder.getText()));
        } catch (InvalidPathException e) {
            // Open it wherever the dialog likes.
        }
        dialog.show();
    }

    private String allUsersFolder() {
        String programs = System.getenv("ProgramFiles");
        return (programs != null ? programs : "C:\\Program Files") + "\\" + info.name();
    }

    private void install(String folderText, boolean desktopShortcut, boolean startMenuShortcut, boolean allUsers) {
        String folder = folderText.strip();
        // A drive's own root, "C:\", keeps its slash; any other trailing slash goes.
        while (folder.length() > 3 && (folder.endsWith("\\") || folder.endsWith("/"))) folder = folder.substring(0, folder.length() - 1);
        choices = new SetupProcess.Choices(folder, desktopShortcut, startMenuShortcut, allUsers);
        if (!isFullPath(folder)) {
            show(optionsPage("Enter a full folder path, such as C:\\Programs\\" + info.name() + "."));
            return;
        }
        state = State.WORKING;
        show(workingPage());
        Thread worker = new Thread(this::work, "Setup-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isFullPath(String folder) {
        if (folder.startsWith("\\\\")) return true;
        try {
            return folder.length() >= 3 && folder.charAt(1) == ':' && Path.of(folder).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** On the worker thread: runs the setup program, then hands the result to the render thread. */
    private void work() {
        String error;
        String failure = previewFailure;
        previewFailure = null;   // fails once, so Back and a second try can be seen too
        try {
            error = setupProgram == null ? SetupProcess.simulate(this::progress, failure) : SetupProcess.run(setupProgram, choices, this::progress);
        } catch (IOException e) {
            error = "Could not start the setup program: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "The installation was interrupted.";
        }
        String result = error;
        Scheduler.runLater(() -> {
            if (result == null) {
                state = State.DONE;
                exitCode = 0;   // an earlier attempt may have failed; the install that counts is this one
                show(donePage());
            } else {
                state = State.FAILED;
                exitCode = 1;
                show(failedPage(result));
            }
        });
    }

    private void progress(int percent) {
        Scheduler.runLater(() -> {
            bar.setProgress(percent / 100.0);
            status.setText(percent < 100 ? "Copying files... " + percent + "%" : "Finishing up...");
        });
    }

    private void finish() {
        try {
            if (launchBox != null && launchBox.isChecked() && setupProgram != null) launch();
        } catch (IOException | RuntimeException e) {
            // It is installed; the shortcut still starts it. Finishing must close the window whatever happens here.
        } finally {
            window.close();
        }
    }

    private void launch() throws IOException {
        new ProcessBuilder(Path.of(choices.folder(), info.exe()).toString())
                .directory(new File(choices.folder()))
                .redirectInput(ProcessBuilder.Redirect.from(new File("NUL")))   // DISCARD is for output only
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    // ---- small builders -------------------------------------------------------------------------------------------

    /** Body text, in whatever the current theme uses for it (the page is built after the theme is set). */
    private static Color ink() {
        return ThemeManager.getCurrent().getText();
    }

    /** Secondary text: the version line, the folder, the progress message. */
    private static Color muted() {
        return ThemeManager.getCurrent().getPlaceholderText();
    }

    private static TextOverlay text(Container c, String text, float size, Color color, double x, double y) {
        TextOverlay overlay = new TextOverlay(text, size, color);
        overlay.setPosition(x, y);
        c.addElement(overlay);
        return overlay;
    }

    private static CheckBoxOverlay checkBox(Container c, String label, boolean checked, double x, double y) {
        CheckBoxOverlay box = new CheckBoxOverlay(20, checked);
        box.setPosition(x, y);
        c.addElement(box);
        text(c, label, 14f, ink(), x + 30, y + 1);
        return box;
    }

    private static void button(Container c, String label, double x, double y, Runnable action) {
        ButtonOverlay button = new ButtonOverlay(label, 100, 36);
        button.setPosition(x, y);
        // The click happens inside the page being replaced, so the swap waits for the next frame.
        button.onAction(() -> Scheduler.runLater(action));
        c.addElement(button);
    }
}
