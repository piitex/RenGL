package me.piitex.engine.ui.text;

import me.piitex.engine.ui.text.TextUndoHistory.EditKind;
import me.piitex.engine.ui.text.TextUndoHistory.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TextUndoHistoryTest {
    @Test
    void undoAndRedoWalkBackAndForthThroughTheEdits() {
        TextUndoHistory history = new TextUndoHistory();
        history.record(new Snapshot("", 0, -1), EditKind.OTHER);
        history.record(new Snapshot("one", 3, -1), EditKind.OTHER);
        history.record(new Snapshot("one two", 7, 2), EditKind.OTHER);
        Snapshot current = new Snapshot("one two three", 13, -1);

        Snapshot back = history.undo(current);
        assertEquals(new Snapshot("one two", 7, 2), back);
        back = history.undo(back);
        assertEquals(new Snapshot("one", 3, -1), back);
        back = history.undo(back);
        assertEquals(new Snapshot("", 0, -1), back);
        assertNull(history.undo(back));
        Snapshot forward = history.redo(back);
        assertEquals(new Snapshot("one", 3, -1), forward);
        assertEquals(new Snapshot("one two", 7, 2), history.redo(forward));
    }

    @Test
    void aNewEditClearsWhatCouldHaveBeenRedone() {
        TextUndoHistory history = new TextUndoHistory();
        history.record(new Snapshot("a", 1, -1), EditKind.OTHER);
        Snapshot back = history.undo(new Snapshot("ab", 2, -1));
        assertTrue(history.canRedo());
        history.record(back, EditKind.OTHER);
        assertFalse(history.canRedo());
    }

    @Test
    void theLimitDropsTheOldestSteps() {
        TextUndoHistory history = new TextUndoHistory();
        history.setLimit(3);
        for (int i = 0; i < 10; i++) history.record(new Snapshot("t" + i, i, -1), EditKind.OTHER);
        Snapshot s = new Snapshot("now", 0, -1);
        int steps = 0;
        while (history.canUndo()) {
            s = history.undo(s);
            steps++;
        }
        assertEquals(3, steps);
        assertEquals("t7", s.text());
    }

    @Test
    void anyMixOfEditsUndoesAndRedoesLikeStoringEveryWholeText() {
        Random random = new Random(11);
        for (int round = 0; round < 40; round++) {
            TextUndoHistory history = new TextUndoHistory();
            history.setLimit(1 + random.nextInt(30));
            Deque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>(); // the reference: whole texts
            int limit = history.getLimit();
            Snapshot current = new Snapshot("start", 5, -1);
            for (int step = 0; step < 300; step++) {
                int action = random.nextInt(5);
                if (action <= 2) { // an edit
                    history.record(current, EditKind.OTHER);
                    undo.push(current);
                    while (undo.size() > limit) undo.removeLast();
                    redo.clear();
                    current = edit(random, current);
                } else if (action == 3) {
                    Snapshot got = history.undo(current);
                    if (undo.isEmpty()) {
                        assertNull(got);
                    } else {
                        redo.push(current);
                        current = undo.pop();
                        assertEquals(current, got, "undo, round " + round + " step " + step);
                    }
                } else {
                    Snapshot got = history.redo(current);
                    if (redo.isEmpty()) {
                        assertNull(got);
                    } else {
                        undo.push(current);
                        while (undo.size() > limit) undo.removeLast();
                        current = redo.pop();
                        assertEquals(current, got, "redo, round " + round + " step " + step);
                    }
                }
                assertEquals(!undo.isEmpty(), history.canUndo());
                assertEquals(!redo.isEmpty(), history.canRedo());
            }
        }
    }

    private static Snapshot edit(Random random, Snapshot s) {
        StringBuilder text = new StringBuilder(s.text());
        int at = random.nextInt(text.length() + 1);
        int cut = random.nextInt(Math.min(4, text.length() - at + 1));
        text.delete(at, at + cut);
        text.insert(at, "xyz".substring(0, random.nextInt(4)));
        return new Snapshot(text.toString(), at, random.nextBoolean() ? -1 : random.nextInt(5));
    }

    @Test
    void aLongHistoryOfAHugeTextStaysTheSizeOfTheTextPlusTheEdits() {
        TextUndoHistory history = new TextUndoHistory();
        String big = "lorem ipsum ".repeat(100_000); // 1.2 million characters
        String text = big;
        for (int i = 0; i < 200; i++) {
            history.record(new Snapshot(text, i, -1), EditKind.OTHER);
            text = text.substring(0, 50 + i) + "!" + text.substring(50 + i);
        }
        // Stored whole, this would be 200 x 1.2 million characters.
        assertTrue(history.storedChars() < big.length() + 10_000, "stored " + history.storedChars());
        Snapshot back = history.undo(new Snapshot(text, 0, -1));
        assertEquals(big.length() + 199, back.text().length());
    }
}
