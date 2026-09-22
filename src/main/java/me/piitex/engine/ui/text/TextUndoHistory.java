package me.piitex.engine.ui.text;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Snapshot-based undo/redo stack for an editable text Element.
 * <p>
 * The owning overlay calls {@link #record} with the state as it was <em>before</em> each edit, and
 * {@link #undo}/{@link #redo} with the state as it is <em>now</em>, getting back the state to restore.
 * Keeping whole snapshots (rather than diffs) is fine for a single-line field and keeps every edit
 * kind, whether typing, paste, cut, or delete, on the same path.
 * <p>
 * Runs of the same kind of small edit (typing, backspacing, forward-deleting) collapse into one undo
 * step while they stay within {@link #COALESCE_WINDOW_NANOS} of each other and nothing has called
 * {@link #seal()} in between. The owner seals on any caret movement, so moving the caret always
 * starts a new step.
 */
public final class TextUndoHistory {
    private static final long COALESCE_WINDOW_NANOS = 1_000_000_000L;

    /**
     * What produced an edit; only the first three kinds ever merge with a previous edit.
     */
    public enum EditKind {
        TYPING,
        BACKSPACE,
        DELETE,
        /**
         * Paste, cut, replacing a selection, and similar edits. Each is always its own undo step.
         */
        OTHER
    }

    /**
     * The editable state needed to put a field back exactly as it was. {@code selectionAnchor} is -1 when nothing was selected.
     */
    public record Snapshot(String text, int cursorIndex, int selectionAnchor) {
    }

    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private int limit = 200;

    private EditKind lastKind = null;
    private long lastEditNanos = 0L;

    /**
     * Notes that an edit of {@code kind} is about to happen from state {@code before}. Merges into the previous undo step when it is a compatible continuation; otherwise pushes {@code before} as a new step. Always clears the redo stack.
     */
    public void record(Snapshot before, EditKind kind) {
        long now = System.nanoTime();
        boolean merge = kind != EditKind.OTHER
                && kind == lastKind
                && now - lastEditNanos <= COALESCE_WINDOW_NANOS
                && !undoStack.isEmpty();
        lastKind = kind;
        lastEditNanos = now;
        redoStack.clear();
        if (merge) return;

        undoStack.push(before);
        while (undoStack.size() > limit) {
            undoStack.removeLast();
        }
    }

    /**
     * Ends the current run of coalesced edits, so the next edit starts its own undo step. Call on caret movement, focus change and the like.
     */
    public void seal() {
        lastKind = null;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Pops the previous state, saving {@code current} for {@link #redo}. Null if there's nothing to undo.
     */
    public Snapshot undo(Snapshot current) {
        if (undoStack.isEmpty()) return null;
        redoStack.push(current);
        seal();
        return undoStack.pop();
    }

    /**
     * Re-applies the last undone edit, saving {@code current} for {@link #undo}. Null if there's nothing to redo.
     */
    public Snapshot redo(Snapshot current) {
        if (redoStack.isEmpty()) return null;
        undoStack.push(current);
        seal();
        return redoStack.pop();
    }

    /**
     * Forgets all history, e.g. after the app replaces the contents wholesale.
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        seal();
    }

    /**
     * Maximum number of undo steps kept; the oldest are dropped first. Defaults to 200.
     */
    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = Math.max(1, limit);
        while (undoStack.size() > this.limit) {
            undoStack.removeLast();
        }
    }
}
