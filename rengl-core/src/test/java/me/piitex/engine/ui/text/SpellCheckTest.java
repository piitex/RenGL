package me.piitex.engine.ui.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SpellCheckTest {
    /** Flags every "bad" and remembers how much text it was asked to look at. */
    private static final class BadWordChecker implements TextChecker {
        final boolean fragments;
        int checkedLength;

        BadWordChecker(boolean fragments) {
            this.fragments = fragments;
        }

        @Override
        public List<TextIssue> check(String text) {
            checkedLength += text.length();
            List<TextIssue> issues = new ArrayList<>();
            Matcher m = Pattern.compile("bad").matcher(text);
            while (m.find()) {
                // Whole words only, as a word-by-word checker would see them.
                boolean startsWord = m.start() == 0 || Character.isWhitespace(text.charAt(m.start() - 1));
                boolean endsWord = m.end() == text.length() || Character.isWhitespace(text.charAt(m.end()));
                if (startsWord && endsWord) issues.add(new TextIssue(m.start(), m.end(), TextIssue.Kind.SPELLING, "bad"));
            }
            return issues;
        }

        @Override
        public boolean checksFragments() {
            return fragments;
        }
    }

    private static List<int[]> spans(List<TextIssue> issues) {
        return issues.stream().map(i -> new int[]{i.start(), i.end()}).sorted((a, b) -> Integer.compare(a[0], b[0])).toList();
    }

    private static void assertSameIssues(List<TextIssue> expected, List<TextIssue> actual) {
        List<int[]> e = spans(expected), a = spans(actual);
        assertEquals(e.size(), a.size(), "issue count");
        for (int i = 0; i < e.size(); i++) assertArrayEquals(e.get(i), a.get(i));
    }

    @Test
    void anEditChecksOnlyTheWordsAroundIt() {
        BadWordChecker checker = new BadWordChecker(true);
        String before = "good bad fine " + "ok ".repeat(300) + "bad end";
        List<TextIssue> issues = checker.check(before);
        checker.checkedLength = 0;

        String after = before.replace("fine", "fine bad");
        List<TextIssue> found = TextChecker.checkChanged(checker, before, issues, after);

        assertSameIssues(new BadWordChecker(true).check(after), found);
        assertTrue(checker.checkedLength < 30, "only the edited word was looked at, not " + checker.checkedLength + " characters");
    }

    @Test
    void issuesAfterTheEditFollowTheText() {
        BadWordChecker checker = new BadWordChecker(true);
        String before = "one two " + "x ".repeat(100) + "bad";
        String after = "ONE TWO THREE " + before.substring("one two ".length());
        List<TextIssue> found = TextChecker.checkChanged(checker, before, checker.check(before), after);
        assertSameIssues(new BadWordChecker(true).check(after), found);
        assertEquals(after.length() - 3, found.get(0).start());
    }

    @Test
    void aCheckerThatNeedsTheWholeTextAlwaysGetsIt() {
        BadWordChecker checker = new BadWordChecker(false);
        String before = "bad " + "ok ".repeat(100);
        List<TextIssue> issues = checker.check(before);
        checker.checkedLength = 0;
        TextChecker.checkChanged(checker, before, issues, before + "more");
        assertEquals(before.length() + 4, checker.checkedLength);
    }

    @Test
    void unchangedTextKeepsItsIssuesWithoutChecking() {
        BadWordChecker checker = new BadWordChecker(true);
        String text = "a bad day";
        List<TextIssue> issues = checker.check(text);
        checker.checkedLength = 0;
        assertSameIssues(issues, TextChecker.checkChanged(checker, text, issues, text));
        assertEquals(0, checker.checkedLength);
    }

    @Test
    void randomEditsAlwaysAgreeWithCheckingEverything() {
        Random random = new Random(7);
        String[] pieces = {"bad", "good", " ", " ", "\n", "ba", "d", "badly", "x"};
        BadWordChecker checker = new BadWordChecker(true);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 80; i++) text.append(pieces[random.nextInt(pieces.length)]);
        String old = text.toString();
        List<TextIssue> issues = checker.check(old);
        for (int round = 0; round < 400; round++) {
            StringBuilder edited = new StringBuilder(old);
            int at = random.nextInt(edited.length() + 1);
            int cut = random.nextInt(Math.min(6, edited.length() - at + 1));
            edited.delete(at, at + cut);
            edited.insert(at, pieces[random.nextInt(pieces.length)]);
            String now = edited.toString();
            issues = TextChecker.checkChanged(checker, old, issues, now);
            assertSameIssues(new BadWordChecker(true).check(now), issues);
            old = now;
        }
    }

    @Test
    void theBundledCheckerFlagsAWordUntilItIsAdded() throws IOException {
        HunspellChecker checker = HunspellChecker.english();
        String text = "The quick brown fox jumps over the lazy xyzzyq today";
        assertEquals(1, checker.check(text).size());
        assertTrue(checker.canAddWords());
        assertTrue(checker.checksFragments());
        checker.addWord("Xyzzyq");
        assertEquals(0, checker.check(text).size());
    }

    @Test
    void theUserDictionaryCanLiveInAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("words.txt");
        HunspellChecker first = HunspellChecker.english().useDictionaryFile(file);
        first.addWord("Xyzzyq");
        first.addWord("Plugh");
        first.addWord("xyzzyq"); // already there
        assertEquals(List.of("plugh", "xyzzyq"), Files.readAllLines(file).stream().sorted().toList());

        HunspellChecker second = HunspellChecker.english().useDictionaryFile(file);
        assertEquals(Set.of("plugh", "xyzzyq"), second.getWords());
        assertEquals(0, second.check("a xyzzyq and a plugh").size());

        second.removeWord("plugh");
        assertEquals(List.of("xyzzyq"), Files.readAllLines(file));
        assertEquals(1, second.check("a plugh").size());
    }

    @Test
    void aMissingDictionaryFileIsFineAndCreatedOnTheFirstWord(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("none.txt");
        HunspellChecker checker = HunspellChecker.english().useDictionaryFile(file);
        assertTrue(checker.getWords().isEmpty());
        assertFalse(Files.exists(file));
        checker.addWord("Quuxly");
        assertTrue(Files.exists(file));
    }
}
