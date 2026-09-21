# Spell checking

[Documentation](../README.md) › [Text editing](README.md)

[`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md) can underline misspelled words and offer
corrections, **entirely offline**. There is no HTTP API, no account, and nothing leaves the machine. The
dictionary is bundled inside the engine's jar.

```java
RichTextAreaOverlay area = new RichTextAreaOverlay(20, 20, 400, 200);
area.setChecker(HunspellChecker.english());
root.addElement(area);
```

Type a misspelled word (for example `yuo`), pause for about half a second, and it gets a red squiggle. Right-click it to
see corrections.

## How it works

| Piece | Where | Job |
|---|---|---|
| `TextChecker` | `me.piitex.engine.ui.text` | The interface: text in, list of issues out |
| `TextIssue` | same | One problem: `start`, `end`, `kind`, `message` |
| `HunspellChecker` | same | The bundled offline spell checker |
| [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md) | `overlays` | Debounces, runs the checker off-thread, draws underlines, shows suggestions |

The bundled checker uses **Lucene's pure-Java Hunspell engine** (`lucene-analysis-common`) and the standard
Hunspell `en_US` dictionary (derived from SCOWL, its license is bundled next to it at
`src/main/resources/dictionaries/en_US-LICENSE.txt`). There are no native libraries to ship.

### The pipeline

1. You edit the text.
2. After **400 ms** with no further edits (`RichTextAreaOverlay.CHECK_DEBOUNCE_MS`), the whole text is handed to
   the checker on a single shared background thread (`rengl-text-checker`). The UI never waits for it.
3. When it finishes, the result is stored together with the exact text it was computed for.
4. Each frame, the overlay draws an underline only where the word under it still matches what was checked. After
   an edit shifts things around, the affected underlines disappear until the next check completes rather than
   landing on the wrong word.
5. **Right-click** a flagged word to open a popup with up to 6 suggestions. The suggestions are computed at that
   moment. Choosing one replaces the word and puts the caret after it.

Underline colors: **red** for `Kind.SPELLING`, **blue** for `Kind.GRAMMAR`. They are not configurable.

## What the Hunspell checker checks

A "word" is a run of Unicode letters, with apostrophes allowed inside (`don't`, `it's`). Curly apostrophes are
normalized to straight ones before lookup.

It **skips** things that are not prose words:

- Words in **ALL CAPS** longer than one letter (`NASA`, `HTTP`).
- Anything in a whitespace-delimited chunk containing a **digit** (`abc123`, `3rd`), a **URL** (`://`,
  `www.`), or an **e-mail** address (`@`).
- Words on your **user dictionary** (see below).

Other notes:

- Hyphenated words are checked as separate words (`well-known` is `well` and `known`).
- Capitalization follows Hunspell's own rules for the dictionary in use.
- Only spelling is checked. It does not find grammar or word-choice errors.

## User dictionary

```java
HunspellChecker checker = HunspellChecker.english();
checker.addWord("RenGL"); // case-insensitive
checker.addWord("Skiko");
area.setChecker(checker);
```

`addWord` is **in-memory only**. Persist your own list and reload it at startup:

```java
Path file = Path.of("user-dictionary.txt");
if (Files.exists(file)) {
    for (String word : Files.readAllLines(file)) {
        checker.addWord(word);
    }
}
```

New words take effect at the **next check**, which only happens when the text changes. To recheck immediately after
adding a word, hand the same checker back to the overlay. That clears the current underlines and schedules a fresh
check:

```java
checker.addWord("Skiko");
area.setChecker(checker); // underlines briefly clear, then reappear minus "Skiko"
```

The right-click popup has no "Add to dictionary" row. To build one, call `addWord` and re-apply the checker as shown
above, for example from a button next to the text area.

## Other languages

`HunspellChecker.fromStreams(aff, dic)` accepts any pair of Hunspell dictionary files, the `.aff` (affix rules) and
`.dic` (word list) that LibreOffice, Firefox and most Linux distributions ship.

```java
try (InputStream aff = Files.newInputStream(Path.of("dictionaries/fr.aff"));
     InputStream dic = Files.newInputStream(Path.of("dictionaries/fr.dic"))) {
    area.setChecker(HunspellChecker.fromStreams(aff, dic));
}
```

To **bundle** another language in your own jar, put the two files on the classpath and read them with
`getResourceAsStream`:

```java
try (InputStream aff = MyApp.class.getResourceAsStream("/dictionaries/de_DE.aff");
     InputStream dic = MyApp.class.getResourceAsStream("/dictionaries/de_DE.dic")) {
    area.setChecker(HunspellChecker.fromStreams(aff, dic));
}
```

- Both streams are fully read while constructing the checker, so a `try`-with-resources is correct.
- `fromStreams` throws `IOException` for unreadable or malformed dictionaries.
- One checker is one language. A [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md) has one checker at a time. Swapping languages is another
  `setChecker` call.
- Check the license of every dictionary you ship. Dictionaries are usually under permissive open-source licenses
  but each has its own terms.

## Writing your own checker

`TextChecker` is a small interface:

```java nocompile
public interface TextChecker {
    List<TextIssue> check(String text); // whole text in, issues out

    default List<String> suggest(String text, TextIssue issue) { // optional; empty by default
        return List.of();
    }
}

public record TextIssue(int start, int end, Kind kind, String message) {
    public enum Kind { SPELLING, GRAMMAR }
}
```

Rules for implementations:

- `check` is always called with the **whole text**, on a **background thread**. It may be slow, but it must be safe
  to call off the render thread.
- **One thread is shared by every overlay in the process.** A very slow checker delays checks for all of them.
- `[start, end)` are indexes into the text string (like `String.substring`). Issues may be returned in any order.
- Never return `null`. Return an empty list for "no issues".
- An unchecked exception from `check` is caught and treated as "no issues", so a broken checker cannot crash the
  UI. It will not tell you it failed either, so log inside your own `try`.
- `suggest` is called on the **render thread**, when the user right-clicks. Keep it fast. It may run at the same time
  as `check` on the background thread, so synchronize any shared state (`HunspellChecker` does).
- `TextIssue.message()` is not shown anywhere yet. It is available for your own use.

### Example: a repeated-word (grammar) checker

This flags "the the" style repeats with the blue grammar underline. It underlines the second occurrence.

```java
public class RepeatedWordChecker implements TextChecker {
    private static final Pattern REPEAT = Pattern.compile("\\b(\\w+)\\s+\\1\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public List<TextIssue> check(String text) {
        List<TextIssue> issues = new ArrayList<>();
        Matcher m = REPEAT.matcher(text);
        while (m.find()) {
            int secondStart = m.end() - m.group(1).length();
            issues.add(new TextIssue(secondStart, m.end(), TextIssue.Kind.GRAMMAR, "Repeated word"));
        }
        return issues;
    }
    // No suggest() override: the default returns no suggestions, so right-click shows "No suggestions".
}
```

### Combining checkers

The overlay holds a single checker. To get spelling **and** grammar, combine them in a wrapper:

```java
public class CompositeChecker implements TextChecker {
    private final List<TextChecker> checkers;

    public CompositeChecker(TextChecker... checkers) {
        this.checkers = List.of(checkers);
    }

    @Override
    public List<TextIssue> check(String text) {
        List<TextIssue> all = new ArrayList<>();
        for (TextChecker checker : checkers) {
            all.addAll(checker.check(text));
        }
        return all;
    }

    @Override
    public List<String> suggest(String text, TextIssue issue) {
        for (TextChecker checker : checkers) {
            List<String> suggestions = checker.suggest(text, issue);
            if (!suggestions.isEmpty()) return suggestions;
        }
        return List.of();
    }
}
```

```java
area.setChecker(new CompositeChecker(HunspellChecker.english(), new RepeatedWordChecker()));
```

### Real grammar checking

The engine ships no grammar engine. Full rule-based grammar checking is what
[LanguageTool](https://languagetool.org) does (open source, and it works offline when embedded as a library). It
is a large dependency (tens of megabytes plus its own dictionaries), which is why it is not bundled. A
`TextChecker` adapter around it looks like this. It is a sketch and is not compiled against a specific
LanguageTool version, so verify the calls against the version you add:

```java nocompile
public class LanguageToolChecker implements TextChecker {
    private final JLanguageTool tool = new JLanguageTool(new AmericanEnglish());

    @Override
    public List<TextIssue> check(String text) {
        try {
            List<TextIssue> issues = new ArrayList<>();
            for (RuleMatch match : tool.check(text)) {
                boolean spelling = match.getRule().isDictionaryBasedSpellingRule();
                issues.add(new TextIssue(match.getFromPos(), match.getToPos(),
                        spelling ? TextIssue.Kind.SPELLING : TextIssue.Kind.GRAMMAR, match.getMessage()));
            }
            return issues;
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public List<String> suggest(String text, TextIssue issue) {
        try {
            for (RuleMatch match : tool.check(text)) {
                if (match.getFromPos() == issue.start() && match.getToPos() == issue.end()) {
                    return match.getSuggestedReplacements();
                }
            }
        } catch (IOException ignored) {
        }
        return List.of();
    }
}
```

`JLanguageTool` is not guaranteed to be thread-safe either. Guard it with a lock if `check` and `suggest` can overlap.

## Turning it off

```java
area.setChecker(null);
```

All underlines vanish and no further checks are scheduled.

## Method reference

| Member | Description |
|---|---|
| `RichTextAreaOverlay.setChecker(TextChecker)` / `getChecker()` | Enable, replace, or disable (`null`) checking |
| `RichTextAreaOverlay.CHECK_DEBOUNCE_MS` | `400` |
| `HunspellChecker.english()` | The bundled en_US checker. Throws `IOException` if the resources are missing |
| `HunspellChecker.fromStreams(aff, dic)` | Any Hunspell dictionary pair |
| `HunspellChecker.addWord(String)` | Add to the (in-memory) user dictionary |
| `TextChecker.check(String)` / `suggest(String, TextIssue)` | The interface to implement |
| `TextIssue(start, end, kind, message)` | An issue; `Kind` is `SPELLING` or `GRAMMAR` |
