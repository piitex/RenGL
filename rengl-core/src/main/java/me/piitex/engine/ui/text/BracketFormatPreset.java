package me.piitex.engine.ui.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code [heading=N]...[/heading]} tags and treats blank-line-separated text
 * outside them as paragraph blocks. {@code N} is not capped the way {@link
 * MarkdownFormatPreset}'s {@code #} levels are; any level past 6 falls back to the
 * level-6 style rather than growing unbounded. This is the syntax some existing content,
 * such as a document authored for a different UI toolkit's bracket-tag convention, may
 * already use verbatim, so it can be dropped in unmodified.
 * <p>
 * Same default sizing/spacing scheme as {@link MarkdownFormatPreset}: 28/24/20/17/15/14,
 * each bold, more space above a heading than between paragraphs. Override with {@link
 * #setHeadingStyle}, {@link #setBodyStyle}, or the spacing setters before calling
 * {@link #parse}.
 */
public class BracketFormatPreset implements TextFormatPreset {
    private static final Pattern HEADING = Pattern.compile("\\[heading=(\\d+)]\\s*(.*?)\\s*\\[/heading]", Pattern.DOTALL);
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\n\\s*\n");
    private static final Pattern INNER_NEWLINE = Pattern.compile("\\s*\n\\s*");
    private static final float[] DEFAULT_HEADING_SIZES = {28f, 24f, 20f, 17f, 15f, 14f};
    private static final int MAX_LEVEL = 6;

    private final Map<Integer, TextStyle> headingStyles = new HashMap<>();
    private TextStyle bodyStyle = TextStyle.of(14f);
    private float headingSpacingBefore = 20f;
    private float headingSpacingAfter = 6f;
    private float paragraphSpacing = 10f;

    public BracketFormatPreset() {
        for (int level = 1; level <= MAX_LEVEL; level++) {
            headingStyles.put(level, new TextStyle(DEFAULT_HEADING_SIZES[level - 1], true, false, null));
        }
    }

    /**
     * Overrides the style used for a {@code [heading=level]} tag. Levels past 6 use the level-6 style unless overridden here directly.
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

        Matcher matcher = HEADING.matcher(source);
        int cursor = 0;

        while (matcher.find()) {
            addParagraphs(blocks, source.substring(cursor, matcher.start()));
            int level = Integer.parseInt(matcher.group(1));
            TextStyle style = headingStyles.getOrDefault(level, headingStyles.get(MAX_LEVEL));
            String text = INNER_NEWLINE.matcher(matcher.group(2).strip()).replaceAll(" ");
            blocks.add(new FormattedBlock(text, style, blocks.isEmpty() ? 0f : headingSpacingBefore, headingSpacingAfter));
            cursor = matcher.end();
        }
        addParagraphs(blocks, source.substring(cursor));

        return blocks;
    }

    private void addParagraphs(List<FormattedBlock> blocks, String text) {
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            String collapsed = INNER_NEWLINE.matcher(paragraph.strip()).replaceAll(" ");
            if (collapsed.isEmpty()) continue;
            blocks.add(new FormattedBlock(collapsed, bodyStyle, blocks.isEmpty() ? 0f : paragraphSpacing, 0f));
        }
    }
}
