package me.piitex.engine.ui.text;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline spell checker backed by Lucene's pure-Java Hunspell implementation, reading
 * standard Hunspell {@code .aff}/{@code .dic} dictionaries. {@link #english()} loads the
 * en_US dictionary bundled in this engine's resources ({@code /dictionaries/en_US.aff}
 * and {@code .dic}); {@link #fromStreams} accepts any other language's pair.
 * <p>
 * Tokens that are not prose words are skipped: URLs, e-mail addresses, anything
 * containing a digit, and all-caps acronyms, along with anything in the user dictionary
 * (see {@link #addWord}).
 */
public class HunspellChecker implements TextChecker {
    /**
     * A run of letters, allowing apostrophes inside a word ("don't", "it's").
     */
    private static final Pattern WORD = Pattern.compile("\\p{L}+(?:['\u2019]\\p{L}+)*");

    private final Hunspell hunspell;
    private final Set<String> userWords = ConcurrentHashMap.newKeySet();

    private HunspellChecker(Hunspell hunspell) {
        this.hunspell = hunspell;
    }

    /**
     * The bundled en_US checker. Throws if the dictionary resources are missing from the classpath.
     */
    public static HunspellChecker english() throws IOException {
        try (InputStream aff = open("/dictionaries/en_US.aff");
             InputStream dic = open("/dictionaries/en_US.dic")) {
            return fromStreams(aff, dic);
        }
    }

    public static HunspellChecker fromStreams(InputStream aff, InputStream dic) throws IOException {
        try (ByteBuffersDirectory tempDir = new ByteBuffersDirectory()) {
            return new HunspellChecker(new Hunspell(new Dictionary(tempDir, "rengl-hunspell", aff, dic)));
        } catch (ParseException e) {
            throw new IOException("Malformed Hunspell dictionary: " + e.getMessage(), e);
        }
    }

    private static InputStream open(String path) throws IOException {
        InputStream in = HunspellChecker.class.getResourceAsStream(path);
        if (in == null) throw new IOException("Missing bundled dictionary resource: " + path);
        return in;
    }

    /**
     * Stops flagging {@code word}, matched case-insensitively, such as a name the user chose "Add to dictionary" on.
     */
    public void addWord(String word) {
        userWords.add(word.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<TextIssue> check(String text) {
        List<TextIssue> issues = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String word = normalize(m.group());
            if (shouldSkip(text, m.start(), m.end(), word)) continue;
            if (userWords.contains(word.toLowerCase(Locale.ROOT))) continue;
            if (!isCorrect(word)) {
                issues.add(new TextIssue(m.start(), m.end(), TextIssue.Kind.SPELLING, "Possible misspelling"));
            }
        }
        return issues;
    }

    @Override
    public List<String> suggest(String text, TextIssue issue) {
        if (issue.kind() != TextIssue.Kind.SPELLING || issue.end() > text.length()) return List.of();
        String word = normalize(text.substring(issue.start(), issue.end()));
        synchronized (hunspell) {
            return hunspell.suggest(word);
        }
    }

    /**
     * Lucene's Hunspell keeps per-instance scratch buffers, so it is not safe to call concurrently, and check() on the background thread can overlap suggest() on the render thread during a right-click. The lock is held per word, so a suggest never waits on a whole document.
     */
    private boolean isCorrect(String word) {
        synchronized (hunspell) {
            return hunspell.spell(word);
        }
    }

    /**
     * The dictionaries use a plain apostrophe; typed/pasted text often has the curly one.
     */
    private static String normalize(String word) {
        return word.replace('\u2019', '\'');
    }

    private static boolean shouldSkip(String text, int start, int end, String word) {
        if (word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT))) return true; // NASA, HTTP, ...

        // The whitespace-delimited chunk this word sits in. URLs, e-mails, and
        // identifiers like "abc123" split into several "words" under WORD, so the whole
        // chunk is judged instead.
        int chunkStart = start;
        while (chunkStart > 0 && !Character.isWhitespace(text.charAt(chunkStart - 1))) chunkStart--;
        int chunkEnd = end;
        while (chunkEnd < text.length() && !Character.isWhitespace(text.charAt(chunkEnd))) chunkEnd++;
        String chunk = text.substring(chunkStart, chunkEnd);

        if (chunk.contains("://") || chunk.contains("@") || chunk.startsWith("www.")) return true;
        for (int i = 0; i < chunk.length(); i++) {
            if (Character.isDigit(chunk.charAt(i))) return true;
        }
        return false;
    }
}
