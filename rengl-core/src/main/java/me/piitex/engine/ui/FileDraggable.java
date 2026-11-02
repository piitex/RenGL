package me.piitex.engine.ui;

import me.piitex.engine.input.FileDrag;

/**
 * Implemented by a {@link Draggable} whose drag can carry on as a drag of files out of the window. When the pointer,
 * still holding the button that began the drag, leaves the window,
 * {@link me.piitex.engine.input.InputProcessor} asks {@link #fileDragOut()}. If that gives a source, the Element's own
 * drag is cancelled ({@link Draggable#onDragCancel}) and the operating system's drag takes over, so the files can be
 * dropped on Explorer or the desktop. See {@link me.piitex.engine.Window#startFileDrag}.
 */
public interface FileDraggable {
    /**
     * The pointer left the window in the middle of this Element's drag. What it carries, or null to leave the drag as
     * an ordinary one that ends when the button comes up.
     */
    FileDrag.Source fileDragOut();
}
