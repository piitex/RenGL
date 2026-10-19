package me.piitex.engine.ui.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds spelling/grammar problems in a {@link me.piitex.engine.ui.overlays.RichTextAreaOverlay}'s
 * text. This is an interface so the engine core does not depend on any one checking
 * library. {@code HunspellChecker} is the bundled offline implementation, and a grammar
 * checker can be dropped in the same way.
 * <p>
 * Always called off the render thread (debounced after edits), so it may be slow, but it
 * must be safe to call from a background thread. It is called with the whole text, never a
 * fragment, unless it says {@link #checksFragments() it can} check one.
 */
public interface TextChecker {
    /**
     * Returns every issue in {@code text}, in any order; never null.
     */
    List<TextIssue> check(String text);

    /**
     * Replacement suggestions for the word at {@code issue} in {@code text}, best first. Empty by default.
     */
    default List<String> suggest(String text, TextIssue issue) {
        return List.of();
    }

    /**
     * Whether {@link #check} judges each issue from the text around it alone, so it can be given just the part of a
     * text that was edited. True for a checker that looks at words one by one, such as {@code HunspellChecker}: after
     * an edit the overlay then checks only the words that changed, which keeps a big document cheap. A checker whose
     * answer for one place depends on text far away, such as a grammar checker reading whole sentences, leaves this
     * false (the default) and always gets the whole text. A fragment starts and ends at whitespace or at an end of
     * the text.
     */
    default boolean checksFragments() {
        return false;
    }

    /**
     * Whether {@link #addWord} does anything, which makes the correction list offer "Add to dictionary". False by
     * default.
     */
    default boolean canAddWords() {
        return false;
    }

    /**
     * Teaches the checker a word it should stop flagging, as when the user picks "Add to dictionary". Does nothing by
     * default. Called on the render thread, so it may run while {@link #check} runs on the background one.
     */
    default void addWord(String word) {
    }

    /**
     * The issues in {@code newText}, found by checking only what changed since {@code oldText}, whose issues were
     * {@code oldIssues}. Issues outside the edit are kept, moved to follow the text, and the edited words are checked
     * again. It falls back to checking all of {@code newText} when {@code checker} does not
     * {@link #checksFragments() check fragments}, when there is no old text, or when most of the text changed.
     */
    static List<TextIssue> checkChanged(TextChecker checker, String oldText, List<TextIssue> oldIssues, String newText) {
        if (!checker.checksFragments() || oldText.isEmpty() || newText.isEmpty()) return checker.check(newText);
        int oldLength = oldText.length(), newLength = newText.length();
        int shortest = Math.min(oldLength, newLength);
        int prefix = 0;
        while (prefix < shortest && oldText.charAt(prefix) == newText.charAt(prefix)) prefix++;
        if (prefix == oldLength && prefix == newLength) return new ArrayList<>(oldIssues);
        int suffix = 0;
        while (suffix < shortest - prefix && oldText.charAt(oldLength - 1 - suffix) == newText.charAt(newLength - 1 - suffix)) suffix++;

        // The edit, widened to whole whitespace-separated words on both sides (the same chunk in either text).
        int start = prefix;
        while (start > 0 && !Character.isWhitespace(newText.charAt(start - 1))) start--;
        int end = newLength - suffix;
        while (end < newLength && !Character.isWhitespace(newText.charAt(end))) end++;
        if (end - start >= newLength / 2) return checker.check(newText);

        int shift = newLength - oldLength;
        int oldEnd = end - shift;
        List<TextIssue> issues = new ArrayList<>();
        for (TextIssue issue : oldIssues) {
            if (issue.end() <= start) {
                issues.add(issue);
            } else if (issue.start() >= oldEnd) {
                issues.add(new TextIssue(issue.start() + shift, issue.end() + shift, issue.kind(), issue.message()));
            }
        }
        for (TextIssue issue : checker.check(newText.substring(start, end))) {
            issues.add(new TextIssue(issue.start() + start, issue.end() + start, issue.kind(), issue.message()));
        }
        return issues;
    }
}
