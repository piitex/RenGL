package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the part of Markdown that documentation needs:
 * <ul>
 *   <li>{@code #} to {@code ######} line-prefix headings (up to 6 levels, same cap as CommonMark). A heading needs a
 *   space after its {@code #}s, matching CommonMark, so a bare {@code "#tag"} stays plain text.</li>
 *   <li>A blank line as the paragraph separator. Everything else is a plain paragraph block.</li>
 *   <li>Bulleted lists ({@code - item}, {@code * item} or {@code + item}) and numbered ones ({@code 1. item}). A line
 *   indented under an item carries on its text, and an item indented two spaces or more sits one level in.</li>
 *   <li>Fenced code blocks, between lines of three backticks. Their line breaks and spacing are kept, and they are drawn
 *   on a background.</li>
 *   <li>A line of three or more {@code -}, {@code *} or {@code _} ends the paragraph before it and draws nothing.</li>
 * </ul>
 * Inline marks style the text inside a block: {@code **bold**}, {@code *italic*} (or the {@code __} and {@code _}
 * spellings), {@code ***both***}, {@code `code`} in the monospace font, and {@code [text](address)} as a link that
 * opens on a click. They come out as {@link InlineRun}s on the block, drawn on top of the block's own
 * {@link TextStyle}; a backslash takes a mark literally, and one that never closes stays as written. Tables, quotes and
 * pictures are not understood and read as paragraphs.
 * <p>
 * Default sizes follow roughly the scale browsers use for h1..h6 against a 14pt body:
 * 28/24/20/17/15/14, each bold, with more space above a heading than between paragraphs.
 * Override any of it with {@link #setHeadingStyle}, {@link #setBodyStyle}, {@link #setCodeStyle}, or the
 * spacing setters before calling {@link #parse}.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class MarkdownFormatPreset implements TextFormatPreset {
    private static final float[] DEFAULT_HEADING_SIZES = {28f, 24f, 20f, 17f, 15f, 14f};
    private static final int MAX_LEVEL = 6;
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+] +(.*\\S.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)] +(.*\\S.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    private final Map<Integer, TextStyle> headingStyles = new HashMap<>();
    private TextStyle bodyStyle = TextStyle.of(14f);
    private TextStyle codeStyle = TextStyle.of(13f);
    private Paint codeBackground = new Color(0.5f, 0.5f, 0.5f, 0.18f);
    private float headingSpacingBefore = 20f;
    private float headingSpacingAfter = 6f;
    private float paragraphSpacing = 10f;
    private float listItemSpacing = 4f;
    private float listIndent = 10f;

    public MarkdownFormatPreset() {
        for (int level = 1; level <= MAX_LEVEL; level++) {
            headingStyles.put(level, new TextStyle(DEFAULT_HEADING_SIZES[level - 1], true, false, null));
        }
    }

    /**
     * Overrides the style used for a heading at {@code level} (1-6).
     */
    public void setHeadingStyle(int level, TextStyle style) {
        headingStyles.put(level, style);
    }

    /**
     * Overrides the style used for plain paragraph text and list items. 14pt, non-bold, non-italic, inherited color by default.
     */
    public void setBodyStyle(TextStyle style) {
        this.bodyStyle = style;
    }

    /**
     * Overrides the style used for fenced code. 13pt, non-bold, non-italic, inherited color by default.
     */
    public void setCodeStyle(TextStyle style) {
        this.codeStyle = style;
    }

    /**
     * What a fenced code block is drawn on, or null for none. A faint grey that suits a light or a dark page by default.
     */
    public void setCodeBackground(Paint background) {
        this.codeBackground = background;
    }

    /**
     * Extra gap above every heading (skipped for a heading that opens the document).
     */
    public void setHeadingSpacingBefore(float spacing) {
        this.headingSpacingBefore = spacing;
    }

    /**
     * Gap below every heading, before the paragraph that follows it.
     */
    public void setHeadingSpacingAfter(float spacing) {
        this.headingSpacingAfter = spacing;
    }

    /**
     * Gap between two consecutive plain paragraphs, and above and below a list or a code block.
     */
    public void setParagraphSpacing(float spacing) {
        this.paragraphSpacing = spacing;
    }

    /**
     * Gap between two list items.
     */
    public void setListItemSpacing(float spacing) {
        this.listItemSpacing = spacing;
    }

    @Override
    public List<FormattedBlock> parse(String source) {
        List<FormattedBlock> blocks = new ArrayList<>();
        if (source == null || source.isEmpty()) return blocks;

        Parse parse = new Parse(blocks);
        boolean fenced = false;
        List<String> code = new ArrayList<>();

        for (String rawLine : source.split("\r?\n", -1)) {
            String line = rawLine.stripTrailing();

            if (line.strip().startsWith("```")) {
                if (fenced) {
                    parse.flushCode(code);
                    code.clear();
                } else {
                    parse.flushAll();
                }
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                code.add(line);
                continue;
            }

            int level = headingLevel(line);
            if (level > 0) {
                parse.flushAll();
                MarkdownInline.Parsed text = MarkdownInline.parse(line.substring(level + 1).strip());
                TextStyle style = headingStyles.getOrDefault(level, bodyStyle);
                parse.add(new FormattedBlock(text.text(), style, blocks.isEmpty() ? 0f : headingSpacingBefore, headingSpacingAfter,
                        0f, "", null, text.runs()));
                parse.afterList = false;
                continue;
            }

            if (line.isBlank() || RULE.matcher(line).matches()) {
                parse.flushAll();
                continue;
            }

            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            if (bullet.matches()) {
                parse.startItem("•", bullet.group(1), bullet.group(2));
            } else if (numbered.matches()) {
                parse.startItem(numbered.group(2) + ".", numbered.group(1), numbered.group(3));
            } else if (parse.item != null && Character.isWhitespace(line.charAt(0))) {
                parse.item.append(' ').append(line.strip());
            } else {
                parse.flushItem();
                if (!parse.paragraph.isEmpty()) parse.paragraph.append(' ');
                parse.paragraph.append(line.strip());
            }
        }
        if (fenced) parse.flushCode(code);
        parse.flushAll();

        return blocks;
    }

    /** The state of one {@link #parse}: what is open, and what came last, for the spacing. */
    private final class Parse {
        final List<FormattedBlock> blocks;
        final StringBuilder paragraph = new StringBuilder();
        StringBuilder item;
        String marker = "";
        float indent;
        boolean afterList;

        Parse(List<FormattedBlock> blocks) {
            this.blocks = blocks;
        }

        void add(FormattedBlock block) {
            blocks.add(block);
        }

        void startItem(String marker, String leading, String text) {
            flushParagraph();
            flushItem();
            int depth = Math.min(3, leading.replace("\t", "  ").length() / 2);
            this.item = new StringBuilder(text.strip());
            this.marker = marker;
            this.indent = listIndent + depth * 16f;
        }

        void flushItem() {
            if (item == null) return;
            float before = blocks.isEmpty() ? 0f : afterList ? listItemSpacing : paragraphSpacing;
            MarkdownInline.Parsed text = MarkdownInline.parse(item.toString());
            add(new FormattedBlock(text.text(), bodyStyle, before, 0f, indent, marker, null, text.runs()));
            afterList = true;
            item = null;
        }

        void flushParagraph() {
            if (paragraph.isEmpty()) return;
            MarkdownInline.Parsed text = MarkdownInline.parse(paragraph.toString());
            add(new FormattedBlock(text.text(), bodyStyle, blocks.isEmpty() ? 0f : paragraphSpacing, 0f, 0f, "", null, text.runs()));
            afterList = false;
            paragraph.setLength(0);
        }

        void flushCode(List<String> lines) {
            flushAll();
            while (!lines.isEmpty() && lines.getFirst().isBlank()) lines.removeFirst();
            while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
            if (lines.isEmpty()) return;
            add(new FormattedBlock(String.join("\n", lines), codeStyle, blocks.isEmpty() ? 0f : paragraphSpacing, 0f, 0f, "", codeBackground));
            afterList = false;
        }

        void flushAll() {
            flushParagraph();
            flushItem();
        }
    }

    /**
     * Takes the inline marks out of {@code text}: {@code **bold**}, {@code *italic*}, {@code `code`} and links keep what they wrap.
     */
    static String inline(String text) {
        return MarkdownInline.parse(text).text();
    }

    /**
     * How many leading {@code #} characters {@code line} has (capped at 6), or 0 if it isn't a valid heading prefix (no trailing space, or no text after it).
     */
    private static int headingLevel(String line) {
        int hashes = 0;
        while (hashes < line.length() && hashes < MAX_LEVEL && line.charAt(hashes) == '#') hashes++;
        if (hashes == 0 || hashes >= line.length() || line.charAt(hashes) != ' ') return 0;
        return hashes;
    }
}
