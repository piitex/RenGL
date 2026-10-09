package me.piitex.engine.ui.text;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo/redo stack for an editable text Element.
 * <p>
 * The owning overlay calls {@link #record} with the state as it was <em>before</em> each edit, and
 * {@link #undo}/{@link #redo} with the state as it is <em>now</em>, getting back the state to restore. Every edit kind,
 * whether typing, paste, cut, or delete, goes through the same path.
 * <p>
 * Only the newest state in each stack is kept whole. Each older one is stored as the piece of text that differs from the
 * state after it, so a step costs about the size of the edit rather than of the whole document, and a long undo history of
 * a very large text stays small.
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

    /**
     * A stack of snapshots, newest on top. Only the top is whole; each one below is the part of its text that is not
     * shared with the snapshot above it (the text before and after the change stays in that snapshot).
     */
    private static final class PatchStack {
        private record Patch(int prefix, int suffix, String middle, int cursorIndex, int selectionAnchor) {
            /** Builds the snapshot this patch was made from, given the one that was above it. */
            Snapshot apply(Snapshot newer) {
                String text = newer.text();
                return new Snapshot(text.substring(0, prefix) + middle + text.substring(text.length() - suffix),
                        cursorIndex, selectionAnchor);
            }

            static Patch between(Snapshot older, Snapshot newer) {
                String a = older.text(), b = newer.text();
                int shortest = Math.min(a.length(), b.length());
                int prefix = 0;
                while (prefix < shortest && a.charAt(prefix) == b.charAt(prefix)) prefix++;
                int suffix = 0;
                while (suffix < shortest - prefix && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) suffix++;
                return new Patch(prefix, suffix, a.substring(prefix, a.length() - suffix), older.cursorIndex(), older.selectionAnchor());
            }
        }

        private Snapshot top;
        private final Deque<Patch> below = new ArrayDeque<>();

        void push(Snapshot snapshot) {
            if (top != null) below.push(Patch.between(top, snapshot));
            top = snapshot;
        }

        Snapshot pop() {
            Snapshot result = top;
            top = below.isEmpty() ? null : below.pop().apply(result);
            return result;
        }

        boolean isEmpty() {
            return top == null;
        }

        int size() {
            return top == null ? 0 : 1 + below.size();
        }

        void trimTo(int limit) {
            while (size() > limit && !below.isEmpty()) below.removeLast();
        }

        void clear() {
            top = null;
            below.clear();
        }

        long storedChars() {
            long total = top == null ? 0 : top.text().length();
            for (Patch patch : below) total += patch.middle().length();
            return total;
        }
    }

    private final PatchStack undoStack = new PatchStack();
    private final PatchStack redoStack = new PatchStack();
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
        undoStack.trimTo(limit);
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
     * How many characters the stored steps hold in all, to see that the history stays small.
     */
    long storedChars() {
        return undoStack.storedChars() + redoStack.storedChars();
    }

    /**
     * Re-applies the last undone edit, saving {@code current} for {@link #undo}. Null if there's nothing to redo.
     */
    public Snapshot redo(Snapshot current) {
        if (redoStack.isEmpty()) return null;
        undoStack.push(current);
        undoStack.trimTo(limit);
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
        undoStack.trimTo(this.limit);
    }
}
