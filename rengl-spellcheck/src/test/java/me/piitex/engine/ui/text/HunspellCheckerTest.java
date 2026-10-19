package me.piitex.engine.ui.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HunspellCheckerTest {
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
