package me.piitex.engine.ui.text;

import java.util.List;

/**
 * Finds spelling/grammar problems in a {@link me.piitex.engine.ui.overlays.RichTextAreaOverlay}'s
 * text. This is an interface so the engine core does not depend on any one checking
 * library. {@link HunspellChecker} is the bundled offline implementation, and a grammar
 * checker can be dropped in the same way.
 * <p>
 * Always called off the render thread (debounced after edits), so it may be slow, but it
 * must be safe to call from a background thread. It is only ever called with the whole
 * text, never a fragment.
 */
public interface TextChecker {
    /** Returns every issue in {@code text}, in any order; never null. */
    List<TextIssue> check(String text);

    /** Replacement suggestions for the word at {@code issue} in {@code text}, best first. Empty by default. */
    default List<String> suggest(String text, TextIssue issue) {
        return List.of();
    }
}
