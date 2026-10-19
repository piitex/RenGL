package me.piitex.engine.input;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;

import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.lwjgl.util.nfd.NativeFileDialog.*;

/**
 * The operating system's own open / save / choose-folder dialog, for importing from and exporting to the file system.
 * <pre>{@code
 * FileDialog.open()
 *         .filter("Images", "png", "jpg")
 *         .onSelect(path -> load(path))
 *         .show();
 *
 * FileDialog.save()
 *         .filter("Text", "txt")
 *         .defaultName("notes.txt")
 *         .onSelect(path -> Files.writeString(path, text))   // wrap checked exceptions yourself
 *         .show();
 * }</pre>
 * {@link #show()} returns immediately. The dialog is modal to the user (the window under it can't be used), but
 * the engine keeps rendering, and the result arrives later through {@link #onSelect}, {@link #onCancel} or
 * {@link #onError}. Those callbacks run on the render thread like every other UI callback, so they can touch
 * elements directly.
 * <p>
 * <b>Threading.</b> Native dialogs must be shown from the main thread (macOS requires it), so this class hands
 * requests over the same way {@link Clipboard} does: {@link #show()} queues, the main loop's {@link #pump()}
 * runs the dialog, and the render loop's {@link #drainCallbacks()} delivers the result. Requests made while a
 * dialog is open queue up and open one after another.
 * <p>
 * This class only chooses paths. It never reads or writes the file itself, and it does not confirm overwrites
 * beyond what the OS's own save dialog does.
 */
public final class FileDialog {
    private static final Logger log = LogManager.getLogger("Engine");

    /**
     * What the dialog is for.
     */
    private enum Kind {OPEN, OPEN_MULTIPLE, SAVE, PICK_FOLDER}

    /**
     * A filter row in the dialog's type drop-down: a display name plus the extensions it matches, without dots.
     */
    private record Filter(String name, String extensions) {
    }

    private static final Queue<FileDialog> requests = new ConcurrentLinkedQueue<>();
    private static final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private static volatile boolean showing = false;
    private static boolean initialised = false; // main thread only
    private static boolean unavailable = false; // main thread only: NFD_Init failed, don't retry every request

    private final Kind kind;
    private final List<Filter> filters = new ArrayList<>();
    private String defaultPath;
    private String defaultName;
    private Consumer<Path> onSelect;
    private Consumer<List<Path>> onSelectAll;
    private Runnable onCancel;
    private Consumer<String> onError;

    private FileDialog(Kind kind) {
        this.kind = kind;
    }

    /**
     * A dialog that picks one existing file.
     */
    public static FileDialog open() {
        return new FileDialog(Kind.OPEN);
    }

    /**
     * A dialog that picks one or more existing files.
     */
    public static FileDialog openMultiple() {
        return new FileDialog(Kind.OPEN_MULTIPLE);
    }

    /**
     * A dialog that picks where to write a file. The file may not exist yet.
     */
    public static FileDialog save() {
        return new FileDialog(Kind.SAVE);
    }

    /**
     * A dialog that picks a folder.
     */
    public static FileDialog pickFolder() {
        return new FileDialog(Kind.PICK_FOLDER);
    }

    /**
     * Adds a row to the file-type drop-down. Extensions are given without the dot ({@code "png", "jpg"}). With no
     * filters every file is shown. Ignored by {@link #pickFolder()}. Some platforms show only the first filter's
     * files until the user changes the drop-down, so put the most likely one first.
     */
    public FileDialog filter(String name, String... extensions) {
        if (extensions.length > 0) {
            filters.add(new Filter(name, String.join(",", extensions)));
        }
        return this;
    }

    /**
     * The folder the dialog opens in. Should exist; otherwise the OS picks.
     */
    public FileDialog defaultPath(Path path) {
        this.defaultPath = path == null ? null : path.toAbsolutePath().toString();
        return this;
    }

    /**
     * The suggested file name in a {@link #save()} dialog. Ignored by the other kinds.
     */
    public FileDialog defaultName(String name) {
        this.defaultName = name;
        return this;
    }

    /**
     * Called with the chosen path. For {@link #openMultiple()} it is called once per chosen file (in the order the
     * OS returned them); use {@link #onSelectAll} to get them together.
     */
    public FileDialog onSelect(Consumer<Path> onSelect) {
        this.onSelect = onSelect;
        return this;
    }

    /**
     * Called once with every chosen path. For the single-choice kinds the list has one entry.
     */
    public FileDialog onSelectAll(Consumer<List<Path>> onSelectAll) {
        this.onSelectAll = onSelectAll;
        return this;
    }

    /**
     * Called when the user closes the dialog without choosing.
     */
    public FileDialog onCancel(Runnable onCancel) {
        this.onCancel = onCancel;
        return this;
    }

    /**
     * Called with a message when the dialog could not be shown, e.g. the platform has no file-dialog support
     * installed. Without this, the failure is only logged.
     */
    public FileDialog onError(Consumer<String> onError) {
        this.onError = onError;
        return this;
    }

    /**
     * Queues the dialog and returns immediately. Safe to call from any thread.
     */
    public void show() {
        requests.add(this);
    }

    /**
     * True from the moment a dialog opens until its result has been queued for delivery.
     */
    public static boolean isShowing() {
        return showing;
    }

    /**
     * Main thread only. Opens the next queued dialog, blocking until the user closes it. Call once per Engine main-loop iteration.
     */
    public static void pump() {
        FileDialog next = requests.poll();
        if (next == null) return;

        showing = true;
        try {
            next.run();
        } finally {
            showing = false;
        }
    }

    /**
     * Render thread only. Runs the callbacks of dialogs that have finished. Call once per frame.
     */
    public static void drainCallbacks() {
        Runnable result;
        while ((result = results.poll()) != null) {
            result.run();
        }
    }

    /**
     * Main thread only. Releases the native library. Call once after the main loop ends.
     */
    public static void shutdown() {
        if (initialised) {
            NFD_Quit();
            initialised = false;
        }
    }

    private void run() {
        if (unavailable || !initialise()) {
            fail("Native file dialogs are not available on this system.");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            NFDFilterItem.Buffer filterBuffer = null;
            if (!filters.isEmpty() && kind != Kind.PICK_FOLDER) {
                filterBuffer = NFDFilterItem.malloc(filters.size(), stack);
                for (int i = 0; i < filters.size(); i++) {
                    filterBuffer.get(i)
                            .name(stack.UTF8(filters.get(i).name()))
                            .spec(stack.UTF8(filters.get(i).extensions()));
                }
            }

            PointerBuffer out = stack.mallocPointer(1);
            int status = switch (kind) {
                case OPEN -> NFD_OpenDialog(out, filterBuffer, defaultPath);
                case OPEN_MULTIPLE -> NFD_OpenDialogMultiple(out, filterBuffer, defaultPath);
                case SAVE -> NFD_SaveDialog(out, filterBuffer, defaultPath, defaultName);
                case PICK_FOLDER -> NFD_PickFolder(out, defaultPath);
            };

            if (status == NFD_CANCEL) {
                if (onCancel != null) results.add(onCancel);
            } else if (status != NFD_OKAY) {
                fail(String.valueOf(NFD_GetError()));
            } else if (kind == Kind.OPEN_MULTIPLE) {
                deliver(readPathSet(out.get(0), stack));
            } else {
                String path = out.getStringUTF8(0);
                NFD_FreePath(out.get(0));
                deliver(List.of(Path.of(path)));
            }
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    private static boolean initialise() {
        if (initialised) return true;
        if (NFD_Init() != NFD_OKAY) {
            log.error("NFD_Init failed: {}", NFD_GetError());
            unavailable = true;
            return false;
        }
        initialised = true;
        return true;
    }

    private static List<Path> readPathSet(long pathSet, MemoryStack stack) {
        List<Path> paths = new ArrayList<>();
        try {
            IntBuffer count = stack.mallocInt(1);
            NFD_PathSet_GetCount(pathSet, count);
            PointerBuffer item = stack.mallocPointer(1);
            for (int i = 0; i < count.get(0); i++) {
                NFD_PathSet_GetPath(pathSet, i, item);
                paths.add(Path.of(item.getStringUTF8(0)));
                NFD_PathSet_FreePath(item.get(0));
            }
        } finally {
            NFD_PathSet_Free(pathSet);
        }
        return paths;
    }

    /**
     * Queues the selection callbacks for the render thread.
     */
    private void deliver(List<Path> paths) {
        if (onSelectAll != null) {
            results.add(() -> onSelectAll.accept(paths));
        }
        if (onSelect != null) {
            for (Path path : paths) {
                results.add(() -> onSelect.accept(path));
            }
        }
    }

    private void fail(String message) {
        log.error("File dialog failed: {}", message);
        if (onError != null) {
            results.add(() -> onError.accept(message));
        }
    }
}
