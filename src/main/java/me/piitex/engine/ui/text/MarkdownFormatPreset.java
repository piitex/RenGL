package me.piitex.engine.ui.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code #}/{@code ##}/{@code ###} (up to 6 levels, same cap as CommonMark)
 * line-prefix headings, plus a blank line as the paragraph separator. Everything else is
 * a plain paragraph block. There is no inline emphasis (no {@code *bold*} or
 * {@code _italic_}); each block's whole text draws in that one block's {@link
 * TextStyle}. A heading needs a space after its {@code #}s, matching CommonMark, so a
 * bare {@code "#tag"} stays plain text.
 * <p>
 * Default sizes follow roughly the scale browsers use for h1..h6 against a 14pt body:
 * 28/24/20/17/15/14, each bold, with more space above a heading than between paragraphs.
 * Override any of it with {@link #setHeadingStyle}, {@link #setBodyStyle}, or the
 * spacing setters before calling {@link #parse}.
 */
public class MarkdownFormatPreset implements TextFormatPreset {
    private static final float[] DEFAULT_HEADING_SIZES = {28f, 24f, 20f, 17f, 15f, 14f};
    private static final int MAX_LEVEL = 6;

    private final Map<Integer, TextStyle> headingStyles = new HashMap<>();
    private TextStyle bodyStyle = TextStyle.of(14f);
    private float headingSpacingBefore = 20f;
    private float headingSpacingAfter = 6f;
    private float paragraphSpacing = 10f;

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
     * Overrides the style used for plain paragraph text. 14pt, non-bold, non-italic, inherited color by default.
     */
    public void setBodyStyle(TextStyle style) {
        this.bodyStyle = style;
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
     * Gap between two consecutive plain paragraphs.
     */
    public void setParagraphSpacing(float spacing) {
        this.paragraphSpacing = spacing;
    }

    @Override
    public List<FormattedBlock> parse(String source) {
        List<FormattedBlock> blocks = new ArrayList<>();
        if (source == null || source.isEmpty()) return blocks;

        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : source.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            int level = headingLevel(line);

            if (level > 0) {
                flushParagraph(blocks, paragraph);
                String text = line.substring(level + 1).strip();
                TextStyle style = headingStyles.getOrDefault(level, bodyStyle);
                blocks.add(new FormattedBlock(text, style, blocks.isEmpty() ? 0f : headingSpacingBefore, headingSpacingAfter));
                continue;
            }

            if (line.isBlank()) {
                flushParagraph(blocks, paragraph);
                continue;
            }

            if (!paragraph.isEmpty()) paragraph.append(' ');
            paragraph.append(line.strip());
        }
        flushParagraph(blocks, paragraph);

        return blocks;
    }

    private void flushParagraph(List<FormattedBlock> blocks, StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        blocks.add(new FormattedBlock(paragraph.toString(), bodyStyle, blocks.isEmpty() ? 0f : paragraphSpacing, 0f));
        paragraph.setLength(0);
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
